package com.engine.gateway

import org.agrona.IoUtil
import org.agrona.concurrent.UnsafeBuffer
import java.io.File
import java.nio.MappedByteBuffer

/**
 * The gateway's order state, in a slot per live order, optionally memory-mapped to a file.
 *
 * This is not a log written *beside* the store — it **is** the store's memory. `OrderStateStore`
 * keeps only the two index maps that find a slot; every value lives here. That is deliberate: a
 * journal maintained alongside an in-memory copy is a second bookkeeping that can drift from the
 * first, and the drift would surface only after a restart, which is the one moment nobody can
 * check it. It is also the engine's own idiom, one layer out — a packed slot array plus a
 * primitive id map (Design.md §3).
 *
 * **What a restart costs, and what it does not.** `origQty` exists nowhere else: the engine does
 * not store it (an order is one cache line and has no room), so no amount of asking recovers it.
 * With a file behind this, a gateway that is killed and restarted comes back with every live order
 * intact, because the mapped pages belong to the operating system and outlive the process. Without
 * one — the default — nothing survives, and a report for an unknown order is marked
 * `Enrichment.UNKNOWN` rather than given a `cumQty` of zero.
 *
 * **The bound worth stating: there is no `msync` on the hot path.** Flushing per order would put a
 * disk write on a leg measured at 0.2 µs. So a *process* crash is fully recovered and a *machine*
 * power loss can lose the most recent writes — those orders come back as `UNKNOWN`, which is the
 * same honest answer as never having journalled at all.
 *
 * Slots are one cache line. The state word is written **last** and read first, so a slot whose
 * fields were only half written is never adopted; and `cumQty` is updated by a single aligned
 * 8-byte store, which cannot tear.
 */
class OrderJournal private constructor(
    private val buffer: UnsafeBuffer,
    private val mapped: MappedByteBuffer?,
    val slotCount: Int,
) : AutoCloseable {

    /** Slots holding an order that has been acknowledged and is not yet terminal. */
    var liveSlots = 0
        private set

    /**
     * Slots holding an order forwarded but not yet acknowledged.
     *
     * Counted separately because it is the one that leaks: nothing reaps a pending entry whose
     * acknowledgement never comes, and with a bounded slot array a leak eventually shows up as
     * orders that cannot be tracked at all. A number that only grows is the tell.
     */
    var pendingSlots = 0
        private set

    /** Orders that arrived with no slot left to put them in, and so cannot be tracked. */
    var exhausted = 0L
        private set

    private var freeHead = NULL_SLOT

    // ------------------------------------------------------------ allocation

    /**
     * Takes a free slot, or [NULL_SLOT] when there are none.
     *
     * Exhaustion is not an error and must not reject an order the engine would have accepted: the
     * order is forwarded untracked, and every report for it is marked `UNKNOWN`. Sizing makes this
     * unreachable in the resting case — the default capacity is the shard's own order pool, so the
     * engine answers `BOOK_CAPACITY` first.
     */
    fun acquire(): Int {
        val slot = freeHead
        if (slot == NULL_SLOT) {
            exhausted++
            return NULL_SLOT
        }
        freeHead = buffer.getInt(offsetOf(slot) + NEXT_FREE)
        return slot
    }

    fun releaseSlot(slot: Int) {
        when (buffer.getLong(offsetOf(slot) + STATE)) {
            STATE_LIVE -> liveSlots--
            STATE_PENDING -> pendingSlots--
        }
        free(slot)
    }

    private fun free(slot: Int) {
        val base = offsetOf(slot)
        buffer.putInt(base + NEXT_FREE, freeHead)
        // Ordered, and last: a slot is free only once the link that makes it reusable is visible.
        buffer.putLongVolatile(base + STATE, STATE_FREE)
        freeHead = slot
    }

    // ---------------------------------------------------------------- writing

    /**
     * Records an order forwarded to the cluster but not yet named by it.
     *
     * Idempotent on a slot that is already pending, because a fragment the cluster back-pressured
     * is re-delivered and recorded again. Rewriting in place is what keeps a retry from consuming
     * a second slot — the defect this shape is guarding against is subtler than a leak, since a
     * slot released and immediately rewritten would still be on the free list and handed out twice.
     */
    fun writePending(slot: Int, participantId: Long, clOrdId: Long, origQty: Long) {
        val base = offsetOf(slot)
        val wasPending = buffer.getLong(base + STATE) == STATE_PENDING
        buffer.putLong(base + PARTICIPANT_ID, participantId)
        buffer.putLong(base + CL_ORD_ID, clOrdId)
        buffer.putLong(base + EXCHANGE_ORDER_ID, 0L)
        buffer.putLong(base + ORIG_QTY, origQty)
        buffer.putLong(base + CUM_QTY, 0L)
        // Written last, with a release store: a crash between the fields and this leaves a slot
        // that replay treats as free, which loses one order rather than inventing one.
        buffer.putLongVolatile(base + STATE, STATE_PENDING)
        if (!wasPending) pendingSlots++
    }

    /** Names an order the engine has acknowledged. */
    fun promote(slot: Int, exchangeOrderId: Long) {
        val base = offsetOf(slot)
        buffer.putLong(base + EXCHANGE_ORDER_ID, exchangeOrderId)
        buffer.putLongVolatile(base + STATE, STATE_LIVE)
        pendingSlots--
        liveSlots++
    }

    /** A single aligned 8-byte store, so a crash finds either the old value or the new one. */
    fun addCumQty(slot: Int, lastQty: Long) {
        val at = offsetOf(slot) + CUM_QTY
        buffer.putLong(at, buffer.getLong(at) + lastQty)
    }

    // ---------------------------------------------------------------- reading

    fun participantIdOf(slot: Int): Long = buffer.getLong(offsetOf(slot) + PARTICIPANT_ID)
    fun clOrdIdOf(slot: Int): Long = buffer.getLong(offsetOf(slot) + CL_ORD_ID)
    fun exchangeOrderIdOf(slot: Int): Long = buffer.getLong(offsetOf(slot) + EXCHANGE_ORDER_ID)
    fun origQtyOf(slot: Int): Long = buffer.getLong(offsetOf(slot) + ORIG_QTY)
    fun cumQtyOf(slot: Int): Long = buffer.getLong(offsetOf(slot) + CUM_QTY)
    fun stateOf(slot: Int): Long = buffer.getLong(offsetOf(slot) + STATE)

    /**
     * Walks the slots once, rebuilding the free chain and handing every occupied one to [onOrder].
     *
     * Called at construction, so an existing file is adopted and a new one is initialised by the
     * same code. Sequential over the whole array: at the default capacity that is the shard's
     * order pool, which is the same walk `loadSnapshot` does one process over.
     */
    private fun rebuild(onOrder: RecoveredOrder) {
        freeHead = NULL_SLOT
        liveSlots = 0
        pendingSlots = 0
        // Descending, so the free chain comes out ascending and a fresh journal fills from slot 0 --
        // which keeps a small run's working set to the first few pages instead of the last.
        for (slot in slotCount - 1 downTo 0) {
            val base = offsetOf(slot)
            when (val state = if (isPlausible(buffer, base)) buffer.getLong(base + STATE) else STATE_FREE) {
                STATE_PENDING, STATE_LIVE -> {
                    if (state == STATE_LIVE) liveSlots++ else pendingSlots++
                    onOrder.accept(
                        slot,
                        state,
                        buffer.getLong(base + PARTICIPANT_ID),
                        buffer.getLong(base + CL_ORD_ID),
                        buffer.getLong(base + EXCHANGE_ORDER_ID),
                    )
                }
                else -> {
                    buffer.putInt(base + NEXT_FREE, freeHead)
                    freeHead = slot
                }
            }
        }
    }

    /**
     * Whether a slot's contents could have come from a completed write.
     *
     * Defence in depth behind the write ordering. The state word is written last, so an
     * interrupted write leaves a slot that already reads as free — but that argument is only as
     * good as the ordering, and the cost of being wrong is an order invented out of whatever bytes
     * were there, reported to a client with an `origQty` of zero. Every real order has a positive
     * quantity (the gateway rejects anything else before it gets here) and every acknowledged one
     * has an id, so a slot failing either was never fully written.
     */
    private fun isPlausible(buffer: UnsafeBuffer, base: Int): Boolean {
        if (buffer.getLong(base + ORIG_QTY) <= 0L) return false
        return buffer.getLong(base + STATE) != STATE_LIVE ||
            buffer.getLong(base + EXCHANGE_ORDER_ID) != 0L
    }

    override fun close() {
        mapped?.let { IoUtil.unmap(it) }
    }

    companion object {
        const val NULL_SLOT = -1

        const val SLOT_SIZE = 64
        const val HEADER_SIZE = 64

        private const val STATE = 0
        private const val PARTICIPANT_ID = 8
        private const val CL_ORD_ID = 16
        private const val EXCHANGE_ORDER_ID = 24
        private const val ORIG_QTY = 32
        private const val CUM_QTY = 40
        // Only meaningful while a slot is free, so it may share space with a live field.
        private const val NEXT_FREE = 48

        const val STATE_FREE = 0L
        const val STATE_PENDING = 1L
        const val STATE_LIVE = 2L

        private const val MAGIC = 0x4D4F5354_4A524E4CL // "MOSTJRNL"
        private const val FORMAT_VERSION = 1L

        private const val H_MAGIC = 0
        private const val H_VERSION = 8
        private const val H_SLOT_COUNT = 16
        private const val H_FINGERPRINT = 24

        /**
         * Opens over a buffer supplied directly, for tests that need to watch the writes.
         *
         * The order in which a slot's words are stored is load-bearing and invisible from the
         * outside: the only way to pin it is to hand the journal a buffer that records what it was
         * asked to do, in order.
         */
        internal fun over(
            buffer: UnsafeBuffer,
            slotCount: Int,
            onOrder: RecoveredOrder,
        ): OrderJournal {
            val journal = OrderJournal(buffer, null, slotCount)
            journal.rebuild(onOrder)
            return journal
        }

        /**
         * Opens [file], or an anonymous heap buffer when it is null.
         *
         * A null file is the default and means no persistence: the gateway behaves exactly as it
         * did before this existed, and a restart marks every in-flight order `UNKNOWN`. A
         * production gateway sets one.
         */
        fun open(
            file: File?,
            slotCount: Int,
            shardFingerprint: Long,
            onOrder: RecoveredOrder,
        ): OrderJournal {
            require(slotCount > 0) { "journal slotCount must be positive: $slotCount" }
            val length = HEADER_SIZE.toLong() + slotCount.toLong() * SLOT_SIZE

            if (file == null) {
                val journal = OrderJournal(UnsafeBuffer(ByteArray(length.toInt())), null, slotCount)
                journal.rebuild(onOrder)
                return journal
            }

            var mapped: MappedByteBuffer? = null
            if (file.isFile && file.length() > 0) {
                mapped = IoUtil.mapExistingFile(file, "gateway order journal")
                // Refuses outright when the file holds orders this gateway must not adopt;
                // otherwise says whether the existing mapping can be kept.
                if (!adopt(file, UnsafeBuffer(mapped), slotCount, shardFingerprint, length, mapped)) {
                    // Empty, but the wrong shape. The file has to be remade rather than
                    // re-headered: it was sized for a different slot count, and a larger one would
                    // walk straight off the end of the mapping.
                    IoUtil.unmap(mapped)
                    file.delete()
                    mapped = null
                }
            }

            val liveMapping = mapped ?: IoUtil.mapNewFile(file, length)
            val buffer = UnsafeBuffer(liveMapping)
            if (mapped == null) {
                buffer.putLong(H_SLOT_COUNT, slotCount.toLong())
                buffer.putLong(H_FINGERPRINT, shardFingerprint)
                buffer.putLong(H_VERSION, FORMAT_VERSION)
                buffer.putLongVolatile(H_MAGIC, MAGIC)
            }

            val journal = OrderJournal(buffer, liveMapping, slotCount)
            journal.rebuild(onOrder)
            return journal
        }

        /**
         * Refuses a journal this gateway cannot faithfully adopt.
         *
         * Same rule as the engine's snapshot restore, and for the same reason: silently discarding
         * orders a client believes are live is worse than not starting. A journal that holds
         * nothing is simply re-initialised, which is how a capacity or a shard is changed.
         */
        @Suppress("ReturnCount")
        private fun adopt(
            file: File,
            buffer: UnsafeBuffer,
            slotCount: Int,
            shardFingerprint: Long,
            requiredLength: Long,
            mapped: MappedByteBuffer,
        ): Boolean {
            val magic = buffer.getLong(H_MAGIC)
            val version = buffer.getLong(H_VERSION)
            if (magic != MAGIC || version != FORMAT_VERSION) {
                IoUtil.unmap(mapped)
                throw OrderJournalUnusable(
                    "gateway: $file is not an order journal this build can read " +
                        "(magic=${java.lang.Long.toHexString(magic)} version=$version).\n" +
                        "  Move it aside to start with no recovered orders."
                )
            }

            val storedSlots = buffer.getLong(H_SLOT_COUNT)
            val storedFingerprint = buffer.getLong(H_FINGERPRINT)
            val fits = buffer.capacity().toLong() >= requiredLength
            if (storedSlots == slotCount.toLong() && storedFingerprint == shardFingerprint && fits) {
                return true
            }

            val occupied = countOccupied(buffer, storedSlots.toInt())
            if (occupied > 0) {
                IoUtil.unmap(mapped)
                throw OrderJournalUnusable(
                    buildString {
                        appendLine("gateway: $file was written by a different gateway and holds $occupied orders.")
                        appendLine()
                        if (storedSlots != slotCount.toLong() || !fits) {
                            appendLine("  capacity: journal $storedSlots slots, this gateway $slotCount")
                        }
                        if (storedFingerprint != shardFingerprint) {
                            appendLine(
                                "  shard:    journal ${java.lang.Long.toHexString(storedFingerprint)}, " +
                                    "this gateway ${java.lang.Long.toHexString(shardFingerprint)}"
                            )
                        }
                        appendLine()
                        appendLine("Those orders are live as far as their owners know. Start with the")
                        appendLine("previous configuration to hand them back, or move the file aside to")
                        appendLine("abandon them -- every report for one then reports cumQty as unknown.")
                    }
                )
            }
            // Empty, so nothing is lost by starting again in the shape this gateway wants.
            return false
        }

        private fun countOccupied(buffer: UnsafeBuffer, storedSlots: Int): Int {
            var occupied = 0
            val capacity = (buffer.capacity() - HEADER_SIZE) / SLOT_SIZE
            val limit = if (storedSlots in 1..capacity) storedSlots else capacity
            for (slot in 0 until limit) {
                if (buffer.getLong(offsetOf(slot) + STATE) != STATE_FREE) occupied++
            }
            return occupied
        }

        private fun offsetOf(slot: Int): Int = HEADER_SIZE + slot * SLOT_SIZE
    }
}

/**
 * What recovery hands back for each occupied slot.
 *
 * The fields come with the slot rather than being read back through the journal, because the
 * journal is still under construction when this runs — a caller that had to hold a reference to it
 * would be holding one that is not yet assigned.
 */
fun interface RecoveredOrder {
    fun accept(slot: Int, state: Long, participantId: Long, clOrdId: Long, exchangeOrderId: Long)
}

/** A journal file this gateway must not adopt. Fatal at startup; never thrown once running. */
class OrderJournalUnusable(message: String) : RuntimeException(message)
