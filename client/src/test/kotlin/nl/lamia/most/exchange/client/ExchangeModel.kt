package nl.lamia.most.exchange.client

import io.aeron.Publication
import io.aeron.logbuffer.FragmentHandler
import io.aeron.logbuffer.FrameDescriptor
import io.aeron.logbuffer.Header
import io.aeron.protocol.DataHeaderFlyweight
import nl.lamia.most.exchange.sbe.ClientExecutionReportEncoder
import nl.lamia.most.exchange.sbe.Enrichment
import nl.lamia.most.exchange.sbe.ExecType
import nl.lamia.most.exchange.sbe.MessageHeaderDecoder
import nl.lamia.most.exchange.sbe.MessageHeaderEncoder
import nl.lamia.most.exchange.sbe.NewOrderSingleDecoder
import nl.lamia.most.exchange.sbe.OrderCancelRequestDecoder
import nl.lamia.most.exchange.sbe.OrderMassStatusCompleteEncoder
import nl.lamia.most.exchange.sbe.OrderMassStatusRequestDecoder
import nl.lamia.most.exchange.sbe.RejectReason
import nl.lamia.most.exchange.sbe.ReportResendCompleteEncoder
import nl.lamia.most.exchange.sbe.ReportResendRequestDecoder
import nl.lamia.most.exchange.sbe.RequestStatus
import nl.lamia.most.exchange.sbe.Side
import org.agrona.DirectBuffer
import org.agrona.concurrent.UnsafeBuffer

/**
 * A model of one shard as a client sees it, written from Design.md §5 ("Report sequence and resend",
 * "Order mass status") and the gateway's refusals (Design.md §1), never from [OrderEntrySession].
 *
 * What it models, because the session's correctness rests on each:
 * - every engine report numbered per participant from 1 and retained in a ring of [retention],
 *   **whether or not it is delivered** — that is what a resend recovers after a failover;
 * - a resend replays from `fromSeq` with the original numbers, then completes, `TRUNCATED` when the
 *   ring no longer reaches `fromSeq`;
 * - a mass status states each open order with `reportSeq` 0, then completes with `nextSeq`;
 * - a gateway's own refusals carry `reportSeq` 0 and never reach the log.
 *
 * No matching: an order rests, and [fill] trades it on demand.
 */
class ExchangeModel(private val retention: Int = 1_000) {

    private class Open(
        val participantId: Long, val clOrdId: Long, val exchangeOrderId: Long, val securityId: Int,
        val side: Side, val price: Long, val origQty: Long, var cumQty: Long,
    )

    private class Retained(val participantId: Long, val seq: Long, val bytes: ByteArray)

    private val nextSeq = HashMap<Long, Long>()
    private val ring = ArrayDeque<Retained>()
    private val open = LinkedHashMap<Long, Open>() // by exchangeOrderId
    private var nextOrderId = 1L
    private val scratch = UnsafeBuffer(ByteArray(256))
    private val header = MessageHeaderEncoder()
    private val reportEncoder = ClientExecutionReportEncoder()

    /** Messages that reached the log, in sequence order, by template id. */
    val sequenced = mutableListOf<Int>()

    fun nextSeqOf(participantId: Long): Long = nextSeq[participantId] ?: 1L

    fun openOrders(participantId: Long): List<Long> =
        open.values.filter { it.participantId == participantId }.map { it.clOrdId }

    /** Sequences one message arriving through [via], and returns what the engine sends back on it. */
    fun sequence(via: ModelGateway, buffer: DirectBuffer, offset: Int) {
        val h = MessageHeaderDecoder().wrap(buffer, offset)
        val body = offset + MessageHeaderDecoder.ENCODED_LENGTH
        sequenced += h.templateId()
        when (h.templateId()) {
            NewOrderSingleDecoder.TEMPLATE_ID -> {
                val d = NewOrderSingleDecoder().wrap(buffer, body, h.blockLength(), h.version())
                val order = Open(
                    d.participantId(), d.clOrdId(), nextOrderId++, d.securityId(), d.side(), d.price(),
                    d.qty(), 0L,
                )
                open[order.exchangeOrderId] = order
                engineReport(via, order, order.clOrdId, ExecType.NEW, lastQty = 0L)
            }
            OrderCancelRequestDecoder.TEMPLATE_ID -> {
                val d = OrderCancelRequestDecoder().wrap(buffer, body, h.blockLength(), h.version())
                val order = open[d.exchangeOrderId()]
                if (order == null || order.participantId != d.participantId() || order.clOrdId != d.origClOrdId()) {
                    val ghost = Open(d.participantId(), d.clOrdId(), d.exchangeOrderId(), d.securityId(), d.side(), 0L, 0L, 0L)
                    engineReport(via, ghost, d.clOrdId(), ExecType.REJECTED, 0L, RejectReason.UNKNOWN_ORDER)
                } else {
                    open.remove(order.exchangeOrderId)
                    // The cancel's report carries the cancel's own clOrdId (MatchingEngineService.onCancel).
                    engineReport(via, order, d.clOrdId(), ExecType.CANCELED, 0L, leavesQty = 0L)
                }
            }
            ReportResendRequestDecoder.TEMPLATE_ID -> {
                val d = ReportResendRequestDecoder().wrap(buffer, body, h.blockLength(), h.version())
                val p = d.participantId()
                val retained = ring.filter { it.participantId == p }
                val oldest = retained.firstOrNull()?.seq ?: nextSeqOf(p)
                var replayed = 0
                for (r in retained) if (r.seq >= d.fromSeq()) {
                    via.deliver(r.bytes)
                    replayed++
                }
                val status = if (d.fromSeq() < oldest) RequestStatus.TRUNCATED else RequestStatus.COMPLETE
                via.deliverResendComplete(p, d.requestId(), d.fromSeq(), nextSeqOf(p), oldest, replayed, status)
            }
            OrderMassStatusRequestDecoder.TEMPLATE_ID -> {
                val d = OrderMassStatusRequestDecoder().wrap(buffer, body, h.blockLength(), h.version())
                val mine = open.values.filter {
                    it.participantId == d.participantId() &&
                        (d.securityId() == ParticipantRequests.ALL_SECURITIES || it.securityId == d.securityId())
                }
                // State, not an event: reportSeq 0, not numbered, not retained (Design.md §5).
                for (order in mine) via.deliver(encode(order, order.clOrdId, ExecType.ORDER_STATUS, 0L, seq = 0L))
                via.deliverStatusComplete(d.participantId(), d.requestId(), nextSeqOf(d.participantId()), mine.size, d.securityId(), RequestStatus.COMPLETE)
            }
            else -> error("the model does not sequence template ${h.templateId()}")
        }
    }

    /** The maker side of a trade: [qty] of [clOrdId] fills, reported through [via]. */
    fun fill(via: ModelGateway, participantId: Long, clOrdId: Long, qty: Long) {
        val order = open.values.first { it.participantId == participantId && it.clOrdId == clOrdId }
        order.cumQty += qty
        if (order.cumQty == order.origQty) open.remove(order.exchangeOrderId)
        engineReport(via, order, clOrdId, ExecType.TRADE, lastQty = qty)
    }

    /** Numbered and retained on every node before it is routed; delivered only if [via] delivers. */
    private fun engineReport(
        via: ModelGateway, order: Open, clOrdId: Long, execType: ExecType, lastQty: Long,
        reason: RejectReason = RejectReason.NONE, leavesQty: Long = order.origQty - order.cumQty,
    ) {
        val p = order.participantId
        val seq = nextSeqOf(p)
        nextSeq[p] = seq + 1
        val bytes = encode(order, clOrdId, execType, lastQty, seq, reason, leavesQty)
        ring.addLast(Retained(p, seq, bytes))
        while (ring.size > retention) ring.removeFirst()
        if (!via.dropReports) via.deliver(bytes)
    }

    private fun encode(
        order: Open, clOrdId: Long, execType: ExecType, lastQty: Long, seq: Long,
        reason: RejectReason = RejectReason.NONE, leavesQty: Long = order.origQty - order.cumQty,
    ): ByteArray {
        reportEncoder.wrapAndApplyHeader(scratch, 0, header)
            .participantId(order.participantId)
            .clOrdId(clOrdId)
            .exchangeOrderId(order.exchangeOrderId)
            .price(order.price)
            .lastQty(lastQty)
            .leavesQty(leavesQty)
            .cumQty(order.cumQty)
            .origQty(order.origQty)
            .securityId(order.securityId)
            .rejectReason(reason)
            .execType(execType)
            .side(order.side)
            .enrichment(Enrichment.KNOWN)
            .reportSeq(seq)
        return bytes(MessageHeaderEncoder.ENCODED_LENGTH + ClientExecutionReportEncoder.BLOCK_LENGTH)
    }

    internal fun bytes(length: Int): ByteArray = ByteArray(length).also { scratch.getBytes(0, it) }
}

/**
 * One gateway in front of an [ExchangeModel], as a [GatewayLink]. Its states are the ones a client
 * meets (Design.md §7, "Gateway placement"):
 * - [Mode.ACTIVE] forwards to the log;
 * - [Mode.STANDBY] refuses `GATEWAY_UNAVAILABLE` with `reportSeq` 0, and holds nothing;
 * - [Mode.DEAD] is gone: no reject, and an offer is `NOT_CONNECTED`.
 *
 * Two faults a failover produces: [blackhole] accepts a request that never reaches the log (sent in
 * the blind window before Aeron notices), and [dropReports] sequences one whose report never arrives.
 */
class ModelGateway(private val exchange: ExchangeModel, name: String) : GatewayLink {
    enum class Mode { ACTIVE, STANDBY, DEAD }

    override val endpoint = GatewayEndpoint("model:$name", 1, "model:$name", 2)
    var mode = Mode.ACTIVE
    var blackhole = false
    var dropReports = false
    var backPressure = 0

    /** Every message this gateway accepted, by template id, in order. */
    val received = mutableListOf<Int>()

    /** The same messages decoded, over copies, for tests that read a field. */
    val requests = mutableListOf<Any>()
    private val outbound = ArrayDeque<ByteArray>()

    // Every report fits one frame, as on the real stream: FragmentAssembler passes it straight through.
    private val frameHeader = Header(0, 0).apply {
        val frame = UnsafeBuffer(ByteArray(DataHeaderFlyweight.HEADER_LENGTH))
        DataHeaderFlyweight(frame).flags(FrameDescriptor.UNFRAGMENTED.toShort())
        buffer(frame)
        offset(0)
    }
    private var position = 0L
    private val scratch = UnsafeBuffer(ByteArray(256))
    private val header = MessageHeaderEncoder()

    override val isConnected: Boolean get() = mode != Mode.DEAD

    override fun offer(buffer: DirectBuffer, offset: Int, length: Int): Long {
        if (mode == Mode.DEAD) return Publication.NOT_CONNECTED
        if (backPressure > 0) {
            backPressure--
            return Publication.BACK_PRESSURED
        }
        val h = MessageHeaderDecoder().wrap(buffer, offset)
        received += h.templateId()
        requests += decodeCopy(buffer, offset, length, h)
        position += length
        when {
            mode == Mode.STANDBY -> refuse(buffer, offset, h)
            blackhole -> Unit
            else -> exchange.sequence(this, buffer, offset)
        }
        return position
    }

    override fun poll(handler: FragmentHandler, fragmentLimit: Int): Int {
        var n = 0
        while (n < fragmentLimit && outbound.isNotEmpty()) {
            val bytes = outbound.removeFirst()
            handler.onFragment(UnsafeBuffer(bytes), 0, bytes.size, frameHeader)
            n++
        }
        return n
    }

    fun deliver(bytes: ByteArray) {
        if (mode != Mode.DEAD) outbound.addLast(bytes)
    }

    @Suppress("LongParameterList")
    fun deliverResendComplete(
        participantId: Long, requestId: Long, fromSeq: Long, nextSeq: Long, oldest: Long, replayed: Int,
        status: RequestStatus,
    ) {
        ReportResendCompleteEncoder().wrapAndApplyHeader(scratch, 0, header)
            .participantId(participantId).requestId(requestId).fromSeq(fromSeq).nextSeq(nextSeq)
            .oldestRetainedSeq(oldest).replayedCount(replayed).status(status)
        deliver(copy(MessageHeaderEncoder.ENCODED_LENGTH + ReportResendCompleteEncoder.BLOCK_LENGTH))
    }

    @Suppress("LongParameterList")
    fun deliverStatusComplete(
        participantId: Long, requestId: Long, nextSeq: Long, orderCount: Int, securityId: Int, status: RequestStatus,
    ) {
        OrderMassStatusCompleteEncoder().wrapAndApplyHeader(scratch, 0, header)
            .participantId(participantId).requestId(requestId).nextSeq(nextSeq).orderCount(orderCount)
            .securityId(securityId).status(status)
        deliver(copy(MessageHeaderEncoder.ENCODED_LENGTH + OrderMassStatusCompleteEncoder.BLOCK_LENGTH))
    }

    /** What a standby gateway answers: its own refusal, `reportSeq` 0, nothing forwarded. */
    private fun refuse(buffer: DirectBuffer, offset: Int, h: MessageHeaderDecoder) {
        val body = offset + MessageHeaderDecoder.ENCODED_LENGTH
        when (h.templateId()) {
            NewOrderSingleDecoder.TEMPLATE_ID -> {
                val d = NewOrderSingleDecoder().wrap(buffer, body, h.blockLength(), h.version())
                localReject(d.participantId(), d.clOrdId(), d.securityId(), d.side())
            }
            OrderCancelRequestDecoder.TEMPLATE_ID -> {
                val d = OrderCancelRequestDecoder().wrap(buffer, body, h.blockLength(), h.version())
                localReject(d.participantId(), d.clOrdId(), d.securityId(), d.side())
            }
            ReportResendRequestDecoder.TEMPLATE_ID -> {
                val d = ReportResendRequestDecoder().wrap(buffer, body, h.blockLength(), h.version())
                deliverResendComplete(d.participantId(), d.requestId(), d.fromSeq(), 0L, 0L, 0, RequestStatus.GATEWAY_UNAVAILABLE)
            }
            OrderMassStatusRequestDecoder.TEMPLATE_ID -> {
                val d = OrderMassStatusRequestDecoder().wrap(buffer, body, h.blockLength(), h.version())
                deliverStatusComplete(d.participantId(), d.requestId(), 0L, 0, d.securityId(), RequestStatus.GATEWAY_UNAVAILABLE)
            }
        }
    }

    private fun localReject(participantId: Long, clOrdId: Long, securityId: Int, side: Side) {
        ClientExecutionReportEncoder().wrapAndApplyHeader(scratch, 0, header)
            .participantId(participantId).clOrdId(clOrdId).exchangeOrderId(0L).price(0L).lastQty(0L)
            .leavesQty(0L).cumQty(0L).origQty(0L).securityId(securityId)
            .rejectReason(RejectReason.GATEWAY_UNAVAILABLE).execType(ExecType.REJECTED).side(side)
            .enrichment(Enrichment.KNOWN).reportSeq(0L)
        deliver(copy(MessageHeaderEncoder.ENCODED_LENGTH + ClientExecutionReportEncoder.BLOCK_LENGTH))
    }

    private fun decodeCopy(buffer: DirectBuffer, offset: Int, length: Int, h: MessageHeaderDecoder): Any {
        val copy = UnsafeBuffer(ByteArray(length)).also { buffer.getBytes(offset, it, 0, length) }
        val body = MessageHeaderDecoder.ENCODED_LENGTH
        return when (h.templateId()) {
            NewOrderSingleDecoder.TEMPLATE_ID -> NewOrderSingleDecoder().wrap(copy, body, h.blockLength(), h.version())
            OrderCancelRequestDecoder.TEMPLATE_ID -> OrderCancelRequestDecoder().wrap(copy, body, h.blockLength(), h.version())
            ReportResendRequestDecoder.TEMPLATE_ID -> ReportResendRequestDecoder().wrap(copy, body, h.blockLength(), h.version())
            OrderMassStatusRequestDecoder.TEMPLATE_ID -> OrderMassStatusRequestDecoder().wrap(copy, body, h.blockLength(), h.version())
            else -> copy
        }
    }

    private fun copy(length: Int): ByteArray = ByteArray(length).also { scratch.getBytes(0, it) }
}
