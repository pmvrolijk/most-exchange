package nl.lamia.most.exchange.tools

import nl.lamia.most.exchange.client.RoutedSecurity
import nl.lamia.most.exchange.client.ShardRoute
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

    // ------------------------------------------------- several gateways, for co-located failover

    private fun overrides(vararg argv: String) = GatewayOverride.listFrom(Args(arrayOf(*argv)))

    @Test
    fun `one gateway, or none named, is a list of one exactly as before`() {
        assertEquals(listOf(override()), overrides())
        assertEquals(
            listOf(override("--order-entry-channel", "aeron:ipc")),
            overrides("--order-entry-channel", "aeron:ipc"),
        )
    }

    @Test
    fun `a gateway per node, paired with its own report channel by position`() {
        val routes = overrides(
            "--order-entry-channel", "aeron:udp?endpoint=node0:20001,aeron:udp?endpoint=node1:20001",
            "--report-channel", "aeron:udp?control=node0:20002|control-mode=dynamic, aeron:udp?control=node1:20002|control-mode=dynamic",
            "--order-entry-stream", "20",
        ).map { it.applyTo(security) }

        assertEquals(2, routes.size)
        assertEquals("aeron:udp?endpoint=node1:20001", routes[1].orderEntryChannel)
        assertEquals("aeron:udp?control=node1:20002|control-mode=dynamic", routes[1].executionReportChannel)
        assertEquals(20, routes[1].orderEntryStreamId)
    }

    @Test
    fun `one report channel is shared by every gateway`() {
        val routes = overrides("--order-entry-channel", "aeron:ipc,aeron:ipc", "--report-channel", "aeron:ipc")
        assertEquals(listOf("aeron:ipc", "aeron:ipc"), routes.map { it.reportChannel })
    }

    @Test
    fun `lists that cannot be paired are refused`() {
        assertFailsWith<IllegalArgumentException> {
            overrides("--order-entry-channel", "a,b,c", "--report-channel", "x,y")
        }
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
