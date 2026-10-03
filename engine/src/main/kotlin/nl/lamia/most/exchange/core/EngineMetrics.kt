package nl.lamia.most.exchange.core

import nl.lamia.most.exchange.reference.LatencyHistogram
import nl.lamia.most.exchange.reference.measureClockOverheadNanos
import org.agrona.concurrent.NanoClock

/**
 * Hot-path timing for the matching engine (Design.md §2, §7).
 *
 * ## Why this is allowed to read the wall clock
 *
 * Design.md §1 bans `System.nanoTime()` in the engine, and this class calls it. That ban is not
 * about *observing* time, it is about time **influencing replicated state**: a decision taken from
 * a node-local clock is a decision two nodes can take differently, and the books diverge on the
 * next order.
 *
 * So the rule this class must obey, and the test for any probe added later:
 *
 * > **Enabling metrics on one node and not another must be incapable of changing the log, the
 * > books, or a snapshot.**
 *
 * That holds only while the histograms are *write-only* as far as the state machine is concerned.
 * Nothing here is ever read by a branch that decides anything, nothing here is snapshotted, and
 * nothing here reaches a feed a consumer acts on. It is printed at shutdown and that is all. Keep
 * it that way: the moment a metric is read back into a decision, this stops being instrumentation
 * and becomes a divergence.
 *
 * ## The two levels
 *
 * [MatchingEngineService] times whole messages whenever metrics are on — two clock reads per
 * message, which is the cost of knowing what share of a client's round trip the engine owns.
 *
 * [stages] adds a partition of a new order's time into [admit], [match] and [settle]: two more
 * clock reads, and only worth paying while diagnosing. **These are a partition, not Design.md §2's
 * cost table.** §2 estimates a 2 ns book lookup and a 50 ns ladder step; `nanoTime` itself costs
 * more than the first and a good fraction of the second, so timing at that grain would measure the
 * clock. The stages are drawn where a boundary is both meaningful and affordable:
 *
 * * [admit] — SBE decode, validation, the collar and capacity checks, id assignment, and the
 *   `NEW` execution report. Everything before the book is touched.
 * * [match] — `matchAggressive`, which carries the fills *and* the reports and book events each
 *   fill publishes. Publishing is not separable from matching without timing every `tryClaim`,
 *   which for a ten-fill order would be twenty clock reads and would distort what it measured.
 * * [settle] — booking any remainder and its `OrderAdded`, or the terminal report of a self-match
 *   or collar breach.
 *
 * The three sum to the whole of `onNewOrder`, so [newOrder] and the stages are reconcilable rather
 * than merely adjacent.
 */
class EngineMetrics(
    private val clock: NanoClock,
    /** Whether to also partition a new order into admit/match/settle. */
    val stages: Boolean = false,
) {
    val newOrder = LatencyHistogram("engine.newOrder")
    val cancel = LatencyHistogram("engine.cancel")
    val sessionTransition = LatencyHistogram("engine.sessionTransition")
    val purge = LatencyHistogram("engine.purge")
    val securityDefinition = LatencyHistogram("engine.securityDefinition")

    val admit = LatencyHistogram("engine.newOrder.admit")
    val match = LatencyHistogram("engine.newOrder.match")
    val settle = LatencyHistogram("engine.newOrder.settle")

    fun nanoTime(): Long = clock.nanoTime()

    /**
     * What one clock read costs here; see [measureClockOverheadNanos].
     *
     * Measured at construction, which is start-up, because the process is quiet then. Measuring it
     * at shutdown instead put it in the middle of a container tearing itself down and reported
     * ~69 ns where a quiet machine reports ~15 — a calibration is only useful if it is taken when
     * nothing else is happening.
     */
    val clockOverheadNanos: Long = measureClockOverheadNanos(clock)


    /** Every histogram, in report order. Stage histograms are dropped when they hold no samples. */
    fun all(): List<LatencyHistogram> = listOf(
        newOrder, cancel, sessionTransition, purge, securityDefinition, admit, match, settle,
    )

    fun summary(): String = buildString {
        append(" (clock read costs ~").append(clockOverheadNanos)
            .append(" ns, which is inside every figure below)")
        for (h in all()) {
            if (h.count == 0L) continue
            append("\n  ").append(h.name.removePrefix("engine.").padEnd(NAME_WIDTH))
            append(' ').append(h.summary())
        }
    }

    private companion object {
        const val NAME_WIDTH = 20
    }
}
