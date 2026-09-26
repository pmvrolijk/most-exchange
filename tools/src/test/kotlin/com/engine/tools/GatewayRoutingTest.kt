package com.engine.tools

import com.engine.reference.RoutedSecurity
import com.engine.reference.ShardRoute
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Addressing a gateway the directory does not advertise, and naming the CLI to the cluster.
 *
 * Design.md §1: participants split across gateways learn their gateway's endpoint out of band, and
 * operator commands pass only through a gateway whose registry entry has `operator=true` -- so the
 * one endpoint the directory advertises per shard is not always the one to use. The directory stays
 * the source of everything else about a security.
 */
class GatewayRoutingTest {

    private val security = RoutedSecurity(
        securityId = 1, shardId = 0, symbol = "AAPL", isin = "US0378331005", name = "Apple Inc.",
        currency = "USD", priceFloor = 0L, tickSize = 1L, levelCount = 1024,
        orderEntryChannel = "aeron:udp?endpoint=gw-0:20121", orderEntryStreamId = 20,
        executionReportChannel = "aeron:udp?endpoint=client:20122", executionReportStreamId = 21,
    )
    private val shard = ShardRoute(
        shardId = 0,
        orderEntryChannel = "aeron:udp?endpoint=gw-0:20121", orderEntryStreamId = 20,
        executionReportChannel = "aeron:udp?endpoint=client:20122", executionReportStreamId = 21,
    )

    private fun override(vararg argv: String) = GatewayOverride.from(Args(arrayOf(*argv)))

    @Test
    fun `with no override the directory's route is used unchanged`() {
        assertEquals(security, override().applyTo(security))
        assertEquals(shard, override().applyTo(shard))
    }

    @Test
    fun `each option replaces only its own part of the route`() {
        val routed = override(
            "--order-entry-channel", "aeron:udp?endpoint=gw-ops:20131",
            "--report-stream", "31",
        ).applyTo(security)

        assertEquals("aeron:udp?endpoint=gw-ops:20131", routed.orderEntryChannel)
        assertEquals(20, routed.orderEntryStreamId)
        assertEquals("aeron:udp?endpoint=client:20122", routed.executionReportChannel)
        assertEquals(31, routed.executionReportStreamId)
        assertEquals(security.securityId, routed.securityId)
        assertEquals(security.tickSize, routed.tickSize)
    }

    @Test
    fun `an operator command can be pointed at an operator gateway`() {
        val routed = override(
            "--order-entry-channel", "aeron:ipc", "--order-entry-stream", "40",
        ).applyTo(shard)

        assertEquals("aeron:ipc", routed.orderEntryChannel)
        assertEquals(40, routed.orderEntryStreamId)
    }

    @Test
    fun `a stream that is not a number is refused rather than defaulted`() {
        val failure = assertFailsWith<IllegalArgumentException> { override("--order-entry-stream", "twenty") }
        assertContains(failure.message!!, "--order-entry-stream")
    }

    // --------------------------------------------------------------------------- cluster identity

    @Test
    fun `no identity connects with no credentials`() {
        assertNull(clusterCredentials(Args(emptyArray())))
    }

    @Test
    fun `an identity and a secret file produce credentials, trailing newline and all`() {
        val secret = File.createTempFile("most-secret", ".txt").apply {
            writeText("s3cret\n")
            deleteOnExit()
        }

        assertEquals(
            "control" to "s3cret",
            clusterCredentials(Args(arrayOf("--identity", "control", "--secret-file", secret.path))),
        )
    }

    /** Half an identity would connect anonymously, which a registry-configured node refuses. */
    @Test
    fun `an identity without a secret file, or the reverse, is refused`() {
        assertFailsWith<IllegalArgumentException> { clusterCredentials(Args(arrayOf("--identity", "control"))) }
        assertFailsWith<IllegalArgumentException> {
            clusterCredentials(Args(arrayOf("--secret-file", "/nonexistent")))
        }
    }

    @Test
    fun `a secret file that is missing or empty is refused`() {
        val empty = File.createTempFile("most-secret", ".txt").apply { deleteOnExit() }

        assertFailsWith<IllegalArgumentException> {
            clusterCredentials(Args(arrayOf("--identity", "control", "--secret-file", "/nonexistent")))
        }
        assertFailsWith<IllegalArgumentException> {
            clusterCredentials(Args(arrayOf("--identity", "control", "--secret-file", empty.path)))
        }
    }
}
