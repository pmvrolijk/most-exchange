package nl.lamia.most.exchange.control

import nl.lamia.most.exchange.reference.ShardSpec
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The claim this module has to earn: a published file is a drop-in replacement for a hand-written
 * one, not a second dialect.
 *
 * The test takes the shard security file checked into `reference`, imports it, publishes it back
 * out, and asserts the fingerprint is unchanged. Since the fingerprint is what every process prints
 * at startup and what an operator compares across nodes, an equal fingerprint over a round trip
 * through Postgres is the strongest available statement that nothing was lost or reinterpreted.
 */
@SpringBootTest
class SpecImporterTest : PostgresTest() {

    @Autowired
    private lateinit var topology: TopologyService

    @Autowired
    private lateinit var importer: SpecImporter

    @Autowired
    private lateinit var publisher: ReleasePublisher

    private fun sampleText(): String = checkNotNull(
        ShardSpec::class.java.getResourceAsStream("/shard-1-securities.properties"),
    ) { "the reference module's sample shard file is missing from the classpath" }
        .bufferedReader().use { it.readText() }

    @Test
    fun `the checked-in shard file survives a round trip through the database`() {
        val original = ShardSpec.from(Properties().apply { load(sampleText().reader()) })
        topology.createShard(shardRow(original.shardId))

        val imported = importer.import(sampleText())
        assertEquals(original.shardId, imported.shardId)
        assertEquals(original.fingerprint(), imported.fingerprint)
        assertEquals(original.securities.map { it.symbol }, imported.inserted)
        assertTrue(imported.updated.isEmpty())

        val release = publisher.publish("imported")
        val republished = ShardSpec.from(
            Properties().apply {
                Files.newInputStream(
                    Path.of(release.directory)
                        .resolve(ReleasePublisher.shardFileName(original.shardId)),
                ).use { load(it) }
            },
        )

        assertEquals(original, republished)
        assertEquals(original.fingerprint(), republished.fingerprint())
    }

    @Test
    fun `re-importing the same file updates rather than duplicating`() {
        val original = ShardSpec.from(Properties().apply { load(sampleText().reader()) })
        topology.createShard(shardRow(original.shardId))

        importer.import(sampleText())
        val second = importer.import(sampleText())

        assertTrue(second.inserted.isEmpty())
        assertEquals(original.securities.map { it.symbol }, second.updated)
        assertEquals(original.securities.size, topology.securities().size)
        assertEquals(original.fingerprint(), second.fingerprint)
    }

    @Test
    fun `importing a change to an existing security is reflected in the fingerprint`() {
        val original = ShardSpec.from(Properties().apply { load(sampleText().reader()) })
        topology.createShard(shardRow(original.shardId))
        importer.import(sampleText())

        val retick = sampleText().replace("tickSize=1000000", "tickSize=500000")
        val result = importer.import(retick)

        assertEquals(
            ShardSpec.from(Properties().apply { load(retick.reader()) }).fingerprint(),
            result.fingerprint,
        )
    }

    @Test
    fun `importing into a shard that does not exist says why`() {
        val e = assertFailsWith<IllegalArgumentException> { importer.import(sampleText()) }
        // A shard security file carries no endpoints, so the shard genuinely cannot be inferred.
        assertContains(e.message.orEmpty(), "does not carry them")
    }

    @Test
    fun `an invalid file is rejected by the same parser that would have booted it`() {
        topology.createShard(shardRow(1))
        val corrupt = sampleText().replace("US0378331005", "US0378331006")
        assertFailsWith<IllegalArgumentException> { importer.import(corrupt) }
        assertTrue(topology.securities().isEmpty(), "a rejected import must write nothing")
    }
}
