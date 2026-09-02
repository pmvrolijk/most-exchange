package com.engine.marketdata

import com.engine.reference.SyncState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The L2 recovery feed, from the engine's book events to a subscriber's rebuilt book.
 *
 * Both ends are the shipped code: [SbeFeedPublisher] encodes, `DepthFeedDecoder` decodes and
 * `DepthFeedAssembler` reassembles. The assertion that carries the weight is always the same one —
 * the book a subscriber ends up with equals the book the market data process is holding — because
 * that is the only property a consumer actually depends on, and every way of getting it wrong
 * produces a book that looks perfectly plausible.
 */
class DepthSnapshotTest {

    private fun service(subscriber: LoopbackSubscriber, vararg securityIds: Int = intArrayOf(1)) =
        MarketDataService(
            SHARD_ID,
            securityIds.map { MarketDataSecurity(it, priceFloor = 0L, tickSize = 1L, levelCount = 1024) },
            subscriber.publisher,
        )

    private fun assertMatchesPublisher(service: MarketDataService, subscriber: LoopbackSubscriber, securityId: Int = 1) {
        val published = assertNotNull(service.bookOf(securityId))
        val received = assertNotNull(
            subscriber.assembler.book(securityId),
            "the subscriber has no book it is willing to show",
        )
        assertEquals(published.levels(isBid = true), received.levelsOf(isBid = true), "bids")
        assertEquals(published.levels(isBid = false), received.levelsOf(isBid = false), "asks")
    }

    @Test
    fun `a subscriber joining after the book exists rebuilds it exactly`() {
        // The open issue this feed was written for: before it, everything below was invisible to
        // anyone who was not already listening, and the e2e test worked around it by starting its
        // inspector before any depth existed.
        val subscriber = LoopbackSubscriber()
        val service = service(subscriber)
        subscriber.connected = false

        val feeder = BookEventFeeder(service)
        feeder.orderAdded(1, orderId = 1, price = 100, qty = 10, side = BUY)
        feeder.orderAdded(1, orderId = 2, price = 99, qty = 5, side = BUY)
        feeder.orderAdded(1, orderId = 3, price = 101, qty = 7, side = SELL)
        feeder.orderAdded(1, orderId = 4, price = 100, qty = 3, side = BUY)

        subscriber.connected = true
        assertNull(subscriber.assembler.book(1), "nothing seen yet is not an empty book")

        service.publishNextSnapshot()

        assertEquals(SyncState.SYNCHRONISED, subscriber.assembler.state(1))
        assertMatchesPublisher(service, subscriber)
        assertEquals(1L, subscriber.assembler.snapshotsApplied)
    }

    @Test
    fun `increments after the image are applied on top of it`() {
        val subscriber = LoopbackSubscriber()
        val service = service(subscriber)
        subscriber.connected = false
        val feeder = BookEventFeeder(service)
        feeder.orderAdded(1, 1, price = 100, qty = 10, side = BUY)

        subscriber.connected = true
        service.publishNextSnapshot()

        feeder.orderAdded(1, 2, price = 98, qty = 4, side = BUY)
        feeder.orderAdded(1, 3, price = 102, qty = 6, side = SELL)
        feeder.orderRemoved(1, 1, price = 100, leavesQty = 10, side = BUY)

        assertMatchesPublisher(service, subscriber)
    }

    @Test
    fun `a gap invalidates the book and the next snapshot repairs it`() {
        // Under MaxMulticastFlowControl a slow subscriber takes an unrecoverable gap by design.
        // What must not happen is that it keeps rendering the stale book as if it were current.
        val subscriber = LoopbackSubscriber()
        val service = service(subscriber)
        val feeder = BookEventFeeder(service)

        feeder.orderAdded(1, 1, price = 100, qty = 10, side = BUY)
        service.publishNextSnapshot()
        assertEquals(SyncState.SYNCHRONISED, subscriber.assembler.state(1))

        subscriber.drop(1)
        feeder.orderAdded(1, 2, price = 99, qty = 5, side = BUY)
        feeder.orderAdded(1, 3, price = 98, qty = 5, side = BUY)

        assertEquals(SyncState.WAITING, subscriber.assembler.state(1))
        assertNull(subscriber.assembler.book(1), "a book with a hole in it must not be shown")
        assertTrue(subscriber.assembler.desynchronisations > 0)

        service.publishNextSnapshot()
        assertEquals(SyncState.SYNCHRONISED, subscriber.assembler.state(1))
        assertMatchesPublisher(service, subscriber)
    }

    @Test
    fun `an empty book synchronises as empty rather than as unknown`() {
        // "No liquidity" and "I do not know" are different answers and a console must not confuse
        // them, so an empty book is still bracketed and still installs.
        val subscriber = LoopbackSubscriber()
        val service = service(subscriber)

        service.publishNextSnapshot()

        assertEquals(SyncState.SYNCHRONISED, subscriber.assembler.state(1))
        assertTrue(assertNotNull(subscriber.assembler.book(1)).isEmpty())
    }

    @Test
    fun `the image carries the last trade so a late joiner is not told the security never traded`() {
        val subscriber = LoopbackSubscriber()
        val service = service(subscriber)
        subscriber.connected = false
        val feeder = BookEventFeeder(service)
        feeder.orderAdded(1, 1, price = 100, qty = 10, side = BUY)
        feeder.tradeExecuted(1, price = 100, qty = 4, aggressorSide = SELL)

        subscriber.connected = true
        service.publishNextSnapshot()

        val book = assertNotNull(subscriber.assembler.book(1))
        assertEquals(100L, book.lastTradePrice)
        assertEquals(4L, book.lastTradeQty)
    }

    @Test
    fun `securities are snapshotted in rotation, one per call`() {
        val subscriber = LoopbackSubscriber()
        val service = service(subscriber, 1, 2)

        assertEquals(1, service.publishNextSnapshot())
        assertEquals(2, service.publishNextSnapshot())
        assertEquals(1, service.publishNextSnapshot())
    }

    @Test
    fun `the image is stamped with the depth sequence it was taken at`() {
        // The splice: everything at or below this sequence is in the image, everything above it
        // must be replayed on top. A value read after the levels were written would swallow the
        // updates in between and no consumer could tell.
        val publisher = RecordingPublisher()
        val service = newService(publisher)
        val feeder = BookEventFeeder(service)
        feeder.orderAdded(1, 1, price = 100, qty = 10, side = BUY)
        feeder.orderAdded(1, 2, price = 99, qty = 5, side = BUY)
        val depthPublished = publisher.depth.size.toLong()

        service.publishNextSnapshot()

        assertEquals(depthPublished, publisher.snapshotBegins.single().l2SeqNum)
        assertEquals(depthPublished, publisher.snapshotEnds.single().l2SeqNum)
    }

    @Test
    fun `the image promises exactly the levels it sends, best first`() {
        val publisher = RecordingPublisher()
        val service = newService(publisher)
        val feeder = BookEventFeeder(service)
        feeder.orderAdded(1, 1, price = 98, qty = 1, side = BUY)
        feeder.orderAdded(1, 2, price = 100, qty = 2, side = BUY)
        feeder.orderAdded(1, 3, price = 99, qty = 3, side = BUY)
        feeder.orderAdded(1, 4, price = 103, qty = 4, side = SELL)
        feeder.orderAdded(1, 5, price = 101, qty = 5, side = SELL)

        service.publishNextSnapshot()

        val begin = publisher.snapshotBegins.single()
        assertEquals(5, begin.levelCount)
        assertEquals(5, publisher.snapshotLevels.size)
        assertEquals(begin.levelCount, publisher.snapshotEnds.single().levelCount)

        val bids = publisher.snapshotLevels.filter { it.side == BUY }.map { it.price }
        val asks = publisher.snapshotLevels.filter { it.side == SELL }.map { it.price }
        assertEquals(listOf(100L, 99L, 98L), bids, "bids descend from the touch")
        assertEquals(listOf(101L, 103L), asks, "asks ascend from the touch")
    }
}
