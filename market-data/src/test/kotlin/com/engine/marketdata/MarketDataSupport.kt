package com.engine.marketdata

import com.engine.sbe.AuctionUncrossedEncoder
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

class RecordingPublisher : FeedPublisher {
    val l3 = mutableListOf<Int>()
    val depth = mutableListOf<Depth>()
    val top = mutableListOf<Top>()
    val trades = mutableListOf<Trade>()

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
