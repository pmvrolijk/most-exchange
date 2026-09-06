package com.engine.reference

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Replacing the participant registry under a running node.
 *
 * The value of this is not the reload itself but what it removes: onboarding a participant used to
 * mean restarting the consensus module and the engine, which is to say a maintenance event on the
 * cluster to add a client to it. The two rules that carry it are both refusals -- a file that
 * cannot be read and a file for another shard leave the registry in force alone -- because the
 * failure mode of standing down on a bad file is a shard that authenticates nobody.
 */
class ParticipantRegistrySourceTest {

    private lateinit var dir: Path
    private lateinit var file: Path
    private val events = mutableListOf<String>()

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("registry-source")
        file = dir.resolve("participants.properties")
    }

    @AfterTest
    fun tearDown() {
        file.toFile().delete()
        dir.toFile().delete()
    }

    private fun registry(shardId: Int = 0, vararg gateways: GatewayIdentity) =
        ParticipantRegistry(shardId, gateways.toList())

    private fun gateway(id: String, secret: String, vararg participants: Long) =
        GatewayIdentity(id, ParticipantRegistry.sha256Hex(secret), participants.toList())

    private fun source(initial: ParticipantRegistry) =
        ParticipantRegistrySource(file.toString(), initial) { events += it }

    @Test
    fun `a new file with a new fingerprint is adopted, and announced with both`() {
        val before = registry(0, gateway("gw-0", "s0", 1L))
        val after = registry(0, gateway("gw-0", "s0", 1L), gateway("gw-1", "s1", 2L))
        file.writeText(after.render())

        val source = source(before)
        assertTrue(source.reload())
        assertEquals(after.fingerprintValue(), source.registry().fingerprintValue())
        assertEquals(1L, source.reloads)
        assertContains(events.single(), before.fingerprint())
        assertContains(events.single(), after.fingerprint())
    }

    @Test
    fun `an identical file is not adopted and is not announced`() {
        val same = registry(0, gateway("gw-0", "s0", 1L))
        file.writeText(same.render())

        val source = source(same)
        assertFalse(source.reload())
        assertEquals(0L, source.reloads)
        assertTrue(events.isEmpty(), "a reload that changed nothing must be silent")
    }

    /**
     * The rule that matters most. A half-written or mistyped file must not disarm authentication:
     * the registry in hand is still correct, and standing down on it would turn an editor mistake
     * into a shard that rejects every gateway.
     */
    @Test
    fun `an unreadable file leaves the registry in force`() {
        val before = registry(0, gateway("gw-0", "s0", 1L))
        file.writeText("shard.id=0\nregistry.gateways=gw-0\n")   // no secret, no participants

        val source = source(before)
        assertFalse(source.reload())
        assertEquals(before.fingerprintValue(), source.registry().fingerprintValue())
        assertEquals(1L, source.failures)
        assertContains(events.single(), "keeping fingerprint ${before.fingerprint()}")
    }

    @Test
    fun `a missing file leaves the registry in force`() {
        val before = registry(0, gateway("gw-0", "s0", 1L))
        val source = source(before)

        assertFalse(source.reload())
        assertEquals(before.fingerprintValue(), source.registry().fingerprintValue())
        assertEquals(1L, source.failures)
    }

    /** Same reasoning as the boot-time check against the ShardSpec, one layer later in time. */
    @Test
    fun `a registry for another shard is refused`() {
        val before = registry(0, gateway("gw-0", "s0", 1L))
        file.writeText(registry(1, gateway("gw-9", "s9", 5L)).render())

        val source = source(before)
        assertFalse(source.reload())
        assertEquals(before.fingerprintValue(), source.registry().fingerprintValue())
        assertContains(events.single(), "it is for shard 1")
    }

    /**
     * The whole point, end to end: the consensus module reads the registry through this, so a
     * gateway added to the file authenticates without the module restarting.
     */
    @Test
    fun `a gateway added to the file authenticates against the running authenticator`() {
        val before = registry(0, gateway("gw-0", "s0", 1L))
        val source = source(before)
        val rejections = mutableListOf<String>()
        val authenticator = RegistryAuthenticator(source::registry) { rejections += it }

        authenticator.onConnectRequest(
            1L, ParticipantRegistry.encodeCredentials("gw-1", "s1"), 0L,
        )
        assertEquals(1, rejections.size, "gw-1 is not in the file yet")

        file.writeText(registry(0, gateway("gw-0", "s0", 1L), gateway("gw-1", "s1", 2L)).render())
        assertTrue(source.reload())

        authenticator.onConnectRequest(
            2L, ParticipantRegistry.encodeCredentials("gw-1", "s1"), 0L,
        )
        assertEquals(1, rejections.size, "gw-1 is in the file now and must be accepted")
    }

    @Test
    fun `a rotated secret stops the old one working`() {
        val before = registry(0, gateway("gw-0", "old", 1L))
        val source = source(before)
        val rejections = mutableListOf<String>()
        val authenticator = RegistryAuthenticator(source::registry) { rejections += it }

        file.writeText(registry(0, gateway("gw-0", "new", 1L)).render())
        assertTrue(source.reload())

        authenticator.onConnectRequest(1L, ParticipantRegistry.encodeCredentials("gw-0", "old"), 0L)
        assertEquals(1, rejections.size)
        authenticator.onConnectRequest(2L, ParticipantRegistry.encodeCredentials("gw-0", "new"), 0L)
        assertEquals(1, rejections.size)
    }

    @Test
    fun `polling adopts a file written after it started`() {
        val before = registry(0, gateway("gw-0", "s0", 1L))
        val after = registry(0, gateway("gw-0", "s0", 1L, 2L))
        val source = source(before)
        source.use {
            it.startPolling(intervalMs = 20)
            file.writeText(after.render())
            val deadline = System.nanoTime() + 5_000_000_000L
            while (it.reloads == 0L && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(after.fingerprintValue(), it.registry().fingerprintValue())
        }
    }
}
