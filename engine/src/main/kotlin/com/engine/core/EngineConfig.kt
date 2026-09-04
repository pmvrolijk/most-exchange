package com.engine.core

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

    companion object {
        const val SECURITIES_FILE = "engine.securitiesFile"
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

        fun from(properties: Properties, shard: ShardSpec): EngineConfig = EngineConfig(
            shard = shard,
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
            return from(properties, ShardSpec.load(securitiesFile))
        }
    }
}
