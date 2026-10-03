package com.engine.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A client's half of Design.md §5, "Report sequence and resend": it asks from its first missing
 * report, counts a replayed report once, and can tell a gap that a replay filled from one it did not.
 */
class ReportLedgerTest {

    private val ledger = ReportLedger(participantBase = 20L, participantCount = 2)

    @Test
    fun `in order, the first missing report is the next one`() {
        for (seq in 1L..3L) assertTrue(ledger.accept(20L, seq))
        assertEquals(4L, ledger.firstMissing(0))
        assertEquals(1L, ledger.firstMissing(1), "each participant has its own sequence")
    }

    @Test
    fun `a gap holds the first missing report back until it is filled`() {
        ledger.accept(20L, 1L)
        ledger.accept(20L, 4L)          // 2 and 3 were lost
        assertEquals(2L, ledger.firstMissing(0))
        assertEquals(2L, ledger.missingBelow(0, nextSeq = 5L))
        assertEquals(1L, ledger.gapsOpened, "a jump past the next report is what tells a client to ask")

        ledger.accept(20L, 2L)
        ledger.accept(20L, 3L)          // the replay fills them
        assertEquals(5L, ledger.firstMissing(0))
        assertEquals(0L, ledger.missingBelow(0, nextSeq = 5L))
        assertEquals(2L, ledger.gapFills)
    }

    @Test
    fun `a replayed report already received is counted once`() {
        ledger.accept(20L, 1L)
        ledger.accept(20L, 2L)
        assertFalse(ledger.accept(20L, 1L))
        assertFalse(ledger.accept(20L, 2L))
        assertEquals(2L, ledger.duplicates)
    }

    @Test
    fun `a report the gateway made itself is outside the sequence and always counted`() {
        assertTrue(ledger.accept(20L, 0L))
        assertTrue(ledger.accept(20L, 0L))
        assertEquals(1L, ledger.firstMissing(0))
        assertEquals(0L, ledger.duplicates)
    }

    @Test
    fun `a participant outside the run is not tracked`() {
        assertTrue(ledger.accept(99L, 1L))
        assertTrue(ledger.accept(99L, 1L))
    }
}
