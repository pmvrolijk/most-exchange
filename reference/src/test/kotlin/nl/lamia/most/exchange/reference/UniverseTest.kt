package nl.lamia.most.exchange.reference

import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class UniverseTest {

    private fun registry(vararg shardIds: Int): Properties = Properties().apply {
        setProperty("discovery.shards", shardIds.joinToString(","))
        shardIds.forEach {
            setProperty("discovery.shard.$it.securitiesFile", "shard-$it.properties")
            setProperty("discovery.shard.$it.orderEntryChannel", "aeron:udp?endpoint=gw$it:20001")
            setProperty("discovery.shard.$it.orderEntryStreamId", "20")
            setProperty("discovery.shard.$it.executionReportChannel", "aeron:udp?endpoint=gw$it:20002")
            setProperty("discovery.shard.$it.executionReportStreamId", "21")
        }
    }

    private fun resolver(specs: Map<String, ShardSpec>): (String) -> ShardSpec =
        { path -> specs[path] ?: error("no spec for $path") }

    private fun shard(id: Int, securityIds: List<Int>, isins: List<String>, symbols: List<String>) =
        ShardSpec(id, securityIds.indices.map {
            spec(securityIds[it], symbols[it], isins[it])
        })

    @Test
    fun `assembles securities across shards`() {
        val universe = Universe.from(
            registry(1, 2),
            resolver(
                mapOf(
                    "shard-1.properties" to shard(1, listOf(1), listOf(ISIN_APPLE), listOf("AAPL")),
                    "shard-2.properties" to shard(2, listOf(2), listOf(ISIN_BMW), listOf("BMW")),
                ),
            ),
        )

        assertEquals(2, universe.entries.size)
        assertEquals(1, universe.shardFor(1))
        assertEquals(2, universe.shardFor(2))
        assertEquals("BMW", universe.bySymbol("BMW")?.spec?.symbol)
        assertEquals("aeron:udp?endpoint=gw2:20001", universe.routeFor(2)?.orderEntryChannel)
        assertEquals(21, universe.routeFor(2)?.executionReportStreamId)
    }

    @Test
    fun `a security served by two shards is rejected`() {
        // Books are independent and nothing matches across shards, so this would silently give
        // clients two disjoint books under one identifier.
        val error = assertFailsWith<IllegalArgumentException> {
            Universe.from(
                registry(1, 2),
                resolver(
                    mapOf(
                        "shard-1.properties" to shard(1, listOf(7), listOf(ISIN_APPLE), listOf("AAPL")),
                        "shard-2.properties" to shard(2, listOf(7), listOf(ISIN_BMW), listOf("BMW")),
                    ),
                ),
            )
        }
        assertContains(error.message!!, "exactly one shard")
        assertContains(error.message!!, "7 on shards [1, 2]")
    }

    @Test
    fun `a symbol reused across shards is rejected`() {
        val error = assertFailsWith<IllegalArgumentException> {
            Universe.from(
                registry(1, 2),
                resolver(
                    mapOf(
                        "shard-1.properties" to shard(1, listOf(1), listOf(ISIN_APPLE), listOf("AAPL")),
                        "shard-2.properties" to shard(2, listOf(2), listOf(ISIN_BMW), listOf("AAPL")),
                    ),
                ),
            )
        }
        assertContains(error.message!!, "duplicate symbol")
    }

    @Test
    fun `a security file declaring the wrong shard is rejected`() {
        val error = assertFailsWith<IllegalArgumentException> {
            Universe.from(
                registry(1),
                resolver(
                    mapOf(
                        "shard-1.properties" to shard(9, listOf(1), listOf(ISIN_APPLE), listOf("AAPL")),
                    ),
                ),
            )
        }
        assertContains(error.message!!, "shard.id=9")
    }

    @Test
    fun `a missing channel is reported by key`() {
        val incomplete = registry(1).apply { remove("discovery.shard.1.orderEntryChannel") }
        val error = assertFailsWith<IllegalStateException> {
            Universe.from(
                incomplete,
                resolver(
                    mapOf(
                        "shard-1.properties" to shard(1, listOf(1), listOf(ISIN_APPLE), listOf("AAPL")),
                    ),
                ),
            )
        }
        assertContains(error.message!!, "discovery.shard.1.orderEntryChannel")
    }

    @Test
    fun `the version changes only when content changes`() {
        fun build(tick: Long) = Universe.from(
            registry(1),
            resolver(
                mapOf(
                    "shard-1.properties" to ShardSpec(
                        1,
                        listOf(spec(1, "AAPL", ISIN_APPLE).copy(tickSize = tick)),
                    ),
                ),
            ),
        )

        assertEquals(build(1_000_000).version, build(1_000_000).version)
        assertNotEquals(build(1_000_000).version, build(500_000).version)
    }
}
