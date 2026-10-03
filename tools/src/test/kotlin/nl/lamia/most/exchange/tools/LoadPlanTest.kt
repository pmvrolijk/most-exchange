package nl.lamia.most.exchange.tools

import nl.lamia.most.exchange.client.PriceCodec
import nl.lamia.most.exchange.client.RoutedSecurity
import nl.lamia.most.exchange.sbe.Side
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val TICK = 1_000_000L // 0.01 with eight implied decimals

private fun security(
    securityId: Int = 1,
    symbol: String = "AAPL",
    priceFloor: Long = 0L,
    tickSize: Long = TICK,
    levelCount: Int = 32_768,
) = RoutedSecurity(
    securityId = securityId,
    shardId = 0,
    symbol = symbol,
    isin = "US0378331005",
    name = "Apple Inc.",
    currency = "USD",
    priceFloor = priceFloor,
    tickSize = tickSize,
    levelCount = levelCount,
    orderEntryChannel = "aeron:ipc",
    orderEntryStreamId = 20,
    executionReportChannel = "aeron:ipc",
    executionReportStreamId = 21,
)

private fun spec(vararg extra: String): LoadSpec {
    val base = arrayOf("--price-min", "99.90", "--price-max", "100.10", "--count", "1000")
    return LoadSpec.from(Args(base + extra), participantBase = 7L)
}

class LoadSpecTest {

    @Test
    fun `delay and rate are two spellings of one knob`() {
        assertEquals(10_000L, LoadSpec.delayNsFrom(Args(arrayOf("--delay-us", "10"))))
        assertEquals(10_000L, LoadSpec.delayNsFrom(Args(arrayOf("--rate", "100000"))))
    }

    @Test
    fun `passing both delay and rate is rejected rather than silently resolved`() {
        val args = Args(arrayOf("--delay-us", "10", "--rate", "100000"))
        assertFailsWith<IllegalArgumentException> { LoadSpec.delayNsFrom(args) }
    }

    @Test
    fun `zero delay means unpaced, and reports no target rate`() {
        val unpaced = spec("--delay-us", "0")
        assertEquals(0L, unpaced.delayNs)
        assertEquals(0.0, unpaced.targetRate)
    }

    @Test
    fun `an inverted band is rejected`() {
        val args = Args(arrayOf("--price-min", "100.10", "--price-max", "99.90"))
        assertFailsWith<IllegalArgumentException> { LoadSpec.from(args, 1L) }
    }

    @Test
    fun `an inverted quantity range is rejected`() {
        assertFailsWith<IllegalArgumentException> { spec("--qty-min", "10", "--qty-max", "1") }
    }

    @Test
    fun `warmup must leave orders to measure`() {
        assertFailsWith<IllegalArgumentException> { spec("--warmup", "1000") }
        assertEquals(100, spec().warmup)
    }
}

class OrderPoolTest {

    @Test
    fun `the pool is a power of two, capped`() {
        assertEquals(1, OrderPool.poolSizeFor(1))
        assertEquals(1024, OrderPool.poolSizeFor(1000))
        assertEquals(OrderPool.MAX_POOL, OrderPool.poolSizeFor(10_000_000))
    }

    @Test
    fun `the same seed generates the same orders`() {
        val securities = listOf(security())
        val first = OrderPool.generate(spec("--seed", "99"), securities)
        val second = OrderPool.generate(spec("--seed", "99"), securities)
        assertContentEquals(first.price, second.price)
        assertContentEquals(first.qty, second.qty)
        assertContentEquals(first.side, second.side)
        assertContentEquals(first.participantId, second.participantId)
    }

    @Test
    fun `a different seed generates different orders`() {
        val securities = listOf(security())
        val first = OrderPool.generate(spec("--seed", "1"), securities)
        val second = OrderPool.generate(spec("--seed", "2"), securities)
        assertTrue(first.price.toList() != second.price.toList())
    }

    @Test
    fun `every price is on tick and inside the band`() {
        val subject = security()
        val loadSpec = spec()
        val pool = OrderPool.generate(loadSpec, listOf(subject))
        for (price in pool.price) {
            assertTrue(
                PriceCodec.isOnTick(price, subject.priceFloor, subject.tickSize),
                "${PriceCodec.format(price)} is off tick",
            )
            assertTrue(
                price in loadSpec.priceMin..loadSpec.priceMax,
                "${PriceCodec.format(price)} is outside the band",
            )
        }
    }

    @Test
    fun `a floor that is not a band boundary still yields on-tick prices`() {
        // A ladder floored at 0.005 with a 0.01 tick: every price must land on floor + n*tick,
        // which is what makes the generated tick index, not the price, the thing drawn.
        val subject = security(priceFloor = 500_000L)
        val pool = OrderPool.generate(spec(), listOf(subject))
        for (price in pool.price) {
            assertTrue(PriceCodec.isOnTick(price, subject.priceFloor, subject.tickSize))
        }
    }

    @Test
    fun `quantities, participants and securities stay inside their ranges`() {
        val securities = listOf(security(1, "AAPL"), security(2, "MSFT"))
        val loadSpec = spec("--qty-min", "5", "--qty-max", "9", "--participants", "3")
        val pool = OrderPool.generate(loadSpec, securities)
        for (i in 0..<pool.size) {
            assertTrue(pool.qty[i] in 5L..9L, "qty ${pool.qty[i]}")
            assertTrue(pool.participantId[i] in 7L..9L, "participant ${pool.participantId[i]}")
            assertTrue(pool.securityId[i] in setOf(1, 2), "security ${pool.securityId[i]}")
        }
    }

    @Test
    fun `both sides are generated, so the book can cross`() {
        val pool = OrderPool.generate(spec(), listOf(security()))
        assertTrue(pool.side.any { it == Side.BUY.value() })
        assertTrue(pool.side.any { it == Side.SELL.value() })
    }

    @Test
    fun `slots cycle through the pool`() {
        val pool = OrderPool.generate(spec("--count", "8"), listOf(security()), count = 8)
        assertEquals(8, pool.size)
        assertEquals(0, pool.slotFor(0))
        assertEquals(7, pool.slotFor(7))
        assertEquals(0, pool.slotFor(8))
    }
}

class BandWarningTest {

    @Test
    fun `a band inside the ladder is quiet`() {
        assertTrue(bandWarnings(spec(), listOf(security())).isEmpty())
    }

    @Test
    fun `a band past the top of the ladder is called out`() {
        // 64 levels of 0.01 from zero tops out at 0.63, well below the band.
        val warnings = bandWarnings(spec(), listOf(security(levelCount = 64)))
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("outside its ladder"), warnings.single())
    }

    @Test
    fun `a band too wide to cross is called out`() {
        val wide = LoadSpec.from(
            Args(arrayOf("--price-min", "1.00", "--price-max", "100.00", "--count", "10")),
            participantBase = 1L,
        )
        val warnings = bandWarnings(wide, listOf(security()))
        assertTrue(warnings.any { it.contains("crosses rarely") }, "$warnings")
    }
}

class Xorshift64Test {

    @Test
    fun `bounded draws stay in range and cover it`() {
        val random = Xorshift64(12345L)
        val seen = BooleanArray(4)
        repeat(1000) {
            val value = random.nextInt(4)
            assertTrue(value in 0..3)
            seen[value] = true
        }
        assertTrue(seen.all { it })
    }

    @Test
    fun `a zero seed does not collapse the sequence`() {
        val random = Xorshift64(0L)
        val values = List(10) { random.nextLong() }
        assertTrue(values.distinct().size == values.size)
    }
}
