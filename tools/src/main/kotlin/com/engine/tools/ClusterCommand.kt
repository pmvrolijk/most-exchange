package com.engine.tools

import com.engine.reference.ParticipantRegistry
import com.engine.reference.ParticipantRegistrySource
import com.engine.reference.RegistryAuthenticatorSupplier
import io.aeron.Aeron
import io.aeron.archive.Archive
import io.aeron.archive.ArchiveThreadingMode
import io.aeron.cluster.ClusterTool
import io.aeron.cluster.ClusteredMediaDriver
import io.aeron.cluster.ConsensusModule
import io.aeron.cluster.client.AeronCluster
import io.aeron.driver.MediaDriver
import io.aeron.driver.ThreadingMode
import org.agrona.concurrent.ShutdownSignalBarrier
import java.io.File

/** How often the consensus module re-reads its participant registry. */
private const val DEFAULT_REGISTRY_RELOAD_MS = 5_000L

/**
 * `most cluster` and its subcommands.
 *
 * With no subcommand it runs a single-node cluster host for local development: media driver,
 * archive and consensus module in one process, which is what the engine's service container
 * attaches to. Production runs three or five of these on separate machines (Design.md §1) and keeps
 * the media driver out of the engine binary so the zero-allocation profile holds.
 *
 * `snapshot` and `shutdown` are the operator side of that: without a snapshot there is no
 * resumption point, and a node killed without one recovers only by replaying the log from genesis.
 */
fun runCluster(args: Args) {
    // Blank positionals are dropped, not just the first one, so a compose file can pass an
    // optional `${CLUSTER_FRESH:-}` argument in any position without it either becoming an unknown
    // subcommand or shadowing a real one.
    when (val sub = args.positional.firstOrNull { it.isNotBlank() }) {
        null, "run" -> runClusterHost(args)
        "snapshot" -> runClusterSnapshot(args)
        "shutdown" -> runClusterShutdown(args)
        else -> throw IllegalArgumentException(
            "unknown cluster subcommand '$sub' (expected run, snapshot or shutdown)"
        )
    }
}

private fun runClusterHost(args: Args) {
    val base = args.optional("dir") ?: "build/cluster-host"
    val aeronDir = args.optional("aeron-dir") ?: "$base/driver"
    val archiveDir = args.optional("archive-dir") ?: "$base/archive"
    val clusterDir = args.optional("cluster-dir") ?: "$base/cluster"
    val ingressTermLength = args.optional("ingress-term-length") ?: "64k"
    // SHARED puts the driver's conductor, sender and receiver on one thread, which is right for a
    // developer laptop running five JVMs and wrong if that thread is what caps the shard. Both are
    // measurable; neither is assumed.
    val driverThreading = ThreadingMode.valueOf(
        (args.optional("driver-threading") ?: "SHARED").uppercase(),
    )
    val archiveThreading = ArchiveThreadingMode.valueOf(
        (args.optional("archive-threading") ?: "SHARED").uppercase(),
    )
    val host = args.optional("host") ?: "localhost"

    // Persist by default. The recorded log and the snapshots taken against it *are* the shard's
    // resumption point, so wiping them has to be something an operator asks for by name rather
    // than something that happens because a flag was forgotten. `--keep` is still accepted, and
    // still means persist, so a script written against the old default does not change meaning.
    //
    // The **archive and cluster directories** only. The Aeron directory is memory-mapped IPC
    // buffers and a cnc.dat -- transient by construction, and always recreated: keeping it makes a
    // restart fail with "Active media driver detected" until the previous driver's liveness
    // timeout expires, and it holds nothing worth keeping. This is the same line the Docker stack
    // draws by making the aeron volume tmpfs and the cluster volume durable.
    val fresh = args.has("fresh")
    if (fresh && args.has("keep")) {
        throw IllegalArgumentException("--fresh and --keep contradict each other")
    }

    // memberId,ingress,consensus,log,catchup,archiveControl
    val members = args.optional("members")
        ?: "0,$host:20110,$host:20220,$host:20330,$host:20440,$host:8010"

    // Who may connect as which gateway. Optional: without it every client connects anonymously,
    // the engine learns its routes from traffic, and the shard behaves exactly as it did before
    // this existed. With it, a gateway presenting the right secret has its id stamped on the
    // session as the encoded principal, which is what lets the engine bind that gateway's
    // participants at session open on every node (Design.md §1).
    val registryFile = args.optional("participants")
    val registry = registryFile?.let(ParticipantRegistry::load)
    // Re-read while the module runs, so onboarding a participant or rotating a gateway secret is
    // a gateway restart rather than a node restart. --participants-reload-ms 0 turns it off.
    val reloadMs = args.optional("participants-reload-ms")?.toLong() ?: DEFAULT_REGISTRY_RELOAD_MS
    val registrySource = registry?.let {
        ParticipantRegistrySource(registryFile, it) { event -> println("cluster: $event") }
            .also { source -> if (reloadMs > 0) source.startPolling(reloadMs) }
    }
    val authenticator = registrySource?.let { source ->
        RegistryAuthenticatorSupplier(source::registry)
    }

    println("cluster: aeronDir=$aeronDir archive=$archiveDir cluster=$clusterDir")
    println("cluster: ingress term length=$ingressTermLength driver=$driverThreading archive=$archiveThreading")
    println(
        if (registry == null) "cluster: no participant registry (pass --participants to authenticate gateways)"
        else "cluster: participants=$registryFile fingerprint=${registry.fingerprint()} " +
            "gateways=${registry.gateways.map { it.gatewayId }} " +
            (if (reloadMs > 0) "reload=${reloadMs}ms" else "reload=off")
    )
    println("cluster: members=$members")
    println(
        if (fresh) "cluster: --fresh -- deleting the archive and cluster directories on start"
        else "cluster: persisting the archive and cluster directories (pass --fresh to wipe them)"
    )

    val driverContext = MediaDriver.Context()
        .aeronDirectoryName(aeronDir)
        .threadingMode(driverThreading)
        .dirDeleteOnStart(true)
        .dirDeleteOnShutdown(true)
        .errorHandler { it.printStackTrace() }

    val archiveContext = Archive.Context()
        .aeronDirectoryName(aeronDir)
        .archiveDir(File(archiveDir))
        .controlChannel("aeron:udp?endpoint=$host:8010")
        .replicationChannel("aeron:udp?endpoint=$host:0")
        .recordingEventsEnabled(false)
        .threadingMode(archiveThreading)
        .deleteArchiveOnStart(fresh)
        .errorHandler { it.printStackTrace() }

    val consensusContext = ConsensusModule.Context()
        .aeronDirectoryName(aeronDir)
        .clusterDir(File(clusterDir))
        .clusterMemberId(0)
        .clusterMembers(members)
        // The ingress term length caps how far the gateway's publication may run ahead of the
        // consensus module's ingress subscription, so it bounds how much of a hiccup the shard can
        // absorb before the sender is flow-controlled. 64k is small; whether it is the shard's
        // throughput ceiling is measured with `most counters` and a sweep, not assumed.
        .ingressChannel("aeron:udp?term-length=$ingressTermLength")
        .replicationChannel("aeron:udp?endpoint=$host:0")
        .deleteDirOnStart(fresh)
        .errorHandler { it.printStackTrace() }
    authenticator?.let(consensusContext::authenticatorSupplier)

    ClusteredMediaDriver.launch(driverContext, archiveContext, consensusContext).use {
        println("cluster: started, awaiting shutdown signal")
        ShutdownSignalBarrier().use { it.await() }
        // Inside nothing that races the exit, but still worth printing: an authenticated gateway
        // count of zero on a shard that configured a registry means every gateway connected
        // anonymously, which looks identical to it working until a maker goes quiet.
        registrySource?.close()
        authenticator?.authenticator?.let {
            println(
                "cluster: authenticatedGateways=${it.authenticatedGateways} " +
                    "rejectedSessions=${it.rejectedSessions}"
            )
        }
        println("cluster: shutdown signal received")
    }
}

/**
 * Asks the cluster to take a snapshot.
 *
 * Two forms, because the two situations are different. `--ingress` sends an admin request through
 * consensus, so every member snapshots at the same log position and the request is *answered* --
 * unlike the four operator commands, which the engine applies or rejects in silence. `--dir` uses
 * the local control toggle instead, for an operator on the node itself with no ingress endpoint to
 * hand.
 */
private fun runClusterSnapshot(args: Args) {
    val ingressEndpoints = args.optional("ingress")
    if (ingressEndpoints != null) {
        val aeronDir = args.optional("aeron-dir")
        val context = Aeron.Context()
        aeronDir?.let(context::aeronDirectoryName)
        Aeron.connect(context).use { aeron ->
            val clusterContext = AeronCluster.Context()
                .aeron(aeron)
                .ownsAeronClient(false)
                .ingressChannel(args.optional("ingress-channel") ?: "aeron:udp")
                .egressChannel(args.optional("egress-channel") ?: "aeron:udp?endpoint=localhost:0")
                .ingressEndpoints(ingressEndpoints)

            val cluster = try {
                AeronCluster.connect(clusterContext)
            } catch (e: io.aeron.exceptions.TimeoutException) {
                System.err.println(
                    "most: could not reach the cluster (${e.message}).\n" +
                        "  Check that the consensus module is running and that --ingress names it."
                )
                return
            }
            cluster.use {
                val correlationId = it.context().aeron().nextCorrelationId()
                if (it.sendAdminRequestToTakeASnapshot(correlationId)) {
                    println("cluster: snapshot requested (correlationId=$correlationId)")
                } else {
                    System.err.println("most: the cluster refused the snapshot request")
                }
            }
        }
        return
    }

    val clusterDir = File(clusterDirOf(args))
    requireClusterDir(clusterDir)
    if (ClusterTool.snapshot(clusterDir, System.out)) {
        println("cluster: snapshot taken via $clusterDir")
    } else {
        System.err.println("most: snapshot failed -- is the consensus module running?")
    }
}

/**
 * Snapshots, then stops the node. The point of having it as a command is that the alternative --
 * SIGTERM to the cluster host -- leaves no snapshot, so the next start replays from wherever the
 * last one was.
 */
private fun runClusterShutdown(args: Args) {
    val clusterDir = File(clusterDirOf(args))
    requireClusterDir(clusterDir)
    if (ClusterTool.shutdown(clusterDir, System.out)) {
        println("cluster: shutdown requested (a snapshot is taken first) via $clusterDir")
    } else {
        System.err.println("most: shutdown failed -- is the consensus module running?")
    }
}

private fun clusterDirOf(args: Args): String =
    args.optional("cluster-dir") ?: "${args.optional("dir") ?: "build/cluster-host"}/cluster"

private fun requireClusterDir(dir: File) {
    require(dir.isDirectory) {
        "cluster directory not found: $dir (pass --dir or --cluster-dir)"
    }
}
