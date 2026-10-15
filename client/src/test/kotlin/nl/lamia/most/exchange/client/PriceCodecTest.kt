package nl.lamia.most.exchange.client

import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PriceCodecTest {

    @Test
    fun `parses decimal text to the implied scale`() {
        assertEquals(100_00000000L, PriceCodec.parse("100"))
        assertEquals(100_25000000L, PriceCodec.parse("100.25"))
        assertEquals(1L, PriceCodec.parse("0.00000001"))
        assertEquals(-50_50000000L, PriceCodec.parse("-50.5"))
    }

    @Test
    fun `parsing is exact rather than floating point`() {
        // 0.1 + 0.2 famously is not 0.3 in binary floating point; these must survive intact.
        assertEquals(PriceCodec.parse("0.1") + PriceCodec.parse("0.2"), PriceCodec.parse("0.3"))
        assertEquals(1_15000000L, PriceCodec.parse("1.15"))
    }

    @Test
    fun `too many decimal places is an error rather than a silent round`() {
        val error = assertFailsWith<IllegalArgumentException> { PriceCodec.parse("1.123456789") }
        assertContains(error.message!!, "more than 8 decimal places")
    }

    @Test
    fun `rejects junk and empty input`() {
        assertFailsWith<IllegalArgumentException> { PriceCodec.parse("abc") }
        assertFailsWith<IllegalArgumentException> { PriceCodec.parse("") }
        assertFailsWith<IllegalArgumentException> { PriceCodec.parse("1.2.3") }
    }

    @Test
    fun `rejects values beyond the int64 range`() {
        assertFailsWith<IllegalArgumentException> { PriceCodec.parse("99999999999999999999") }
    }

    @Test
    fun `formats with at least two decimals`() {
        assertEquals("100.00", PriceCodec.format(100_00000000L))
        assertEquals("100.25", PriceCodec.format(100_25000000L))
        assertEquals("0.00000001", PriceCodec.format(1L))
        assertEquals("-50.50", PriceCodec.format(-50_50000000L))
    }

    @Test
    fun `round trips`() {
        listOf("0.00", "1.15", "100.25", "9999.99999999").forEach {
            assertEquals(it.toBigDecimal().stripTrailingZeros(),
                PriceCodec.format(PriceCodec.parse(it)).toBigDecimal().stripTrailingZeros())
        }
    }

    // ------------------------------------------------------------------------- from a FIX decimal

    @Test
    fun `a FIX decimal converts exactly to the implied scale`() {
        // Adapters.md §2: a mantissa and a scale, as Artio's DecimalFloat holds them.
        assertEquals(100_25000000L, PriceCodec.fromDecimal(10025L, 2))
        assertEquals(100_00000000L, PriceCodec.fromDecimal(100L, 0))
        assertEquals(1L, PriceCodec.fromDecimal(1L, 8))
        assertEquals(-50_50000000L, PriceCodec.fromDecimal(-505L, 1))
        assertEquals(0L, PriceCodec.fromDecimal(0L, 0))
        // More than 8 places is still exact when the digits beyond the eighth are zeros.
        assertEquals(123L, PriceCodec.fromDecimal(1230L, 9))
        assertEquals(1_00000000L, PriceCodec.fromDecimal(1_000_000_000_000_000_000L, 18))
    }

    @Test
    fun `a FIX decimal that is not exact at 8 places, or does not fit, is INVALID and never rounded`() {
        // Adapters.md §2: INVALID for one that is not exact at 8 decimals or does not fit.
        assertEquals(PriceCodec.INVALID, PriceCodec.fromDecimal(123456789L, 9))
        assertEquals(PriceCodec.INVALID, PriceCodec.fromDecimal(1L, 19))
        assertEquals(PriceCodec.INVALID, PriceCodec.fromDecimal(Long.MAX_VALUE / 10, 0))
        assertEquals(PriceCodec.INVALID, PriceCodec.fromDecimal(Long.MIN_VALUE / 10, 0))
        assertEquals(PriceCodec.INVALID, PriceCodec.fromDecimal(1L, -1))
        assertEquals(PriceCodec.INVALID, PriceCodec.fromDecimal(Long.MIN_VALUE, 8))
    }

    @Test
    fun `a fixed-point value is its own FIX decimal at scale 8`() {
        // Adapters.md §2: the other way needs no conversion.
        listOf("0.00000001", "1.15", "100.25", "-50.5", "9999.99999999").map(PriceCodec::parse).forEach {
            assertEquals(it, PriceCodec.fromDecimal(it, PriceCodec.IMPLIED_DECIMALS))
        }
    }

    @Test
    fun `converting a FIX decimal allocates nothing`() {
        // Adapters.md §2: without allocating. Judged as the engine's proofs are: most windows read zero.
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        var sink = 0L
        fun round() {
            for (scale in 0..12) sink += PriceCodec.fromDecimal(10025L + scale, scale)
            sink += PriceCodec.fromDecimal(123456789L, 9)
        }
        repeat(200_000) { round() }
        val zeroWindows = (1..8).count {
            val before = threads.currentThreadAllocatedBytes
            repeat(100_000) { round() }
            threads.currentThreadAllocatedBytes - before == 0L
        }
        assertTrue(zeroWindows > 4, "only $zeroWindows of 8 windows read zero bytes (sink $sink)")
    }

    @Test
    fun `tick alignment`() {
        assertTrue(PriceCodec.isOnTick(100_00000000L, priceFloor = 0, tickSize = 1_000_000L))
        assertFalse(PriceCodec.isOnTick(100_00500000L, priceFloor = 0, tickSize = 1_000_000L))
        assertFalse(PriceCodec.isOnTick(100L, priceFloor = 0, tickSize = 0L))
    }
}
