package com.engine.gateway

import com.engine.reference.LatencyHistogram
import com.engine.reference.ShardSpec
import io.aeron.Aeron
import io.aeron.Publication
import io.aeron.ControlledFragmentAssembler
import io.aeron.cluster.client.AeronCluster
import io.aeron.cluster.client.EgressListener
import io.aeron.logbuffer.ControlledFragmentHandler
import org.agrona.DirectBuffer
import org.agrona.concurrent.BusySpinIdleStrategy
import org.agrona.concurrent.ShutdownSignalBarrier
import org.agrona.concurrent.SystemNanoClock
import java.io.File
import java.util.Properties

/**
 * Order entry gateway: a cluster client on one side, a plain Aeron subscription and publication
 * facing upstream protocol gateways on the other.
 *
 * Both legs speak binary SBE. Anything that needs to speak FIX or a proprietary session protocol
 * is a separate process upstream of this one (Design.md §1).
 */
fun main(args: Array<String>) {
    val config = try {
        GatewayConfig.load(args.firstOrNull())
    } catch (e: IllegalArgumentException) {
        System.err.println("gateway: ${e.message}")
        return
    } catch (e: IllegalStateException) {
        System.err.println("gateway: ${e.message}")
        return
    }

    println(
        "gateway: shard=${config.shard.shardId} fingerprint=${config.shard.fingerprint()} " +
            "securities=${config.shard.securities.map { it.symbol }} " +
            "in=${config.clientInboundChannel}:${config.clientInboundStreamId} " +
            "out=${config.clientOutboundChannel}:${config.clientOutboundStreamId}"
    )

    val aeronContext = Aeron.Context()
    config.aeronDirectoryName?.let(aeronContext::aeronDirectoryName)

    val aeron = try {
        Aeron.connect(aeronContext)
    } catch (e: io.aeron.exceptions.DriverTimeoutException) {
        System.err.println("gateway: no Aeron media driver found (${e.message}).")
        return
    }

    aeron.use {
        val inbound = aeron.addSubscription(config.clientInboundChannel, config.clientInboundStreamId)
        val outbound = aeron.addPublication(config.clientOutboundChannel, config.clientOutboundStreamId)

        lateinit var service: GatewayService

        val egressListener = EgressListener { _, _, buffer, offset, length, _ ->
            service.onExecutionReport(buffer, offset, length)
        }

        val clusterContext = AeronCluster.Context()
            .aeron(aeron)
            .ownsAeronClient(false)
            .egressListener(egressListener)
            .ingressChannel(config.ingressChannel)
            .egressChannel(config.egressChannel)
        // Multi-node clusters advertise every member's ingress endpoint so the client can find
        // the leader; a channel carrying its own endpoint is the single-node shorthand.
        config.ingressEndpoints?.let(clusterContext::ingressEndpoints)

        val cluster = try {
            AeronCluster.connect(clusterContext)
        } catch (e: io.aeron.exceptions.TimeoutException) {
            System.err.println(
                "gateway: could not reach the cluster (${e.message}).\n" +
                    "  Check that the consensus module is running and that ingressChannel points at it."
            )
            return@use
        }

        cluster.use {
            // Counted separately so an operator can tell "nothing arrived" from "forwarded but
            // nothing came back" -- the two look identical from order state alone.
            val sink = object : GatewaySink {
                var forwardedToCluster = 0L
                var clusterBackpressure = 0L
                var sentToClient = 0L
                var droppedToClient = 0L

                /**
                 * Backpressure is transient and is reported as such, never as a drop. The caller
                 * leaves the fragment unconsumed and offers the same bytes on the next poll, so
                 * congestion propagates back to the client's publication instead of quietly
                 * swallowing an order.
                 */
                override fun toCluster(
                    buffer: DirectBuffer,
                    offset: Int,
                    length: Int,
                ): ClusterOffer {
                    val result = cluster.offer(buffer, offset, length)
                    return when {
                        result >= 0L -> {
                            forwardedToCluster++
                            ClusterOffer.SENT
                        }

                        result == Publication.BACK_PRESSURED ||
                            result == Publication.ADMIN_ACTION -> {
                            clusterBackpressure++
                            ClusterOffer.RETRY
                        }

                        else -> {
                            System.err.println("gateway: cluster offer failed with $result")
                            ClusterOffer.FAILED
                        }
                    }
                }

                /**
                 * One attempt, never a retry loop. Egress cannot be left unconsumed the way
                 * ingress can -- there is no back-channel, and stalling here would stall the
                 * cluster session -- so a subscriber that has fallen behind loses reports whatever
                 * this does. Spinning was measured and made things worse: it burned the poller
                 * thread that also drives ingress and keepalives, pushing the round-trip p99 from
                 * 0.3 ms to 4 ms while still dropping. droppedToClient is a real loss, and the fix
                 * for it is a larger term buffer or a faster subscriber, not a busier gateway.
                 */
                override fun toClient(buffer: DirectBuffer, offset: Int, length: Int) {
                    if (outbound.offer(buffer, offset, length) < 0) droppedToClient++
                    else sentToClient++
                }
            }
            val metrics = if (config.metricsEnabled) GatewayMetrics(SystemNanoClock.INSTANCE) else null
            service = GatewayService(config.shard.securityIds, sink, metrics = metrics)

            // Controlled, so a message the cluster cannot take right now is left in the
            // subscription rather than consumed and lost. COMMIT rather than CONTINUE on the
            // handled path is what makes that safe: CONTINUE only commits the position at the end
            // of the whole poll, so a later ABORT would rewind past fragments already forwarded
            // and send them a second time.
            val assembler = ControlledFragmentAssembler { buffer, offset, length, _ ->
                when (service.onClientMessage(buffer, offset, length)) {
                    ClientMessageAction.CONSUME -> ControlledFragmentHandler.Action.COMMIT
                    ClientMessageAction.RETRY -> ControlledFragmentHandler.Action.ABORT
                }
            }

            val idle = BusySpinIdleStrategy()
            val barrier = ShutdownSignalBarrier()
            println("gateway: started")

            val worker = Thread({
                var lastKeepAliveNs = System.nanoTime()
                while (!Thread.currentThread().isInterrupted) {
                    var work = inbound.controlledPoll(assembler, FRAGMENT_LIMIT)
                    work += cluster.pollEgress()

                    // A cluster session that sends nothing is closed by the consensus module
                    // after its session timeout, and every later offer then fails silently.
                    // Polling egress is not enough -- a quiet market would kill the gateway.
                    val now = System.nanoTime()
                    if (now - lastKeepAliveNs >= KEEP_ALIVE_INTERVAL_NS) {
                        cluster.sendKeepAlive()
                        lastKeepAliveNs = now
                    }

                    if (cluster.isClosed) {
                        System.err.println(
                            "gateway: cluster session closed; orders can no longer be forwarded"
                        )
                        break
                    }
                    idle.idle(work)
                }
            }, "gateway-poller")
            worker.start()

            barrier.use {
                it.await()
                worker.interrupt()
                // Inside the barrier: closing it releases the signal and the JVM exits at once.
                println(
                    "gateway: stopped. forwardedToCluster=${sink.forwardedToCluster} " +
                        "clusterBackpressure=${sink.clusterBackpressure} " +
                        "sentToClient=${sink.sentToClient} " +
                        "droppedToClient=${sink.droppedToClient} " +
                        "liveOrders=${service.liveOrders} " +
                        "rejectedLocally=${service.rejectedLocally} " +
                        "unreachableRejects=${service.unreachableRejects} " +
                        "undeliverableCommands=${service.undeliverableCommands} " +
                        "untrackedReports=${service.untrackedReports}"
                )
                metrics?.let { m ->
                    println("gateway: latency${m.summary()}")
                    config.metricsFile?.let { path ->
                        val written = LatencyHistogram.writeAll(
                            path,
                            "gateway shard=${config.shard.shardId} -- values in microseconds",
                            m.all(),
                        )
                        println(
                            if (written != null) "gateway: histograms written to $written"
                            else "gateway: no samples recorded, nothing written to $path"
                        )
                    }
                }
                System.out.flush()
            }
            worker.join(SHUTDOWN_TIMEOUT_MS)
        }
    }
}

private const val FRAGMENT_LIMIT = 64

/** Comfortably inside the consensus module's session timeout, which defaults to 10 seconds. */
private val KEEP_ALIVE_INTERVAL_NS = java.util.concurrent.TimeUnit.SECONDS.toNanos(1)
private const val SHUTDOWN_TIMEOUT_MS = 5_000L


data class GatewayConfig(
    val shard: ShardSpec,
    val aeronDirectoryName: String?,
    val ingressChannel: String,
    val ingressEndpoints: String?,
    val egressChannel: String,
    val clientInboundChannel: String,
    val clientInboundStreamId: Int,
    val clientOutboundChannel: String,
    val clientOutboundStreamId: Int,
    /** Hot-path timing: two clock reads per message on each leg. Off unless asked for. */
    val metricsEnabled: Boolean = false,
    /** Where to write percentile distributions at shutdown, for diffing against a later run. */
    val metricsFile: String? = null,
) {
    companion object {
        const val SECURITIES_FILE = "gateway.securitiesFile"
        const val METRICS_ENABLED = "gateway.metrics"
        const val METRICS_FILE = "gateway.metrics.file"

        fun from(properties: Properties, shard: ShardSpec): GatewayConfig = GatewayConfig(
            shard = shard,
            aeronDirectoryName = properties.getProperty("gateway.aeronDir"),
            ingressChannel = properties.getProperty("gateway.ingressChannel") ?: "aeron:udp",
            ingressEndpoints = properties.getProperty("gateway.ingressEndpoints"),
            egressChannel = properties.getProperty("gateway.egressChannel")
                ?: "aeron:udp?endpoint=localhost:9020",
            clientInboundChannel = properties.getProperty("gateway.client.inbound.channel")
                ?: "aeron:ipc",
            clientInboundStreamId =
                properties.getProperty("gateway.client.inbound.streamId")?.toInt() ?: 20,
            clientOutboundChannel = properties.getProperty("gateway.client.outbound.channel")
                ?: "aeron:ipc",
            clientOutboundStreamId =
                properties.getProperty("gateway.client.outbound.streamId")?.toInt() ?: 21,
            metricsEnabled = properties.getProperty(METRICS_ENABLED).toBoolean(),
            metricsFile = properties.getProperty(METRICS_FILE),
        )

        fun load(path: String?): GatewayConfig {
            val properties = Properties()
            if (path != null) {
                val file = File(path)
                require(file.isFile) { "config file not found: $path" }
                file.inputStream().use(properties::load)
            }
            for ((key, value) in System.getProperties()) {
                val name = key as String
                if (name.startsWith("gateway.")) properties.setProperty(name, value as String)
            }
            val securitiesFile = properties.getProperty(SECURITIES_FILE)
                ?: error("missing required configuration key: $SECURITIES_FILE")
            return from(properties, ShardSpec.load(securitiesFile))
        }
    }
}
