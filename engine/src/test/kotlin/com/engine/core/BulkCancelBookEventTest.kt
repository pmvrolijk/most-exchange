package com.engine.core

import com.engine.sbe.MessageHeaderDecoder
import com.engine.sbe.OrderRemovedDecoder
import com.engine.sbe.RemoveReason
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

/**
 * Design.md §4.8: the market sees a bulk cancel as ordinary cancels. One `OrderRemoved` per order,
 * `RemoveReason.CANCELED`, carrying the `leavesQty` that left the book. Needs a real publication,
 * because the book event stream is one; the harness fakes stop at egress.
 */
class BulkCancelBookEventTest {

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

    private data class Removed(val orderId: Long, val leavesQty: Long, val reason: RemoveReason)

    @Test
    fun `each bulk-cancelled order is an OrderRemoved with reason CANCELED and its leaves`() {
        val subscription = aeron.addSubscription(CHANNEL, STREAM)
        val engine = Driver(aeron = aeron, bookEventChannel = CHANNEL, bookEventStreamId = STREAM)
        awaitConnected(subscription)
        engine.newOrder(7L, 1L, Side.BUY, price = 99L, qty = 10L)
        engine.newOrder(7L, 2L, Side.SELL, price = 200L, qty = 4L)
        engine.newOrder(8L, 3L, Side.BUY, price = 98L, qty = 5L)
        drain(subscription)

        engine.cancelParticipantOrders(7L)

        assertEquals(
            listOf(Removed(1L, 10L, RemoveReason.CANCELED), Removed(2L, 4L, RemoveReason.CANCELED)),
            drain(subscription),
        )
    }

    private fun drain(subscription: Subscription): List<Removed> {
        val removed = mutableListOf<Removed>()
        val header = MessageHeaderDecoder()
        val decoder = OrderRemovedDecoder()
        val handler = FragmentHandler { buffer, offset, _, _ ->
            header.wrap(buffer, offset)
            if (header.templateId() == OrderRemovedDecoder.TEMPLATE_ID) {
                decoder.wrap(buffer, offset + MessageHeaderDecoder.ENCODED_LENGTH, header.blockLength(), header.version())
                removed += Removed(decoder.exchangeOrderId(), decoder.leavesQty(), decoder.reason())
            }
        }
        val deadline = System.nanoTime() + POLL_NS
        while (System.nanoTime() < deadline) {
            if (subscription.poll(handler, 128) == 0) Thread.sleep(1)
        }
        return removed
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
        const val STREAM = 23
        const val CONNECT_TIMEOUT_NS = 10_000_000_000L
        const val POLL_NS = 300_000_000L
    }
}
