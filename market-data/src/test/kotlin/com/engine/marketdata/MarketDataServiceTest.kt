package com.engine.marketdata

import com.engine.sbe.RemoveReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarketDataServiceTest {

    @Test
    fun `L2 aggregates several orders onto one price level`() {
        val pub = RecordingPublisher()
        val feeder = BookEventFeeder(newService(pub))

        feeder.orderAdded(1, orderId = 1, price = 100, qty = 10, side = BUY)
        feeder.orderAdded(1, orderId = 2, price = 100, qty = 15, side = BUY)

        assertEquals(Depth(1, BUY, 100, 10, 1), pub.depth[0])
        assertEquals(Depth(1, BUY, 100, 25, 2), pub.depth[1])
    }

    @Test
    fun `a fill reduces quantity but keeps the order counted`() {
        val pub = RecordingPublisher()
        val feeder = BookEventFeeder(newService(pub))

        feeder.orderAdded(1, 1, price = 100, qty = 10, side = SELL)
        feeder.orderReduced(1, 1, price = 100, lastQty = 4, leavesQty = 6, side = SELL)

        assertEquals(Depth(1, SELL, 100, 6, 1), pub.depth.last())
    }

    @Test
    fun `a removal subtracts the remaining quantity carried on the event`() {
        // Without leavesQty on OrderRemoved the aggregator would have to shadow per-order state.
        val pub = RecordingPublisher()
        val feeder = BookEventFeeder(newService(pub))

        feeder.orderAdded(1, 1, price = 100, qty = 10, side = BUY)
        feeder.orderAdded(1, 2, price = 100, qty = 7, side = BUY)
        feeder.orderRemoved(1, 1, price = 100, leavesQty = 10, side = BUY)

        assertEquals(Depth(1, BUY, 100, 7, 1), pub.depth.last())
    }

    @Test
    fun `emptying a level publishes a zero and drops it from the touch`() {
        val pub = RecordingPublisher()
        val feeder = BookEventFeeder(newService(pub))

        feeder.orderAdded(1, 1, price = 100, qty = 10, side = BUY)
        feeder.orderAdded(1, 2, price = 99, qty = 5, side = BUY)
        feeder.orderRemoved(1, 1, price = 100, leavesQty = 10, side = BUY)

        assertEquals(Depth(1, BUY, 100, 0, 0), pub.depth.last())
        assertEquals(99L, pub.top.last().bidPrice)
        assertEquals(5L, pub.top.last().bidQty)
    }

    @Test
    fun `top of book tracks both sides`() {
        val pub = RecordingPublisher()
        val feeder = BookEventFeeder(newService(pub))

        feeder.orderAdded(1, 1, price = 99, qty = 10, side = BUY)
        feeder.orderAdded(1, 2, price = 101, qty = 8, side = SELL)

        assertEquals(Top(1, 99, 10, 101, 8), pub.top.last())
    }

    @Test
    fun `a better price replaces the touch`() {
        val pub = RecordingPublisher()
        val feeder = BookEventFeeder(newService(pub))

        feeder.orderAdded(1, 1, price = 99, qty = 10, side = BUY)
        feeder.orderAdded(1, 2, price = 100, qty = 3, side = BUY)

        assertEquals(100L, pub.top.last().bidPrice)
        assertEquals(3L, pub.top.last().bidQty)
    }

    @Test
    fun `deeper liquidity does not republish the touch`() {
        // A busy book must not flood L1 with unchanged top-of-book.
        val pub = RecordingPublisher()
        val feeder = BookEventFeeder(newService(pub))

        feeder.orderAdded(1, 1, price = 100, qty = 10, side = BUY)
        val afterTouch = pub.top.size
        feeder.orderAdded(1, 2, price = 90, qty = 10, side = BUY)
        feeder.orderAdded(1, 3, price = 80, qty = 10, side = BUY)

        assertEquals(afterTouch, pub.top.size)
        assertEquals(3, pub.depth.size)
    }

    @Test
    fun `an empty side reports no price rather than zero`() {
        val pub = RecordingPublisher()
        val feeder = BookEventFeeder(newService(pub))

        feeder.orderAdded(1, 1, price = 100, qty = 10, side = BUY)

        assertEquals(MarketDataService.NO_PRICE, pub.top.last().askPrice)
        assertEquals(0L, pub.top.last().askQty)
    }

    @Test
    fun `trades are republished on L1`() {
        val pub = RecordingPublisher()
        val feeder = BookEventFeeder(newService(pub))

        feeder.tradeExecuted(1, price = 100, qty = 5, aggressorSide = BUY)

        assertEquals(Trade(1, 100, 5, BUY), pub.trades.single())
    }

    @Test
    fun `every book event is forwarded verbatim to L3`() {
        val pub = RecordingPublisher()
        val feeder = BookEventFeeder(newService(pub))

        feeder.orderAdded(1, 1, price = 100, qty = 10, side = BUY)
        feeder.tradeExecuted(1, price = 100, qty = 5, aggressorSide = BUY)
        feeder.auctionUncrossed(1, price = 100, qty = 5)
        feeder.orderRemoved(1, 1, price = 100, leavesQty = 5, side = BUY, reason = RemoveReason.FILLED)

        assertEquals(4, pub.l3.size)
    }

    @Test
    fun `a sequence gap is detected and counted`() {
        val pub = RecordingPublisher()
        val service = newService(pub)
        val feeder = BookEventFeeder(service)

        feeder.orderAdded(1, 1, price = 100, qty = 10, side = BUY)
        feeder.skipSequence(5)
        feeder.orderAdded(1, 2, price = 99, qty = 10, side = BUY)

        assertEquals(1L, service.gapsDetected)
        assertEquals(5L, service.eventsMissed)
    }

    @Test
    fun `a clean stream detects no gaps`() {
        val pub = RecordingPublisher()
        val service = newService(pub)
        val feeder = BookEventFeeder(service)

        repeat(10) { feeder.orderAdded(1, it.toLong(), price = 100L - it, qty = 5, side = BUY) }

        assertEquals(0L, service.gapsDetected)
    }

    @Test
    fun `events for an unconfigured security are counted not applied`() {
        val pub = RecordingPublisher()
        val service = newService(pub, 1)
        val feeder = BookEventFeeder(service)

        feeder.orderAdded(securityId = 99, orderId = 1, price = 100, qty = 10, side = BUY)

        assertEquals(1L, service.unknownSecurityEvents)
        assertTrue(pub.depth.isEmpty())
        // Still forwarded on L3: the event is valid, this process just does not aggregate it.
        assertEquals(1, pub.l3.size)
    }

    @Test
    fun `securities are aggregated independently`() {
        val pub = RecordingPublisher()
        val feeder = BookEventFeeder(newService(pub, 1, 2))

        feeder.orderAdded(1, 1, price = 100, qty = 10, side = BUY)
        feeder.orderAdded(2, 2, price = 50, qty = 7, side = BUY)

        assertEquals(100L, pub.top.first { it.securityId == 1 }.bidPrice)
        assertEquals(50L, pub.top.first { it.securityId == 2 }.bidPrice)
    }

    @Test
    fun `a price outside the ladder is counted rather than corrupting the book`() {
        val pub = RecordingPublisher()
        val service = newService(pub)
        val feeder = BookEventFeeder(service)

        feeder.orderAdded(1, 1, price = 999_999, qty = 10, side = BUY)

        assertEquals(1L, service.outOfRangeEvents)
        assertTrue(pub.depth.isEmpty())
    }
}

class MarketDataShardTest {

    @Test
    fun `events from another shard are rejected rather than aggregated`() {
        // A market-data process pointed at the wrong engine would otherwise fold another shard's
        // orders into these books silently.
        val pub = RecordingPublisher()
        val service = newService(pub, 1, shardId = 1)
        val foreign = BookEventFeeder(service, shardId = 2)

        foreign.orderAdded(1, orderId = 1, price = 100, qty = 10, side = BUY)

        assertEquals(1L, service.foreignShardEvents)
        assertTrue(pub.depth.isEmpty())
        assertTrue(pub.top.isEmpty())
        // Not forwarded to L3 either: its sequence belongs to a different stream.
        assertTrue(pub.l3.isEmpty())
    }

    @Test
    fun `a foreign shard does not disturb this shard's sequence`() {
        val pub = RecordingPublisher()
        val service = newService(pub, 1, shardId = 1)
        val own = BookEventFeeder(service, shardId = 1)
        val foreign = BookEventFeeder(service, shardId = 2)

        own.orderAdded(1, 1, price = 100, qty = 10, side = BUY)
        foreign.orderAdded(1, 2, price = 99, qty = 10, side = BUY)
        foreign.orderAdded(1, 3, price = 98, qty = 10, side = BUY)
        own.orderAdded(1, 4, price = 97, qty = 10, side = BUY)

        assertEquals(2L, service.foreignShardEvents)
        assertEquals(0L, service.gapsDetected)
        assertEquals(2, pub.l3.size)
    }
}
