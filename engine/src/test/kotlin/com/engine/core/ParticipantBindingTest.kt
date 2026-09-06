package com.engine.core

import com.engine.reference.GatewayIdentity
import com.engine.reference.ParticipantRegistry
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
}
