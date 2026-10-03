package com.engine.core

import com.engine.sbe.MessageHeaderEncoder
import com.engine.sbe.Phase as SbePhase
import com.engine.sbe.SnapshotBookEncoder
import com.engine.sbe.SnapshotEndEncoder
import com.engine.sbe.SnapshotEngineStateEncoder
import com.engine.sbe.SnapshotOrderEncoder
import io.aeron.Aeron
import io.aeron.ExclusivePublication
import io.aeron.Image
import io.aeron.Subscription
import io.aeron.driver.MediaDriver
import io.aeron.driver.ThreadingMode
import org.agrona.CloseHelper
import org.agrona.concurrent.UnsafeBuffer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Snapshot round trip and geometry reconciliation.
 *
 * A restart is how geometry is reapplied, so the restore is the only place that sees both the state
 * that existed and the shape it is being poured into. Until this file existed nothing exercised
 * `loadSnapshot` at all, which is exactly why it could silently drop a book whose security had left
 * the shard, and index a ladder that had moved under it.
 *
 * It pays for a real embedded media driver for the reason [AeronAllocationTest] does: both
 * `ExclusivePublication` and `Image` are `final` with no interface, so a snapshot cannot be taken or
 * read through a double. That is the point rather than a cost -- the shipped encoder feeds the
 * shipped decoder, so the two cannot agree with each other while disagreeing with reality.
 */
class SnapshotRestoreTest {

    private lateinit var driver: MediaDriver
    private lateinit var aeron: Aeron

    @BeforeTest
    fun setUp() {
        driver = MediaDriver.launchEmbedded(
            MediaDriver.Context()
                .threadingMode(ThreadingMode.SHARED)
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(true)
                .ipcTermBufferLength(16 * 1024 * 1024)
        )
        aeron = Aeron.connect(Aeron.Context().aeronDirectoryName(driver.aeronDirectoryName()))
    }

    @AfterTest
    fun tearDown() {
        CloseHelper.quietClose(aeron)
        CloseHelper.quietClose(driver)
    }

    // ------------------------------------------------------------ round trip

    @Test
    fun `a restored book holds the same orders at the same prices in the same order`() {
        val source = openHarness(arrayOf(serviceBook(securityId = 1)))
        // Two at one price so FIFO has something to preserve, and both sides so the walk covers
        // both ladders.
        source.newOrder(10L, 1L, 1, Side.BUY, 100L, 5L)
        source.newOrder(11L, 2L, 1, Side.BUY, 100L, 7L)
        source.newOrder(12L, 3L, 1, Side.BUY, 99L, 3L)
        source.newOrder(13L, 4L, 1, Side.SELL, 200L, 9L)
        assertEquals(4, source.books[0].restingOrderCount())

        val target = Harness(arrayOf(serviceBook(securityId = 1)))
        roundTrip(source, target)

        assertBooksMatch(source.books[0], target.books[0])
        assertEquals(
            source.service.nextExchangeOrderId,
            target.service.nextExchangeOrderId,
            "a restored engine must not reissue exchange order ids",
        )
        assertEquals(
            source.service.nextBookEventSeqNum,
            target.service.nextBookEventSeqNum,
            "the book event sequence must continue across a restart",
        )
    }

    @Test
    fun `a restored book keeps its phase, references and collars`() {
        val source = openHarness(arrayOf(serviceBook(securityId = 1)))
        source.newOrder(10L, 1L, 1, Side.BUY, 100L, 5L)
        val from = source.books[0]

        val target = Harness(arrayOf(serviceBook(securityId = 1)))
        roundTrip(source, target)
        val to = target.books[0]

        // A halted security comes back halted: phase is per book, and reopening is operator-driven.
        assertEquals(from.phase, to.phase)
        assertEquals(from.tradingDate, to.tradingDate)
        assertEquals(from.staticReference, to.staticReference)
        assertEquals(from.dynamicReference, to.dynamicReference)
        assertEquals(from.staticCollarBps, to.staticCollarBps)
        assertEquals(from.dynamicCollarBps, to.dynamicCollarBps)
    }

    // --------------------------------------------------- geometry reconciliation

    @Test
    fun `a security removed from the shard with resting orders refuses to restore`() {
        val source = openHarness(arrayOf(serviceBook(securityId = 1), serviceBook(securityId = 2)))
        source.newOrder(10L, 1L, 2, Side.BUY, 100L, 5L)

        val target = Harness(arrayOf(serviceBook(securityId = 1)), shardFingerprint = OTHER)
        val failure = assertFailsWith<SnapshotRestoreFailed> { roundTrip(source, target) }

        assertContains(failure.message!!, "security 2 is no longer on this shard")
        assertContains(failure.message!!, "1 resting orders")
    }

    @Test
    fun `a security removed from the shard with an empty book restores quietly`() {
        val source = openHarness(arrayOf(serviceBook(securityId = 1), serviceBook(securityId = 2)))
        source.newOrder(10L, 1L, 1, Side.BUY, 100L, 5L)
        assertEquals(0, source.books[1].restingOrderCount(), "security 2 must be empty")

        // The quiet case on purpose: this is how a security leaves a shard.
        val target = Harness(arrayOf(serviceBook(securityId = 1)), shardFingerprint = OTHER)
        roundTrip(source, target)

        assertBooksMatch(source.books[0], target.books[0])
    }

    @Test
    fun `a security added to the shard restores with an empty book`() {
        val source = openHarness(arrayOf(serviceBook(securityId = 1)))
        source.newOrder(10L, 1L, 1, Side.BUY, 100L, 5L)

        val target = Harness(
            arrayOf(serviceBook(securityId = 1), serviceBook(securityId = 2)),
            shardFingerprint = OTHER,
        )
        roundTrip(source, target)

        assertBooksMatch(source.books[0], target.books[0])
        assertEquals(0, target.books[1].restingOrderCount())
    }

    @Test
    fun `a security whose ladder changed refuses to restore`() {
        val source = openHarness(arrayOf(serviceBook(securityId = 1, levelCount = 1024)))
        source.newOrder(10L, 1L, 1, Side.BUY, 100L, 5L)

        val target = Harness(
            arrayOf(serviceBook(securityId = 1, levelCount = 512)),
            shardFingerprint = OTHER,
        )
        val failure = assertFailsWith<SnapshotRestoreFailed> { roundTrip(source, target) }

        assertContains(failure.message!!, "security 1 changed geometry")
        assertContains(failure.message!!, "levelCount=1024")
        assertContains(failure.message!!, "levelCount=512")
    }

    @Test
    fun `a security whose tick size changed refuses to restore`() {
        val source = openHarness(arrayOf(serviceBook(securityId = 1, tickSize = 1L)))
        source.newOrder(10L, 1L, 1, Side.BUY, 100L, 5L)

        val target = Harness(
            arrayOf(serviceBook(securityId = 1, tickSize = 5L)),
            shardFingerprint = OTHER,
        )
        val failure = assertFailsWith<SnapshotRestoreFailed> { roundTrip(source, target) }
        assertContains(failure.message!!, "security 1 changed geometry")
    }

    @Test
    fun `a shrunk order pool refuses to restore rather than exhausting it`() {
        val source = openHarness(arrayOf(serviceBook(securityId = 1, maxOrders = 512)))
        repeat(4) { source.newOrder(10L, it.toLong(), 1, Side.BUY, 100L + it, 5L) }

        // Fewer slots than the snapshot holds orders. Left to book() this is the `check` on an
        // exhausted pool, which is a throw on every node at the same log position.
        val target = Harness(
            arrayOf(serviceBook(securityId = 1, maxOrders = 2)),
            shardFingerprint = OTHER,
        )
        val failure = assertFailsWith<SnapshotRestoreFailed> { roundTrip(source, target) }
        assertContains(failure.message!!, "this node's pool is 2")
    }

    @Test
    fun `an order outside the ladder is refused, not quietly dropped`() {
        // The geometry check above is the primary path. This is its backstop: a snapshot whose
        // fingerprint agrees is trusted without a per-book comparison, so the range check is the
        // only thing between a bad price and a book that is quietly wrong.
        //
        // Quietly, not loudly, and that is the finding worth keeping: book() would index the
        // ladder out of bounds, but Image.poll catches a handler exception, reports it to the
        // client error handler and advances the position regardless. Removing this check does not
        // crash the node -- it drops the order and leaves the restore looking successful.
        val source = openHarness(arrayOf(serviceBook(securityId = 1, levelCount = 1024)))
        source.newOrder(10L, 1L, 1, Side.BUY, 900L, 5L)

        val target = Harness(arrayOf(serviceBook(securityId = 1, levelCount = 512)))
        val failure = assertFailsWith<SnapshotRestoreFailed> { roundTrip(source, target) }

        assertContains(failure.message!!, "falls outside this node's ladder")
    }

    @Test
    fun `a snapshot taken by another shard refuses to restore`() {
        val source = openHarness(arrayOf(serviceBook(securityId = 1)))
        source.newOrder(10L, 1L, 1, Side.BUY, 100L, 5L)

        val target = Harness(arrayOf(serviceBook(securityId = 1)))
        val image = publish { pub ->
            // Same content, a different shard id: the shape of a node pointed at another shard's
            // cluster directory.
            val buffer = UnsafeBuffer(ByteArray(256))
            SnapshotEngineStateEncoder().wrapAndApplyHeader(buffer, 0, MessageHeaderEncoder())
                .nextExchangeOrderId(2L)
                .nextBookEventSeqNum(2L)
                .shardFingerprint(FINGERPRINT)
                .shardId(9)
            offer(pub, buffer, MessageHeaderEncoder.ENCODED_LENGTH + SnapshotEngineStateEncoder.BLOCK_LENGTH)
            end(pub, 0L)
        }
        val failure = assertFailsWith<SnapshotRestoreFailed> { target.restore(image) }

        assertContains(failure.message!!, "taken by shard 9")
        assertContains(failure.message!!, "this node is shard 1")
    }

    // ------------------------------------------------- a snapshot that lies

    @Test
    fun `a book claiming more orders than it carries refuses to restore`() {
        val target = Harness(arrayOf(serviceBook(securityId = 1)))
        val image = publish { pub ->
            state(pub, FINGERPRINT)
            bookHeader(pub, securityId = 1, claimedOrders = 5)
            end(pub, 0L)
        }
        val failure = assertFailsWith<SnapshotRestoreFailed> { target.restore(image) }

        assertContains(failure.message!!, "claims 5 resting orders but carries 0")
    }

    @Test
    fun `a truncated snapshot refuses to restore`() {
        val target = Harness(arrayOf(serviceBook(securityId = 1)))
        // No SnapshotEnd: every count check hangs off it, so a snapshot that stops early must not
        // be mistaken for a complete one that happened to be short.
        val image = publish { pub ->
            state(pub, FINGERPRINT)
            bookHeader(pub, securityId = 1, claimedOrders = 0)
        }
        val failure = assertFailsWith<SnapshotRestoreFailed> { target.restore(image) }

        assertContains(failure.message!!, "truncated")
    }

    @Test
    fun `a snapshot predating the geometry fields reconciles on security ids alone`() {
        val target = Harness(arrayOf(serviceBook(securityId = 1)))
        val image = publish { pub -> writeVersionOneSnapshot(pub, securityId = 1) }

        target.restore(image)

        assertEquals(42L, target.service.nextExchangeOrderId)
        assertEquals(0, target.books[0].restingOrderCount())
    }

    @Test
    fun `a version one snapshot refuses when a security has left the shard`() {
        // The count is unknown at version 1, so "the book was probably empty" is a guess. Refusing
        // is the only answer that cannot destroy live orders.
        val target = Harness(arrayOf(serviceBook(securityId = 1)))
        val image = publish { pub -> writeVersionOneSnapshot(pub, securityId = 7) }

        val failure = assertFailsWith<SnapshotRestoreFailed> { target.restore(image) }
        assertContains(failure.message!!, "too old to say whether its book was empty")
    }

    /**
     * A version 2 snapshot predates the engine holding `origQty`, so a restored order has none.
     *
     * That has to come back as 0 = unknown and travel to the client as `Enrichment.UNKNOWN`. The
     * plausible mistake is to fill it in from `leavesQty`, which is right only for an order that
     * never traded and silently understates every order that did.
     */
    @Test
    fun `an order from a version 2 snapshot restores with an unknown original quantity`() {
        val target = Harness(arrayOf(serviceBook(securityId = 1)))
        target.restore(
            publish { pub ->
                state(pub, FINGERPRINT)
                bookHeader(pub, securityId = 1, claimedOrders = 1)
                writeVersionTwoOrder(pub, securityId = 1, price = 100L, leavesQty = 6L)
                end(pub, 1L)
            }
        )

        val book = target.books[0]
        assertEquals(1, book.restingOrderCount())
        var node = -1
        book.forEachRestingOrder { node = it }
        assertEquals(6L, book.leavesQtyOf(node))
        assertEquals(0L, book.origQtyOf(node), "0 means unknown, not zero quantity")
    }

    @Test
    fun `a node that has refused its configuration writes no snapshot`() {
        // Design.md §7, "Enforced through the log": a refused node holds state computed under a
        // configuration the leader disagrees with, and a snapshot would hand it to the next restart.
        val source = openHarness(arrayOf(serviceBook(1)))
        source.newOrder(participantId = 1, clOrdId = 100, securityId = 1, side = Side.BUY, price = 99, qty = 10)
        assertFailsWith<ConfigurationMismatch> { source.announce(OTHER, Harness.ENGINE_FINGERPRINT) }

        publish { pub ->
            assertFailsWith<ConfigurationMismatch> { source.service.onTakeSnapshot(pub) }
            assertEquals(0L, pub.position(), "nothing was written to the snapshot")
        }
    }

    // ------------------------------------------------------------------ support

    /** A harness with a book that accepts orders: defined, and trading. */
    private fun openHarness(books: Array<OrderBook>): Harness {
        val harness = Harness(books)
        for (book in books) {
            harness.defineSecurity(book = book, referencePrice = 100L)
        }
        harness.sessionTransition(Phase.CONTINUOUS)
        return harness
    }

    /** Takes [source]'s snapshot with the shipped encoder and restores it into [target]. */
    private fun roundTrip(source: Harness, target: Harness) {
        target.restore(publish { pub -> source.service.onTakeSnapshot(pub) })
    }

    /**
     * Runs [write] against a real publication and returns the image a restore reads.
     *
     * The publication is closed before the image is handed back so the stream reaches its end:
     * a restore that never sees a terminator must be able to stop, and that is a case under test.
     */
    private fun publish(write: (ExclusivePublication) -> Unit): Image {
        val subscription: Subscription = aeron.addSubscription(CHANNEL, STREAM)
        val publication = aeron.addExclusivePublication(CHANNEL, STREAM)
        awaitConnected(subscription)
        write(publication)
        CloseHelper.quietClose(publication)
        awaitImage(subscription)
        return subscription.imageAtIndex(0)
    }

    private fun offer(publication: ExclusivePublication, buffer: UnsafeBuffer, length: Int) {
        val deadline = System.nanoTime() + CONNECT_TIMEOUT_NS
        while (publication.offer(buffer, 0, length) < 0) {
            check(System.nanoTime() < deadline) { "could not offer a snapshot fragment" }
            Thread.sleep(1)
        }
    }

    private fun state(publication: ExclusivePublication, fingerprint: Long) {
        val buffer = UnsafeBuffer(ByteArray(256))
        SnapshotEngineStateEncoder().wrapAndApplyHeader(buffer, 0, MessageHeaderEncoder())
            .nextExchangeOrderId(1L)
            .nextBookEventSeqNum(1L)
            .shardFingerprint(fingerprint)
            .shardId(1)
        offer(
            publication, buffer,
            MessageHeaderEncoder.ENCODED_LENGTH + SnapshotEngineStateEncoder.BLOCK_LENGTH,
        )
    }

    private fun bookHeader(
        publication: ExclusivePublication,
        securityId: Int,
        claimedOrders: Int,
    ) {
        val buffer = UnsafeBuffer(ByteArray(256))
        SnapshotBookEncoder().wrapAndApplyHeader(buffer, 0, MessageHeaderEncoder())
            .staticReference(100L)
            .dynamicReference(100L)
            .securityId(securityId)
            .tradingDate(20260904)
            .staticCollarBps(0)
            .dynamicCollarBps(0)
            .phase(SbePhase.CONTINUOUS)
            .priceFloor(0L)
            .tickSize(1L)
            .levelCount(1024)
            .maxOrders(512)
            .restingOrderCount(claimedOrders)
        offer(publication, buffer, MessageHeaderEncoder.ENCODED_LENGTH + SnapshotBookEncoder.BLOCK_LENGTH)
    }

    private fun end(publication: ExclusivePublication, total: Long) {
        val buffer = UnsafeBuffer(ByteArray(256))
        SnapshotEndEncoder().wrapAndApplyHeader(buffer, 0, MessageHeaderEncoder())
            .restingOrderCount(total)
        offer(publication, buffer, MessageHeaderEncoder.ENCODED_LENGTH + SnapshotEndEncoder.BLOCK_LENGTH)
    }

    /**
     * A schema version 1 snapshot: the header says version 1 and the block lengths are the ones
     * that shipped with it, so the generated decoders take their `sinceVersion` path and report
     * the new fields as absent. Hand-written because the current encoders cannot produce it.
     */
    private fun writeVersionOneSnapshot(publication: ExclusivePublication, securityId: Int) {
        val buffer = UnsafeBuffer(ByteArray(256))

        val header = MessageHeaderEncoder().wrap(buffer, 0)
            .blockLength(V1_STATE_BLOCK_LENGTH)
            .templateId(SnapshotEngineStateEncoder.TEMPLATE_ID)
            .schemaId(SnapshotEngineStateEncoder.SCHEMA_ID)
            .version(1)
        val body = MessageHeaderEncoder.ENCODED_LENGTH
        buffer.putLong(body, 42L)      // nextExchangeOrderId
        buffer.putLong(body + 8, 7L)   // nextBookEventSeqNum
        check(header.encodedLength() == body)
        offer(publication, buffer, body + V1_STATE_BLOCK_LENGTH)

        MessageHeaderEncoder().wrap(buffer, 0)
            .blockLength(V1_BOOK_BLOCK_LENGTH)
            .templateId(SnapshotBookEncoder.TEMPLATE_ID)
            .schemaId(SnapshotBookEncoder.SCHEMA_ID)
            .version(1)
        buffer.putLong(body, 100L)              // staticReference
        buffer.putLong(body + 8, 100L)          // dynamicReference
        buffer.putInt(body + 16, securityId)
        buffer.putInt(body + 20, 20260904)      // tradingDate
        buffer.putInt(body + 24, 0)             // staticCollarBps
        buffer.putInt(body + 28, 0)             // dynamicCollarBps
        buffer.putByte(body + 32, Phase.CONTINUOUS)
        offer(publication, buffer, body + V1_BOOK_BLOCK_LENGTH)

        end(publication, 0L)
    }

    /**
     * A `SnapshotOrder` as schema version 2 wrote it: the same body, one field shorter, and a
     * header that says so. Hand-written because the current encoder cannot produce it.
     */
    private fun writeVersionTwoOrder(
        publication: ExclusivePublication,
        securityId: Int,
        price: Long,
        leavesQty: Long,
    ) {
        val buffer = UnsafeBuffer(ByteArray(256))
        MessageHeaderEncoder().wrap(buffer, 0)
            .blockLength(V2_ORDER_BLOCK_LENGTH)
            .templateId(SnapshotOrderEncoder.TEMPLATE_ID)
            .schemaId(SnapshotOrderEncoder.SCHEMA_ID)
            .version(2)
        val body = MessageHeaderEncoder.ENCODED_LENGTH
        buffer.putLong(body, 10L)              // participantId
        buffer.putLong(body + 8, 10L)          // smpId
        buffer.putLong(body + 16, 1L)          // clOrdId
        buffer.putLong(body + 24, 1L)          // exchangeOrderId
        buffer.putLong(body + 32, price)
        buffer.putLong(body + 40, leavesQty)
        buffer.putInt(body + 48, securityId)
        buffer.putInt(body + 52, 0)            // expireDate
        buffer.putByte(body + 56, Side.BUY)
        buffer.putByte(body + 57, SmpStrategy.CANCEL_AGGRESSOR)
        offer(publication, buffer, body + V2_ORDER_BLOCK_LENGTH)
    }

    private fun assertBooksMatch(expected: OrderBook, actual: OrderBook) {
        val from = mutableListOf<String>()
        expected.forEachRestingOrder { from += describe(expected, it) }
        val to = mutableListOf<String>()
        actual.forEachRestingOrder { to += describe(actual, it) }
        // Ladder order and FIFO within a level, so list equality is the FIFO assertion too.
        assertEquals(from, to)
        assertEquals(expected.bestBid(), actual.bestBid())
        assertEquals(expected.bestAsk(), actual.bestAsk())
        assertTrue(from.isNotEmpty(), "sanity: the source book must not be empty")
    }

    private fun describe(book: OrderBook, node: Int): String = buildString {
        append("id=").append(book.exchangeOrderIdOf(node))
        append(" participant=").append(book.participantIdOf(node))
        append(" clOrdId=").append(book.clOrdIdOf(node))
        append(" smpId=").append(book.smpIdOf(node))
        append(" side=").append(book.sideOfOrder(node))
        append(" price=").append(book.orderPrice(node))
        append(" leaves=").append(book.leavesQtyOf(node))
        append(" orig=").append(book.origQtyOf(node))
        append(" expire=").append(book.expireDateOfOrder(node))
        append(" smp=").append(book.smpStrategyOfOrder(node))
    }

    private fun awaitConnected(subscription: Subscription) {
        val deadline = System.nanoTime() + CONNECT_TIMEOUT_NS
        while (subscription.imageCount() == 0) {
            check(System.nanoTime() < deadline) { "no publication connected" }
            Thread.sleep(1)
        }
    }

    private fun awaitImage(subscription: Subscription) {
        val deadline = System.nanoTime() + CONNECT_TIMEOUT_NS
        while (subscription.imageCount() == 0) {
            check(System.nanoTime() < deadline) { "the snapshot image went away" }
            Thread.sleep(1)
        }
    }

    private companion object {
        const val CHANNEL = "aeron:ipc?term-length=16m"
        const val STREAM = 21
        const val CONNECT_TIMEOUT_NS = 10_000_000_000L

        /** Matches [Harness]'s default, so a round trip is a same-geometry restart. */
        const val FINGERPRINT = 0x1234_5678_9abc_def0L

        /** A different published security file. */
        const val OTHER = 0x0fed_cba9_8765_4321L

        // The block lengths these two messages shipped with, before the geometry fields.
        const val V1_STATE_BLOCK_LENGTH = 16
        const val V1_BOOK_BLOCK_LENGTH = 40

        /** SnapshotOrder's blockLength before origQty was added in schema version 3. */
        const val V2_ORDER_BLOCK_LENGTH = 64
    }
}
