package nl.lamia.most.exchange.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The order pool bit-packs two int32 links into one word and three small fields into
 * another (Design.md §3.1). Negative values matter: NULL_INDEX is -1, and a naive pack
 * sign-extends it across the neighbouring field.
 */
class OrderPackingTest {

    @Test
    fun `links round-trip including NULL_INDEX`() {
        val packed = packLinks(NULL_INDEX, 12345)
        assertEquals(NULL_INDEX, nextOf(packed))
        assertEquals(12345, prevOf(packed))

        val both = packLinks(NULL_INDEX, NULL_INDEX)
        assertEquals(NULL_INDEX, nextOf(both))
        assertEquals(NULL_INDEX, prevOf(both))
    }

    @Test
    fun `links round-trip at the top of the index range`() {
        val packed = packLinks(Int.MAX_VALUE, Int.MAX_VALUE - 1)
        assertEquals(Int.MAX_VALUE, nextOf(packed))
        assertEquals(Int.MAX_VALUE - 1, prevOf(packed))
    }

    @Test
    fun `meta round-trips expireDate side and smpStrategy`() {
        val meta = packMeta(20260829, Side.SELL, SmpStrategy.CANCEL_RESTING)
        assertEquals(20260829, expireDateOf(meta))
        assertEquals(Side.SELL, sideOf(meta))
        assertEquals(SmpStrategy.CANCEL_RESTING, smpStrategyOf(meta))
    }

    @Test
    fun `GTC orders carry expireDate zero`() {
        val meta = packMeta(0, Side.BUY, SmpStrategy.CANCEL_AGGRESSOR)
        assertEquals(0, expireDateOf(meta))
        assertEquals(Side.BUY, sideOf(meta))
        assertEquals(SmpStrategy.CANCEL_AGGRESSOR, smpStrategyOf(meta))
    }

    @Test
    fun `effective smp id defaults to participant id`() {
        assertEquals(777L, effectiveSmpId(smpId = 0L, participantId = 777L))
        assertEquals(42L, effectiveSmpId(smpId = 42L, participantId = 777L))
    }

    @Test
    fun `an order occupies exactly one cache line`() {
        assertEquals(8, OrderField.STRIDE)
        assertEquals(64, OrderField.STRIDE * Long.SIZE_BYTES)
    }
}
