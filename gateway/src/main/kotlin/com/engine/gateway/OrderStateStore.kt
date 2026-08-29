package com.engine.gateway

import org.agrona.collections.Long2LongHashMap
import org.agrona.collections.Long2ObjectHashMap

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
 */
class OrderStateStore {

    private val pendingByParticipant = Long2ObjectHashMap<Long2LongHashMap>()
    private val liveByExchangeOrderId = Long2LongHashMap(NOT_FOUND)

    var liveOrders = 0
        private set

    fun recordPending(participantId: Long, clOrdId: Long, origQty: Long) {
        pendingByParticipant
            .computeIfAbsent(participantId) { Long2LongHashMap(NOT_FOUND) }
            .put(clOrdId, origQty)
    }

    /**
     * Binds a pending order to the id the engine assigned. Returns the original quantity, or
     * [NOT_FOUND] if this gateway never saw the order — which happens legitimately after a
     * gateway restart, and must not be treated as an error.
     */
    fun bind(participantId: Long, clOrdId: Long, exchangeOrderId: Long): Long {
        val pending = pendingByParticipant.get(participantId) ?: return NOT_FOUND
        val origQty = pending.remove(clOrdId)
        if (origQty == NOT_FOUND) return NOT_FOUND
        liveByExchangeOrderId.put(exchangeOrderId, origQty)
        liveOrders++
        return origQty
    }

    fun origQtyOf(exchangeOrderId: Long): Long = liveByExchangeOrderId.get(exchangeOrderId)

    /** Drops a rejected order that never reached the book, so pending state cannot accumulate. */
    fun discardPending(participantId: Long, clOrdId: Long) {
        pendingByParticipant.get(participantId)?.remove(clOrdId)
    }

    fun release(exchangeOrderId: Long) {
        if (liveByExchangeOrderId.remove(exchangeOrderId) != NOT_FOUND) liveOrders--
    }
}
