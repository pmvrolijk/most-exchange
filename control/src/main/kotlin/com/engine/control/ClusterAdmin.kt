package com.engine.control

import com.engine.reference.GatewayCredentialsSupplier
import io.aeron.Aeron
import io.aeron.cluster.client.AeronCluster
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Asks a shard's cluster to take a snapshot.
 *
 * **This is the one thing the control plane does not send through the gateway**, and the exception
 * is deliberate rather than an oversight. A snapshot is a cluster admin request, not a message for
 * the log: there is no SBE for it, the engine never sees it, and nothing about it can bypass the
 * gateway's validation because it carries no order flow. Everything else here still goes to the
 * gateway's client channel exactly as `most` does.
 *
 * The ingress endpoints therefore live in **this process's own configuration**, not in the database
 * and not in the discovery broadcast. `docs/ControlPlane.md` already draws that line -- a release
 * carries topology, deployment stays in each process's config -- and keeping cluster ingress out of
 * the directory is what stops an upstream adapter from finding the cluster and skipping the gateway.
 *
 * Also unlike the four operator commands, a snapshot **is** acknowledged. Aeron answers the admin
 * request, so `confirmed` here means confirmed rather than merely sent.
 */
@Component
class ClusterAdmin(
    private val link: ClusterLink,
    /**
     * Read per shard on demand, as `control.cluster.ingress.<shardId>`, rather than bound as a map.
     * A map would have to declare its shard ids up front in the YAML; looking them up through the
     * environment means a shard configured only by an environment variable works the same way, and
     * relaxed binding maps `CONTROL_CLUSTER_INGRESS_0` onto it without any further help.
     */
    private val environment: org.springframework.core.env.Environment,
    @Value("\${control.cluster.ingressChannel:aeron:udp}")
    private val ingressChannel: String,
    @Value("\${control.cluster.egressChannel:aeron:udp?endpoint=0.0.0.0:0}")
    private val egressChannel: String,
) {
    private val log = LoggerFactory.getLogger(ClusterAdmin::class.java)

    // One connection per shard, held open. Connecting costs a round trip and a pair of buffers,
    // and a scheduled snapshot at every session close would otherwise pay it every time.
    private val clusters = ConcurrentHashMap<Int, AeronCluster>()

    /**
     * Aeron's member-endpoint form -- `0=host:port` for one node, comma-separated across members so
     * the client can find the leader. Null when the shard has none configured, which the caller
     * reports as such rather than turning into a connection attempt that times out.
     */
    fun endpointsFor(shardId: Int): String? =
        environment.getProperty("control.cluster.ingress.$shardId")?.takeIf { it.isNotBlank() }

    /**
     * Requests a snapshot on [shardId].
     *
     * Sequenced through consensus, so every member snapshots at the same log position -- which is
     * what makes the resulting snapshot restorable by any of them.
     */
    fun snapshot(shardId: Int): CommandResult {
        val endpoints = endpointsFor(shardId)
            ?: return CommandResult(
                command = "snapshot",
                sent = false,
                confirmed = false,
                detail = "no cluster ingress configured for shard $shardId " +
                    "(set control.cluster.ingress.$shardId)",
            )
        val aeron = link.aeron()
            ?: return CommandResult(
                command = "snapshot",
                sent = false,
                confirmed = false,
                detail = "no Aeron media driver: ${link.status().detail}",
            )

        val credentials = try {
            credentialsFor(environment, shardId)
        } catch (e: IllegalArgumentException) {
            return CommandResult("snapshot", sent = false, confirmed = false, detail = e.message ?: "bad identity")
        }

        val cluster = try {
            connect(shardId, aeron, endpoints, credentials)
        } catch (e: Exception) {
            // Never fatal, for the same reason the rest of the link is optional: authoring
            // reference data must keep working with no exchange running anywhere near it.
            log.warn("cluster admin: could not reach shard {} at {}", shardId, endpoints, e)
            return CommandResult(
                command = "snapshot",
                sent = false,
                confirmed = false,
                detail = "could not reach the cluster at $endpoints (${e.message})",
            )
        }

        return try {
            val correlationId = aeron.nextCorrelationId()
            if (cluster.sendAdminRequestToTakeASnapshot(correlationId)) {
                CommandResult(
                    command = "snapshot",
                    sent = true,
                    // The cluster answers this one, which none of the four operator commands do.
                    confirmed = true,
                    detail = "shard $shardId took a snapshot (correlationId=$correlationId)",
                )
            } else {
                CommandResult("snapshot", sent = true, confirmed = false, detail = "the cluster refused the request")
            }
        } catch (e: Exception) {
            clusters.remove(shardId)?.let { runCatching { it.close() } }
            CommandResult("snapshot", sent = false, confirmed = false, detail = "snapshot failed: ${e.message}")
        }
    }

    private fun connect(
        shardId: Int,
        aeron: Aeron,
        endpoints: String,
        credentials: Pair<String, String>?,
    ): AeronCluster {
        clusters[shardId]?.let { if (!it.isClosed) return it else clusters.remove(shardId) }
        val context = AeronCluster.Context()
            .aeron(aeron)
            .ownsAeronClient(false)
            .ingressChannel(ingressChannel)
            .egressChannel(egressChannel)
            .ingressEndpoints(endpoints)
        credentials?.let { (identity, secret) ->
            context.credentialsSupplier(GatewayCredentialsSupplier(identity, secret))
        }
        val cluster = AeronCluster.connect(context)
        clusters[shardId] = cluster
        return cluster
    }

    companion object {
        /**
         * The identity this process presents to [shardId]'s cluster, or null for none.
         *
         * A node running with a participant registry refuses a session with no credentials, since
         * an anonymous session skips every gateway check (Design.md §1). The control plane is then
         * named by an operator-only entry in that shard's registry -- `operator=true`, no
         * participants -- authored here like any other gateway, and presented exactly as a gateway
         * presents its own. Per shard, as `control.cluster.identity.<shardId>` and
         * `control.cluster.secretFile.<shardId>`, for the reason [endpointsFor] is. The secret comes
         * from a file only, so it is never in the YAML or the environment listing.
         */
        fun credentialsFor(
            environment: org.springframework.core.env.Environment,
            shardId: Int,
        ): Pair<String, String>? {
            val identity = environment.getProperty("control.cluster.identity.$shardId")
                ?.trim()?.ifEmpty { null }
            val secretFile = environment.getProperty("control.cluster.secretFile.$shardId")
                ?.trim()?.ifEmpty { null }
            require((identity == null) == (secretFile == null)) {
                "control.cluster.identity.$shardId and control.cluster.secretFile.$shardId go " +
                    "together: an identity with no secret cannot authenticate, and a secret with no " +
                    "identity has nothing to authenticate as"
            }
            if (identity == null || secretFile == null) return null
            val file = File(secretFile)
            require(file.isFile) { "control.cluster.secretFile.$shardId not found: $secretFile" }
            val secret = file.readText().trim()
            require(secret.isNotEmpty()) { "control.cluster.secretFile.$shardId is empty: $secretFile" }
            return identity to secret
        }
    }

    @PreDestroy
    fun stop() {
        clusters.values.forEach { runCatching { it.close() } }
        clusters.clear()
    }
}
