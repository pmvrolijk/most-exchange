package nl.lamia.most.exchange.reference

import nl.lamia.most.exchange.sbe.MessageHeaderDecoder
import nl.lamia.most.exchange.sbe.Phase
import nl.lamia.most.exchange.sbe.PurgeExpiredOrdersDecoder
import nl.lamia.most.exchange.sbe.CancelParticipantOrdersDecoder
import nl.lamia.most.exchange.sbe.SecurityDefinitionDecoder
import nl.lamia.most.exchange.sbe.SessionTransitionDecoder
import org.agrona.concurrent.UnsafeBuffer
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Encode and decode with the generated codecs, because the only thing that matters about these is
 * that the engine reads back exactly what was written. Two senders — the CLI and the control plane
 * — share this encoding precisely so there is one thing to get right.
 */
class OperatorCommandsTest {

    private val buffer = UnsafeBuffer(ByteArray(256))
    private val header = MessageHeaderDecoder()

    private fun decodeHeader(length: Int): MessageHeaderDecoder {
        check(length > MessageHeaderDecoder.ENCODED_LENGTH) { "nothing was encoded" }
        return header.wrap(buffer, 0)
    }

    @Test
    fun `a security definition round trips every field`() {
        val length = OperatorCommands.encodeSecurityDefinition(
            buffer,
            securityId = 7,
            referencePrice = 10_000_000_000L,
            priceFloor = 500_000L,
            tickSize = 1_000_000L,
            levelCount = 32_768,
            staticCollarBps = 5_000,
            dynamicCollarBps = 2_000,
        )
        decodeHeader(length)
        assertEquals(SecurityDefinitionDecoder.TEMPLATE_ID, header.templateId())

        val decoder = SecurityDefinitionDecoder().wrap(
            buffer, MessageHeaderDecoder.ENCODED_LENGTH, header.blockLength(), header.version(),
        )
        assertEquals(7, decoder.securityId())
        assertEquals(10_000_000_000L, decoder.referencePrice())
        assertEquals(500_000L, decoder.priceFloor())
        assertEquals(1_000_000L, decoder.tickSize())
        assertEquals(32_768, decoder.levelCount())
        assertEquals(5_000, decoder.staticCollarBps())
        assertEquals(2_000, decoder.dynamicCollarBps())
        assertEquals(
            MessageHeaderDecoder.ENCODED_LENGTH + SecurityDefinitionDecoder.BLOCK_LENGTH,
            length,
        )
    }

    @Test
    fun `a session transition carries the phase and the trading date`() {
        val length =
            OperatorCommands.encodeSessionTransition(buffer, Phase.OPEN_AUCTION.value(), 20260830)
        decodeHeader(length)
        assertEquals(SessionTransitionDecoder.TEMPLATE_ID, header.templateId())

        val decoder = SessionTransitionDecoder().wrap(
            buffer, MessageHeaderDecoder.ENCODED_LENGTH, header.blockLength(), header.version(),
        )
        assertEquals(Phase.OPEN_AUCTION, decoder.targetPhase())
        assertEquals(20260830, decoder.tradingDate())
        // Zero, and the engine ignores it: time comes from the sequenced consensus timestamp, never
        // from a field a client filled in.
        assertEquals(0L, decoder.transitionTime())
    }

    @Test
    fun `a purge carries the trading date it sweeps against`() {
        val length = OperatorCommands.encodePurgeExpiredOrders(buffer, 20260830)
        decodeHeader(length)
        assertEquals(PurgeExpiredOrdersDecoder.TEMPLATE_ID, header.templateId())

        val decoder = PurgeExpiredOrdersDecoder().wrap(
            buffer, MessageHeaderDecoder.ENCODED_LENGTH, header.blockLength(), header.version(),
        )
        assertEquals(20260830, decoder.tradingDate())
        assertEquals(0L, decoder.purgeTime())
    }

    @Test
    fun `a bulk cancel names the participant and the security`() {
        val length = OperatorCommands.encodeCancelParticipantOrders(buffer, participantId = 42L, securityId = 7)
        decodeHeader(length)
        assertEquals(CancelParticipantOrdersDecoder.TEMPLATE_ID, header.templateId())

        val decoder = CancelParticipantOrdersDecoder().wrap(
            buffer, MessageHeaderDecoder.ENCODED_LENGTH, header.blockLength(), header.version(),
        )
        assertEquals(42L, decoder.participantId())
        assertEquals(7, decoder.securityId())
    }

    @Test
    fun `a bulk cancel with no security covers the whole shard, which is not security 0`() {
        // Design.md §4.8: -1 means every book, because 0 is a legal security id.
        val length = OperatorCommands.encodeCancelParticipantOrders(buffer, participantId = 42L)
        decodeHeader(length)
        val decoder = CancelParticipantOrdersDecoder().wrap(
            buffer, MessageHeaderDecoder.ENCODED_LENGTH, header.blockLength(), header.version(),
        )
        assertEquals(-1, decoder.securityId())
        assertEquals(OperatorCommands.ALL_SECURITIES, decoder.securityId())
    }

    @Test
    fun `trading dates are YYYYMMDD`() {
        assertEquals(20260830, OperatorCommands.tradingDateOf(LocalDate.of(2026, 8, 30)))
        assertEquals(20260101, OperatorCommands.tradingDateOf(LocalDate.of(2026, 1, 1)))
        assertEquals(19991231, OperatorCommands.tradingDateOf(LocalDate.of(1999, 12, 31)))
    }

    @Test
    fun `phase names the operator actually types are accepted`() {
        assertEquals(Phase.CLOSED.value(), OperatorCommands.parsePhase("closed"))
        assertEquals(Phase.PRE_OPEN.value(), OperatorCommands.parsePhase("pre-open"))
        assertEquals(Phase.PRE_OPEN.value(), OperatorCommands.parsePhase("PRE_OPEN"))
        assertEquals(Phase.OPEN_AUCTION.value(), OperatorCommands.parsePhase("auction"))
        assertEquals(Phase.CONTINUOUS.value(), OperatorCommands.parsePhase("Continuous"))
        assertFailsWith<IllegalArgumentException> { OperatorCommands.parsePhase("halted") }
    }

    @Test
    fun `the reopen sequence ends in continuous, where the uncross runs`() {
        // The uncross runs only on OPEN_AUCTION to CONTINUOUS. A sequence that skipped the auction
        // would reopen the security without ever crossing the book that accumulated while it was
        // closed.
        assertEquals(
            listOf(Phase.PRE_OPEN.value(), Phase.OPEN_AUCTION.value(), Phase.CONTINUOUS.value()),
            OperatorCommands.REOPEN_SEQUENCE,
        )
    }
}
