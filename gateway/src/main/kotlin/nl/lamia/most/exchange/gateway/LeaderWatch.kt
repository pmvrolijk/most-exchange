package nl.lamia.most.exchange.gateway

import io.aeron.AeronCounters
import io.aeron.cluster.service.Cluster
import io.aeron.cluster.service.ClusterCounters
import org.agrona.concurrent.status.CountersReader

/**
 * Whether the consensus module on this gateway's own media driver is the leader, read from its
 * `Cluster node role` counter.
 *
 * A co-located gateway (`gateway.placement=colocated`) cannot learn this from its cluster client.
 * With egress on IPC, a new leader's `NewLeaderEvent` is sent on the new leader's media driver,
 * which a gateway on the old leader's machine never sees. Its client would sit in
 * `AWAIT_NEW_LEADER` for `newLeaderTimeoutNs` (~20 s by default), offering orders into a follower,
 * before closing. The counter changes the moment the node's role does (Design.md §7, "Gateway
 * placement"; Rationale §15).
 *
 * Read from the gateway's poll loop, at an interval, never per message. The counter id is found
 * once and checked again on each read, because a counter that was freed may be reused for another.
 */
class LeaderWatch(
    private val counters: CountersReader,
    private val clusterId: Int = 0,
) {
    private var counterId = CountersReader.NULL_COUNTER_ID

    /** The node's role, or null when no consensus module on this driver has allocated one. */
    fun role(): Cluster.Role? {
        if (!isRoleCounter(counterId)) {
            counterId = ClusterCounters.find(counters, AeronCounters.CLUSTER_NODE_ROLE_TYPE_ID, clusterId)
        }
        if (counterId == CountersReader.NULL_COUNTER_ID) return null
        return Cluster.Role.get(counters.getCounterValue(counterId))
    }

    fun isLeader(): Boolean = role() == Cluster.Role.LEADER

    private fun isRoleCounter(id: Int): Boolean =
        id != CountersReader.NULL_COUNTER_ID &&
            counters.getCounterState(id) == CountersReader.RECORD_ALLOCATED &&
            counters.getCounterTypeId(id) == AeronCounters.CLUSTER_NODE_ROLE_TYPE_ID
}
