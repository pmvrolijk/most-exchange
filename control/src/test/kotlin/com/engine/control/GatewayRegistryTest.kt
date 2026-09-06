package com.engine.control

import com.engine.reference.ParticipantRegistry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
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

    /** Structural, not checked: participant_id is the join table's primary key. */
    @Test
    fun `a participant claimed by two gateways is refused`() {
        seed()
        topology.createGateway(gateway("gw-0", 7L))
        assertFailsWith<DataIntegrityViolationException> {
            topology.createGateway(gateway("gw-1", 7L, secret = "another-secret-token"))
        }
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
