package com.engine.control

import com.engine.reference.ShardSpec
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every invariant asserted here is enforced by constructing a `reference` domain type, never by a
 * rule reimplemented in this module. The tests therefore double as proof that the crossing points
 * — [ShardRow.toRoute] and [SecurityRow.toSpec] — are actually on the write path.
 */
@SpringBootTest
class TopologyServiceTest : PostgresTest() {

    @Autowired
    private lateinit var topology: TopologyService

    @Test
    fun `a shard and its securities round trip`() {
        topology.createShard(shardRow(0))
        topology.createSecurity(securityRow(1, 0, "AAPL", ISIN_APPLE))
        topology.createSecurity(securityRow(2, 0, "MSFT", ISIN_MICROSOFT))

        assertEquals(listOf(0), topology.shards().map { it.shardId })
        assertEquals(listOf("AAPL", "MSFT"), topology.securities().map { it.symbol })
        // CHAR(12) pads on the way out of Postgres; the domain type rejects a padded ISIN, so the
        // trim has to happen in the row mapper rather than being noticed at publish time.
        assertEquals(ISIN_APPLE, topology.security(1)?.isin)
        assertEquals("USD", topology.security(1)?.currency)
    }

    @Test
    fun `a bad ISIN check digit is refused with the domain's own message`() {
        topology.createShard(shardRow(0))
        val e = assertFailsWith<IllegalArgumentException> {
            // Apple's ISIN with the check digit changed from 5 to 6.
            topology.createSecurity(securityRow(1, 0, isin = "US0378331006"))
        }
        assertContains(e.message.orEmpty(), "ISIN")
    }

    @Test
    fun `a symbol longer than the wire allows is refused`() {
        topology.createShard(shardRow(0))
        // Symbol is char[16] on the wire; a 17th character would be silently truncated in the
        // directory broadcast, which is exactly the failure the length limit exists to prevent.
        assertFailsWith<IllegalArgumentException> {
            topology.createSecurity(securityRow(1, 0, symbol = "A".repeat(17)))
        }
    }

    @Test
    fun `an eleventh security on a shard is refused at the point of the mistake`() {
        topology.createShard(shardRow(0))
        repeat(ShardSpec.MAX_SECURITIES_PER_SHARD) { i ->
            topology.createSecurity(
                securityRow(i + 1, 0, symbol = "SYM$i", isin = isinFor(i)),
            )
        }
        val e = assertFailsWith<IllegalArgumentException> {
            topology.createSecurity(securityRow(99, 0, symbol = "TOOMANY", isin = ISIN_BAE))
        }
        assertContains(e.message.orEmpty(), "maximum")
    }

    @Test
    fun `a security cannot be created on a shard that does not exist`() {
        val e = assertFailsWith<IllegalArgumentException> { topology.createSecurity(securityRow(1, 7)) }
        assertContains(e.message.orEmpty(), "no such shard: 7")
    }

    @Test
    fun `a reused symbol or ISIN is refused by the database`() {
        topology.createShard(shardRow(0))
        topology.createSecurity(securityRow(1, 0, "AAPL", ISIN_APPLE))

        assertFailsWith<DataIntegrityViolationException> {
            topology.createSecurity(securityRow(2, 0, "AAPL", ISIN_MICROSOFT))
        }
        assertFailsWith<DataIntegrityViolationException> {
            topology.createSecurity(securityRow(3, 0, "OTHER", ISIN_APPLE))
        }
    }

    @Test
    fun `one shard per security is structural, not checked`() {
        // securityId is the primary key and shardId is a column, so a security simply cannot name
        // two shards. Moving it is an update, and the old shard loses it in the same statement.
        topology.createShard(shardRow(0))
        topology.createShard(shardRow(1))
        topology.createSecurity(securityRow(1, 0))

        topology.updateSecurity(securityRow(1, 1))

        assertEquals(1, topology.securities().size)
        assertEquals(1, topology.security(1)?.shardId)
    }

    @Test
    fun `a shard still serving securities cannot be deleted`() {
        topology.createShard(shardRow(0))
        topology.createSecurity(securityRow(1, 0, "AAPL", ISIN_APPLE))

        val e = assertFailsWith<IllegalArgumentException> { topology.deleteShard(0) }
        assertContains(e.message.orEmpty(), "AAPL")

        topology.deleteSecurity(1)
        assertTrue(topology.deleteShard(0))
    }

    @Test
    fun `updating something that does not exist reports absence rather than creating it`() {
        topology.createShard(shardRow(0))
        assertNull(topology.updateShard(shardRow(9)))
        assertNull(topology.updateSecurity(securityRow(9, 0)))
        assertNull(topology.updateParticipant(ParticipantRow(9, "Nobody")))
    }

    @Test
    fun `the view reports every problem, not just the first`() {
        // A half-built topology is the normal state while someone is editing it, so the editor has
        // to be able to show all of it at once.
        topology.createShard(shardRow(0))
        topology.createShard(shardRow(1))

        val view = topology.view()
        assertEquals(2, view.problems.size, "${view.problems}")
        assertTrue(view.problems.all { it.contains("defines no securities") })
        assertNull(view.universeVersion)
        assertTrue(view.shards.all { it.fingerprint == null })
    }

    @Test
    fun `a complete topology has no problems and carries its fingerprints`() {
        topology.createShard(shardRow(0))
        topology.createSecurity(securityRow(1, 0, "AAPL", ISIN_APPLE))

        val view = topology.view()
        assertEquals(emptyList(), view.problems)
        assertEquals(
            topology.shardSpecs().single().fingerprint(),
            view.shards.single().fingerprint,
        )
        assertEquals(topology.universe().version, view.universeVersion)
    }

    @Test
    fun `participants are authored independently of the topology`() {
        topology.createParticipant(ParticipantRow(7, "Acme Capital", smpId = 0))
        topology.createParticipant(ParticipantRow(8, "Beta Trading", smpId = 8))

        assertEquals(listOf(7L, 8L), topology.participants().map { it.participantId })
        assertFailsWith<IllegalArgumentException> { ParticipantRow(0, "Invalid") }
        assertFailsWith<IllegalArgumentException> { ParticipantRow(1, " ") }
    }

    /**
     * Ten distinct real ISINs, so the ten-per-shard test fails on the cap rather than on a
     * duplicate ISIN and quietly stops testing what its name claims.
     */
    private fun isinFor(index: Int): String = REAL_ISINS[index]

    private companion object {
        val REAL_ISINS = listOf(
            ISIN_APPLE, ISIN_MICROSOFT, ISIN_BMW, ISIN_BAE,
            "US02079K3059", "US0231351067", "US30303M1027", "US88160R1014",
            "US67066G1040", "US4581401001",
        )
    }
}
