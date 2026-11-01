package nl.lamia.most.exchange.core

import nl.lamia.most.exchange.client.ClOrdId
import nl.lamia.most.exchange.sbe.MessageHeaderEncoder
import nl.lamia.most.exchange.sbe.NewOrderSingleEncoder
import org.agrona.MutableDirectBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Design.md §5, "Client order ids". Every expected value is read off that clause:
 *
 * - every report carries `clOrdId` and `origClOrdId`; a participant's cancel is answered with the
 *   cancel's `clOrdId` and the order's `origClOrdId`, its reject with the `origClOrdId` it named;
 *   every other report carries the order's own id in both;
 * - a cancel matches its order only when participant and all 20 bytes of `origClOrdId` match;
 * - the engine refuses a version 7 order or cancel in the log, rather than reading it as version 8.
 */
class ClientOrderIdTest {

    private fun continuous(): Harness {
        val h = Harness(arrayOf(serviceBook(SECURITY)))
        h.defineSecurity(h.books[0], referencePrice = 100)
        h.sessionTransition(Phase.CONTINUOUS)
        return h
    }

    @Test
    fun `a new order's report carries the participant's own id in both fields`() {
        val h = continuous()
        h.newOrder(MAKER, "ORD-2026/11.01:a_1", SECURITY, Side.SELL, price = 100, qty = 5)

        val ack = h.reports.single()
        assertEquals("NEW", ack.execType)
        assertEquals("ORD-2026/11.01:a_1", ack.clOrdIdText)
        assertEquals("ORD-2026/11.01:a_1", ack.origClOrdIdText)
    }

    @Test
    fun `a fill reports each side's own id, the maker's from the book`() {
        val h = continuous()
        h.newOrder(MAKER, "MAKER-1", SECURITY, Side.SELL, price = 100, qty = 5)
        h.newOrder(TAKER, "TAKER-1", SECURITY, Side.BUY, price = 100, qty = 5)

        val trades = h.reports.filter { it.execType == "TRADE" }
        assertEquals(
            listOf("TAKER-1" to "TAKER-1", "MAKER-1" to "MAKER-1"),
            trades.map { it.clOrdIdText to it.origClOrdIdText },
        )
    }

    @Test
    fun `a cancel is answered with its own id and the order's`() {
        val h = continuous()
        h.newOrder(MAKER, "BUY-7", SECURITY, Side.BUY, price = 99, qty = 5)
        h.cancel(MAKER, origClOrdId = "BUY-7", clOrdId = "CXL-7", exchangeOrderId = 1, securityId = SECURITY, side = Side.BUY)

        val cancelled = h.reports.last()
        assertEquals("CANCELED", cancelled.execType)
        assertEquals("CXL-7" to "BUY-7", cancelled.clOrdIdText to cancelled.origClOrdIdText)
        assertEquals(0, h.books[0].restingOrderCount())
    }

    @Test
    fun `a refused cancel names the cancel and the id it named`() {
        val h = continuous()
        h.newOrder(MAKER, "BUY-7", SECURITY, Side.BUY, price = 99, qty = 5)
        h.cancel(MAKER, origClOrdId = "NOT-MINE", clOrdId = "CXL-8", exchangeOrderId = 1, securityId = SECURITY, side = Side.BUY)

        val refused = h.reports.last()
        assertEquals("REJECTED", refused.execType)
        assertEquals(RejectReason.UNAUTHORIZED_PARTICIPANT, refused.rejectReason)
        assertEquals("CXL-8" to "NOT-MINE", refused.clOrdIdText to refused.origClOrdIdText)
        assertEquals(1, h.books[0].restingOrderCount())
    }

    @Test
    fun `a cancel matches its order on all 20 bytes of the id`() {
        val h = continuous()
        h.newOrder(MAKER, "ABCDEFGHIJKLMNOPQRST", SECURITY, Side.BUY, price = 99, qty = 5)
        h.cancel(MAKER, origClOrdId = "ABCDEFGHIJKLMNOPQRSU", clOrdId = "C1", exchangeOrderId = 1, securityId = SECURITY, side = Side.BUY)
        assertEquals(RejectReason.UNAUTHORIZED_PARTICIPANT, h.reports.last().rejectReason, "the last byte differs")

        h.cancel(MAKER, origClOrdId = "ABCDEFGHIJKLMNOPQRST", clOrdId = "C2", exchangeOrderId = 1, securityId = SECURITY, side = Side.BUY)
        assertEquals("CANCELED", h.reports.last().execType)
    }

    @Test
    fun `an unsolicited cancel carries the order's own id in both fields`() {
        // A bulk cancel names no client id of its own (Design.md §4.8).
        val h = continuous()
        h.newOrder(MAKER, "REST-1", SECURITY, Side.BUY, price = 99, qty = 5)
        h.cancelParticipantOrders(MAKER)

        val cancelled = h.reports.last()
        assertEquals("CANCELED", cancelled.execType)
        assertEquals("REST-1" to "REST-1", cancelled.clOrdIdText to cancelled.origClOrdIdText)
    }

    @Test
    fun `a version 7 order in the log is refused, never read as version 8`() {
        val h = continuous()
        val refusal = assertFailsWith<SchemaMismatch> {
            h.sendRaw { buffer -> versionSevenOrder(buffer) }
        }
        assertTrue(h.service.refused)
        assertTrue("version 7" in refusal.message!!, refusal.message)
        assertEquals(0, h.books[0].restingOrderCount())
        assertTrue(h.reports.isEmpty(), "nothing was answered for it")

        // A refused node applies nothing after it.
        h.newOrder(MAKER, "AFTER", SECURITY, Side.BUY, price = 99, qty = 5)
        assertEquals(0, h.books[0].restingOrderCount())
    }

    /** A NewOrderSingle whose header says version 7: what a log tail written before version 8 holds. */
    private fun versionSevenOrder(buffer: MutableDirectBuffer): Int {
        val header = MessageHeaderEncoder()
        val order = NewOrderSingleEncoder().wrapAndApplyHeader(buffer, 0, header)
            .participantId(MAKER).price(99).qty(5).securityId(SECURITY)
            .side(nl.lamia.most.exchange.sbe.Side.BUY)
        ClOrdId.of("V7").writeTo(buffer, order.offset() + NewOrderSingleEncoder.clOrdIdEncodingOffset())
        header.version(7)
        return MessageHeaderEncoder.ENCODED_LENGTH + NewOrderSingleEncoder.BLOCK_LENGTH
    }

    private companion object {
        const val SECURITY = 1
        const val MAKER = 7L
        const val TAKER = 9L
    }
}
