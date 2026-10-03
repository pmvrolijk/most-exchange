package com.engine.gateway

import com.engine.reference.DutyCycles
import com.engine.reference.GatewayCredentialsSupplier
import com.engine.reference.GatewayIdentity
import com.engine.reference.IdleStrategySpec
import com.engine.reference.LatencyHistogram
import com.engine.reference.ParticipantRegistry
import com.engine.reference.ParticipantRegistrySource
import com.engine.reference.ShardSpec
import io.aeron.Aeron
import io.aeron.Publication
import io.aeron.ControlledFragmentAssembler
import io.aeron.cluster.client.AeronCluster
import io.aeron.cluster.client.EgressListener
import io.aeron.logbuffer.ControlledFragmentHandler
import org.agrona.DirectBuffer
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
    println(
        "gateway: placement=${config.placement.name.lowercase()} " +
            "ingress=${config.ingressChannel} egress=${config.egressChannel}" +
            if (config.placement == GatewayPlacement.COLOCATED) " leaderPoll=${config.leaderPollMs}ms" else ""
    )

    // Resolved before anything connects. A gateway that authenticates as nobody still trades --
    // its own clients' reports come back on the session their orders went out on -- but the
    // participants it speaks for are then reachable only while they keep speaking, which is the
    // failure this identity exists to remove. Better said at boot than discovered in a counter.
    val identity = config.identity()
    if (identity == null) {
        println(
            "gateway: no cluster identity (${GatewayConfig.GATEWAY_ID} unset). Connecting " +
                "anonymously: the engine will learn routes from traffic, so a participant of " +
                "this gateway that has been quiet since connect will not be sent its fills."
        )
    } else {
        println(
            "gateway: identity=${identity.gatewayId} " +
                "registry=${config.participantRegistry?.fingerprint()} " +
                "reload=${config.registryReloadMs}ms " + grants(identity)
        )
    }

    // Re-read while the gateway runs, because this is where the registry is enforced: revoking a
    // participant must not wait for a restart (Design.md §1). Legal here for the reason it would
    // not be in the engine -- a refusal never reaches the log, so no two processes need to agree
    // on when a new file took effect.
    val registrySource: ParticipantRegistrySource? =
        if (identity == null) null
        else config.participantRegistryFile?.let { path ->
            lateinit var source: ParticipantRegistrySource
            // Each swap is announced with this gateway's own grants, since that -- not the whole
            // file's fingerprint -- is what an operator revoking a participant is waiting to see.
            source = ParticipantRegistrySource(path, config.participantRegistry!!) { event ->
                println("gateway: $event")
                val now = source.registry().gateway(identity.gatewayId)
                println(
                    if (now == null) "gateway: ${identity.gatewayId} is no longer in the registry: " +
                        "placing nothing, forwarding no operator command, passing cancels it allowed"
                    else "gateway: now ${grants(now)}"
                )
            }
            source.also { if (config.registryReloadMs > 0) it.startPolling(config.registryReloadMs) }
        }
    val access: GatewayAccess = when {
        identity == null -> GatewayAccess.OPEN
        registrySource != null -> RegistryAccess(registrySource::registry, identity.gatewayId)
        else -> config.participantRegistry!!.let { fixed -> RegistryAccess({ fixed }, identity.gatewayId) }
    }

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

        // A fresh context per connection: a co-located gateway connects again every time its node
        // is elected, and an Aeron context is concluded, and closed, with the client it made.
        val clusterContext = {
            val context = AeronCluster.Context()
                .aeron(aeron)
                .ownsAeronClient(false)
                .egressListener(egressListener)
                .ingressChannel(config.ingressChannel)
                .egressChannel(config.egressChannel)
            // The consensus module verifies this against the same registry file and stamps the
            // gateway id on the session as its encoded principal, which reaches every node through
            // the replicated log. Wrong credentials are rejected outright rather than downgraded to
            // an anonymous session, so a failure here is a failure to connect, not a quiet loss of
            // fills.
            config.credentials()?.let { (gatewayId, token) ->
                context.credentialsSupplier(GatewayCredentialsSupplier(gatewayId, token))
            }
            // Multi-node clusters advertise every member's ingress endpoint so the client can find
            // the leader; a channel carrying its own endpoint is the single-node shorthand.
            config.ingressEndpoints?.let(context::ingressEndpoints)
            context
        }
        val link = ClusterLink(clusterContext)

        // An independent gateway connects once, now, and a failure to is a failure to start. A
        // co-located one starts standing by, and connects only while its node leads.
        if (config.placement == GatewayPlacement.INDEPENDENT) {
            try {
                link.connectNow()
            } catch (e: io.aeron.exceptions.TimeoutException) {
                System.err.println(
                    "gateway: could not reach the cluster (${e.message}).\n" +
                        "  Check that the consensus module is running and that ingressChannel points at it."
                )
                return@use
            }
        }
        val leaderWatch =
            if (config.placement == GatewayPlacement.COLOCATED) LeaderWatch(aeron.countersReader()) else null

        try {
            // Counted separately so an operator can tell "nothing arrived" from "forwarded but
            // nothing came back" -- the two look identical from order state alone.
            val sink = object : GatewaySink {
                var forwardedToCluster = 0L
                var clusterBackpressure = 0L
                var sentToClient = 0L
                var droppedToClient = 0L
                var standbyRejects = 0L

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
                    // A co-located gateway with no session: held while it connects, which takes
                    // milliseconds over IPC, and refused while its node does not lead, so a client
                    // learns at once to try the gateway on another node (Design.md §7).
                    val cluster = link.cluster ?: return if (link.isConnecting) {
                        clusterBackpressure++
                        ClusterOffer.RETRY
                    } else {
                        standbyRejects++
                        ClusterOffer.FAILED
                    }
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
            service = GatewayService(config.shard.securityIds, sink, metrics = metrics, access = access)

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

            // One loop carries every order in and every report out, so how full it is decides
            // whether a second gateway would help (Design.md §7, "Duty cycle"; Measurements.md A5).
            val duty = if (config.metricsEnabled) DutyCycles(SystemNanoClock.INSTANCE) else null
            val idle = config.idleStrategy.create().let { duty?.wrap("gateway ${config.gatewayId ?: "anonymous"}", it) ?: it }
            duty?.attachInBackground(aeron, { println("gateway: duty cycle counters $it") })
            println("gateway: idle strategy ${config.idleStrategy}")
            val barrier = ShutdownSignalBarrier()
            println("gateway: started")

            val leaderPollNs = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(config.leaderPollMs)
            val worker = Thread({
                var lastKeepAliveNs = System.nanoTime()
                var nextRoleCheckNs = 0L
                while (!Thread.currentThread().isInterrupted) {
                    var work = inbound.controlledPoll(assembler, FRAGMENT_LIMIT)
                    val now = System.nanoTime()
                    val cluster = link.cluster
                    if (cluster != null) {
                        work += cluster.pollEgress()

                        // A cluster session that sends nothing is closed by the consensus module
                        // after its session timeout, and every later offer then fails silently.
                        // Polling egress is not enough -- a quiet market would kill the gateway.
                        if (now - lastKeepAliveNs >= KEEP_ALIVE_INTERVAL_NS) {
                            cluster.sendKeepAlive()
                            lastKeepAliveNs = now
                        }

                        if (cluster.isClosed) {
                            if (leaderWatch == null) {
                                System.err.println(
                                    "gateway: cluster session closed; orders can no longer be forwarded"
                                )
                                break
                            }
                            link.drop()
                            println("gateway: cluster session closed; standing by")
                        }
                    }
                    if (leaderWatch != null) {
                        work += link.pollConnect()?.let { connected ->
                            lastKeepAliveNs = now
                            println(
                                "gateway: active -- this node leads, session ${connected.clusterSessionId()} " +
                                    "over ${config.egressChannel}"
                            )
                            1
                        } ?: 0
                        // The role, not the cluster client, says when to stand down: with IPC
                        // egress a new leader elsewhere is never announced here (LeaderWatch).
                        if (now - nextRoleCheckNs >= 0) {
                            nextRoleCheckNs = now + leaderPollNs
                            val leads = leaderWatch.isLeader()
                            if (leads && link.isIdle) {
                                println("gateway: this node leads; connecting")
                                link.connectAsync()
                            } else if (!leads && !link.isIdle) {
                                link.drop()
                                println("gateway: this node no longer leads; standing by")
                            }
                        }
                    }
                    idle.idle(work)
                }
            }, "gateway-poller")
            worker.start()

            barrier.use {
                it.await()
                worker.interrupt()
                registrySource?.close()
                // Inside the barrier: closing it releases the signal and the JVM exits at once.
                println(
                    "gateway: stopped. forwardedToCluster=${sink.forwardedToCluster} " +
                        "clusterBackpressure=${sink.clusterBackpressure} " +
                        "sentToClient=${sink.sentToClient} " +
                        "droppedToClient=${sink.droppedToClient} " +
                        "standbyRejects=${sink.standbyRejects} activations=${link.activations} " +
                        "rejectedLocally=${service.rejectedLocally} " +
                        "unreachableRejects=${service.unreachableRejects} " +
                        "undeliverableCommands=${service.undeliverableCommands} " +
                        "unauthorizedRejects=${service.unauthorizedRejects} " +
                        "refusedCommands=${service.refusedCommands} " +
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
        } finally {
            link.close()
        }
    }
}

/**
 * The gateway's cluster session, and for a co-located gateway the getting of one.
 *
 * Only the poller thread touches it once that thread has started, so nothing here is shared.
 */
private class ClusterLink(private val newContext: () -> AeronCluster.Context) {
    var cluster: AeronCluster? = null
        private set
    private var connecting: AeronCluster.AsyncConnect? = null

    /** Sessions this gateway has opened: one for an independent gateway, one per election won here. */
    var activations = 0L
        private set

    val isConnecting: Boolean get() = connecting != null
    val isIdle: Boolean get() = cluster == null && connecting == null

    fun connectNow() {
        cluster = AeronCluster.connect(newContext())
        activations++
    }

    fun connectAsync() {
        connecting = AeronCluster.asyncConnect(newContext())
    }

    /** Advances a connect in progress; the session, once, when it completes. */
    fun pollConnect(): AeronCluster? {
        val pending = connecting ?: return null
        val connected = try {
            pending.poll()
        } catch (e: RuntimeException) {
            // Most often the role moved while connecting. The next role check decides again.
            System.err.println("gateway: connect failed (${e.message}); standing by")
            pending.close()
            connecting = null
            return null
        } ?: return null
        connecting = null
        cluster = connected
        activations++
        return connected
    }

    fun drop() {
        connecting?.close()
        connecting = null
        cluster?.close()
        cluster = null
    }

    fun close() = drop()
}

private const val FRAGMENT_LIMIT = 64

/** What a registry entry lets this gateway do, as printed at startup and on every reload. */
private fun grants(identity: GatewayIdentity): String =
    "participants=${identity.participants.sorted()} cancelOnly=${identity.cancelOnly.sorted()} " +
        "operator=${identity.operator}"

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
    /** The poller's idle strategy; busy-spin unless configured (Design.md §7, "Duty cycle"). */
    val idleStrategy: IdleStrategySpec = IdleStrategySpec.BUSY_SPIN,
    /**
     * Which gateway speaks for which participant, or null to connect anonymously.
     *
     * Read for two reasons: to check at boot that the id this gateway is about to present is in
     * the file, and to **enforce** what that entry grants on every client message -- which
     * participants may place, which may only cancel, whether operator commands pass
     * (Design.md §1, "Enforcement, at the gateway"). The consensus module authenticates against
     * the same file.
     */
    val participantRegistry: ParticipantRegistry? = null,
    /** Where [participantRegistry] was read from, so it can be re-read. */
    val participantRegistryFile: String? = null,
    /** How often to re-read it, in milliseconds; 0 turns reloading off. */
    val registryReloadMs: Long = DEFAULT_REGISTRY_RELOAD_MS,
    /** This gateway's id in [participantRegistry]. Null connects anonymously. */
    val gatewayId: String? = null,
    /**
     * The shared secret behind [ParticipantRegistry] entry's SHA-256. Kept out of the registry
     * file on purpose: that file is published to every node, and a secret that travels that far
     * is not one. [CREDENTIAL_TOKEN_FILE] is the better home for it than a properties value.
     */
    val credentialToken: String? = null,
    /** Where this gateway runs relative to the cluster (Design.md §7, "Gateway placement"). */
    val placement: GatewayPlacement = GatewayPlacement.INDEPENDENT,
    /** How often a co-located gateway reads its node's role, in milliseconds. */
    val leaderPollMs: Long = DEFAULT_LEADER_POLL_MS,
) {
    init {
        if (placement == GatewayPlacement.COLOCATED) {
            require(aeronDirectoryName != null) {
                "$PLACEMENT=colocated runs on its node's own media driver: set gateway.aeronDir to " +
                    "the cluster host's Aeron directory"
            }
            require(gatewayId != null) {
                "$PLACEMENT=colocated needs $GATEWAY_ID: every node's gateway presents the same " +
                    "registry identity, so whichever one is active is the primary for its " +
                    "participants and takes their routes the moment it connects"
            }
            require(ingressChannel.startsWith(IPC) && egressChannel.startsWith(IPC) && ingressEndpoints == null) {
                "$PLACEMENT=colocated reaches the leader over IPC, so gateway.ingressChannel and " +
                    "gateway.egressChannel must be aeron:ipc and gateway.ingressEndpoints unset " +
                    "(got ingress=$ingressChannel egress=$egressChannel endpoints=$ingressEndpoints)"
            }
            require(leaderPollMs > 0) { "$LEADER_POLL_MS must be positive, got $leaderPollMs" }
        }
        require((gatewayId == null) == (credentialToken == null)) {
            "$GATEWAY_ID and one of $CREDENTIAL_TOKEN / $CREDENTIAL_TOKEN_FILE must be set " +
                "together: an id with no secret cannot authenticate, and a secret with no id " +
                "has nothing to authenticate as"
        }
        require(gatewayId == null || participantRegistry != null) {
            "$GATEWAY_ID is set but $PARTICIPANT_REGISTRY is not, so there is nothing to check " +
                "the id against"
        }
        val registry = participantRegistry
        if (gatewayId != null && registry != null) {
            require(registry.gateway(gatewayId) != null) {
                "gateway id '$gatewayId' is not in the participant registry, which registers " +
                    registry.gateways.map { it.gatewayId }
            }
            require(registry.shardId == shard.shardId) {
                "$PARTICIPANT_REGISTRY is for shard ${registry.shardId}, but this gateway " +
                    "serves shard ${shard.shardId}"
            }
        }
    }

    /** This gateway's registry entry, or null when it connects anonymously. */
    fun identity(): GatewayIdentity? =
        gatewayId?.let { participantRegistry?.gateway(it) }

    /** The id and secret to present, or null to connect with no credentials at all. */
    fun credentials(): Pair<String, String>? {
        val id = gatewayId ?: return null
        val token = credentialToken ?: return null
        return id to token
    }

    companion object {
        const val SECURITIES_FILE = "gateway.securitiesFile"
        const val PARTICIPANT_REGISTRY = "gateway.participantRegistry"
        const val PARTICIPANT_REGISTRY_RELOAD_MS = "gateway.participantRegistry.reloadMs"
        /** The same default the nodes use (`engine.participantRegistry.reloadMs`). */
        const val DEFAULT_REGISTRY_RELOAD_MS = 5_000L
        const val GATEWAY_ID = "gateway.gatewayId"
        const val CREDENTIAL_TOKEN = "gateway.credentialToken"
        const val CREDENTIAL_TOKEN_FILE = "gateway.credentialTokenFile"
        const val METRICS_ENABLED = "gateway.metrics"
        const val METRICS_FILE = "gateway.metrics.file"
        const val IDLE_STRATEGY = "gateway.idleStrategy"
        const val PLACEMENT = "gateway.placement"
        const val LEADER_POLL_MS = "gateway.leaderPollMs"
        /** Well inside an election, and a read of one counter: cheap enough to do often. */
        const val DEFAULT_LEADER_POLL_MS = 10L
        private const val IPC = "aeron:ipc"

        fun from(
            properties: Properties,
            shard: ShardSpec,
            registry: ParticipantRegistry? = null,
        ): GatewayConfig = GatewayPlacement.parse(properties.getProperty(PLACEMENT)).let { placement ->
            // Co-located means IPC both ways, so it is the default there rather than a second
            // thing to remember; an explicit UDP channel is refused, not silently overridden.
            val colocated = placement == GatewayPlacement.COLOCATED
            GatewayConfig(
                shard = shard,
                aeronDirectoryName = properties.getProperty("gateway.aeronDir"),
                ingressChannel = properties.getProperty("gateway.ingressChannel") ?: if (colocated) IPC else "aeron:udp",
                ingressEndpoints = properties.getProperty("gateway.ingressEndpoints"),
                egressChannel = properties.getProperty("gateway.egressChannel")
                    ?: if (colocated) IPC else "aeron:udp?endpoint=localhost:9020",
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
                idleStrategy = IdleStrategySpec.parse(properties.getProperty(IDLE_STRATEGY)),
                participantRegistry = registry,
                participantRegistryFile = properties.getProperty(PARTICIPANT_REGISTRY)?.trim()?.ifEmpty { null },
                registryReloadMs = properties.getProperty(PARTICIPANT_REGISTRY_RELOAD_MS)?.trim()?.toLong()
                    ?: DEFAULT_REGISTRY_RELOAD_MS,
                gatewayId = properties.getProperty(GATEWAY_ID)?.trim()?.ifEmpty { null },
                credentialToken = credentialToken(properties),
                placement = placement,
                leaderPollMs = properties.getProperty(LEADER_POLL_MS)?.trim()?.toLong() ?: DEFAULT_LEADER_POLL_MS,
            )
        }

        /**
         * The secret, from a file if one is named and from the property otherwise.
         *
         * The file wins, and is the form to use: a properties file full of endpoints and stream
         * ids ends up in a repository, and a secret in it ends up there too. Trailing whitespace
         * is trimmed because a secret in a file almost always ends with a newline nobody typed.
         */
        private fun credentialToken(properties: Properties): String? {
            val path = properties.getProperty(CREDENTIAL_TOKEN_FILE)?.trim()?.ifEmpty { null }
            if (path != null) {
                val file = File(path)
                require(file.isFile) { "$CREDENTIAL_TOKEN_FILE not found: $path" }
                val token = file.readText().trim()
                require(token.isNotEmpty()) { "$CREDENTIAL_TOKEN_FILE is empty: $path" }
                return token
            }
            return properties.getProperty(CREDENTIAL_TOKEN)?.trim()?.ifEmpty { null }
        }

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
            val registryFile = properties.getProperty(PARTICIPANT_REGISTRY)
            return from(
                properties,
                ShardSpec.load(securitiesFile),
                registryFile?.let(ParticipantRegistry::load),
            )
        }
    }
}

/**
 * Where a gateway runs relative to the cluster (Design.md §7, "Gateway placement").
 *
 * [INDEPENDENT] is its own tier: one cluster session, held across failovers by Aeron's client, with
 * execution reports over UDP. [COLOCATED] is one gateway per node on that node's media driver,
 * active only while the node leads, with both legs over IPC -- the throughput of IPC egress, paid
 * for with a gateway switch on every failover.
 */
enum class GatewayPlacement {
    INDEPENDENT,
    COLOCATED;

    companion object {
        fun parse(value: String?): GatewayPlacement =
            value?.trim()?.ifEmpty { null }?.let { name ->
                entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                    ?: throw IllegalArgumentException(
                        "${GatewayConfig.PLACEMENT} must be independent or colocated, got '$name'"
                    )
            } ?: INDEPENDENT
    }
}
