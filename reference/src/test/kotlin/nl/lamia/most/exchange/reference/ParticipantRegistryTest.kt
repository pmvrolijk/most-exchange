package nl.lamia.most.exchange.reference

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

    // ---------------------------------------------------------------- several gateways, primary
    // Design.md §1: a participant may be listed on several gateways, and then the registry must name
    // one of them its primary, which lists it.

    /**
     * Without a declared primary, two claims on one participant would be resolved by whichever
     * session happened to open last -- a route decided by connection timing rather than by
     * anything anyone wrote down.
     */
    @Test
    fun `a participant on two gateways with no primary is refused, naming both`() {
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
    fun `a participant on two gateways with a primary is accepted, and the primary is the one named`() {
        val registry = ParticipantRegistry(
            1,
            listOf(gateway("gw-a", "north", listOf(100L, 101L)), gateway("gw-b", "south", listOf(101L))),
            primaries = mapOf(101L to "gw-b"),
        )

        assertEquals("gw-b", registry.primaryOf(101L))
        assertTrue(registry.mayPlace("gw-a", 101L))
        assertTrue(registry.mayPlace("gw-b", 101L))
    }

    @Test
    fun `a participant on one gateway has that gateway as its primary without saying so`() {
        assertEquals("gw-a", registry().primaryOf(100L))
        assertNull(registry().primaryOf(999L))
    }

    @Test
    fun `a primary that does not list the participant is refused`() {
        assertFailsWith<IllegalArgumentException> {
            ParticipantRegistry(
                1,
                listOf(gateway("gw-a", "north", listOf(100L, 101L)), gateway("gw-b", "south", listOf(101L))),
                primaries = mapOf(101L to "gw-c"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ParticipantRegistry(
                1,
                listOf(
                    gateway("gw-a", "north", listOf(100L, 101L)),
                    gateway("gw-b", "south", listOf(101L)),
                    gateway("gw-c", "west", listOf(300L)),
                ),
                primaries = mapOf(101L to "gw-c"),
            )
        }
    }

    /** Design.md §1: a primary line for a participant that is not on several gateways is a typo. */
    @Test
    fun `a primary for a participant on only one gateway is refused as a typo`() {
        assertFailsWith<IllegalArgumentException> {
            ParticipantRegistry(1, listOf(gateway("gw-a", "north", listOf(100L))), primaries = mapOf(100L to "gw-a"))
        }
    }

    /** A cancel-only listing still fills, so it still competes for the route and counts as a listing. */
    @Test
    fun `a cancel only listing on another gateway also needs a primary`() {
        assertFailsWith<IllegalArgumentException> {
            registry(
                gateways = listOf(
                    gateway("gw-a", "north", listOf(100L)),
                    GatewayIdentity("gw-b", ParticipantRegistry.sha256Hex("south"), listOf(200L), cancelOnly = listOf(100L)),
                ),
            )
        }
    }

    // ------------------------------------------------------------------------ what each grants
    // Design.md §1, "Enforcement, at the gateway": participants may place and cancel; cancelOnly may
    // cancel and not place; operator may send operator commands.

    @Test
    fun `a listed participant may place and cancel, and an unlisted one may do neither`() {
        val registry = registry()

        assertTrue(registry.mayPlace("gw-a", 100L))
        assertTrue(registry.mayCancel("gw-a", 100L))
        assertFalse(registry.mayPlace("gw-a", 200L))
        assertFalse(registry.mayCancel("gw-a", 200L))
        assertFalse(registry.mayPlace("gw-c", 100L))
        assertFalse(registry.mayCancel("gw-c", 100L))
    }

    @Test
    fun `a cancel only participant may cancel and may not place`() {
        val registry = registry(
            gateways = listOf(
                GatewayIdentity(
                    "gw-a", ParticipantRegistry.sha256Hex("north"), listOf(100L), cancelOnly = listOf(101L),
                ),
            ),
        )

        assertFalse(registry.mayPlace("gw-a", 101L))
        assertTrue(registry.mayCancel("gw-a", 101L))
    }

    /** Design.md §1: its resting orders still fill, so the engine must still bind it. */
    @Test
    fun `a cancel only participant is still bound to its gateway`() {
        val registry = registry(
            gateways = listOf(
                GatewayIdentity(
                    "gw-a", ParticipantRegistry.sha256Hex("north"), listOf(100L), cancelOnly = listOf(101L),
                ),
            ),
        )

        assertEquals(listOf(100L, 101L), registry.participantsOf("gw-a")?.toList())
    }

    @Test
    fun `a participant in both lists of one gateway is refused`() {
        assertFailsWith<IllegalArgumentException> {
            GatewayIdentity("gw-a", ParticipantRegistry.sha256Hex("north"), listOf(100L), cancelOnly = listOf(100L))
        }
    }

    @Test
    fun `only an operator gateway may send operator commands`() {
        val registry = registry(
            gateways = listOf(
                gateway("gw-a", "north", listOf(100L)),
                GatewayIdentity("control", ParticipantRegistry.sha256Hex("west"), emptyList(), operator = true),
            ),
        )

        assertFalse(registry.mayOperate("gw-a"))
        assertTrue(registry.mayOperate("control"))
        assertFalse(registry.mayOperate("gw-c"))
    }

    /** Design.md §1: an operator-only identity is the only kind of entry allowed to list nobody. */
    @Test
    fun `an operator only identity lists nobody and may neither place nor cancel`() {
        val registry = registry(
            gateways = listOf(
                GatewayIdentity("control", ParticipantRegistry.sha256Hex("west"), emptyList(), operator = true),
            ),
        )

        assertFalse(registry.mayPlace("control", 100L))
        assertFalse(registry.mayCancel("control", 100L))
        assertEquals(emptyList(), registry.participantsOf("control")?.toList())
    }

    @Test
    fun `a gateway whose participants are all cancel only still lists somebody`() {
        GatewayIdentity("gw-a", ParticipantRegistry.sha256Hex("north"), emptyList(), cancelOnly = listOf(100L))
    }

    // ---------------------------------------------------------------------------------- the file

    @Test
    fun `cancel only, operator and primary lines round trip`() {
        // Declared in gateway-id order, because render() writes them in that order and equality on
        // the list is positional; the fingerprint, which is what nodes compare, is order-free.
        val original = ParticipantRegistry(
            1,
            listOf(
                GatewayIdentity("control", ParticipantRegistry.sha256Hex("west"), emptyList(), operator = true),
                GatewayIdentity(
                    "gw-a", ParticipantRegistry.sha256Hex("north"), listOf(100L, 101L), cancelOnly = listOf(102L),
                ),
                gateway("gw-b", "south", listOf(101L)),
            ),
            primaries = mapOf(101L to "gw-b"),
        )
        val reparsed = ParticipantRegistry.from(parse(original.render()))

        assertEquals(original, reparsed)
        assertEquals(original.fingerprint(), reparsed.fingerprint())
    }

    @Test
    fun `the documented file format parses`() {
        val registry = ParticipantRegistry.from(
            parse(
                """
                shard.id=0
                registry.gateways=gw-0,gw-1,control
                gateway.gw-0.secret=${ParticipantRegistry.sha256Hex("a")}
                gateway.gw-0.participants=7,8,9
                gateway.gw-0.cancelOnly=10
                gateway.gw-0.operator=false
                gateway.gw-1.secret=${ParticipantRegistry.sha256Hex("b")}
                gateway.gw-1.participants=7,14
                gateway.control.secret=${ParticipantRegistry.sha256Hex("c")}
                gateway.control.operator=true
                participant.7.primary=gw-0
                """.trimIndent(),
            ),
        )

        assertTrue(registry.mayCancel("gw-0", 10L))
        assertFalse(registry.mayPlace("gw-0", 10L))
        assertTrue(registry.mayPlace("gw-1", 7L))
        assertEquals("gw-0", registry.primaryOf(7L))
        assertTrue(registry.mayOperate("control"))
        assertFalse(registry.mayOperate("gw-0"))
    }

    /** "Nothing has a default" still holds where a default would widen access. */
    @Test
    fun `a gateway that is not an operator still has to name its participants`() {
        val failure = assertFailsWith<IllegalStateException> {
            ParticipantRegistry.from(
                parse(
                    """
                    shard.id=0
                    registry.gateways=gw-0
                    gateway.gw-0.secret=${ParticipantRegistry.sha256Hex("a")}
                    """.trimIndent(),
                ),
            )
        }
        assertContains(failure.message!!, "gateway.gw-0.participants")
    }

    @Test
    fun `an operator flag that is not true or false is refused rather than read as false`() {
        assertFailsWith<IllegalStateException> {
            ParticipantRegistry.from(
                parse(
                    """
                    shard.id=0
                    registry.gateways=gw-0
                    gateway.gw-0.secret=${ParticipantRegistry.sha256Hex("a")}
                    gateway.gw-0.participants=7
                    gateway.gw-0.operator=yes
                    """.trimIndent(),
                ),
            )
        }
    }

    /**
     * Releases already published record a registry fingerprint (spec_release_shard), so a registry
     * using none of the new fields must render and hash exactly as it did before they existed.
     */
    @Test
    fun `a registry without the new fields keeps its old rendering and fingerprint`() {
        val registry = registry()

        assertFalse(registry.render().contains("cancelOnly"))
        assertFalse(registry.render().contains("operator"))
        assertFalse(registry.render().contains("primary"))
        assertEquals(LEGACY_FINGERPRINT, registry.fingerprint())
    }

    @Test
    fun `the fingerprint moves with cancel only, operator and primary`() {
        val base = ParticipantRegistry(
            1,
            listOf(gateway("gw-a", "north", listOf(100L, 101L)), gateway("gw-b", "south", listOf(101L))),
            primaries = mapOf(101L to "gw-a"),
        )
        val otherPrimary = base.copy(primaries = mapOf(101L to "gw-b"))
        val withCancelOnly = registry(
            gateways = listOf(
                GatewayIdentity(
                    "gw-a", ParticipantRegistry.sha256Hex("north"), listOf(100L), cancelOnly = listOf(101L),
                ),
                gateway("gw-b", "south", listOf(200L)),
            ),
        )
        val withOperator = registry(
            gateways = listOf(
                GatewayIdentity(
                    "gw-a", ParticipantRegistry.sha256Hex("north"), listOf(100L, 101L), operator = true,
                ),
                gateway("gw-b", "south", listOf(200L)),
            ),
        )

        assertNotEquals(base.fingerprint(), otherPrimary.fingerprint())
        assertNotEquals(registry().fingerprint(), withCancelOnly.fingerprint())
        assertNotEquals(registry().fingerprint(), withOperator.fingerprint())
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
    fun `a gateway that is not an operator and lists nobody is refused`() {
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

    private companion object {
        /**
         * [registry]'s fingerprint as computed before cancelOnly, operator and primaries existed,
         * taken from the code at 8922d59. Hard-coded on purpose: a value recomputed by the current
         * code would agree with itself whatever changed.
         */
        const val LEGACY_FINGERPRINT = "dbbaaeaf18f6598f"
    }
}
