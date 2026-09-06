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

/** ExecutionReport's blockLength before origQty and cumQty were added. */
private const val VERSION_2_BLOCK_LENGTH = 64

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
        origQty: Long = 0,
        cumQty: Long = 0,
    ) {
        val b = UnsafeBuffer(ByteArray(512))
        ExecutionReportEncoder().wrapAndApplyHeader(b, 0, MessageHeaderEncoder())
            .participantId(participantId).clOrdId(clOrdId).exchangeOrderId(exchangeOrderId)
            .price(100).lastQty(lastQty).leavesQty(leavesQty).securityId(1)
            .rejectReason(rejectReason).execType(execType).side(Side.BUY)
            .origQty(origQty).cumQty(cumQty)
        service.onExecutionReport(
            b, 0, MessageHeaderEncoder.ENCODED_LENGTH + ExecutionReportEncoder.BLOCK_LENGTH,
        )
    }

    /**
     * A report from an engine built before schema version 3, which carried neither quantity. The
     * body is identical; only the header says how much of it to believe.
     */
    private fun versionTwoReport(
        participantId: Long,
        clOrdId: Long,
        exchangeOrderId: Long,
        execType: ExecType,
        lastQty: Long = 0,
        leavesQty: Long = 0,
    ) {
        val b = UnsafeBuffer(ByteArray(512))
        val header = MessageHeaderEncoder()
        ExecutionReportEncoder().wrapAndApplyHeader(b, 0, header)
            .participantId(participantId).clOrdId(clOrdId).exchangeOrderId(exchangeOrderId)
            .price(100).lastQty(lastQty).leavesQty(leavesQty).securityId(1)
            .rejectReason(RejectReason.NONE).execType(execType).side(Side.BUY)
        header.wrap(b, 0).blockLength(VERSION_2_BLOCK_LENGTH).version(2)
            .templateId(ExecutionReportEncoder.TEMPLATE_ID)
            .schemaId(ExecutionReportEncoder.SCHEMA_ID)
        service.onExecutionReport(
            b, 0, MessageHeaderEncoder.ENCODED_LENGTH + VERSION_2_BLOCK_LENGTH,
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
    fun `the engine's quantities are carried through untouched`() {
        newOrder(participantId = 1, clOrdId = 100, securityId = 1, qty = 10)
        report(1, 100, 500, ExecType.NEW, leavesQty = 10, origQty = 10, cumQty = 0)

        val ack = sink.toClient.single()
        assertEquals("NEW", ack.execType)
        assertEquals(10L, ack.origQty)
        assertEquals(0L, ack.cumQty)
        assertEquals(Enrichment.KNOWN, ack.enrichment)
    }

    @Test
    fun `a gateway that never saw the order still reports its quantities`() {
        // The point of moving origQty into the engine: this gateway has no record of order 500
        // and needs none. Before, this was the restart case that reported UNKNOWN.
        report(1, 100, 500, ExecType.TRADE, lastQty = 3, leavesQty = 7, origQty = 10, cumQty = 3)

        val fill = sink.toClient.single()
        assertEquals(10L, fill.origQty)
        assertEquals(3L, fill.cumQty)
        assertEquals(Enrichment.KNOWN, fill.enrichment)
        assertEquals(0L, service.untrackedReports)
    }

    @Test
    fun `a cancel reports the filled portion, not everything`() {
        // A terminal report carries leavesQty = 0 whether the order filled or was cancelled, so
        // origQty - leavesQty would say "fully filled". The engine states cumQty instead, and the
        // gateway must not recompute it.
        report(1, 101, 500, ExecType.CANCELED, leavesQty = 0, origQty = 10, cumQty = 4)

        val cancel = sink.toClient.single()
        assertEquals("CANCELED", cancel.execType)
        assertEquals(4L, cancel.cumQty)
        assertEquals(10L, cancel.origQty)
    }

    @Test
    fun `an engine rejection is forwarded and is not counted as unknown`() {
        report(1, 100, 0, ExecType.REJECTED, rejectReason = RejectReason.MARKET_CLOSED,
            origQty = 10)

        assertEquals(0L, service.untrackedReports)
        val rejection = sink.toClient.single()
        assertEquals("REJECTED", rejection.execType)
        assertEquals(Enrichment.KNOWN, rejection.enrichment)
    }

    @Test
    fun `a rejected cancel has no quantity to know and is still marked known`() {
        // The engine cannot state an origQty for an order it never accepted. That is not a loss
        // of state, so it must not read as one.
        report(1, 100, 0, ExecType.REJECTED, rejectReason = RejectReason.UNKNOWN_ORDER)

        assertEquals(0L, service.untrackedReports)
        assertEquals(Enrichment.KNOWN, sink.toClient.single().enrichment)
    }

    @Test
    fun `a report whose origQty the engine could not state is marked unknown`() {
        // The remaining case: an order restored from a version 2 snapshot, which predates the
        // engine holding origQty. The zeros are not facts and the flag is what says so -- on a
        // half-filled order "cumQty = 0" is both false and indistinguishable from the truth.
        report(1, 100, 999, ExecType.TRADE, lastQty = 5, leavesQty = 5, origQty = 0)

        assertEquals(1L, service.untrackedReports)
        val forwarded = sink.toClient.single()
        assertEquals("TRADE", forwarded.execType)
        assertEquals(Enrichment.UNKNOWN, forwarded.enrichment)
        assertEquals(0L, forwarded.origQty)
        assertEquals(0L, forwarded.cumQty)
    }

    @Test
    fun `a report from an engine older than schema version 3 is marked unknown`() {
        // The field is absent, not zero, and the two must reach the same honest answer.
        versionTwoReport(1, 100, 500, ExecType.TRADE, lastQty = 4, leavesQty = 6)

        val fill = sink.toClient.single()
        assertEquals(Enrichment.UNKNOWN, fill.enrichment)
        assertEquals(0L, fill.origQty)
        assertEquals(0L, fill.cumQty)
        assertEquals(1L, service.untrackedReports)
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
        // It cannot re-heal: nothing downstream of the engine can recover a quantity the engine
        // itself does not have, and a gateway accumulating from the fills it happened to see
        // would be wrong by exactly what it missed -- worse than saying it does not know.
        report(1, 100, 999, ExecType.TRADE, lastQty = 5, leavesQty = 5, origQty = 0)
        report(1, 100, 999, ExecType.TRADE, lastQty = 5, leavesQty = 0, origQty = 0)

        assertEquals(2, sink.toClient.size)
        assertTrue(sink.toClient.all { it.enrichment == Enrichment.UNKNOWN })
        assertTrue(sink.toClient.all { it.cumQty == 0L })
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
    fun `a retried order is forwarded once, not twice`() {
        // The retry re-delivers the same fragment. Nothing is recorded either way -- which is the
        // point of holding no state: there is no pending entry to unwind or to leak.
        sink.clusterOffer = ClusterOffer.RETRY
        newOrder(participantId = 1, clOrdId = 100, securityId = 1, qty = 10)
        sink.clusterOffer = ClusterOffer.SENT
        newOrder(participantId = 1, clOrdId = 100, securityId = 1, qty = 10)

        assertEquals(1, sink.toCluster.size)
        assertTrue(sink.toClient.isEmpty())
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
