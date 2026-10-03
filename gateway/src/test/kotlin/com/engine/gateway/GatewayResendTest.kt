package com.engine.gateway

import com.engine.reference.GatewayIdentity
import com.engine.reference.ParticipantRegistry
import com.engine.sbe.ExecType
import com.engine.sbe.ExecutionReportEncoder
import com.engine.sbe.MessageHeaderEncoder
import com.engine.sbe.NewOrderSingleEncoder
import com.engine.sbe.RejectReason
import com.engine.sbe.ReportResendCompleteEncoder
import com.engine.sbe.ReportResendRequestEncoder
import com.engine.sbe.RequestStatus
import com.engine.sbe.Side
import com.engine.sbe.SmpStrategy
import org.agrona.concurrent.UnsafeBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The gateway's half of Design.md §5, "Report sequence and resend". It copies the engine's
 * `reportSeq` and never makes one up. It checks a resend request as it checks a cancel and forwards
 * it untouched. It answers every request it does not forward, so a client never waits on one that
 * went nowhere. And it passes the engine's completion across unchanged. Expected values come from
 * that clause.
 */
class GatewayResendTest {

    private val sink = RecordingSink()
    private val buffer = UnsafeBuffer(ByteArray(512))
    private val headerEncoder = MessageHeaderEncoder()

    /** gw-a places for 7 and may only cancel for 9; 14 belongs to another gateway. */
    private val registry = ParticipantRegistry(
        0,
        listOf(
            GatewayIdentity("gw-a", ParticipantRegistry.sha256Hex("gw-a"), listOf(7L), cancelOnly = listOf(9L)),
            GatewayIdentity("gw-b", ParticipantRegistry.sha256Hex("gw-b"), listOf(14L)),
        ),
    )
    private val service = GatewayService(intArrayOf(1, 2), sink, access = RegistryAccess({ registry }, "gw-a"))

    private fun resendRequest(participantId: Long, requestId: Long = 55L, fromSeq: Long = 12L): ClientMessageAction {
        ReportResendRequestEncoder().wrapAndApplyHeader(buffer, 0, headerEncoder)
            .participantId(participantId).requestId(requestId).fromSeq(fromSeq)
        return service.onClientMessage(
            buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + ReportResendRequestEncoder.BLOCK_LENGTH,
        )
    }

    private fun engineReport(reportSeq: Long?) {
        val b = UnsafeBuffer(ByteArray(512))
        val header = MessageHeaderEncoder()
        val encoder = ExecutionReportEncoder().wrapAndApplyHeader(b, 0, header)
            .participantId(7).clOrdId(1001).exchangeOrderId(3).price(100).lastQty(4).leavesQty(6)
            .securityId(1).rejectReason(RejectReason.NONE).execType(ExecType.TRADE).side(Side.SELL)
            .origQty(10).cumQty(4)
        val blockLength = if (reportSeq == null) {
            // An engine older than version 6: the same body, a header that says it ends at cumQty.
            header.wrap(b, 0).blockLength(VERSION_5_BLOCK_LENGTH).version(5)
                .templateId(ExecutionReportEncoder.TEMPLATE_ID).schemaId(ExecutionReportEncoder.SCHEMA_ID)
            VERSION_5_BLOCK_LENGTH
        } else {
            encoder.reportSeq(reportSeq)
            ExecutionReportEncoder.BLOCK_LENGTH
        }
        service.onExecutionReport(b, 0, MessageHeaderEncoder.ENCODED_LENGTH + blockLength)
    }

    // --------------------------------------------------------------------------------- reportSeq

    @Test
    fun `the engine's reportSeq reaches the client unchanged`() {
        engineReport(reportSeq = 41L)
        assertEquals(41L, sink.toClient.single().reportSeq)
    }

    @Test
    fun `a report from an engine older than version 6 carries reportSeq 0, not sequenced`() {
        engineReport(reportSeq = null)
        assertEquals(0L, sink.toClient.single().reportSeq)
        assertEquals(4L, sink.toClient.single().cumQty, "the fields before reportSeq still decode")
    }

    @Test
    fun `a report the gateway makes itself is outside the sequence`() {
        NewOrderSingleEncoder().wrapAndApplyHeader(buffer, 0, headerEncoder)
            .participantId(7).clOrdId(1).price(100).qty(10).smpId(0).securityId(99).expireDate(0)
            .side(Side.BUY).smpStrategy(SmpStrategy.CANCEL_AGGRESSOR)
        service.onClientMessage(buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + NewOrderSingleEncoder.BLOCK_LENGTH)

        val reject = sink.toClient.single()
        assertEquals("REJECTED", reject.execType)
        assertEquals(0L, reject.reportSeq)
    }

    // ------------------------------------------------------------------------- the resend request

    @Test
    fun `a participant's resend request is forwarded untouched`() {
        assertEquals(ClientMessageAction.CONSUME, resendRequest(7L))
        assertEquals(listOf(MessageHeaderEncoder.ENCODED_LENGTH + ReportResendRequestEncoder.BLOCK_LENGTH), sink.toCluster)
        assertTrue(sink.resends.isEmpty(), "the engine answers a forwarded request, not the gateway")
    }

    @Test
    fun `a cancel only participant may still ask for its reports`() {
        resendRequest(9L)
        assertEquals(1, sink.toCluster.size)
    }

    @Test
    fun `a participant this gateway does not serve is refused with an answer, and nothing is sent`() {
        assertEquals(ClientMessageAction.CONSUME, resendRequest(14L, requestId = 77L, fromSeq = 5L))

        assertTrue(sink.toCluster.isEmpty())
        assertEquals(
            ResendCompletion(14L, 77L, 5L, nextSeq = 0L, oldestRetainedSeq = 0L, replayedCount = 0,
                status = RequestStatus.UNAUTHORIZED_PARTICIPANT),
            sink.resends.single(),
        )
        assertEquals(1L, service.unauthorizedRejects)
    }

    @Test
    fun `an unreachable cluster answers GATEWAY_UNAVAILABLE`() {
        sink.clusterOffer = ClusterOffer.FAILED
        assertEquals(ClientMessageAction.CONSUME, resendRequest(7L, requestId = 78L))

        val answer = sink.resends.single()
        assertEquals(RequestStatus.GATEWAY_UNAVAILABLE, answer.status)
        assertEquals(78L, answer.requestId)
        assertEquals(0, answer.replayedCount)
    }

    @Test
    fun `a back pressured request is neither sent nor consumed nor answered`() {
        sink.clusterOffer = ClusterOffer.RETRY
        assertEquals(ClientMessageAction.RETRY, resendRequest(7L))
        assertTrue(sink.toCluster.isEmpty())
        assertTrue(sink.resends.isEmpty())
    }

    // ------------------------------------------------------------------------- the engine's answer

    @Test
    fun `the engine's completion reaches the client unchanged`() {
        val b = UnsafeBuffer(ByteArray(512))
        ReportResendCompleteEncoder().wrapAndApplyHeader(b, 0, MessageHeaderEncoder())
            .participantId(7).requestId(55).fromSeq(12).nextSeq(30).oldestRetainedSeq(3)
            .replayedCount(18).status(RequestStatus.COMPLETE)
        service.onExecutionReport(b, 0, MessageHeaderEncoder.ENCODED_LENGTH + ReportResendCompleteEncoder.BLOCK_LENGTH)

        assertEquals(ResendCompletion(7L, 55L, 12L, 30L, 3L, 18, RequestStatus.COMPLETE), sink.resends.single())
        assertTrue(sink.toClient.isEmpty(), "a completion is not an execution report")
    }

    private companion object {
        /** ExecutionReport's blockLength before reportSeq was added. */
        const val VERSION_5_BLOCK_LENGTH = 80
    }
}
