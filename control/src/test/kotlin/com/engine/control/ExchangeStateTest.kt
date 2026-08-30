package com.engine.control

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure: what the feed said, with no Aeron and no database anywhere near it. */
class ExchangeStateTest {

    private val state = ExchangeState()

    @Test
    fun `a phase change is recorded per security`() {
        state.onSessionChanged(1, 0, "CONTINUOUS")
        state.onSessionChanged(2, 0, "PRE_OPEN")

        assertEquals("CONTINUOUS", state.securityState(1)?.phase)
        assertEquals("PRE_OPEN", state.securityState(2)?.phase)
        assertNotNull(state.securityState(1)?.phaseAt)
        assertNull(state.securityState(3))
    }

    @Test
    fun `a halt is kept, with what breached and by how much`() {
        state.onVolatilityHalted(
            securityId = 1, shardId = 0,
            collarReference = 10_000_000_000L,
            attemptedPrice = 11_000_000_000L,
            breachedBound = 10_200_000_000L,
            aggressorSide = "BUY",
        )
        val halt = assertNotNull(state.securityState(1)?.halt)
        assertEquals(10_000_000_000L, halt.collarReference)
        assertEquals(11_000_000_000L, halt.attemptedPrice)
        assertEquals(10_200_000_000L, halt.breachedBound)
        assertEquals("BUY", halt.aggressorSide)
        assertNull(halt.clearedAt)
    }

    @Test
    fun `a halt survives the CLOSED that accompanies it and clears when the security reopens`() {
        // The engine emits VolatilityHalted and SessionChanged(CLOSED) together, so the CLOSED that
        // arrives next is part of the halt, not a recovery from it. Only a move away clears it --
        // otherwise the halt would erase itself the instant it was reported.
        state.onVolatilityHalted(1, 0, 1L, 2L, 3L, "SELL")
        state.onSessionChanged(1, 0, "CLOSED")
        assertNull(state.securityState(1)?.halt?.clearedAt, "CLOSED must not clear a halt")

        state.onSessionChanged(1, 0, "PRE_OPEN")
        assertNotNull(state.securityState(1)?.halt?.clearedAt)
        // Kept rather than dropped: an operator still wants to see what broke this morning.
        assertEquals("SELL", state.securityState(1)?.halt?.aggressorSide)
    }

    @Test
    fun `uncrosses and trades are recorded`() {
        state.onAuctionUncrossed(1, 0, price = 10_000_000_000L, qty = 500L)
        state.onTrade(1, 0, price = 10_100_000_000L, qty = 25L)

        val security = assertNotNull(state.securityState(1))
        assertEquals(10_000_000_000L, security.lastUncrossPrice)
        assertEquals(500L, security.lastUncrossQty)
        assertEquals(10_100_000_000L, security.lastTradePrice)
        assertEquals(25L, security.lastTradeQty)
    }

    @Test
    fun `missed events are counted as gaps, not as events seen`() {
        state.recordEvent(0)
        state.recordEvent(0)
        state.recordEvent(7)

        assertEquals(3, state.eventsSeen)
        assertEquals(1, state.feedGaps)
        assertEquals(7, state.eventsMissed)
    }

    @Test
    fun `securities are listed in id order`() {
        state.onSessionChanged(3, 0, "CLOSED")
        state.onSessionChanged(1, 0, "CLOSED")
        state.onSessionChanged(2, 0, "CLOSED")

        assertEquals(listOf(1, 2, 3), state.securities().map { it.securityId })
        assertTrue(state.securities().all { it.shardId == 0 })
    }
}
