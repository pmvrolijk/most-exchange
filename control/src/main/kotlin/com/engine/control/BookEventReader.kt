package com.engine.control

import com.engine.reference.FeedSequenceTracker
import com.engine.sbe.AuctionUncrossedDecoder
import com.engine.sbe.MessageHeaderDecoder
import com.engine.sbe.OrderAddedDecoder
import com.engine.sbe.OrderReducedDecoder
import com.engine.sbe.OrderRemovedDecoder
import com.engine.sbe.SessionChangedDecoder
import com.engine.sbe.TradeExecutedDecoder
import com.engine.sbe.VolatilityHaltedDecoder
import org.agrona.DirectBuffer

/**
 * Reads the L3 feed into [ExchangeState], and counts what the feed lost on the way.
 *
 * L3 is the engine's own book events forwarded verbatim, which is why the control plane subscribes
 * to it rather than to L1 or L2: a volatility halt appears **only** here. A dashboard watching depth
 * would never learn that a security broke.
 *
 * **Every book event consumes a sequence number, whether or not this process interprets it**
 * (Design.md §5). The control plane deliberately ignores order add/reduce/remove — depth is
 * market-data's job and shadowing a book here would be a second implementation of it — but it must
 * still count them, because the sequence it is checking for gaps is the engine's, not its own.
 * Counting only the four decoded branches made every ignored order event read as a gap, so a busy
 * book cried continuous loss and real loss became invisible.
 *
 * A book image (`BookImageBegin`/`Level`/`End`) carries its `seqNum` as a **baseline and consumes
 * none**, so it is not passed to the tracker. Market-data does not forward images onto L3 today;
 * the branch is here so that a change there cannot turn every image into a phantom gap.
 *
 * Separate from [ClusterLink] because that class cannot exist without a media driver, and this is
 * the part with a contract worth pinning.
 */
class BookEventReader(
    private val state: ExchangeState,
    private val sequences: FeedSequenceTracker = FeedSequenceTracker(),
) {
    private val header = MessageHeaderDecoder()
    private val added = OrderAddedDecoder()
    private val reduced = OrderReducedDecoder()
    private val removed = OrderRemovedDecoder()
    private val session = SessionChangedDecoder()
    private val halted = VolatilityHaltedDecoder()
    private val uncrossed = AuctionUncrossedDecoder()
    private val traded = TradeExecutedDecoder()

    /** Duplicates and replays, which are not loss. Exposed for the same reason gaps are. */
    val duplicatesOrReplays: Long get() = sequences.duplicatesOrReplays

    fun onBookEvent(buffer: DirectBuffer, offset: Int, length: Int) {
        if (length < MessageHeaderDecoder.ENCODED_LENGTH) return
        header.wrap(buffer, offset)
        val body = offset + MessageHeaderDecoder.ENCODED_LENGTH
        val blockLength = header.blockLength()
        val version = header.version()

        when (header.templateId()) {
            // Counted and otherwise ignored: the sequence is the engine's, so a message this
            // process does not care about still has to be consumed or it reads as a gap.
            OrderAddedDecoder.TEMPLATE_ID -> {
                added.wrap(buffer, body, blockLength, version)
                accept(added.shardId(), added.seqNum())
            }

            OrderReducedDecoder.TEMPLATE_ID -> {
                reduced.wrap(buffer, body, blockLength, version)
                accept(reduced.shardId(), reduced.seqNum())
            }

            OrderRemovedDecoder.TEMPLATE_ID -> {
                removed.wrap(buffer, body, blockLength, version)
                accept(removed.shardId(), removed.seqNum())
            }

            SessionChangedDecoder.TEMPLATE_ID -> {
                session.wrap(buffer, body, blockLength, version)
                accept(session.shardId(), session.seqNum())
                state.onSessionChanged(session.securityId(), session.shardId(), session.phase().name)
            }

            VolatilityHaltedDecoder.TEMPLATE_ID -> {
                halted.wrap(buffer, body, blockLength, version)
                accept(halted.shardId(), halted.seqNum())
                state.onVolatilityHalted(
                    securityId = halted.securityId(),
                    shardId = halted.shardId(),
                    collarReference = halted.collarReference(),
                    attemptedPrice = halted.attemptedPrice(),
                    breachedBound = halted.breachedBound(),
                    aggressorSide = halted.aggressorSide().name,
                )
            }

            AuctionUncrossedDecoder.TEMPLATE_ID -> {
                uncrossed.wrap(buffer, body, blockLength, version)
                accept(uncrossed.shardId(), uncrossed.seqNum())
                state.onAuctionUncrossed(
                    uncrossed.securityId(), uncrossed.shardId(),
                    uncrossed.uncrossPrice(), uncrossed.executedQty(),
                )
            }

            TradeExecutedDecoder.TEMPLATE_ID -> {
                traded.wrap(buffer, body, blockLength, version)
                accept(traded.shardId(), traded.seqNum())
                state.onTrade(traded.securityId(), traded.shardId(), traded.price(), traded.qty())
            }

            // An image's seqNum is a baseline and consumes nothing. Counting it would report a gap
            // the size of the book's whole history.
            else -> Unit
        }
    }

    /**
     * Sequences are namespaced per shard — each numbers from 1 independently — so a shared feed has
     * to be tracked per shard or every interleaved message reads as a gap.
     */
    private fun accept(shardId: Int, seqNum: Long) {
        state.recordEvent(sequences.accept(shardId, seqNum))
    }
}
