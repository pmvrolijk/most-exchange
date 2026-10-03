package nl.lamia.most.exchange.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The recovery state machine, driven message by message.
 *
 * These are the orderings a loopback test cannot produce, because in one it is the same thread
 * that publishes the image and the increments: what happens when an update arrives *between* a
 * Begin and its End, and what happens when a cycle is cut in half. Both are ordinary on a real
 * feed, and both have a wrong answer that produces a book which looks entirely reasonable.
 */
class DepthFeedAssemblerTest {

    private val shard = 1
    private val security = 7
    private var l2 = 0L
    private var snap = 0L

    private fun DepthFeedAssembler.update(isBid: Boolean, price: Long, qty: Long, orders: Int = 1) =
        onDepthUpdate(security, shard, ++l2, isBid, price, qty, orders)

    /** A complete cycle for the levels given, stamped at the current incremental sequence. */
    private fun DepthFeedAssembler.snapshot(
        vararg levels: Triple<Boolean, Long, Long>,
        at: Long = l2,
        lastTradePrice: Long = DepthFeedAssembler.NO_PRICE,
        lastTradeQty: Long = 0,
        endAt: Long = at,
        endCount: Int = levels.size,
        withEnd: Boolean = true,
    ) {
        onSnapshotBegin(security, shard, ++snap, at, lastTradePrice, lastTradeQty, levels.size)
        for ((isBid, price, qty) in levels) {
            onSnapshotLevel(security, shard, ++snap, isBid, price, qty, 1)
        }
        if (withEnd) onSnapshotEnd(security, shard, ++snap, endAt, endCount)
    }

    @Test
    fun `an update arriving while the image is in flight is replayed on top of it`() {
        val a = DepthFeedAssembler()
        a.onSnapshotBegin(security, shard, ++snap, l2, DepthFeedAssembler.NO_PRICE, 0, 1)
        a.onSnapshotLevel(security, shard, ++snap, isBid = true, price = 100, aggregateQty = 10, orderCount = 1)
        // Published after the image was taken, and received before the image finished arriving.
        a.update(isBid = true, price = 99, qty = 5)
        a.onSnapshotEnd(security, shard, ++snap, l2 - 1, 1)

        val book = assertNotNull(a.book(security))
        assertEquals(100L, book.bestBid())
        assertEquals(2, book.levelCount(isBid = true), "the buffered update was lost")
    }

    @Test
    fun `an update the image already contains is discarded rather than replayed`() {
        // It has to arrive *during* the cycle to be buffered at all -- an update received before
        // any image is dropped for an entirely different reason, and a test that let it be
        // dropped that way would pass without ever exercising the rule it is named after.
        val a = DepthFeedAssembler()
        a.onSnapshotBegin(security, shard, ++snap, 10L, DepthFeedAssembler.NO_PRICE, 0, 1)
        // At the image's own sequence, so the image already accounts for it. Carrying a different
        // quantity on purpose: if it were replayed, the book would show 99 instead of 10.
        a.onDepthUpdate(security, shard, 10L, isBid = true, price = 100, aggregateQty = 99, orderCount = 1)
        a.onSnapshotLevel(security, shard, ++snap, isBid = true, price = 100, aggregateQty = 10, orderCount = 1)
        a.onSnapshotEnd(security, shard, ++snap, 10L, 1)

        val book = assertNotNull(a.book(security))
        assertEquals(1, book.levelCount(isBid = true))
        assertEquals(10L, book.levels(isBid = true, limit = 1).single().qty)
    }

    @Test
    fun `a cycle that never ends is discarded, and the next one installs`() {
        val a = DepthFeedAssembler()
        a.snapshot(Triple(true, 100L, 10L), withEnd = false)
        assertNull(a.book(security), "a half-received image is not a book")

        a.snapshot(Triple(true, 100L, 10L), Triple(false, 101L, 4L))
        val book = assertNotNull(a.book(security))
        assertEquals(101L, book.bestAsk())
    }

    @Test
    fun `an end that disagrees with its begin is discarded`() {
        // A truncated cycle whose middle was lost: every message individually valid, and the book
        // they describe never existed.
        val a = DepthFeedAssembler()
        a.snapshot(Triple(true, 100L, 10L), endCount = 2)

        assertNull(a.book(security))
        assertEquals(1L, a.snapshotsDiscarded)
    }

    @Test
    fun `an image stamped with a different sequence than its end is discarded`() {
        val a = DepthFeedAssembler()
        a.snapshot(Triple(true, 100L, 10L), at = 5, endAt = 9)

        assertNull(a.book(security))
        assertEquals(1L, a.snapshotsDiscarded)
    }

    @Test
    fun `more levels than promised abandons the cycle`() {
        val a = DepthFeedAssembler()
        a.onSnapshotBegin(security, shard, ++snap, 0, DepthFeedAssembler.NO_PRICE, 0, 1)
        a.onSnapshotLevel(security, shard, ++snap, true, 100, 10, 1)
        a.onSnapshotLevel(security, shard, ++snap, true, 99, 5, 1)
        a.onSnapshotEnd(security, shard, ++snap, 0, 1)

        assertNull(a.book(security))
        assertTrue(a.snapshotsDiscarded > 0)
    }

    @Test
    fun `a snapshot is ignored while the book is synchronised`() {
        // The trap: the image is consistent at its own sequence, but updates past that sequence
        // have already been applied straight to the book and were never buffered. Installing it
        // would rewind the book to a state that is older than what the subscriber already had --
        // and every message involved would be perfectly valid.
        val a = DepthFeedAssembler()
        a.snapshot(Triple(true, 100L, 10L))
        a.update(isBid = true, price = 100, qty = 25)

        val stale = l2 - 1
        a.snapshot(Triple(true, 100L, 10L), at = stale)

        val book = assertNotNull(a.book(security))
        assertEquals(25L, book.levels(isBid = true, limit = 1).single().qty, "the book was rewound")
    }

    @Test
    fun `a snapshot at or ahead of everything applied is installed, not ignored`() {
        // The other half of the rule above, and the case that stranded a real consumer.
        //
        // A subscriber joining a stream can be handed whatever is still in the buffer, so the
        // first complete cycle it sees may be the OLDEST -- an empty book from before a recovery.
        // Under "ignore snapshots while synchronised" it would then discard every later image and
        // wait for an increment to rescue it. On a quiet book none comes, and it shows an empty
        // ladder for ever on a market with real depth, with every message it received valid.
        //
        // Installing is safe precisely when the image is at or past everything applied: it already
        // contains all of it, so there is nothing to rewind.
        val a = DepthFeedAssembler()
        a.snapshot()                                   // synchronised, and empty
        assertEquals(0, assertNotNull(a.book(security)).levels(isBid = true, limit = 5).size)

        a.snapshot(Triple(true, 99L, 15L), Triple(false, 101L, 6L))

        val book = assertNotNull(a.book(security))
        assertEquals(99L, book.bestBid(), "a newer image was ignored and the book left empty")
        assertEquals(101L, book.bestAsk())
    }

    @Test
    fun `an update applied after the image still wins over that image`() {
        // The guard is on the image's sequence, not on its arrival: an increment past the image
        // has been applied and was never buffered, so the image must not come back over it. This
        // is the case the exception above must not have widened.
        val a = DepthFeedAssembler()
        a.snapshot(Triple(true, 100L, 10L))
        val imageSeq = l2
        a.update(isBid = true, price = 100, qty = 25)

        a.snapshot(Triple(true, 100L, 10L), at = imageSeq)

        val book = assertNotNull(a.book(security))
        assertEquals(25L, book.levels(isBid = true, limit = 1).single().qty, "the book was rewound")
    }

    @Test
    fun `a gap on one shard leaves another shard's books alone`() {
        // Several shards may share one multicast group; their sequences are separate streams, and
        // one falling behind says nothing about the other.
        val a = DepthFeedAssembler()
        a.onSnapshotBegin(1, 1, 1, 0, DepthFeedAssembler.NO_PRICE, 0, 0)
        a.onSnapshotEnd(1, 1, 2, 0, 0)
        a.onSnapshotBegin(2, 2, 1, 0, DepthFeedAssembler.NO_PRICE, 0, 0)
        a.onSnapshotEnd(2, 2, 2, 0, 0)

        a.onDepthUpdate(1, 1, 1, true, 100, 1, 1)
        a.onDepthUpdate(2, 2, 1, true, 100, 1, 1)
        // Shard 1 skips ahead; shard 2 stays contiguous.
        a.onDepthUpdate(1, 1, 9, true, 99, 1, 1)
        a.onDepthUpdate(2, 2, 2, true, 99, 1, 1)

        assertEquals(SyncState.WAITING, a.state(1))
        assertEquals(SyncState.SYNCHRONISED, a.state(2))
    }

    @Test
    fun `an update with no image to apply it to is counted, not applied`() {
        val a = DepthFeedAssembler()
        a.update(isBid = true, price = 100, qty = 10)

        assertNull(a.book(security))
        assertEquals(1L, a.updatesWithoutImage)
    }

    @Test
    fun `a cycle whose buffer overflows is abandoned rather than grown`() {
        val a = DepthFeedAssembler(maxPending = 2)
        a.onSnapshotBegin(security, shard, ++snap, 0, DepthFeedAssembler.NO_PRICE, 0, 1)
        repeat(3) { a.update(isBid = true, price = 100 + it.toLong(), qty = 1) }
        a.onSnapshotLevel(security, shard, ++snap, true, 100, 10, 1)
        a.onSnapshotEnd(security, shard, ++snap, 0, 1)

        assertNull(a.book(security))
        assertTrue(a.snapshotsDiscarded > 0)
    }

    @Test
    fun `a trade is kept even while the depth cannot be trusted`() {
        val a = DepthFeedAssembler()
        a.onLastTrade(security, shard, 1, price = 100, qty = 4)
        a.snapshot(Triple(true, 99L, 1L))

        val book = assertNotNull(a.book(security))
        assertEquals(100L, book.lastTradePrice)
    }
}
