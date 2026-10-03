package nl.lamia.most.exchange.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The engine states `origQty` and `cumQty` on every execution report (Design.md §3.1).
 *
 * It used not to. The gateway held `origQty` for every live order, because the packed slot is one
 * cache line and had no room, which made the gateway the only component that could not be
 * restarted or replaced without losing something. `origQty` now lives in the cold word beside the
 * line and the engine is the one party that knows both numbers at the moment a report is made.
 *
 * Every case below exists because it is one a subtraction gets wrong. A terminal report carries
 * `leavesQty = 0` whether the order filled or was cancelled, so `origQty - leavesQty` reports a
 * cancelled order as fully filled -- the defect the gateway shipped with once already.
 */
class OrderQuantityReportingTest {

    private fun harness() = Harness(books = arrayOf(serviceBook(1))).also {
        it.defineSecurity(it.books[0], 100L, 0, 0)
        it.sessionTransition(Phase.CONTINUOUS)
    }

    @Test
    fun `an acknowledgement carries the entered quantity and nothing filled`() {
        val h = harness()
        h.newOrder(1, 100, 1, Side.BUY, 100, 10)

        val ack = h.reports.single { it.execType == "NEW" }
        assertEquals(10L, ack.origQty)
        assertEquals(0L, ack.cumQty)
    }

    @Test
    fun `both sides of a fill carry their own original quantity`() {
        val h = harness()
        h.newOrder(1, 100, 1, Side.SELL, 100, 10)
        h.newOrder(2, 200, 1, Side.BUY, 100, 4)

        val trades = h.reports.filter { it.execType == "TRADE" }
        val maker = trades.single { it.participantId == 1L }
        val taker = trades.single { it.participantId == 2L }

        // The maker rested 10 and has 4 done; the taker entered 4 and is complete.
        assertEquals(10L, maker.origQty)
        assertEquals(4L, maker.cumQty)
        assertEquals(6L, maker.leavesQty)
        assertEquals(4L, taker.origQty)
        assertEquals(4L, taker.cumQty)
    }

    @Test
    fun `cumQty accumulates across a taker's partial fills`() {
        val h = harness()
        h.newOrder(1, 100, 1, Side.SELL, 100, 3)
        h.newOrder(1, 101, 1, Side.SELL, 100, 2)
        h.newOrder(2, 200, 1, Side.BUY, 100, 10)

        val taker = h.reports.filter { it.execType == "TRADE" && it.participantId == 2L }
        assertEquals(listOf(3L, 5L), taker.map { it.cumQty })
        assertEquals(listOf(10L, 10L), taker.map { it.origQty })
    }

    /** The case a subtraction gets wrong, and the reason cumQty is on the wire at all. */
    @Test
    fun `a cancel after a partial fill reports the filled portion, not the whole order`() {
        val h = harness()
        h.newOrder(1, 100, 1, Side.SELL, 100, 10)
        val orderId = h.reports.single { it.execType == "NEW" }.exchangeOrderId
        h.newOrder(2, 200, 1, Side.BUY, 100, 4)
        h.cancel(1, 100, 101, orderId, 1, Side.SELL)

        val cancel = h.reports.single { it.execType == "CANCELED" }
        assertEquals(10L, cancel.origQty)
        assertEquals(4L, cancel.cumQty)
        // The report itself says nothing is left, which is exactly why cumQty cannot be derived.
        assertEquals(0L, cancel.leavesQty)
    }

    @Test
    fun `an expiry reports what the order had done before it expired`() {
        val h = harness()
        h.newOrder(1, 100, 1, Side.SELL, 100, 10, expireDate = 20260829)
        h.newOrder(2, 200, 1, Side.BUY, 100, 4)
        h.purge(20260830)

        val expired = h.reports.single { it.execType == "EXPIRED" }
        assertEquals(10L, expired.origQty)
        assertEquals(4L, expired.cumQty)
    }

    @Test
    fun `a rejected order reports the quantity it was refused for`() {
        val h = harness()
        // Out of the ladder, so it is refused before anything is booked.
        h.newOrder(1, 100, 1, Side.BUY, 100, 0)

        val rejection = h.reports.single { it.execType == "REJECTED" }
        assertEquals(0L, rejection.cumQty)
    }

    @Test
    fun `a self match cancels the aggressor and reports what it had already done`() {
        val h = harness()
        // Two resting sells, one from a different participant so the aggressor trades first.
        h.newOrder(2, 200, 1, Side.SELL, 100, 3)
        h.newOrder(1, 100, 1, Side.SELL, 100, 7)
        h.newOrder(1, 101, 1, Side.BUY, 100, 10)

        val cancel = h.reports.single {
            it.execType == "CANCELED" && it.clOrdId == 101L
        }
        assertEquals(10L, cancel.origQty)
        assertEquals(3L, cancel.cumQty, "the fill against the other participant stands")
    }

    @Test
    fun `an auction fill reports both sides' original quantities`() {
        val h = Harness(books = arrayOf(serviceBook(1))).also {
            it.defineSecurity(it.books[0], 100L, 0, 0)
            it.sessionTransition(Phase.OPEN_AUCTION)
        }
        h.newOrder(1, 100, 1, Side.BUY, 100, 10)
        h.newOrder(2, 200, 1, Side.SELL, 100, 6)
        h.sessionTransition(Phase.CONTINUOUS)

        val trades = h.reports.filter { it.execType == "TRADE" }
        assertEquals(10L, trades.single { it.participantId == 1L }.origQty)
        assertEquals(6L, trades.single { it.participantId == 1L }.cumQty)
        assertEquals(6L, trades.single { it.participantId == 2L }.origQty)
        assertEquals(6L, trades.single { it.participantId == 2L }.cumQty)
    }
}
