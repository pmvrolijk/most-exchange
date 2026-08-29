package com.engine.reference

import org.agrona.collections.Int2ObjectHashMap

/**
 * Per-shard gap detection for a feed, so several shards may publish to one multicast group.
 *
 * Each shard numbers its streams from 1 independently, so on a shared channel a subscriber sees
 * interleaved sequences and would read every message as a gap. Every book event and feed message
 * therefore carries `shardId`, and the sequence is tracked **per shard** rather than per channel.
 *
 * Detection only. Under `MaxMulticastFlowControl` — the correct setting for a market data feed — a
 * slow subscriber takes an unrecoverable gap by design, so what to do about one is the
 * subscriber's decision: resynchronise from a snapshot, alert, or drop the security.
 */
class FeedSequenceTracker {

    private val expectedByShard = Int2ObjectHashMap<LongBox>()

    var gapsDetected = 0L
        private set

    var messagesMissed = 0L
        private set

    var duplicatesOrReplays = 0L
        private set

    /**
     * Records [seqNum] from [shardId]. Returns the number of messages missed — 0 when the stream
     * is contiguous, and 0 for the first message seen from a shard, which establishes the
     * baseline rather than counting everything before it as lost.
     */
    fun accept(shardId: Int, seqNum: Long): Long {
        val box = expectedByShard.computeIfAbsent(shardId) { LongBox() }
        if (!box.initialised) {
            box.initialised = true
            box.expected = seqNum + 1
            return 0L
        }
        if (seqNum == box.expected) {
            box.expected = seqNum + 1
            return 0L
        }
        if (seqNum < box.expected) {
            // Aeron does not reorder within a stream, so this is a replay or a duplicate.
            duplicatesOrReplays++
            return 0L
        }
        val missed = seqNum - box.expected
        gapsDetected++
        messagesMissed += missed
        box.expected = seqNum + 1
        return missed
    }

    fun expectedFor(shardId: Int): Long = expectedByShard[shardId]?.expected ?: 0L

    fun isTracking(shardId: Int): Boolean = expectedByShard.containsKey(shardId)

    fun reset() {
        expectedByShard.clear()
        gapsDetected = 0
        messagesMissed = 0
        duplicatesOrReplays = 0
    }

    private class LongBox {
        var expected = 0L
        var initialised = false
    }
}
