package com.engine.marketdata

const val NULL_LEVEL = -1

/**
 * Price-aggregated depth for one security, derived from the engine's book event stream.
 *
 * Deliberately *not* the engine's `PriceLadder`: this side keeps no order queues, no free list
 * and no per-order state at all. Because `OrderRemoved` carries the removed quantity, every L2
 * aggregate is derivable from the event stream alone, so the market data process never has to
 * shadow the engine's order pool.
 *
 * The occupancy bitset is the same technique as the engine's ladder — best bid and best offer are
 * a word scan rather than a search.
 */
class DepthBook(private val levelCount: Int, private val priceFloor: Long, private val tickSize: Long) {

    private val bidQty = LongArray(levelCount)
    private val askQty = LongArray(levelCount)
    private val bidOrders = IntArray(levelCount)
    private val askOrders = IntArray(levelCount)
    private val bidOccupancy = LongArray((levelCount + 63) ushr 6)
    private val askOccupancy = LongArray((levelCount + 63) ushr 6)

    var bestBidLevel = NULL_LEVEL
        private set
    var bestAskLevel = NULL_LEVEL
        private set

    fun levelOf(price: Long): Int = ((price - priceFloor) / tickSize).toInt()

    fun priceOf(level: Int): Long = priceFloor + level.toLong() * tickSize

    fun isInRange(level: Int): Boolean = level in 0 until levelCount

    fun qtyAt(level: Int, isBid: Boolean): Long = if (isBid) bidQty[level] else askQty[level]

    fun ordersAt(level: Int, isBid: Boolean): Int = if (isBid) bidOrders[level] else askOrders[level]

    fun bestBidQty(): Long = if (bestBidLevel == NULL_LEVEL) 0L else bidQty[bestBidLevel]

    fun bestAskQty(): Long = if (bestAskLevel == NULL_LEVEL) 0L else askQty[bestAskLevel]

    /** Adds [qty] and one order to a level. Returns the level, or [NULL_LEVEL] if out of range. */
    fun add(price: Long, qty: Long, isBid: Boolean): Int {
        val level = levelOf(price)
        if (!isInRange(level)) return NULL_LEVEL
        val quantities = if (isBid) bidQty else askQty
        val counts = if (isBid) bidOrders else askOrders
        val wasEmpty = quantities[level] == 0L && counts[level] == 0
        quantities[level] += qty
        counts[level]++
        if (wasEmpty) {
            occupancy(isBid).let { it[level ushr 6] = it[level ushr 6] or (1L shl (level and 63)) }
            if (isBid) {
                if (bestBidLevel == NULL_LEVEL || level > bestBidLevel) bestBidLevel = level
            } else {
                if (bestAskLevel == NULL_LEVEL || level < bestAskLevel) bestAskLevel = level
            }
        }
        return level
    }

    /**
     * Removes [qty] from a level, and one order when [removesOrder]. A fill reduces quantity but
     * leaves the order in place until it is exhausted, so the two are separate.
     */
    fun reduce(price: Long, qty: Long, isBid: Boolean, removesOrder: Boolean): Int {
        val level = levelOf(price)
        if (!isInRange(level)) return NULL_LEVEL
        val quantities = if (isBid) bidQty else askQty
        val counts = if (isBid) bidOrders else askOrders

        quantities[level] -= qty
        if (quantities[level] < 0L) quantities[level] = 0L
        if (removesOrder && counts[level] > 0) counts[level]--

        if (counts[level] == 0) {
            quantities[level] = 0L
            clearLevel(level, isBid)
        }
        return level
    }

    private fun clearLevel(level: Int, isBid: Boolean) {
        val bits = occupancy(isBid)
        bits[level ushr 6] = bits[level ushr 6] and (1L shl (level and 63)).inv()
        if (isBid && level == bestBidLevel) {
            bestBidLevel = highestOccupiedAtOrBelow(level - 1)
        } else if (!isBid && level == bestAskLevel) {
            bestAskLevel = lowestOccupiedAtOrAbove(level + 1)
        }
    }

    private fun occupancy(isBid: Boolean) = if (isBid) bidOccupancy else askOccupancy

    private fun highestOccupiedAtOrBelow(start: Int): Int {
        if (start < 0) return NULL_LEVEL
        val from = if (start >= levelCount) levelCount - 1 else start
        var w = from ushr 6
        var word = bidOccupancy[w] and (-1L ushr (63 - (from and 63)))
        while (true) {
            if (word != 0L) return (w shl 6) + (63 - word.countLeadingZeroBits())
            if (w == 0) return NULL_LEVEL
            w--
            word = bidOccupancy[w]
        }
    }

    private fun lowestOccupiedAtOrAbove(start: Int): Int {
        if (start >= levelCount) return NULL_LEVEL
        val from = if (start < 0) 0 else start
        var w = from ushr 6
        var word = askOccupancy[w] and (-1L shl (from and 63))
        while (true) {
            if (word != 0L) return (w shl 6) + word.countTrailingZeroBits()
            w++
            if (w >= askOccupancy.size) return NULL_LEVEL
            word = askOccupancy[w]
        }
    }
}
