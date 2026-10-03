package nl.lamia.most.exchange.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrderBookMatchingTest {

    @Test
    fun `resting orders establish the touch`() {
        val book = newBook()
        val ids = Ids()
        book.add(ids, Side.BUY, price = 99, qty = 10)
        book.add(ids, Side.SELL, price = 101, qty = 10)

        assertEquals(99, book.bestBid())
        assertEquals(101, book.bestAsk())
        assertEquals(2, book.restingOrderCount())
    }

    @Test
    fun `an aggressive buy fills the best ask first`() {
        val book = newBook()
        val ids = Ids()
        book.add(ids, Side.SELL, price = 103, qty = 5)
        val best = book.add(ids, Side.SELL, price = 101, qty = 5)

        val (outcome, fills) = book.aggress(Side.BUY, price = 103, qty = 5, smpId = 999)

        assertEquals(MatchStatus.COMPLETE, outcome.status)
        assertEquals(5L, outcome.filledQty)
        assertEquals(1, fills.size)
        assertEquals(best, fills[0].makerOrderId)
        assertEquals(101L, fills[0].price)
    }

    @Test
    fun `fills respect time priority within a level`() {
        val book = newBook()
        val ids = Ids()
        val first = book.add(ids, Side.SELL, price = 100, qty = 4)
        val second = book.add(ids, Side.SELL, price = 100, qty = 4)

        val (_, fills) = book.aggress(Side.BUY, price = 100, qty = 8, smpId = 999)

        assertEquals(listOf(first, second), fills.map { it.makerOrderId })
    }

    @Test
    fun `a partially filled resting order keeps its true remainder`() {
        val book = newBook()
        val ids = Ids()
        val maker = book.add(ids, Side.SELL, price = 100, qty = 10)

        val (_, fills) = book.aggress(Side.BUY, price = 100, qty = 4, smpId = 999)

        assertEquals(6L, fills.single().makerLeaves)
        assertEquals(6L, book.leavesQtyOf(book.indexOf(maker)))
        assertEquals(1, book.restingOrderCount())

        // The remainder is genuinely matchable, not just recorded.
        val (_, more) = book.aggress(Side.BUY, price = 100, qty = 6, smpId = 999)
        assertEquals(6L, more.single().qty)
        assertEquals(0, book.restingOrderCount())
    }

    @Test
    fun `sweeping several levels reports a running remainder`() {
        val book = newBook()
        val ids = Ids()
        book.add(ids, Side.SELL, price = 100, qty = 3)
        book.add(ids, Side.SELL, price = 101, qty = 3)
        book.add(ids, Side.SELL, price = 102, qty = 3)

        val (outcome, fills) = book.aggress(Side.BUY, price = 102, qty = 9, smpId = 999)

        assertEquals(9L, outcome.filledQty)
        assertEquals(listOf(100L, 101L, 102L), fills.map { it.price })
        assertEquals(NULL_LEVEL, book.bestAsk())
    }

    @Test
    fun `matching stops when the book stops crossing`() {
        val book = newBook()
        val ids = Ids()
        book.add(ids, Side.SELL, price = 100, qty = 2)
        book.add(ids, Side.SELL, price = 105, qty = 2)

        val (outcome, fills) = book.aggress(Side.BUY, price = 100, qty = 10, smpId = 999)

        assertEquals(2L, outcome.filledQty)
        assertEquals(1, fills.size)
        assertEquals(105, book.bestAsk())
    }

    @Test
    fun `every trade advances the dynamic reference but not the static anchor`() {
        val book = newBook(reference = 100L)
        val ids = Ids()
        book.add(ids, Side.SELL, price = 104, qty = 1)

        book.aggress(Side.BUY, price = 104, qty = 1, smpId = 999)

        assertEquals(104L, book.dynamicReference)
        assertEquals(100L, book.staticReference)
    }

    @Test
    fun `cancel requires matching participant and original client order id`() {
        val book = newBook()
        val ids = Ids()
        val id = book.add(ids, Side.BUY, price = 100, qty = 5, participantId = 7L, clOrdId = 42L)
        val outcome = CancelOutcome()

        assertFalse(book.prepareCancel(id, participantId = 8L, origClOrdId = 42L, outcome))
        assertEquals(RejectReason.UNAUTHORIZED_PARTICIPANT, outcome.rejectReason)

        assertFalse(book.prepareCancel(id, participantId = 7L, origClOrdId = 43L, outcome))
        assertEquals(RejectReason.UNAUTHORIZED_PARTICIPANT, outcome.rejectReason)

        assertFalse(book.prepareCancel(9999L, participantId = 7L, origClOrdId = 42L, outcome))
        assertEquals(RejectReason.UNKNOWN_ORDER, outcome.rejectReason)

        assertTrue(book.prepareCancel(id, participantId = 7L, origClOrdId = 42L, outcome))
        assertEquals(100L, outcome.price)
        assertEquals(5L, outcome.leavesQty)
        assertEquals(Side.BUY, outcome.side)
    }

    @Test
    fun `cancelling frees the slot and clears the level`() {
        val book = newBook()
        val ids = Ids()
        val id = book.add(ids, Side.BUY, price = 100, qty = 5)
        val outcome = CancelOutcome()

        assertTrue(book.prepareCancel(id, 1L, id, outcome))
        book.unlink(outcome.nodeIndex)

        assertEquals(0, book.restingOrderCount())
        assertEquals(NULL_LEVEL, book.bestBid())
        assertEquals(NULL_INDEX, book.indexOf(id))
    }

    @Test
    fun `cancelling the middle of a level preserves the rest of the queue`() {
        val book = newBook()
        val ids = Ids()
        val first = book.add(ids, Side.SELL, price = 100, qty = 1)
        val middle = book.add(ids, Side.SELL, price = 100, qty = 1)
        val last = book.add(ids, Side.SELL, price = 100, qty = 1)

        val outcome = CancelOutcome()
        assertTrue(book.prepareCancel(middle, 1L, middle, outcome))
        book.unlink(outcome.nodeIndex)

        val (_, fills) = book.aggress(Side.BUY, price = 100, qty = 2, smpId = 999)
        assertEquals(listOf(first, last), fills.map { it.makerOrderId })
    }

    @Test
    fun `freed slots are reused so the pool does not leak`() {
        val book = newBook(maxOrders = 4)
        val ids = Ids()
        repeat(20) {
            val id = book.add(ids, Side.BUY, price = 100, qty = 1)
            val outcome = CancelOutcome()
            assertTrue(book.prepareCancel(id, 1L, id, outcome))
            book.unlink(outcome.nodeIndex)
        }
        assertEquals(0, book.restingOrderCount())
    }

    @Test
    fun `capacity high water mark is reported before the pool is exhausted`() {
        val book = newBook(maxOrders = 100)
        val ids = Ids()
        while (book.hasCapacity()) book.add(ids, Side.BUY, price = 100, qty = 1)

        assertEquals(95, book.restingOrderCount())
        assertFalse(book.hasCapacity())
    }
}
