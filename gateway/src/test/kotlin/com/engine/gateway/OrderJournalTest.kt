package com.engine.gateway

import org.agrona.concurrent.UnsafeBuffer
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The gateway's order state surviving its own restart.
 *
 * `origQty` exists nowhere else in the system — the engine does not store it, because an order is
 * one cache line — so a gateway that forgets it cannot ask anyone. Before the journal, a restart
 * left every in-flight order reporting a `cumQty` of zero for the rest of its life; it now reports
 * `UNKNOWN` instead, which is honest but is still not the number the client wants. This is where
 * the number actually comes back.
 *
 * Restarts are simulated by closing a store and opening another on the same file, which is exactly
 * what a killed process does: the mapped pages belong to the operating system, so a `kill -9`
 * loses nothing that reached them.
 */
class OrderJournalTest {

    private val temp: File = Files.createTempDirectory("gateway-journal").toFile()
    private val file: File get() = File(temp, "orders.jrnl")

    @AfterTest
    fun tearDown() {
        temp.deleteRecursively()
    }

    private fun store(slots: Int = 64, fingerprint: Long = FINGERPRINT) =
        OrderStateStore(file, slots, fingerprint)

    @Test
    fun `a live order comes back with its origQty and its accumulated fills`() {
        store().use { first ->
            first.recordPending(participantId = 7, clOrdId = 100, origQty = 10)
            first.bind(participantId = 7, clOrdId = 100, exchangeOrderId = 500)
            first.recordFill(exchangeOrderId = 500, lastQty = 4)
        }

        store().use { recovered ->
            assertEquals(1, recovered.recoveredOrders)
            assertEquals(1, recovered.liveOrders)
            assertEquals(10L, recovered.origQtyOf(500))
            // The number the whole thing exists for: derived from fills, not from leavesQty, and
            // therefore impossible to reconstruct from anything on the wire.
            assertEquals(4L, recovered.cumQtyOf(500))
        }
    }

    @Test
    fun `fills after a restart accumulate onto the recovered ones`() {
        store().use { first ->
            first.recordPending(7, 100, origQty = 10)
            first.bind(7, 100, exchangeOrderId = 500)
            first.recordFill(500, lastQty = 4)
        }

        store().use { recovered ->
            recovered.recordFill(500, lastQty = 3)
            assertEquals(7L, recovered.cumQtyOf(500), "the recovered fills were not carried")
        }
    }

    @Test
    fun `an order still awaiting its acknowledgement comes back too`() {
        // Its acknowledgement may well arrive after the restart: the engine names an order by
        // participant and clOrdId on the way back, which is exactly the handle kept here.
        store().use { first ->
            first.recordPending(7, 100, origQty = 25)
            assertEquals(1, first.pendingOrders)
        }

        store().use { recovered ->
            assertEquals(1, recovered.pendingOrders)
            assertEquals(0, recovered.liveOrders)
            assertEquals(25L, recovered.bind(7, 100, exchangeOrderId = 501))
            assertEquals(25L, recovered.origQtyOf(501))
        }
    }

    @Test
    fun `a released order does not come back`() {
        store().use { first ->
            first.recordPending(7, 100, origQty = 10)
            first.bind(7, 100, exchangeOrderId = 500)
            first.release(500)
        }

        store().use { recovered ->
            assertEquals(0, recovered.recoveredOrders)
            assertEquals(NOT_FOUND, recovered.origQtyOf(500))
        }
    }

    @Test
    fun `a slot freed by a release is handed out again`() {
        // Otherwise a busy gateway exhausts a journal sized for its live orders within minutes.
        store(slots = 2).use { s ->
            repeat(50) { i ->
                val id = 500L + i
                s.recordPending(7, id, origQty = 10)
                s.bind(7, id, exchangeOrderId = id)
                assertEquals(10L, s.origQtyOf(id), "order $id was not tracked")
                s.release(id)
            }
            assertEquals(0L, s.journalExhausted)
        }
    }

    @Test
    fun `an order with no slot left is forwarded untracked rather than rejected`() {
        // Refusing an order the engine would have accepted, to protect a bookkeeping structure,
        // would be the wrong trade. It reports UNKNOWN instead, which is what that flag is for.
        store(slots = 1).use { s ->
            s.recordPending(7, 100, origQty = 10)
            s.recordPending(7, 101, origQty = 20)

            assertEquals(1L, s.journalExhausted)
            assertEquals(10L, s.bind(7, 100, exchangeOrderId = 500))
            assertEquals(NOT_FOUND, s.bind(7, 101, exchangeOrderId = 501))
        }
    }

    @Test
    fun `a re-delivered order reuses its slot rather than consuming a second`() {
        // The cluster back-pressures, the fragment is left unconsumed and offered again, and the
        // same order is recorded twice. A slot leaked per retry would drain the journal under
        // exactly the load that causes retries.
        store(slots = 4).use { s ->
            repeat(10) { s.recordPending(7, 100, origQty = 10) }

            assertEquals(1, s.pendingOrders)
            assertEquals(0L, s.journalExhausted)
            assertEquals(10L, s.bind(7, 100, exchangeOrderId = 500))
        }
    }

    @Test
    fun `a journal for a different shard is refused while it holds orders`() {
        // Those orders belong to someone, and adopting them into another shard's gateway would
        // hand a client back a cumQty for an order it never placed here. Same rule as the engine
        // refusing a snapshot it cannot faithfully restore.
        store().use { it.recordPending(7, 100, origQty = 10) }

        val failure = assertFailsWith<OrderJournalUnusable> {
            OrderStateStore(file, 64, FINGERPRINT + 1).close()
        }
        assertTrue(failure.message!!.contains("holds 1 orders"), failure.message!!)
    }

    @Test
    fun `a journal whose capacity changed is refused while it holds orders`() {
        store(slots = 64).use { it.recordPending(7, 100, origQty = 10) }

        val failure = assertFailsWith<OrderJournalUnusable> {
            OrderStateStore(file, 128, FINGERPRINT).close()
        }
        assertTrue(failure.message!!.contains("capacity"), failure.message!!)
    }

    @Test
    fun `an empty journal simply adopts the new shape`() {
        // Which is how a gateway's capacity or its shard is changed: drain it first.
        store(slots = 64).use { it.recordPending(7, 100, origQty = 10) }
        store(slots = 64).use { it.discardPending(7, 100) }

        OrderStateStore(file, 128, FINGERPRINT + 1).use { s ->
            assertEquals(0, s.recoveredOrders)
            s.recordPending(9, 200, origQty = 5)
            assertEquals(5L, s.bind(9, 200, exchangeOrderId = 900))
        }
    }

    @Test
    fun `a slot whose fields were written but never marked is not adopted`() {
        // The state word is written last, so a crash between the fields and the mark leaves a slot
        // that looks free. That loses one order, which reports UNKNOWN. Adopting it instead would
        // invent an order out of whatever bytes happened to be there.
        store().use { first ->
            first.recordPending(7, 100, origQty = 10)
            first.bind(7, 100, exchangeOrderId = 500)
        }
        // Clear only the state word of slot 0, as an interrupted write would leave it.
        val raw = file.readBytes()
        for (i in 0 until 8) raw[OrderJournal.HEADER_SIZE + i] = 0
        file.writeBytes(raw)

        store().use { recovered ->
            assertEquals(0, recovered.recoveredOrders)
            assertEquals(NOT_FOUND, recovered.origQtyOf(500))
        }
    }

    @Test
    fun `a file that is not a journal is refused rather than read as one`() {
        file.writeBytes(ByteArray(OrderJournal.HEADER_SIZE + OrderJournal.SLOT_SIZE) { 0x5A })

        val failure = assertFailsWith<OrderJournalUnusable> { store().close() }
        assertTrue(failure.message!!.contains("not an order journal"), failure.message!!)
    }

    @Test
    fun `with no file at all nothing survives, and that is the documented default`() {
        OrderStateStore().use { first ->
            first.recordPending(7, 100, origQty = 10)
            first.bind(7, 100, exchangeOrderId = 500)
            assertEquals(10L, first.origQtyOf(500))
        }
        OrderStateStore().use { second ->
            assertEquals(0, second.recoveredOrders)
            assertEquals(NOT_FOUND, second.origQtyOf(500))
        }
    }

    @Test
    fun `many orders round trip in full`() {
        val expected = HashMap<Long, Pair<Long, Long>>()
        store(slots = 512).use { s ->
            for (i in 0 until 200) {
                val id = 1_000L + i
                val qty = 10L + i
                s.recordPending(participantId = i % 4L, clOrdId = id, origQty = qty)
                s.bind(i % 4L, id, exchangeOrderId = id)
                s.recordFill(id, lastQty = i % 7L)
                if (i % 5 == 0) {
                    s.release(id)
                } else {
                    expected[id] = qty to (i % 7L)
                }
            }
        }

        store(slots = 512).use { recovered ->
            assertEquals(expected.size, recovered.liveOrders)
            assertNotEquals(0, expected.size)
            for ((id, state) in expected) {
                assertEquals(state.first, recovered.origQtyOf(id), "origQty for $id")
                assertEquals(state.second, recovered.cumQtyOf(id), "cumQty for $id")
            }
        }
    }

    @Test
    fun `the state word is written after the fields it validates`() {
        // The one property a file cannot show, because it is about the order of stores rather than
        // their result. Get it backwards and an interrupted write leaves a slot marked occupied
        // over fields that were never written -- an order invented from whatever was there, handed
        // to a client with an origQty of zero. The whole reason to journal is not to do that.
        val writes = mutableListOf<Int>()
        val recording = object : UnsafeBuffer(
            ByteArray(OrderJournal.HEADER_SIZE + 4 * OrderJournal.SLOT_SIZE),
        ) {
            override fun putLong(index: Int, value: Long) {
                writes += index
                super.putLong(index, value)
            }

            override fun putLongVolatile(index: Int, value: Long) {
                writes += index
                super.putLongVolatile(index, value)
            }
        }
        val journal = OrderJournal.over(recording, 4) { _, _, _, _, _ -> }

        writes.clear()
        journal.writePending(slot = 0, participantId = 7, clOrdId = 100, origQty = 10)

        val stateAt = OrderJournal.HEADER_SIZE
        assertEquals(stateAt, writes.last(), "the state word must be the last store of the slot")
        assertTrue(writes.size > 1, "sanity: the fields were written too")
    }

    @Test
    fun `a slot marked occupied over fields that were never written is not adopted`() {
        // The backstop for the ordering above: even marked, a slot with no quantity cannot have
        // come from a completed write, and adopting it would report an order with origQty zero --
        // exactly the confident zero the enrichment flag exists to avoid.
        store().use { it.recordPending(7, 100, origQty = 10) }
        val raw = file.readBytes()
        // Zero origQty while leaving the slot marked, as a mark-first write interrupted early
        // would have left it.
        for (i in 0 until 8) raw[OrderJournal.HEADER_SIZE + ORIG_QTY_OFFSET + i] = 0
        file.writeBytes(raw)

        store().use { recovered ->
            assertEquals(0, recovered.recoveredOrders)
            assertEquals(0, recovered.pendingOrders)
        }
    }

    private companion object {
        const val FINGERPRINT = 0x1234_5678_9abc_def0L

        /** Matches OrderJournal's slot layout; only the test needs to know it. */
        const val ORIG_QTY_OFFSET = 32
    }
}
