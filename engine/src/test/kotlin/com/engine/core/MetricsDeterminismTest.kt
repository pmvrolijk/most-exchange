package com.engine.core

import org.agrona.concurrent.SystemNanoClock
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The instrumentation reads `System.nanoTime()` inside a deterministic state machine, which
 * Design.md §1 otherwise forbids. [EngineMetrics] argues that is safe because the clock never
 * influences state. This is the test of that argument rather than a restatement of it.
 *
 * The invariant, stated as something falsifiable:
 *
 * > **Enabling metrics on one node and not another must be incapable of changing the log, the
 * > books, or a snapshot.**
 *
 * It matters because it is exactly what a real deployment will do. Metrics are node-local
 * configuration and are deliberately excluded from `EngineConfig.fingerprint()`, so an operator
 * may turn them on for one node of a cluster to diagnose it — which is a divergence waiting to
 * happen if a single metric ever feeds a decision. Two engines are driven through an identical
 * command sequence, one instrumented at the deepest level and one not at all, and everything a
 * follower could disagree about is compared.
 */
class MetricsDeterminismTest {

    private fun drive(harness: Harness) {
        harness.defineSecurity(harness.books[0], referencePrice = 100, staticCollarBps = 5_000)
        harness.sessionTransition(Phase.PRE_OPEN)
        harness.sessionTransition(Phase.OPEN_AUCTION)

        // An auction with a self-match, so the SMP fixed point runs and cancels something.
        harness.newOrder(1L, 10L, SECURITY, Side.BUY, price = 100, qty = 5, smpId = 77L)
        harness.newOrder(2L, 11L, SECURITY, Side.SELL, price = 100, qty = 3, smpId = 77L)
        harness.newOrder(3L, 12L, SECURITY, Side.SELL, price = 99, qty = 4)
        harness.sessionTransition(Phase.CONTINUOUS)

        // Continuous trading: partial fills, a rest, a cancel, and a rejection.
        harness.newOrder(4L, 20L, SECURITY, Side.SELL, price = 101, qty = 10)
        harness.newOrder(5L, 21L, SECURITY, Side.BUY, price = 101, qty = 4)
        harness.newOrder(6L, 22L, SECURITY, Side.BUY, price = 98, qty = 7)
        harness.newOrder(7L, 23L, SECURITY, Side.BUY, price = -1, qty = 1)
        harness.cancel(6L, 22L, 30L, exchangeOrderId = 6L, securityId = SECURITY, side = Side.BUY)

        // Expiry, which walks the ladders.
        harness.newOrder(8L, 24L, SECURITY, Side.BUY, price = 97, qty = 2, expireDate = 20260101)
        harness.purge(tradingDate = 20260829)
    }

    private fun bookFingerprint(book: OrderBook): List<Long> {
        val out = mutableListOf<Long>()
        out += book.phase.toLong()
        out += book.tradingDate.toLong()
        out += book.staticReference
        out += book.dynamicReference
        out += book.restingOrderCount().toLong()
        out += book.bestBid().toLong()
        out += book.bestAsk().toLong()
        // Every resting order, in ladder order, with every field a snapshot would carry.
        book.forEachRestingOrder { node ->
            out += book.exchangeOrderIdOf(node)
            out += book.participantIdOf(node)
            out += book.smpIdOf(node)
            out += book.clOrdIdOf(node)
            out += book.orderPrice(node)
            out += book.leavesQtyOf(node)
            out += book.sideOfOrder(node).toLong()
            out += book.expireDateOfOrder(node).toLong()
        }
        return out
    }

    @Test
    fun `an instrumented node produces identical state to an uninstrumented one`() {
        val plain = Harness(arrayOf(serviceBook(SECURITY)))
        val metrics = EngineMetrics(SystemNanoClock.INSTANCE, stages = true)
        val instrumented = Harness(arrayOf(serviceBook(SECURITY)), metrics = metrics)

        drive(plain)
        drive(instrumented)

        // The egress a client would see, report for report and field for field.
        assertContentEquals(
            plain.reports,
            instrumented.reports,
            "instrumentation changed the execution reports",
        )
        // The state a snapshot would carry, and the sequences a follower continues from.
        assertContentEquals(
            bookFingerprint(plain.books[0]),
            bookFingerprint(instrumented.books[0]),
            "instrumentation changed the book",
        )
        assertEquals(
            plain.service.nextExchangeOrderId,
            instrumented.service.nextExchangeOrderId,
            "instrumentation changed the order id sequence",
        )
        assertEquals(
            plain.service.nextBookEventSeqNum,
            instrumented.service.nextBookEventSeqNum,
            "instrumentation changed the book event sequence",
        )

        // And the run has to have been worth comparing.
        assertTrue(plain.reports.size > 5, "sanity: the sequence produced reports to compare")
        assertTrue(metrics.newOrder.count > 0, "sanity: the instrumented node actually measured")
        assertTrue(metrics.admit.count > 0, "sanity: stage timing was on")
    }

    private companion object {
        const val SECURITY = 1
    }
}
