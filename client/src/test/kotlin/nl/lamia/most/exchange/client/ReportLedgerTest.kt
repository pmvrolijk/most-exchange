package nl.lamia.most.exchange.client

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
    fun `a mass status resumes the sequence past reports a truncated resend could not send`() {
        ledger.accept(20L, 1L)
        ledger.accept(20L, 9L)          // 2 to 8 lost, and the ring no longer holds them
        assertEquals(2L, ledger.firstMissing(0))

        ledger.resumeFrom(0, nextSeq = 10L)

        assertEquals(10L, ledger.firstMissing(0))
        assertEquals(0L, ledger.missingBelow(0, nextSeq = 10L), "the status stands in for them")
        assertTrue(ledger.accept(20L, 10L), "and the sequence carries on from there")
    }

    @Test
    fun `a participant outside the run is not tracked`() {
        assertTrue(ledger.accept(99L, 1L))
        assertTrue(ledger.accept(99L, 1L))
    }

    @Test
    fun `an adapter's participants need not be a range, and each keeps its own sequence`() {
        // Design.md §5: one counter per participant. An adapter acts for whichever ids it was given.
        val sparse = ReportLedger(longArrayOf(7L, 1_000L, 42L))
        sparse.accept(1_000L, 1L)
        sparse.accept(1_000L, 3L)
        sparse.accept(42L, 1L)
        assertEquals(1L, sparse.firstMissing(sparse.indexOf(7L)))
        assertEquals(2L, sparse.firstMissing(sparse.indexOf(1_000L)))
        assertEquals(2L, sparse.firstMissing(sparse.indexOf(42L)))
        assertEquals(-1, sparse.indexOf(8L), "a participant it does not act for")
        assertTrue(sparse.accept(8L, 5L), "an untracked participant's report is passed on, not counted")
        assertEquals(1L, sparse.gapsOpened)
    }
}
