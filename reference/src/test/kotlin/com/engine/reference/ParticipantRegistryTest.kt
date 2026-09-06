package com.engine.reference

import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The registry that turns the engine's participant routing from something learned into something
 * declared. Every node reads its own copy, so what this file has to guarantee is that two copies
 * of the same content parse to the same thing and say so through the fingerprint.
 */
class ParticipantRegistryTest {

    private fun parse(text: String): Properties = Properties().apply { load(text.reader()) }

    private fun registry(
        shardId: Int = 1,
        gateways: List<GatewayIdentity> = listOf(
            gateway("gw-a", "north", listOf(100L, 101L)),
            gateway("gw-b", "south", listOf(200L)),
        ),
    ) = ParticipantRegistry(shardId, gateways)

    private fun gateway(id: String, token: String, participants: List<Long>) =
        GatewayIdentity(id, ParticipantRegistry.sha256Hex(token), participants)

    @Test
    fun `a rendered registry parses back to itself`() {
        val original = registry()
        val reparsed = ParticipantRegistry.from(parse(original.render()))

        assertEquals(original, reparsed)
        assertEquals(original.fingerprint(), reparsed.fingerprint())
    }

    @Test
    fun `participants resolve by gateway id, and an unknown gateway resolves to nothing`() {
        val registry = registry()

        assertContentEquals(longArrayOf(100L, 101L), registry.participantsOf("gw-a"))
        assertContentEquals(longArrayOf(200L), registry.participantsOf("gw-b"))
        assertNull(registry.participantsOf("gw-c"))
    }

    private fun assertContentEquals(expected: LongArray, actual: LongArray?) =
        assertEquals(expected.toList(), actual?.toList())

    @Test
    fun `the right secret verifies and a wrong one does not`() {
        val registry = registry()

        assertTrue(registry.verify("gw-a", "north"))
        assertFalse(registry.verify("gw-a", "south"))
        assertFalse(registry.verify("gw-a", "nort"))
        assertFalse(registry.verify("gw-c", "north"))
    }

    @Test
    fun `credentials round trip through the one encoding both sides share`() {
        val encoded = ParticipantRegistry.encodeCredentials("gw-a", "north")
        val (gatewayId, token) = ParticipantRegistry.decodeCredentials(encoded)!!

        assertEquals("gw-a", gatewayId)
        assertEquals("north", token)
        assertTrue(registry().verify(gatewayId, token))
    }

    @Test
    fun `credentials that are not gatewayId colon secret decode to nothing`() {
        assertNull(ParticipantRegistry.decodeCredentials(ByteArray(0)))
        assertNull(ParticipantRegistry.decodeCredentials("no-separator".toByteArray()))
        assertNull(ParticipantRegistry.decodeCredentials(":secret-with-no-id".toByteArray()))
        assertNull(ParticipantRegistry.decodeCredentials("id-with-no-secret:".toByteArray()))
    }

    /**
     * The one rule that cannot be repaired downstream. Two gateways claiming one participant is
     * not a merge: whichever session opened last would win, so the route would be decided by
     * connection timing rather than by anything anyone wrote down.
     */
    @Test
    fun `a participant claimed by two gateways is refused`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            registry(
                gateways = listOf(
                    gateway("gw-a", "north", listOf(100L, 101L)),
                    gateway("gw-b", "south", listOf(101L)),
                ),
            )
        }
        assertContains(failure.message!!, "101")
        assertContains(failure.message!!, "gw-a")
        assertContains(failure.message!!, "gw-b")
    }

    @Test
    fun `a duplicate gateway id is refused`() {
        assertFailsWith<IllegalArgumentException> {
            registry(
                gateways = listOf(
                    gateway("gw-a", "north", listOf(100L)),
                    gateway("gw-a", "south", listOf(200L)),
                ),
            )
        }
    }

    @Test
    fun `a gateway with no participants is refused`() {
        assertFailsWith<IllegalArgumentException> { gateway("gw-a", "north", emptyList()) }
    }

    @Test
    fun `a secret that is not a SHA-256 digest is refused`() {
        assertFailsWith<IllegalArgumentException> {
            GatewayIdentity("gw-a", "north", listOf(100L))
        }
    }

    @Test
    fun `a gateway id that could not survive the credential encoding is refused`() {
        assertFailsWith<IllegalArgumentException> {
            gateway("gw:a", "north", listOf(100L))
        }
    }

    @Test
    fun `a non-positive participant id is refused`() {
        assertFailsWith<IllegalArgumentException> { gateway("gw-a", "north", listOf(0L)) }
    }

    /**
     * The fingerprint exists so two nodes can be compared without diffing files, which means every
     * field a node could disagree on has to move it -- a rotated secret included, since a node
     * left on the old one would refuse the gateway the others accept.
     */
    @Test
    fun `the fingerprint moves with the shard, the gateways, the participants and the secret`() {
        val base = registry()

        assertNotEquals(base.fingerprint(), registry(shardId = 2).fingerprint())
        assertNotEquals(
            base.fingerprint(),
            registry(
                gateways = listOf(
                    gateway("gw-a", "north", listOf(100L, 101L)),
                    gateway("gw-b", "south", listOf(201L)),
                ),
            ).fingerprint(),
        )
        assertNotEquals(
            base.fingerprint(),
            registry(
                gateways = listOf(
                    gateway("gw-a", "rotated", listOf(100L, 101L)),
                    gateway("gw-b", "south", listOf(200L)),
                ),
            ).fingerprint(),
        )
    }

    @Test
    fun `order does not move the fingerprint`() {
        assertEquals(
            registry().fingerprint(),
            registry(
                gateways = listOf(
                    gateway("gw-b", "south", listOf(200L)),
                    gateway("gw-a", "north", listOf(101L, 100L)),
                ),
            ).fingerprint(),
        )
    }

    @Test
    fun `a missing key names itself`() {
        val text = registry().render().lineSequence()
            .filterNot { it.startsWith("gateway.gw-b.secret") }
            .joinToString("\n")
        val failure = assertFailsWith<IllegalStateException> {
            ParticipantRegistry.from(parse(text))
        }
        assertContains(failure.message!!, "gateway.gw-b.secret")
    }
}
