package com.engine.control

import org.springframework.mock.env.MockEnvironment
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The identity the control plane presents to a shard's cluster (Design.md §1): a node running with a
 * participant registry refuses a session with no credentials, so the control plane is named by an
 * operator-only registry entry. No Aeron needed to check how that identity is configured.
 */
class ClusterAdminIdentityTest {

    private fun secretFile(content: String) = File.createTempFile("control-secret", ".txt").apply {
        writeText(content)
        deleteOnExit()
    }

    @Test
    fun `no identity configured connects with no credentials`() {
        assertNull(ClusterAdmin.credentialsFor(MockEnvironment(), 0))
    }

    @Test
    fun `an identity and a secret file are read per shard, trailing newline and all`() {
        val environment = MockEnvironment()
            .withProperty("control.cluster.identity.0", "control")
            .withProperty("control.cluster.secretFile.0", secretFile("operator-secret\n").path)

        assertEquals("control" to "operator-secret", ClusterAdmin.credentialsFor(environment, 0))
        assertNull(ClusterAdmin.credentialsFor(environment, 1), "shard 1 was configured with nothing")
    }

    /** Half an identity would connect anonymously, which a node with a registry refuses. */
    @Test
    fun `an identity without a secret file is refused, naming both keys`() {
        val environment = MockEnvironment().withProperty("control.cluster.identity.0", "control")

        val failure = assertFailsWith<IllegalArgumentException> { ClusterAdmin.credentialsFor(environment, 0) }
        assertContains(failure.message!!, "control.cluster.secretFile.0")
    }

    @Test
    fun `a secret file that is missing or empty is refused`() {
        assertFailsWith<IllegalArgumentException> {
            ClusterAdmin.credentialsFor(
                MockEnvironment()
                    .withProperty("control.cluster.identity.0", "control")
                    .withProperty("control.cluster.secretFile.0", "/nonexistent/secret"),
                0,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ClusterAdmin.credentialsFor(
                MockEnvironment()
                    .withProperty("control.cluster.identity.0", "control")
                    .withProperty("control.cluster.secretFile.0", secretFile("").path),
                0,
            )
        }
    }
}

/**
 * Where the control plane's operator commands go. Only an `operator=true` gateway forwards them
 * (Design.md §1), and they are unacknowledged, so a dedicated operator endpoint must win over the
 * shard's advertised one whenever it is configured.
 */
class OperatorEndpointTest {

    private val shard = ShardRow(
        shardId = 0,
        orderEntryChannel = "aeron:udp?endpoint=shard0:20121",
        orderEntryStreamId = 20,
        executionReportChannel = "aeron:udp?endpoint=control:20122",
        executionReportStreamId = 21,
    )

    @Test
    fun `with nothing configured the shard's advertised endpoint is used`() {
        assertEquals(
            "aeron:udp?endpoint=shard0:20121" to 20,
            ClusterLink.operatorEndpointFor(MockEnvironment(), shard),
        )
    }

    @Test
    fun `a configured operator channel wins, keeping the shard's stream unless one is given`() {
        val channelOnly = MockEnvironment()
            .withProperty("control.cluster.operatorChannel.0", "aeron:udp?endpoint=shard0:20131")
        val both = MockEnvironment()
            .withProperty("control.cluster.operatorChannel.0", "aeron:udp?endpoint=shard0:20131")
            .withProperty("control.cluster.operatorStream.0", "30")

        assertEquals("aeron:udp?endpoint=shard0:20131" to 20, ClusterLink.operatorEndpointFor(channelOnly, shard))
        assertEquals("aeron:udp?endpoint=shard0:20131" to 30, ClusterLink.operatorEndpointFor(both, shard))
        assertEquals(
            "aeron:udp?endpoint=shard0:20121" to 20,
            ClusterLink.operatorEndpointFor(both, shard.copy(shardId = 1)),
            "configured for shard 0 only",
        )
    }

    @Test
    fun `a stream that is not a number is refused rather than defaulted`() {
        assertFailsWith<IllegalArgumentException> {
            ClusterLink.operatorEndpointFor(
                MockEnvironment().withProperty("control.cluster.operatorStream.0", "thirty"),
                shard,
            )
        }
    }
}
