package nl.lamia.most.exchange.control

import nl.lamia.most.exchange.reference.AdminResponses
import nl.lamia.most.exchange.reference.GatewayCredentialsSupplier
import nl.lamia.most.exchange.reference.requestSnapshot
import io.aeron.Aeron
import io.aeron.cluster.client.AeronCluster
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File
import java.time.Duration

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
 * Also unlike the four operator commands, a snapshot **is** acknowledged: the cluster answers the
 * admin request on egress, and `confirmed` means that answer was OK. It used to mean only that the
 * request was offered, while Aeron's default authorisation was refusing every one (Design.md §1) --
 * the consensus module now grants it to an `operator=true` identity, which is what
 * [credentialsFor] presents.
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

        val responses = AdminResponses()
        val cluster = try {
            connect(aeron, endpoints, credentials, responses)
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

        // Connected per request and closed after, never cached: a held session that sends no
        // keepalives is closed by the consensus module after its session timeout, and the next
        // snapshot would then be offered to a dead session.
        return cluster.use {
            val correlationId = aeron.nextCorrelationId()
            val outcome = try {
                requestSnapshot(
                    responses, correlationId, SNAPSHOT_TIMEOUT,
                    send = it::sendAdminRequestToTakeASnapshot,
                    poll = it::pollEgress,
                )
            } catch (e: Exception) {
                return@use CommandResult("snapshot", sent = false, confirmed = false, detail = "snapshot failed: ${e.message}")
            }
            // Confirmed means the cluster answered OK, which it does once the snapshot is taken --
            // not that the request was offered. A refusal is reported with the cluster's message.
            CommandResult(
                command = "snapshot",
                sent = outcome.sent,
                confirmed = outcome.confirmed,
                detail = if (outcome.confirmed) "shard $shardId took a snapshot (correlationId=$correlationId)"
                else "shard $shardId: ${outcome.message}",
            )
        }
    }

    private fun connect(
        aeron: Aeron,
        endpoints: String,
        credentials: Pair<String, String>?,
        responses: AdminResponses,
    ): AeronCluster {
        val context = AeronCluster.Context()
            .aeron(aeron)
            .ownsAeronClient(false)
            .egressListener(responses)
            .ingressChannel(ingressChannel)
            .egressChannel(egressChannel)
            .ingressEndpoints(endpoints)
        credentials?.let { (identity, secret) ->
            context.credentialsSupplier(GatewayCredentialsSupplier(identity, secret))
        }
        return AeronCluster.connect(context)
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
        /** Long enough for a snapshot of full books to be written; a timeout is reported as one. */
        private val SNAPSHOT_TIMEOUT: Duration = Duration.ofSeconds(30)

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
}
