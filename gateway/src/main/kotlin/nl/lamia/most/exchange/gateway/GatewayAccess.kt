package nl.lamia.most.exchange.gateway

import nl.lamia.most.exchange.reference.GatewayIdentity
import nl.lamia.most.exchange.reference.ParticipantRegistry

/**
 * What this gateway may do, asked once per client message (Design.md §1, "Enforcement, at the
 * gateway").
 *
 * The decision is taken here, before the log, and never in the engine: a refused message reaches no
 * node, so it can rest on a registry each gateway re-reads on its own schedule. An engine reject on
 * the same file would diverge the nodes.
 */
interface GatewayAccess {
    fun mayPlace(participantId: Long): Boolean

    fun mayCancel(participantId: Long): Boolean

    fun mayOperate(): Boolean

    companion object {
        /**
         * A gateway with no identity enforces nothing -- the behaviour before the registry existed,
         * and still the default. A node that runs with a registry refuses such a gateway's
         * anonymous session, so this is only ever reachable against an open cluster.
         */
        val OPEN: GatewayAccess = object : GatewayAccess {
            override fun mayPlace(participantId: Long) = true
            override fun mayCancel(participantId: Long) = true
            override fun mayOperate() = true
        }
    }
}

/**
 * [GatewayAccess] from the registry in force, which a [nl.lamia.most.exchange.reference.ParticipantRegistrySource]
 * may replace at any time.
 *
 * Called only on the gateway's poller thread. The entry for [gatewayId] is re-resolved when the
 * registry *object* changes -- one reference comparison per message -- and each check is then a
 * binary search on arrays [GatewayIdentity] sorted once. Nothing here allocates per message.
 *
 * **A gateway dropped from the registry** places nothing and sends no operator command, but keeps
 * the cancel rights of its last known entry, so the participants it spoke for can withdraw what is
 * resting. The consensus module refuses the gateway itself the next time it connects.
 */
class RegistryAccess(
    private val registry: () -> ParticipantRegistry,
    private val gatewayId: String,
) : GatewayAccess {

    private var seen: ParticipantRegistry = registry()
    private var entry: GatewayIdentity? = seen.gateway(gatewayId)
    private var lastKnown: GatewayIdentity = requireNotNull(entry) {
        "gateway id '$gatewayId' is not in the participant registry"
    }

    private fun current(): GatewayIdentity? {
        val inForce = registry()
        if (inForce !== seen) {
            seen = inForce
            entry = inForce.gateway(gatewayId)
            entry?.let { lastKnown = it }
        }
        return entry
    }

    override fun mayPlace(participantId: Long): Boolean = current()?.mayPlace(participantId) ?: false

    override fun mayCancel(participantId: Long): Boolean =
        (current() ?: lastKnown).mayCancel(participantId)

    override fun mayOperate(): Boolean = current()?.operator ?: false
}
