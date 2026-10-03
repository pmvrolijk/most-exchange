package com.engine.tools

import java.util.BitSet

/**
 * Which of each participant's execution reports a client has received, by `reportSeq` (Design.md
 * §5, "Report sequence and resend").
 *
 * A replay sends every retained report from the client's first missing one, so it re-sends some
 * the client already has. This is what makes counting them idempotent, and what says where to ask
 * from. A report the gateway made itself carries `reportSeq` 0 and is outside the sequence.
 *
 * Written by the receiving thread only; [firstMissing] is also read by the sending thread, where a
 * stale value only asks for more than needed, which the replay's duplicates then absorb.
 */
class ReportLedger(private val participantBase: Long, participantCount: Int) {
    private val seen = Array(participantCount) { BitSet() }
    private val firstMissing = LongArray(participantCount) { 1L }
    private val highest = LongArray(participantCount)

    /** Reports received a second time, by a replay. */
    var duplicates = 0L
        private set

    /** Reports that arrived below one already seen: a gap being filled. */
    var gapFills = 0L
        private set

    /** Times a report arrived above the next expected one: a gap opened, and a resend is owed. */
    var gapsOpened = 0L
        private set

    /** True for a report to count; false for one already received. */
    fun accept(participantId: Long, reportSeq: Long): Boolean {
        val i = indexOf(participantId)
        if (reportSeq <= 0L || i < 0) return true
        val bit = (reportSeq - 1).toInt()
        if (seen[i].get(bit)) {
            duplicates++
            return false
        }
        seen[i].set(bit)
        if (reportSeq > highest[i] + 1) gapsOpened++
        if (reportSeq < highest[i]) gapFills++ else highest[i] = reportSeq
        if (reportSeq == firstMissing[i]) firstMissing[i] = seen[i].nextClearBit(bit).toLong() + 1
        return true
    }

    /**
     * A mass status answered at [nextSeq] (Design.md §5, "Order mass status"): it states every open
     * order as of every report below [nextSeq], so those reports are no longer owed. Used when a resend
     * came back `TRUNCATED` and the reports it could not send are gone.
     */
    fun resumeFrom(participantIndex: Int, nextSeq: Long) {
        if (nextSeq <= 1L) return
        seen[participantIndex].set(0, (nextSeq - 1).toInt())
        if (highest[participantIndex] < nextSeq - 1) highest[participantIndex] = nextSeq - 1
        firstMissing[participantIndex] = seen[participantIndex].nextClearBit(0).toLong() + 1
    }

    /** Where a resend for this participant should start. */
    fun firstMissing(participantIndex: Int): Long = firstMissing[participantIndex]

    /**
     * Reports below [nextSeq] still not received. After a `COMPLETE` resend that ended at [nextSeq]
     * this must be 0; anything else is a report the engine says it sent and the client never had.
     */
    fun missingBelow(participantIndex: Int, nextSeq: Long): Long {
        val below = (nextSeq - 1).toInt()
        if (below <= 0) return 0L
        return (below - seen[participantIndex].get(0, below).cardinality()).toLong()
    }

    fun participantId(participantIndex: Int): Long = participantBase + participantIndex

    private fun indexOf(participantId: Long): Int {
        val i = participantId - participantBase
        return if (i in 0..<seen.size.toLong()) i.toInt() else -1
    }
}
