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
class DepthBook(
    @PublishedApi internal val levelCount: Int,
    @PublishedApi internal val priceFloor: Long,
    @PublishedApi internal val tickSize: Long,
) {

    // `internal` rather than `private` because the snapshot walk below is a public inline function
    // and a public inline function cannot touch private members -- the same reason the engine's
    // pool and ladders are @PublishedApi internal.
    @PublishedApi internal val bidQty = LongArray(levelCount)
    @PublishedApi internal val askQty = LongArray(levelCount)
    @PublishedApi internal val bidOrders = IntArray(levelCount)
    @PublishedApi internal val askOrders = IntArray(levelCount)
    @PublishedApi internal val bidOccupancy = LongArray((levelCount + 63) ushr 6)
    @PublishedApi internal val askOccupancy = LongArray((levelCount + 63) ushr 6)

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

    /**
     * How many levels a side is holding, which is what a snapshot has to promise before it sends
     * them. A population count over the occupancy words rather than a scan of the ladder: the
     * bitset is 512 words for a 32768-level book and the ladder is 32768 slots, nearly all empty.
     */
    fun occupiedLevels(isBid: Boolean): Int {
        var count = 0
        for (word in occupancy(isBid)) count += word.countOneBits()
        return count
    }

    /**
     * Walks the occupied levels of one side, best price first, for a snapshot.
     *
     * `inline` so the callback does not box the four values on every level -- the sanctioned use
     * of `inline` in this codebase is exactly a function taking a lambda. Best-first because that
     * is the order a consumer wants to read a book in, and because the levels that matter arrive
     * first if a cycle is ever cut short.
     */
    inline fun forEachOccupied(isBid: Boolean, action: (price: Long, qty: Long, orders: Int) -> Unit) {
        var level = if (isBid) bestBidLevel else bestAskLevel
        while (level != NULL_LEVEL) {
            action(
                priceFloor + level.toLong() * tickSize,
                if (isBid) bidQty[level] else askQty[level],
                if (isBid) bidOrders[level] else askOrders[level],
            )
            level = if (isBid) nextOccupiedBelow(level) else nextOccupiedAbove(level)
        }
    }

    /** Public so [forEachOccupied] can inline against them; not otherwise part of the surface. */
    @PublishedApi
    internal fun nextOccupiedBelow(level: Int): Int = highestOccupiedAtOrBelow(level - 1)

    @PublishedApi
    internal fun nextOccupiedAbove(level: Int): Int = lowestOccupiedAtOrAbove(level + 1)

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
