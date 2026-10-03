package nl.lamia.most.exchange.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrderBookCollarTest {

    @Test
    fun `static collar bounds acceptance around the session anchor`() {
        val book = newBook(staticCollarBps = 1000, reference = 100L) // +/- 10%
        assertFalse(book.isPriceOutOfBounds(110))
        assertFalse(book.isPriceOutOfBounds(90))
        assertTrue(book.isPriceOutOfBounds(111))
        assertTrue(book.isPriceOutOfBounds(89))
    }

    @Test
    fun `static collar tracks the static anchor not the last trade`() {
        val book = newBook(staticCollarBps = 1000, reference = 100L)
        val ids = Ids()
        book.add(ids, Side.SELL, price = 108, qty = 1)
        book.aggress(Side.BUY, price = 108, qty = 1, smpId = 999)

        assertEquals(108L, book.dynamicReference)
        assertEquals(100L, book.staticReference)
        // Still measured against 100, so 111 stays out of bounds despite the 108 print.
        assertTrue(book.isPriceOutOfBounds(111))
    }

    @Test
    fun `dynamic collar halts the aggressor at the breaching level`() {
        val book = newBook(dynamicCollarBps = 500, reference = 100L) // +/- 5 ticks
        val ids = Ids()
        val inside = book.add(ids, Side.SELL, price = 104, qty = 2)
        book.add(ids, Side.SELL, price = 106, qty = 2)

        val (outcome, fills) = book.aggress(Side.BUY, price = 110, qty = 4, smpId = 999)

        assertEquals(MatchStatus.COLLAR_BREACH, outcome.status)
        assertEquals(106L, outcome.attemptedPrice)
        assertEquals(5L, outcome.breachedBound)
        assertEquals(100L, outcome.collarReference)

        // The fill inside the collar stands; the breaching one never printed.
        assertEquals(listOf(inside), fills.map { it.makerOrderId })
        assertEquals(2L, outcome.filledQty)
        assertEquals(106, book.bestAsk())
    }

    @Test
    fun `the collar reference is snapshotted so an order cannot ratchet`() {
        // Each level sits within 5 of the one below it, so a live reference would let this
        // order walk the whole book. Against the arrival snapshot of 100 it must stop at 105.
        val book = newBook(dynamicCollarBps = 500, reference = 100L)
        val ids = Ids()
        book.add(ids, Side.SELL, price = 104, qty = 1)
        book.add(ids, Side.SELL, price = 108, qty = 1)
        book.add(ids, Side.SELL, price = 112, qty = 1)

        val (outcome, fills) = book.aggress(Side.BUY, price = 999, qty = 3, smpId = 999)

        assertEquals(MatchStatus.COLLAR_BREACH, outcome.status)
        assertEquals(1, fills.size)
        assertEquals(104L, fills.single().price)
        assertEquals(108L, outcome.attemptedPrice)
    }

    @Test
    fun `a zero collar disables the gate`() {
        val book = newBook(dynamicCollarBps = 0, staticCollarBps = 0, reference = 100L)
        val ids = Ids()
        book.add(ids, Side.SELL, price = 500, qty = 1)
        assertFalse(book.isPriceOutOfBounds(9999))

        val (outcome, _) = book.aggress(Side.BUY, price = 500, qty = 1, smpId = 999)
        assertEquals(MatchStatus.COMPLETE, outcome.status)
    }
}

class OrderBookSmpTest {

    @Test
    fun `cancel aggressor stops matching and leaves the resting order`() {
        val book = newBook()
        val ids = Ids()
        val resting = book.add(ids, Side.SELL, price = 100, qty = 5, participantId = 7L)

        val canceled = mutableListOf<Long>()
        val (outcome, fills) = book.aggress(
            Side.BUY, price = 100, qty = 5, smpId = 7L,
            smpStrategy = SmpStrategy.CANCEL_AGGRESSOR, canceledResting = canceled,
        )

        assertEquals(MatchStatus.SELF_MATCH_STOP, outcome.status)
        assertEquals(0L, outcome.filledQty)
        assertTrue(fills.isEmpty())
        assertTrue(canceled.isEmpty())
        assertEquals(5L, book.leavesQtyOf(book.indexOf(resting)))
    }

    @Test
    fun `cancel resting removes the maker and matching continues`() {
        val book = newBook()
        val ids = Ids()
        val own = book.add(ids, Side.SELL, price = 100, qty = 5, participantId = 7L)
        val other = book.add(ids, Side.SELL, price = 100, qty = 5, participantId = 8L)

        val canceled = mutableListOf<Long>()
        val (outcome, fills) = book.aggress(
            Side.BUY, price = 100, qty = 5, smpId = 7L,
            smpStrategy = SmpStrategy.CANCEL_RESTING, canceledResting = canceled,
        )

        assertEquals(MatchStatus.COMPLETE, outcome.status)
        assertContentEquals(listOf(own), canceled)
        assertEquals(listOf(other), fills.map { it.makerOrderId })
        assertEquals(5L, outcome.filledQty)
        assertEquals(NULL_INDEX, book.indexOf(own))
    }

    @Test
    fun `an explicit smp id groups orders across participants`() {
        val book = newBook()
        val ids = Ids()
        book.add(ids, Side.SELL, price = 100, qty = 5, participantId = 7L, smpId = 4242L)

        // Different participant, same SMP group.
        val (outcome, _) = book.aggress(Side.BUY, price = 100, qty = 5, smpId = 4242L)
        assertEquals(MatchStatus.SELF_MATCH_STOP, outcome.status)
    }

    @Test
    fun `an absent smp id falls back to the participant id`() {
        val book = newBook()
        val ids = Ids()
        book.add(ids, Side.SELL, price = 100, qty = 5, participantId = 7L, smpId = 0L)

        val (same, _) = book.aggress(
            Side.BUY, price = 100, qty = 5, smpId = effectiveSmpId(0L, 7L),
        )
        assertEquals(MatchStatus.SELF_MATCH_STOP, same.status)

        val (different, fills) = book.aggress(
            Side.BUY, price = 100, qty = 5, smpId = effectiveSmpId(0L, 8L),
        )
        assertEquals(MatchStatus.COMPLETE, different.status)
        assertEquals(1, fills.size)
    }

    @Test
    fun `a self match never moves the reference price`() {
        val book = newBook(reference = 100L)
        val ids = Ids()
        book.add(ids, Side.SELL, price = 104, qty = 5, participantId = 7L)

        book.aggress(Side.BUY, price = 104, qty = 5, smpId = 7L)

        assertEquals(100L, book.dynamicReference)
    }
}

class OrderBookExpiryTest {

    @Test
    fun `purge removes only orders dated before the trading date`() {
        val book = newBook()
        val ids = Ids()
        val stale = book.add(ids, Side.BUY, price = 100, qty = 1, expireDate = 20260828)
        val today = book.add(ids, Side.BUY, price = 100, qty = 1, expireDate = 20260829)
        val future = book.add(ids, Side.BUY, price = 101, qty = 1, expireDate = 20260830)
        val gtc = book.add(ids, Side.SELL, price = 105, qty = 1, expireDate = 0)

        val expired = mutableListOf<Long>()
        book.purgeExpired(20260829) { node -> expired += book.exchangeOrderIdOf(node) }

        assertContentEquals(listOf(stale), expired)
        assertEquals(3, book.restingOrderCount())
        assertEquals(NULL_INDEX, book.indexOf(stale))
        assertTrue(book.indexOf(today) != NULL_INDEX)
        assertTrue(book.indexOf(future) != NULL_INDEX)
        assertTrue(book.indexOf(gtc) != NULL_INDEX)
    }

    @Test
    fun `purging an entire level clears it and rebuilds the touch`() {
        val book = newBook()
        val ids = Ids()
        book.add(ids, Side.BUY, price = 100, qty = 1, expireDate = 20260828)
        book.add(ids, Side.BUY, price = 100, qty = 1, expireDate = 20260828)
        book.add(ids, Side.BUY, price = 98, qty = 1, expireDate = 0)

        book.purgeExpired(20260829) { }

        assertEquals(1, book.restingOrderCount())
        assertEquals(98, book.bestBid())
    }

    @Test
    fun `purge walks past survivors mid queue`() {
        val book = newBook()
        val ids = Ids()
        val a = book.add(ids, Side.BUY, price = 100, qty = 1, expireDate = 20260828)
        val b = book.add(ids, Side.BUY, price = 100, qty = 1, expireDate = 0)
        val c = book.add(ids, Side.BUY, price = 100, qty = 1, expireDate = 20260828)
        val d = book.add(ids, Side.BUY, price = 100, qty = 1, expireDate = 0)

        val expired = mutableListOf<Long>()
        book.purgeExpired(20260829) { node -> expired += book.exchangeOrderIdOf(node) }

        assertContentEquals(listOf(a, c), expired)
        assertEquals(2, book.restingOrderCount())

        // Survivors keep their relative time priority.
        val (_, fills) = book.aggress(Side.SELL, price = 100, qty = 2, smpId = 999)
        assertEquals(listOf(b, d), fills.map { it.makerOrderId })
    }
}

/** Design.md §4.8: the bulk cancel walks the ladders as the purge does. */
class OrderBookBulkCancelTest {

    @Test
    fun `only the named participant's orders are removed, on both sides`() {
        val book = newBook()
        val ids = Ids()
        val bid = book.add(ids, Side.BUY, price = 99, qty = 1, participantId = 7L)
        val otherBid = book.add(ids, Side.BUY, price = 99, qty = 1, participantId = 8L)
        val ask = book.add(ids, Side.SELL, price = 105, qty = 1, participantId = 7L)
        val otherAsk = book.add(ids, Side.SELL, price = 106, qty = 1, participantId = 8L)

        val cancelled = mutableListOf<Long>()
        book.cancelParticipant(7L) { node -> cancelled += book.exchangeOrderIdOf(node) }

        assertContentEquals(listOf(bid, ask), cancelled)
        assertEquals(2, book.restingOrderCount())
        assertEquals(NULL_INDEX, book.indexOf(bid))
        assertEquals(NULL_INDEX, book.indexOf(ask))
        assertTrue(book.indexOf(otherBid) != NULL_INDEX)
        assertTrue(book.indexOf(otherAsk) != NULL_INDEX)
        assertEquals(106, book.bestAsk())
    }

    @Test
    fun `the walk passes survivors mid queue and clears whole levels`() {
        val book = newBook()
        val ids = Ids()
        val a = book.add(ids, Side.BUY, price = 100, qty = 1, participantId = 7L)
        book.add(ids, Side.BUY, price = 100, qty = 1, participantId = 8L)
        val c = book.add(ids, Side.BUY, price = 100, qty = 1, participantId = 7L)
        val d = book.add(ids, Side.BUY, price = 101, qty = 1, participantId = 7L)

        val cancelled = mutableListOf<Long>()
        book.cancelParticipant(7L) { node -> cancelled += book.exchangeOrderIdOf(node) }

        assertContentEquals(listOf(a, c, d), cancelled)
        assertEquals(1, book.restingOrderCount())
        assertEquals(100, book.bestBid())
    }

    @Test
    fun `a participant with nothing resting removes nothing`() {
        val book = newBook()
        val ids = Ids()
        book.add(ids, Side.BUY, price = 100, qty = 1, participantId = 8L)

        var calls = 0
        book.cancelParticipant(7L) { calls++ }

        assertEquals(0, calls)
        assertEquals(1, book.restingOrderCount())
    }
}
