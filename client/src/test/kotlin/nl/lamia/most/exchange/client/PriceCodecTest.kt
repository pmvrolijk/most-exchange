package nl.lamia.most.exchange.client

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

    @Test
    fun `tick alignment`() {
        assertTrue(PriceCodec.isOnTick(100_00000000L, priceFloor = 0, tickSize = 1_000_000L))
        assertFalse(PriceCodec.isOnTick(100_00500000L, priceFloor = 0, tickSize = 1_000_000L))
        assertFalse(PriceCodec.isOnTick(100L, priceFloor = 0, tickSize = 0L))
    }
}
