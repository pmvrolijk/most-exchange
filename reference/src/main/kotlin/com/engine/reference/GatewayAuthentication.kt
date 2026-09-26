package com.engine.reference

import io.aeron.cluster.AllowBackupAndStandbyAuthorisationService
import io.aeron.cluster.codecs.AdminRequestType
import io.aeron.security.Authenticator
import io.aeron.security.AuthenticatorSupplier
import io.aeron.security.AuthorisationService
import io.aeron.security.CredentialsSupplier
import io.aeron.security.SessionProxy
import org.agrona.collections.Long2ObjectHashMap

/**
 * What a gateway presents to the cluster at connect, so the engine can bind its participants at
 * session open rather than infer them from traffic (Design.md §1).
 *
 * The encoding has exactly one implementation, in [ParticipantRegistry.encodeCredentials]; this is
 * only the Aeron-shaped wrapper around it. Challenge/response is unsupported and returns the same
 * credentials: this is a shared secret presented once, not a protocol.
 */
class GatewayCredentialsSupplier(
    gatewayId: String,
    token: String,
) : CredentialsSupplier {
    private val encoded = ParticipantRegistry.encodeCredentials(gatewayId, token)

    override fun encodedCredentials(): ByteArray = encoded
    override fun onChallenge(encodedChallenge: ByteArray): ByteArray = encoded
}

/**
 * Authenticates a connecting gateway against the shard's [ParticipantRegistry] and stamps its
 * gateway id on the session as the encoded principal.
 *
 * This runs in the **consensus module** process, not the engine — but the principal it sets
 * travels to every node through the replicated log, and `ClusteredService.onSessionOpen` is handed
 * it. That is the whole point: a binding derived from the principal is identical on every node and
 * survives both failover and a snapshot restore, because the session and its principal are
 * consensus state rather than anything the service has to remember.
 *
 * A node started with a registry **authenticates everything or nothing** (Design.md §1). Three
 * outcomes:
 *
 *  - **No credentials at all are rejected.** An anonymous session reaches the engine without
 *    passing any gateway, so it could act for any participant and send any operator command, and
 *    every check the gateway makes would be decoration. The control plane and the `most` CLI,
 *    which reach the cluster directly, present operator-only identities instead. A node started
 *    *without* a registry installs no authenticator and accepts everyone, as before this existed.
 *  - **Credentials that do not verify are rejected**, never downgraded to anonymous. A gateway
 *    presenting the wrong secret and silently becoming an unbound anonymous session is the
 *    failure this is meant to prevent: it would connect, trade, and lose precisely the fills that
 *    binding exists to deliver.
 *  - **Credentials that verify** are authenticated with the gateway id as the principal.
 *
 * The registry is read through a supplier rather than held, so a [ParticipantRegistrySource] can
 * replace it under a running consensus module: onboarding a participant or rotating a gateway
 * secret then costs a gateway restart rather than a node restart. Each connect attempt reads it
 * once, so a swap mid-authentication cannot see half of two registries.
 */
class RegistryAuthenticator(
    private val registrySupplier: () -> ParticipantRegistry,
    /** Where a rejection goes. Separate from the caller so a test can read it back. */
    private val onRejection: (String) -> Unit = { System.err.println("cluster: $it") },
) : Authenticator {

    constructor(
        registry: ParticipantRegistry,
        onRejection: (String) -> Unit = { System.err.println("cluster: $it") },
    ) : this({ registry }, onRejection)

    /**
     * The decision taken at connect, held until Aeron asks for it: the principal to stamp. An
     * absent key is a rejection. Entries are dropped as soon as either is applied.
     */
    private val decisions = Long2ObjectHashMap<ByteArray>()

    /** Sessions authenticated with a gateway principal. Printed by the host to show it working. */
    var authenticatedGateways = 0L
        private set

    /** Connect attempts refused for credentials that did not verify. */
    var rejectedSessions = 0L
        private set

    override fun onConnectRequest(sessionId: Long, encodedCredentials: ByteArray, nowMs: Long) {
        if (encodedCredentials.isEmpty()) {
            onRejection(
                "session $sessionId presented no credentials; this node has a participant registry " +
                    "and accepts only a gateway or operator identity from it"
            )
            return
        }
        val credentials = ParticipantRegistry.decodeCredentials(encodedCredentials)
        if (credentials == null) {
            onRejection("session $sessionId presented credentials that are not 'gatewayId:secret'")
            return
        }
        val (gatewayId, token) = credentials
        if (!registrySupplier().verify(gatewayId, token)) {
            onRejection(
                "session $sessionId failed authentication as gateway '$gatewayId' " +
                    "(unknown gateway, or the wrong secret)"
            )
            return
        }
        decisions.put(sessionId, gatewayId.toByteArray(Charsets.US_ASCII))
    }

    /** Unsupported: there is no challenge, so a response can only be a client that invented one. */
    override fun onChallengeResponse(sessionId: Long, encodedCredentials: ByteArray, nowMs: Long) =
        Unit

    override fun onConnectedSession(sessionProxy: SessionProxy, nowMs: Long) {
        val sessionId = sessionProxy.sessionId()
        val principal = decisions.get(sessionId)
        if (principal == null) {
            decisions.remove(sessionId)
            rejectedSessions++
            sessionProxy.reject()
            return
        }
        if (sessionProxy.authenticate(principal)) {
            decisions.remove(sessionId)
            authenticatedGateways++
        }
    }

    /** Unreachable: nothing here ever challenges, so no session can be in the challenged state. */
    override fun onChallengedSession(sessionProxy: SessionProxy, nowMs: Long) =
        sessionProxy.reject()
}

/**
 * Supplies one [RegistryAuthenticator] per consensus module. Aeron asks for an authenticator once,
 * so the instance is shared and its counters are the module's.
 */
class RegistryAuthenticatorSupplier(
    registrySupplier: () -> ParticipantRegistry,
) : AuthenticatorSupplier {
    constructor(registry: ParticipantRegistry) : this({ registry })

    val authenticator = RegistryAuthenticator(registrySupplier)
    override fun get(): Authenticator = authenticator
}

/**
 * Authorises the consensus module's admin requests from the same registry the authenticator reads
 * (Design.md §1): a snapshot requested through consensus is granted only to a session whose
 * principal is an `operator=true` entry. Everything else falls through to Aeron's default, which
 * allows cluster backup and standby traffic and nothing more.
 *
 * Needed at all because that default grants **no** snapshot request: every `sendAdminRequestToTake
 * ASnapshot` was refused, and nothing read the refusal. Installed only on a node with a registry; a
 * node without one allows every admin request, since nothing there is authenticated to check.
 *
 * Read per request through a supplier, like [RegistryAuthenticator], so revoking `operator` in a
 * reload revokes the right to snapshot at once. Runs on the leader's consensus module, never in the
 * replicated state machine.
 */
class RegistryAuthorisationService(
    private val registrySupplier: () -> ParticipantRegistry,
) : AuthorisationService {

    override fun isAuthorised(
        protocolId: Int,
        actionId: Int,
        type: Any?,
        encodedPrincipal: ByteArray?,
    ): Boolean {
        if (type == AdminRequestType.SNAPSHOT) {
            val principal = encodedPrincipal?.takeIf { it.isNotEmpty() } ?: return false
            return registrySupplier().mayOperate(String(principal, Charsets.US_ASCII))
        }
        return AllowBackupAndStandbyAuthorisationService.INSTANCE
            .isAuthorised(protocolId, actionId, type, encodedPrincipal)
    }
}
