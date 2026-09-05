package com.engine.gateway

import com.engine.sbe.ClientExecutionReportDecoder
import com.engine.sbe.Enrichment
import com.engine.sbe.ExecType
import com.engine.sbe.ExecutionReportEncoder
import com.engine.sbe.MessageHeaderDecoder
import com.engine.sbe.MessageHeaderEncoder
import com.engine.sbe.NewOrderSingleEncoder
import com.engine.sbe.OrderCancelRequestEncoder
import com.engine.sbe.RejectReason
import com.engine.sbe.Side
import com.engine.sbe.SmpStrategy
import org.agrona.DirectBuffer
import org.agrona.concurrent.UnsafeBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

data class ClientReport(
    val participantId: Long,
    val clOrdId: Long,
    val exchangeOrderId: Long,
    val execType: String,
    val lastQty: Long,
    val leavesQty: Long,
    val cumQty: Long,
    val origQty: Long,
    val rejectReason: Int,
    val enrichment: Enrichment,
)

class RecordingSink : GatewaySink {
    val toCluster = mutableListOf<Int>()
    val toClient = mutableListOf<ClientReport>()

    /** What the next offer to the cluster should pretend to be. */
    var clusterOffer = ClusterOffer.SENT

    private val header = MessageHeaderDecoder()
    private val decoder = ClientExecutionReportDecoder()

    override fun toCluster(buffer: DirectBuffer, offset: Int, length: Int): ClusterOffer {
        if (clusterOffer == ClusterOffer.SENT) toCluster += length
        return clusterOffer
    }

    override fun toClient(buffer: DirectBuffer, offset: Int, length: Int) {
        header.wrap(buffer, offset)
        decoder.wrap(
            buffer,
            offset + MessageHeaderDecoder.ENCODED_LENGTH,
            header.blockLength(),
            header.version(),
        )
        toClient += ClientReport(
            decoder.participantId(), decoder.clOrdId(), decoder.exchangeOrderId(),
            decoder.execType().name, decoder.lastQty(), decoder.leavesQty(),
            decoder.cumQty(), decoder.origQty(), decoder.rejectReason().value(),
            decoder.enrichment(),
        )
    }
}

class GatewayServiceTest {

    private val sink = RecordingSink()
    private val service = GatewayService(intArrayOf(1, 2), sink)
    private val buffer = UnsafeBuffer(ByteArray(512))
    private val headerEncoder = MessageHeaderEncoder()

    private fun newOrder(
        participantId: Long,
        clOrdId: Long,
        securityId: Int,
        qty: Long,
        price: Long = 100,
        side: Side = Side.BUY,
    ): ClientMessageAction {
        NewOrderSingleEncoder().wrapAndApplyHeader(buffer, 0, headerEncoder)
            .participantId(participantId).clOrdId(clOrdId).price(price).qty(qty).smpId(0)
            .securityId(securityId).expireDate(0).side(side).smpStrategy(SmpStrategy.CANCEL_AGGRESSOR)
        return service.onClientMessage(
            buffer, 0,
            MessageHeaderEncoder.ENCODED_LENGTH + NewOrderSingleEncoder.BLOCK_LENGTH,
        )
    }

    private fun cancelRequest(
        participantId: Long,
        clOrdId: Long,
        securityId: Int,
    ): ClientMessageAction {
        OrderCancelRequestEncoder().wrapAndApplyHeader(buffer, 0, headerEncoder)
            .participantId(participantId).origClOrdId(clOrdId).clOrdId(clOrdId + 1)
            .exchangeOrderId(1).securityId(securityId).side(Side.BUY)
        return service.onClientMessage(
            buffer, 0,
            MessageHeaderEncoder.ENCODED_LENGTH + OrderCancelRequestEncoder.BLOCK_LENGTH,
        )
    }

    private fun report(
        participantId: Long,
        clOrdId: Long,
        exchangeOrderId: Long,
        execType: ExecType,
        lastQty: Long = 0,
        leavesQty: Long = 0,
        rejectReason: RejectReason = RejectReason.NONE,
    ) {
        val b = UnsafeBuffer(ByteArray(512))
        ExecutionReportEncoder().wrapAndApplyHeader(b, 0, MessageHeaderEncoder())
            .participantId(participantId).clOrdId(clOrdId).exchangeOrderId(exchangeOrderId)
            .price(100).lastQty(lastQty).leavesQty(leavesQty).securityId(1)
            .rejectReason(rejectReason).execType(execType).side(Side.BUY)
        service.onExecutionReport(
            b, 0, MessageHeaderEncoder.ENCODED_LENGTH + ExecutionReportEncoder.BLOCK_LENGTH,
        )
    }

    @Test
    fun `a valid order is forwarded untouched`() {
        assertEquals(
            ClientMessageAction.CONSUME,
            newOrder(participantId = 1, clOrdId = 100, securityId = 1, qty = 10),
        )
        assertEquals(1, sink.toCluster.size)
        assertTrue(sink.toClient.isEmpty())
    }

    @Test
    fun `an out of shard security is rejected without a cluster round trip`() {
        assertEquals(
            ClientMessageAction.CONSUME,
            newOrder(participantId = 1, clOrdId = 100, securityId = 99, qty = 10),
        )

        assertTrue(sink.toCluster.isEmpty())
        val rejection = sink.toClient.single()
        assertEquals("REJECTED", rejection.execType)
        assertEquals(RejectReason.UNKNOWN_SECURITY.value(), rejection.rejectReason)
        assertEquals(1L, service.rejectedLocally)
    }

    @Test
    fun `non positive quantity and price are rejected locally`() {
        newOrder(1, 100, 1, qty = 0)
        newOrder(1, 101, 1, qty = 10, price = 0)
        assertEquals(2, sink.toClient.size)
        assertTrue(sink.toCluster.isEmpty())
    }

    @Test
    fun `a cancel for an out of shard security is rejected locally`() {
        cancelRequest(participantId = 1, clOrdId = 100, securityId = 99)
        assertEquals(RejectReason.UNKNOWN_SECURITY.value(), sink.toClient.single().rejectReason)
    }

    @Test
    fun `operator commands pass through`() {
        val b = UnsafeBuffer(ByteArray(64))
        com.engine.sbe.SessionTransitionEncoder().wrapAndApplyHeader(b, 0, MessageHeaderEncoder())
            .transitionTime(0).tradingDate(20260829).targetPhase(com.engine.sbe.Phase.CONTINUOUS)
        assertEquals(
            ClientMessageAction.CONSUME,
            service.onClientMessage(
                b, 0,
                MessageHeaderEncoder.ENCODED_LENGTH +
                    com.engine.sbe.SessionTransitionEncoder.BLOCK_LENGTH,
            ),
        )
        assertEquals(1, sink.toCluster.size)
    }

    @Test
    fun `the acknowledgement restores origQty and a zero cumQty`() {
        newOrder(participantId = 1, clOrdId = 100, securityId = 1, qty = 10)
        report(1, 100, exchangeOrderId = 500, execType = ExecType.NEW, leavesQty = 10)

        val ack = sink.toClient.single()
        assertEquals("NEW", ack.execType)
        assertEquals(10L, ack.origQty)
        assertEquals(0L, ack.cumQty)
        assertEquals(1, service.liveOrders)
    }

    @Test
    fun `cumQty accumulates across partial fills`() {
        // The whole reason the gateway is stateful: the engine cannot afford origQty in its slot.
        newOrder(participantId = 1, clOrdId = 100, securityId = 1, qty = 10)
        report(1, 100, 500, ExecType.NEW, leavesQty = 10)
        report(1, 100, 500, ExecType.TRADE, lastQty = 3, leavesQty = 7)
        report(1, 100, 500, ExecType.TRADE, lastQty = 2, leavesQty = 5)

        val fills = sink.toClient.filter { it.execType == "TRADE" }
        assertEquals(listOf(3L, 5L), fills.map { it.cumQty })
        assertEquals(listOf(10L, 10L), fills.map { it.origQty })
    }

    @Test
    fun `a fully filled order releases its state`() {
        newOrder(1, 100, 1, qty = 10)
        report(1, 100, 500, ExecType.NEW, leavesQty = 10)
        report(1, 100, 500, ExecType.TRADE, lastQty = 10, leavesQty = 0)

        assertEquals(10L, sink.toClient.last().cumQty)
        assertEquals(0, service.liveOrders)
    }

    @Test
    fun `a cancel releases state and reports the filled portion`() {
        newOrder(1, 100, 1, qty = 10)
        report(1, 100, 500, ExecType.NEW, leavesQty = 10)
        report(1, 100, 500, ExecType.TRADE, lastQty = 4, leavesQty = 6)
        report(1, 101, 500, ExecType.CANCELED, leavesQty = 0)

        val cancel = sink.toClient.last()
        assertEquals("CANCELED", cancel.execType)
        // 4 filled, 6 cancelled. A terminal report carries leavesQty = 0 either way, so cumQty
        // must come from the fills rather than from origQty - leavesQty.
        assertEquals(4L, cancel.cumQty)
        assertEquals(10L, cancel.origQty)
        assertEquals(0, service.liveOrders)
    }

    @Test
    fun `an expiry releases state and reports nothing filled`() {
        newOrder(1, 100, 1, qty = 10)
        report(1, 100, 500, ExecType.NEW, leavesQty = 10)
        report(1, 100, 500, ExecType.EXPIRED, leavesQty = 0)

        assertEquals(0L, sink.toClient.last().cumQty)
        assertEquals(0, service.liveOrders)
    }

    @Test
    fun `an engine rejection discards the pending order`() {
        newOrder(1, 100, 1, qty = 10)
        report(1, 100, 0, ExecType.REJECTED, rejectReason = RejectReason.MARKET_CLOSED)

        assertEquals(0, service.liveOrders)
        assertEquals(0L, service.untrackedReports)
        assertEquals("REJECTED", sink.toClient.last().execType)

        // The discarded order must not later bind to an acknowledgement.
        report(1, 100, 500, ExecType.NEW, leavesQty = 10)
        assertEquals(0, service.liveOrders)
    }

    @Test
    fun `a report for an unknown order is forwarded, counted, and marked unknown`() {
        // The usual cause is a gateway restart, not a fault: the client is still waiting.
        //
        // The quantities are zeros because there is nothing to reconstruct them from -- this
        // gateway holds origQty in memory and the engine does not store it at all -- and the flag
        // is what stops a client reading those zeros as facts. On a half-filled order "cumQty = 0"
        // says nothing has filled, which is both false and indistinguishable from the truth.
        report(1, 100, 999, ExecType.TRADE, lastQty = 5, leavesQty = 5)

        assertEquals(1L, service.untrackedReports)
        val forwarded = sink.toClient.single()
        assertEquals("TRADE", forwarded.execType)
        assertEquals(Enrichment.UNKNOWN, forwarded.enrichment)
        assertEquals(0L, forwarded.origQty)
        assertEquals(0L, forwarded.cumQty)
    }

    @Test
    fun `an order the gateway tracked is marked known`() {
        newOrder(1, 100, 1, qty = 10)
        report(1, 100, 500, ExecType.NEW, leavesQty = 10)
        report(1, 100, 500, ExecType.TRADE, lastQty = 4, leavesQty = 6)

        assertTrue(sink.toClient.all { it.enrichment == Enrichment.KNOWN })
        assertEquals(4L, sink.toClient.last().cumQty)
    }

    @Test
    fun `a locally rejected order is known, not unknown`() {
        // The gateway is rejecting an order it is holding in its hand, so it knows everything it
        // is saying. Marking these UNKNOWN would make the flag mean "a reject happened" and cost
        // it the only meaning worth having.
        newOrder(1, 100, securityId = 99, qty = 10)

        val forwarded = sink.toClient.single()
        assertEquals("REJECTED", forwarded.execType)
        assertEquals(Enrichment.KNOWN, forwarded.enrichment)
        assertEquals(10L, forwarded.origQty)
    }

    @Test
    fun `an unknown order stays unknown as further fills arrive`() {
        // It never re-heals: recordFill ignores an order with no origQty, so a gateway that
        // started accumulating from the fills it happened to see would report a cumQty that is
        // wrong by exactly what it missed -- worse than saying it does not know.
        report(1, 100, 999, ExecType.TRADE, lastQty = 5, leavesQty = 5)
        report(1, 100, 999, ExecType.TRADE, lastQty = 5, leavesQty = 0)

        assertEquals(2, sink.toClient.size)
        assertTrue(sink.toClient.all { it.enrichment == Enrichment.UNKNOWN })
        assertTrue(sink.toClient.all { it.cumQty == 0L })
    }

    @Test
    fun `client order ids are scoped per participant`() {
        // clOrdId is unique per participant, not globally; two participants may reuse one.
        newOrder(participantId = 1, clOrdId = 100, securityId = 1, qty = 10)
        newOrder(participantId = 2, clOrdId = 100, securityId = 1, qty = 25)

        report(1, 100, 500, ExecType.NEW, leavesQty = 10)
        report(2, 100, 501, ExecType.NEW, leavesQty = 25)

        assertEquals(10L, sink.toClient[0].origQty)
        assertEquals(25L, sink.toClient[1].origQty)
        assertEquals(2, service.liveOrders)
    }

    @Test
    fun `a back pressured order is neither sent nor consumed`() {
        // The defect this replaced: the order was dropped and the client told nothing, so it
        // waited forever for an acknowledgement that could never come.
        sink.clusterOffer = ClusterOffer.RETRY
        assertEquals(
            ClientMessageAction.RETRY,
            newOrder(participantId = 1, clOrdId = 100, securityId = 1, qty = 10),
        )

        assertTrue(sink.toCluster.isEmpty())
        assertTrue(sink.toClient.isEmpty(), "backpressure is transient, not a rejection")
    }

    @Test
    fun `a retried order is recorded once, not twice`() {
        // The retry re-delivers the same fragment, so the pending entry must have been unwound.
        // Recording it twice would leave a stale entry that nothing ever releases.
        sink.clusterOffer = ClusterOffer.RETRY
        newOrder(participantId = 1, clOrdId = 100, securityId = 1, qty = 10)
        sink.clusterOffer = ClusterOffer.SENT
        newOrder(participantId = 1, clOrdId = 100, securityId = 1, qty = 10)

        assertEquals(1, sink.toCluster.size)
        report(1, 100, exchangeOrderId = 500, execType = ExecType.NEW, leavesQty = 10)
        assertEquals(1, service.liveOrders)
        assertEquals(10L, sink.toClient.single().origQty)

        // And nothing is left pending under the old key.
        report(1, 100, exchangeOrderId = 501, execType = ExecType.NEW, leavesQty = 10)
        assertEquals(1L, service.untrackedReports)
    }

    @Test
    fun `an unreachable cluster rejects the order back to the client`() {
        sink.clusterOffer = ClusterOffer.FAILED
        assertEquals(
            ClientMessageAction.CONSUME,
            newOrder(participantId = 1, clOrdId = 100, securityId = 1, qty = 10),
        )

        assertTrue(sink.toCluster.isEmpty())
        val rejection = sink.toClient.single()
        assertEquals("REJECTED", rejection.execType)
        assertEquals(RejectReason.GATEWAY_UNAVAILABLE.value(), rejection.rejectReason)
        assertEquals(10L, rejection.origQty)
        assertEquals(1L, service.unreachableRejects)
        // Nothing may be left pending, or liveOrders drifts up for the life of the process.
        assertEquals(0, service.liveOrders)
    }

    @Test
    fun `an unreachable cluster rejects a cancel too`() {
        sink.clusterOffer = ClusterOffer.FAILED
        assertEquals(
            ClientMessageAction.CONSUME,
            cancelRequest(participantId = 1, clOrdId = 100, securityId = 1),
        )
        assertEquals(
            RejectReason.GATEWAY_UNAVAILABLE.value(),
            sink.toClient.single().rejectReason,
        )
    }

    @Test
    fun `a back pressured cancel is retried`() {
        sink.clusterOffer = ClusterOffer.RETRY
        assertEquals(
            ClientMessageAction.RETRY,
            cancelRequest(participantId = 1, clOrdId = 100, securityId = 1),
        )
        assertTrue(sink.toClient.isEmpty())
    }

    @Test
    fun `a back pressured operator command is retried, and a lost one is counted`() {
        val b = UnsafeBuffer(ByteArray(64))
        com.engine.sbe.SessionTransitionEncoder().wrapAndApplyHeader(b, 0, MessageHeaderEncoder())
            .transitionTime(0).tradingDate(20260829).targetPhase(com.engine.sbe.Phase.CONTINUOUS)
        val length = MessageHeaderEncoder.ENCODED_LENGTH +
            com.engine.sbe.SessionTransitionEncoder.BLOCK_LENGTH

        sink.clusterOffer = ClusterOffer.RETRY
        assertEquals(ClientMessageAction.RETRY, service.onClientMessage(b, 0, length))

        // No client session to reject to, so the only honest thing is to count it.
        sink.clusterOffer = ClusterOffer.FAILED
        assertEquals(ClientMessageAction.CONSUME, service.onClientMessage(b, 0, length))
        assertEquals(1L, service.undeliverableCommands)
        assertTrue(sink.toClient.isEmpty())
    }

    @Test
    fun `a truncated message is ignored`() {
        assertEquals(ClientMessageAction.CONSUME, service.onClientMessage(buffer, 0, 2))
        assertTrue(sink.toCluster.isEmpty())
    }
}
