package com.engine.gateway

import com.engine.sbe.ClientExecutionReportEncoder
import com.engine.sbe.ExecType
import com.engine.sbe.ExecutionReportDecoder
import com.engine.sbe.MessageHeaderDecoder
import com.engine.sbe.MessageHeaderEncoder
import com.engine.sbe.NewOrderSingleDecoder
import com.engine.sbe.OrderCancelRequestDecoder
import com.engine.sbe.RejectReason
import com.engine.sbe.Side
import org.agrona.DirectBuffer
import org.agrona.MutableDirectBuffer
import org.agrona.concurrent.UnsafeBuffer

/** Where the gateway sends things. An interface so the logic is testable without Aeron. */
interface GatewaySink {
    /** To the cluster, unchanged. */
    fun toCluster(buffer: DirectBuffer, offset: Int, length: Int)

    /** To the client, enriched. */
    fun toClient(buffer: DirectBuffer, offset: Int, length: Int)
}

/**
 * The order entry gateway (Design.md §1).
 *
 * Protocol-agnostic: it speaks the binary SBE messages of §5 on both legs and knows nothing about
 * FIX. Translating a client's own session protocol happens in a separate upstream gateway, so
 * adding a client protocol means adding a process, not changing this one.
 *
 * Two jobs. Inbound, it validates `securityId` against the shard map and rejects out-of-shard
 * orders locally, without spending a cluster round trip or a slot in the replicated log. Outbound,
 * it restores `cumQty` and `origQty` onto every execution report from the state it kept.
 */
class GatewayService(
    shardSecurityIds: IntArray,
    private val sink: GatewaySink,
    private val state: OrderStateStore = OrderStateStore(),
) {
    private val securityIds = shardSecurityIds.copyOf()

    private val header = MessageHeaderDecoder()
    private val headerEncoder = MessageHeaderEncoder()
    private val newOrder = NewOrderSingleDecoder()
    private val cancel = OrderCancelRequestDecoder()
    private val execReport = ExecutionReportDecoder()
    private val clientReport = ClientExecutionReportEncoder()
    private val outbound: MutableDirectBuffer = UnsafeBuffer(ByteArray(512))

    var rejectedLocally = 0L
        private set
    var untrackedReports = 0L
        private set

    val liveOrders: Int get() = state.liveOrders

    // ------------------------------------------------------------- inbound

    /** Client to cluster. Returns true when the message was forwarded. */
    fun onClientMessage(buffer: DirectBuffer, offset: Int, length: Int): Boolean {
        if (length < MessageHeaderDecoder.ENCODED_LENGTH) return false
        header.wrap(buffer, offset)
        val body = offset + MessageHeaderDecoder.ENCODED_LENGTH
        val blockLength = header.blockLength()
        val version = header.version()

        return when (header.templateId()) {
            NewOrderSingleDecoder.TEMPLATE_ID -> {
                newOrder.wrap(buffer, body, blockLength, version)
                onNewOrder(buffer, offset, length)
            }

            OrderCancelRequestDecoder.TEMPLATE_ID -> {
                cancel.wrap(buffer, body, blockLength, version)
                onCancel(buffer, offset, length)
            }

            // Session transitions, purges and security definitions are operator commands and
            // pass through untouched.
            else -> {
                sink.toCluster(buffer, offset, length)
                true
            }
        }
    }

    private fun onNewOrder(buffer: DirectBuffer, offset: Int, length: Int): Boolean {
        val participantId = newOrder.participantId()
        val clOrdId = newOrder.clOrdId()
        val securityId = newOrder.securityId()
        val qty = newOrder.qty()
        val price = newOrder.price()
        val side = newOrder.side()

        val reason = validate(securityId, qty, price)
        if (reason != RejectReason.NONE) {
            rejectedLocally++
            emitClientReport(
                participantId, clOrdId, 0L, securityId, ExecType.REJECTED, side,
                price = price, lastQty = 0L, leavesQty = 0L, cumQty = 0L, origQty = qty,
                rejectReason = reason,
            )
            return false
        }

        // Recorded before forwarding: the acknowledgement can come back before this returns.
        state.recordPending(participantId, clOrdId, qty)
        sink.toCluster(buffer, offset, length)
        return true
    }

    private fun onCancel(buffer: DirectBuffer, offset: Int, length: Int): Boolean {
        val securityId = cancel.securityId()
        if (indexOf(securityId) < 0) {
            rejectedLocally++
            emitClientReport(
                cancel.participantId(), cancel.clOrdId(), cancel.exchangeOrderId(), securityId,
                ExecType.REJECTED, cancel.side(),
                price = 0L, lastQty = 0L, leavesQty = 0L, cumQty = 0L, origQty = 0L,
                rejectReason = RejectReason.UNKNOWN_SECURITY,
            )
            return false
        }
        sink.toCluster(buffer, offset, length)
        return true
    }

    /**
     * Only what the gateway can decide alone. Collars, phase and capacity depend on book state
     * the gateway does not have, and stay in the engine; duplicating them here would mean two
     * places to get them wrong.
     */
    private fun validate(securityId: Int, qty: Long, price: Long): RejectReason = when {
        indexOf(securityId) < 0 -> RejectReason.UNKNOWN_SECURITY
        qty <= 0L -> RejectReason.PRICE_OUT_OF_BOUNDS
        price <= 0L -> RejectReason.PRICE_OUT_OF_BOUNDS
        else -> RejectReason.NONE
    }

    // ------------------------------------------------------------ outbound

    /** Cluster to client: restore `origQty` and `cumQty`, then forward. */
    fun onExecutionReport(buffer: DirectBuffer, offset: Int, length: Int) {
        if (length < MessageHeaderDecoder.ENCODED_LENGTH) return
        header.wrap(buffer, offset)
        if (header.templateId() != ExecutionReportDecoder.TEMPLATE_ID) return
        execReport.wrap(
            buffer,
            offset + MessageHeaderDecoder.ENCODED_LENGTH,
            header.blockLength(),
            header.version(),
        )

        val participantId = execReport.participantId()
        val clOrdId = execReport.clOrdId()
        val exchangeOrderId = execReport.exchangeOrderId()
        val execType = execReport.execType()
        val leavesQty = execReport.leavesQty()

        val origQty = when (execType) {
            ExecType.NEW -> state.bind(participantId, clOrdId, exchangeOrderId)
            ExecType.REJECTED -> {
                state.discardPending(participantId, clOrdId)
                NOT_FOUND
            }

            else -> state.origQtyOf(exchangeOrderId)
        }

        // An order this gateway never saw — the usual cause is a restart, not a fault. Forward
        // it with what is known rather than dropping a report the client is waiting for.
        if (origQty == NOT_FOUND && execType != ExecType.REJECTED) untrackedReports++

        val knownOrigQty = if (origQty == NOT_FOUND) 0L else origQty
        val cumQty = if (origQty == NOT_FOUND) 0L else origQty - leavesQty

        emitClientReport(
            participantId, clOrdId, exchangeOrderId, execReport.securityId(), execType,
            execReport.side(), price = execReport.price(), lastQty = execReport.lastQty(),
            leavesQty = leavesQty, cumQty = cumQty, origQty = knownOrigQty,
            rejectReason = execReport.rejectReason(),
        )

        if (isTerminal(execType, leavesQty)) state.release(exchangeOrderId)
    }

    /** Terminal states free the order's state; anything else may still see further fills. */
    private fun isTerminal(execType: ExecType, leavesQty: Long): Boolean = when (execType) {
        ExecType.CANCELED, ExecType.EXPIRED, ExecType.REJECTED -> true
        ExecType.TRADE -> leavesQty == 0L
        else -> false
    }

    @Suppress("LongParameterList")
    private fun emitClientReport(
        participantId: Long,
        clOrdId: Long,
        exchangeOrderId: Long,
        securityId: Int,
        execType: ExecType,
        side: Side,
        price: Long,
        lastQty: Long,
        leavesQty: Long,
        cumQty: Long,
        origQty: Long,
        rejectReason: RejectReason,
    ) {
        clientReport.wrapAndApplyHeader(outbound, 0, headerEncoder)
            .participantId(participantId)
            .clOrdId(clOrdId)
            .exchangeOrderId(exchangeOrderId)
            .price(price)
            .lastQty(lastQty)
            .leavesQty(leavesQty)
            .cumQty(cumQty)
            .origQty(origQty)
            .securityId(securityId)
            .rejectReason(rejectReason)
            .execType(execType)
            .side(side)
        sink.toClient(
            outbound, 0,
            MessageHeaderEncoder.ENCODED_LENGTH + ClientExecutionReportEncoder.BLOCK_LENGTH,
        )
    }

    private fun indexOf(securityId: Int): Int {
        for (i in securityIds.indices) if (securityIds[i] == securityId) return i
        return -1
    }
}
