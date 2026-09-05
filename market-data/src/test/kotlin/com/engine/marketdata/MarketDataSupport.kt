package com.engine.marketdata

import com.engine.reference.AggregatedBook
import com.engine.reference.DepthFeedAssembler
import com.engine.reference.DepthFeedDecoder
import com.engine.sbe.AuctionUncrossedEncoder
import com.engine.sbe.BookImageBeginEncoder
import com.engine.sbe.BookImageEndEncoder
import com.engine.sbe.BookImageLevelEncoder
import com.engine.sbe.MessageHeaderEncoder
import com.engine.sbe.OrderAddedEncoder
import com.engine.sbe.OrderReducedEncoder
import com.engine.sbe.OrderRemovedEncoder
import com.engine.sbe.RemoveReason
import com.engine.sbe.Side
import com.engine.sbe.TradeExecutedEncoder
import org.agrona.DirectBuffer
import org.agrona.concurrent.UnsafeBuffer

data class Depth(val securityId: Int, val side: Byte, val price: Long, val qty: Long, val orders: Int)
data class Top(val securityId: Int, val bidPrice: Long, val bidQty: Long, val askPrice: Long, val askQty: Long)
data class Trade(val securityId: Int, val price: Long, val qty: Long, val aggressorSide: Byte)
data class SnapshotBegin(
    val securityId: Int,
    val l2SeqNum: Long,
    val lastTradePrice: Long,
    val lastTradeQty: Long,
    val levelCount: Int,
)
data class SnapshotEnd(val securityId: Int, val l2SeqNum: Long, val levelCount: Int)

class RecordingPublisher : FeedPublisher {
    val l3 = mutableListOf<Int>()
    val depth = mutableListOf<Depth>()
    val top = mutableListOf<Top>()
    val trades = mutableListOf<Trade>()
    val snapshotBegins = mutableListOf<SnapshotBegin>()
    val snapshotLevels = mutableListOf<Depth>()
    val snapshotEnds = mutableListOf<SnapshotEnd>()

    /** Stands in for the real publisher's next-sequence counter: one per DepthUpdate written. */
    override val lastDepthSeqNum: Long get() = depth.size.toLong()

    override fun publishSnapshotBegin(
        securityId: Int,
        l2SeqNum: Long,
        lastTradePrice: Long,
        lastTradeQty: Long,
        levelCount: Int,
    ) {
        snapshotBegins += SnapshotBegin(securityId, l2SeqNum, lastTradePrice, lastTradeQty, levelCount)
    }

    override fun publishSnapshotLevel(
        securityId: Int,
        side: Byte,
        price: Long,
        aggregateQty: Long,
        orderCount: Int,
    ) {
        snapshotLevels += Depth(securityId, side, price, aggregateQty, orderCount)
    }

    override fun publishSnapshotEnd(securityId: Int, l2SeqNum: Long, levelCount: Int) {
        snapshotEnds += SnapshotEnd(securityId, l2SeqNum, levelCount)
    }

    override fun publishL3(buffer: DirectBuffer, offset: Int, length: Int) {
        l3 += length
    }

    override fun publishDepth(securityId: Int, side: Byte, price: Long, aggregateQty: Long, orderCount: Int) {
        depth += Depth(securityId, side, price, aggregateQty, orderCount)
    }

    override fun publishTopOfBook(securityId: Int, bidPrice: Long, bidQty: Long, askPrice: Long, askQty: Long) {
        top += Top(securityId, bidPrice, bidQty, askPrice, askQty)
    }

    override fun publishLastTrade(securityId: Int, price: Long, qty: Long, aggressorSide: Byte) {
        trades += Trade(securityId, price, qty, aggressorSide)
    }
}

/** Emits the engine's book events so the service can be driven exactly as in production. */
class BookEventFeeder(
    private val service: MarketDataService,
    private val shardId: Int = SHARD_ID,
) {
    private val buffer = UnsafeBuffer(ByteArray(1024))
    private val header = MessageHeaderEncoder()
    private var seq = 1L

    fun skipSequence(by: Long) {
        seq += by
    }

    fun orderAdded(securityId: Int, orderId: Long, price: Long, qty: Long, side: Byte) {
        OrderAddedEncoder().wrapAndApplyHeader(buffer, 0, header)
            .seqNum(seq++).exchangeOrderId(orderId).price(price).qty(qty)
            .securityId(securityId).shardId(shardId).side(Side.get(side))
        emit(MessageHeaderEncoder.ENCODED_LENGTH + OrderAddedEncoder.BLOCK_LENGTH)
    }

    fun orderReduced(securityId: Int, orderId: Long, price: Long, lastQty: Long, leavesQty: Long, side: Byte) {
        OrderReducedEncoder().wrapAndApplyHeader(buffer, 0, header)
            .seqNum(seq++).exchangeOrderId(orderId).price(price).lastQty(lastQty)
            .leavesQty(leavesQty).securityId(securityId).shardId(shardId).side(Side.get(side))
        emit(MessageHeaderEncoder.ENCODED_LENGTH + OrderReducedEncoder.BLOCK_LENGTH)
    }

    fun orderRemoved(
        securityId: Int,
        orderId: Long,
        price: Long,
        leavesQty: Long,
        side: Byte,
        reason: RemoveReason = RemoveReason.CANCELED,
    ) {
        OrderRemovedEncoder().wrapAndApplyHeader(buffer, 0, header)
            .seqNum(seq++).exchangeOrderId(orderId).price(price).leavesQty(leavesQty)
            .securityId(securityId).shardId(shardId).side(Side.get(side)).reason(reason)
        emit(MessageHeaderEncoder.ENCODED_LENGTH + OrderRemovedEncoder.BLOCK_LENGTH)
    }

    fun tradeExecuted(securityId: Int, price: Long, qty: Long, aggressorSide: Byte) {
        TradeExecutedEncoder().wrapAndApplyHeader(buffer, 0, header)
            .seqNum(seq++).price(price).qty(qty).takerOrderId(1).makerOrderId(2)
            .securityId(securityId).shardId(shardId).aggressorSide(Side.get(aggressorSide))
        emit(MessageHeaderEncoder.ENCODED_LENGTH + TradeExecutedEncoder.BLOCK_LENGTH)
    }

    fun auctionUncrossed(securityId: Int, price: Long, qty: Long) {
        AuctionUncrossedEncoder().wrapAndApplyHeader(buffer, 0, header)
            .seqNum(seq++).uncrossPrice(price).executedQty(qty).securityId(securityId).shardId(shardId)
        emit(MessageHeaderEncoder.ENCODED_LENGTH + AuctionUncrossedEncoder.BLOCK_LENGTH)
    }

    /**
     * One bracketed book image, exactly as the engine writes it after a snapshot restore.
     *
     * [seq] is passed through as the baseline and **not** advanced: an image consumes no book event
     * sequence numbers, which is what lets the engine publish one at a node-local moment without
     * changing replicated state. A feeder that advanced it here would be testing a different
     * protocol from the one the engine speaks.
     */
    fun bookImage(
        securityId: Int,
        levels: List<Triple<Byte, Long, Pair<Long, Int>>>,
        declaredCount: Int = levels.size,
        shardIdOverride: Int = shardId,
    ) {
        BookImageBeginEncoder().wrapAndApplyHeader(buffer, 0, header)
            .seqNum(seq).securityId(securityId).shardId(shardIdOverride).levelCount(declaredCount)
        emit(MessageHeaderEncoder.ENCODED_LENGTH + BookImageBeginEncoder.BLOCK_LENGTH)

        for ((side, price, aggregate) in levels) {
            BookImageLevelEncoder().wrapAndApplyHeader(buffer, 0, header)
                .seqNum(seq).price(price).qty(aggregate.first).securityId(securityId)
                .shardId(shardIdOverride).orderCount(aggregate.second).side(Side.get(side))
            emit(MessageHeaderEncoder.ENCODED_LENGTH + BookImageLevelEncoder.BLOCK_LENGTH)
        }

        BookImageEndEncoder().wrapAndApplyHeader(buffer, 0, header)
            .seqNum(seq).securityId(securityId).shardId(shardIdOverride).levelCount(declaredCount)
        emit(MessageHeaderEncoder.ENCODED_LENGTH + BookImageEndEncoder.BLOCK_LENGTH)
    }

    /** A Begin with no End, for the truncated-cycle case. */
    fun bookImageBeginOnly(securityId: Int, declaredCount: Int) {
        BookImageBeginEncoder().wrapAndApplyHeader(buffer, 0, header)
            .seqNum(seq).securityId(securityId).shardId(shardId).levelCount(declaredCount)
        emit(MessageHeaderEncoder.ENCODED_LENGTH + BookImageBeginEncoder.BLOCK_LENGTH)
    }

    private fun emit(length: Int) = service.onBookEvent(buffer, 0, length)
}

const val BUY = 0.toByte()
const val SELL = 1.toByte()

const val SHARD_ID = 1

fun newService(
    publisher: RecordingPublisher,
    vararg securityIds: Int = intArrayOf(1),
    shardId: Int = SHARD_ID,
) = MarketDataService(
    shardId,
    securityIds.map { MarketDataSecurity(it, priceFloor = 0L, tickSize = 1L, levelCount = 1024) },
    publisher,
)

/**
 * A real subscriber on the other end of the real encoders.
 *
 * The publisher is [SbeFeedPublisher] itself, not a second encoding written for the test, and the
 * consumer is the shipped [DepthFeedAssembler] behind the shipped [DepthFeedDecoder]. What this
 * checks is therefore the thing that actually has to hold: that a book assembled from the wire
 * equals the book the market data process is holding. A hand-written double on either end would
 * validate a protocol only the test speaks.
 *
 * [connected] models a subscriber that has not joined yet, and [dropNext] the gap that
 * `MaxMulticastFlowControl` hands a slow one -- the two situations the snapshot exists for, and
 * the same situation from the assembler's point of view.
 */
class LoopbackSubscriber(shardId: Int = SHARD_ID) {

    val assembler = DepthFeedAssembler()
    private val decoder = DepthFeedDecoder(assembler)

    var connected = true
    private var dropRemaining = 0

    /** Drops the next [count] feed messages, whatever they are, as a lossy network would. */
    fun drop(count: Int) {
        dropRemaining = count
    }

    val publisher = SbeFeedPublisher(
        shardId = shardId,
        l3 = FeedSink { _, _, _ -> true },
        l2 = FeedSink { buffer, offset, length -> deliver(buffer, offset, length) },
        l1 = FeedSink { buffer, offset, length -> deliver(buffer, offset, length) },
        snapshot = FeedSink { buffer, offset, length -> deliver(buffer, offset, length) },
        buffer = UnsafeBuffer(ByteArray(1024)),
    )

    private fun deliver(buffer: DirectBuffer, offset: Int, length: Int): Boolean {
        if (dropRemaining > 0) {
            dropRemaining--
            // Sent, from the publisher's point of view: it reached the network and was lost after.
            return true
        }
        if (connected) decoder.onMessage(buffer, offset, length)
        return true
    }
}

/** The publisher's own view of a book, as a comparable value. */
fun DepthBook.levels(isBid: Boolean): List<Triple<Long, Long, Int>> = buildList {
    forEachOccupied(isBid) { price, qty, orders -> add(Triple(price, qty, orders)) }
}

/** The subscriber's view of the same book, in the same shape. */
fun AggregatedBook.levelsOf(isBid: Boolean): List<Triple<Long, Long, Int>> =
    levels(isBid, Int.MAX_VALUE).map { Triple(it.price, it.qty, it.orders) }
