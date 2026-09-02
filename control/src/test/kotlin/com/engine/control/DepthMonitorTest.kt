package com.engine.control

import com.engine.reference.DepthFeedAssembler
import com.engine.sbe.DepthSnapshotBeginEncoder
import com.engine.sbe.DepthSnapshotEndEncoder
import com.engine.sbe.DepthSnapshotLevelEncoder
import com.engine.sbe.DepthUpdateEncoder
import com.engine.sbe.MessageHeaderEncoder
import com.engine.sbe.Side
import org.agrona.concurrent.UnsafeBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The console's view of a book, driven by real feed messages.
 *
 * No Spring and no database: this is the piece between the shared assembler and the browser, and
 * what it has to get right is what it publishes when a book *cannot* be trusted.
 */
class DepthMonitorTest {

    private val buffer = UnsafeBuffer(ByteArray(256))
    private val header = MessageHeaderEncoder()
    private var seq = 0L
    private val shard = 0
    private val security = 1

    private fun DepthMonitor.snapshot(vararg levels: Triple<Boolean, Long, Long>, at: Long = 0) {
        DepthSnapshotBeginEncoder().wrapAndApplyHeader(buffer, 0, header)
            .seqNum(++seq).l2SeqNum(at).lastTradePrice(DepthFeedAssembler.NO_PRICE).lastTradeQty(0)
            .securityId(security).shardId(shard).levelCount(levels.size)
        onMessage(buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + DepthSnapshotBeginEncoder.BLOCK_LENGTH)

        for ((isBid, price, qty) in levels) {
            DepthSnapshotLevelEncoder().wrapAndApplyHeader(buffer, 0, header)
                .seqNum(++seq).price(price).aggregateQty(qty).securityId(security).shardId(shard)
                .orderCount(1).side(if (isBid) Side.BUY else Side.SELL)
            onMessage(buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + DepthSnapshotLevelEncoder.BLOCK_LENGTH)
        }

        DepthSnapshotEndEncoder().wrapAndApplyHeader(buffer, 0, header)
            .seqNum(++seq).l2SeqNum(at).securityId(security).shardId(shard).levelCount(levels.size)
        onMessage(buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + DepthSnapshotEndEncoder.BLOCK_LENGTH)
    }

    private fun DepthMonitor.update(l2Seq: Long, isBid: Boolean, price: Long, qty: Long) {
        DepthUpdateEncoder().wrapAndApplyHeader(buffer, 0, header)
            .seqNum(l2Seq).price(price).aggregateQty(qty).securityId(security).shardId(shard)
            .orderCount(1).side(if (isBid) Side.BUY else Side.SELL)
        onMessage(buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + DepthUpdateEncoder.BLOCK_LENGTH)
    }

    @Test
    fun `a book becomes an image with both sides and the touch`() {
        val monitor = DepthMonitor(contextOf = { BookContext(symbol = "AAPL", phase = "CONTINUOUS") })
        monitor.snapshot(Triple(true, 99L, 10L), Triple(false, 101L, 5L))
        monitor.publishImages()

        val image = requireNotNull(monitor.image(security))
        assertTrue(image.synchronised)
        assertEquals("AAPL", image.symbol)
        assertEquals(0, image.shardId)
        // Joined from L3, which the depth feed knows nothing about: a ladder without a phase beside
        // it looks the same whether the market is open or halted.
        assertEquals("CONTINUOUS", image.phase)
        assertFalse(image.halted)
        assertEquals(99L, image.bestBid)
        assertEquals(101L, image.bestAsk)
        assertEquals(2L, image.spread)
        assertEquals(listOf(BookLevel(99, 10, 1)), image.bids)
        assertEquals(listOf(BookLevel(101, 5, 1)), image.asks)
    }

    @Test
    fun `an unsynchronised book publishes no depth and says which state it is in`() {
        // The failure this whole feed exists to prevent: a console drawing a stale or partial
        // ladder that looks exactly like a current one. Sending nothing and saying so is the only
        // honest answer, and the client is told the state rather than having to infer it from an
        // empty list -- which is also what an empty book looks like.
        val monitor = DepthMonitor()
        monitor.update(1, isBid = true, price = 99, qty = 10)
        monitor.publishImages()

        val image = requireNotNull(monitor.image(security))
        assertFalse(image.synchronised)
        assertEquals("WAITING", image.state)
        assertTrue(image.bids.isEmpty())
        assertNull(image.bestBid)
    }

    @Test
    fun `a gap withdraws the depth already published`() {
        val monitor = DepthMonitor()
        monitor.snapshot(Triple(true, 99L, 10L))
        monitor.update(1, isBid = true, price = 98, qty = 5)
        monitor.publishImages()
        assertTrue(requireNotNull(monitor.image(security)).synchronised)

        monitor.update(9, isBid = true, price = 97, qty = 5)
        monitor.publishImages()

        val image = requireNotNull(monitor.image(security))
        assertFalse(image.synchronised, "a book with a hole in it must stop being published")
        assertTrue(image.bids.isEmpty())
    }

    @Test
    fun `a deep book is truncated for display but says how deep it really is`() {
        val monitor = DepthMonitor(maxLevels = 2)
        monitor.snapshot(
            Triple(true, 99L, 1L), Triple(true, 98L, 1L), Triple(true, 97L, 1L),
            Triple(false, 101L, 1L),
        )
        monitor.publishImages()

        val image = requireNotNull(monitor.image(security))
        assertEquals(2, image.bids.size)
        assertEquals(3, image.bidLevelsTotal)
        assertEquals(listOf(99L, 98L), image.bids.map { it.price }, "the truncation keeps the touch")
    }

    @Test
    fun `an unchanged book keeps its version`() {
        // What a subscriber uses to be sent only what moved. A version that ticked on every
        // interval would push four images a second per security to every open console for ever,
        // and make an idle book look exactly like a busy one.
        val monitor = DepthMonitor()
        monitor.snapshot(Triple(true, 99L, 10L))
        monitor.publishImages()
        val first = requireNotNull(monitor.image(security))

        monitor.publishImages()
        monitor.publishImages()

        assertEquals(first.version, requireNotNull(monitor.image(security)).version)
    }

    @Test
    fun `a changed book gets a new version`() {
        val monitor = DepthMonitor()
        monitor.snapshot(Triple(true, 99L, 10L))
        monitor.publishImages()
        val first = requireNotNull(monitor.image(security))

        monitor.update(1, isBid = true, price = 98, qty = 5)
        monitor.publishImages()

        val second = requireNotNull(monitor.image(security))
        assertTrue(second.version > first.version)
        assertEquals(2, second.bids.size)
    }

    @Test
    fun `feed health counts what the console needs to explain an empty screen`() {
        val monitor = DepthMonitor()
        monitor.snapshot(Triple(true, 99L, 10L))
        // The first update seen establishes the sequence baseline rather than counting everything
        // before it as lost, so a gap needs a contiguous message before it to be a gap at all.
        monitor.update(1, isBid = true, price = 98, qty = 5)
        monitor.update(9, isBid = true, price = 97, qty = 5)

        val status = monitor.status(subscribed = true, detail = "connected")
        assertEquals(listOf(security), status.securities)
        // Subscribed is not the same as receiving: a driver can die with every object on this side
        // still looking healthy, so the count and the silence are reported separately.
        // Begin, one level, End, and the two updates.
        assertEquals(5L, status.messagesSeen)
        assertEquals(1L, status.snapshotsApplied)
        assertEquals(1L, status.desynchronisations)
        assertEquals(0, status.synchronised)
    }
}
