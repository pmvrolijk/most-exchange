package com.engine.core

import io.aeron.cluster.service.ClusteredServiceContainer
import io.aeron.exceptions.DriverTimeoutException
import org.agrona.concurrent.BusySpinIdleStrategy
import org.agrona.concurrent.ShutdownSignalBarrier

/**
 * Hosts the matching engine as an Aeron Cluster service container.
 *
 * This process deliberately contains **only** the service. The media driver, the archive and the
 * consensus module run separately (Design.md §7): the driver allocates, and keeping it out of this
 * binary is what lets the engine credibly claim a zero-allocation steady state under Epsilon GC.
 * A node is therefore two processes:
 *
 * ```
 *   1. io.aeron.cluster.ClusteredMediaDriver   (driver + archive + consensus module)
 *   2. this binary                             (ClusteredServiceContainer + MatchingEngineService)
 * ```
 *
 * Both attach to the same Aeron directory. Usage:
 *
 * ```
 *   matching-engine [config.properties]
 * ```
 *
 * Any `engine.*` system property overrides the corresponding file entry. See [EngineConfig].
 */
fun main(args: Array<String>) {
    val config = try {
        EngineConfig.load(args.firstOrNull())
    } catch (e: IllegalArgumentException) {
        System.err.println("matching-engine: ${e.message}")
        return
    } catch (e: IllegalStateException) {
        System.err.println("matching-engine: ${e.message}")
        return
    }

    val books = config.newBooks()
    val service = MatchingEngineService(
        shardId = config.shard.shardId,
        books = books,
        bookEventChannel = config.bookEventChannel,
        bookEventStreamId = config.bookEventStreamId,
        levelCount = config.maxLevelCount(),
        auctionMaxPasses = config.auctionMaxPasses,
        backpressureAlertThreshold = config.backpressureAlertThreshold,
    )

    // Every node must boot with identical configuration or the books diverge on the first order.
    // Print the fingerprint so an operator can compare nodes without diffing files.
    println(
        "matching-engine: shard=${config.shard.shardId} fingerprint=${config.fingerprint()} " +
            "securities=${config.shard.securities.map { it.symbol }} " +
            "serviceId=${config.serviceId} clusterDir=${config.clusterDir}"
    )

    val context = ClusteredServiceContainer.Context()
        .clusteredService(service)
        .serviceId(config.serviceId)
        .serviceName("matching-engine")
        .clusterDir(config.clusterDir)
        // Busy-spin: this thread owns an isolated core and must never yield it (Design.md §7).
        .idleStrategySupplier { BusySpinIdleStrategy() }
        .errorHandler { throwable ->
            System.err.println("matching-engine: ${throwable.message}")
            throwable.printStackTrace()
        }

    config.aeronDirectoryName?.let(context::aeronDirectoryName)

    val container = try {
        ClusteredServiceContainer.launch(context)
    } catch (e: DriverTimeoutException) {
        // By far the most likely startup mistake: this process hosts only the service, so the
        // media driver and consensus module must already be running and sharing an Aeron dir.
        System.err.println(
            "matching-engine: no Aeron media driver found (${e.message}).\n" +
                "  This binary hosts only the cluster service. Start the driver and consensus\n" +
                "  module first (io.aeron.cluster.ClusteredMediaDriver), and point both at the\n" +
                "  same Aeron directory via ${EngineConfig.AERON_DIR}."
        )
        return
    }

    container.use {
        println("matching-engine: started, awaiting shutdown signal")
        ShutdownSignalBarrier().await()
        println("matching-engine: shutdown signal received")
    }
}
