package com.engine.tools

import com.engine.reference.AdminResponses
import com.engine.reference.DutyCycles
import com.engine.reference.GatewayCredentialsSupplier
import com.engine.reference.ParticipantRegistry
import com.engine.reference.ParticipantRegistrySource
import com.engine.reference.RegistryAuthenticatorSupplier
import com.engine.reference.RegistryAuthorisationService
import com.engine.reference.requestSnapshot
import io.aeron.Aeron
import io.aeron.archive.Archive
import io.aeron.archive.ArchiveThreadingMode
import io.aeron.cluster.ClusterMember
import io.aeron.cluster.ClusterTool
import io.aeron.cluster.ClusteredMediaDriver
import io.aeron.cluster.ConsensusModule
import io.aeron.cluster.client.AeronCluster
import io.aeron.driver.MediaDriver
import io.aeron.cluster.service.ClusteredServiceContainer
import io.aeron.driver.Configuration as DriverConfiguration
import io.aeron.driver.ThreadingMode
import io.aeron.security.AuthorisationService
import io.aeron.security.AuthorisationServiceSupplier
import org.agrona.concurrent.ShutdownSignalBarrier
import org.agrona.concurrent.SystemNanoClock
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
    // Which entry of the member string this process is. Every member is passed the same string and
    // differs only here, so a node that names the wrong id binds another member's endpoints --
    // refused below rather than discovered as an election that never forms.
    val memberId = args.int("member-id", 0)
    val thisMember = ClusterMember.findMember(ClusterMember.parse(members), memberId)
        ?: throw IllegalArgumentException("--member-id $memberId is not in --members $members")
    // A client on this node's own media driver may then reach the consensus module over IPC, which
    // it subscribes to only while it is the leader (Design.md §7, "Gateway placement"). Off by
    // default, as in Aeron: an independent gateway has no use for it.
    val ipcIngress = args.has("ipc-ingress")

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
    println("cluster: member=$memberId members=$members ipcIngress=$ipcIngress")
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
        // The member string's archive endpoint, so the other members' catch-up and snapshot
        // replication reach this archive and not whichever one happens to own the default port.
        .controlChannel("aeron:udp?endpoint=${thisMember.archiveEndpoint()}")
        .replicationChannel("aeron:udp?endpoint=$host:0")
        .recordingEventsEnabled(false)
        .threadingMode(archiveThreading)
        .deleteArchiveOnStart(fresh)
        .errorHandler { it.printStackTrace() }

    val consensusContext = ConsensusModule.Context()
        .aeronDirectoryName(aeronDir)
        .clusterDir(File(clusterDir))
        .clusterMemberId(memberId)
        .clusterMembers(members)
        .isIpcIngressAllowed(ipcIngress)
        // The ingress term length caps how far the gateway's publication may run ahead of the
        // consensus module's ingress subscription, so it bounds how much of a hiccup the shard can
        // absorb before the sender is flow-controlled. 64k is small; whether it is the shard's
        // throughput ceiling is measured with `most counters` and a sweep, not assumed.
        .ingressChannel("aeron:udp?term-length=$ingressTermLength")
        .replicationChannel("aeron:udp?endpoint=$host:0")
        .deleteDirOnStart(fresh)
        .errorHandler { it.printStackTrace() }
    authenticator?.let(consensusContext::authenticatorSupplier)
    // Who may ask for a snapshot through consensus (Design.md §1). Aeron's default grants no
    // snapshot request at all, so without this every `snapshot --ingress` and every control-plane
    // snapshot was refused. With a registry, only an operator=true identity; without one, anyone,
    // since nothing there is authenticated to check.
    consensusContext.authorisationServiceSupplier(
        registrySource?.let { source -> AuthorisationServiceSupplier { RegistryAuthorisationService(source::registry) } }
            ?: AuthorisationServiceSupplier { AuthorisationService.ALLOW_ALL }
    )

    // How full each of this process's threads is (Design.md §7, "Duty cycle"). Aeron's own idle
    // strategies are kept -- all back off by default -- and only measured around.
    val duty = if (args.has("duty")) DutyCycles(SystemNanoClock.INSTANCE) else null
    duty?.let { wrapThreads(it, driverContext, driverThreading, archiveContext, archiveThreading, consensusContext) }

    ClusteredMediaDriver.launch(driverContext, archiveContext, consensusContext).use {
        // The counters need a client, and a client needs the driver this call just started.
        val counterClient = duty?.let { Aeron.connect(Aeron.Context().aeronDirectoryName(aeronDir)) }
        counterClient?.let { duty.attachInBackground(it, { labels -> println("cluster: duty cycle counters $labels") }) }
        println("cluster: started, awaiting shutdown signal")
        ShutdownSignalBarrier().use { it.await() }
        counterClient?.close()
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
 * Wraps the idle strategy of every thread the chosen threading modes create, around Aeron's own
 * default for that thread, so measuring changes nothing about how the thread idles. Only the threads
 * that will exist: a wrapper for a thread that never runs would publish a counter reading zero,
 * which reads as an idle thread rather than an absent one.
 */
private fun wrapThreads(
    duty: DutyCycles,
    driver: MediaDriver.Context,
    driverThreading: ThreadingMode,
    archive: Archive.Context,
    archiveThreading: ArchiveThreadingMode,
    consensus: ConsensusModule.Context,
) {
    when (driverThreading) {
        ThreadingMode.SHARED -> driver.sharedIdleStrategy(
            duty.wrap("driver shared", DriverConfiguration.sharedIdleStrategy(null)),
        )
        ThreadingMode.SHARED_NETWORK -> {
            driver.conductorIdleStrategy(duty.wrap("driver conductor", DriverConfiguration.conductorIdleStrategy(null)))
            driver.sharedNetworkIdleStrategy(
                duty.wrap("driver sender+receiver", DriverConfiguration.sharedNetworkIdleStrategy(null)),
            )
        }
        ThreadingMode.DEDICATED -> {
            driver.conductorIdleStrategy(duty.wrap("driver conductor", DriverConfiguration.conductorIdleStrategy(null)))
            driver.senderIdleStrategy(duty.wrap("driver sender", DriverConfiguration.senderIdleStrategy(null)))
            driver.receiverIdleStrategy(duty.wrap("driver receiver", DriverConfiguration.receiverIdleStrategy(null)))
        }
        ThreadingMode.INVOKER -> Unit
    }
    when (archiveThreading) {
        ArchiveThreadingMode.SHARED -> archive.idleStrategySupplier(
            duty.supplier("archive", Archive.Configuration.idleStrategySupplier(null)),
        )
        ArchiveThreadingMode.DEDICATED -> {
            archive.idleStrategySupplier(duty.supplier("archive conductor", Archive.Configuration.idleStrategySupplier(null)))
            archive.recorderIdleStrategySupplier(
                duty.supplier("archive recorder", Archive.Configuration.recorderIdleStrategySupplier(null)),
            )
            archive.replayerIdleStrategySupplier(
                duty.supplier("archive replayer", Archive.Configuration.replayerIdleStrategySupplier(null)),
            )
        }
        ArchiveThreadingMode.INVOKER -> Unit
    }
    consensus.idleStrategySupplier(
        duty.supplier("consensus-module", ClusteredServiceContainer.Configuration.idleStrategySupplier(null)),
    )
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
        // A node with a participant registry refuses a session with no credentials (Design.md §1),
        // so the CLI presents an operator-only registry identity exactly as a gateway would.
        val credentials = clusterCredentials(args)
        val aeronDir = args.optional("aeron-dir")
        val context = Aeron.Context()
        aeronDir?.let(context::aeronDirectoryName)
        Aeron.connect(context).use { aeron ->
            val responses = AdminResponses()
            val clusterContext = AeronCluster.Context()
                .aeron(aeron)
                .ownsAeronClient(false)
                // The cluster answers on egress, and that answer -- not the offer -- is the result.
                .egressListener(responses)
                .ingressChannel(args.optional("ingress-channel") ?: "aeron:udp")
                .egressChannel(args.optional("egress-channel") ?: "aeron:udp?endpoint=localhost:0")
                .ingressEndpoints(ingressEndpoints)
            credentials?.let { (identity, secret) ->
                clusterContext.credentialsSupplier(GatewayCredentialsSupplier(identity, secret))
            }

            val cluster = try {
                AeronCluster.connect(clusterContext)
            } catch (e: io.aeron.exceptions.TimeoutException) {
                System.err.println(
                    "most: could not reach the cluster (${e.message}).\n" +
                        "  Check that the consensus module is running and that --ingress names it."
                )
                return
            } catch (e: io.aeron.security.AuthenticationException) {
                System.err.println(
                    "most: the cluster refused this client (${e.message}).\n" +
                        if (credentials == null) "  It runs with a participant registry, which refuses " +
                            "anonymous sessions: pass --identity and --secret-file for an operator entry."
                        else "  Check that '${credentials.first}' is in the shard's registry and the " +
                            "secret file holds its current secret."
                )
                return
            }
            cluster.use {
                val outcome = requestSnapshot(
                    responses,
                    correlationId = it.context().aeron().nextCorrelationId(),
                    timeout = java.time.Duration.ofSeconds(args.long("timeout", 30L)),
                    send = it::sendAdminRequestToTakeASnapshot,
                    poll = it::pollEgress,
                )
                if (outcome.confirmed) {
                    println("cluster: snapshot taken, the cluster answered ${outcome.code}")
                } else {
                    System.err.println(
                        "most: no snapshot -- ${outcome.message}" +
                            if (outcome.code == io.aeron.cluster.codecs.AdminResponseCode.UNAUTHORISED_ACCESS)
                                "\n  Only an operator=true registry identity may request one (Design.md §1)."
                            else ""
                    )
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
