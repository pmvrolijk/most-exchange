package com.engine.core

import io.aeron.cluster.service.ClusteredServiceContainer
import io.aeron.exceptions.DriverTimeoutException
import com.engine.reference.LatencyHistogram
import org.agrona.concurrent.BusySpinIdleStrategy
import org.agrona.concurrent.SystemNanoClock
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
    val metrics =
        if (config.metricsEnabled) EngineMetrics(SystemNanoClock.INSTANCE, config.metricsStages)
        else null
    val service = MatchingEngineService(
        shardId = config.shard.shardId,
        books = books,
        shardFingerprint = config.shard.fingerprintValue(),
        bookEventChannel = config.bookEventChannel,
        bookEventStreamId = config.bookEventStreamId,
        levelCount = config.maxLevelCount(),
        auctionMaxPasses = config.auctionMaxPasses,
        backpressureAlertThreshold = config.backpressureAlertThreshold,
        metrics = metrics,
    )

    // Every node must boot with identical configuration or the books diverge on the first order.
    // Print the fingerprint so an operator can compare nodes without diffing files.
    println(
        "matching-engine: shard=${config.shard.shardId} fingerprint=${config.fingerprint()} " +
            "securities=${config.shard.securities.map { it.symbol }} " +
            "serviceId=${config.serviceId} clusterDir=${config.clusterDir}"
    )

    // Created before the container so the error handler below can release it. A refused snapshot
    // restore has to leave through the same orderly shutdown as everything else -- see below.
    val barrier = ShutdownSignalBarrier()
    val refused = java.util.concurrent.atomic.AtomicReference<SnapshotRestoreFailed>()

    val context = ClusteredServiceContainer.Context()
        .clusteredService(service)
        .serviceId(config.serviceId)
        .serviceName("matching-engine")
        .clusterDir(config.clusterDir)
        // Busy-spin: this thread owns an isolated core and must never yield it (Design.md §7).
        .idleStrategySupplier { BusySpinIdleStrategy() }
        .errorHandler { throwable ->
            val refusal = generateSequence(throwable) { it.cause }
                .filterIsInstance<SnapshotRestoreFailed>()
                .firstOrNull()
            if (refusal != null) {
                // A refused restore is a decision, not a crash: the report is the whole message
                // and a stack trace only buries it. The service runs on the container's agent
                // thread, so throwing there lands here rather than out of launch() -- and a node
                // that cannot restore its state must not go on to join consensus without it.
                //
                // Release the barrier rather than halting the JVM. Halting skips
                // `ClusteredServiceContainer.close()`, which leaves this service's cluster mark
                // file carrying a live timestamp -- so the operator who fixes the security file
                // and restarts immediately is met with "active mark file detected" for the next
                // ten seconds instead of a working node. The orderly path releases it.
                refused.set(refusal)
                barrier.signal()
                return@errorHandler
            }
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
        barrier.use {
            barrier.await()
            refused.get()?.let { refusal ->
                // Nothing below applies: this engine restored nothing and processed nothing, so
                // its counters and histograms would describe a node that never ran.
                System.err.println(refusal.message)
                System.err.flush()
                System.out.flush()
                return@use
            }
            // Everything below is INSIDE the barrier block on purpose: closing the barrier
            // releases the signal and the process exits at once, so anything printed after it is
            // racing the exit (CLAUDE.md). The single line that used to live out here won that
            // race; writing a histogram file does not.
            println(
                "matching-engine: shutdown signal received. " +
                    "undeliverableReports=${service.undeliverableReports} " +
                    "droppedBookEvents=${service.droppedBookEvents} " +
                    "backpressureStalls=${service.backpressureStalls} " +
                    "rejectedDefinitions=${service.rejectedDefinitions} " +
                    "auctionPassLimitBreaches=${service.auctionPassLimitBreaches}"
            )
            if (metrics != null) {
                println("matching-engine: latency${metrics.summary()}")
                config.metricsFile?.let { path ->
                    val written = LatencyHistogram.writeAll(
                        path,
                        "matching-engine shard=${config.shard.shardId} -- values in microseconds",
                        metrics.all(),
                    )
                    println(
                        if (written != null) "matching-engine: histograms written to $written"
                        else "matching-engine: no samples recorded, nothing written to $path"
                    )
                }
            }
            System.out.flush()
        }
    }
    // After the container is closed, so the mark file is released before the process goes away.
    if (refused.get() != null) kotlin.system.exitProcess(1)
}
