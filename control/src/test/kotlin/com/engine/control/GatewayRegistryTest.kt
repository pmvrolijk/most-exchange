package com.engine.control

import com.engine.reference.ParticipantRegistry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Authoring and publishing the participant registry.
 *
 * It was hand-written until now: the consensus module authenticated gateways against a file and the
 * engine bound participants from it, while this database held the participants and nothing checked
 * the two agreed. The assertion that matters is the same one the shard files get — what is written
 * parses back through the *shipped* `ParticipantRegistry` to the fingerprint the release recorded,
 * so a renderer that drifted by a separator fails here rather than at a shard that authenticates
 * nobody.
 */
@SpringBootTest
class GatewayRegistryTest : PostgresTest() {

    @Autowired
    private lateinit var topology: TopologyService

    @Autowired
    private lateinit var publisher: ReleasePublisher

    private fun seed() {
        topology.createShard(shardRow(0))
        topology.createSecurity(securityRow(1, 0, "AAPL", ISIN_APPLE))
        topology.createParticipant(ParticipantRow(7L, "North"))
        topology.createParticipant(ParticipantRow(8L, "South"))
    }

    private fun gateway(id: String, vararg participants: Long, secret: String = "s3cret-token-1234") =
        GatewayRow(
            gatewayId = id,
            shardId = 0,
            participants = participants.toList(),
            secretSha256 = ParticipantRegistry.sha256Hex(secret),
        )

    @Test
    fun `a published registry parses back to the fingerprint the release recorded`() {
        seed()
        topology.createGateway(gateway("gw-0", 7L, 8L))

        val release = publisher.publish("with a registry")
        val file = Path.of(release.directory)
            .resolve(ReleasePublisher.participantsFileName(0))
        assertTrue(Files.isRegularFile(file), "$file was not written")

        // Read by the same code the consensus module and the engine use at boot.
        val booted = ParticipantRegistry.load(file.toString())
        assertEquals(0, booted.shardId)
        assertEquals(listOf(7L, 8L), booted.participantsOf("gw-0")!!.toList())
        assertEquals(booted.fingerprint(), release.registryFingerprints[0])
        assertTrue(booted.verify("gw-0", "s3cret-token-1234"))
    }

    /**
     * The registry fingerprint is a second value, never folded into the shard's. That hash is
     * recorded in every release published so far, and rotating a gateway secret is not a change
     * of shard geometry.
     */
    @Test
    fun `rotating a secret changes the registry fingerprint and not the shard's`() {
        seed()
        topology.createGateway(gateway("gw-0", 7L))
        val before = publisher.publish("before")

        assertTrue(topology.rotateGatewaySecret("gw-0", "a-completely-different-token"))
        val after = publisher.publish("after")

        assertEquals(before.fingerprints[0], after.fingerprints[0])
        assertTrue(
            before.registryFingerprints[0] != after.registryFingerprints[0],
            "a rotated secret must be visible in the registry fingerprint",
        )
    }

    /**
     * A shard with no gateways is not a broken registry; it is a shard whose gateways connect
     * anonymously, which is what shipped before the registry existed. `ParticipantRegistry`
     * requires at least one gateway, so publishing has to skip rather than construct one.
     */
    @Test
    fun `a shard with no gateways publishes no registry and still publishes`() {
        seed()
        val release = publisher.publish("no gateways")

        assertFalse(
            Files.exists(
                Path.of(release.directory).resolve(ReleasePublisher.participantsFileName(0))
            )
        )
        assertNull(release.registryFingerprints[0])
        assertTrue(release.fingerprints.containsKey(0), "the shard file is published regardless")
    }

    // --------------------------------------------------- several gateways per participant, primary
    // Design.md §1: a participant may be listed on several gateways, and then one must be named its
    // primary. The rule lives in `reference`; what is checked here is that the control plane can
    // author it and that the published file carries it.

    private fun registryOf(release: ReleaseRow): ParticipantRegistry = ParticipantRegistry.load(
        Path.of(release.directory).resolve(ReleasePublisher.participantsFileName(0)).toString()
    )

    @Test
    fun `a participant on two gateways with a primary publishes, and the file names the primary`() {
        seed()
        topology.createGateway(gateway("gw-0", 7L).copy(primaryFor = listOf(7L)))
        topology.createGateway(gateway("gw-1", 7L, 8L, secret = "another-secret-token"))

        val booted = registryOf(publisher.publish("failover"))

        assertEquals("gw-0", booted.primaryOf(7L))
        assertTrue(booted.mayPlace("gw-1", 7L))
    }

    /** The domain type refuses it, and the draft view says so before anyone publishes. */
    @Test
    fun `a participant on two gateways with no primary is a problem in the draft view`() {
        seed()
        topology.createGateway(gateway("gw-0", 7L))
        topology.createGateway(gateway("gw-1", 7L, secret = "another-secret-token"))

        val problem = topology.view().shards.single { it.shard.shardId == 0 }.problem
        assertTrue(problem != null && "primary" in problem, "expected a primary problem, got $problem")
    }

    @Test
    fun `two gateways on one shard cannot both be a participant's primary`() {
        seed()
        topology.createGateway(gateway("gw-0", 7L).copy(primaryFor = listOf(7L)))
        assertFailsWith<IllegalArgumentException> {
            topology.createGateway(
                gateway("gw-1", 7L, secret = "another-secret-token").copy(primaryFor = listOf(7L))
            )
        }
    }

    @Test
    fun `a gateway cannot be primary for a participant it does not list`() {
        seed()
        assertFailsWith<IllegalArgumentException> {
            topology.createGateway(gateway("gw-0", 7L).copy(primaryFor = listOf(8L)))
        }
    }

    /**
     * A primary flag on a participant only one gateway lists means nothing, and is left out of the
     * file rather than refused -- otherwise disabling a failover would break the shard's registry.
     */
    @Test
    fun `a primary flag on a participant only one gateway lists is not published`() {
        seed()
        topology.createGateway(gateway("gw-0", 7L).copy(primaryFor = listOf(7L)))

        val booted = registryOf(publisher.publish("one gateway"))

        assertEquals("gw-0", booted.primaryOf(7L))
        assertTrue(booted.primaries.isEmpty())
    }

    /** The old primary key on participant_id alone confined a participant to one gateway anywhere. */
    @Test
    fun `a participant may be on gateways of two shards, each its own primary`() {
        seed()
        topology.createShard(shardRow(1))
        topology.createSecurity(securityRow(2, 1, "MSFT", ISIN_MICROSOFT))
        topology.createGateway(gateway("gw-0", 7L).copy(primaryFor = listOf(7L)))
        topology.createGateway(
            gateway("gw-1", 7L, secret = "another-secret-token").copy(shardId = 1, primaryFor = listOf(7L))
        )

        assertEquals("gw-0", topology.participantRegistry(0)!!.primaryOf(7L))
        assertEquals("gw-1", topology.participantRegistry(1)!!.primaryOf(7L))
    }

    // ------------------------------------------------------------------ cancel only and operator

    @Test
    fun `a cancel only participant publishes as cancel only`() {
        seed()
        topology.createGateway(gateway("gw-0", 7L).copy(cancelOnly = listOf(8L)))

        val booted = registryOf(publisher.publish("revoking 8"))

        assertTrue(booted.mayCancel("gw-0", 8L))
        assertFalse(booted.mayPlace("gw-0", 8L))
    }

    @Test
    fun `a participant cannot be both placing and cancel only on one gateway`() {
        seed()
        assertFailsWith<IllegalArgumentException> {
            topology.createGateway(gateway("gw-0", 7L).copy(cancelOnly = listOf(7L)))
        }
    }

    /**
     * How the control plane and the CLI get named (Design.md §1): an operator-only identity lists
     * nobody. It must still be published -- a registry that dropped it would leave them anonymous,
     * which a node with a registry refuses.
     */
    @Test
    fun `an operator only gateway lists nobody and is still published`() {
        seed()
        topology.createGateway(gateway("gw-0", 7L))
        topology.createGateway(gateway("control", secret = "operator-secret-token").copy(operator = true))

        val booted = registryOf(publisher.publish("with an operator"))

        assertTrue(booted.mayOperate("control"))
        assertFalse(booted.mayOperate("gw-0"))
        assertEquals(emptyList(), booted.participantsOf("control")!!.toList())
    }

    @Test
    fun `a gateway that lists nobody and is not an operator is refused`() {
        seed()
        assertFailsWith<IllegalArgumentException> { topology.createGateway(gateway("gw-0")) }
    }

    @Test
    fun `an update carries operator, cancel only and primary through`() {
        seed()
        topology.createGateway(gateway("gw-0", 7L))
        topology.updateGateway(
            GatewayRow("gw-0", shardId = 0, participants = listOf(7L), cancelOnly = listOf(8L), operator = true),
        )

        val stored = topology.gateway("gw-0")!!
        assertEquals(listOf(7L), stored.participants)
        assertEquals(listOf(8L), stored.cancelOnly)
        assertTrue(stored.operator)
    }

    @Test
    fun `a gateway id outside the wire's alphabet is refused by the domain type`() {
        seed()
        assertFailsWith<IllegalArgumentException> {
            topology.createGateway(gateway("gw:0", 7L))
        }
    }

    @Test
    fun `an update leaves the secret alone`() {
        seed()
        topology.createGateway(gateway("gw-0", 7L))
        topology.updateGateway(GatewayRow("gw-0", shardId = 0, participants = listOf(7L, 8L)))

        val release = publisher.publish("after an update")
        val booted = ParticipantRegistry.load(
            Path.of(release.directory).resolve(ReleasePublisher.participantsFileName(0)).toString()
        )
        assertEquals(listOf(7L, 8L), booted.participantsOf("gw-0")!!.toList())
        assertTrue(booted.verify("gw-0", "s3cret-token-1234"), "the secret must have survived")
    }

    @Test
    fun `the draft view carries the registry fingerprint beside the shard's`() {
        seed()
        topology.createGateway(gateway("gw-0", 7L))

        val view = topology.view().shards.single { it.shard.shardId == 0 }
        assertEquals(
            topology.participantRegistry(0)!!.fingerprint(),
            view.registryFingerprint,
        )
        assertTrue(view.problem == null)
    }
}
