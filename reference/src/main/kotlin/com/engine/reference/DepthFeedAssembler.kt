package com.engine.reference

import org.agrona.collections.Int2ObjectHashMap

/** Where one security's book stands: only [SYNCHRONISED] means the depth can be believed. */
enum class SyncState {
    /** No usable image. Increments are being discarded because there is nothing to apply them to. */
    WAITING,

    /** A snapshot is arriving. Increments are buffered so none is lost while it is installed. */
    BUILDING,

    /** An image was installed and every increment since has been applied in order. */
    SYNCHRONISED,
}

/**
 * Rebuilds L2 books from the incremental feed and the periodic snapshot (Design.md §5).
 *
 * The problem this exists to solve: under `MaxMulticastFlowControl` — the correct setting, and one
 * the design says explicitly not to change — the publisher runs at the speed of the *fastest*
 * receiver, so a slow subscriber takes an unrecoverable gap. Recovery is therefore a subscriber
 * responsibility, and a subscriber that joins mid-session has, in effect, missed everything: it is
 * the same problem as a gap, with the same fix.
 *
 * The splice is the `l2SeqNum` a snapshot carries — the incremental sequence the image was taken
 * at. Increments that arrive while the image is being received are buffered, and those at or below
 * that sequence are then discarded because the image already contains them. Applying them twice
 * would be harmless for an aggregate that is replaced rather than accumulated, but discarding them
 * is what makes the rule the same for every message rather than one that happens to be safe here.
 *
 * **A snapshot is ignored while a security is synchronised.** The image is consistent at its own
 * sequence, but increments past that sequence have already been applied straight to the book and
 * were never buffered, so installing it would silently rewind the book to an older state. That is
 * the one ordering mistake in this class that would produce a plausible, wrong book.
 *
 * Sequence tracking is per shard and per stream: several shards may share one multicast group, and
 * the snapshot stream numbers independently of the incremental one.
 */
class DepthFeedAssembler(private val maxPending: Int = DEFAULT_MAX_PENDING) {

    private val l2Sequences = FeedSequenceTracker()
    private val snapshotSequences = FeedSequenceTracker()
    private val states = Int2ObjectHashMap<SecurityFeed>()

    val gapsDetected: Long get() = l2Sequences.gapsDetected + snapshotSequences.gapsDetected
    val messagesMissed: Long get() = l2Sequences.messagesMissed + snapshotSequences.messagesMissed

    var snapshotsApplied = 0L
        private set

    /** Cycles abandoned: a truncated one, a mismatched end, or one whose buffer overflowed. */
    var snapshotsDiscarded = 0L
        private set

    /** Books dropped because a gap made their aggregates untrustworthy. */
    var desynchronisations = 0L
        private set

    /** Increments discarded for a security with no image to apply them to. */
    var updatesWithoutImage = 0L
        private set

    /** The book, or null while it cannot be trusted — the caller cannot render a partial one by accident. */
    fun book(securityId: Int): AggregatedBook? =
        states[securityId]?.takeIf { it.state == SyncState.SYNCHRONISED }?.book

    fun state(securityId: Int): SyncState = states[securityId]?.state ?: SyncState.WAITING

    /** Every security seen on the feed, synchronised or not. */
    fun securities(): List<Int> = states.keys.sorted()

    /**
     * The shard a security's depth arrived from, learned from the messages themselves rather than
     * from configuration — which is what lets one subscriber follow several shards on one group.
     */
    fun shardOf(securityId: Int): Int? = states[securityId]?.shardId

    fun onDepthUpdate(
        securityId: Int,
        shardId: Int,
        seqNum: Long,
        isBid: Boolean,
        price: Long,
        aggregateQty: Long,
        orderCount: Int,
    ) {
        if (l2Sequences.accept(shardId, seqNum) > 0L) desynchronise(shardId)
        val feed = feedFor(securityId, shardId)
        when (feed.state) {
            SyncState.SYNCHRONISED -> feed.book.applyDepth(isBid, price, aggregateQty, orderCount)
            SyncState.BUILDING -> feed.buffer(seqNum, isBid, price, aggregateQty, orderCount, maxPending)
                .also { if (!it) abandon(feed) }
            SyncState.WAITING -> updatesWithoutImage++
        }
    }

    /**
     * A trade is not depth. It is applied whatever the sync state, because it is one message that
     * either arrived or did not, and holding it back would hide a fact the feed did deliver.
     */
    fun onLastTrade(securityId: Int, shardId: Int, seqNum: Long, price: Long, qty: Long) {
        feedFor(securityId, shardId).book.applyTrade(price, qty)
    }

    fun onSnapshotBegin(
        securityId: Int,
        shardId: Int,
        seqNum: Long,
        l2SeqNum: Long,
        lastTradePrice: Long,
        lastTradeQty: Long,
        levelCount: Int,
    ) {
        snapshotSequences.accept(shardId, seqNum)
        val feed = feedFor(securityId, shardId)
        if (feed.state == SyncState.SYNCHRONISED) return
        feed.state = SyncState.BUILDING
        feed.staging.clear()
        feed.pending.clear()
        feed.imageSeqNum = l2SeqNum
        feed.expectedLevels = levelCount
        feed.lastTradePrice = lastTradePrice
        feed.lastTradeQty = lastTradeQty
    }

    fun onSnapshotLevel(
        securityId: Int,
        shardId: Int,
        seqNum: Long,
        isBid: Boolean,
        price: Long,
        aggregateQty: Long,
        orderCount: Int,
    ) {
        snapshotSequences.accept(shardId, seqNum)
        val feed = states[securityId] ?: return
        if (feed.state != SyncState.BUILDING) return
        // One more than promised means the cycle is not the one that began; stop rather than
        // install an image whose contents were never agreed.
        if (feed.staging.size >= feed.expectedLevels) {
            abandon(feed)
            return
        }
        feed.staging += StagedLevel(isBid, price, aggregateQty, orderCount)
    }

    fun onSnapshotEnd(
        securityId: Int,
        shardId: Int,
        seqNum: Long,
        l2SeqNum: Long,
        levelCount: Int,
    ) {
        snapshotSequences.accept(shardId, seqNum)
        val feed = states[securityId] ?: return
        if (feed.state != SyncState.BUILDING) return

        // Begin and End must describe the same image, and every level promised must have arrived.
        // Anything else is a cycle that lost messages in the middle, and a book assembled from one
        // is wrong in a way nothing downstream could detect.
        if (l2SeqNum != feed.imageSeqNum ||
            levelCount != feed.expectedLevels ||
            feed.staging.size != feed.expectedLevels
        ) {
            abandon(feed)
            return
        }

        feed.book.clearDepth()
        for (level in feed.staging) {
            feed.book.applyDepth(level.isBid, level.price, level.qty, level.orders)
        }
        if (feed.lastTradePrice != NO_PRICE) {
            feed.book.applyTrade(feed.lastTradePrice, feed.lastTradeQty)
        }
        // Everything the image already contains is dropped; the rest is replayed in arrival order,
        // which is publication order, because Aeron does not reorder within a stream.
        for (update in feed.pending) {
            if (update.seqNum > feed.imageSeqNum) {
                feed.book.applyDepth(update.isBid, update.price, update.qty, update.orders)
            }
        }
        feed.staging.clear()
        feed.pending.clear()
        feed.state = SyncState.SYNCHRONISED
        snapshotsApplied++
    }

    /**
     * A gap on one shard's incremental stream invalidates every book on that shard: the missed
     * message could have been for any of them, and there is no way to tell which. Books on other
     * shards are untouched — their sequences are separate streams that lost nothing.
     */
    private fun desynchronise(shardId: Int) {
        for (feed in states.values) {
            if (feed.shardId != shardId || feed.state == SyncState.WAITING) continue
            feed.book.clearDepth()
            feed.staging.clear()
            feed.pending.clear()
            feed.state = SyncState.WAITING
            desynchronisations++
        }
    }

    private fun abandon(feed: SecurityFeed) {
        feed.staging.clear()
        feed.pending.clear()
        feed.state = SyncState.WAITING
        snapshotsDiscarded++
    }

    private fun feedFor(securityId: Int, shardId: Int): SecurityFeed =
        states.computeIfAbsent(securityId) { SecurityFeed(AggregatedBook(securityId), shardId) }

    private class SecurityFeed(val book: AggregatedBook, val shardId: Int) {
        var state = SyncState.WAITING
        var imageSeqNum = 0L
        var expectedLevels = 0
        var lastTradePrice = NO_PRICE
        var lastTradeQty = 0L
        val staging = ArrayList<StagedLevel>()
        val pending = ArrayList<PendingUpdate>()

        fun buffer(
            seqNum: Long,
            isBid: Boolean,
            price: Long,
            qty: Long,
            orders: Int,
            limit: Int,
        ): Boolean {
            if (pending.size >= limit) return false
            pending += PendingUpdate(seqNum, isBid, price, qty, orders)
            return true
        }
    }

    private class StagedLevel(val isBid: Boolean, val price: Long, val qty: Long, val orders: Int)

    private class PendingUpdate(
        val seqNum: Long,
        val isBid: Boolean,
        val price: Long,
        val qty: Long,
        val orders: Int,
    )

    companion object {
        /**
         * No trade yet, matching the market data process's own absent-price convention. A real
         * price of zero is possible on a ladder whose floor is zero, so absence needs a value no
         * price can take.
         */
        const val NO_PRICE: Long = Long.MIN_VALUE

        /**
         * Bounded: a security whose snapshot cycle never completes must not buffer for ever. On
         * overflow the cycle is abandoned and the next one is waited for, which costs one snapshot
         * interval and cannot cost memory.
         */
        const val DEFAULT_MAX_PENDING: Int = 8192
    }
}
