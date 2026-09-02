package com.engine.control

import com.engine.reference.AggregatedBook
import com.engine.reference.DepthFeedAssembler
import com.engine.reference.DepthFeedDecoder
import com.engine.reference.SyncState
import org.agrona.DirectBuffer
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * What the console needs about a security that the depth feed does not carry.
 *
 * Phase and halt come from L3, depth from L2 — different feeds answering different questions. They
 * are joined here because an operator reading a ladder has to know whether that market is open:
 * a halted security keeps a full, correct book, and shown on its own it looks exactly like a
 * trading one.
 */
data class BookContext(val symbol: String? = null, val phase: String? = null, val halted: Boolean = false)

/** One price level, as the console reads it. */
data class BookLevel(val price: Long, val qty: Long, val orders: Int)

/**
 * A book as it stood at one instant, ready to send.
 *
 * Immutable, and built on the feed thread. The alternative — letting a web request walk the live
 * `AggregatedBook` — reads a `TreeMap` that another thread is mutating, which is not merely a race
 * on a number but a traversal that can miss or repeat levels. Publishing a finished value and
 * swapping the reference is what makes every reader safe without a lock anywhere near the feed.
 *
 * `synchronised` is the field that matters most. False means the depth below is **not** the book:
 * either no snapshot has arrived yet or a gap invalidated it, and a console must say so rather than
 * draw an empty ladder.
 */
data class BookImage(
    val securityId: Int,
    val symbol: String?,
    val shardId: Int?,
    /**
     * Changes only when this book's content changes.
     *
     * A counter that ticked on every interval would make an idle book indistinguishable from a
     * busy one, and every console would receive four images a second per security for ever. The
     * version is what lets a subscriber be sent only what actually moved.
     */
    val version: Long,
    val at: String,
    val synchronised: Boolean,
    val state: String,
    val bids: List<BookLevel>,
    val asks: List<BookLevel>,
    val bestBid: Long?,
    val bestAsk: Long?,
    val spread: Long?,
    val lastTradePrice: Long?,
    val lastTradeQty: Long,
    /** From L3, not from depth: a book alone cannot tell you the market is closed or halted. */
    val phase: String?,
    val halted: Boolean,
    /** Levels held beyond the ones sent, so "top 20 of 300" is distinguishable from "all 20". */
    val bidLevelsTotal: Int,
    val askLevelsTotal: Int,
)

/**
 * Feed health, for the status screen and for anyone wondering why a book will not synchronise.
 *
 * [subscribed] means a subscription exists, which is **not** the same as data arriving: a media
 * driver that dies takes the feed with it while every object on this side still looks fine. That
 * distinction has cost this project time more than once, so silence is reported as a number rather
 * than left to be inferred from books that quietly stop updating.
 */
data class DepthFeedStatus(
    val subscribed: Boolean,
    val detail: String,
    val messagesSeen: Long,
    /** How long the feed has been silent, or null if nothing has ever arrived. */
    val silentMs: Long?,
    val securities: List<Int>,
    val synchronised: Int,
    val snapshotsApplied: Long,
    val snapshotsDiscarded: Long,
    val desynchronisations: Long,
    val gapsDetected: Long,
    val messagesMissed: Long,
)

/**
 * Rebuilds the shard's books from the market data feeds and keeps a conflated image of each.
 *
 * The control plane is an ordinary L2 subscriber here — it uses `reference`'s
 * [DepthFeedAssembler], the same code the operator CLI and any future FIX market data adapter use,
 * so a book on the console cannot disagree with a book anywhere else. Nothing about depth is
 * re-derived in this module.
 *
 * **Conflation, not streaming.** The feed can carry 100k updates a second and an operator can read
 * perhaps four. Images are published on a fixed interval and every update in between is simply
 * folded into the next one, which is what a human wants and what keeps a browser off the feed's
 * critical path entirely. It also means a slow console costs the exchange nothing: there is no
 * back pressure path from a browser to this thread, by construction.
 */
class DepthMonitor(
    private val maxLevels: Int = DEFAULT_MAX_LEVELS,
    private val contextOf: (Int) -> BookContext = { BookContext() },
) {
    private val assembler = DepthFeedAssembler()
    private val decoder = DepthFeedDecoder(assembler)
    private val images = ConcurrentHashMap<Int, BookImage>()


    /** Feed thread only, so no synchronisation; read across threads through [status]. */
    private var messages = 0L
    private var messagesAtLastImage = 0L

    @Volatile
    private var lastMessageAt = 0L

    /** Called on the feed thread for every market data message. */
    fun onMessage(buffer: DirectBuffer, offset: Int, length: Int) {
        decoder.onMessage(buffer, offset, length)
        messages++
    }

    /**
     * Publishes an image of every book. **Feed thread only**, between messages, for the same
     * reason the market data process takes its snapshot on its own poll thread: an image assembled
     * while updates are landing is a book that never existed.
     */
    fun publishImages() {
        // Liveness is sampled here rather than stamped per message: at 100k messages a second a
        // clock read per message is real cost for a value nobody can use at finer resolution than
        // the publish interval.
        if (messages != messagesAtLastImage) {
            messagesAtLastImage = messages
            lastMessageAt = System.currentTimeMillis()
        }
        val now = Instant.now().toString()
        for (securityId in assembler.securities()) {
            val previous = images[securityId]
            // Built at the previous version and timestamp so that equality compares *content*.
            // If nothing moved, the old image is kept: same version, and no subscriber is woken.
            val candidate = imageOf(securityId, previous?.version ?: 1L, previous?.at ?: now)
            if (candidate != previous) {
                images[securityId] = candidate.copy(version = (previous?.version ?: 0L) + 1L, at = now)
            }
        }
    }

    fun image(securityId: Int): BookImage? = images[securityId]

    fun allImages(): List<BookImage> = images.values.sortedBy { it.securityId }

    fun status(subscribed: Boolean, detail: String) = DepthFeedStatus(
        subscribed = subscribed,
        detail = detail,
        messagesSeen = messages,
        silentMs = if (lastMessageAt == 0L) null else System.currentTimeMillis() - lastMessageAt,
        securities = assembler.securities(),
        synchronised = assembler.securities().count { assembler.state(it) == SyncState.SYNCHRONISED },
        snapshotsApplied = assembler.snapshotsApplied,
        snapshotsDiscarded = assembler.snapshotsDiscarded,
        desynchronisations = assembler.desynchronisations,
        gapsDetected = assembler.gapsDetected,
        messagesMissed = assembler.messagesMissed,
    )

    private fun imageOf(securityId: Int, version: Long, at: String): BookImage {
        val state = assembler.state(securityId)
        val book: AggregatedBook? = assembler.book(securityId)
        val context = contextOf(securityId)
        return BookImage(
            securityId = securityId,
            symbol = context.symbol,
            shardId = assembler.shardOf(securityId),
            version = version,
            at = at,
            synchronised = book != null,
            state = state.name,
            // An unsynchronised book sends no depth at all rather than the last good one. Stale
            // depth that looks current is the failure this whole feed exists to prevent, and the
            // client is told which state it is in rather than being left to infer it from a count.
            bids = book?.levels(isBid = true, limit = maxLevels).orEmpty().map { it.toLevel() },
            asks = book?.levels(isBid = false, limit = maxLevels).orEmpty().map { it.toLevel() },
            bestBid = book?.bestBid(),
            bestAsk = book?.bestAsk(),
            spread = book?.spread(),
            // The last trade survives a desynchronisation: it was one message that arrived, not an
            // aggregate that can no longer be trusted.
            lastTradePrice = book?.lastTradePrice,
            lastTradeQty = book?.lastTradeQty ?: 0L,
            bidLevelsTotal = book?.levelCount(isBid = true) ?: 0,
            askLevelsTotal = book?.levelCount(isBid = false) ?: 0,
            phase = context.phase,
            halted = context.halted,
        )
    }

    private fun AggregatedBook.Level.toLevel() = BookLevel(price, qty, orders)

    companion object {
        /**
         * A ladder is read by a person. A book can hold thousands of levels and sending them all,
         * four times a second, to a screen showing twenty would be a waste that grows with the
         * book rather than with what anyone looks at.
         */
        const val DEFAULT_MAX_LEVELS: Int = 25
    }
}
