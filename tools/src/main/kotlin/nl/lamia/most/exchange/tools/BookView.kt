package nl.lamia.most.exchange.tools

import nl.lamia.most.exchange.client.AggregatedBook
import nl.lamia.most.exchange.client.PriceCodec
import nl.lamia.most.exchange.client.SyncState

/**
 * Renders one security's book for a terminal.
 *
 * Display only: the book itself is `reference`'s [AggregatedBook], assembled by the same code the
 * control plane and any other consumer use, so what this prints cannot disagree with what they
 * hold. All that lives here is the two-sided ladder an operator reads.
 */
class BookView(val securityId: Int, val symbol: String) {

    /**
     * A book that is not synchronised renders as *waiting*, never as empty.
     *
     * Before the recovery feed existed this tool drew whatever increments it had happened to see,
     * which for a subscriber that joined mid-session is a book missing everything that came
     * before it — a plausible ladder that was simply wrong. Refusing to draw one is the whole
     * point of tracking the state.
     */
    fun render(book: AggregatedBook?, state: SyncState, depth: Int): String = buildString {
        append("── $symbol (id $securityId) ")
        append("─".repeat(if (symbol.length < 40) 40 - symbol.length else 1))
        append('\n')

        if (book == null) {
            append(
                when (state) {
                    SyncState.BUILDING -> "  (receiving snapshot…)\n"
                    else -> "  (waiting for a snapshot to synchronise)\n"
                },
            )
            return@buildString
        }

        append(String.format("%10s %-6s %10s │ %-10s %-10s %s%n", "qty", "ords", "bid", "ask", "qty", "ords"))

        val bidRows = book.levels(isBid = true, limit = depth)
        val askRows = book.levels(isBid = false, limit = depth)
        val rows = maxOf(bidRows.size, askRows.size)
        if (rows == 0) {
            append("  (empty)\n")
        } else {
            // An absent level prints blank rather than zero: "0 (0)" at no price reads as
            // real liquidity that happens to be empty, which is not what it means.
            for (i in 0 until rows) {
                val bid = bidRows.getOrNull(i)
                val ask = askRows.getOrNull(i)
                append(
                    String.format(
                        "%10s %-6s %10s │ %-10s %-10s %s%n",
                        bid?.qty?.toString() ?: "",
                        bid?.orders?.let { "($it)" } ?: "",
                        bid?.price?.let { PriceCodec.format(it) } ?: "",
                        ask?.price?.let { PriceCodec.format(it) } ?: "",
                        ask?.qty?.toString() ?: "",
                        ask?.orders?.let { "($it)" } ?: "",
                    ),
                )
            }
        }

        val spread = book.spread()
        append("  spread ")
        append(if (spread == null) "n/a" else PriceCodec.format(spread))
        book.lastTradePrice?.let { append("   last ${PriceCodec.format(it)} x ${book.lastTradeQty}") }
        append("   updates ${book.updates}\n")
    }
}
