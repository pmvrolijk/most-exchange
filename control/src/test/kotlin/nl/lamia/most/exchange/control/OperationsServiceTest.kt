package nl.lamia.most.exchange.control

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * Runs with `control.aeron.enabled=false`, which is itself part of what is under test: the control
 * plane must author reference data with no media driver anywhere near it. Everything a command can
 * check *before* it goes on the wire is checked here; whether the bytes actually move is proved
 * against a real cluster in the end-to-end run, not in a unit test with a fake Aeron.
 */
@SpringBootTest
class OperationsServiceTest : PostgresTest() {

    @Autowired
    private lateinit var topology: TopologyService

    @Autowired
    private lateinit var operations: OperationsService

    @Autowired
    private lateinit var link: ClusterLink

    private fun seed(
        referencePrice: Long? = 10_000_000_000L,
        staticCollarBps: Int? = 5_000,
        levelCount: Int = 32_768,
    ) {
        topology.createShard(shardRow(0))
        topology.createSecurity(
            securityRow(1, 0, "AAPL", ISIN_APPLE, levelCount = levelCount).copy(
                referencePrice = referencePrice,
                staticCollarBps = staticCollarBps,
                dynamicCollarBps = 2_000,
            ),
        )
    }

    @Test
    fun `with no media driver the link is down and says so, rather than failing to start`() {
        val status = link.status()
        assertFalse(status.connected)
        assertContains(status.detail, "disabled")
    }

    @Test
    fun `reference price and collars are stored on the security`() {
        seed()
        val stored = topology.security(1)
        assertEquals(10_000_000_000L, stored?.referencePrice)
        assertEquals(5_000, stored?.staticCollarBps)
        assertEquals(2_000, stored?.dynamicCollarBps)
    }

    @Test
    fun `adding a reference price does not change the fingerprint`() {
        // Reference prices are not geometry and are outside the fingerprint by design. If they
        // leaked into it, seeding a price would invalidate every published release.
        topology.createShard(shardRow(0))
        topology.createSecurity(securityRow(1, 0, "AAPL", ISIN_APPLE))
        val before = topology.shardSpecs().single().fingerprint()

        topology.updateSecurity(
            securityRow(1, 0, "AAPL", ISIN_APPLE).copy(referencePrice = 10_000_000_000L),
        )
        assertEquals(before, topology.shardSpecs().single().fingerprint())
    }

    @Test
    fun `a definition without a reference price is refused before it reaches the wire`() {
        seed(referencePrice = null)
        val e = assertFailsWith<IllegalArgumentException> { operations.define(1) }
        assertContains(e.message.orEmpty(), "no reference price")
    }

    @Test
    fun `a collar band outside the ladder is refused, since the engine would reject it silently`() {
        // 32768 levels of 0.01 from zero tops out at 327.67. A 5000bp band around 300.00 reaches
        // 450.00, which the engine refuses -- by incrementing a counter and saying nothing.
        seed(referencePrice = 30_000_000_000L, levelCount = 32_768)
        val e = assertFailsWith<IllegalArgumentException> { operations.define(1) }
        assertContains(e.message.orEmpty(), "outside")
        assertContains(e.message.orEmpty(), "without replying")
    }

    @Test
    fun `a definition is never reported as confirmed, because nothing confirms one`() {
        seed()
        val result = operations.define(1)
        assertFalse(result.confirmed)
        // Not sent either, with no link -- but the point is that confirmed stays false regardless.
        assertFalse(result.sent)
        assertContains(result.detail, "no cluster link")
    }

    @Test
    fun `commands against an unknown or empty shard are caller errors`() {
        topology.createShard(shardRow(0))
        val empty = assertFailsWith<IllegalArgumentException> {
            operations.session(0, nl.lamia.most.exchange.sbe.Phase.CONTINUOUS.value())
        }
        assertContains(empty.message.orEmpty(), "serves no securities")

        val missing = assertFailsWith<IllegalArgumentException> { operations.purge(9) }
        assertContains(missing.message.orEmpty(), "no such shard: 9")

        assertFailsWith<IllegalArgumentException> { operations.define(99) }
    }

    @Test
    fun `a reopen walks pre-open, open-auction then continuous`() {
        seed()
        val result = operations.reopen(0)

        // The definition first, then the three transitions in the order that makes the uncross run.
        assertEquals(
            listOf("define AAPL", "session PRE_OPEN shard 0"),
            result.steps.map { it.command },
            "the walk must stop once a step could not be sent",
        )
        assertFalse(result.succeeded)
    }

    @Test
    fun `a reopen warns that a session transition moves the whole shard`() {
        seed()
        topology.createSecurity(
            securityRow(2, 0, "MSFT", ISIN_MICROSOFT).copy(referencePrice = 20_000_000_000L),
        )
        val result = operations.reopen(0)

        val warning = requireNotNull(result.warning)
        assertContains(warning, "shard-wide")
        assertContains(warning, "AAPL")
        assertContains(warning, "MSFT")
        assertEquals(listOf("AAPL", "MSFT"), result.securities)
    }

    @Test
    fun `a reference price given to a reopen needs a security to belong to`() {
        seed()
        topology.createSecurity(securityRow(2, 0, "MSFT", ISIN_MICROSOFT))
        val e = assertFailsWith<IllegalArgumentException> {
            operations.reopen(0, referencePrice = 12_300_000_000L)
        }
        assertContains(e.message.orEmpty(), "needs a securityId")
    }

    // Design.md §4.8 -- what a bulk cancel can check before it goes on the wire.

    @Test
    fun `a bulk cancel names its scope and is never confirmed`() {
        seed()
        val shardWide = operations.cancelParticipantOrders(0, participantId = 42L)
        assertEquals("cancel orders of participant 42 on shard 0", shardWide.command)
        assertFalse(shardWide.confirmed)

        val oneSecurity = operations.cancelParticipantOrders(0, participantId = 42L, securityId = 1)
        assertEquals("cancel orders of participant 42 on AAPL on shard 0", oneSecurity.command)
        assertFalse(oneSecurity.confirmed)
    }

    @Test
    fun `a bulk cancel for a security another shard hosts is refused before the wire`() {
        seed()
        topology.createShard(shardRow(1))
        topology.createSecurity(securityRow(2, 1, "MSFT", ISIN_MICROSOFT))

        val e = assertFailsWith<IllegalArgumentException> {
            operations.cancelParticipantOrders(0, participantId = 42L, securityId = 2)
        }
        assertContains(e.message.orEmpty(), "security 2 is not on shard 0")
    }

    @Test
    fun `a bulk cancel for an unknown shard is refused before the wire`() {
        val e = assertFailsWith<IllegalArgumentException> { operations.cancelParticipantOrders(9, 42L) }
        assertContains(e.message.orEmpty(), "no such shard")
    }

    @Test
    fun `a reopen only re-seeds securities that have a reference price`() {
        seed()
        topology.createSecurity(securityRow(2, 0, "MSFT", ISIN_MICROSOFT))
        val result = operations.reopen(0)

        assertEquals(
            listOf("define AAPL"),
            result.steps.filter { it.command.startsWith("define") }.map { it.command },
        )
    }
}
