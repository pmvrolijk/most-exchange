package com.engine.reference

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeedSequenceTrackerTest {

    @Test
    fun `a contiguous stream reports no gaps`() {
        val tracker = FeedSequenceTracker()
        for (seq in 1L..10L) assertEquals(0L, tracker.accept(shardId = 1, seqNum = seq))
        assertEquals(0L, tracker.gapsDetected)
    }

    @Test
    fun `the first message seen establishes the baseline`() {
        // Joining a feed mid-session must not report every earlier message as lost.
        val tracker = FeedSequenceTracker()
        assertEquals(0L, tracker.accept(shardId = 1, seqNum = 5_000L))
        assertEquals(0L, tracker.gapsDetected)
        assertEquals(5_001L, tracker.expectedFor(1))
    }

    @Test
    fun `a gap reports how many were missed`() {
        val tracker = FeedSequenceTracker()
        tracker.accept(1, 1)
        tracker.accept(1, 2)
        assertEquals(7L, tracker.accept(1, 10))

        assertEquals(1L, tracker.gapsDetected)
        assertEquals(7L, tracker.messagesMissed)
        assertEquals(11L, tracker.expectedFor(1))
    }

    @Test
    fun `interleaved shards on one channel each keep their own sequence`() {
        // The whole point: two shards publishing to one multicast group both number from 1, and
        // without per-shard tracking a subscriber would read every message as a gap.
        val tracker = FeedSequenceTracker()
        tracker.accept(shardId = 1, seqNum = 1)
        tracker.accept(shardId = 2, seqNum = 1)
        tracker.accept(shardId = 1, seqNum = 2)
        tracker.accept(shardId = 2, seqNum = 2)
        tracker.accept(shardId = 1, seqNum = 3)
        tracker.accept(shardId = 2, seqNum = 3)

        assertEquals(0L, tracker.gapsDetected)
        assertEquals(4L, tracker.expectedFor(1))
        assertEquals(4L, tracker.expectedFor(2))
    }

    @Test
    fun `a gap on one shard does not implicate another`() {
        val tracker = FeedSequenceTracker()
        tracker.accept(1, 1)
        tracker.accept(2, 1)
        assertEquals(4L, tracker.accept(1, 6))
        assertEquals(0L, tracker.accept(2, 2))

        assertEquals(1L, tracker.gapsDetected)
        assertEquals(4L, tracker.messagesMissed)
        assertEquals(3L, tracker.expectedFor(2))
    }

    @Test
    fun `a replayed sequence is counted separately from a gap`() {
        // Aeron does not reorder within a stream, so a lower sequence is a replay, not loss.
        val tracker = FeedSequenceTracker()
        tracker.accept(1, 1)
        tracker.accept(1, 2)
        assertEquals(0L, tracker.accept(1, 2))

        assertEquals(0L, tracker.gapsDetected)
        assertEquals(1L, tracker.duplicatesOrReplays)
        assertEquals(3L, tracker.expectedFor(1))
    }

    @Test
    fun `shards are tracked only once seen`() {
        val tracker = FeedSequenceTracker()
        assertFalse(tracker.isTracking(3))
        tracker.accept(3, 1)
        assertTrue(tracker.isTracking(3))
    }

    @Test
    fun `reset clears every shard`() {
        val tracker = FeedSequenceTracker()
        tracker.accept(1, 1)
        tracker.accept(1, 9)
        tracker.reset()

        assertEquals(0L, tracker.gapsDetected)
        assertFalse(tracker.isTracking(1))
    }
}
