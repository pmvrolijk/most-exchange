package com.engine.core

import org.agrona.concurrent.SystemNanoClock
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Design.md §7 step 2: prove zero steady-state allocation before `--gc=epsilon` is switched on.
 * Under Epsilon any steady allocation is eventually fatal, and because the engine is deterministic
 * it is fatal on every cluster node at the same log position — so this is the assertion that has to
 * exist before that flag does.
 *
 * The measurement is `ThreadMXBean.getCurrentThreadAllocatedBytes`, which counts bytes this thread
 * allocated. The engine is single-threaded by construction, so one thread's counter is the whole
 * hot path.
 *
 * **Everything in the driving loop must itself be allocation-free**, or the harness is what is
 * being measured. That is why the fakes in `AllocationSupport.kt` exist rather than [Harness]:
 * that one decodes each report into a `Report` data class, which would swamp the reading.
 *
 * **What this does not cover:** anything needing a live Aeron. `bookEventPub` is an
 * `ExclusivePublication`, which is `final` and so cannot be faked at all — it is null here and
 * `publishBookEvent` returns at its first line, and `onTakeSnapshot` cannot be called. Order entry,
 * validation, matching, booking, unlinking, the execution-report encode, the opening uncross and
 * the expiry purge are all covered here. The book-event and snapshot paths are covered by
 * [AeronAllocationTest], which pays for a real media driver to reach them.
 */
class AllocationTest {

    @Test
    fun `the allocation counter reads zero on a loop that cannot allocate`() {
        repeat(Alloc.WARMUP) { Alloc.threads.currentThreadAllocatedBytes }
        allocatedBytes { repeat(Alloc.WINDOW) { Alloc.threads.currentThreadAllocatedBytes } }

        val counterCost = allocatedBytes { repeat(Alloc.WINDOW) { Alloc.threads.currentThreadAllocatedBytes } }

        assertEquals(0L, counterCost, "reading the allocation counter must not itself allocate")
    }

    /**
     * The steady state that matters: every order fully matches against a resting order on the
     * other side, so the book returns to empty and the pool, the ladders and the id map are all
     * reused rather than grown. Two execution reports and a full match walk per pair.
     *
     * This is also the case that would catch the boxing CLAUDE.md warns about: `matchAggressive`
     * takes its fill callback as an `inline fun`, and that callback captures a mutable `remaining`.
     * Were it a functional interface instead, Kotlin would materialise a `Ref.LongRef` per order
     * and this test would report 16 bytes an order rather than none.
     */
    @Test
    fun `continuous matching allocates nothing per order`() {
        val driver = Driver()
        var clOrdId = 0L

        assertNoSteadyStateAllocation("continuous matching", opsPerRound = 2) {
            driver.newOrder(1L, clOrdId++, Side.BUY, Alloc.PRICE, 1L)
            driver.newOrder(2L, clOrdId++, Side.SELL, Alloc.PRICE, 1L)
        }
        assertEquals(0, driver.book.restingOrderCount(), "book must return to empty each pair")
        assertTrue(driver.session.claims > 0, "sanity: reports were actually encoded")
    }

    /**
     * The other steady state: orders rest and are cancelled, exercising `book`, `prepareCancel`
     * and `unlink` — the free-list and id-map paths that matching's full fills also take, but here
     * without a trade. The id map is the one to watch: `Long2LongHashMap` compacts its probe chain
     * on removal, and is presized so a steady book never rehashes.
     */
    @Test
    fun `book and cancel allocate nothing per order`() {
        val driver = Driver()
        var clOrdId = 0L
        var orderId = 0L

        assertNoSteadyStateAllocation("book and cancel", opsPerRound = 2) {
            val id = clOrdId++
            orderId++
            driver.newOrder(1L, id, Side.BUY, Alloc.PRICE, 1L)
            driver.cancel(1L, id, clOrdId++, orderId, Side.BUY)
        }
        assertEquals(0, driver.book.restingOrderCount())
    }

    /**
     * A rejected order takes a different path — no book mutation, one report — and is the case a
     * misconfigured client produces in volume, so it must not be the one that allocates.
     */
    @Test
    fun `rejected orders allocate nothing`() {
        val driver = Driver()
        var clOrdId = 0L
        // Below the ladder floor: rejected by the range backstop, before the book is touched.
        val badPrice = -1L

        assertNoSteadyStateAllocation("rejection", opsPerRound = 1) {
            driver.newOrder(1L, clOrdId++, Side.BUY, badPrice, 1L)
        }
        assertEquals(0, driver.book.restingOrderCount())
    }

    /**
     * A partial fill leaves the aggressor's remainder resting, and runs the fill callback ten
     * times for one inbound order — the shape that would multiply any per-fill allocation.
     */
    @Test
    fun `partial fills allocate nothing`() {
        val driver = Driver()
        var clOrdId = 0L

        assertNoSteadyStateAllocation("partial fills", opsPerRound = 11, rounds = Alloc.WINDOW / 10) {
            // Ten makers of 1, then one taker of 10: ten fills through one inline callback.
            repeat(10) { driver.newOrder(1L, clOrdId++, Side.SELL, Alloc.PRICE, 1L) }
            driver.newOrder(2L, clOrdId++, Side.BUY, Alloc.PRICE, 10L)
        }
        assertEquals(0, driver.book.restingOrderCount())
    }

    /**
     * The opening uncross, which is where allocation would be least visible and most expensive: it
     * is a fixed-point loop with three inline callbacks of its own, it runs over the whole crossed
     * book rather than one order, and it runs at the moment a market opens. `computeUncrossPrice`
     * takes a reusable `scratch` array precisely so this stays free; this is what checks it.
     */
    @Test
    fun `the opening auction allocates nothing`() {
        val driver = Driver()
        var clOrdId = 0L

        // A crossed book built in OPEN_AUCTION, then uncrossed by the move to CONTINUOUS. Walking
        // back through PRE_OPEN is what makes the round repeatable: the uncross runs only on the
        // OPEN_AUCTION -> CONTINUOUS edge (CLAUDE.md), so the phase has to be walked, not set.
        assertNoSteadyStateAllocation("the opening auction", opsPerRound = 21, rounds = 2_000) {
            driver.sessionTransition(Phase.PRE_OPEN)
            driver.sessionTransition(Phase.OPEN_AUCTION)
            repeat(10) { driver.newOrder(1L, clOrdId++, Side.BUY, Alloc.PRICE + 1, 1L) }
            repeat(10) { driver.newOrder(2L, clOrdId++, Side.SELL, Alloc.PRICE - 1, 1L) }
            driver.sessionTransition(Phase.CONTINUOUS)
        }
        assertEquals(0, driver.book.restingOrderCount(), "the uncross must clear a fully crossed book")
    }

    /**
     * The expiry purge walks the price ladders rather than the id map (CLAUDE.md), so it is a
     * different traversal from anything above, and it runs on every book before the market opens.
     */
    @Test
    fun `the expiry purge allocates nothing`() {
        val driver = Driver()
        var clOrdId = 0L
        driver.sessionTransition(Phase.PRE_OPEN)

        assertNoSteadyStateAllocation("the expiry purge", opsPerRound = 11, rounds = 5_000) {
            // Yesterday's orders, so the purge takes all ten.
            repeat(10) { driver.newOrderExpiring(1L, clOrdId++, Side.BUY, Alloc.PRICE, Alloc.TRADING_DATE - 1) }
            driver.purge(Alloc.TRADING_DATE)
        }
        assertEquals(0, driver.book.restingOrderCount(), "the purge must clear every expired order")
    }

    /**
     * Instrumentation that allocates would be worse than none: it would cost the zero-allocation
     * property on exactly the runs being measured, so every number it produced would describe a
     * process behaving differently from the one that ships.
     *
     * `LatencyHistogram` writes into a `long[]` sized at construction with auto-resize off, which
     * is the setting that would otherwise allocate on a large sample. This is what checks it.
     */
    @Test
    fun `metrics allocate nothing when enabled`() {
        val driver = Driver(metrics = EngineMetrics(SystemNanoClock.INSTANCE, stages = false))
        var clOrdId = 0L

        assertNoSteadyStateAllocation("matching with metrics on", opsPerRound = 2) {
            driver.newOrder(1L, clOrdId++, Side.BUY, Alloc.PRICE, 1L)
            driver.newOrder(2L, clOrdId++, Side.SELL, Alloc.PRICE, 1L)
        }
        assertEquals(0, driver.book.restingOrderCount())
    }

    /** The same, with the admit/match/settle partition on: four clock reads and three records. */
    @Test
    fun `stage metrics allocate nothing when enabled`() {
        val metrics = EngineMetrics(SystemNanoClock.INSTANCE, stages = true)
        val driver = Driver(metrics = metrics)
        var clOrdId = 0L

        assertNoSteadyStateAllocation("matching with stage metrics on", opsPerRound = 2) {
            driver.newOrder(1L, clOrdId++, Side.BUY, Alloc.PRICE, 1L)
            driver.newOrder(2L, clOrdId++, Side.SELL, Alloc.PRICE, 1L)
        }
        assertEquals(0, driver.book.restingOrderCount())
        assertTrue(metrics.admit.count > 0, "sanity: the stage histograms actually recorded")
        assertTrue(metrics.match.count > 0)
    }

    /**
     * Design.md §1: with a registry in force, every order and cancel from an authenticated session
     * is checked against what its gateway declares, and an undeclared one is counted. That check
     * is on the hot path, so it must allocate nothing -- participant 1 is declared, 2 is not, so
     * both branches run every round.
     */
    @Test
    fun `declared participant accounting allocates nothing`() {
        val registry = com.engine.reference.ParticipantRegistry(
            shardId = 1,
            gateways = listOf(
                com.engine.reference.GatewayIdentity(
                    "gw-alloc", com.engine.reference.ParticipantRegistry.sha256Hex("alloc"), listOf(1L),
                ),
            ),
        )
        val driver = Driver(participantRegistry = registry, principal = "gw-alloc")
        var clOrdId = 0L

        assertNoSteadyStateAllocation("matching with declarations checked", opsPerRound = 2) {
            driver.newOrder(1L, clOrdId++, Side.BUY, Alloc.PRICE, 1L)
            driver.newOrder(2L, clOrdId++, Side.SELL, Alloc.PRICE, 1L)
        }
        assertEquals(0, driver.book.restingOrderCount())
        assertTrue(driver.service.undeclaredParticipantMessages > 0, "sanity: the check actually ran")
        assertEquals(1L, driver.service.declaredBindings, "sanity: the session's declaration was bound")
    }
}
