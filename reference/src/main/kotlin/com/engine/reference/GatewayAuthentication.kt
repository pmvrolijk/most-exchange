package com.engine.reference

import io.aeron.security.Authenticator
import io.aeron.security.AuthenticatorSupplier
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
 * Three outcomes, and the middle one is the one worth stating:
 *
 *  - **No credentials at all** authenticate anonymously, with the null principal. The control
 *    plane and the `most` CLI connect to send operator commands and are addressed by nobody, so
 *    demanding a secret of them would buy nothing. An engine that sees the null principal falls
 *    back to learning routes from traffic, which is exactly the behaviour that shipped before this
 *    existed.
 *  - **Credentials that do not verify are rejected**, never downgraded to anonymous. A gateway
 *    presenting the wrong secret and silently becoming an unbound anonymous session is the
 *    failure this is meant to prevent: it would connect, trade, and lose precisely the fills that
 *    binding exists to deliver.
 *  - **Credentials that verify** are authenticated with the gateway id as the principal.
 */
class RegistryAuthenticator(
    private val registry: ParticipantRegistry,
    /** Where a rejection goes. Separate from the caller so a test can read it back. */
    private val onRejection: (String) -> Unit = { System.err.println("cluster: $it") },
) : Authenticator {

    /**
     * The decision taken at connect, held until Aeron asks for it. A null value is the anonymous
     * session; an absent key is a rejection. Entries are dropped as soon as either is applied.
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
            decisions.put(sessionId, NULL_PRINCIPAL)
            return
        }
        val credentials = ParticipantRegistry.decodeCredentials(encodedCredentials)
        if (credentials == null) {
            onRejection("session $sessionId presented credentials that are not 'gatewayId:secret'")
            return
        }
        val (gatewayId, token) = credentials
        if (!registry.verify(gatewayId, token)) {
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
            if (principal.isNotEmpty()) authenticatedGateways++
        }
    }

    /** Unreachable: nothing here ever challenges, so no session can be in the challenged state. */
    override fun onChallengedSession(sessionProxy: SessionProxy, nowMs: Long) =
        sessionProxy.reject()

    private companion object {
        val NULL_PRINCIPAL = ByteArray(0)
    }
}

/**
 * Supplies one [RegistryAuthenticator] per consensus module. Aeron asks for an authenticator once,
 * so the instance is shared and its counters are the module's.
 */
class RegistryAuthenticatorSupplier(
    registry: ParticipantRegistry,
) : AuthenticatorSupplier {
    val authenticator = RegistryAuthenticator(registry)
    override fun get(): Authenticator = authenticator
}
