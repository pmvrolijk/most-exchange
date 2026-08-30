package com.engine.reference

import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The round trip that lets a generated file replace a hand-written one.
 *
 * A control plane that renders shard specs is only safe if what it renders parses back to the same
 * spec with the same fingerprint. Any drift between [ShardSpec.from] and [ShardSpec.render] —
 * a key renamed on one side, a field forgotten — has to fail here rather than in a deployment,
 * where the symptom is four processes printing different fingerprints, or worse, the same
 * fingerprint over different geometry.
 */
class SpecRenderTest {

    private fun parse(text: String): Properties =
        Properties().apply { load(text.reader()) }

    @Test
    fun `a rendered shard spec parses back to itself`() {
        val original = ShardSpec.from(
            shardProperties(
                shardId = 3,
                securities = listOf(1, 2),
                isins = listOf(ISIN_APPLE, ISIN_MICROSOFT),
                symbols = listOf("AAPL", "MSFT"),
            ),
        )
        val reparsed = ShardSpec.from(parse(original.render()))

        assertEquals(original, reparsed)
        assertEquals(original.fingerprint(), reparsed.fingerprint())
    }

    @Test
    fun `toProperties and render agree`() {
        val original = ShardSpec.from(shardProperties())
        assertEquals(
            ShardSpec.from(original.toProperties()),
            ShardSpec.from(parse(original.render())),
        )
    }

    @Test
    fun `rendering is deterministic and independent of declaration order`() {
        // Two publishes of the same universe must produce identical bytes, or an operator diffing
        // two releases learns nothing. Ordering by securityId is what makes that true.
        val ascending = ShardSpec.from(
            shardProperties(
                securities = listOf(1, 2),
                isins = listOf(ISIN_APPLE, ISIN_MICROSOFT),
                symbols = listOf("AAPL", "MSFT"),
            ),
        )
        val descending = ShardSpec(ascending.shardId, ascending.securities.reversed())

        assertEquals(ascending.render(), descending.render())
        assertEquals(ascending.render(), ascending.render())
    }

    @Test
    fun `the rendered spec carries its own fingerprint as a comment`() {
        val spec = ShardSpec.from(shardProperties())
        assertTrue(spec.render().contains("# fingerprint=${spec.fingerprint()}"))
    }

    @Test
    fun `the checked-in sample shard file round trips unchanged`() {
        // The strongest form of the claim: the generator reproduces a file a human already wrote
        // and that the sample configs point at. If this holds, generated files are a drop-in
        // replacement rather than a second dialect.
        val text = checkNotNull(
            ShardSpec::class.java.getResourceAsStream("/shard-1-securities.properties"),
        ) { "shard-1-securities.properties is missing from reference's resources" }
            .bufferedReader().use { it.readText() }

        val handWritten = ShardSpec.from(parse(text))
        val regenerated = ShardSpec.from(parse(handWritten.render()))

        assertEquals(handWritten, regenerated)
        assertEquals(handWritten.fingerprint(), regenerated.fingerprint())
    }

    @Test
    fun `a rendered universe registry parses back to itself`() {
        val universe = universeOf(
            route(1) to listOf(spec(1, "AAPL", ISIN_APPLE)),
            route(2) to listOf(spec(2, "MSFT", ISIN_MICROSOFT)),
        )
        val paths = universe.shards.associate { it.shardId to "/etc/most/shard-${it.shardId}.properties" }

        val specsByPath = universe.shardSpecs().associateBy { paths.getValue(it.shardId) }
        val reparsed = Universe.from(parse(universe.render { paths.getValue(it) })) { path ->
            specsByPath.getValue(path)
        }

        assertEquals(universe.shards.sortedBy { it.shardId }, reparsed.shards.sortedBy { it.shardId })
        assertEquals(universe.version, reparsed.version)
    }

    @Test
    fun `shardSpecs splits the universe back into per-shard lists`() {
        val universe = universeOf(
            route(1) to listOf(spec(1, "AAPL", ISIN_APPLE), spec(3, "BMW", ISIN_BMW)),
            route(2) to listOf(spec(2, "MSFT", ISIN_MICROSOFT)),
        )
        val specs = universe.shardSpecs()

        assertEquals(listOf(1, 2), specs.map { it.shardId })
        assertEquals(listOf(1, 3), specs[0].securities.map { it.securityId })
        assertEquals(listOf(2), specs[1].securities.map { it.securityId })
    }

    private fun route(shardId: Int) = ShardRoute(
        shardId = shardId,
        orderEntryChannel = "aeron:udp?endpoint=gw$shardId:20001",
        orderEntryStreamId = 20,
        executionReportChannel = "aeron:udp?endpoint=gw$shardId:20002",
        executionReportStreamId = 21,
    )

    private fun universeOf(vararg shards: Pair<ShardRoute, List<SecuritySpec>>) = Universe(
        shards = shards.map { it.first },
        entries = shards.flatMap { (route, specs) ->
            specs.map { UniverseEntry(it, route.shardId) }
        },
    )
}
