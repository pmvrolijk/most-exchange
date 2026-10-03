package com.engine.core

import io.aeron.cluster.service.ClusteredServiceContainer
import io.aeron.exceptions.DriverTimeoutException
import com.engine.reference.DutyCycles
import com.engine.reference.LatencyHistogram
import com.engine.reference.ParticipantRegistrySource
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

    // Re-read while the node runs, so a participant list change costs a gateway restart rather
    // than a node restart. Node-local: what it feeds is egress routing, not replicated state --
    // see ParticipantRegistrySource for why that distinction is the whole argument.
    val registrySource = config.participantRegistry?.let { initial ->
        ParticipantRegistrySource(config.participantRegistryFile!!, initial) {
            println("matching-engine: $it")
        }.also { if (config.registryReloadMs > 0) it.startPolling(config.registryReloadMs) }
    }

    val books = config.newBooks()
    val metrics =
        if (config.metricsEnabled) EngineMetrics(SystemNanoClock.INSTANCE, config.metricsStages)
        else null
    // How full the service thread is, which the histograms cannot say and `ps` cannot either, since
    // it busy-spins (Design.md §7, "Duty cycle"). Metrics under the same rule: it wraps the idle
    // strategy, which the state machine never sees, and its counter is read only from outside.
    val duty = if (config.metricsEnabled) DutyCycles(SystemNanoClock.INSTANCE) else null
    val service = MatchingEngineService(
        shardId = config.shard.shardId,
        books = books,
        shardFingerprint = config.shard.fingerprintValue(),
        engineFingerprint = config.engineFingerprintValue(),
        bookEventChannel = config.bookEventChannel,
        bookEventStreamId = config.bookEventStreamId,
        levelCount = config.maxLevelCount(),
        auctionMaxPasses = config.auctionMaxPasses,
        backpressureAlertThreshold = config.backpressureAlertThreshold,
        participantRegistry = { registrySource?.registry() },
        metrics = metrics,
        reportRetention = config.reportRetention,
    )

    // Every node must boot with identical configuration or the books diverge on the first order.
    // Printed so an operator can compare nodes without diffing files; the engine also compares them
    // itself, against the leader's announcement in the log (Design.md §7).
    println(
        "matching-engine: shard=${config.shard.shardId} fingerprint=${config.fingerprint()} " +
            "engineFingerprint=${config.engineFingerprint()} reportRetention=${config.reportRetention} " +
            "securities=${config.shard.securities.map { it.symbol }} " +
            "serviceId=${config.serviceId} clusterDir=${config.clusterDir} " +
            "participantRegistry=${config.registryFingerprint()}" +
            (if (registrySource != null && config.registryReloadMs > 0)
                " reload=${config.registryReloadMs}ms" else "")
    )
    if (config.participantRegistry == null) {
        // Not an error -- it is the behaviour that shipped before the registry existed -- but it
        // is the difference between a maker being reachable and its fills being counted and
        // dropped, so it is said out loud rather than left to be noticed in a counter.
        println(
            "matching-engine: no participant registry (${EngineConfig.PARTICIPANT_REGISTRY} " +
                "unset). Routes are learned from traffic only, so a participant that has sent " +
                "nothing since its gateway connected cannot be sent its fills."
        )
    }

    // Created before the container so the error handler below can release it. A refused snapshot
    // restore, or a configuration the leader's announcement disagrees with, has to leave through
    // the same orderly shutdown as everything else -- see below.
    val barrier = ShutdownSignalBarrier()
    val refused = java.util.concurrent.atomic.AtomicReference<NodeRefusal>()

    val context = ClusteredServiceContainer.Context()
        .clusteredService(service)
        .serviceId(config.serviceId)
        .serviceName("matching-engine")
        .clusterDir(config.clusterDir)
        // Busy-spin unless configured otherwise: in production this thread owns an isolated core
        // and must never yield it (Design.md §7).
        .idleStrategySupplier {
            config.idleStrategy.create().let { idle -> duty?.wrap("engine service", idle) ?: idle }
        }
        .errorHandler { throwable ->
            val refusal = generateSequence(throwable) { it.cause }
                .filterIsInstance<NodeRefusal>()
                .firstOrNull()
            if (refusal != null) {
                // A refusal is a decision, not a crash: the report is the whole message and a stack
                // trace only buries it. The service runs on the container's agent thread, so
                // throwing there lands here rather than out of launch() -- and a node that cannot
                // restore its state, or that disagrees with the leader's configuration, must not go
                // on as a member of the cluster.
                //
                // Release the barrier rather than halting the JVM. Halting skips
                // `ClusteredServiceContainer.close()`, which leaves this service's cluster mark
                // file carrying a live timestamp -- so the operator who fixes the security file
                // and restarts immediately is met with "active mark file detected" for the next
                // ten seconds instead of a working node. The orderly path releases it.
                //
                // Only the first is kept: a node that refused its configuration also refuses to
                // snapshot, and that second refusal is a consequence, not the news.
                refused.compareAndSet(null, refusal)
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
        duty?.attachInBackground(context.aeron(), { println("matching-engine: duty cycle counters $it") })
        println("matching-engine: idle strategy ${config.idleStrategy}")
        println("matching-engine: started, awaiting shutdown signal")
        registrySource.use { barrier.use {
            barrier.await()
            refused.get()?.let { refusal ->
                // Nothing below applies: this engine either restored nothing, or applied the log
                // under a configuration it has since refused, so its counters and histograms would
                // describe a node that should not have run.
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
                    "bookImagesPublished=${service.bookImagesPublished} " +
                    "backpressureStalls=${service.backpressureStalls} " +
                    "rejectedDefinitions=${service.rejectedDefinitions} " +
                    "auctionPassLimitBreaches=${service.auctionPassLimitBreaches} " +
                    "bulkCancelledOrders=${service.bulkCancelledOrders} " +
                    "resendRequests=${service.resendRequests} replayedReports=${service.replayedReports} " +
                    "massStatusRequests=${service.massStatusRequests} statusReports=${service.statusReports} " +
                    "rejectedBulkCancels=${service.rejectedBulkCancels} " +
                    "declaredBindings=${service.declaredBindings} " +
                    "unknownPrincipals=${service.unknownPrincipals} " +
                    "undeclaredParticipantMessages=${service.undeclaredParticipantMessages} " +
                    "participantRoutes=${service.participantRoutes} " +
                    "configurationAnnouncementsAgreed=${service.configurationAnnouncementsAgreed}" +
                    (registrySource?.let {
                        " registryReloads=${it.reloads} registryReloadFailures=${it.failures}"
                    } ?: "")
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
        } }
    }
    // After the container is closed, so the mark file is released before the process goes away.
    if (refused.get() != null) kotlin.system.exitProcess(1)
}
