package com.engine.core

import com.engine.reference.IdleStrategySpec
import com.engine.reference.ParticipantRegistry
import com.engine.reference.ShardSpec
import java.io.File
import java.util.Properties

/**
 * Process-local configuration for one engine node.
 *
 * The security list is **not** here: it lives in the shard's security file, which the gateway and
 * the market data process read too (see [com.engine.reference.ShardSpec]). One shard, one
 * definition, one fingerprint — rather than three lists that must be kept in step by hand.
 *
 * Only geometry and capacity are static. Reference prices and collar widths arrive as
 * `SecurityDefinition` commands through the replicated log, because they change during a session
 * and every node must apply them at the same log position (Design.md §4.4).
 */
data class EngineConfig(
    val shard: ShardSpec,
    /**
     * Which gateway speaks for which participant, or null to keep learning every route from
     * traffic. Optional so an existing deployment starts unchanged; a production shard sets it,
     * because without it a maker that has been quiet since its gateway last connected cannot be
     * sent its own fills (Design.md §1).
     */
    val participantRegistry: ParticipantRegistry? = null,
    /** Where [participantRegistry] was read from, so it can be re-read while the node runs. */
    val participantRegistryFile: String? = null,
    /**
     * How often to re-read [participantRegistryFile]; 0 disables it and is the old behaviour.
     *
     * Onboarding a participant or rotating a gateway secret is then a gateway restart rather than
     * a node restart. Node-local by design and therefore excluded from [fingerprint], for exactly
     * the reason metrics are: two nodes polling on different schedules cannot diverge the log.
     */
    val registryReloadMs: Long = 0L,
    val aeronDirectoryName: String?,
    val clusterDir: File,
    val serviceId: Int,
    val bookEventChannel: String,
    val bookEventStreamId: Int,
    val auctionMaxPasses: Int,
    val backpressureAlertThreshold: Int,
    /**
     * Hot-path timing. Off by default: it costs two clock reads per message, and an engine that
     * reads a clock at all needs a deliberate decision behind it (see [EngineMetrics]). The dev
     * stack and `e2e/` turn it on; a production node opts in.
     */
    val metricsEnabled: Boolean = false,
    /** Adds the admit/match/settle partition of a new order. Two more clock reads. */
    val metricsStages: Boolean = false,
    /** Where to write the percentile distributions at shutdown, for diffing against a later run. */
    val metricsFile: String? = null,
    /**
     * The service container's idle strategy. Busy-spin by default: in production this thread owns an
     * isolated core and must never yield it. The others spare a development machine's cores
     * (Design.md §7, "Duty cycle"). Node-local and not in [fingerprint], like the metrics: how a
     * node idles cannot change what it computes.
     */
    val idleStrategy: IdleStrategySpec = IdleStrategySpec.BUSY_SPIN,
) {
    init {
        require(auctionMaxPasses > 0) { "auctionMaxPasses must be positive" }
        require(backpressureAlertThreshold > 0) { "backpressureAlertThreshold must be positive" }
    }

    /** The widest ladder in the shard, which sizes the shared uncross scratch buffer. */
    fun maxLevelCount(): Int = shard.maxLevelCount()

    fun newBooks(): Array<OrderBook> = Array(shard.securities.size) { i ->
        val s = shard.securities[i]
        OrderBook(
            securityId = s.securityId,
            priceFloor = s.priceFloor,
            tickSize = s.tickSize,
            levelCount = s.levelCount,
            maxOrders = s.maxOrders,
        )
    }

    /**
     * Every node and every process in the shard must print the same value.
     *
     * Metrics settings are deliberately absent from it. They are node-local by design — enabling
     * them on one node and not another must be incapable of changing the log (see [EngineMetrics])
     * — so folding them in would report a mismatch between nodes that agree on everything that
     * matters.
     */
    fun fingerprint(): String = shard.fingerprint()

    /**
     * Every setting here that can change what the engine computes from a given log, as one 64-bit
     * hash. The leader announces it beside [fingerprint] and a node that disagrees refuses to go on
     * (Design.md §7, "Enforced through the log").
     *
     * Today that is [auctionMaxPasses] alone: it bounds the SMP fixed point of an uncross, so two
     * nodes on different values print the same uncross from the same log only until one of them
     * runs out of passes. A *separate* value rather than folded into [fingerprint], because
     * `ShardSpec.fingerprint()` is recorded by the control plane and published in every release.
     *
     * A setting added to [EngineConfig] belongs in here unless it is node-local in the sense the
     * metrics are: incapable of changing the log, the books or a snapshot. The idle strategy, the
     * registry reload interval and [backpressureAlertThreshold] -- which only decides when a stall
     * is *counted* -- are out for that reason.
     */
    fun engineFingerprintValue(): Long {
        var hash = 1125899906842597L
        for (c in "auctionMaxPasses=$auctionMaxPasses") hash = hash * 31 + c.code
        return hash
    }

    /** [engineFingerprintValue] in hex, for printing beside [fingerprint]. */
    fun engineFingerprint(): String = java.lang.Long.toHexString(engineFingerprintValue())

    /**
     * The registry's own fingerprint, or `none`. Deliberately a *second* value beside
     * [fingerprint] rather than folded into it: `ShardSpec.fingerprint()` is recorded by the
     * control plane and published in every release, so widening what it covers would invalidate
     * every value already written down. Rotating a gateway secret is not a change of geometry.
     */
    fun registryFingerprint(): String = participantRegistry?.fingerprint() ?: "none"

    companion object {
        const val SECURITIES_FILE = "engine.securitiesFile"
        const val PARTICIPANT_REGISTRY = "engine.participantRegistry"
        const val PARTICIPANT_REGISTRY_RELOAD_MS = "engine.participantRegistry.reloadMs"

        /** Matches the consensus module's default, so the two see a new file at the same rate. */
        const val DEFAULT_REGISTRY_RELOAD_MS = 5_000L
        const val AERON_DIR = "engine.aeronDir"
        const val CLUSTER_DIR = "engine.clusterDir"
        const val SERVICE_ID = "engine.serviceId"
        const val BOOK_EVENT_CHANNEL = "engine.bookEvent.channel"
        const val BOOK_EVENT_STREAM_ID = "engine.bookEvent.streamId"
        const val AUCTION_MAX_PASSES = "engine.auction.maxPasses"
        const val BACKPRESSURE_ALERT_THRESHOLD = "engine.backpressure.alertThreshold"
        const val METRICS_ENABLED = "engine.metrics"
        const val METRICS_STAGES = "engine.metrics.stages"
        const val METRICS_FILE = "engine.metrics.file"
        const val IDLE_STRATEGY = "engine.idleStrategy"

        fun from(
            properties: Properties,
            shard: ShardSpec,
            participantRegistry: ParticipantRegistry? = null,
            participantRegistryFile: String? = null,
        ): EngineConfig = EngineConfig(
            shard = shard,
            participantRegistry = participantRegistry,
            participantRegistryFile = participantRegistryFile,
            registryReloadMs = properties.getProperty(PARTICIPANT_REGISTRY_RELOAD_MS)?.toLong()
                ?: DEFAULT_REGISTRY_RELOAD_MS,
            aeronDirectoryName = properties.getProperty(AERON_DIR),
            clusterDir = File(properties.getProperty(CLUSTER_DIR) ?: "cluster"),
            serviceId = properties.getProperty(SERVICE_ID)?.toInt() ?: 0,
            bookEventChannel = properties.getProperty(BOOK_EVENT_CHANNEL) ?: "aeron:ipc",
            bookEventStreamId = properties.getProperty(BOOK_EVENT_STREAM_ID)?.toInt() ?: 12,
            auctionMaxPasses = properties.getProperty(AUCTION_MAX_PASSES)?.toInt() ?: 64,
            backpressureAlertThreshold =
                properties.getProperty(BACKPRESSURE_ALERT_THRESHOLD)?.toInt() ?: 1_000_000,
            metricsEnabled = properties.getProperty(METRICS_ENABLED).toBoolean(),
            metricsStages = properties.getProperty(METRICS_STAGES).toBoolean(),
            metricsFile = properties.getProperty(METRICS_FILE),
            idleStrategy = IdleStrategySpec.parse(properties.getProperty(IDLE_STRATEGY)),
        )

        /** Loads [path] if given, then lets `engine.*` system properties override individual keys. */
        fun load(path: String?): EngineConfig {
            val properties = Properties()
            if (path != null) {
                val file = File(path)
                require(file.isFile) { "config file not found: $path" }
                file.inputStream().use(properties::load)
            }
            for ((key, value) in System.getProperties()) {
                val name = key as String
                if (name.startsWith("engine.")) properties.setProperty(name, value as String)
            }
            val securitiesFile = properties.getProperty(SECURITIES_FILE)
                ?: error("missing required configuration key: $SECURITIES_FILE")
            val registryFile = properties.getProperty(PARTICIPANT_REGISTRY)
            val registry = registryFile?.let(ParticipantRegistry::load)
            val shard = ShardSpec.load(securitiesFile)
            // Caught here rather than at the first misrouted report: a registry published for
            // another shard would authenticate gateways this engine never serves and bind
            // participants no book of its own has ever heard of.
            require(registry == null || registry.shardId == shard.shardId) {
                "$PARTICIPANT_REGISTRY is for shard ${registry?.shardId}, " +
                    "but this node serves shard ${shard.shardId}"
            }
            return from(properties, shard, registry, registryFile)
        }
    }
}
