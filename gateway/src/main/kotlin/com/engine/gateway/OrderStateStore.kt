package com.engine.gateway

import org.agrona.collections.Long2LongHashMap
import org.agrona.collections.Long2ObjectHashMap
import java.io.File

const val NOT_FOUND = -1L

/**
 * Remembers the original quantity of every live order, which is the gateway's reason to be
 * stateful (Design.md §1).
 *
 * The engine's `ExecutionReport` carries no `origQty` and therefore no `cumQty`: the packed order
 * slot is exactly one cache line and has no room for a field the boundary can reconstruct. The
 * gateway sent `origQty`, the engine reports `leavesQty`, so `cumQty = origQty - leavesQty` is
 * recoverable here and nowhere cheaper.
 *
 * An order is tracked in two phases because the gateway forwards it before the engine has named
 * it. Until the `NEW` acknowledgement arrives the only handle is the client's own `clOrdId`,
 * which is unique per participant but not globally, so the pending side is keyed by participant.
 * On acknowledgement the entry moves to the engine's `exchangeOrderId`, which is unique outright.
 *
 * **The values live in [OrderJournal], not here.** These maps hold slot indices and nothing else,
 * so there is one copy of an order's state rather than a durable one and a memory one that can
 * disagree — and they disagree, if at all, only after a restart, which is the one moment nobody is
 * in a position to check. With a file behind the journal a killed gateway comes back with its
 * orders; without one it behaves exactly as it did before the journal existed, and says `UNKNOWN`.
 */
class OrderStateStore(
    file: File? = null,
    slotCount: Int = DEFAULT_SLOTS,
    shardFingerprint: Long = 0L,
) : AutoCloseable {

    // clOrdId is unique per participant, not globally, so the pending side needs both.
    private val pendingByParticipant = Long2ObjectHashMap<Long2LongHashMap>()
    private val slotByExchangeOrderId = Long2LongHashMap(NOT_FOUND)

    /** Orders adopted from a journal file at startup. Zero on a cold start. */
    var recoveredOrders: Int = 0
        private set

    // Recovery is just re-indexing: the journal knows which slots are occupied and what is in
    // them, and all that is left is to put them back in the maps that find one.
    private val journal =
        OrderJournal.open(file, slotCount, shardFingerprint) { slot, state, participantId, clOrdId, exchangeOrderId ->
            if (state == OrderJournal.STATE_LIVE) {
                slotByExchangeOrderId.put(exchangeOrderId, slot.toLong())
            } else {
                pendingFor(participantId).put(clOrdId, slot.toLong())
            }
            recoveredOrders++
        }

    val liveOrders: Int get() = journal.liveSlots
    val pendingOrders: Int get() = journal.pendingSlots

    /** Orders that arrived with no slot to track them in, and so will report `UNKNOWN`. */
    val journalExhausted: Long get() = journal.exhausted

    /**
     * An order forwarded to the cluster and not yet acknowledged.
     *
     * With no slot left the order is simply not tracked — never rejected. Refusing an order the
     * engine would have accepted to protect a bookkeeping structure would be the wrong trade; the
     * report comes back marked `UNKNOWN` instead, which is what that flag is for.
     */
    fun recordPending(participantId: Long, clOrdId: Long, origQty: Long) {
        val pending = pendingFor(participantId)
        // A fragment the cluster back-pressured is re-delivered, so the same order can arrive
        // twice. Reuse its slot: writePending rewrites in place and counts it once.
        val existing = pending.get(clOrdId)
        val slot = if (existing != NOT_FOUND) existing.toInt() else journal.acquire()
        if (slot == OrderJournal.NULL_SLOT) return
        journal.writePending(slot, participantId, clOrdId, origQty)
        pending.put(clOrdId, slot.toLong())
    }

    /**
     * Binds a pending order to the id the engine assigned. Returns the original quantity, or
     * [NOT_FOUND] if this gateway never saw the order — which happens legitimately after a
     * gateway restart with no journal, and must not be treated as an error.
     */
    fun bind(participantId: Long, clOrdId: Long, exchangeOrderId: Long): Long {
        val pending = pendingByParticipant.get(participantId) ?: return NOT_FOUND
        val slot = pending.remove(clOrdId)
        if (slot == NOT_FOUND) return NOT_FOUND
        journal.promote(slot.toInt(), exchangeOrderId)
        slotByExchangeOrderId.put(exchangeOrderId, slot)
        return journal.origQtyOf(slot.toInt())
    }

    fun origQtyOf(exchangeOrderId: Long): Long {
        val slot = slotByExchangeOrderId.get(exchangeOrderId)
        return if (slot == NOT_FOUND) NOT_FOUND else journal.origQtyOf(slot.toInt())
    }

    /**
     * Filled quantity, accumulated from each fill's lastQty rather than derived as
     * `origQty - leavesQty`. A terminal report carries `leavesQty = 0` whether the order filled
     * or was cancelled, so deriving it would report a cancelled order as fully filled.
     */
    fun cumQtyOf(exchangeOrderId: Long): Long {
        val slot = slotByExchangeOrderId.get(exchangeOrderId)
        return if (slot == NOT_FOUND) 0L else journal.cumQtyOf(slot.toInt())
    }

    /** Accumulates a fill. Ignored for an order this gateway never saw. */
    fun recordFill(exchangeOrderId: Long, lastQty: Long) {
        val slot = slotByExchangeOrderId.get(exchangeOrderId)
        if (slot == NOT_FOUND) return
        journal.addCumQty(slot.toInt(), lastQty)
    }

    /** Drops a rejected order that never reached the book, so pending state cannot accumulate. */
    fun discardPending(participantId: Long, clOrdId: Long) {
        val slot = pendingByParticipant.get(participantId)?.remove(clOrdId) ?: return
        if (slot != NOT_FOUND) journal.releaseSlot(slot.toInt())
    }

    fun release(exchangeOrderId: Long) {
        val slot = slotByExchangeOrderId.remove(exchangeOrderId)
        if (slot != NOT_FOUND) journal.releaseSlot(slot.toInt())
    }

    override fun close() = journal.close()

    private fun pendingFor(participantId: Long): Long2LongHashMap =
        pendingByParticipant.computeIfAbsent(participantId) { Long2LongHashMap(NOT_FOUND) }

    companion object {
        /** Enough for a test or a small run; a gateway derives its own from the shard it serves. */
        const val DEFAULT_SLOTS = 1 shl 16
    }
}
