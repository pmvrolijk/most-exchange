package nl.lamia.most.exchange.gateway

import nl.lamia.most.exchange.reference.GatewayIdentity
import nl.lamia.most.exchange.reference.ParticipantRegistry
import nl.lamia.most.exchange.sbe.ExecType
import nl.lamia.most.exchange.sbe.ExecutionReportEncoder
import nl.lamia.most.exchange.sbe.MessageHeaderEncoder
import nl.lamia.most.exchange.sbe.OrderMassStatusCompleteEncoder
import nl.lamia.most.exchange.sbe.OrderMassStatusRequestEncoder
import nl.lamia.most.exchange.sbe.RejectReason
import nl.lamia.most.exchange.sbe.RequestStatus
import nl.lamia.most.exchange.sbe.Side
import org.agrona.concurrent.UnsafeBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The gateway's half of Design.md §5, "Order mass status". It checks a request as it checks a cancel
 * and forwards it untouched. It answers every request it does not forward. It translates each
 * `ORDER_STATUS` report like any other, with `reportSeq` 0, and passes the completion across
 * unchanged.
 */
class GatewayMassStatusTest {

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

    private fun statusRequest(participantId: Long, requestId: Long = 31L, securityId: Int = -1): ClientMessageAction {
        OrderMassStatusRequestEncoder().wrapAndApplyHeader(buffer, 0, headerEncoder)
            .participantId(participantId).requestId(requestId).securityId(securityId)
        return service.onClientMessage(
            buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + OrderMassStatusRequestEncoder.BLOCK_LENGTH,
        )
    }

    @Test
    fun `a participant's request is forwarded untouched, and a cancel only one's too`() {
        assertEquals(ClientMessageAction.CONSUME, statusRequest(7L))
        assertEquals(ClientMessageAction.CONSUME, statusRequest(9L))
        assertEquals(2, sink.toCluster.size)
        assertTrue(sink.statuses.isEmpty(), "the engine answers a forwarded request, not the gateway")
    }

    @Test
    fun `a participant this gateway does not serve is refused with an answer, and nothing is sent`() {
        statusRequest(14L, requestId = 5L, securityId = 2)

        assertTrue(sink.toCluster.isEmpty())
        assertEquals(
            StatusCompletion(14L, 5L, nextSeq = 0L, orderCount = 0, securityId = 2, status = RequestStatus.UNAUTHORIZED_PARTICIPANT),
            sink.statuses.single(),
        )
    }

    @Test
    fun `an unreachable cluster answers GATEWAY_UNAVAILABLE, and back pressure holds the request`() {
        sink.clusterOffer = ClusterOffer.RETRY
        assertEquals(ClientMessageAction.RETRY, statusRequest(7L))
        assertTrue(sink.statuses.isEmpty())

        sink.clusterOffer = ClusterOffer.FAILED
        assertEquals(ClientMessageAction.CONSUME, statusRequest(7L))
        assertEquals(RequestStatus.GATEWAY_UNAVAILABLE, sink.statuses.single().status)
    }

    @Test
    fun `an order status is translated like any report, outside the sequence`() {
        val b = UnsafeBuffer(ByteArray(512))
        ExecutionReportEncoder().wrapAndApplyHeader(b, 0, MessageHeaderEncoder())
            .participantId(7).clOrdId(1001).exchangeOrderId(3).price(101).lastQty(0).leavesQty(6)
            .securityId(1).rejectReason(RejectReason.NONE).execType(ExecType.ORDER_STATUS).side(Side.SELL)
            .origQty(10).cumQty(4).reportSeq(0)
        service.onExecutionReport(b, 0, MessageHeaderEncoder.ENCODED_LENGTH + ExecutionReportEncoder.BLOCK_LENGTH)

        val status = sink.toClient.single()
        assertEquals("ORDER_STATUS", status.execType)
        assertEquals(10L, status.origQty)
        assertEquals(4L, status.cumQty)
        assertEquals(6L, status.leavesQty)
        assertEquals(0L, status.reportSeq)
        assertEquals(0L, service.untrackedReports)
    }

    @Test
    fun `the engine's completion reaches the client unchanged`() {
        val b = UnsafeBuffer(ByteArray(512))
        OrderMassStatusCompleteEncoder().wrapAndApplyHeader(b, 0, MessageHeaderEncoder())
            .participantId(7).requestId(31).nextSeq(42).orderCount(3).securityId(-1).status(RequestStatus.COMPLETE)
        service.onExecutionReport(b, 0, MessageHeaderEncoder.ENCODED_LENGTH + OrderMassStatusCompleteEncoder.BLOCK_LENGTH)

        assertEquals(StatusCompletion(7L, 31L, 42L, 3, -1, RequestStatus.COMPLETE), sink.statuses.single())
        assertTrue(sink.toClient.isEmpty(), "a completion is not an execution report")
    }
}
