package com.engine.gateway

import com.engine.reference.ShardSpec
import io.aeron.Aeron
import io.aeron.FragmentAssembler
import io.aeron.cluster.client.AeronCluster
import io.aeron.cluster.client.EgressListener
import org.agrona.DirectBuffer
import org.agrona.concurrent.BusySpinIdleStrategy
import org.agrona.concurrent.ShutdownSignalBarrier
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
            val sink = object : GatewaySink {
                var droppedToCluster = 0L
                var droppedToClient = 0L

                override fun toCluster(buffer: DirectBuffer, offset: Int, length: Int) {
                    if (cluster.offer(buffer, offset, length) < 0) droppedToCluster++
                }

                override fun toClient(buffer: DirectBuffer, offset: Int, length: Int) {
                    if (outbound.offer(buffer, offset, length) < 0) droppedToClient++
                }
            }
            service = GatewayService(config.shard.securityIds, sink)

            val assembler = FragmentAssembler { buffer, offset, length, _ ->
                service.onClientMessage(buffer, offset, length)
            }

            val idle = BusySpinIdleStrategy()
            val barrier = ShutdownSignalBarrier()
            println("gateway: started")

            val worker = Thread({
                while (!Thread.currentThread().isInterrupted) {
                    var work = inbound.poll(assembler, FRAGMENT_LIMIT)
                    work += cluster.pollEgress()
                    idle.idle(work)
                }
            }, "gateway-poller")
            worker.start()

            barrier.await()
            worker.interrupt()
            worker.join(SHUTDOWN_TIMEOUT_MS)
            println(
                "gateway: stopped. liveOrders=${service.liveOrders} " +
                    "rejectedLocally=${service.rejectedLocally} " +
                    "untrackedReports=${service.untrackedReports}"
            )
        }
    }
}

private const val FRAGMENT_LIMIT = 64
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
) {
    companion object {
        const val SECURITIES_FILE = "gateway.securitiesFile"

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
