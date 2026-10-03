package com.engine.core

import com.engine.sbe.RequestStatus
import io.aeron.cluster.service.Cluster
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Design.md §5, "Order mass status". Expected values are counted from that clause:
 *
 * - one `ExecutionReport` with ExecType `ORDER_STATUS` per open order of the participant, stating
 *   `origQty`, `cumQty` and `leavesQty` at the request's log position, on the session that asked;
 * - `reportSeq` 0 on each, and no sequence number consumed: a status is state, not an event;
 * - then `OrderMassStatusComplete` with `orderCount`, the participant's `nextSeq` at that position,
 *   the security asked about (-1 for every book), and `COMPLETE` -- or `UNKNOWN_SECURITY` with
 *   nothing sent for a security the shard does not host.
 *
 * The participant's sequence is counted from §5's report sequence: one NEW per order that rests,
 * one TRADE per fill on each side.
 */
class OrderMassStatusTest {

    private fun trading(): Harness {
        val harness = Harness(arrayOf(serviceBook(FIRST), serviceBook(SECOND)))
        for (book in harness.books) harness.defineSecurity(book, referencePrice = 100, staticCollarBps = 5_000)
        harness.sessionTransition(Phase.PRE_OPEN)
        harness.sessionTransition(Phase.OPEN_AUCTION)
        harness.sessionTransition(Phase.CONTINUOUS)
        return harness
    }

    /**
     * Maker: sells 10 @ 101 (NEW, seq 1), filled 4 by the taker (TRADE, seq 2), and buys 1 @ 95 on
     * the second book (NEW, seq 3). So it has two open orders and its next sequence is 4.
     */
    private fun makerWithTwoOpenOrders(harness: Harness) {
        harness.newOrder(MAKER, 100L, FIRST, Side.SELL, price = 101, qty = 10)
        harness.newOrder(TAKER, 200L, FIRST, Side.BUY, price = 101, qty = 4)
        harness.newOrder(MAKER, 101L, SECOND, Side.BUY, price = 95, qty = 1)
        harness.newOrder(TAKER, 201L, FIRST, Side.BUY, price = 90, qty = 3) // the taker's own open order
    }

    private fun Harness.statusReports(session: FakeSession = this.session) =
        session.reports.filter { it.execType == "ORDER_STATUS" }

    @Test
    fun `every open order of the participant is stated, with its quantities, then the completion`() {
        val harness = trading()
        makerWithTwoOpenOrders(harness)

        harness.orderMassStatus(MAKER, requestId = 9L)

        val statuses = harness.statusReports().sortedBy { it.clOrdId }
        assertEquals(listOf(100L, 101L), statuses.map { it.clOrdId })
        val sell = statuses[0]
        assertEquals(MAKER, sell.participantId)
        assertEquals(FIRST, sell.securityId)
        assertEquals(101L, sell.price)
        assertEquals(10L, sell.origQty)
        assertEquals(4L, sell.cumQty)
        assertEquals(6L, sell.leavesQty)
        assertEquals(0L, sell.lastQty, "a status is not a fill")
        val buy = statuses[1]
        assertEquals(SECOND, buy.securityId)
        assertEquals(1L, buy.origQty)
        assertEquals(0L, buy.cumQty)
        assertEquals(1L, buy.leavesQty)

        assertEquals(
            MassStatusCompletion(MAKER, 9L, nextSeq = 4L, orderCount = 2, securityId = -1, status = RequestStatus.COMPLETE),
            harness.session.statusCompletions.single(),
        )
    }

    @Test
    fun `a status is outside the report sequence and consumes no number`() {
        val harness = trading()
        makerWithTwoOpenOrders(harness)

        harness.orderMassStatus(MAKER)

        assertTrue(harness.statusReports().all { it.reportSeq == 0L })
        assertEquals(3L, harness.service.lastReportSeq(MAKER))
        harness.newOrder(MAKER, 102L, FIRST, Side.BUY, price = 94, qty = 1)
        assertEquals(4L, harness.session.reports.last().reportSeq, "the next real report is the completion's nextSeq")
    }

    @Test
    fun `only the participant's own orders are stated`() {
        val harness = trading()
        makerWithTwoOpenOrders(harness)

        harness.orderMassStatus(TAKER)

        assertEquals(listOf(201L), harness.statusReports().map { it.clOrdId })
        assertEquals(1, harness.session.statusCompletions.single().orderCount)
    }

    @Test
    fun `one security narrows the answer to that book`() {
        val harness = trading()
        makerWithTwoOpenOrders(harness)

        harness.orderMassStatus(MAKER, securityId = SECOND)

        assertEquals(listOf(101L), harness.statusReports().map { it.clOrdId })
        val done = harness.session.statusCompletions.single()
        assertEquals(1, done.orderCount)
        assertEquals(SECOND, done.securityId)
        assertEquals(RequestStatus.COMPLETE, done.status)
    }

    @Test
    fun `a security the shard does not host is answered as such, with nothing stated`() {
        val harness = trading()
        makerWithTwoOpenOrders(harness)

        harness.orderMassStatus(MAKER, securityId = 99)

        assertTrue(harness.statusReports().isEmpty())
        assertEquals(
            MassStatusCompletion(MAKER, 1L, nextSeq = 4L, orderCount = 0, securityId = 99, status = RequestStatus.UNKNOWN_SECURITY),
            harness.session.statusCompletions.single(),
        )
    }

    @Test
    fun `a participant with nothing open gets an empty, complete answer`() {
        val harness = trading()
        harness.orderMassStatus(MAKER)
        assertEquals(
            MassStatusCompletion(MAKER, 1L, nextSeq = 1L, orderCount = 0, securityId = -1, status = RequestStatus.COMPLETE),
            harness.session.statusCompletions.single(),
        )
    }

    @Test
    fun `the answer goes to the session that asked`() {
        val harness = trading()
        makerWithTwoOpenOrders(harness)
        val other = harness.openSession(2L)

        harness.on(other).orderMassStatus(MAKER)

        assertEquals(2, harness.statusReports(other).size)
        assertEquals(1, other.statusCompletions.size)
        assertTrue(harness.statusReports().isEmpty())
    }

    @Test
    fun `a follower answers nothing and changes nothing`() {
        val harness = trading()
        makerWithTwoOpenOrders(harness)
        harness.service.onRoleChange(Cluster.Role.FOLLOWER)
        harness.session.mocked = true

        harness.orderMassStatus(MAKER)

        assertEquals(3L, harness.service.lastReportSeq(MAKER))
        assertEquals(3, harness.books.sumOf { it.restingOrderCount() }, "the books are untouched")
    }

    private companion object {
        const val FIRST = 1
        const val SECOND = 2
        const val MAKER = 7L
        const val TAKER = 9L
    }
}
