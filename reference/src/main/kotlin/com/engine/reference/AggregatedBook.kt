package com.engine.reference

import java.util.TreeMap

/**
 * A price-aggregated book, as a *consumer* of the L2 feed holds it.
 *
 * Deliberately not the engine's flat ladder, and not the market data process's `DepthBook`. Both
 * of those are indexed by tick from a floor because they are on a path where a cache miss matters
 * and because they know the security's geometry by construction. A consumer knows neither: it may
 * hold a stale universe, it may be watching a security whose geometry it was never told, and it
 * renders a handful of levels a few times a second. A `TreeMap` keyed by price needs no geometry
 * at all and cannot disagree with the publisher about what level a price maps to.
 *
 * A `DepthUpdate` carries the **new aggregate** for a level, never a delta, and an aggregate of
 * zero means the level is gone. That is the whole of the wire semantics, and it is applied in
 * exactly one place so a snapshot level and an incremental update cannot be interpreted
 * differently.
 */
class AggregatedBook(val securityId: Int) {

    private val bids = TreeMap<Long, Level>(reverseOrder())
    private val asks = TreeMap<Long, Level>()

    /** Null until something trades. Never derived from depth — a book can be empty and still have traded. */
    var lastTradePrice: Long? = null
        private set

    var lastTradeQty: Long = 0
        private set

    /** Depth messages applied since this book was created, snapshot levels included. */
    var updates: Long = 0
        private set

    data class Level(val price: Long, val qty: Long, val orders: Int)

    fun applyDepth(isBid: Boolean, price: Long, aggregateQty: Long, orderCount: Int) {
        updates++
        val side = if (isBid) bids else asks
        if (aggregateQty <= 0L) side.remove(price) else side[price] = Level(price, aggregateQty, orderCount)
    }

    fun applyTrade(price: Long, qty: Long) {
        lastTradePrice = price
        lastTradeQty = qty
    }

    /**
     * Drops the depth and keeps the last trade.
     *
     * Used when a gap makes the aggregates untrustworthy. The last trade is not an aggregate of
     * anything — it was one message that either arrived or did not — so discarding it would throw
     * away a fact that is still true.
     */
    fun clearDepth() {
        bids.clear()
        asks.clear()
    }

    fun bestBid(): Long? = bids.firstEntry()?.key

    fun bestAsk(): Long? = asks.firstEntry()?.key

    fun spread(): Long? {
        val bid = bestBid() ?: return null
        val ask = bestAsk() ?: return null
        return ask - bid
    }

    fun isEmpty(): Boolean = bids.isEmpty() && asks.isEmpty()

    fun levelCount(isBid: Boolean): Int = if (isBid) bids.size else asks.size

    /** Best first: bids descending, asks ascending. */
    fun levels(isBid: Boolean, limit: Int): List<Level> =
        (if (isBid) bids else asks).values.take(limit)
}
