package com.engine.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrderBookAuctionTest {

    private fun scratch() = LongArray(1024)

    @Test
    fun `an uncrossed book produces no auction price`() {
        val book = newBook()
        val ids = Ids()
        book.add(ids, Side.BUY, price = 99, qty = 10)
        book.add(ids, Side.SELL, price = 101, qty = 10)

        assertEquals(NO_UNCROSS, book.computeUncrossPrice(scratch()))
    }

    @Test
    fun `an empty book produces no auction price`() {
        assertEquals(NO_UNCROSS, newBook().computeUncrossPrice(scratch()))
    }

    @Test
    fun `the price maximising executable volume wins`() {
        val book = newBook(reference = 100L)
        val ids = Ids()
        // Buyers at 102 and 101, sellers at 99 and 100. Most volume clears at 100/101.
        book.add(ids, Side.BUY, price = 102, qty = 10)
        book.add(ids, Side.BUY, price = 101, qty = 30)
        book.add(ids, Side.SELL, price = 99, qty = 10)
        book.add(ids, Side.SELL, price = 100, qty = 30)

        val price = book.computeUncrossPrice(scratch())
        assertEquals(100L, price)
    }

    @Test
    fun `a buy surplus resolves to the highest tied price`() {
        val book = newBook(reference = 100L)
        val ids = Ids()
        // 20 lots clear anywhere in 100..102; demand exceeds supply at every tied price.
        book.add(ids, Side.BUY, price = 102, qty = 50)
        book.add(ids, Side.SELL, price = 100, qty = 20)

        assertEquals(102L, book.computeUncrossPrice(scratch()))
    }

    @Test
    fun `a sell surplus resolves to the lowest tied price`() {
        val book = newBook(reference = 100L)
        val ids = Ids()
        book.add(ids, Side.BUY, price = 102, qty = 20)
        book.add(ids, Side.SELL, price = 100, qty = 50)

        assertEquals(100L, book.computeUncrossPrice(scratch()))
    }

    @Test
    fun `a balanced book resolves to the price nearest the dynamic reference`() {
        val book = newBook(reference = 101L)
        val ids = Ids()
        // Equal size crossing: every price in 100..102 clears 20 with zero imbalance.
        book.add(ids, Side.BUY, price = 102, qty = 20)
        book.add(ids, Side.SELL, price = 100, qty = 20)

        assertEquals(101L, book.computeUncrossPrice(scratch()))

        book.dynamicReference = 100L
        assertEquals(100L, book.computeUncrossPrice(scratch()))

        book.dynamicReference = 500L
        assertEquals(102L, book.computeUncrossPrice(scratch()))
    }

    @Test
    fun `uncross executes at a single price in price-time priority`() {
        val book = newBook(reference = 100L)
        val ids = Ids()
        val b1 = book.add(ids, Side.BUY, price = 102, qty = 5, participantId = 1L)
        val b2 = book.add(ids, Side.BUY, price = 101, qty = 5, participantId = 2L)
        val s1 = book.add(ids, Side.SELL, price = 99, qty = 5, participantId = 3L)
        val s2 = book.add(ids, Side.SELL, price = 100, qty = 5, participantId = 4L)

        val result = book.runUncross()

        assertTrue(result.trades.isNotEmpty())
        assertTrue(result.trades.all { it.price == result.price })
        // Best bid pairs with best offer first.
        assertEquals(b1, result.trades.first().buyOrderId)
        assertEquals(s1, result.trades.first().sellOrderId)
        assertEquals(10L, result.trades.sumOf { it.qty })
        assertContentEquals(listOf(b1, b2), result.trades.map { it.buyOrderId }.distinct())
        assertContentEquals(listOf(s1, s2), result.trades.map { it.sellOrderId }.distinct())
    }

    @Test
    fun `an executing uncross resets both references`() {
        val book = newBook(reference = 50L)
        val ids = Ids()
        book.add(ids, Side.BUY, price = 102, qty = 10, participantId = 1L)
        book.add(ids, Side.SELL, price = 102, qty = 10, participantId = 2L)

        val result = book.runUncross()

        assertEquals(102L, result.price)
        assertEquals(102L, book.staticReference)
        assertEquals(102L, book.dynamicReference)
    }

    @Test
    fun `an uncross with no trade leaves both references untouched`() {
        val book = newBook(reference = 50L)
        val ids = Ids()
        book.add(ids, Side.BUY, price = 40, qty = 10)
        book.add(ids, Side.SELL, price = 60, qty = 10)

        val result = book.runUncross()

        assertEquals(NO_UNCROSS, result.price)
        assertEquals(50L, book.staticReference)
        assertEquals(50L, book.dynamicReference)
    }

    @Test
    fun `the surplus side is left resting after the uncross`() {
        val book = newBook(reference = 100L)
        val ids = Ids()
        val big = book.add(ids, Side.BUY, price = 100, qty = 30, participantId = 1L)
        book.add(ids, Side.SELL, price = 100, qty = 10, participantId = 2L)

        val result = book.runUncross()

        assertEquals(10L, result.trades.sumOf { it.qty })
        assertEquals(20L, book.leavesQtyOf(book.indexOf(big)))
        assertEquals(NULL_LEVEL, book.bestAsk())
    }
}

class OrderBookAuctionSmpTest {

    @Test
    fun `a self matching pair is resolved before the auction prints`() {
        val book = newBook(reference = 100L)
        val ids = Ids()
        val buy = book.add(ids, Side.BUY, price = 100, qty = 10, participantId = 7L)
        val sell = book.add(ids, Side.SELL, price = 100, qty = 10, participantId = 7L)

        val result = book.runUncross()

        // The later order (the sell) plays the aggressor; default strategy cancels it.
        assertContentEquals(listOf(sell), result.canceled)
        assertTrue(result.trades.isEmpty())
        assertEquals(NO_UNCROSS, result.price)
        assertEquals(10L, book.leavesQtyOf(book.indexOf(buy)))
    }

    @Test
    fun `cancel resting on the later order cancels the earlier one`() {
        val book = newBook(reference = 100L)
        val ids = Ids()
        val buy = book.add(ids, Side.BUY, price = 100, qty = 10, participantId = 7L)
        val sell = book.add(
            ids, Side.SELL, price = 100, qty = 10, participantId = 7L,
            smpStrategy = SmpStrategy.CANCEL_RESTING,
        )

        val result = book.runUncross()

        assertContentEquals(listOf(buy), result.canceled)
        assertEquals(10L, book.leavesQtyOf(book.indexOf(sell)))
    }

    @Test
    fun `the auction still clears against a third party after a self match`() {
        val book = newBook(reference = 100L)
        val ids = Ids()
        val ownBuy = book.add(ids, Side.BUY, price = 100, qty = 10, participantId = 7L)
        val otherBuy = book.add(ids, Side.BUY, price = 100, qty = 10, participantId = 8L)
        val ownSell = book.add(ids, Side.SELL, price = 100, qty = 10, participantId = 7L)

        val result = book.runUncross()

        // ownSell is later than ownBuy, so the default strategy cancels the sell — and with
        // it the only liquidity, leaving nothing to clear.
        assertContentEquals(listOf(ownSell), result.canceled)
        assertTrue(result.trades.isEmpty())
        assertEquals(10L, book.leavesQtyOf(book.indexOf(ownBuy)))
        assertEquals(10L, book.leavesQtyOf(book.indexOf(otherBuy)))
    }

    @Test
    fun `a self match behind a clean pair does not disturb it`() {
        val book = newBook(reference = 100L)
        val ids = Ids()
        val cleanBuy = book.add(ids, Side.BUY, price = 100, qty = 10, participantId = 1L)
        val cleanSell = book.add(ids, Side.SELL, price = 100, qty = 10, participantId = 2L)
        val ownBuy = book.add(ids, Side.BUY, price = 100, qty = 10, participantId = 7L)
        val ownSell = book.add(ids, Side.SELL, price = 100, qty = 10, participantId = 7L)

        val result = book.runUncross()

        assertContentEquals(listOf(ownSell), result.canceled)
        assertEquals(1, result.trades.size)
        assertEquals(cleanBuy, result.trades.single().buyOrderId)
        assertEquals(cleanSell, result.trades.single().sellOrderId)
        assertEquals(10L, result.trades.single().qty)
        assertEquals(10L, book.leavesQtyOf(book.indexOf(ownBuy)))
    }

    @Test
    fun `the walk converges when a cancellation moves the uncross price`() {
        val book = newBook(reference = 100L)
        val ids = Ids()
        // Own pair sets the crossing at 105; removing it re-prices the auction at 100.
        book.add(ids, Side.BUY, price = 100, qty = 10, participantId = 1L)
        book.add(ids, Side.SELL, price = 100, qty = 10, participantId = 2L)
        book.add(ids, Side.BUY, price = 105, qty = 50, participantId = 7L)
        val ownSell = book.add(ids, Side.SELL, price = 95, qty = 50, participantId = 7L)

        val result = book.runUncross()

        assertFalse(result.passLimitHit)
        assertTrue(result.canceled.contains(ownSell))
        assertTrue(result.trades.isNotEmpty())
        assertTrue(result.trades.all { it.price == result.price })
    }

    @Test
    fun `one walk resolves every collision at a single price`() {
        // Six self-matching pairs at one price. Because cancelling only advances the cursor,
        // a single walk clears all of them -- the loop does not iterate per pair.
        val book = newBook(reference = 100L)
        val ids = Ids()
        repeat(6) {
            book.add(ids, Side.BUY, price = 100, qty = 1, participantId = 7L)
            book.add(ids, Side.SELL, price = 100, qty = 1, participantId = 7L)
        }

        val result = book.runUncross(maxPasses = 2)

        assertFalse(result.passLimitHit)
        assertEquals(6, result.canceled.size)
        assertEquals(NO_UNCROSS, result.price)
    }

    @Test
    fun `the pass limit is a safety valve rather than a spin`() {
        // Needs two iterations: the first cancels the self-matching pair, and the book still
        // crosses afterwards, so the price must be recomputed.
        val book = newBook(reference = 100L)
        val ids = Ids()
        book.add(ids, Side.BUY, price = 100, qty = 10, participantId = 1L)
        book.add(ids, Side.SELL, price = 100, qty = 10, participantId = 2L)
        book.add(ids, Side.BUY, price = 105, qty = 50, participantId = 7L)
        book.add(ids, Side.SELL, price = 95, qty = 50, participantId = 7L)

        val result = book.runUncross(maxPasses = 1)

        assertTrue(result.passLimitHit)
        assertEquals(NO_UNCROSS, result.price)
    }
}
