package com.engine.gateway

import io.aeron.AeronCounters
import io.aeron.cluster.service.Cluster
import org.agrona.concurrent.UnsafeBuffer
import java.nio.ByteBuffer
import org.agrona.concurrent.status.CountersManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A co-located gateway's only signal that it may act. The counter is laid out as the consensus
 * module allocates it (`ClusterCounters.allocate`: type 201, the cluster id as a four-byte key), in
 * a real Agrona counter set, so a change to how the gateway finds it is tested against the
 * format and not against a stub of it.
 */
class LeaderWatchTest {

    private val counters = CountersManager(
        UnsafeBuffer(ByteBuffer.allocateDirect(64 * 1024)),
        UnsafeBuffer(ByteBuffer.allocateDirect(16 * 1024)),
    )

    private fun allocateRole(clusterId: Int, typeId: Int = AeronCounters.CLUSTER_NODE_ROLE_TYPE_ID): Int {
        val key = UnsafeBuffer(ByteArray(4)).apply { putInt(0, clusterId) }
        val label = UnsafeBuffer("Cluster node role - clusterId=$clusterId".toByteArray())
        return counters.allocate(typeId, key, 0, 4, label, 0, label.capacity())
    }

    @Test
    fun `no consensus module on the driver is no role, and not the leader`() {
        val watch = LeaderWatch(counters)
        assertNull(watch.role())
        assertFalse(watch.isLeader())
    }

    @Test
    fun `follows the node's role as the consensus module changes it`() {
        val id = allocateRole(clusterId = 0)
        val watch = LeaderWatch(counters)

        assertEquals(Cluster.Role.FOLLOWER, watch.role())
        counters.setCounterValue(id, Cluster.Role.CANDIDATE.code().toLong())
        assertFalse(watch.isLeader())
        counters.setCounterValue(id, Cluster.Role.LEADER.code().toLong())
        assertTrue(watch.isLeader())
        counters.setCounterValue(id, Cluster.Role.FOLLOWER.code().toLong())
        assertFalse(watch.isLeader(), "a node that lost the election must stand the gateway down")
    }

    @Test
    fun `reads only its own cluster's role`() {
        val other = allocateRole(clusterId = 1)
        counters.setCounterValue(other, Cluster.Role.LEADER.code().toLong())
        val watch = LeaderWatch(counters, clusterId = 0)

        assertFalse(watch.isLeader(), "another cluster on the same driver leads, not this one")
    }

    @Test
    fun `a freed role counter whose slot is reused is not read as the role`() {
        val first = allocateRole(clusterId = 0)
        counters.setCounterValue(first, Cluster.Role.LEADER.code().toLong())
        val watch = LeaderWatch(counters)
        assertTrue(watch.isLeader())

        // The consensus module goes away and something else takes the slot.
        counters.free(first)
        val reused = counters.allocate("not a role")
        counters.setCounterValue(reused, Cluster.Role.LEADER.code().toLong())

        assertFalse(watch.isLeader())
    }
}
