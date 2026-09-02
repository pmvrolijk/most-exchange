package com.engine.reference

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The wire semantics of a depth level, asserted once against the single implementation every
 * consumer shares. These used to live beside the operator CLI's own copy of a book; there is no
 * longer a second copy for them to drift from.
 */
class AggregatedBookTest {

    private fun book() = AggregatedBook(1)

    @Test
    fun `depth builds both sides and the touch is the best price on each`() {
        val book = book()
        book.applyDepth(isBid = true, price = PriceCodec.parse("99.00"), aggregateQty = 10, orderCount = 2)
        book.applyDepth(isBid = false, price = PriceCodec.parse("101.00"), aggregateQty = 5, orderCount = 1)

        assertEquals(PriceCodec.parse("99.00"), book.bestBid())
        assertEquals(PriceCodec.parse("101.00"), book.bestAsk())
        assertEquals(PriceCodec.parse("2.00"), book.spread())
    }

    @Test
    fun `levels come back best first on both sides`() {
        val book = book()
        listOf("98.00", "99.00", "97.00").forEach { book.applyDepth(true, PriceCodec.parse(it), 1, 1) }
        listOf("102.00", "101.00", "103.00").forEach { book.applyDepth(false, PriceCodec.parse(it), 1, 1) }

        assertEquals(
            listOf("99.00", "98.00", "97.00").map(PriceCodec::parse),
            book.levels(isBid = true, limit = 5).map { it.price },
        )
        assertEquals(
            listOf("101.00", "102.00", "103.00").map(PriceCodec::parse),
            book.levels(isBid = false, limit = 5).map { it.price },
        )
    }

    @Test
    fun `a zero aggregate removes the level`() {
        val book = book()
        val price = PriceCodec.parse("99.00")
        book.applyDepth(true, price, 10, 1)
        book.applyDepth(true, price, 0, 0)

        assertNull(book.bestBid())
        assertTrue(book.isEmpty())
    }

    @Test
    fun `a level is replaced, not accumulated`() {
        // DepthUpdate carries the new aggregate for the level, never a delta. Getting this wrong
        // produces a book that only ever grows, which looks like liquidity that is not there.
        val book = book()
        val price = PriceCodec.parse("99.00")
        book.applyDepth(true, price, 10, 1)
        book.applyDepth(true, price, 25, 3)

        assertEquals(25L, book.levels(isBid = true, limit = 1).single().qty)
        assertEquals(3, book.levels(isBid = true, limit = 1).single().orders)
    }

    @Test
    fun `spread is absent when a side is empty`() {
        val book = book()
        book.applyDepth(true, PriceCodec.parse("99.00"), 10, 1)
        assertNull(book.spread())
    }

    @Test
    fun `clearing the depth keeps the last trade`() {
        // A gap invalidates the aggregates. It does not make the trade that printed untrue.
        val book = book()
        book.applyDepth(true, PriceCodec.parse("99.00"), 10, 1)
        book.applyTrade(PriceCodec.parse("99.50"), 4)

        book.clearDepth()

        assertTrue(book.isEmpty())
        assertEquals(PriceCodec.parse("99.50"), book.lastTradePrice)
        assertEquals(4L, book.lastTradeQty)
    }
}
