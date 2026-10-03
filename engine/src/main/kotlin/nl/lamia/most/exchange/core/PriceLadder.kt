package nl.lamia.most.exchange.core

/**
 * One side of one book: a flat array of price levels indexed by ticks from a floor, with an
 * occupancy bitset so finding the next best price is a word scan rather than a tree walk
 * (Design.md §3.2).
 *
 * Each level holds a FIFO of order indices (time priority) plus aggregates used by market
 * data and by the auction's cumulative volume curves.
 */
class PriceLadder(private val levelCount: Int) {

    val head = IntArray(levelCount) { NULL_INDEX }
    val tail = IntArray(levelCount) { NULL_INDEX }
    val levelQty = LongArray(levelCount)
    val orderCount = IntArray(levelCount)

    private val occupancy = LongArray((levelCount + 63) ushr 6)

    fun markOccupied(level: Int) {
        occupancy[level ushr 6] = occupancy[level ushr 6] or (1L shl (level and 63))
    }

    fun markEmpty(level: Int) {
        occupancy[level ushr 6] = occupancy[level ushr 6] and (1L shl (level and 63)).inv()
    }

    fun isOccupied(level: Int): Boolean =
        occupancy[level ushr 6] and (1L shl (level and 63)) != 0L

    /** Best bid: highest occupied level at or below [start], or [NULL_LEVEL]. */
    fun highestOccupiedAtOrBelow(start: Int): Int {
        if (start < 0) return NULL_LEVEL
        val from = if (start >= levelCount) levelCount - 1 else start
        var w = from ushr 6
        var word = occupancy[w] and (-1L ushr (63 - (from and 63)))
        while (true) {
            if (word != 0L) return (w shl 6) + (63 - word.countLeadingZeroBits())
            if (w == 0) return NULL_LEVEL
            w--
            word = occupancy[w]
        }
    }

    /** Best ask: lowest occupied level at or above [start], or [NULL_LEVEL]. */
    fun lowestOccupiedAtOrAbove(start: Int): Int {
        if (start >= levelCount) return NULL_LEVEL
        val from = if (start < 0) 0 else start
        var w = from ushr 6
        var word = occupancy[w] and (-1L shl (from and 63))
        while (true) {
            if (word != 0L) return (w shl 6) + word.countTrailingZeroBits()
            w++
            if (w >= occupancy.size) return NULL_LEVEL
            word = occupancy[w]
        }
    }
}
