package nl.lamia.most.exchange.client

import nl.lamia.most.exchange.sbe.ClientExecutionReportDecoder
import nl.lamia.most.exchange.sbe.ExecType
import nl.lamia.most.exchange.sbe.NewOrderSingleDecoder
import nl.lamia.most.exchange.sbe.OrderMassStatusRequestDecoder
import nl.lamia.most.exchange.sbe.ReportResendRequestDecoder
import nl.lamia.most.exchange.sbe.RequestStatus
import nl.lamia.most.exchange.sbe.Side
import org.agrona.concurrent.NanoClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [OrderEntrySession] against [ExchangeModel], with every expected value taken from
 * docs/Adapters.md §1–§4 and Design.md §5 — the clause is cited on each test.
 */
class OrderEntrySessionTest {

    private val p = 7L
    private val exchange = ExchangeModel()
    private val a = ModelGateway(exchange, "a")
    private val b = ModelGateway(exchange, "b")
    private var now = 1_000_000_000L
    private val clock = NanoClock { now }
    private val seen = Recorder()

    private fun session(vararg links: GatewayLink = arrayOf(a, b), store: SessionStore = SessionStore.InMemory()) =
        OrderEntrySession(links.toList(), longArrayOf(p), seen, OrderEntrySession.Config(requestIdBase = 1_000L), store, clock)

    private fun OrderEntrySession.pump(times: Int = 10) = repeat(times) { doWork() }

    private fun OrderEntrySession.opened(): OrderEntrySession = apply {
        pump()
        check(isReady) { "the opening mass status did not complete" }
    }

    private fun OrderEntrySession.buy(clOrdId: Long) = newOrder(p, clOrdId, 1, Side.BUY, 100_00000000L, 10_00000000L)

    // ------------------------------------------------------------------------- connecting

    @Test
    fun `nothing is sent until a gateway has both legs, and the first thing sent is a mass status`() {
        // Adapters.md §1: both legs before the first message; on a logon, a mass status before trading.
        a.mode = ModelGateway.Mode.DEAD
        b.mode = ModelGateway.Mode.DEAD
        val session = session()
        session.pump()
        assertEquals(SendResult.NOT_READY, session.buy(1))
        assertTrue(a.received.isEmpty() && b.received.isEmpty())

        a.mode = ModelGateway.Mode.ACTIVE
        session.pump()
        assertEquals(listOf(OrderMassStatusRequestDecoder.TEMPLATE_ID), a.received)
        assertTrue(session.isReady)
        assertEquals(1, seen.ready)
    }

    @Test
    fun `the opening mass status states what is open and the sequence resumes from its nextSeq`() {
        // Design.md §5 "Order mass status": a client resumes its report sequence from nextSeq.
        // An earlier run left five reports (seq 1..5) and one open order the new session never saw.
        repeat(4) { exchange.sequence(a, order(clOrdId = 100L + it)) }
        exchange.fill(a, p, 100L, 10_00000000L) // seq 5; 100 finishes, 101..103 stay open
        a.drain()

        val session = session().opened()
        assertEquals(listOf(101L, 102L, 103L), seen.reports.filter { it.execType == ExecType.ORDER_STATUS }.map { it.clOrdId })

        assertEquals(SendResult.SENT, session.buy(1))
        session.pump()
        assertEquals(6L, seen.reports.last().reportSeq)
        assertEquals(0L, session.fencesSent, "seq 6 after a status at nextSeq 6 is not a gap")
    }

    // ------------------------------------------------------------------------- back-pressure

    @Test
    fun `back-pressure is returned to the caller and nothing is lost or sent twice`() {
        // Adapters.md §2: BACK_PRESSURED must be retried, not dropped.
        val session = session().opened()
        a.backPressure = 1
        assertEquals(SendResult.BACK_PRESSURED, session.buy(1))
        assertEquals(SendResult.SENT, session.buy(1))
        session.pump()
        assertEquals(1, exchange.sequenced.count { it == NewOrderSingleDecoder.TEMPLATE_ID })
        assertEquals(listOf(1L), seen.reports.map { it.clOrdId })
    }

    // ------------------------------------------------------------------------- gaps and replays

    @Test
    fun `a gap is fenced from the first missing report, and a replayed report is delivered once`() {
        // Adapters.md §4: on a gap, resend from the first missing reportSeq; count each report once.
        val session = session().opened()
        session.buy(1); session.pump()
        a.dropReports = true
        session.buy(2); session.pump() // seq 2, lost
        a.dropReports = false
        session.buy(3); session.pump() // seq 3 arrives: the gap opens, and the fence asks from 2

        val resend = a.lastRequest<ReportResendRequestDecoder>()
        assertEquals(2L, resend.fromSeq())
        assertEquals(listOf(1L, 3L, 2L), seen.reports.map { it.reportSeq })
        assertEquals(1L, session.duplicateReports, "the replay re-sent seq 3, which arrived once already")
        assertTrue(seen.settled.isEmpty(), "every order was answered")
    }

    // ------------------------------------------------------------------------- failover

    @Test
    fun `after a failover every unanswered order is settled -- sequenced ones by their replayed reports, the rest never sequenced`() {
        // Adapters.md §3-§4 and Design.md §5: GATEWAY_UNAVAILABLE means try another gateway; after a
        // switch, fence; after a COMPLETE fence, an unanswered order sent before it was never sequenced.
        val session = session().opened()
        a.dropReports = true
        session.buy(1); session.buy(2) // sequenced, reports lost
        a.dropReports = false
        a.blackhole = true
        session.buy(3); session.buy(4) // accepted, never reached the log
        a.blackhole = false
        a.mode = ModelGateway.Mode.STANDBY
        session.buy(5) // refused by the standby, GATEWAY_UNAVAILABLE
        session.pump()

        assertEquals(b.endpoint, session.activeGateway)
        assertEquals(ReportResendRequestDecoder.TEMPLATE_ID, b.received.first(), "the fence goes before anything else")
        assertEquals(listOf(1L, 2L), seen.reports.map { it.clOrdId }, "the replay recovered the sequenced orders")
        assertEquals(
            mapOf(3L to OrderFate.NEVER_SEQUENCED, 4L to OrderFate.NEVER_SEQUENCED, 5L to OrderFate.NEVER_SEQUENCED),
            seen.settled,
        )
        assertTrue(seen.reports.none { it.execType == ExecType.REJECTED }, "a GATEWAY_UNAVAILABLE is never the market's answer")
    }

    @Test
    fun `a gateway that dies sends nothing -- the session moves on the disconnect and fences before its next order`() {
        // Adapters.md §3: a co-located gateway dies with its node and sends no reject; a disconnect
        // means try another gateway, and orders in the blind window are settled by the fence.
        val session = session().opened()
        a.blackhole = true
        session.buy(1)
        a.mode = ModelGateway.Mode.DEAD

        assertEquals(SendResult.BACK_PRESSURED, session.buy(2), "a fence is owed on the new gateway first")
        session.pump()
        assertEquals(listOf(ReportResendRequestDecoder.TEMPLATE_ID), b.received)
        assertEquals(mapOf(1L to OrderFate.NEVER_SEQUENCED), seen.settled)
        assertEquals(SendResult.SENT, session.buy(2))
    }

    @Test
    fun `a standby that refuses the fence moves the session on, and the fence is sent again there`() {
        // Adapters.md §4: on GATEWAY_UNAVAILABLE nothing was sent; switch, and fence again.
        val c = ModelGateway(exchange, "c")
        val session = session(a, b, c).opened()
        b.mode = ModelGateway.Mode.STANDBY
        a.blackhole = true
        session.buy(1)
        a.blackhole = false
        a.mode = ModelGateway.Mode.STANDBY
        session.buy(2) // refused by a; the session moves to b, which refuses the fence too
        session.pump()
        // A refused request waits before it is sent again, so two standbys are not flooded in turn.
        now += OrderEntrySession.Config().retryIntervalNs
        session.pump()

        assertEquals(c.endpoint, session.activeGateway)
        assertEquals(ReportResendRequestDecoder.TEMPLATE_ID, c.received.first())
        assertEquals(mapOf(1L to OrderFate.NEVER_SEQUENCED, 2L to OrderFate.NEVER_SEQUENCED), seen.settled)
    }

    // ------------------------------------------------------------------------- truncation

    @Test
    fun `a truncated resend is closed by a mass status -- open orders are stated, the rest are ambiguous and never rejected`() {
        // Design.md §5 "Order mass status", "What it does not recover"; Adapters.md §4 on TRUNCATED.
        val small = ExchangeModel(retention = 1)
        val sa = ModelGateway(small, "a")
        val sb = ModelGateway(small, "b")
        val session = OrderEntrySession(listOf(sa, sb), longArrayOf(p), seen, OrderEntrySession.Config(requestIdBase = 1_000L), clock = clock).opened()

        sa.dropReports = true
        session.buy(1); session.buy(2); session.buy(3) // NEW seq 1..3, all lost
        small.fill(sa, p, 1L, 10_00000000L) // order 1 finishes: seq 4, lost
        small.fill(sa, p, 3L, 5_00000000L) // order 3 half fills: seq 5, lost; the ring now holds only seq 5
        sa.dropReports = false
        sa.blackhole = true
        session.buy(4) // never sequenced
        sa.blackhole = false
        sa.mode = ModelGateway.Mode.STANDBY
        session.buy(5) // refused: the switch to b, and a fence from seq 1 that the ring cannot reach
        session.pump()

        assertEquals(Report(3L, ExecType.TRADE, 5L), seen.reports.first(), "what the ring still held is replayed")
        assertEquals(
            listOf(2L, 3L),
            seen.reports.filter { it.execType == ExecType.ORDER_STATUS }.map { it.clOrdId },
            "the orders still open are stated",
        )
        assertEquals(
            mapOf(1L to OrderFate.AMBIGUOUS, 4L to OrderFate.AMBIGUOUS, 5L to OrderFate.NEVER_SEQUENCED),
            seen.settled,
            "1 finished in the lost window and 4 never arrived: after a truncation the two cannot be told apart",
        )

        val fences = session.fencesSent
        session.buy(6); session.pump()
        assertEquals(6L, seen.reports.last().reportSeq)
        assertEquals(fences, session.fencesSent, "the sequence resumed from the status's nextSeq: no gap")
    }

    // ------------------------------------------------------------------------- unanswered

    @Test
    fun `a request unanswered for longer than a failover takes is fenced`() {
        // Adapters.md §6: a participant's last report, if dropped, is noticed only at its next
        // report; an adapter SHOULD fence when an order has gone unanswered longer than a failover.
        val session = session(a).opened()
        a.blackhole = true
        session.buy(1)
        a.blackhole = false
        session.pump()
        assertEquals(0L, session.fencesSent)

        now += OrderEntrySession.Config().unansweredFenceNs + 1
        session.pump()
        assertEquals(1L, session.fencesSent)
        assertEquals(mapOf(1L to OrderFate.NEVER_SEQUENCED), seen.settled)
    }

    @Test
    fun `a fence settles only what was sent before it`() {
        // Design.md §5: after the completion, every still-unanswered order sent *before the request*
        // was never sequenced. One sent after it is not covered, and stays in flight.
        val session = session(a).opened()
        a.blackhole = true
        session.buy(1)
        a.blackhole = false
        now += OrderEntrySession.Config().unansweredFenceNs + 1
        session.doWork() // 1 is overdue: a fence is owed
        session.doWork() // the fence goes out; its completion is not yet read
        a.blackhole = true
        session.buy(2) // after the fence, and never sequenced either
        a.blackhole = false
        session.pump()

        assertEquals(mapOf(1L to OrderFate.NEVER_SEQUENCED), seen.settled)
    }

    // ------------------------------------------------------------------------- restart

    @Test
    fun `a restart with a stored sequence resends from it before its mass status`() {
        // Adapters.md §5: persisting the first missing reportSeq turns a restart into a resend from
        // where it stopped.
        repeat(4) { exchange.sequence(a, order(clOrdId = 200L + it)) } // seq 1..4
        a.drain()
        val store = SessionStore.InMemory().apply { save(p, 3L) } // 1 and 2 were received before

        session(store = store).opened()
        assertEquals(
            listOf(ReportResendRequestDecoder.TEMPLATE_ID, OrderMassStatusRequestDecoder.TEMPLATE_ID),
            a.received,
        )
        assertEquals(3L, a.lastRequest<ReportResendRequestDecoder>().fromSeq())
        assertEquals(listOf(3L, 4L), seen.reports.filter { it.reportSeq > 0L }.map { it.reportSeq })
        assertEquals(5L, store.firstMissing(p))
    }

    @Test
    fun `reports for participants the session does not act for are not delivered`() {
        val session = session().opened()
        exchange.sequence(a, order(participantId = 99L, clOrdId = 1L))
        session.pump()
        assertTrue(seen.reports.isEmpty())
    }

    // ------------------------------------------------------------------------- support

    private fun order(participantId: Long = p, clOrdId: Long): org.agrona.DirectBuffer {
        val buffer = org.agrona.concurrent.UnsafeBuffer(ByteArray(128))
        nl.lamia.most.exchange.sbe.NewOrderSingleEncoder()
            .wrapAndApplyHeader(buffer, 0, nl.lamia.most.exchange.sbe.MessageHeaderEncoder())
            .participantId(participantId).clOrdId(clOrdId).price(100_00000000L).qty(10_00000000L)
            .smpId(0L).securityId(1).expireDate(0).side(Side.SELL)
            .smpStrategy(nl.lamia.most.exchange.sbe.SmpStrategy.CANCEL_AGGRESSOR)
        return buffer
    }

    private fun ExchangeModel.sequence(via: ModelGateway, buffer: org.agrona.DirectBuffer) = sequence(via, buffer, 0)

    private fun ModelGateway.drain() {
        do {
            val drained = poll({ _, _, _, _ -> }, 100)
        } while (drained > 0)
    }

    private inline fun <reified T> ModelGateway.lastRequest(): T = requests.filterIsInstance<T>().last()

    data class Report(val clOrdId: Long, val execType: ExecType, val reportSeq: Long)

    class Recorder : OrderEntryListener {
        val reports = mutableListOf<Report>()
        val settled = LinkedHashMap<Long, OrderFate>()
        val statuses = mutableListOf<Pair<RequestStatus, Long>>()
        var ready = 0

        override fun onReport(report: ClientExecutionReportDecoder) {
            reports += Report(report.clOrdId(), report.execType(), report.reportSeq())
        }

        override fun onSettled(participantId: Long, clOrdId: Long, fate: OrderFate) {
            check(settled.put(clOrdId, fate) == null) { "order $clOrdId settled twice" }
        }

        override fun onMassStatusComplete(participantId: Long, status: RequestStatus, orderCount: Int, nextSeq: Long) {
            statuses += status to nextSeq
        }

        override fun onReady() {
            ready++
        }
    }
}
