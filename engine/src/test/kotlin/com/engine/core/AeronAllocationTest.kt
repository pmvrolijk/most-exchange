package com.engine.core

import com.engine.sbe.SnapshotOrderEncoder
import io.aeron.Aeron
import io.aeron.ExclusivePublication
import io.aeron.Subscription
import io.aeron.driver.MediaDriver
import io.aeron.driver.ThreadingMode
import org.agrona.CloseHelper
import org.agrona.concurrent.BusySpinIdleStrategy
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two hot paths [AllocationTest] cannot reach, measured the only way they can be.
 *
 * `publishBookEvent` and `onTakeSnapshot` both write to an Aeron `ExclusivePublication`, which is
 * a `final` class — there is no faking it, and no interface to implement. So this test pays for a
 * real embedded media driver and real publications, and measures the shipped code writing real
 * bytes into a real term buffer via `tryClaim`.
 *
 * Both paths matter under `--gc=epsilon` for the same reason and in different degrees. A book event
 * is published for **every** order, so a per-event cost is a per-order cost. A snapshot is rarer,
 * but it walks **every resting order** in the book — so a per-order cost there is tens of megabytes
 * in one burst, and it recurs for the life of the session. That second one is the reason
 * `engine.useEpsilonGc` was still off (Design.md §7).
 *
 * The subscriptions are drained on a **separate** thread, deliberately. Something has to consume
 * the streams or `tryClaim` back-pressures and the measurement becomes a study of Aeron's flow
 * control — but a drainer on the measuring thread would put its own allocations into the reading.
 * Allocation is counted per thread, so draining elsewhere costs nothing here.
 */
class AeronAllocationTest {

    private lateinit var driver: MediaDriver
    private lateinit var aeron: Aeron
    private lateinit var drain: Drainer

    /** Consumes a subscription on its own thread so `tryClaim` never back-pressures. */
    private class Drainer(private val subscriptions: List<Subscription>) : Runnable {
        private val running = AtomicBoolean(true)
        private val thread = Thread(this, "alloc-drain").apply { isDaemon = true; start() }

        override fun run() {
            val idle = BusySpinIdleStrategy()
            val handler = io.aeron.logbuffer.FragmentHandler { _, _, _, _ -> }
            while (running.get()) {
                var work = 0
                for (s in subscriptions) work += s.poll(handler, 256)
                idle.idle(work)
            }
        }

        fun stop() {
            running.set(false)
            thread.join(5_000)
        }
    }

    @BeforeTest
    fun setUp() {
        driver = MediaDriver.launchEmbedded(
            MediaDriver.Context()
                .threadingMode(ThreadingMode.SHARED)
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(true)
                // Room for a snapshot of the whole book without waiting on the drainer.
                .ipcTermBufferLength(16 * 1024 * 1024)
        )
        aeron = Aeron.connect(Aeron.Context().aeronDirectoryName(driver.aeronDirectoryName()))
    }

    @AfterTest
    fun tearDown() {
        if (::drain.isInitialized) drain.stop()
        CloseHelper.quietClose(aeron)
        CloseHelper.quietClose(driver)
    }

    /**
     * Every order publishes at least one book event, so this is the per-order path that
     * [AllocationTest] measures with the publication muted. Here it is live: `tryClaim` really
     * reserves, the encoder really writes into the log buffer, `commit` really publishes.
     */
    @Test
    fun `publishing book events allocates nothing per order`() {
        val bookEvents = aeron.addSubscription(CHANNEL, BOOK_EVENT_STREAM)
        drain = Drainer(listOf(bookEvents))

        val driverUnderTest = Driver(
            maxOrders = 4096,
            aeron = aeron,
            bookEventChannel = CHANNEL,
            bookEventStreamId = BOOK_EVENT_STREAM,
        )
        awaitConnected(bookEvents)

        var clOrdId = 0L
        assertNoSteadyStateAllocation("publishing book events", opsPerRound = 2, rounds = 20_000) {
            driverUnderTest.newOrder(1L, clOrdId++, Side.BUY, Alloc.PRICE, 1L)
            driverUnderTest.newOrder(2L, clOrdId++, Side.SELL, Alloc.PRICE, 1L)
        }
        assertEquals(0, driverUnderTest.book.restingOrderCount())
        // Without this the test could pass having measured nothing: publishBookEvent returns early
        // on an unconnected publication, counting the drop, and never reaches the encode at all.
        assertEquals(
            0L,
            driverUnderTest.service.droppedBookEvents,
            "book events were dropped, so the encode path was never measured",
        )
        assertTrue(
            driverUnderTest.service.nextBookEventSeqNum > 1L,
            "sanity: book events were actually generated",
        )
    }

    /**
     * The snapshot walk: `offerToSnapshot` and `forEachRestingOrder` are both `inline fun`, so
     * nothing should box — but a snapshot encodes one message **per resting order**, which is
     * exactly the shape that turns a small per-call cost into a large burst.
     *
     * The book is filled once and left alone, so each round is a pure snapshot of a constant book
     * and the measurement is the walk itself rather than the booking that preceded it.
     */
    @Test
    fun `taking a snapshot allocates nothing per resting order`() {
        val bookEvents = aeron.addSubscription(CHANNEL, BOOK_EVENT_STREAM)
        val snapshots = aeron.addSubscription(CHANNEL, SNAPSHOT_STREAM)
        drain = Drainer(listOf(bookEvents, snapshots))

        val driverUnderTest = Driver(
            maxOrders = 4096,
            aeron = aeron,
            bookEventChannel = CHANNEL,
            bookEventStreamId = BOOK_EVENT_STREAM,
        )
        val snapshotPub: ExclusivePublication =
            aeron.addExclusivePublication(CHANNEL, SNAPSHOT_STREAM)
        awaitConnected(bookEvents)
        awaitConnected(snapshots)

        // A book that rests rather than trades: orders on one side only, so none of them match.
        driverUnderTest.sessionTransition(Phase.PRE_OPEN)
        var clOrdId = 0L
        repeat(RESTING_ORDERS) {
            driverUnderTest.newOrder(1L, clOrdId++, Side.BUY, Alloc.PRICE - it % 512, 1L)
        }
        assertEquals(
            RESTING_ORDERS,
            driverUnderTest.book.restingOrderCount(),
            "the snapshot must have a full book to walk",
        )

        // One round is one whole snapshot, so a round is RESTING_ORDERS encoded messages.
        assertNoSteadyStateAllocation(
            "taking a snapshot",
            opsPerRound = RESTING_ORDERS,
            rounds = 20,
        ) {
            driverUnderTest.service.onTakeSnapshot(snapshotPub)
        }

        assertEquals(
            RESTING_ORDERS,
            driverUnderTest.book.restingOrderCount(),
            "a snapshot must not disturb the book it walked",
        )
        assertTrue(
            snapshotPub.position() > RESTING_ORDERS.toLong() * SnapshotOrderEncoder.BLOCK_LENGTH,
            "sanity: the snapshots were really written to the publication",
        )
        CloseHelper.quietClose(snapshotPub)
    }

    private fun awaitConnected(subscription: Subscription) {
        val deadline = System.nanoTime() + CONNECT_TIMEOUT_NS
        while (subscription.imageCount() == 0) {
            check(System.nanoTime() < deadline) { "no publication connected to $subscription" }
            Thread.sleep(1)
        }
    }

    private companion object {
        const val CHANNEL = "aeron:ipc?term-length=16m"
        const val BOOK_EVENT_STREAM = 12
        const val SNAPSHOT_STREAM = 13
        const val RESTING_ORDERS = 2_000
        const val CONNECT_TIMEOUT_NS = 10_000_000_000L
    }
}
