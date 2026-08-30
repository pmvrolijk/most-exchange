package com.engine.control

import com.engine.reference.ShardSpec
import com.engine.reference.Universe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The publish step is where "database authors, files boot" is either true or a slogan.
 *
 * What matters is not that files appear, but that what appears parses back to the same geometry
 * with the same fingerprint the processes will print. A generator that emitted a subtly different
 * dialect would fail here rather than at 06:00 on a trading day.
 */
@SpringBootTest
class ReleasePublisherTest : PostgresTest() {

    @Autowired
    private lateinit var topology: TopologyService

    @Autowired
    private lateinit var publisher: ReleasePublisher

    private fun seedTwoShards() {
        topology.createShard(shardRow(0))
        topology.createShard(shardRow(1))
        topology.createSecurity(securityRow(1, 0, "AAPL", ISIN_APPLE))
        topology.createSecurity(securityRow(2, 0, "MSFT", ISIN_MICROSOFT))
        topology.createSecurity(securityRow(3, 1, "BMW", ISIN_BMW, currency = "EUR"))
    }

    private fun parse(path: Path): Properties =
        Properties().apply { Files.newInputStream(path).use { load(it) } }

    @Test
    fun `a published shard file boots to the fingerprint the release recorded`() {
        seedTwoShards()
        val release = publisher.publish("first")
        val directory = Path.of(release.directory)

        for ((shardId, fingerprint) in release.fingerprints) {
            val file = directory.resolve(ReleasePublisher.shardFileName(shardId))
            assertTrue(Files.isRegularFile(file), "$file was not written")
            // Parsed by the same code the engine, gateway and market-data use at boot.
            val booted = ShardSpec.from(parse(file))
            assertEquals(shardId, booted.shardId)
            assertEquals(fingerprint, booted.fingerprint())
        }
    }

    @Test
    fun `the published registry resolves the shard files beside it`() {
        seedTwoShards()
        val release = publisher.publish(null)
        val directory = Path.of(release.directory)

        // Discovery is the one process that must read every shard's file, and it finds them
        // through this registry. Loading it the way discovery does is the only real assertion.
        val universe = Universe.from(parse(directory.resolve(ReleasePublisher.REGISTRY_FILE)))

        assertEquals(listOf(0, 1), universe.shards.map { it.shardId }.sorted())
        assertEquals(3, universe.entries.size)
        assertEquals(release.universeVersion, universe.version)
        // The endpoints came from the shard table, so the gateway's client endpoints and the ones
        // discovery advertises cannot disagree -- they are one row rendered twice.
        assertEquals("aeron:udp?endpoint=gw0:20001", universe.routeFor(0)?.orderEntryChannel)
    }

    @Test
    fun `republishing allocates a new version rather than rewriting one`() {
        seedTwoShards()
        val first = publisher.publish("before")
        val firstText = Files.readString(
            Path.of(first.directory).resolve(ReleasePublisher.shardFileName(0)),
        )

        topology.createSecurity(securityRow(4, 0, "BAE", ISIN_BAE, currency = "GBP"))
        val second = publisher.publish("after")

        assertTrue(second.version > first.version)
        assertNotEquals(first.directory, second.directory)
        assertNotEquals(first.universeVersion, second.universeVersion)
        assertNotEquals(first.fingerprints[0], second.fingerprints[0])
        // A process booted from the first release must keep seeing the first release.
        assertEquals(
            firstText,
            Files.readString(Path.of(first.directory).resolve(ReleasePublisher.shardFileName(0))),
        )
    }

    @Test
    fun `publishing an unsatisfiable topology is refused before anything is written`() {
        topology.createShard(shardRow(0))
        topology.createShard(shardRow(1))
        topology.createSecurity(securityRow(1, 0, "AAPL", ISIN_APPLE))
        // Shard 1 has no securities, which ShardSpec rejects.

        val e = assertFailsWith<IllegalArgumentException> { publisher.publish(null) }
        assertContains(e.message.orEmpty(), "defines no securities")
        assertTrue(publisher.releases().isEmpty(), "a failed publish must not leave a release row")
    }

    @Test
    fun `an empty topology cannot be published`() {
        val e = assertFailsWith<IllegalStateException> { publisher.publish(null) }
        assertContains(e.message.orEmpty(), "no shards")
    }

    @Test
    fun `the manifest names the fingerprint an operator will see in the logs`() {
        seedTwoShards()
        val release = publisher.publish("with a \"quoted\" note")
        val manifest = Files.readString(
            Path.of(release.directory).resolve(ReleasePublisher.MANIFEST_FILE),
        )

        assertContains(manifest, "\"version\": ${release.version}")
        assertContains(manifest, "\"universeVersion\": ${release.universeVersion}")
        for ((shardId, fingerprint) in release.fingerprints) {
            assertContains(manifest, "\"shardId\": $shardId")
            assertContains(manifest, "\"fingerprint\": \"$fingerprint\"")
        }
        assertContains(manifest, "with a \\\"quoted\\\" note")
    }

    @Test
    fun `artifacts are served by name and cannot escape the release directory`() {
        seedTwoShards()
        val release = publisher.publish(null)

        val served = publisher.artifact(release.version, ReleasePublisher.shardFileName(0))
        assertEquals(
            release.fingerprints[0],
            ShardSpec.from(Properties().apply { load(served!!.reader()) }).fingerprint(),
        )
        assertNull(publisher.artifact(release.version, "../../etc/passwd"))
        assertNull(publisher.artifact(release.version, "nonexistent.properties"))
        assertNull(publisher.artifact(release.version + 999, ReleasePublisher.REGISTRY_FILE))
    }

    @Test
    fun `releases are listed newest first and the latest is the newest`() {
        seedTwoShards()
        val first = publisher.publish("one")
        val second = publisher.publish("two")

        assertEquals(listOf(second.version, first.version), publisher.releases().map { it.version })
        assertEquals(second.version, publisher.latest()?.version)
        assertEquals(second.fingerprints, publisher.latest()?.fingerprints)
    }
}
