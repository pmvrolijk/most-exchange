package nl.lamia.most.exchange.core

import nl.lamia.most.exchange.reference.GatewayIdentity
import nl.lamia.most.exchange.reference.ParticipantRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Routing an execution report to a participant that has not spoken.
 *
 * A cluster session belongs to a gateway, not to an end participant, so the maker side of a fill
 * needs a route back that its own inbound message cannot supply. Learned from traffic, that route
 * dies with the session it was learned on: a gateway restart leaves every one of its quiet
 * participants unreachable, their fills counted undeliverable and dropped, and -- because the
 * gateway cannot journal a fill it is never told about -- the `cumQty` it reports for those orders
 * silently stops advancing. Nothing downstream can detect that.
 *
 * Declared from the authenticated principal at session open, the route comes back the moment the
 * gateway reconnects. Every test here is that difference.
 */
class ParticipantBindingTest {

    private companion object {
        const val MAKER = 100L
        const val TAKER = 200L
        const val MAKER_SESSION = 8L
        const val REPLACEMENT_SESSION = 9L
        const val FAILOVER_SESSION = 10L
    }

    private val registry = ParticipantRegistry(
        shardId = 1,
        gateways = listOf(
            GatewayIdentity("gw-maker", ParticipantRegistry.sha256Hex("north"), listOf(MAKER)),
            GatewayIdentity("gw-taker", ParticipantRegistry.sha256Hex("south"), listOf(TAKER)),
        ),
    )

    private fun harness(withRegistry: Boolean) = Harness(
        books = arrayOf(serviceBook(1)),
        participantRegistry = if (withRegistry) registry else null,
        sessionPrincipal = if (withRegistry) "gw-taker" else null,
    ).also {
        it.defineSecurity(it.books[0], 100L, 0, 0)
        it.sessionTransition(Phase.CONTINUOUS)
    }

    /**
     * The whole issue in one test. The maker rests an order, its gateway restarts, and it says
     * nothing more; the taker then crosses it.
     */
    @Test
    fun `a maker whose gateway restarted is still sent its fill`() {
        val h = harness(withRegistry = true)
        val makerSession = h.openSession(MAKER_SESSION, principal = "gw-maker")
        h.on(makerSession).newOrder(MAKER, 100, 1, Side.SELL, 100, 10)

        h.closeSession(makerSession)
        val replacement = h.openSession(REPLACEMENT_SESSION, principal = "gw-maker")

        h.newOrder(TAKER, 200, 1, Side.BUY, 100, 10)

        val fill = replacement.reports.single { it.execType == "TRADE" }
        assertEquals(MAKER, fill.participantId)
        assertEquals(10L, fill.lastQty)
        assertEquals(0L, h.service.undeliverableReports)
    }

    /**
     * The same run with no registry, which is what shipped before this existed. It is here so the
     * test above is known to be measuring the binding rather than the weather: delete the binding
     * and this is the behaviour that comes back.
     */
    @Test
    fun `without a registry that same fill is dropped`() {
        val h = harness(withRegistry = false)
        val makerSession = h.openSession(MAKER_SESSION)
        h.on(makerSession).newOrder(MAKER, 100, 1, Side.SELL, 100, 10)

        h.closeSession(makerSession)
        val replacement = h.openSession(REPLACEMENT_SESSION)

        h.newOrder(TAKER, 200, 1, Side.BUY, 100, 10)

        assertTrue(replacement.reports.isEmpty())
        assertEquals(1L, h.service.undeliverableReports)
    }

    /**
     * A restart replays no session opens: Aeron's `ServiceSnapshotLoader` adds the surviving
     * sessions straight to the container's map. So the rebuild `onStart` runs over
     * `clientSessions()` is the only thing standing between a restored engine and a shard whose
     * every quiet participant is unreachable until it happens to send something.
     */
    @Test
    fun `a service that restarted rebinds the sessions that outlived it`() {
        val h = harness(withRegistry = true)
        val makerSession = h.openSession(MAKER_SESSION, principal = "gw-maker")
        h.on(makerSession).newOrder(MAKER, 100, 1, Side.SELL, 100, 10)

        // What a restore leaves behind: the sessions are there, nothing bound them.
        val restarted = Harness(
            books = h.books,
            participantRegistry = registry,
            sessionPrincipal = "gw-taker",
        )
        val survivor = restarted.openSessionWithoutBinding(MAKER_SESSION, principal = "gw-maker")
        restarted.rebindFromExistingSessions()

        restarted.defineSecurity(restarted.books[0], 100L, 0, 0)
        restarted.sessionTransition(Phase.CONTINUOUS)
        restarted.newOrder(TAKER, 200, 1, Side.BUY, 100, 10)

        assertEquals(MAKER, survivor.reports.single { it.execType == "TRADE" }.participantId)
        assertEquals(0L, restarted.service.undeliverableReports)
    }

    /**
     * Traffic still wins, and that is not a leftover. The gateway that forwarded an order is the
     * one holding its `origQty`, and so the only one that can restore `cumQty` on the way back --
     * a report has to follow the order, not the registry.
     */
    @Test
    fun `an order sent through another gateway reports back to that gateway`() {
        val h = harness(withRegistry = true)
        val makerSession = h.openSession(MAKER_SESSION, principal = "gw-maker")

        // The maker's order comes in on the taker's session, against what the registry declares.
        h.newOrder(MAKER, 100, 1, Side.SELL, 100, 10)
        h.newOrder(TAKER, 200, 1, Side.BUY, 100, 10)

        assertTrue(makerSession.reports.isEmpty())
        assertEquals(2, h.reports.count { it.execType == "TRADE" })
    }

    /**
     * Closing a session must not take a route that has since moved to a live one, or a gateway
     * shutting down would strand a participant that had already migrated away from it.
     */
    @Test
    fun `closing a session leaves a route that traffic has since moved`() {
        val h = harness(withRegistry = true)
        val makerSession = h.openSession(MAKER_SESSION, principal = "gw-maker")
        h.newOrder(MAKER, 100, 1, Side.SELL, 100, 10) // on the taker's session: route moves there
        h.closeSession(makerSession)

        h.newOrder(TAKER, 200, 1, Side.BUY, 100, 10)

        assertEquals(2, h.reports.count { it.execType == "TRADE" })
        assertEquals(0L, h.service.undeliverableReports)
    }

    @Test
    fun `closing a session drops the routes it still owns`() {
        val h = harness(withRegistry = true)
        val makerSession = h.openSession(MAKER_SESSION, principal = "gw-maker")
        h.on(makerSession).newOrder(MAKER, 100, 1, Side.SELL, 100, 10)
        h.closeSession(makerSession)

        h.newOrder(TAKER, 200, 1, Side.BUY, 100, 10)

        assertEquals(1L, h.service.undeliverableReports)
    }

    /**
     * A principal only exists because the consensus module verified it against this same file, so
     * one that resolves to nothing here means the two copies disagree. Counted, not refused:
     * rejecting is the module's job and it already declined to.
     */
    @Test
    fun `a session carrying an unknown principal is counted`() {
        val h = harness(withRegistry = true)
        h.openSession(MAKER_SESSION, principal = "gw-from-another-release")

        assertEquals(1L, h.service.unknownPrincipals)
    }

    @Test
    fun `an anonymous session binds nothing and is not counted as unknown`() {
        val h = harness(withRegistry = true)
        h.openSession(MAKER_SESSION)

        assertEquals(0L, h.service.unknownPrincipals)
        assertEquals(1L, h.service.declaredBindings) // the harness's own gw-taker session only
    }

    // ------------------------------------------------------------------ primary and failover
    // Design.md §1: a session binds each participant it lists for which its gateway is the
    // primary, or which has no live route yet; a closing session's routes are rebound to another
    // open session whose gateway lists the participant, the primary first.

    private val withFailover = ParticipantRegistry(
        shardId = 1,
        gateways = listOf(
            GatewayIdentity("gw-maker", ParticipantRegistry.sha256Hex("north"), listOf(MAKER)),
            GatewayIdentity("gw-failover", ParticipantRegistry.sha256Hex("east"), listOf(MAKER)),
            GatewayIdentity("gw-taker", ParticipantRegistry.sha256Hex("south"), listOf(TAKER)),
        ),
        primaries = mapOf(MAKER to "gw-maker"),
    )

    private fun failoverHarness() = Harness(
        books = arrayOf(serviceBook(1)),
        participantRegistry = withFailover,
        sessionPrincipal = "gw-taker",
    ).also {
        it.defineSecurity(it.books[0], 100L, 0, 0)
        it.sessionTransition(Phase.CONTINUOUS)
    }

    @Test
    fun `a failover gateway connecting beside a live primary takes nothing from it`() {
        val h = failoverHarness()
        val primary = h.openSession(MAKER_SESSION, principal = "gw-maker")
        h.on(primary).newOrder(MAKER, 100, 1, Side.SELL, 100, 10)
        val failover = h.openSession(FAILOVER_SESSION, principal = "gw-failover")

        h.newOrder(TAKER, 200, 1, Side.BUY, 100, 10)

        assertEquals(MAKER, primary.reports.single { it.execType == "TRADE" }.participantId)
        assertTrue(failover.reports.isEmpty())
    }

    @Test
    fun `a failover gateway binds a participant that has no live route`() {
        val h = failoverHarness()
        val primary = h.openSession(MAKER_SESSION, principal = "gw-maker")
        h.on(primary).newOrder(MAKER, 100, 1, Side.SELL, 100, 10)
        h.closeSession(primary)
        val failover = h.openSession(FAILOVER_SESSION, principal = "gw-failover")

        h.newOrder(TAKER, 200, 1, Side.BUY, 100, 10)

        assertEquals(MAKER, failover.reports.single { it.execType == "TRADE" }.participantId)
        assertEquals(0L, h.service.undeliverableReports)
    }

    /** The case failover exists for: the primary goes away while the failover is already up. */
    @Test
    fun `a closing primary's routes move to a failover that is already connected`() {
        val h = failoverHarness()
        val primary = h.openSession(MAKER_SESSION, principal = "gw-maker")
        val failover = h.openSession(FAILOVER_SESSION, principal = "gw-failover")
        h.on(primary).newOrder(MAKER, 100, 1, Side.SELL, 100, 10)

        h.closeSession(primary)
        h.newOrder(TAKER, 200, 1, Side.BUY, 100, 10)

        assertEquals(MAKER, failover.reports.single { it.execType == "TRADE" }.participantId)
        assertEquals(0L, h.service.undeliverableReports)
    }

    @Test
    fun `a primary that reconnects takes its participants back`() {
        val h = failoverHarness()
        val primary = h.openSession(MAKER_SESSION, principal = "gw-maker")
        val failover = h.openSession(FAILOVER_SESSION, principal = "gw-failover")
        h.on(primary).newOrder(MAKER, 100, 1, Side.SELL, 100, 10)
        h.closeSession(primary)
        val restarted = h.openSession(REPLACEMENT_SESSION, principal = "gw-maker")

        h.newOrder(TAKER, 200, 1, Side.BUY, 100, 10)

        assertEquals(MAKER, restarted.reports.single { it.execType == "TRADE" }.participantId)
        assertTrue(failover.reports.none { it.execType == "TRADE" })
    }

    /** Design.md §1: a cancel-only participant's resting orders still fill, so it is still bound. */
    @Test
    fun `a cancel only participant is still sent its fills`() {
        val revoked = ParticipantRegistry(
            shardId = 1,
            gateways = listOf(
                GatewayIdentity(
                    "gw-maker", ParticipantRegistry.sha256Hex("north"), emptyList(), cancelOnly = listOf(MAKER),
                ),
                GatewayIdentity("gw-taker", ParticipantRegistry.sha256Hex("south"), listOf(TAKER)),
            ),
        )
        val h = Harness(books = arrayOf(serviceBook(1)), participantRegistry = revoked, sessionPrincipal = "gw-taker")
            .also {
                it.defineSecurity(it.books[0], 100L, 0, 0)
                it.sessionTransition(Phase.CONTINUOUS)
            }
        val makerSession = h.openSession(MAKER_SESSION, principal = "gw-maker")
        h.on(makerSession).newOrder(MAKER, 100, 1, Side.SELL, 100, 10)
        h.closeSession(makerSession)
        val replacement = h.openSession(REPLACEMENT_SESSION, principal = "gw-maker")

        h.newOrder(TAKER, 200, 1, Side.BUY, 100, 10)

        assertEquals(MAKER, replacement.reports.single { it.execType == "TRADE" }.participantId)
    }

    // ------------------------------------------------------------- undeclared participant counter
    // Design.md §1: a message from an authenticated session for a participant its principal does
    // not list is counted, never rejected, never branched on.

    @Test
    fun `an order or cancel for a participant the sending gateway does not list is counted`() {
        val h = harness(withRegistry = true) // the harness's own session is gw-taker, listing TAKER

        h.newOrder(TAKER, 200, 1, Side.BUY, 90, 1)
        assertEquals(0L, h.service.undeclaredParticipantMessages)

        h.newOrder(MAKER, 100, 1, Side.SELL, 110, 1)
        h.cancel(MAKER, 100, 101, exchangeOrderId = 2, securityId = 1, side = Side.SELL)
        assertEquals(2L, h.service.undeclaredParticipantMessages)
    }

    @Test
    fun `counted, not refused -- the undeclared order is accepted exactly as before`() {
        val h = harness(withRegistry = true)

        h.newOrder(MAKER, 100, 1, Side.SELL, 110, 1)

        assertEquals("NEW", h.reports.single().execType)
        assertEquals(1, h.books[0].restingOrderCount())
    }

    @Test
    fun `an anonymous session is not counted, since it declares nothing to disagree with`() {
        val h = harness(withRegistry = true)
        val anonymous = h.openSession(MAKER_SESSION)

        h.on(anonymous).newOrder(MAKER, 100, 1, Side.SELL, 110, 1)

        assertEquals(0L, h.service.undeclaredParticipantMessages)
    }

    /** The counter follows the registry in force, not the one the session opened under. */
    @Test
    fun `a registry reload that lists the participant stops the count`() {
        val h = harness(withRegistry = true)
        h.newOrder(MAKER, 100, 1, Side.SELL, 110, 1)
        assertEquals(1L, h.service.undeclaredParticipantMessages)

        h.participantRegistry = ParticipantRegistry(
            shardId = 1,
            gateways = listOf(
                GatewayIdentity("gw-maker", ParticipantRegistry.sha256Hex("north"), listOf(300L)),
                GatewayIdentity("gw-taker", ParticipantRegistry.sha256Hex("south"), listOf(TAKER, MAKER)),
            ),
        )
        h.newOrder(MAKER, 101, 1, Side.SELL, 111, 1)

        assertEquals(1L, h.service.undeclaredParticipantMessages)
    }

    /**
     * The falsifiable form of "never branched on" (compare MetricsDeterminismTest): two nodes whose
     * registries disagree about who declares what must end with identical books and sequences.
     */
    @Test
    fun `two nodes that disagree about declarations end with identical books`() {
        val declaresAll = ParticipantRegistry(
            shardId = 1,
            gateways = listOf(
                GatewayIdentity("gw-taker", ParticipantRegistry.sha256Hex("south"), listOf(TAKER, MAKER, 300L)),
            ),
        )
        val declaresNone = ParticipantRegistry(
            shardId = 1,
            gateways = listOf(GatewayIdentity("gw-taker", ParticipantRegistry.sha256Hex("south"), listOf(999L))),
        )
        fun run(registry: ParticipantRegistry) = Harness(
            books = arrayOf(serviceBook(1)), participantRegistry = registry, sessionPrincipal = "gw-taker",
        ).also { h ->
            h.defineSecurity(h.books[0], 100L, 0, 0)
            h.sessionTransition(Phase.CONTINUOUS)
            h.newOrder(MAKER, 100, 1, Side.SELL, 101, 10)
            h.newOrder(TAKER, 200, 1, Side.BUY, 101, 4)
            h.newOrder(300L, 300, 1, Side.BUY, 99, 5)
            h.cancel(300L, 300, 301, exchangeOrderId = 3, securityId = 1, side = Side.BUY)
        }

        val a = run(declaresAll)
        val b = run(declaresNone)

        assertEquals(0L, a.service.undeclaredParticipantMessages)
        assertEquals(4L, b.service.undeclaredParticipantMessages)
        assertEquals(a.reports, b.reports)
        assertEquals(a.service.nextExchangeOrderId, b.service.nextExchangeOrderId)
        assertEquals(a.service.nextBookEventSeqNum, b.service.nextBookEventSeqNum)
        assertEquals(a.books[0].restingOrderCount(), b.books[0].restingOrderCount())
        assertEquals(a.books[0].bestAsk(), b.books[0].bestAsk())
        assertEquals(a.books[0].dynamicReference, b.books[0].dynamicReference)
    }
}
