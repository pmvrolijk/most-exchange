package com.engine.core

import com.engine.reference.IdleStrategySpec
import com.engine.reference.SecuritySpec
import com.engine.reference.ShardSpec
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Security parsing, validation and the fingerprint live in [ShardSpec] and are tested there;
 * every process in the shard reads that same file. What remains here is node-local wiring.
 */
class EngineConfigTest {

    private fun shard(vararg securityIds: Int, levelCount: Int = 65536) = ShardSpec(
        shardId = 1,
        securities = securityIds.map {
            SecuritySpec(
                securityId = it,
                symbol = "S$it",
                isin = if (it % 2 == 0) "DE0005190003" else "US0378331005",
                name = "Security $it",
                currency = "USD",
                priceFloor = 0,
                tickSize = 1_000_000,
                levelCount = levelCount,
                maxOrders = 1_000_000,
            )
        },
    )

    @Test
    fun `applies node-local defaults`() {
        val config = EngineConfig.from(Properties(), shard(1))

        assertEquals("aeron:ipc", config.bookEventChannel)
        assertEquals(12, config.bookEventStreamId)
        assertEquals(0, config.serviceId)
        assertEquals(64, config.auctionMaxPasses)
    }

    @Test
    fun `overrides replace defaults`() {
        val properties = Properties().apply {
            setProperty(EngineConfig.BOOK_EVENT_CHANNEL, "aeron:udp?endpoint=239.1.1.1:9000")
            setProperty(EngineConfig.BOOK_EVENT_STREAM_ID, "77")
            setProperty(EngineConfig.SERVICE_ID, "3")
            setProperty(EngineConfig.AUCTION_MAX_PASSES, "8")
        }
        val config = EngineConfig.from(properties, shard(1))

        assertEquals("aeron:udp?endpoint=239.1.1.1:9000", config.bookEventChannel)
        assertEquals(77, config.bookEventStreamId)
        assertEquals(3, config.serviceId)
        assertEquals(8, config.auctionMaxPasses)
    }

    @Test
    fun `books are built to the shard geometry`() {
        val spec = SecuritySpec(
            securityId = 5, symbol = "TEST", isin = "US0378331005", name = "Test",
            currency = "USD", priceFloor = 1000, tickSize = 25, levelCount = 128, maxOrders = 64,
        )
        val config = EngineConfig.from(Properties(), ShardSpec(1, listOf(spec)))
        val book = config.newBooks().single()

        assertEquals(5, book.securityId)
        assertEquals(1000L, book.priceFloor)
        assertEquals(25L, book.tickSize)
        assertEquals(128, book.levelCount)
        assertEquals(1000L + 25L * 4, book.priceOf(4))
    }

    @Test
    fun `the scratch buffer is sized for the widest ladder in the shard`() {
        val wide = ShardSpec(
            1,
            listOf(
                SecuritySpec(1, "A", "US0378331005", "A", "USD", 0, 1, 1024, 10),
                SecuritySpec(2, "B", "DE0005190003", "B", "USD", 0, 1, 131072, 10),
            ),
        )
        assertEquals(131072, EngineConfig.from(Properties(), wide).maxLevelCount())
    }

    @Test
    fun `the engine reports the shard fingerprint`() {
        val spec = shard(1, 2)
        assertEquals(spec.fingerprint(), EngineConfig.from(Properties(), spec).fingerprint())
    }

    @Test
    fun `the securities file is required`() {
        val error = assertFailsWith<IllegalStateException> { EngineConfig.load(null) }
        assertContains(error.message!!, EngineConfig.SECURITIES_FILE)
    }

    @Test
    fun `a missing config file is reported rather than ignored`() {
        val error = assertFailsWith<IllegalArgumentException> {
            EngineConfig.load("/nonexistent/engine.properties")
        }
        assertTrue(error.message!!.contains("config file not found"))
    }

    @Test
    fun `a non-positive auction pass limit is rejected`() {
        val properties = Properties().apply { setProperty(EngineConfig.AUCTION_MAX_PASSES, "0") }
        assertFailsWith<IllegalArgumentException> { EngineConfig.from(properties, shard(1)) }
    }

    // Design.md §7, "Duty cycle": busy-spin by default, and node-local like the metrics.

    @Test
    fun `the service thread busy-spins unless configured otherwise`() {
        assertEquals(IdleStrategySpec.BUSY_SPIN, EngineConfig.from(Properties(), shard(1)).idleStrategy)
    }

    @Test
    fun `the idle strategy is read from configuration`() {
        val properties = Properties().apply { setProperty(EngineConfig.IDLE_STRATEGY, "sleeping:50") }
        assertEquals(IdleStrategySpec.parse("sleeping:50"), EngineConfig.from(properties, shard(1)).idleStrategy)
    }

    @Test
    fun `how a node idles is not part of what it must agree on`() {
        // Two nodes of one cluster may idle differently, exactly as they may differ on metrics.
        val spec = shard(1, 2)
        val spinning = EngineConfig.from(Properties(), spec)
        val sleeping = EngineConfig.from(
            Properties().apply {
                setProperty(EngineConfig.IDLE_STRATEGY, "backoff")
                setProperty(EngineConfig.METRICS_ENABLED, "true")
            },
            spec,
        )
        assertEquals(spinning.fingerprint(), sleeping.fingerprint())
    }

    @Test
    fun `an unknown idle strategy stops the engine at startup rather than at the first idle`() {
        val properties = Properties().apply { setProperty(EngineConfig.IDLE_STRATEGY, "spin") }
        assertFailsWith<IllegalArgumentException> { EngineConfig.from(properties, shard(1)) }
    }
}
