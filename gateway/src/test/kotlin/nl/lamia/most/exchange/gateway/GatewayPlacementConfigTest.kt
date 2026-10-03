package nl.lamia.most.exchange.gateway

import nl.lamia.most.exchange.reference.GatewayIdentity
import nl.lamia.most.exchange.reference.ParticipantRegistry
import nl.lamia.most.exchange.reference.SecuritySpec
import nl.lamia.most.exchange.reference.ShardSpec
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Design.md §7, "Gateway placement". `independent` is what every gateway did before the key
 * existed and must be unchanged by it. `colocated` is IPC both ways on the node's own driver, under
 * the shared identity; anything else is a misconfiguration that would trade until the first
 * failover, so it stops the process instead.
 */
class GatewayPlacementConfigTest {

    private val shard = ShardSpec(
        shardId = 1,
        securities = listOf(
            SecuritySpec(
                securityId = 1, symbol = "ACME", isin = "US0378331005", name = "Acme", currency = "USD",
                priceFloor = 0L, tickSize = 1L, levelCount = 1024, maxOrders = 512,
            ),
        ),
    )

    private val registry = ParticipantRegistry(
        shardId = 1,
        gateways = listOf(GatewayIdentity("gw-node", ParticipantRegistry.sha256Hex("north"), listOf(100L))),
    )

    private val colocated = arrayOf(
        GatewayConfig.PLACEMENT to "colocated",
        "gateway.aeronDir" to "/dev/shm/node0",
        GatewayConfig.GATEWAY_ID to "gw-node",
        GatewayConfig.CREDENTIAL_TOKEN to "north",
    )

    private fun config(vararg entries: Pair<String, String>) = GatewayConfig.from(
        Properties().apply { entries.forEach { (k, v) -> setProperty(k, v) } },
        shard,
        registry,
    )

    @Test
    fun `no placement is independent, with the channels a gateway has always had`() {
        val config = config()
        assertEquals(GatewayPlacement.INDEPENDENT, config.placement)
        assertEquals("aeron:udp", config.ingressChannel)
        assertEquals("aeron:udp?endpoint=localhost:9020", config.egressChannel)
    }

    @Test
    fun `colocated reaches the leader over IPC both ways without being told`() {
        val config = config(*colocated)
        assertEquals(GatewayPlacement.COLOCATED, config.placement)
        assertEquals("aeron:ipc", config.ingressChannel)
        assertEquals("aeron:ipc", config.egressChannel)
        assertEquals(GatewayConfig.DEFAULT_LEADER_POLL_MS, config.leaderPollMs)
    }

    @Test
    fun `the placement is read case-insensitively and a wrong one refuses to start`() {
        assertEquals(GatewayPlacement.COLOCATED, config(*colocated, GatewayConfig.PLACEMENT to "Colocated").placement)
        val failure = assertFailsWith<IllegalArgumentException> { config(GatewayConfig.PLACEMENT to "sidecar") }
        assertContains(failure.message!!, "sidecar")
    }

    @Test
    fun `colocated without the node's media driver refuses to start`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            config(*colocated.filterNot { it.first == "gateway.aeronDir" }.toTypedArray())
        }
        assertContains(failure.message!!, "gateway.aeronDir")
    }

    @Test
    fun `colocated without the shared identity refuses to start`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            config(GatewayConfig.PLACEMENT to "colocated", "gateway.aeronDir" to "/dev/shm/node0")
        }
        assertContains(failure.message!!, GatewayConfig.GATEWAY_ID)
    }

    @Test
    fun `colocated with UDP egress refuses rather than quietly losing the reason to co-locate`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            config(*colocated, "gateway.egressChannel" to "aeron:udp?endpoint=localhost:0")
        }
        assertContains(failure.message!!, "aeron:ipc")
    }

    @Test
    fun `colocated with member endpoints refuses, since it only ever talks to its own node`() {
        assertFailsWith<IllegalArgumentException> {
            config(*colocated, "gateway.ingressEndpoints" to "0=localhost:20110")
        }
    }
}
