package com.engine.gateway

import com.engine.reference.GatewayIdentity
import com.engine.reference.ParticipantRegistry
import com.engine.sbe.MessageHeaderEncoder
import com.engine.sbe.NewOrderSingleEncoder
import com.engine.sbe.OrderCancelRequestEncoder
import com.engine.sbe.Phase
import com.engine.sbe.RejectReason
import com.engine.sbe.SessionTransitionEncoder
import com.engine.sbe.Side
import com.engine.sbe.SmpStrategy
import org.agrona.DirectBuffer
import org.agrona.concurrent.UnsafeBuffer
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Design.md §1, "Enforcement, at the gateway". Every expectation here is read off that clause:
 *
 *  - `participants` may place and cancel;
 *  - `cancelOnly` may cancel and may not place;
 *  - `operator` may send operator commands, and a gateway that is not one consumes and counts them;
 *  - a refusal is a local `REJECTED` / `UNAUTHORIZED_PARTICIPANT`, and never reaches the cluster;
 *  - the gateway re-reads the registry while it runs, so revocation needs no restart.
 */
class GatewayEnforcementTest {

    private val sink = RecordingSink()
    private val buffer = UnsafeBuffer(ByteArray(512))
    private val headerEncoder = MessageHeaderEncoder()
    // Held rather than made per message, so the allocation test measures the gateway, not this file.
    private val orderEncoder = NewOrderSingleEncoder()
    private val cancelEncoder = OrderCancelRequestEncoder()
    private val transitionEncoder = SessionTransitionEncoder()

    private fun identity(
        id: String,
        participants: List<Long>,
        cancelOnly: List<Long> = emptyList(),
        operator: Boolean = false,
    ) = GatewayIdentity(id, ParticipantRegistry.sha256Hex(id), participants, cancelOnly, operator)

    /** gw-a: places for 7 and 8, cancel-only for 9. gw-b: places for 14. control: operator only. */
    private fun registry(vararg gateways: GatewayIdentity = DEFAULT) =
        ParticipantRegistry(0, gateways.toList())

    private var inForce = registry()

    private fun service(gatewayId: String = "gw-a", target: GatewaySink = sink) =
        GatewayService(intArrayOf(1, 2), target, access = RegistryAccess({ inForce }, gatewayId))

    private fun GatewayService.order(participantId: Long, securityId: Int = 1): ClientMessageAction {
        orderEncoder.wrapAndApplyHeader(buffer, 0, headerEncoder)
            .participantId(participantId).clOrdId(100).price(100).qty(10).smpId(0)
            .securityId(securityId).expireDate(0).side(Side.BUY).smpStrategy(SmpStrategy.CANCEL_AGGRESSOR)
        return onClientMessage(buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + NewOrderSingleEncoder.BLOCK_LENGTH)
    }

    private fun GatewayService.cancel(participantId: Long): ClientMessageAction {
        cancelEncoder.wrapAndApplyHeader(buffer, 0, headerEncoder)
            .participantId(participantId).origClOrdId(100).clOrdId(101)
            .exchangeOrderId(1).securityId(1).side(Side.BUY)
        return onClientMessage(
            buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + OrderCancelRequestEncoder.BLOCK_LENGTH,
        )
    }

    private fun GatewayService.operatorCommand(): ClientMessageAction {
        transitionEncoder.wrapAndApplyHeader(buffer, 0, headerEncoder)
            .transitionTime(0).tradingDate(20260926).targetPhase(Phase.CONTINUOUS)
        return onClientMessage(
            buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + SessionTransitionEncoder.BLOCK_LENGTH,
        )
    }

    private fun assertRejectedUnauthorized(participantId: Long) {
        val report = sink.toClient.last()
        assertEquals("REJECTED", report.execType)
        assertEquals(RejectReason.UNAUTHORIZED_PARTICIPANT.value(), report.rejectReason)
        assertEquals(participantId, report.participantId)
    }

    // ------------------------------------------------------------------------------ participants

    @Test
    fun `a listed participant's order and cancel are forwarded`() {
        val gateway = service()

        assertEquals(ClientMessageAction.CONSUME, gateway.order(7))
        assertEquals(ClientMessageAction.CONSUME, gateway.cancel(7))

        assertEquals(2, sink.toCluster.size)
        assertTrue(sink.toClient.isEmpty())
        assertEquals(0L, gateway.unauthorizedRejects)
    }

    @Test
    fun `an order for a participant this gateway does not list is rejected locally and never sent`() {
        val gateway = service()

        assertEquals(ClientMessageAction.CONSUME, gateway.order(14))

        assertTrue(sink.toCluster.isEmpty())
        assertRejectedUnauthorized(14)
        assertEquals(10L, sink.toClient.single().origQty)
        assertEquals(1L, gateway.unauthorizedRejects)
    }

    /**
     * Not optional (Design.md §1): the engine's cancel check is participant equality, so a cancel
     * that claimed someone else's participant id would otherwise withdraw their order.
     */
    @Test
    fun `a cancel for a participant this gateway does not list is rejected locally and never sent`() {
        val gateway = service()

        gateway.cancel(14)

        assertTrue(sink.toCluster.isEmpty())
        assertRejectedUnauthorized(14)
        assertEquals(1L, gateway.unauthorizedRejects)
    }

    /** The participant check comes first: an unlisted participant learns nothing about the shard. */
    @Test
    fun `an unlisted participant is refused as unauthorized even for an unknown security`() {
        service().order(14, securityId = 99)

        assertRejectedUnauthorized(14)
    }

    // ------------------------------------------------------------------------------ cancel only

    @Test
    fun `a cancel only participant may cancel and may not place`() {
        val gateway = service()

        gateway.order(9)
        assertTrue(sink.toCluster.isEmpty())
        assertRejectedUnauthorized(9)

        gateway.cancel(9)
        assertEquals(1, sink.toCluster.size)
        assertEquals(1, sink.toClient.size)
    }

    // --------------------------------------------------------------------------------- operator

    @Test
    fun `a gateway that is not an operator consumes an operator command, counts it, and sends nothing`() {
        val gateway = service("gw-a")

        assertEquals(ClientMessageAction.CONSUME, gateway.operatorCommand())

        assertTrue(sink.toCluster.isEmpty())
        assertTrue(sink.toClient.isEmpty())
        assertEquals(1L, gateway.refusedCommands)
    }

    @Test
    fun `a participant's own gateway may not bulk cancel, even for that participant`() {
        // Design.md §4.8: only an operator gateway forwards it. gw-a places for 7 and still may not
        // withdraw 7's whole book in one message.
        val gateway = service("gw-a")
        val length = com.engine.reference.OperatorCommands.encodeCancelParticipantOrders(buffer, participantId = 7L)

        assertEquals(ClientMessageAction.CONSUME, gateway.onClientMessage(buffer, 0, length))

        assertTrue(sink.toCluster.isEmpty())
        assertEquals(1L, gateway.refusedCommands)
    }

    @Test
    fun `an operator gateway forwards a bulk cancel untouched`() {
        val gateway = service("control")
        val length = com.engine.reference.OperatorCommands.encodeCancelParticipantOrders(buffer, participantId = 7L)

        gateway.onClientMessage(buffer, 0, length)

        assertEquals(1, sink.toCluster.size)
        assertEquals(0L, gateway.refusedCommands)
    }

    @Test
    fun `an operator gateway forwards operator commands`() {
        val gateway = service("control")

        gateway.operatorCommand()

        assertEquals(1, sink.toCluster.size)
        assertEquals(0L, gateway.refusedCommands)
    }

    @Test
    fun `an operator only identity may neither place nor cancel`() {
        val gateway = service("control")

        gateway.order(7)
        gateway.cancel(7)

        assertTrue(sink.toCluster.isEmpty())
        assertEquals(2L, gateway.unauthorizedRejects)
    }

    // ------------------------------------------------------------------------------ hot reload

    @Test
    fun `a participant moved to cancel only by a reload can no longer place but can still cancel`() {
        val gateway = service()
        gateway.order(7)
        assertEquals(1, sink.toCluster.size)

        inForce = registry(identity("gw-a", listOf(8L), cancelOnly = listOf(7L, 9L)), GW_B, CONTROL)

        gateway.order(7)
        assertEquals(1, sink.toCluster.size)
        assertRejectedUnauthorized(7)
        gateway.cancel(7)
        assertEquals(2, sink.toCluster.size)
    }

    @Test
    fun `a participant added by a reload may place at once`() {
        val gateway = service()
        gateway.order(15)
        assertTrue(sink.toCluster.isEmpty())

        inForce = registry(identity("gw-a", listOf(7L, 8L, 15L), cancelOnly = listOf(9L)), GW_B, CONTROL)

        gateway.order(15)
        assertEquals(1, sink.toCluster.size)
    }

    /**
     * A gateway removed from the registry places nothing and sends no operator command, but lets
     * the participants it listed withdraw what is resting -- the cluster refuses the gateway itself
     * on its next connect anyway.
     */
    @Test
    fun `a gateway dropped from the registry places nothing and still passes the cancels it allowed`() {
        inForce = registry(
            identity("gw-a", listOf(7L, 8L), cancelOnly = listOf(9L), operator = true), GW_B, CONTROL,
        )
        val gateway = service()

        inForce = registry(GW_B, CONTROL)

        gateway.order(7)
        assertRejectedUnauthorized(7)
        gateway.operatorCommand()
        assertEquals(1L, gateway.refusedCommands)
        gateway.cancel(7)
        gateway.cancel(9)
        assertEquals(2, sink.toCluster.size)
        gateway.cancel(14)
        assertRejectedUnauthorized(14)
    }

    // -------------------------------------------------------------------------------- no registry

    /** Design.md §1: the registry is optional, and a gateway without an identity enforces nothing. */
    @Test
    fun `a gateway without an identity enforces nothing`() {
        val gateway = GatewayService(intArrayOf(1, 2), sink)

        gateway.order(14)
        gateway.cancel(14)
        gateway.operatorCommand()

        assertEquals(3, sink.toCluster.size)
        assertTrue(sink.toClient.isEmpty())
    }

    // -------------------------------------------------------------------------------- allocation

    /**
     * The check runs on every message, on the thread that also drives ingress and keepalives, so it
     * must allocate nothing: a forwarded order, a refused one, a cancel-only cancel and a refused
     * operator command, repeated. A registry swap in the middle is included, since the gateway
     * re-resolves its entry then and must still not allocate per message afterwards.
     */
    @Test
    fun `enforcement allocates nothing per message`() {
        val gateway = service(target = NullSink)
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val thread = Thread.currentThread().threadId()

        fun round(n: Int) = repeat(n) {
            gateway.order(7)
            gateway.order(14)
            gateway.cancel(9)
            gateway.operatorCommand()
        }

        round(WARMUP)
        inForce = registry(identity("gw-a", listOf(7L), cancelOnly = listOf(9L)), GW_B, CONTROL)
        round(1) // the one re-resolution, outside the window

        val zeroWindows = (1..WINDOWS).count {
            val before = threads.getThreadAllocatedBytes(thread)
            round(PER_WINDOW)
            threads.getThreadAllocatedBytes(thread) - before == 0L
        }
        assertTrue(zeroWindows > WINDOWS / 2, "only $zeroWindows of $WINDOWS windows read zero bytes")
    }

    private object NullSink : GatewaySink {
        override fun toCluster(buffer: DirectBuffer, offset: Int, length: Int) = ClusterOffer.SENT
        override fun toClient(buffer: DirectBuffer, offset: Int, length: Int) = Unit
    }

    private companion object {
        val GW_B = GatewayIdentity("gw-b", ParticipantRegistry.sha256Hex("gw-b"), listOf(14L))
        val CONTROL = GatewayIdentity(
            "control", ParticipantRegistry.sha256Hex("control"), emptyList(), operator = true,
        )
        val DEFAULT = arrayOf(
            GatewayIdentity("gw-a", ParticipantRegistry.sha256Hex("gw-a"), listOf(7L, 8L), listOf(9L)),
            GW_B,
            CONTROL,
        )

        const val WARMUP = 50_000
        const val WINDOWS = 8
        const val PER_WINDOW = 10_000
    }
}
