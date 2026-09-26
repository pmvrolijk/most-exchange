package com.engine.control

import com.engine.reference.DirectoryClient
import io.aeron.Aeron
import io.aeron.FragmentAssembler
import io.aeron.Publication
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.agrona.concurrent.SleepingIdleStrategy
import org.agrona.concurrent.UnsafeBuffer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * The control plane's live connection to a running exchange.
 *
 * Two subscriptions and a publication per shard, all plain Aeron — the backend talks to the
 * **gateway's** client channel exactly as `most` does, never to the cluster directly, so operator
 * commands take the same validated path an order does and are sequenced through the replicated log.
 *
 * **Optional by design.** The control plane's first job is authoring reference data, and that must
 * work with no media driver anywhere near it. A missing driver is reported and the REST API keeps
 * serving; it is not a startup failure.
 *
 * The poller is deliberately a `SleepingIdleStrategy`, not the `BusySpinIdleStrategy` the exchange
 * processes use. This is a web backend observing a feed, and burning a core to shave microseconds
 * off how quickly a dashboard notices a phase change would be a poor trade.
 */
@Component
class ClusterLink(
    private val topology: TopologyService,
    @Value("\${control.aeron.enabled:true}") private val enabled: Boolean,
    @Value("\${control.aeron.dir:#{null}}") private val aeronDir: String?,
    @Value("\${control.discovery.channel:aeron:udp?endpoint=239.10.0.1:40000}")
    private val discoveryChannel: String,
    @Value("\${control.discovery.streamId:100}") private val discoveryStreamId: Int,
    @Value("\${control.l3.channel:aeron:udp?endpoint=239.10.1.3:40003}")
    private val l3Channel: String,
    @Value("\${control.l3.streamId:3}") private val l3StreamId: Int,
    @Value("\${control.l2.channel:aeron:udp?endpoint=239.10.1.2:40002}")
    private val l2Channel: String,
    @Value("\${control.l2.streamId:2}") private val l2StreamId: Int,
    @Value("\${control.l1.channel:aeron:udp?endpoint=239.10.1.1:40001}")
    private val l1Channel: String,
    @Value("\${control.l1.streamId:1}") private val l1StreamId: Int,
    @Value("\${control.snapshot.channel:aeron:udp?endpoint=239.10.1.4:40004}")
    private val snapshotChannel: String,
    @Value("\${control.snapshot.streamId:4}") private val snapshotStreamId: Int,
    @Value("\${control.depth.publishMs:250}") private val depthPublishMs: Long,
    @Value("\${control.depth.maxLevels:25}") private val depthMaxLevels: Int,
    /** Per-shard lookups -- the operator gateway endpoint -- read on demand, as ClusterAdmin does. */
    private val environment: org.springframework.core.env.Environment,
) {
    private val log = LoggerFactory.getLogger(ClusterLink::class.java)

    val state = ExchangeState()

    /**
     * The books, rebuilt from L2 and the recovery feed.
     *
     * A separate concern from [state], which is derived from L3: a halt is visible only on the raw
     * book event stream, and depth is not derivable from it without shadowing the engine's order
     * pool. The two feeds answer different questions and the control plane subscribes to both.
     */
    val depth = DepthMonitor(maxLevels = depthMaxLevels) { securityId ->
        val observed = state.securityState(securityId)
        BookContext(
            symbol = topology.security(securityId)?.symbol,
            phase = observed?.phase,
            // A halt is visible only on L3, and only until an operator clears it by reopening.
            halted = observed?.halt?.clearedAt == null && observed?.halt != null,
        )
    }

    private val directoryClient = DirectoryClient()

    /** L3 into [state], and the gap count. Testable on its own; see [BookEventReader]. */
    private val events = BookEventReader(state)
    private val publications = ConcurrentHashMap<Int, Publication>()

    private var aeron: Aeron? = null
    private var poller: Thread? = null

    @Volatile
    private var detail: String = "not started"

    val connected: Boolean get() = aeron != null

    /**
     * The shared Aeron client, or null when there is no media driver.
     *
     * Exposed for [ClusterAdmin], which needs a cluster admin connection rather than a plain
     * publication to the gateway. One Aeron client per process: a second would map its own set of
     * buffers into the same directory for no gain.
     */
    fun aeron(): Aeron? = aeron

    @PostConstruct
    fun start() {
        if (!enabled) {
            detail = "disabled (control.aeron.enabled=false)"
            log.info("cluster link {}", detail)
            return
        }
        val context = Aeron.Context()
        aeronDir?.let(context::aeronDirectoryName)
        aeron = try {
            Aeron.connect(context)
        } catch (e: io.aeron.exceptions.DriverTimeoutException) {
            // Reported, never fatal: authoring reference data must not require a media driver.
            detail = "no Aeron media driver found (${e.message}); commands and monitoring are off"
            log.warn("cluster link: {}", detail)
            return
        }
        detail = "connected"
        poller = Thread({ poll(checkNotNull(aeron)) }, "control-feed-poller").apply {
            isDaemon = true
            start()
        }
        log.info(
            "cluster link: discovery={}:{} l3={}:{} l2={}:{} l1={}:{} snapshot={}:{} images every {}ms",
            discoveryChannel, discoveryStreamId, l3Channel, l3StreamId,
            l2Channel, l2StreamId, l1Channel, l1StreamId,
            snapshotChannel, snapshotStreamId, depthPublishMs,
        )
    }

    @PreDestroy
    fun stop() {
        poller?.interrupt()
        publications.values.forEach { it.close() }
        publications.clear()
        aeron?.close()
        aeron = null
        detail = "stopped"
    }

    // ---------------------------------------------------------------- sending

    /**
     * Offers one encoded command to a shard's operator gateway.
     *
     * Endpoints come from the database rather than the directory: the control plane is the
     * authority on topology, and a command must still be sendable when discovery is down. Where the
     * two disagree, [routingDrift] reports it rather than one silently winning.
     *
     * **Only a gateway whose registry entry has `operator=true` forwards these** (Design.md §1), and
     * they are unacknowledged -- one sent to a gateway that is not an operator is consumed and
     * counted there, and nothing here can tell. So the shard's advertised endpoint is used only when
     * no dedicated one is configured; see [operatorEndpointFor].
     */
    fun send(shardId: Int, encode: (UnsafeBuffer) -> Int): SendOutcome {
        val link = aeron ?: return SendOutcome(false, "no cluster link: $detail")
        val shard = topology.shard(shardId) ?: return SendOutcome(false, "no such shard: $shardId")
        val (channel, streamId) = try {
            operatorEndpointFor(environment, shard)
        } catch (e: IllegalArgumentException) {
            return SendOutcome(false, e.message ?: "bad operator endpoint for shard $shardId")
        }

        val publication = publications.computeIfAbsent(shardId) {
            link.addPublication(channel, streamId)
        }
        if (!publication.isConnected) {
            // Give a freshly created publication a moment to see the gateway's subscription.
            val deadline = System.nanoTime() + CONNECT_TIMEOUT.toNanos()
            val idle = SleepingIdleStrategy(POLL_IDLE.toNanos())
            while (!publication.isConnected && System.nanoTime() < deadline) idle.idle(0)
        }
        if (!publication.isConnected) {
            return SendOutcome(
                false,
                "no gateway listening on $channel:$streamId for shard $shardId",
            )
        }

        val buffer = UnsafeBuffer(ByteArray(COMMAND_BUFFER))
        val length = encode(buffer)
        val result = publication.offer(buffer, 0, length)
        return if (result >= 0) {
            SendOutcome(true, "offered to $channel:$streamId")
        } else {
            SendOutcome(false, "the gateway did not accept the command (offer returned $result)")
        }
    }

    // --------------------------------------------------------------- watching

    /** Where the published topology and what discovery is actually broadcasting disagree. */
    fun routingDrift(): List<String> {
        if (!directoryClient.isReady) return emptyList()
        val drift = mutableListOf<String>()
        for (shard in topology.shards()) {
            val route = directoryClient.shardRoute(shard.shardId)
            if (route == null) {
                drift += "shard ${shard.shardId} is in the database but not in the directory"
                continue
            }
            if (route.orderEntryChannel != shard.orderEntryChannel ||
                route.orderEntryStreamId != shard.orderEntryStreamId
            ) {
                drift += "shard ${shard.shardId} order entry: database says " +
                    "${shard.orderEntryChannel}:${shard.orderEntryStreamId}, directory says " +
                    "${route.orderEntryChannel}:${route.orderEntryStreamId}"
            }
        }
        for (route in directoryClient.shards) {
            if (topology.shard(route.shardId) == null) {
                drift += "shard ${route.shardId} is broadcast by discovery but absent from the database"
            }
        }
        return drift
    }

    fun status(): ExchangeStatus = ExchangeStatus(
        connected = connected,
        detail = detail,
        directory = state.directory,
        securities = state.securities(),
        feedGaps = state.feedGaps,
        eventsMissed = state.eventsMissed,
        eventsSeen = state.eventsSeen,
        routingDrift = routingDrift(),
    )

    private fun poll(link: Aeron) {
        val directory = link.addSubscription(discoveryChannel, discoveryStreamId)
        val l3Events = link.addSubscription(l3Channel, l3StreamId)
        // L2 for the increments and the recovery stream for the images that make them applicable.
        // Subscribing to one without the other gives a subscriber that can never synchronise.
        val depthUpdates = link.addSubscription(l2Channel, l2StreamId)
        val snapshots = link.addSubscription(snapshotChannel, snapshotStreamId)
        // L1 carries LastTrade, and depth alone never will: a snapshot's last trade only reaches a
        // subscriber that is still joining, because a synchronised one ignores snapshots. Without
        // this the console would show a book that trades and a last price that stays empty.
        val topOfBook = link.addSubscription(l1Channel, l1StreamId)
        val depthHandler = FragmentAssembler { buffer, offset, length, _ ->
            depth.onMessage(buffer, offset, length)
        }

        val directoryHandler = FragmentAssembler { buffer, offset, length, _ ->
            directoryClient.onDirectoryMessage(buffer, offset, length)
            if (directoryClient.isReady) {
                state.directory = DirectoryState(
                    version = directoryClient.version,
                    securities = directoryClient.securities.size,
                    shards = directoryClient.shards.map { it.shardId }.sorted(),
                    lastSeenAt = Instant.now().toString(),
                    incompleteBroadcasts = directoryClient.incompleteBroadcasts,
                )
            }
        }

        val eventHandler = FragmentAssembler { buffer, offset, length, _ ->
            events.onBookEvent(buffer, offset, length)
        }

        val idle = SleepingIdleStrategy(POLL_IDLE.toNanos())
        // Wall clock. This process is a feed subscriber and holds no replicated state, so the
        // engine's ban on reading a clock is not in play here.
        var nextImage = System.currentTimeMillis() + depthPublishMs
        while (!Thread.currentThread().isInterrupted) {
            var work = directory.poll(directoryHandler, FRAGMENT_LIMIT)
            work += l3Events.poll(eventHandler, FRAGMENT_LIMIT)
            work += depthUpdates.poll(depthHandler, DEPTH_FRAGMENT_LIMIT)
            work += snapshots.poll(depthHandler, DEPTH_FRAGMENT_LIMIT)
            work += topOfBook.poll(depthHandler, FRAGMENT_LIMIT)

            // On this thread, between messages: an image assembled while updates are landing is a
            // book that never existed. Everything since the last one is folded into this one.
            val now = System.currentTimeMillis()
            if (now >= nextImage) {
                nextImage = now + depthPublishMs
                depth.publishImages()
            }
            idle.idle(work)
        }
    }

    companion object {
        private const val FRAGMENT_LIMIT = 64

        /**
         * Depth runs at orders of magnitude the volume of L3, and a snapshot cycle arrives as a
         * burst of one message per level. A larger drain per poll keeps a cycle from being spread
         * across so many iterations that the images fall behind the increments.
         */
        private const val DEPTH_FRAGMENT_LIMIT = 256
        private const val COMMAND_BUFFER = 512
        private val POLL_IDLE: Duration = Duration.ofMillis(1)
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)

        /**
         * Where [send] delivers a shard's operator commands: a dedicated operator gateway if one is
         * configured, otherwise the shard's advertised order-entry endpoint.
         *
         * `control.cluster.operatorChannel.<shardId>` (`CONTROL_CLUSTER_OPERATORCHANNEL_<shardId>`)
         * and, optionally, `control.cluster.operatorStream.<shardId>`, which defaults to the shard's
         * own stream. Two keys rather than one `channel:stream`, because a channel URI is full of
         * colons of its own. Deployment, not topology, so this process's configuration rather than
         * the database -- the line ClusterAdmin draws for cluster ingress.
         */
        fun operatorEndpointFor(
            environment: org.springframework.core.env.Environment,
            shard: ShardRow,
        ): Pair<String, Int> {
            val channel = environment.getProperty("control.cluster.operatorChannel.${shard.shardId}")
                ?.trim()?.ifEmpty { null }
            val stream = environment.getProperty("control.cluster.operatorStream.${shard.shardId}")
                ?.trim()?.ifEmpty { null }
                ?.let {
                    requireNotNull(it.toIntOrNull()) {
                        "control.cluster.operatorStream.${shard.shardId} must be a stream id, not '$it'"
                    }
                }
            return (channel ?: shard.orderEntryChannel) to (stream ?: shard.orderEntryStreamId)
        }
    }
}

/** Whether the bytes reached the gateway. Not whether the engine applied them. */
data class SendOutcome(val sent: Boolean, val detail: String)
