package nl.lamia.most.exchange.core

import io.aeron.cluster.service.Cluster
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Design.md §5, "Sequence Numbers and Shard Namespacing": every book event's `seqNum` is stamped
 * where the event is **generated**, "which happens identically on every node, so a new leader
 * continues the sequence rather than restarting it". It is replicated state, snapshotted beside
 * `nextExchangeOrderId`.
 *
 * So whether an event is *published* -- only the leader publishes, and only to a connected
 * publication -- must not decide whether it consumes a number. The harness has no book event
 * publication at all, which makes every node here one whose publication is not connected; the
 * expected values are counted from the clause, not read off the code. Until the first three-node
 * run (`e2e/run-cluster3.sh`) this was invisible: one node is always the leader, and a restored
 * member printed `nextBookEventSeqNum=1` after a session of trading.
 */
class BookEventSequenceTest {

    private fun continuous(role: Cluster.Role): Harness {
        val harness = Harness(arrayOf(serviceBook(SECURITY)))
        harness.service.onRoleChange(role)
        harness.defineSecurity(harness.books[0], referencePrice = 100, staticCollarBps = 5_000)
        harness.sessionTransition(Phase.PRE_OPEN)
        harness.sessionTransition(Phase.OPEN_AUCTION)
        harness.sessionTransition(Phase.CONTINUOUS)
        return harness
    }

    /** One order rests (OrderAdded), then is cancelled (OrderRemoved): two events. */
    private fun restAndCancel(harness: Harness, clOrdId: Long) {
        val orderId = harness.service.nextExchangeOrderId
        harness.newOrder(PARTICIPANT, clOrdId, SECURITY, Side.BUY, price = 99, qty = 5)
        harness.cancel(PARTICIPANT, clOrdId, clOrdId + 1, exchangeOrderId = orderId, securityId = SECURITY, side = Side.BUY)
    }

    @Test
    fun `a follower consumes book event sequence numbers exactly as the leader does`() {
        val leader = continuous(Cluster.Role.LEADER)
        val follower = continuous(Cluster.Role.FOLLOWER)
        assertEquals(
            leader.service.nextBookEventSeqNum,
            follower.service.nextBookEventSeqNum,
            "the session transitions numbered differently on a follower",
        )
        val before = leader.service.nextBookEventSeqNum

        restAndCancel(leader, 10L)
        restAndCancel(follower, 10L)

        assertEquals(before + 2, leader.service.nextBookEventSeqNum, "a rest and a cancel are two book events")
        assertEquals(before + 2, follower.service.nextBookEventSeqNum, "a follower must number what it generates")
    }

    @Test
    fun `the session transitions consume sequence numbers on a node that publishes nothing`() {
        // One SessionChanged per security per transition: three transitions, one security.
        val follower = continuous(Cluster.Role.FOLLOWER)
        assertEquals(1L + 3, follower.service.nextBookEventSeqNum)
    }

    @Test
    fun `a new leader continues the sequence rather than restarting it`() {
        val node = continuous(Cluster.Role.FOLLOWER)
        restAndCancel(node, 10L)
        val asFollower = node.service.nextBookEventSeqNum

        node.service.onRoleChange(Cluster.Role.LEADER)
        restAndCancel(node, 20L)

        assertEquals(asFollower + 2, node.service.nextBookEventSeqNum)
    }

    private companion object {
        const val SECURITY = 1
        const val PARTICIPANT = 7L
    }
}
