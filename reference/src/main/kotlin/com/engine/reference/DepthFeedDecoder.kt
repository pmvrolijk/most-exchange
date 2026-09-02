package com.engine.reference

import com.engine.sbe.DepthSnapshotBeginDecoder
import com.engine.sbe.DepthSnapshotEndDecoder
import com.engine.sbe.DepthSnapshotLevelDecoder
import com.engine.sbe.DepthUpdateDecoder
import com.engine.sbe.LastTradeDecoder
import com.engine.sbe.MessageHeaderDecoder
import com.engine.sbe.Side
import org.agrona.DirectBuffer

/**
 * Decodes the market data feeds into one [DepthFeedAssembler].
 *
 * One decoder for every consumer — the operator CLI, the control plane, an e2e inspector — so
 * "which template ids belong to a book, and which fields carry the splice" is answered in a single
 * place. A second consumer that decoded a `DepthSnapshotEnd` slightly differently would build a
 * book that disagreed with everyone else's and have no way to notice.
 *
 * The decoders are reused across calls, which is why this is a class and not a function: they wrap
 * a buffer rather than copy it, and allocating a set per message on a feed doing 100k/s would be
 * absurd for something whose only job is to read four fields.
 */
class DepthFeedDecoder(private val assembler: DepthFeedAssembler) {

    private val header = MessageHeaderDecoder()
    private val depth = DepthUpdateDecoder()
    private val lastTrade = LastTradeDecoder()
    private val snapshotBegin = DepthSnapshotBeginDecoder()
    private val snapshotLevel = DepthSnapshotLevelDecoder()
    private val snapshotEnd = DepthSnapshotEndDecoder()

    /** True when the message was one this consumer understands. */
    fun onMessage(buffer: DirectBuffer, offset: Int, length: Int): Boolean {
        if (length < MessageHeaderDecoder.ENCODED_LENGTH) return false
        header.wrap(buffer, offset)
        val body = offset + MessageHeaderDecoder.ENCODED_LENGTH
        val blockLength = header.blockLength()
        val version = header.version()

        when (header.templateId()) {
            DepthUpdateDecoder.TEMPLATE_ID -> {
                depth.wrap(buffer, body, blockLength, version)
                assembler.onDepthUpdate(
                    securityId = depth.securityId(),
                    shardId = depth.shardId(),
                    seqNum = depth.seqNum(),
                    isBid = depth.side() == Side.BUY,
                    price = depth.price(),
                    aggregateQty = depth.aggregateQty(),
                    orderCount = depth.orderCount(),
                )
            }

            LastTradeDecoder.TEMPLATE_ID -> {
                lastTrade.wrap(buffer, body, blockLength, version)
                assembler.onLastTrade(
                    securityId = lastTrade.securityId(),
                    shardId = lastTrade.shardId(),
                    seqNum = lastTrade.seqNum(),
                    price = lastTrade.price(),
                    qty = lastTrade.qty(),
                )
            }

            DepthSnapshotBeginDecoder.TEMPLATE_ID -> {
                snapshotBegin.wrap(buffer, body, blockLength, version)
                assembler.onSnapshotBegin(
                    securityId = snapshotBegin.securityId(),
                    shardId = snapshotBegin.shardId(),
                    seqNum = snapshotBegin.seqNum(),
                    l2SeqNum = snapshotBegin.l2SeqNum(),
                    lastTradePrice = snapshotBegin.lastTradePrice(),
                    lastTradeQty = snapshotBegin.lastTradeQty(),
                    levelCount = snapshotBegin.levelCount(),
                )
            }

            DepthSnapshotLevelDecoder.TEMPLATE_ID -> {
                snapshotLevel.wrap(buffer, body, blockLength, version)
                assembler.onSnapshotLevel(
                    securityId = snapshotLevel.securityId(),
                    shardId = snapshotLevel.shardId(),
                    seqNum = snapshotLevel.seqNum(),
                    isBid = snapshotLevel.side() == Side.BUY,
                    price = snapshotLevel.price(),
                    aggregateQty = snapshotLevel.aggregateQty(),
                    orderCount = snapshotLevel.orderCount(),
                )
            }

            DepthSnapshotEndDecoder.TEMPLATE_ID -> {
                snapshotEnd.wrap(buffer, body, blockLength, version)
                assembler.onSnapshotEnd(
                    securityId = snapshotEnd.securityId(),
                    shardId = snapshotEnd.shardId(),
                    seqNum = snapshotEnd.seqNum(),
                    l2SeqNum = snapshotEnd.l2SeqNum(),
                    levelCount = snapshotEnd.levelCount(),
                )
            }

            // TopOfBook and the L3 book events are not this assembler's business: L1 is derivable
            // from the book it is already building, and re-deriving it here would be a second
            // implementation that could disagree with the publisher's.
            else -> return false
        }
        return true
    }
}
