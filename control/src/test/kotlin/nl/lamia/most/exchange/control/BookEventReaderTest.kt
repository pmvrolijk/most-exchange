package nl.lamia.most.exchange.control

import nl.lamia.most.exchange.sbe.AuctionUncrossedEncoder
import nl.lamia.most.exchange.sbe.BookImageBeginEncoder
import nl.lamia.most.exchange.sbe.BookImageEndEncoder
import nl.lamia.most.exchange.sbe.BookImageLevelEncoder
import nl.lamia.most.exchange.sbe.MessageHeaderEncoder
import nl.lamia.most.exchange.sbe.OrderAddedEncoder
import nl.lamia.most.exchange.sbe.OrderRemovedEncoder
import nl.lamia.most.exchange.sbe.Phase
import nl.lamia.most.exchange.sbe.RemoveReason
import nl.lamia.most.exchange.sbe.SessionChangedEncoder
import nl.lamia.most.exchange.sbe.Side
import nl.lamia.most.exchange.sbe.TradeExecutedEncoder
import org.agrona.concurrent.UnsafeBuffer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The control plane's L3 reader, against Design.md §5 "Sequence Numbers and Shard Namespacing".
 *
 * Pure: the real encoders on one side, the shipped reader on the other, no Aeron in between.
 */
class BookEventReaderTest {

    private val state = ExchangeState()
    private val reader = BookEventReader(state)
    private val feed = Feed(reader)

    /**
     * Design.md §5 — "Every book event carries a monotonic `seqNum`". Every one: the control plane
     * decodes four of the seven and ignores the order events, so the arithmetic that matters is
     * that an ignored event still advances the expected sequence. Eight events numbered 1..8 with
     * nothing lost is a gap count of zero, whichever of them this process happens to care about.
     */
    @Test
    fun `an order event consumes a sequence number, so ignoring one is not a gap`() {
        feed.sessionChanged(SECURITY, Phase.CONTINUOUS)
        feed.orderAdded(SECURITY, price = 10_000_000_000L, qty = 100)
        feed.orderAdded(SECURITY, price = 10_100_000_000L, qty = 100)
        feed.orderRemoved(SECURITY, price = 10_100_000_000L, leavesQty = 100)
        feed.tradeExecuted(SECURITY, price = 10_000_000_000L, qty = 50)
        feed.orderAdded(SECURITY, price = 9_900_000_000L, qty = 100)
        feed.orderAdded(SECURITY, price = 9_800_000_000L, qty = 100)
        feed.auctionUncrossed(SECURITY, price = 10_000_000_000L, qty = 10)

        assertEquals(0L, state.feedGaps)
        assertEquals(0L, state.eventsMissed)
        assertEquals(8L, state.eventsSeen)
        // And the four it does decode still landed.
        assertEquals("CONTINUOUS", state.securityState(SECURITY)?.phase)
        assertEquals(10_000_000_000L, state.securityState(SECURITY)?.lastTradePrice)
    }

    /**
     * Design.md §5 — a gap is "a jump forward, counted with how many were missed". Three events are
     * lost between sequence 1 and sequence 5, so that is one gap of three.
     */
    @Test
    fun `a gap is counted once, with how many events were missed`() {
        feed.orderAdded(SECURITY, price = 10_000_000_000L, qty = 100)
        feed.lose(3)
        feed.tradeExecuted(SECURITY, price = 10_000_000_000L, qty = 50)

        assertEquals(1L, state.feedGaps)
        assertEquals(3L, state.eventsMissed)
        assertEquals(2L, state.eventsSeen)
    }

    /**
     * Design.md §5 — the first message from a shard "establishes the baseline so joining
     * mid-session does not report everything before it as lost".
     */
    @Test
    fun `joining mid-session reports no loss for what came before`() {
        feed.jumpTo(5_000)
        feed.orderAdded(SECURITY, price = 10_000_000_000L, qty = 100)
        feed.tradeExecuted(SECURITY, price = 10_000_000_000L, qty = 50)

        assertEquals(0L, state.feedGaps)
        assertEquals(0L, state.eventsMissed)
    }

    /**
     * Design.md §5 — "Each shard numbers its streams from 1 independently, so on a shared multicast
     * group a subscriber would see two interleaved sequences and read the entire feed as gaps."
     */
    @Test
    fun `two shards interleaved on one feed are tracked apart`() {
        val one = Feed(reader, shardId = 1)
        val two = Feed(reader, shardId = 2)
        repeat(4) {
            one.orderAdded(SECURITY, price = 10_000_000_000L, qty = 100)
            two.orderAdded(OTHER_SECURITY, price = 20_000_000_000L, qty = 100)
        }

        assertEquals(0L, state.feedGaps)
        assertEquals(0L, state.eventsMissed)
        assertEquals(8L, state.eventsSeen)
    }

    /**
     * Design.md §5 — an image's seqNum is "a baseline, not a sequence": the messages "do not consume
     * book event sequence numbers and must not be counted as a gap". Market-data does not forward
     * images onto L3 today, so this pins the reader against a change there rather than against
     * today's traffic.
     */
    @Test
    fun `a book image is a baseline and never a gap`() {
        feed.orderAdded(SECURITY, price = 10_000_000_000L, qty = 100)
        feed.bookImage(SECURITY, levels = 3)
        feed.tradeExecuted(SECURITY, price = 10_000_000_000L, qty = 50)

        assertEquals(0L, state.feedGaps)
        assertEquals(0L, state.eventsMissed)
        // The image's five messages are not book events and are not counted as seen.
        assertEquals(2L, state.eventsSeen)
    }

    /** A replay is a duplicate, not loss: Aeron does not reorder within a stream. */
    @Test
    fun `a replayed event is not counted as loss`() {
        feed.orderAdded(SECURITY, price = 10_000_000_000L, qty = 100)
        feed.orderAdded(SECURITY, price = 10_100_000_000L, qty = 100)
        feed.rewind(2)
        feed.orderAdded(SECURITY, price = 10_000_000_000L, qty = 100)

        assertEquals(0L, state.feedGaps)
        assertEquals(0L, state.eventsMissed)
        assertEquals(1L, reader.duplicatesOrReplays)
    }

    /** Emits the engine's book events exactly as market-data forwards them onto L3. */
    private class Feed(private val reader: BookEventReader, private val shardId: Int = SHARD_ID) {
        private val buffer = UnsafeBuffer(ByteArray(1024))
        private val header = MessageHeaderEncoder()
        private var seq = 1L

        /** The events a `MaxMulticastFlowControl` gap swallowed: numbered, never delivered. */
        fun lose(count: Int) {
            seq += count
        }

        fun jumpTo(seqNum: Long) {
            seq = seqNum
        }

        fun rewind(by: Long) {
            seq -= by
        }

        fun orderAdded(securityId: Int, price: Long, qty: Long) {
            OrderAddedEncoder().wrapAndApplyHeader(buffer, 0, header)
                .seqNum(seq++).exchangeOrderId(1).price(price).qty(qty)
                .securityId(securityId).shardId(shardId).side(Side.BUY)
            emit(OrderAddedEncoder.BLOCK_LENGTH)
        }

        fun orderRemoved(securityId: Int, price: Long, leavesQty: Long) {
            OrderRemovedEncoder().wrapAndApplyHeader(buffer, 0, header)
                .seqNum(seq++).exchangeOrderId(1).price(price).leavesQty(leavesQty)
                .securityId(securityId).shardId(shardId).side(Side.BUY)
                .reason(RemoveReason.CANCELED)
            emit(OrderRemovedEncoder.BLOCK_LENGTH)
        }

        fun tradeExecuted(securityId: Int, price: Long, qty: Long) {
            TradeExecutedEncoder().wrapAndApplyHeader(buffer, 0, header)
                .seqNum(seq++).price(price).qty(qty).takerOrderId(1).makerOrderId(2)
                .securityId(securityId).shardId(shardId).aggressorSide(Side.BUY)
            emit(TradeExecutedEncoder.BLOCK_LENGTH)
        }

        fun auctionUncrossed(securityId: Int, price: Long, qty: Long) {
            AuctionUncrossedEncoder().wrapAndApplyHeader(buffer, 0, header)
                .seqNum(seq++).uncrossPrice(price).executedQty(qty)
                .securityId(securityId).shardId(shardId)
            emit(AuctionUncrossedEncoder.BLOCK_LENGTH)
        }

        fun sessionChanged(securityId: Int, phase: Phase) {
            SessionChangedEncoder().wrapAndApplyHeader(buffer, 0, header)
                .seqNum(seq++).securityId(securityId).shardId(shardId).phase(phase)
            emit(SessionChangedEncoder.BLOCK_LENGTH)
        }

        /** A bracketed image, whose seqNum is the baseline and is deliberately not advanced. */
        fun bookImage(securityId: Int, levels: Int) {
            BookImageBeginEncoder().wrapAndApplyHeader(buffer, 0, header)
                .seqNum(seq).securityId(securityId).shardId(shardId).levelCount(levels)
            emit(BookImageBeginEncoder.BLOCK_LENGTH)
            repeat(levels) {
                BookImageLevelEncoder().wrapAndApplyHeader(buffer, 0, header)
                    .seqNum(seq).price(10_000_000_000L).qty(100).securityId(securityId)
                    .shardId(shardId).orderCount(1).side(Side.BUY)
                emit(BookImageLevelEncoder.BLOCK_LENGTH)
            }
            BookImageEndEncoder().wrapAndApplyHeader(buffer, 0, header)
                .seqNum(seq).securityId(securityId).shardId(shardId).levelCount(levels)
            emit(BookImageEndEncoder.BLOCK_LENGTH)
        }

        private fun emit(blockLength: Int) =
            reader.onBookEvent(buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + blockLength)
    }

    private companion object {
        const val SHARD_ID = 0
        const val SECURITY = 1
        const val OTHER_SECURITY = 2
    }
}
