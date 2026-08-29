package com.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MatchingEngineServiceTest {

    private fun harness(vararg securityIds: Int = intArrayOf(1)): Harness =
        Harness(Array(securityIds.size) { serviceBook(securityIds[it]) })

    private fun openContinuous(h: Harness, reference: Long = 100L, static: Int = 0, dynamic: Int = 0) {
        h.books.forEach { h.defineSecurity(it, reference, static, dynamic) }
        h.sessionTransition(Phase.CONTINUOUS)
    }

    @Test
    fun `a new order is acknowledged then rested`() {
        val h = harness()
        openContinuous(h)

        h.newOrder(participantId = 1, clOrdId = 100, securityId = 1, side = Side.BUY, price = 99, qty = 10)

        val ack = h.reports.single()
        assertEquals("NEW", ack.execType)
        assertEquals(1L, ack.exchangeOrderId)
        assertEquals(10L, ack.leavesQty)
        assertEquals(1, h.books[0].restingOrderCount())
    }

    @Test
    fun `exchange order ids are assigned monotonically`() {
        val h = harness()
        openContinuous(h)
        h.newOrder(1, 100, 1, Side.BUY, 99, 10)
        h.newOrder(1, 101, 1, Side.BUY, 98, 10)

        assertEquals(listOf(1L, 2L), h.reports.map { it.exchangeOrderId })
        assertEquals(3L, h.service.nextExchangeOrderId)
    }

    @Test
    fun `a trade reports both sides with the taker running remainder`() {
        val h = harness()
        openContinuous(h)
        h.newOrder(participantId = 1, clOrdId = 100, securityId = 1, side = Side.SELL, price = 100, qty = 4)
        h.newOrder(participantId = 1, clOrdId = 101, securityId = 1, side = Side.SELL, price = 100, qty = 4)
        h.newOrder(participantId = 2, clOrdId = 200, securityId = 1, side = Side.BUY, price = 100, qty = 8)

        val trades = h.reports.filter { it.execType == "TRADE" }
        assertEquals(4, trades.size)

        // Taker reports carry a running remainder: 4 after the first fill, 0 after the second.
        val takerLeaves = trades.filter { it.participantId == 2L }.map { it.leavesQty }
        assertEquals(listOf(4L, 0L), takerLeaves)
        assertEquals(0, h.books[0].restingOrderCount())
    }

    @Test
    fun `an unfilled remainder rests and is acknowledged only once`() {
        val h = harness()
        openContinuous(h)
        h.newOrder(1, 100, 1, Side.SELL, 100, 3)
        h.newOrder(2, 200, 1, Side.BUY, 100, 10)

        assertEquals(1, h.reports.count { it.execType == "NEW" && it.participantId == 2L })
        assertEquals(7L, h.books[0].leavesQtyOf(h.books[0].indexOf(2L)))
    }

    @Test
    fun `orders are booked but never matched outside continuous trading`() {
        val h = harness()
        h.defineSecurity(h.books[0], 100L)
        h.sessionTransition(Phase.PRE_OPEN)

        h.newOrder(1, 100, 1, Side.SELL, 100, 5)
        h.newOrder(2, 200, 1, Side.BUY, 100, 5)

        assertTrue(h.reports.none { it.execType == "TRADE" })
        assertEquals(2, h.books[0].restingOrderCount())
    }

    @Test
    fun `a closed security rejects orders`() {
        val h = harness()
        h.defineSecurity(h.books[0], 100L)
        h.sessionTransition(Phase.CLOSED)
        h.newOrder(1, 100, 1, Side.BUY, 100, 5)

        val report = h.reports.single()
        assertEquals("REJECTED", report.execType)
        assertEquals(RejectReason.MARKET_CLOSED, report.rejectReason)
    }

    @Test
    fun `an unknown security is rejected without touching a book`() {
        val h = harness()
        openContinuous(h)
        h.newOrder(1, 100, securityId = 99, side = Side.BUY, price = 100, qty = 5)

        val report = h.reports.single()
        assertEquals("REJECTED", report.execType)
        assertEquals(RejectReason.UNKNOWN_SECURITY, report.rejectReason)
    }

    @Test
    fun `a stale expire date is rejected`() {
        val h = harness()
        openContinuous(h)
        h.newOrder(1, 100, 1, Side.BUY, 100, 5, expireDate = 20260828)

        assertEquals(RejectReason.ORDER_EXPIRED, h.reports.single().rejectReason)
    }

    @Test
    fun `the static collar rejects at acceptance`() {
        val h = harness()
        openContinuous(h, reference = 100L, static = 1000)
        h.newOrder(1, 100, 1, Side.BUY, price = 150, qty = 5)

        assertEquals(RejectReason.PRICE_OUT_OF_BOUNDS, h.reports.single().rejectReason)
        assertEquals(0, h.books[0].restingOrderCount())
    }

    @Test
    fun `a cancel is confirmed and frees the order`() {
        val h = harness()
        openContinuous(h)
        h.newOrder(1, 100, 1, Side.BUY, 99, 10)
        h.cancel(participantId = 1, origClOrdId = 100, clOrdId = 101, exchangeOrderId = 1, securityId = 1, side = Side.BUY)

        val cancel = h.reports.last()
        assertEquals("CANCELED", cancel.execType)
        assertEquals(101L, cancel.clOrdId)
        assertEquals(0, h.books[0].restingOrderCount())
    }

    @Test
    fun `a cancel from the wrong participant is rejected`() {
        val h = harness()
        openContinuous(h)
        h.newOrder(1, 100, 1, Side.BUY, 99, 10)
        h.cancel(participantId = 2, origClOrdId = 100, clOrdId = 101, exchangeOrderId = 1, securityId = 1, side = Side.BUY)

        assertEquals(RejectReason.UNAUTHORIZED_PARTICIPANT, h.reports.last().rejectReason)
        assertEquals(1, h.books[0].restingOrderCount())
    }

    @Test
    fun `self match prevention cancels the aggressor remainder`() {
        val h = harness()
        openContinuous(h)
        h.newOrder(participantId = 7, clOrdId = 100, securityId = 1, side = Side.SELL, price = 100, qty = 5)
        h.newOrder(participantId = 7, clOrdId = 200, securityId = 1, side = Side.BUY, price = 100, qty = 5)

        val last = h.reports.last()
        assertEquals("CANCELED", last.execType)
        assertEquals(RejectReason.SELF_MATCH_PREVENTED, last.rejectReason)
        assertTrue(h.reports.none { it.execType == "TRADE" })
        assertEquals(1, h.books[0].restingOrderCount())
    }

    @Test
    fun `a collar breach cancels the aggressor and closes the security`() {
        val h = harness()
        openContinuous(h, reference = 100L, dynamic = 500)
        h.newOrder(1, 100, 1, Side.SELL, price = 104, qty = 2)
        h.newOrder(1, 101, 1, Side.SELL, price = 120, qty = 2)
        h.newOrder(participantId = 2, clOrdId = 200, securityId = 1, side = Side.BUY, price = 120, qty = 4)

        // The fill inside the collar stands.
        assertEquals(1, h.reports.count { it.execType == "TRADE" && it.participantId == 2L })

        val last = h.reports.last()
        assertEquals("CANCELED", last.execType)
        assertEquals(RejectReason.VOLATILITY_HALT, last.rejectReason)
        assertEquals(Phase.CLOSED, h.books[0].phase)

        // The resting book survives the halt.
        assertEquals(1, h.books[0].restingOrderCount())
    }

    @Test
    fun `a halted security rejects further orders until reopened`() {
        val h = harness()
        openContinuous(h, reference = 100L, dynamic = 500)
        h.newOrder(1, 100, 1, Side.SELL, price = 120, qty = 2)
        h.newOrder(2, 200, 1, Side.BUY, price = 120, qty = 2)
        assertEquals(Phase.CLOSED, h.books[0].phase)

        h.newOrder(3, 300, 1, Side.BUY, price = 100, qty = 1)
        assertEquals(RejectReason.MARKET_CLOSED, h.reports.last().rejectReason)
    }

    @Test
    fun `the opening auction uncrosses on the transition to continuous`() {
        val h = harness()
        h.defineSecurity(h.books[0], 100L)
        h.sessionTransition(Phase.OPEN_AUCTION)
        h.newOrder(participantId = 1, clOrdId = 100, securityId = 1, side = Side.BUY, price = 102, qty = 10)
        h.newOrder(participantId = 2, clOrdId = 200, securityId = 1, side = Side.SELL, price = 98, qty = 10)
        assertTrue(h.reports.none { it.execType == "TRADE" })

        h.sessionTransition(Phase.CONTINUOUS)

        val trades = h.reports.filter { it.execType == "TRADE" }
        assertEquals(2, trades.size)
        assertTrue(trades.all { it.price == 100L })
        assertEquals(100L, h.books[0].staticReference)
        assertEquals(Phase.CONTINUOUS, h.books[0].phase)
    }

    @Test
    fun `the purge expires stale orders and reports them`() {
        val h = harness()
        h.defineSecurity(h.books[0], 100L)
        h.sessionTransition(Phase.PRE_OPEN, tradingDate = 20260829)
        h.newOrder(1, 100, 1, Side.BUY, 99, 5, expireDate = 20260830)
        h.newOrder(1, 101, 1, Side.BUY, 98, 5, expireDate = 0)

        // Roll the date forward so the first order is now stale.
        h.purge(tradingDate = 20260901)

        val expired = h.reports.filter { it.execType == "EXPIRED" }
        assertEquals(1, expired.size)
        assertEquals(1L, expired.single().exchangeOrderId)
        assertEquals(1, h.books[0].restingOrderCount())
    }

    @Test
    fun `a security definition re-seeds both references`() {
        val h = harness()
        openContinuous(h, reference = 100L, static = 1000)
        h.newOrder(1, 100, 1, Side.BUY, price = 150, qty = 5)
        assertEquals(RejectReason.PRICE_OUT_OF_BOUNDS, h.reports.last().rejectReason)

        // The operator's lever for reopening around a new price level (Design.md §4.6).
        h.defineSecurity(h.books[0], referencePrice = 150L, staticCollarBps = 1000)
        h.newOrder(1, 101, 1, Side.BUY, price = 150, qty = 5)

        assertEquals("NEW", h.reports.last().execType)
        assertEquals(150L, h.books[0].staticReference)
    }

    @Test
    fun `a definition whose geometry disagrees is rejected wholesale`() {
        val h = harness()
        h.defineSecurity(h.books[0], referencePrice = 100L, staticCollarBps = 500)
        h.defineSecurity(h.books[0], referencePrice = 999L, staticCollarBps = 1, levelCount = 7)

        assertEquals(100L, h.books[0].staticReference)
        assertEquals(500, h.books[0].staticCollarBps)
        assertEquals(1L, h.service.rejectedDefinitions)
    }

    @Test
    fun `each security in a shard keeps its own phase`() {
        val h = Harness(arrayOf(serviceBook(1), serviceBook(2)))
        openContinuous(h, reference = 100L, dynamic = 500)
        h.newOrder(1, 100, securityId = 1, side = Side.SELL, price = 120, qty = 2)
        h.newOrder(2, 200, securityId = 1, side = Side.BUY, price = 120, qty = 2)

        assertEquals(Phase.CLOSED, h.books[0].phase)
        assertEquals(Phase.CONTINUOUS, h.books[1].phase)
    }

    @Test
    fun `an unknown template is ignored rather than thrown on`() {
        val h = harness()
        openContinuous(h)
        // Determinism makes a throw fatal to every node at once, so bad input must not throw.
        h.service.onSessionMessage(
            h.session, 0L,
            org.agrona.concurrent.UnsafeBuffer(ByteArray(64)), 0, 64,
            io.aeron.logbuffer.Header(0, 0),
        )
        h.newOrder(1, 100, 1, Side.BUY, 99, 10)
        assertEquals("NEW", h.reports.last().execType)
    }
}
