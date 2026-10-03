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
import com.engine.sbe.ReportResendCompleteDecoder
import com.engine.sbe.ReportResendCompleteEncoder
import com.engine.sbe.ReportResendRequestDecoder
import com.engine.sbe.ResendStatus
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
 * Three jobs. Inbound, it enforces what the participant registry grants this gateway -- which
 * participants may place, which may only cancel, whether it may send operator commands
 * ([GatewayAccess], Design.md §1) -- and validates `securityId` against the shard map, rejecting
 * locally in both cases without spending a cluster round trip or a slot in the replicated log.
 * Outbound, it translates the engine's report into the client-facing shape.
 *
 * **It holds no per-order state**, and that is deliberate. It used to hold `origQty` for every
 * live order, because the engine had no room for it; the engine now carries it in the cold word
 * beside the order's cache line (Design.md §3.1) and states both `origQty` and `cumQty` on every
 * report. So a gateway is disposable: restarting one loses nothing, and several can serve one
 * shard without any of them holding something the others need.
 */
class GatewayService(
    shardSecurityIds: IntArray,
    private val sink: GatewaySink,
    /** Hot-path timing, or null to keep the clock out of the path entirely. */
    private val metrics: GatewayMetrics? = null,
    /** What the registry grants this gateway. [GatewayAccess.OPEN] when it has no identity. */
    private val access: GatewayAccess = GatewayAccess.OPEN,
) {
    private val securityIds = shardSecurityIds.copyOf()

    private val header = MessageHeaderDecoder()
    private val headerEncoder = MessageHeaderEncoder()
    private val newOrder = NewOrderSingleDecoder()
    private val cancel = OrderCancelRequestDecoder()
    private val execReport = ExecutionReportDecoder()
    private val clientReport = ClientExecutionReportEncoder()
    private val resendRequest = ReportResendRequestDecoder()
    private val resendComplete = ReportResendCompleteEncoder()
    private val outbound: MutableDirectBuffer = UnsafeBuffer(ByteArray(512))

    var rejectedLocally = 0L
        private set

    /** Reports for an order the engine could not state an `origQty` for; see [Enrichment]. */
    var untrackedReports = 0L
        private set

    /** Client messages rejected because the cluster session could not take them. */
    var unreachableRejects = 0L
        private set

    /** Operator commands lost the same way. They have no client to reject to. */
    var undeliverableCommands = 0L
        private set

    /**
     * Orders and cancels refused `UNAUTHORIZED_PARTICIPANT`: the participant is not one this
     * gateway may act for (or, for an order, is cancel-only here). Counted apart from
     * [rejectedLocally], because it says something about who is connected, not about the order.
     */
    var unauthorizedRejects = 0L
        private set

    /** Operator commands consumed unsent because this gateway is not an operator. */
    var refusedCommands = 0L
        private set

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

            ReportResendRequestDecoder.TEMPLATE_ID -> {
                resendRequest.wrap(buffer, body, blockLength, version)
                onResendRequest(buffer, offset, length)
            }

            // Session transitions, purges, security definitions and image requests are operator
            // commands, and so is anything else this gateway does not recognise: deny by default.
            // Only an operator gateway forwards them, untouched. There is no client session to
            // reject to, so a refused or lost one is counted; the poller is about to stop anyway on
            // a loss, since only a dead session fails here.
            else -> if (!access.mayOperate()) {
                refusedCommands++
                ClientMessageAction.CONSUME
            } else when (sink.toCluster(buffer, offset, length)) {
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

        // First, so a participant this gateway may not act for learns nothing about the shard.
        val reason = if (!access.mayPlace(participantId)) RejectReason.UNAUTHORIZED_PARTICIPANT
        else validate(securityId, qty, price)
        if (reason != RejectReason.NONE) {
            if (reason == RejectReason.UNAUTHORIZED_PARTICIPANT) unauthorizedRejects++
            else rejectedLocally++
            emitClientReport(
                participantId, clOrdId, 0L, securityId, ExecType.REJECTED, side,
                price = price, lastQty = 0L, leavesQty = 0L, cumQty = 0L, origQty = qty,
                rejectReason = reason,
            )
            return ClientMessageAction.CONSUME
        }

        return when (sink.toCluster(buffer, offset, length)) {
            ClusterOffer.SENT -> ClientMessageAction.CONSUME
            ClusterOffer.RETRY -> ClientMessageAction.RETRY

            ClusterOffer.FAILED -> {
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
        // Checked, not trusted: the engine's own cancel check is that this participant id matches
        // the order's, which only means something once the gateway has established the id is one
        // this endpoint may use (Design.md §1).
        val reason = when {
            !access.mayCancel(cancel.participantId()) -> RejectReason.UNAUTHORIZED_PARTICIPANT
            indexOf(securityId) < 0 -> RejectReason.UNKNOWN_SECURITY
            else -> RejectReason.NONE
        }
        if (reason != RejectReason.NONE) {
            if (reason == RejectReason.UNAUTHORIZED_PARTICIPANT) unauthorizedRejects++
            else rejectedLocally++
            emitClientReport(
                cancel.participantId(), cancel.clOrdId(), cancel.exchangeOrderId(), securityId,
                ExecType.REJECTED, cancel.side(),
                price = 0L, lastQty = 0L, leavesQty = 0L, cumQty = 0L, origQty = 0L,
                rejectReason = reason,
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
     * A participant asking for its reports again (Design.md §5, "Report sequence and resend").
     * Checked as a cancel is: a cancel-only participant still has fills to learn about. Refused with
     * a [ResendStatus] where an order would be refused with a [RejectReason], so the client always
     * gets an answer and never waits on a request that went nowhere.
     */
    private fun onResendRequest(
        buffer: DirectBuffer,
        offset: Int,
        length: Int,
    ): ClientMessageAction {
        val participantId = resendRequest.participantId()
        if (!access.mayCancel(participantId)) {
            unauthorizedRejects++
            refuseResend(ResendStatus.UNAUTHORIZED_PARTICIPANT)
            return ClientMessageAction.CONSUME
        }
        return when (sink.toCluster(buffer, offset, length)) {
            ClusterOffer.SENT -> ClientMessageAction.CONSUME
            ClusterOffer.RETRY -> ClientMessageAction.RETRY
            ClusterOffer.FAILED -> {
                unreachableRejects++
                refuseResend(ResendStatus.GATEWAY_UNAVAILABLE)
                ClientMessageAction.CONSUME
            }
        }
    }

    /** The completion for a request that never reached the engine: nothing replayed, nothing known. */
    private fun refuseResend(status: ResendStatus) {
        resendComplete.wrapAndApplyHeader(outbound, 0, headerEncoder)
            .participantId(resendRequest.participantId())
            .requestId(resendRequest.requestId())
            .fromSeq(resendRequest.fromSeq())
            .nextSeq(0L)
            .oldestRetainedSeq(0L)
            .replayedCount(0)
            .status(status)
        sink.toClient(
            outbound, 0,
            MessageHeaderEncoder.ENCODED_LENGTH + ReportResendCompleteEncoder.BLOCK_LENGTH,
        )
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

    /**
     * Cluster to client. An execution report is translated into the client's shape; the end of a
     * resend is the same message on both legs and goes across unchanged. Anything else from the
     * cluster is not for a client.
     */
    fun onExecutionReport(buffer: DirectBuffer, offset: Int, length: Int) {
        if (length < MessageHeaderDecoder.ENCODED_LENGTH) return
        header.wrap(buffer, offset)
        if (header.templateId() == ReportResendCompleteDecoder.TEMPLATE_ID) {
            sink.toClient(buffer, offset, length)
            return
        }
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

        // Both stated by the engine. A version 2 report carries neither, and an order restored
        // from a version 2 snapshot carries origQty = 0 -- in both cases the number is genuinely
        // unknown, and saying so is the point: cumQty = 0 on a half-filled order is a confident
        // lie the client has no way to detect. A rejected order has no accumulated quantity to
        // know, so it is not counted as a loss.
        val reportedOrigQty = execReport.origQty()
        val known = reportedOrigQty != ORIG_QTY_ABSENT && reportedOrigQty != 0L
        if (!known && execType != ExecType.REJECTED) untrackedReports++

        emitClientReport(
            participantId, clOrdId, exchangeOrderId, execReport.securityId(), execType,
            execReport.side(), price = execReport.price(), lastQty = execReport.lastQty(),
            leavesQty = leavesQty,
            cumQty = if (known) execReport.cumQty() else 0L,
            origQty = if (known) reportedOrigQty else 0L,
            rejectReason = execReport.rejectReason(),
            enrichment = if (known || execType == ExecType.REJECTED) Enrichment.KNOWN
            else Enrichment.UNKNOWN,
            // Copied, never assigned here: the sequence is the engine's, per participant. A report
            // from an engine older than version 6 has none, and 0 says so.
            reportSeq = execReport.reportSeq().let { if (it == REPORT_SEQ_ABSENT) 0L else it },
        )

        metrics?.outbound?.record(metrics.nanoTime() - started)
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
         * from an engine that could not state origQty is UNKNOWN, and that one passes it
         * explicitly.
         */
        enrichment: Enrichment = Enrichment.KNOWN,
        /**
         * The engine's per-participant report sequence. Defaults to 0 for the same reason: a report
         * the gateway makes itself never reached the log and has no place in that sequence.
         */
        reportSeq: Long = 0L,
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
            .reportSeq(reportSeq)
        sink.toClient(
            outbound, 0,
            MessageHeaderEncoder.ENCODED_LENGTH + ClientExecutionReportEncoder.BLOCK_LENGTH,
        )
    }

    private fun indexOf(securityId: Int): Int {
        for (i in securityIds.indices) if (securityIds[i] == securityId) return i
        return -1
    }

    private companion object {
        /** What an `origQty` decodes to on a report from an engine older than schema version 3. */
        val ORIG_QTY_ABSENT: Long = ExecutionReportDecoder.origQtyNullValue()

        /** What a `reportSeq` decodes to on a report from an engine older than schema version 6. */
        val REPORT_SEQ_ABSENT: Long = ExecutionReportDecoder.reportSeqNullValue()
    }
}
