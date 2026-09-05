package com.engine.gateway

import com.engine.sbe.ClientExecutionReportEncoder
import com.engine.sbe.Enrichment
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

/** What became of an offer to the cluster. */
enum class ClusterOffer {
    SENT,

    /**
     * Transient — backpressure, or an admin action such as a term rotation. The message has not
     * been sent and the same bytes must be offered again.
     */
    RETRY,

    /** Terminal: the cluster session is gone, and offering these bytes again will not help. */
    FAILED,
}

/**
 * What the poller should do with the fragment it just handed over.
 *
 * A gateway that consumed a fragment it could not forward would lose a client's order with no
 * acknowledgement and no rejection, which is the one outcome a client cannot recover from.
 */
enum class ClientMessageAction {
    /** Dealt with: forwarded to the cluster, or rejected back to the client. */
    CONSUME,

    /** Not sent. Leave the fragment unconsumed so the next poll offers it again. */
    RETRY,
}

/** Where the gateway sends things. An interface so the logic is testable without Aeron. */
interface GatewaySink {
    /** To the cluster, unchanged. */
    fun toCluster(buffer: DirectBuffer, offset: Int, length: Int): ClusterOffer

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
    /** Hot-path timing, or null to keep the clock out of the path entirely. */
    private val metrics: GatewayMetrics? = null,
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

    /** Client messages rejected because the cluster session could not take them. */
    var unreachableRejects = 0L
        private set

    /** Operator commands lost the same way. They have no client to reject to. */
    var undeliverableCommands = 0L
        private set

    val liveOrders: Int get() = state.liveOrders

    /**
     * Orders forwarded and not yet acknowledged. Worth watching because nothing reaps one whose
     * acknowledgement never comes, so a number that only grows is a leak rather than traffic.
     */
    val pendingOrders: Int get() = state.pendingOrders

    /** Orders the journal had no room to track, and which will therefore report `UNKNOWN`. */
    val journalExhausted: Long get() = state.journalExhausted

    // ------------------------------------------------------------- inbound

    /**
     * Client to cluster. The return value says whether the fragment may be consumed: on cluster
     * backpressure the message is left unsent and unconsumed, so the poller offers it again
     * rather than dropping an order the client believes it has placed.
     */
    fun onClientMessage(
        buffer: DirectBuffer,
        offset: Int,
        length: Int,
    ): ClientMessageAction {
        if (length < MessageHeaderDecoder.ENCODED_LENGTH) return ClientMessageAction.CONSUME
        header.wrap(buffer, offset)
        val body = offset + MessageHeaderDecoder.ENCODED_LENGTH
        val blockLength = header.blockLength()
        val version = header.version()

        val started = if (metrics != null) metrics.nanoTime() else 0L
        // Measured on every outcome including RETRY: a fragment left unconsumed under cluster
        // backpressure still cost this process the time, and dropping those samples would make the
        // leg look fastest exactly when the cluster is struggling.
        val action = when (header.templateId()) {
            NewOrderSingleDecoder.TEMPLATE_ID -> {
                newOrder.wrap(buffer, body, blockLength, version)
                onNewOrder(buffer, offset, length)
            }

            OrderCancelRequestDecoder.TEMPLATE_ID -> {
                cancel.wrap(buffer, body, blockLength, version)
                onCancel(buffer, offset, length)
            }

            // Session transitions, purges and security definitions are operator commands and
            // pass through untouched. There is no client session to reject to, so a lost one is
            // counted; the poller is about to stop anyway, since only a dead session fails here.
            else -> when (sink.toCluster(buffer, offset, length)) {
                ClusterOffer.SENT -> ClientMessageAction.CONSUME
                ClusterOffer.RETRY -> ClientMessageAction.RETRY
                ClusterOffer.FAILED -> {
                    undeliverableCommands++
                    ClientMessageAction.CONSUME
                }
            }
        }
        metrics?.inbound?.record(metrics.nanoTime() - started)
        return action
    }

    private fun onNewOrder(
        buffer: DirectBuffer,
        offset: Int,
        length: Int,
    ): ClientMessageAction {
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
            return ClientMessageAction.CONSUME
        }

        // Recorded before forwarding: the acknowledgement can come back before this returns.
        // An offer that does not send therefore has to unwind it, or a retried order would be
        // recorded twice and a failed one would leak a pending entry that nothing ever releases.
        state.recordPending(participantId, clOrdId, qty)
        return when (sink.toCluster(buffer, offset, length)) {
            ClusterOffer.SENT -> ClientMessageAction.CONSUME
            ClusterOffer.RETRY -> {
                state.discardPending(participantId, clOrdId)
                ClientMessageAction.RETRY
            }

            ClusterOffer.FAILED -> {
                state.discardPending(participantId, clOrdId)
                unreachableRejects++
                emitClientReport(
                    participantId, clOrdId, 0L, securityId, ExecType.REJECTED, side,
                    price = price, lastQty = 0L, leavesQty = 0L, cumQty = 0L, origQty = qty,
                    rejectReason = RejectReason.GATEWAY_UNAVAILABLE,
                )
                ClientMessageAction.CONSUME
            }
        }
    }

    private fun onCancel(
        buffer: DirectBuffer,
        offset: Int,
        length: Int,
    ): ClientMessageAction {
        val securityId = cancel.securityId()
        if (indexOf(securityId) < 0) {
            rejectedLocally++
            emitClientReport(
                cancel.participantId(), cancel.clOrdId(), cancel.exchangeOrderId(), securityId,
                ExecType.REJECTED, cancel.side(),
                price = 0L, lastQty = 0L, leavesQty = 0L, cumQty = 0L, origQty = 0L,
                rejectReason = RejectReason.UNKNOWN_SECURITY,
            )
            return ClientMessageAction.CONSUME
        }
        return when (sink.toCluster(buffer, offset, length)) {
            ClusterOffer.SENT -> ClientMessageAction.CONSUME
            ClusterOffer.RETRY -> ClientMessageAction.RETRY
            ClusterOffer.FAILED -> {
                unreachableRejects++
                emitClientReport(
                    cancel.participantId(), cancel.clOrdId(), cancel.exchangeOrderId(), securityId,
                    ExecType.REJECTED, cancel.side(),
                    price = 0L, lastQty = 0L, leavesQty = 0L, cumQty = 0L, origQty = 0L,
                    rejectReason = RejectReason.GATEWAY_UNAVAILABLE,
                )
                ClientMessageAction.CONSUME
            }
        }
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
        // Started after the template check, so a foreign fragment is not counted as a report the
        // gateway handled quickly.
        val started = if (metrics != null) metrics.nanoTime() else 0L
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
        // it with what is known, marked as such, rather than dropping a report the client is
        // waiting for or inventing a cumQty for it.
        if (origQty == NOT_FOUND && execType != ExecType.REJECTED) untrackedReports++

        // CumQty is accumulated from fills, never derived from leavesQty: a terminal report
        // carries leavesQty = 0 whether the order filled or was cancelled, so deriving it would
        // report a cancelled order as fully filled.
        if (execType == ExecType.TRADE) state.recordFill(exchangeOrderId, execReport.lastQty())

        // A rejected order was never bound, so having no origQty for it is the normal case rather
        // than a loss of state; everything else with no origQty is this gateway not knowing.
        val unknown = origQty == NOT_FOUND && execType != ExecType.REJECTED
        val knownOrigQty = if (origQty == NOT_FOUND) 0L else origQty
        val cumQty = if (origQty == NOT_FOUND) 0L else state.cumQtyOf(exchangeOrderId)

        emitClientReport(
            participantId, clOrdId, exchangeOrderId, execReport.securityId(), execType,
            execReport.side(), price = execReport.price(), lastQty = execReport.lastQty(),
            leavesQty = leavesQty, cumQty = cumQty, origQty = knownOrigQty,
            rejectReason = execReport.rejectReason(),
            // Said out loud rather than left to be inferred from a zero. This gateway holds
            // origQty in memory and the engine does not store it at all, so after a gateway
            // restart there is nothing to recover it from -- and `cumQty = 0` on a half-filled
            // order is a confident lie the client has no way to detect. It never re-heals either:
            // recordFill ignores an order it has no origQty for, so the value would stay wrong for
            // the rest of that order's life.
            enrichment = if (unknown) Enrichment.UNKNOWN else Enrichment.KNOWN,
        )

        if (isTerminal(execType, leavesQty)) state.release(exchangeOrderId)
        metrics?.outbound?.record(metrics.nanoTime() - started)
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
        /**
         * Defaults to KNOWN because every gateway-generated report is one it knows everything
         * about — it is rejecting an order it is holding in its hand. Only a report coming back
         * from the engine for an order this gateway has no record of is UNKNOWN, and that one
         * passes it explicitly.
         */
        enrichment: Enrichment = Enrichment.KNOWN,
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
            .enrichment(enrichment)
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
