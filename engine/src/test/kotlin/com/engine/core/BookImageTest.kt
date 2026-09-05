package com.engine.core

import com.engine.sbe.BookImageBeginDecoder
import com.engine.sbe.BookImageEndDecoder
import com.engine.sbe.BookImageLevelDecoder
import com.engine.sbe.MessageHeaderDecoder
import io.aeron.Aeron
import io.aeron.Subscription
import io.aeron.driver.MediaDriver
import io.aeron.driver.ThreadingMode
import io.aeron.logbuffer.FragmentHandler
import org.agrona.CloseHelper
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the engine actually writes when it republishes a book.
 *
 * The other half of recovery. `SnapshotRestoreTest` shows the engine's own books coming back; this
 * shows the one thing that lets *market data* come back with them, since it derives depth purely
 * from the book event stream and a restore publishes no events for the orders it restored.
 *
 * A real embedded media driver, for the reason the snapshot tests use one: `ExclusivePublication`
 * is `final` with no interface, so the publish path cannot be driven through a double at all.
 */
class BookImageTest {

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

    private data class Level(val side: Byte, val price: Long, val qty: Long, val orders: Int)

    private class Capture {
        val levels = mutableListOf<Level>()
        var begins = 0
        var ends = 0
        var declaredAtBegin = -1
        var declaredAtEnd = -1
        var baselineAtBegin = -1L
    }

    @Test
    fun `an image describes the book level by level, and states its own length`() {
        val subscription = aeron.addSubscription(CHANNEL, STREAM)
        val engine = Driver(aeron = aeron, bookEventChannel = CHANNEL, bookEventStreamId = STREAM)
        awaitConnected(subscription)

        // Two orders on one bid level so the image has an aggregate to get right, plus a second
        // bid level and one offer.
        engine.newOrder(1L, 1L, Side.BUY, price = 100L, qty = 10L)
        engine.newOrder(2L, 2L, Side.BUY, price = 100L, qty = 15L)
        engine.newOrder(3L, 3L, Side.BUY, price = 99L, qty = 7L)
        engine.newOrder(4L, 4L, Side.SELL, price = 200L, qty = 4L)
        assertEquals(4, engine.book.restingOrderCount())

        val sequenceBefore = engine.service.nextBookEventSeqNum
        drain(subscription)                       // discard the ordinary events

        engine.requestBookImage()
        assertTrue(engine.service.doBackgroundWork(0L) > 0, "no image was published")

        val capture = drain(subscription)
        assertEquals(1, capture.begins)
        assertEquals(1, capture.ends)
        assertEquals(
            listOf(
                Level(Side.BUY, 99L, 7L, 1),
                Level(Side.BUY, 100L, 25L, 2),
                Level(Side.SELL, 200L, 4L, 1),
            ),
            capture.levels,
        )
        // Begin and End agree on the count, so a cycle that lost its middle is detectable.
        assertEquals(3, capture.declaredAtBegin)
        assertEquals(3, capture.declaredAtEnd)

        // The baseline is the sequence the image is consistent at, and sending it consumed none.
        assertEquals(sequenceBefore, capture.baselineAtBegin)
        assertEquals(
            sequenceBefore,
            engine.service.nextBookEventSeqNum,
            "an image must not consume book event sequence numbers",
        )
    }

    @Test
    fun `an empty book still publishes a bracketed zero-level cycle`() {
        // "No liquidity" and "I cannot yet know" are different answers, and a consumer that never
        // receives the first will sit on an empty book forever waiting to be told something.
        val subscription = aeron.addSubscription(CHANNEL, STREAM)
        val engine = Driver(aeron = aeron, bookEventChannel = CHANNEL, bookEventStreamId = STREAM)
        awaitConnected(subscription)
        drain(subscription)

        engine.requestBookImage()
        engine.service.doBackgroundWork(0L)

        val capture = drain(subscription)
        assertEquals(1, capture.begins)
        assertEquals(1, capture.ends)
        assertEquals(0, capture.declaredAtBegin)
        assertTrue(capture.levels.isEmpty())
    }

    @Test
    fun `nothing is published until the book event publication is connected`() {
        // Both things that ask for an image do so when a subscriber is least likely to be there: a
        // restore runs inside onStart, and the operator command exists for a market data process
        // that has only just restarted. Publishing into nothing would count a drop and lose it.
        val engine = Driver(aeron = aeron, bookEventChannel = CHANNEL, bookEventStreamId = STREAM)

        engine.requestBookImage()
        assertEquals(0, engine.service.doBackgroundWork(0L), "published with no subscriber")
        assertEquals(0L, engine.service.bookImagesPublished)

        val subscription = aeron.addSubscription(CHANNEL, STREAM)
        awaitConnected(subscription)
        assertTrue(engine.service.doBackgroundWork(0L) > 0, "the image was not held until connected")
        assertEquals(1L, engine.service.bookImagesPublished)
        assertEquals(1, drain(subscription).begins)
    }

    @Test
    fun `an image is published once per request, not on every duty cycle`() {
        val subscription = aeron.addSubscription(CHANNEL, STREAM)
        val engine = Driver(aeron = aeron, bookEventChannel = CHANNEL, bookEventStreamId = STREAM)
        awaitConnected(subscription)
        drain(subscription)

        engine.requestBookImage()
        engine.service.doBackgroundWork(0L)
        repeat(5) { engine.service.doBackgroundWork(0L) }

        assertEquals(1L, engine.service.bookImagesPublished)
        assertEquals(1, drain(subscription).begins)
    }

    private fun drain(subscription: Subscription): Capture {
        val capture = Capture()
        val header = MessageHeaderDecoder()
        val begin = BookImageBeginDecoder()
        val level = BookImageLevelDecoder()
        val end = BookImageEndDecoder()
        val handler = FragmentHandler { buffer, offset, _, _ ->
            header.wrap(buffer, offset)
            val body = offset + MessageHeaderDecoder.ENCODED_LENGTH
            when (header.templateId()) {
                BookImageBeginDecoder.TEMPLATE_ID -> {
                    begin.wrap(buffer, body, header.blockLength(), header.version())
                    capture.begins++
                    capture.declaredAtBegin = begin.levelCount()
                    capture.baselineAtBegin = begin.seqNum()
                }
                BookImageLevelDecoder.TEMPLATE_ID -> {
                    level.wrap(buffer, body, header.blockLength(), header.version())
                    capture.levels += Level(
                        level.side().value(), level.price(), level.qty(), level.orderCount(),
                    )
                }
                BookImageEndDecoder.TEMPLATE_ID -> {
                    end.wrap(buffer, body, header.blockLength(), header.version())
                    capture.ends++
                    capture.declaredAtEnd = end.levelCount()
                }
            }
        }
        val deadline = System.nanoTime() + POLL_NS
        while (System.nanoTime() < deadline) {
            if (subscription.poll(handler, 128) == 0) Thread.sleep(1)
        }
        return capture
    }

    private fun awaitConnected(subscription: Subscription) {
        val deadline = System.nanoTime() + CONNECT_TIMEOUT_NS
        while (subscription.imageCount() == 0) {
            check(System.nanoTime() < deadline) { "no publication connected" }
            Thread.sleep(1)
        }
    }

    private companion object {
        const val CHANNEL = "aeron:ipc?term-length=16m"
        const val STREAM = 22
        const val CONNECT_TIMEOUT_NS = 10_000_000_000L
        const val POLL_NS = 300_000_000L
    }
}
