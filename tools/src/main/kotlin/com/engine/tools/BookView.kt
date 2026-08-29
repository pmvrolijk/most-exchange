package com.engine.tools

import com.engine.reference.PriceCodec
import java.util.TreeMap

/**
 * An aggregated book rebuilt from the L2 `DepthUpdate` feed, for display.
 *
 * Deliberately a `TreeMap` rather than the engine's flat ladder. This is an operator tool that
 * prints a handful of levels a few times a second; sorted iteration and readable code are worth
 * more here than the allocation-free access the matching path needs, and the tool does not know
 * a security's geometry until discovery tells it.
 */
class BookView(val securityId: Int, val symbol: String) {

    private val bids = TreeMap<Long, Level>(reverseOrder())
    private val asks = TreeMap<Long, Level>()

    var lastTradePrice: Long? = null
        private set
    var lastTradeQty: Long = 0
        private set
    var updates: Long = 0
        private set

    data class Level(val qty: Long, val orders: Int)

    /** A level with zero aggregate quantity has been emptied and leaves the book. */
    fun applyDepth(isBid: Boolean, price: Long, aggregateQty: Long, orderCount: Int) {
        updates++
        val side = if (isBid) bids else asks
        if (aggregateQty <= 0L) side.remove(price) else side[price] = Level(aggregateQty, orderCount)
    }

    fun applyTrade(price: Long, qty: Long) {
        lastTradePrice = price
        lastTradeQty = qty
    }

    fun bestBid(): Long? = bids.firstEntry()?.key

    fun bestAsk(): Long? = asks.firstEntry()?.key

    fun spread(): Long? {
        val bid = bestBid() ?: return null
        val ask = bestAsk() ?: return null
        return ask - bid
    }

    fun isEmpty(): Boolean = bids.isEmpty() && asks.isEmpty()

    /** Renders the top [depth] levels as a two-sided ladder. */
    fun render(depth: Int): String = buildString {
        append("── $symbol (id $securityId) ")
        append("─".repeat(if (symbol.length < 40) 40 - symbol.length else 1))
        append('\n')
        append(String.format("%18s %10s │ %-10s %-18s%n", "bid qty", "bid", "ask", "ask qty"))

        val bidRows = bids.entries.take(depth)
        val askRows = asks.entries.take(depth)
        val rows = maxOf(bidRows.size, askRows.size)
        if (rows == 0) {
            append("  (empty)\n")
        } else {
            for (i in 0 until rows) {
                val bid = bidRows.getOrNull(i)
                val ask = askRows.getOrNull(i)
                append(
                    String.format(
                        "%12d (%3d) %10s │ %-10s %-12d (%d)%n",
                        bid?.value?.qty ?: 0,
                        bid?.value?.orders ?: 0,
                        bid?.key?.let { PriceCodec.format(it) } ?: "",
                        ask?.key?.let { PriceCodec.format(it) } ?: "",
                        ask?.value?.qty ?: 0,
                        ask?.value?.orders ?: 0,
                    ),
                )
            }
        }

        val spread = spread()
        append("  spread ")
        append(if (spread == null) "n/a" else PriceCodec.format(spread))
        lastTradePrice?.let { append("   last ${PriceCodec.format(it)} x $lastTradeQty") }
        append("   updates $updates\n")
    }
}
