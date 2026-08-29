package com.engine.marketdata

import com.engine.reference.FeedSequenceTracker
import com.engine.sbe.AuctionUncrossedDecoder
import com.engine.sbe.MessageHeaderDecoder
import com.engine.sbe.OrderAddedDecoder
import com.engine.sbe.OrderReducedDecoder
import com.engine.sbe.OrderRemovedDecoder
import com.engine.sbe.SessionChangedDecoder
import com.engine.sbe.Side
import com.engine.sbe.TradeExecutedDecoder
import com.engine.sbe.VolatilityHaltedDecoder
import org.agrona.DirectBuffer

/**
 * Where the derived feeds go. An interface so the derivation logic is testable without Aeron,
 * and so the transport (multicast, unicast fallback) is chosen at the edge.
 */
interface FeedPublisher {
    /** L3: the engine's book event, forwarded byte for byte. */
    fun publishL3(buffer: DirectBuffer, offset: Int, length: Int)

    fun publishDepth(securityId: Int, side: Byte, price: Long, aggregateQty: Long, orderCount: Int)

    fun publishTopOfBook(
        securityId: Int,
        bidPrice: Long,
        bidQty: Long,
        askPrice: Long,
        askQty: Long,
    )

    fun publishLastTrade(securityId: Int, price: Long, qty: Long, aggressorSide: Byte)
}

data class MarketDataSecurity(
    val securityId: Int,
    val priceFloor: Long,
    val tickSize: Long,
    val levelCount: Int,
)

/**
 * Derives L1, L2 and L3 from the engine's book event stream (Design.md §5).
 *
 * L3 is a verbatim forward: the engine's per-order events *are* market by order, so re-encoding
 * them would only add latency and a chance to disagree with the engine.
 *
 * L2 is maintained incrementally in a [DepthBook] and emitted as a `DepthUpdate` for the one
 * level each event touches. L1 is emitted only when the touch actually moves, which is what keeps
 * a busy book from flooding the top-of-book feed.
 *
 * Gap detection is the subscriber's job (Design.md §5): under `MaxMulticastFlowControl` a slow
 * consumer takes an unrecoverable gap rather than slowing the publisher. This process tracks the
 * engine's own `seqNum` for the same reason, and counts what it misses.
 */
class MarketDataService(
    private val shardId: Int,
    securities: List<MarketDataSecurity>,
    private val publisher: FeedPublisher,
) {
    private val securityIds = IntArray(securities.size) { securities[it].securityId }
    private val books = Array(securities.size) {
        DepthBook(securities[it].levelCount, securities[it].priceFloor, securities[it].tickSize)
    }
    private val lastBidPrice = LongArray(securities.size)
    private val lastBidQty = LongArray(securities.size)
    private val lastAskPrice = LongArray(securities.size)
    private val lastAskQty = LongArray(securities.size)

    private val header = MessageHeaderDecoder()
    private val orderAdded = OrderAddedDecoder()
    private val orderReduced = OrderReducedDecoder()
    private val orderRemoved = OrderRemovedDecoder()
    private val tradeExecuted = TradeExecutedDecoder()
    private val auctionUncrossed = AuctionUncrossedDecoder()
    private val sessionChanged = SessionChangedDecoder()
    private val volatilityHalted = VolatilityHaltedDecoder()

    private val sequences = FeedSequenceTracker()

    val gapsDetected: Long get() = sequences.gapsDetected
    val eventsMissed: Long get() = sequences.messagesMissed

    var unknownSecurityEvents = 0L
        private set
    var outOfRangeEvents = 0L
        private set

    /**
     * Book events stamped with a different shard. This process subscribes to one engine, so a
     * non-zero count means it is pointed at the wrong one — a misconfiguration that would
     * otherwise aggregate another shard's orders into these books silently.
     */
    var foreignShardEvents = 0L
        private set

    init {
        for (i in securities.indices) {
            lastBidPrice[i] = NO_PRICE
            lastAskPrice[i] = NO_PRICE
        }
    }

    fun onBookEvent(buffer: DirectBuffer, offset: Int, length: Int) {
        if (length < MessageHeaderDecoder.ENCODED_LENGTH) return
        header.wrap(buffer, offset)
        val body = offset + MessageHeaderDecoder.ENCODED_LENGTH
        val blockLength = header.blockLength()
        val version = header.version()

        when (header.templateId()) {
            OrderAddedDecoder.TEMPLATE_ID -> {
                orderAdded.wrap(buffer, body, blockLength, version)
                if (!accept(orderAdded.seqNum(), orderAdded.shardId())) return
                applyAdd(
                    orderAdded.securityId(), orderAdded.side(),
                    orderAdded.price(), orderAdded.qty(),
                )
            }

            OrderReducedDecoder.TEMPLATE_ID -> {
                orderReduced.wrap(buffer, body, blockLength, version)
                if (!accept(orderReduced.seqNum(), orderReduced.shardId())) return
                // A fill reduces quantity; the order itself survives while leavesQty > 0.
                applyReduce(
                    orderReduced.securityId(), orderReduced.side(),
                    orderReduced.price(), orderReduced.lastQty(), removesOrder = false,
                )
            }

            OrderRemovedDecoder.TEMPLATE_ID -> {
                orderRemoved.wrap(buffer, body, blockLength, version)
                if (!accept(orderRemoved.seqNum(), orderRemoved.shardId())) return
                applyReduce(
                    orderRemoved.securityId(), orderRemoved.side(),
                    orderRemoved.price(), orderRemoved.leavesQty(), removesOrder = true,
                )
            }

            TradeExecutedDecoder.TEMPLATE_ID -> {
                tradeExecuted.wrap(buffer, body, blockLength, version)
                if (!accept(tradeExecuted.seqNum(), tradeExecuted.shardId())) return
                publisher.publishLastTrade(
                    tradeExecuted.securityId(), tradeExecuted.price(), tradeExecuted.qty(),
                    tradeExecuted.aggressorSide().value(),
                )
            }

            AuctionUncrossedDecoder.TEMPLATE_ID -> {
                auctionUncrossed.wrap(buffer, body, blockLength, version)
                if (!accept(auctionUncrossed.seqNum(), auctionUncrossed.shardId())) return
            }

            SessionChangedDecoder.TEMPLATE_ID -> {
                sessionChanged.wrap(buffer, body, blockLength, version)
                if (!accept(sessionChanged.seqNum(), sessionChanged.shardId())) return
            }

            VolatilityHaltedDecoder.TEMPLATE_ID -> {
                volatilityHalted.wrap(buffer, body, blockLength, version)
                if (!accept(volatilityHalted.seqNum(), volatilityHalted.shardId())) return
            }

            else -> return // Unknown template: forward nothing rather than guess.
        }

        // L3 is the engine's own event, unaltered.
        publisher.publishL3(buffer, offset, length)
    }

    /**
     * The engine stamps a monotonic sequence on every book event, so a gap here means this
     * process fell behind and its L2 aggregates are now wrong. Counted rather than thrown on;
     * recovery is a resynchronisation concern, not a crash.
     *
     * Returns false for an event belonging to another shard, which must not be aggregated or
     * forwarded — its sequence is a different stream entirely.
     */
    private fun accept(seqNum: Long, eventShardId: Int): Boolean {
        if (eventShardId != shardId) {
            foreignShardEvents++
            return false
        }
        sequences.accept(eventShardId, seqNum)
        return true
    }

    private fun applyAdd(securityId: Int, side: Side, price: Long, qty: Long) {
        val index = indexOf(securityId)
        if (index < 0) {
            unknownSecurityEvents++
            return
        }
        val isBid = side == Side.BUY
        val level = books[index].add(price, qty, isBid)
        if (level == NULL_LEVEL) {
            outOfRangeEvents++
            return
        }
        emitDepth(index, securityId, side, level, isBid)
        emitTopOfBookIfChanged(index, securityId)
    }

    private fun applyReduce(
        securityId: Int,
        side: Side,
        price: Long,
        qty: Long,
        removesOrder: Boolean,
    ) {
        val index = indexOf(securityId)
        if (index < 0) {
            unknownSecurityEvents++
            return
        }
        val isBid = side == Side.BUY
        val level = books[index].reduce(price, qty, isBid, removesOrder)
        if (level == NULL_LEVEL) {
            outOfRangeEvents++
            return
        }
        emitDepth(index, securityId, side, level, isBid)
        emitTopOfBookIfChanged(index, securityId)
    }

    private fun emitDepth(index: Int, securityId: Int, side: Side, level: Int, isBid: Boolean) {
        val book = books[index]
        publisher.publishDepth(
            securityId, side.value(), book.priceOf(level),
            book.qtyAt(level, isBid), book.ordersAt(level, isBid),
        )
    }

    /** Only when the touch actually moves — a busy book must not flood the L1 feed. */
    private fun emitTopOfBookIfChanged(index: Int, securityId: Int) {
        val book = books[index]
        val bidPrice = if (book.bestBidLevel == NULL_LEVEL) NO_PRICE else book.priceOf(book.bestBidLevel)
        val askPrice = if (book.bestAskLevel == NULL_LEVEL) NO_PRICE else book.priceOf(book.bestAskLevel)
        val bidQty = book.bestBidQty()
        val askQty = book.bestAskQty()

        if (bidPrice == lastBidPrice[index] && bidQty == lastBidQty[index] &&
            askPrice == lastAskPrice[index] && askQty == lastAskQty[index]
        ) {
            return
        }
        lastBidPrice[index] = bidPrice
        lastBidQty[index] = bidQty
        lastAskPrice[index] = askPrice
        lastAskQty[index] = askQty
        publisher.publishTopOfBook(securityId, bidPrice, bidQty, askPrice, askQty)
    }

    private fun indexOf(securityId: Int): Int {
        for (i in securityIds.indices) if (securityIds[i] == securityId) return i
        return -1
    }

    companion object {
        /** No bid or no offer. Distinct from a real price so consumers can tell them apart. */
        const val NO_PRICE = Long.MIN_VALUE
    }
}
