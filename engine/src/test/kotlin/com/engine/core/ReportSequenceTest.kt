package com.engine.core

import com.engine.sbe.RequestStatus
import io.aeron.cluster.service.Cluster
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Design.md §5, "Report sequence and resend". Every expected value here is counted from that clause,
 * not read off the code:
 *
 * - a participant's reports carry `reportSeq` from 1, gap-free, one counter per participant;
 * - the number is consumed on every node, delivered or not -- a follower's egress is mocked, and a
 *   new leader's goes nowhere until its gateway reconnects;
 * - a `ReportResendRequest` replays the participant's retained reports from `fromSeq`, each with
 *   its original number, on the session that asked, then a `ReportResendComplete` carrying
 *   `nextSeq`, `oldestRetainedSeq`, `replayedCount` and `COMPLETE`, or `TRUNCATED` when reports
 *   below `oldestRetainedSeq` were asked for and are gone.
 *
 * One resting order is one `NEW` report. That is the only engine behaviour the counts lean on.
 */
class ReportSequenceTest {

    private fun continuous(reportRetention: Int = 4096): Harness {
        val harness = Harness(arrayOf(serviceBook(SECURITY)), reportRetention = reportRetention)
        harness.defineSecurity(harness.books[0], referencePrice = 100, staticCollarBps = 5_000)
        harness.sessionTransition(Phase.PRE_OPEN)
        harness.sessionTransition(Phase.OPEN_AUCTION)
        harness.sessionTransition(Phase.CONTINUOUS)
        return harness
    }

    /** [count] buys that rest at distinct prices below the market: [count] NEW reports. */
    private fun rest(harness: Harness, participantId: Long, count: Int, firstClOrdId: Long = 100L) {
        for (i in 0..<count) {
            harness.newOrder(participantId, firstClOrdId + i, SECURITY, Side.BUY, price = 95L - i, qty = 1)
        }
    }

    private fun Harness.reportsOf(participantId: Long, session: FakeSession = this.session) =
        session.reports.filter { it.participantId == participantId }

    @Test
    fun `each participant's reports are numbered from 1 without a gap`() {
        val harness = continuous()
        rest(harness, MAKER, 3)
        rest(harness, TAKER, 2, firstClOrdId = 200L)
        rest(harness, MAKER, 1, firstClOrdId = 300L)

        assertEquals(listOf(1L, 2L, 3L, 4L), harness.reportsOf(MAKER).map { it.reportSeq })
        assertEquals(listOf(1L, 2L), harness.reportsOf(TAKER).map { it.reportSeq })
        assertEquals(4L, harness.service.lastReportSeq(MAKER))
    }

    @Test
    fun `a follower numbers its mocked reports exactly as the leader numbers its sent ones`() {
        val leader = continuous()
        val follower = continuous()
        follower.service.onRoleChange(Cluster.Role.FOLLOWER)
        follower.session.mocked = true

        for (harness in listOf(leader, follower)) {
            rest(harness, MAKER, 3)
            rest(harness, TAKER, 2, firstClOrdId = 200L)
        }

        assertTrue(follower.session.reports.isEmpty(), "sanity: a follower's egress reached no one")
        assertEquals(leader.service.lastReportSeq(MAKER), follower.service.lastReportSeq(MAKER))
        assertEquals(leader.service.lastReportSeq(TAKER), follower.service.lastReportSeq(TAKER))
        assertEquals(3L, follower.service.lastReportSeq(MAKER))
    }

    @Test
    fun `a report that could not be delivered is numbered, retained and replayed on request`() {
        val harness = continuous()
        rest(harness, MAKER, 1)
        harness.session.notConnected = true
        rest(harness, MAKER, 2, firstClOrdId = 200L)          // reports 2 and 3, sent nowhere
        harness.session.notConnected = false
        assertEquals(listOf(1L), harness.reportsOf(MAKER).map { it.reportSeq })

        harness.reportResendRequest(MAKER, fromSeq = 2L, requestId = 41L)

        val replayed = harness.reportsOf(MAKER).drop(1)
        assertEquals(listOf(2L, 3L), replayed.map { it.reportSeq })
        assertEquals(listOf(200L, 201L), replayed.map { it.clOrdId }, "the original reports, not new ones")
        assertEquals(
            ResendCompletion(MAKER, 41L, 2L, nextSeq = 4L, oldestRetainedSeq = 1L, replayedCount = 2, status = RequestStatus.COMPLETE),
            harness.session.completions.single(),
        )
    }

    @Test
    fun `a replay goes to the session that asked, in order, then the completion`() {
        val harness = continuous()
        rest(harness, MAKER, 3)
        val other = harness.openSession(2L)

        harness.on(other).reportResendRequest(MAKER, fromSeq = 1L)

        assertEquals(listOf(1L, 2L, 3L), harness.reportsOf(MAKER, other).map { it.reportSeq })
        assertEquals(3, other.completions.single().replayedCount)
        assertEquals(3, harness.reportsOf(MAKER).size, "the first session is sent nothing again")
        assertTrue(harness.session.completions.isEmpty())
    }

    @Test
    fun `asking from past the end replays nothing and says where the sequence is`() {
        val harness = continuous()
        rest(harness, MAKER, 2)

        harness.reportResendRequest(MAKER, fromSeq = 9L, requestId = 5L)

        assertEquals(2, harness.reportsOf(MAKER).size)
        assertEquals(
            ResendCompletion(MAKER, 5L, 9L, nextSeq = 3L, oldestRetainedSeq = 1L, replayedCount = 0, status = RequestStatus.COMPLETE),
            harness.session.completions.single(),
        )
    }

    @Test
    fun `a participant with no reports gets an empty, complete answer`() {
        val harness = continuous()
        harness.reportResendRequest(MAKER, fromSeq = 1L)
        assertEquals(
            ResendCompletion(MAKER, 1L, 1L, nextSeq = 1L, oldestRetainedSeq = 1L, replayedCount = 0, status = RequestStatus.COMPLETE),
            harness.session.completions.single(),
        )
    }

    @Test
    fun `reports older than the ring are reported gone, never passed off as complete`() {
        // A ring of 4 and six reports: 1 and 2 are evicted, 3 to 6 remain.
        val harness = continuous(reportRetention = 4)
        rest(harness, MAKER, 6)

        harness.reportResendRequest(MAKER, fromSeq = 1L)

        assertEquals(listOf(3L, 4L, 5L, 6L), harness.reportsOf(MAKER).drop(6).map { it.reportSeq })
        assertEquals(
            ResendCompletion(MAKER, 1L, 1L, nextSeq = 7L, oldestRetainedSeq = 3L, replayedCount = 4, status = RequestStatus.TRUNCATED),
            harness.session.completions.single(),
        )
    }

    @Test
    fun `another participant's reports evict this one's, and the sequence still holds`() {
        val harness = continuous(reportRetention = 4)
        rest(harness, MAKER, 2)                                 // maker 1, 2
        rest(harness, TAKER, 3, firstClOrdId = 200L)            // evicts maker 1
        rest(harness, MAKER, 1, firstClOrdId = 300L)            // maker 3; evicts maker 2

        harness.reportResendRequest(MAKER, fromSeq = 1L)

        assertEquals(listOf(3L), harness.reportsOf(MAKER).drop(3).map { it.reportSeq })
        val answer = harness.session.completions.single()
        assertEquals(RequestStatus.TRUNCATED, answer.status)
        assertEquals(3L, answer.oldestRetainedSeq)
        assertEquals(3L, harness.service.lastReportSeq(TAKER), "a resend for one participant numbers nothing for another")
    }

    @Test
    fun `a new leader replays the reports it generated while it was following`() {
        val node = continuous()
        node.service.onRoleChange(Cluster.Role.FOLLOWER)
        node.session.mocked = true
        rest(node, MAKER, 2)

        // Elected. Its gateway reconnects and the maker asks where it got to: nowhere.
        node.service.onRoleChange(Cluster.Role.LEADER)
        node.session.mocked = false
        node.reportResendRequest(MAKER, fromSeq = 1L)

        assertEquals(listOf(1L, 2L), node.reportsOf(MAKER).map { it.reportSeq })
        assertEquals(RequestStatus.COMPLETE, node.session.completions.single().status)
    }

    @Test
    fun `a resend request is not itself a report and consumes no number`() {
        val harness = continuous()
        rest(harness, MAKER, 2)
        harness.reportResendRequest(MAKER, fromSeq = 1L)
        rest(harness, MAKER, 1, firstClOrdId = 300L)

        assertEquals(3L, harness.service.lastReportSeq(MAKER))
        assertEquals(3L, harness.reportsOf(MAKER).last().reportSeq)
    }

    private companion object {
        const val SECURITY = 1
        const val MAKER = 7L
        const val TAKER = 9L
    }
}
