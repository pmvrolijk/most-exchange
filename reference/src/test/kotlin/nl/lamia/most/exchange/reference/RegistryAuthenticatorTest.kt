package nl.lamia.most.exchange.reference

import io.aeron.security.SessionProxy
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The consensus module's half of the participant binding.
 *
 * What the engine can bind at session open is exactly what this decides, so the case worth pinning
 * hardest is the middle one: credentials that do not verify must be *rejected*, never quietly
 * downgraded to an anonymous session. A gateway that connected anonymously would trade perfectly
 * well and lose only the fills of whichever of its participants had gone quiet -- the failure this
 * whole mechanism exists to remove, reintroduced silently by a typo in a secret.
 */
class RegistryAuthenticatorTest {

    private class FakeSessionProxy(private val sessionId: Long) : SessionProxy {
        var authenticatedAs: ByteArray? = null
        var rejected = false
        var challenged = false

        override fun sessionId(): Long = sessionId

        override fun challenge(encodedChallenge: ByteArray?): Boolean {
            challenged = true
            return true
        }

        override fun authenticate(encodedPrincipal: ByteArray?): Boolean {
            authenticatedAs = encodedPrincipal ?: ByteArray(0)
            return true
        }

        override fun reject() {
            rejected = true
        }
    }

    private val registry = ParticipantRegistry(
        shardId = 1,
        gateways = listOf(
            GatewayIdentity("gw-a", ParticipantRegistry.sha256Hex("north"), listOf(100L, 101L)),
            GatewayIdentity("gw-b", ParticipantRegistry.sha256Hex("south"), listOf(200L)),
        ),
    )

    private val rejections = mutableListOf<String>()
    private val authenticator = RegistryAuthenticator(registry) { rejections += it }

    private fun connect(sessionId: Long, credentials: ByteArray): FakeSessionProxy {
        authenticator.onConnectRequest(sessionId, credentials, 0L)
        val proxy = FakeSessionProxy(sessionId)
        authenticator.onConnectedSession(proxy, 0L)
        return proxy
    }

    @Test
    fun `the right secret authenticates as the gateway id`() {
        val proxy = connect(1L, ParticipantRegistry.encodeCredentials("gw-a", "north"))

        assertEquals("gw-a", proxy.authenticatedAs?.let { String(it, Charsets.US_ASCII) })
        assertEquals(false, proxy.rejected)
        assertEquals(1L, authenticator.authenticatedGateways)
        assertEquals(0L, authenticator.rejectedSessions)
        assertTrue(rejections.isEmpty())
    }

    @Test
    fun `a client with no credentials is rejected, and says why`() {
        // Design.md §1: a node started with a registry authenticates everything or nothing. An
        // anonymous session reaches the engine without passing any gateway, so it could act for any
        // participant and send any operator command -- every gateway check would be decoration.
        // The control plane and the CLI hold operator-only identities instead.
        val proxy = connect(1L, ByteArray(0))

        assertTrue(proxy.rejected)
        assertNull(proxy.authenticatedAs)
        assertEquals(1L, authenticator.rejectedSessions)
        assertEquals(0L, authenticator.authenticatedGateways)
        assertContains(rejections.single(), "no credentials")
    }

    @Test
    fun `an operator only identity authenticates like any gateway`() {
        val withOperator = RegistryAuthenticator(
            { registry.copy(gateways = registry.gateways + GatewayIdentity(
                "control", ParticipantRegistry.sha256Hex("west"), emptyList(), operator = true,
            )) },
            { rejections += it },
        )
        withOperator.onConnectRequest(1L, ParticipantRegistry.encodeCredentials("control", "west"), 0L)
        val proxy = FakeSessionProxy(1L)
        withOperator.onConnectedSession(proxy, 0L)

        assertEquals("control", proxy.authenticatedAs?.let { String(it, Charsets.US_ASCII) })
        assertEquals(false, proxy.rejected)
    }

    @Test
    fun `the wrong secret is rejected, not downgraded to anonymous`() {
        val proxy = connect(1L, ParticipantRegistry.encodeCredentials("gw-a", "south"))

        assertTrue(proxy.rejected)
        assertNull(proxy.authenticatedAs)
        assertEquals(1L, authenticator.rejectedSessions)
        assertContains(rejections.single(), "gw-a")
    }

    @Test
    fun `an unknown gateway is rejected`() {
        val proxy = connect(1L, ParticipantRegistry.encodeCredentials("gw-z", "north"))

        assertTrue(proxy.rejected)
        assertEquals(1L, authenticator.rejectedSessions)
        assertContains(rejections.single(), "gw-z")
    }

    @Test
    fun `credentials of the wrong shape are rejected`() {
        val proxy = connect(1L, "gw-a".toByteArray(Charsets.US_ASCII))

        assertTrue(proxy.rejected)
        assertContains(rejections.single(), "gatewayId:secret")
    }

    /**
     * A decision belongs to the session that made it. Without the session id in the map, a second
     * client connecting during a first one's handshake could be handed the first one's principal.
     */
    @Test
    fun `decisions do not leak between overlapping connects`() {
        authenticator.onConnectRequest(1L, ParticipantRegistry.encodeCredentials("gw-a", "north"), 0L)
        authenticator.onConnectRequest(2L, ParticipantRegistry.encodeCredentials("gw-b", "south"), 0L)

        val second = FakeSessionProxy(2L)
        val first = FakeSessionProxy(1L)
        authenticator.onConnectedSession(second, 0L)
        authenticator.onConnectedSession(first, 0L)

        assertEquals("gw-b", second.authenticatedAs?.let { String(it, Charsets.US_ASCII) })
        assertEquals("gw-a", first.authenticatedAs?.let { String(it, Charsets.US_ASCII) })
    }

    /** Nothing here ever challenges, so a session claiming to be challenged is not one of ours. */
    @Test
    fun `a challenged session is rejected`() {
        val proxy = FakeSessionProxy(1L)
        authenticator.onChallengedSession(proxy, 0L)

        assertTrue(proxy.rejected)
    }
}
