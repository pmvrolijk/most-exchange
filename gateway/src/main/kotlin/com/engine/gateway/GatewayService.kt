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
 * it translates the engine's report into the client-facing shape.
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

    /** Reports for an order the engine could not state an `origQty` for; see [Enrichment]. */
    var untrackedReports = 0L
        private set

    /** Client messages rejected because the cluster session could not take them. */
    var unreachableRejects = 0L
        private set

    /** Operator commands lost the same way. They have no client to reject to. */
    var undeliverableCommands = 0L
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

    private companion object {
        /** What an `origQty` decodes to on a report from an engine older than schema version 3. */
        val ORIG_QTY_ABSENT: Long = ExecutionReportDecoder.origQtyNullValue()
    }
}
