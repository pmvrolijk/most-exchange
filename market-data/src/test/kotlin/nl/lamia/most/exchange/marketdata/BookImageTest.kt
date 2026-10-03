package nl.lamia.most.exchange.marketdata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Rebuilding a book from an image, which is the only way this process can recover one.
 *
 * Depth here is derived entirely from the engine's book event stream. A restored engine publishes
 * no events for the orders it restored, so before the image existed a market data process that
 * restarted alongside the engine came back with an empty book and stayed empty until the next order
 * arrived on that security — showing no liquidity on a market that had plenty, with nothing about
 * it looking wrong.
 */
class BookImageTest {

    private fun level(side: Byte, price: Long, qty: Long, orders: Int) =
        Triple(side, price, qty to orders)

    @Test
    fun `an image installs a book this process never saw built`() {
        val pub = RecordingPublisher()
        val service = newService(pub)
        val feeder = BookEventFeeder(service)

        // Not one of these levels was built from an event here: this is exactly the position a
        // market data process is in after restarting into a running exchange.
        feeder.bookImage(
            1,
            listOf(
                level(BUY, 99, 15, 1),
                level(BUY, 98, 40, 3),
                level(SELL, 101, 6, 1),
                level(SELL, 102, 20, 2),
            ),
        )

        assertEquals(1L, service.imagesApplied)
        assertEquals(0L, service.imagesDiscarded)

        // The aggregates, level for level, as a consumer would read them off the snapshot feed.
        // Bids best-first, as the snapshot feed always walks them.
        assertEquals(
            listOf(
                Depth(1, BUY, 99, 15, 1),
                Depth(1, BUY, 98, 40, 3),
                Depth(1, SELL, 101, 6, 1),
                Depth(1, SELL, 102, 20, 2),
            ),
            pub.snapshotLevels,
        )
    }

    @Test
    fun `an image is published downstream at once rather than waiting for the snapshot cycle`() {
        // Every L2 and L1 consumer is looking at the same empty book this process just was, so
        // holding the recovered book until the next cycle would leave them all wrong for no reason.
        val pub = RecordingPublisher()
        val service = newService(pub)
        val feeder = BookEventFeeder(service)

        feeder.bookImage(1, listOf(level(BUY, 99, 15, 1), level(SELL, 101, 6, 1)))

        assertEquals(1, pub.snapshotEnds.size, "the recovered book was not snapshotted")
        assertEquals(Top(1, 99, 15, 101, 6), pub.top.last(), "top of book was not republished")
    }

    @Test
    fun `an image is republished as increments, not only as a snapshot`() {
        // The whole point, and the thing that was missing first time round. A subscriber that is
        // already synchronised IGNORES snapshots -- deliberately, so an image cannot rewind a book
        // whose later increments were applied straight through. So a console that synchronised to
        // this process's empty book a moment before the image arrived would ignore every snapshot
        // that followed and sit on an empty book for ever, on a market with real depth.
        val pub = RecordingPublisher()
        val service = newService(pub)
        val feeder = BookEventFeeder(service)

        feeder.bookImage(1, listOf(level(BUY, 99, 15, 1), level(SELL, 101, 6, 1)))

        assertEquals(
            listOf(Depth(1, BUY, 99, 15, 1), Depth(1, SELL, 101, 6, 1)),
            pub.depth,
            "the recovered levels were not published as increments",
        )
    }

    @Test
    fun `a level the image removed is zeroed, not left to linger`() {
        // An increment carries a level's absolute quantity, so a level the image *changes* is
        // self-correcting. One it removes is not: it would stop being mentioned and would sit in a
        // synchronised consumer's book undisturbed, showing liquidity that no longer exists.
        val pub = RecordingPublisher()
        val service = newService(pub)
        val feeder = BookEventFeeder(service)

        feeder.orderAdded(1, orderId = 1, price = 97, qty = 50, side = BUY)
        pub.depth.clear()

        feeder.bookImage(1, listOf(level(BUY, 99, 15, 1)))

        assertEquals(
            listOf(Depth(1, BUY, 97, 0, 0), Depth(1, BUY, 99, 15, 1)),
            pub.depth,
            "the vacated level was not zeroed before the new one was published",
        )
    }

    @Test
    fun `an image that changes nothing still republishes the levels it holds`() {
        // Safe to repeat is a property worth having: the operator command exists to be run when
        // nobody is sure, and it must not depend on the book being wrong first.
        val pub = RecordingPublisher()
        val service = newService(pub)
        val feeder = BookEventFeeder(service)

        feeder.orderAdded(1, orderId = 1, price = 99, qty = 15, side = BUY)
        pub.depth.clear()

        feeder.bookImage(1, listOf(level(BUY, 99, 15, 1)))

        assertEquals(listOf(Depth(1, BUY, 99, 15, 1)), pub.depth, "no zeroing of an unchanged level")
    }

    @Test
    fun `an image replaces the book rather than merging into it`() {
        // The engine sends one when this process cannot be trusted to know the book, so whatever is
        // here is stale by definition. Merging would leave the old state at every level the image
        // does not mention — a book that is wrong only where nothing draws attention to it.
        val pub = RecordingPublisher()
        val service = newService(pub)
        val feeder = BookEventFeeder(service)

        feeder.orderAdded(1, orderId = 1, price = 50, qty = 999, side = BUY)
        feeder.bookImage(1, listOf(level(BUY, 99, 15, 1)))

        val prices = pub.snapshotLevels.map { it.price }
        assertEquals(listOf(99L), prices, "a stale level survived the image")
        assertEquals(Top(1, 99, 15, MarketDataService.NO_PRICE, 0), pub.top.last())
    }

    @Test
    fun `an empty book still arrives as a bracketed cycle`() {
        // "There is no liquidity" and "I cannot yet know" are different answers. A consumer that
        // cannot tell them apart sits on an empty book waiting for an image that already came.
        val pub = RecordingPublisher()
        val service = newService(pub)
        val feeder = BookEventFeeder(service)

        feeder.orderAdded(1, orderId = 1, price = 50, qty = 999, side = BUY)
        feeder.bookImage(1, emptyList())

        assertEquals(1L, service.imagesApplied)
        assertTrue(pub.snapshotLevels.isEmpty(), "an empty image must install an empty book")
        assertEquals(
            Top(1, MarketDataService.NO_PRICE, 0, MarketDataService.NO_PRICE, 0),
            pub.top.last(),
        )
    }

    @Test
    fun `a cycle that lost its middle is discarded rather than installed`() {
        val pub = RecordingPublisher()
        val service = newService(pub)
        val feeder = BookEventFeeder(service)

        // Two levels sent, three declared: the shape of a cycle whose middle was dropped. Installing
        // it would produce a book that is wrong and entirely plausible.
        feeder.bookImage(1, listOf(level(BUY, 99, 15, 1), level(SELL, 101, 6, 1)), declaredCount = 3)

        assertEquals(0L, service.imagesApplied)
        assertEquals(1L, service.imagesDiscarded)
        assertTrue(pub.snapshotEnds.isEmpty(), "a discarded image must not be republished")
    }

    @Test
    fun `a truncated cycle leaves an empty book rather than a merged one`() {
        // Cleared at Begin, not at End. An empty book is visibly wrong and recovers on the next
        // image; a book half-replaced by a cycle that stopped early looks entirely normal.
        val pub = RecordingPublisher()
        val service = newService(pub)
        val feeder = BookEventFeeder(service)

        feeder.orderAdded(1, orderId = 1, price = 50, qty = 999, side = BUY)
        feeder.bookImageBeginOnly(1, declaredCount = 4)

        // The stale level is gone, and no image was ever applied.
        assertEquals(0L, service.imagesApplied)
        feeder.bookImage(1, listOf(level(BUY, 99, 15, 1)))
        assertEquals(1L, service.imagesApplied)
        assertEquals(listOf(99L), pub.snapshotLevels.map { it.price })
    }

    @Test
    fun `an image does not count as a gap in the feed sequence`() {
        // The image's seqNum is the baseline it is consistent at, not a feed sequence: the engine
        // consumes no sequence numbers to send one. Counting them would report a gap on every
        // image, and the counters exist to tell a real gap from a quiet feed.
        val pub = RecordingPublisher()
        val service = newService(pub)
        val feeder = BookEventFeeder(service)

        feeder.orderAdded(1, orderId = 1, price = 100, qty = 10, side = BUY)
        val before = service.gapsDetected
        feeder.bookImage(1, listOf(level(BUY, 99, 15, 1), level(SELL, 101, 6, 1)))
        feeder.orderAdded(1, orderId = 2, price = 100, qty = 5, side = BUY)

        assertEquals(before, service.gapsDetected, "an image was counted as a gap")
    }

    @Test
    fun `an image for another shard is rejected`() {
        val pub = RecordingPublisher()
        val service = newService(pub)
        val feeder = BookEventFeeder(service)

        feeder.bookImage(1, listOf(level(BUY, 99, 15, 1)), shardIdOverride = SHARD_ID + 1)

        assertEquals(0L, service.imagesApplied)
        assertTrue(service.foreignShardEvents > 0)
    }
}
