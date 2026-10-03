package nl.lamia.most.exchange.core

import nl.lamia.most.exchange.sbe.ConfigurationAnnouncementDecoder
import nl.lamia.most.exchange.sbe.MessageHeaderDecoder
import nl.lamia.most.exchange.sbe.MessageHeaderEncoder
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Design.md §7, "Enforced through the log". Every expectation below is taken from that clause:
 *
 *  - at the start of every leadership term each node offers a `ConfigurationAnnouncement` service
 *    message carrying its `shardId`, its shard fingerprint and its engine fingerprint;
 *  - every node compares the announcement it reads with its own: all three agree, nothing happens;
 *    any one differs, the node refuses and stops applying the log;
 *  - only a service message is honoured; one on a client session is ignored.
 *
 * Two harnesses stand in for two nodes. What one offers at a term start is delivered to the other
 * as the leader's copy, which is the only copy Aeron appends.
 */
class ConfigurationAnnouncementTest {

    private fun node(
        shardFingerprint: Long = Harness.FINGERPRINT,
        engineFingerprint: Long = Harness.ENGINE_FINGERPRINT,
    ): Harness {
        val h = Harness(arrayOf(serviceBook(1)), shardFingerprint = shardFingerprint, engineFingerprint = engineFingerprint)
        h.defineSecurity(h.books[0], 100L)
        h.sessionTransition(Phase.CONTINUOUS)
        return h
    }

    /** Delivers everything [leader] offered at its term start to [follower], as the log would. */
    private fun replicate(leader: Harness, offered: List<Int>, follower: Harness) {
        val buffer = leader.cluster.serviceMessageBuffer
        for (offset in offered) {
            follower.deliverServiceMessage(
                buffer, offset, MessageHeaderEncoder.ENCODED_LENGTH + ConfigurationAnnouncementDecoder.BLOCK_LENGTH,
            )
        }
    }

    @Test
    fun `a term start offers one announcement carrying this node's shard id and both fingerprints`() {
        val h = node()

        val offered = h.startTerm()

        assertEquals(1, offered.size)
        // Read at the offset after the reserved session header, which is where Aeron's log starts
        // the payload. An encoder that wrote at the claim offset would put garbage here.
        val buffer = h.cluster.serviceMessageBuffer
        val header = MessageHeaderDecoder().wrap(buffer, offered.single())
        assertEquals(ConfigurationAnnouncementDecoder.TEMPLATE_ID, header.templateId())
        val decoder = ConfigurationAnnouncementDecoder().wrap(
            buffer, offered.single() + MessageHeaderDecoder.ENCODED_LENGTH, header.blockLength(), header.version(),
        )
        assertEquals(Harness.SHARD_ID, decoder.shardId())
        assertEquals(Harness.FINGERPRINT, decoder.shardFingerprint())
        assertEquals(Harness.ENGINE_FINGERPRINT, decoder.engineFingerprint())
    }

    @Test
    fun `a node that agrees with the leader carries on`() {
        val leader = node()
        val follower = node()

        replicate(leader, leader.startTerm(), follower)

        assertFalse(follower.service.refused)
        assertEquals(1L, follower.service.configurationAnnouncementsAgreed)
        follower.newOrder(participantId = 1, clOrdId = 100, securityId = 1, side = Side.BUY, price = 99, qty = 10)
        assertEquals("NEW", follower.reports.single().execType)
    }

    @Test
    fun `the leader agrees with its own announcement`() {
        val leader = node()

        replicate(leader, leader.startTerm(), leader)

        assertFalse(leader.service.refused)
        assertEquals(1L, leader.service.configurationAnnouncementsAgreed)
    }

    @Test
    fun `a node booted with different geometry refuses the leader's announcement`() {
        val leader = node()
        val follower = node(shardFingerprint = Harness.FINGERPRINT + 1)

        val failure = assertFailsWith<ConfigurationMismatch> { replicate(leader, leader.startTerm(), follower) }

        assertTrue(follower.service.refused)
        // Both sets of values, so the operator can see which side is which without diffing files.
        assertContains(failure.message!!, java.lang.Long.toHexString(Harness.FINGERPRINT))
        assertContains(failure.message!!, java.lang.Long.toHexString(Harness.FINGERPRINT + 1))
    }

    @Test
    fun `a node booted with a different auction pass limit refuses the leader's announcement`() {
        val leader = node()
        val follower = node(engineFingerprint = Harness.ENGINE_FINGERPRINT + 1)

        assertFailsWith<ConfigurationMismatch> { replicate(leader, leader.startTerm(), follower) }
        assertTrue(follower.service.refused)
    }

    @Test
    fun `an announcement for another shard is refused`() {
        val h = node()

        assertFailsWith<ConfigurationMismatch> {
            h.announce(Harness.FINGERPRINT, Harness.ENGINE_FINGERPRINT, shardId = Harness.SHARD_ID + 1)
        }
        assertTrue(h.service.refused)
    }

    @Test
    fun `a node that has refused applies nothing more of the log`() {
        val h = node()
        assertFailsWith<ConfigurationMismatch> { h.announce(Harness.FINGERPRINT + 1, Harness.ENGINE_FINGERPRINT) }

        // Aeron's image swallows the throw and goes on delivering; the node must not go on applying.
        h.newOrder(participantId = 1, clOrdId = 100, securityId = 1, side = Side.BUY, price = 99, qty = 10)
        h.sessionTransition(Phase.CLOSED)

        assertTrue(h.reports.isEmpty())
        assertEquals(0, h.books[0].restingOrderCount())
        assertEquals(Phase.CONTINUOUS, h.books[0].phase)
        assertEquals(1L, h.service.nextExchangeOrderId)
    }

    @Test
    fun `a refused node announces nothing at the next term`() {
        val h = node()
        assertFailsWith<ConfigurationMismatch> { h.announce(Harness.FINGERPRINT + 1, Harness.ENGINE_FINGERPRINT) }

        assertTrue(h.startTerm().isEmpty())
    }

    @Test
    fun `an announcement on a client session is ignored, so a client cannot stop a node`() {
        val h = node()

        h.announce(Harness.FINGERPRINT + 1, Harness.ENGINE_FINGERPRINT + 1, onClientSession = h.session)

        assertFalse(h.service.refused)
        assertEquals(0L, h.service.configurationAnnouncementsAgreed)
        h.newOrder(participantId = 1, clOrdId = 100, securityId = 1, side = Side.BUY, price = 99, qty = 10)
        assertEquals("NEW", h.reports.single().execType)
    }

    @Test
    fun `a client message delivered without a session is not applied`() {
        // Only a service message arrives with no session. Anything else in that position is not an
        // order from anyone, so it must not be booked as one.
        val h = node()
        val buffer = org.agrona.concurrent.UnsafeBuffer(ByteArray(256))
        nl.lamia.most.exchange.sbe.NewOrderSingleEncoder().wrapAndApplyHeader(buffer, 0, MessageHeaderEncoder())
            .participantId(1).clOrdId(100).price(99).qty(10).securityId(1)
            .side(nl.lamia.most.exchange.sbe.Side.BUY)

        h.deliverServiceMessage(
            buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + nl.lamia.most.exchange.sbe.NewOrderSingleEncoder.BLOCK_LENGTH,
        )

        assertEquals(0, h.books[0].restingOrderCount())
        assertFalse(h.service.refused)
    }
}
