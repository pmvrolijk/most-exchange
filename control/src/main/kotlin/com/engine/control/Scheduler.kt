package com.engine.control

import com.engine.sbe.Phase
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** What a tick decided for one shard, whether or not it did anything. */
data class ScheduleDecision(
    val shardId: Int,
    val schedule: String,
    val expectedPhase: String,
    val observedPhase: String?,
    val actions: List<CommandResult>,
    val skipped: String?,
)

/**
 * Drives session transitions and the daily purge from a calendar.
 *
 * The schedule lives here rather than in the engine, and that costs no determinism: the
 * `SessionTransition` this emits is sequenced through the replicated log like any other command, so
 * every node applies it at the same log position. What keeping it here buys is that a trading
 * calendar — weekends, holidays, daylight saving — stays out of the deterministic state machine,
 * where a bug would kill every node at the same log position simultaneously.
 *
 * Every tick is a **reconciliation**, not a trigger: it compares the phase the schedule says a
 * shard should be in against the phase the L3 feed says it is in, and sends the difference. That
 * one choice makes the scheduler idempotent, makes it recover by itself after an outage, and means
 * there is no missed-timer state to keep anywhere.
 */
@Service
class Scheduler(
    private val schedules: ScheduleRepository,
    private val topology: TopologyService,
    private val operations: OperationsService,
    private val link: ClusterLink,
    @Value("\${control.scheduler.enabled:true}") private val enabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(Scheduler::class.java)

    @Scheduled(fixedDelayString = "\${control.scheduler.intervalMs:5000}")
    fun tick() {
        if (!enabled) return
        runCatching { reconcile(Instant.now()) }
            .onFailure { log.error("scheduler tick failed", it) }
    }

    /**
     * One reconciliation pass over every scheduled shard.
     *
     * Takes the instant rather than reading the clock, so a test can put it at 09:00 on a Tuesday
     * without waiting until Tuesday.
     */
    @Transactional
    fun reconcile(now: Instant): List<ScheduleDecision> {
        // Only one instance may drive the market. A duplicated transition is survivable; a
        // duplicated purge is not something to leave to luck.
        if (!schedules.tryLock()) {
            log.debug("another instance holds the scheduler lock")
            return emptyList()
        }
        return schedules.scheduledShards().map { (shardId, name) -> reconcileShard(shardId, name, now) }
    }

    @Suppress("ReturnCount")
    private fun reconcileShard(shardId: Int, name: String, now: Instant): ScheduleDecision {
        val schedule = schedules.schedule(name)
            ?: return skip(shardId, name, "?", null, "schedule '$name' no longer exists")

        val expected = schedule.phaseAt(now)
        val expectedName = phaseName(expected)
        val securities = topology.securitiesOfShard(shardId)
        if (securities.isEmpty()) {
            return skip(shardId, name, expectedName, null, "shard $shardId serves no securities")
        }

        val observedPhases = securities.map { link.state.securityState(it.securityId)?.phase }
        val observed = observedPhases.firstOrNull()

        // A halted security is never reopened automatically. Recovery is operator-driven by design
        // (Design.md §4.6), and a scheduler that reconciled its way back to CONTINUOUS would not
        // only override that -- it would do it *without an auction*, since the shard is already
        // past OPEN_AUCTION from the scheduler's point of view.
        val halted = securities.filter {
            val halt = link.state.securityState(it.securityId)?.halt
            halt != null && halt.clearedAt == null
        }
        if (halted.isNotEmpty()) {
            return skip(
                shardId, name, expectedName, observed,
                "${halted.joinToString(", ") { it.symbol }} halted; recovery is operator-driven " +
                    "(POST /api/shards/$shardId/reopen)",
            )
        }

        val tradingDate = schedule.tradingDateAt(now)
        val actions = mutableListOf<CommandResult>()

        // The purge runs before any transition of the day, and once per trading date.
        if (schedule.purgeDue(now) && !schedules.hasRun(shardId, PURGE, tradingDate)) {
            val result = operations.purge(shardId, tradingDate)
            actions += result
            record(shardId, PURGE, null, tradingDate, result)
        }

        if (observedPhases.any { it == null }) {
            // A freshly booted engine has made no transition, so it has emitted no SessionChanged
            // and its phase is genuinely unknown here. Guessing is not safe in both directions: the
            // engine starts CLOSED, but a control plane that restarted mid-session would be looking
            // at exactly the same silence with the market wide open, and assuming CLOSED there
            // would shut it. So it waits, and says what unblocks it.
            val unknown = securities.zip(observedPhases).filter { it.second == null }
            return skip(
                shardId, name, expectedName, observed,
                "no phase seen yet for ${unknown.joinToString(", ") { it.first.symbol }}: send any " +
                    "session command once to establish a baseline " +
                    "(POST /api/shards/$shardId/session), after which the feed keeps it current. " +
                    "If that was already done, check that market-data is running and that " +
                    "control.l3 points at it.",
            )
        }
        if (observedPhases.distinct().size > 1) {
            return skip(
                shardId, name, expectedName, observed,
                "books disagree on their phase: " +
                    securities.zip(observedPhases).joinToString(", ") { "${it.first.symbol}=${it.second}" },
            )
        }
        if (observed == expectedName) {
            return ScheduleDecision(shardId, name, expectedName, observed, actions, null)
        }

        // The difference between two phases is a path, not a destination: the uncross runs only on
        // OPEN_AUCTION -> CONTINUOUS, so catching up to CONTINUOUS from CLOSED has to walk through
        // the auction or a crossed resting book stays crossed.
        val from = Phase.valueOf(observed!!).value()
        for (step in SessionSchedule.pathTo(from, expected)) {
            val result = operations.session(shardId, step, tradingDate)
            actions += result
            record(shardId, SESSION, phaseName(step), tradingDate, result)
            if (!result.sent) break
        }
        return ScheduleDecision(shardId, name, expectedName, observed, actions, null)
    }

    /** What the schedule would do on a given day, without doing any of it. */
    fun preview(name: String, at: Instant): List<String> {
        val schedule = schedules.schedule(name) ?: return listOf("no such schedule: $name")
        val date = at.atZone(schedule.zone).toLocalDate()
        if (!schedule.isTradingDay(date)) {
            val why = when {
                !schedule.enabled -> "the schedule is disabled"
                date in schedule.holidays -> "a holiday"
                else -> "not a trading weekday"
            }
            return listOf("$date is not a trading day: $why")
        }
        return buildList {
            schedule.purgeTime?.let { add("$it purge expired orders") }
            schedule.ordered.forEach { add("${it.at} ${it.phaseName}") }
        }
    }

    /**
     * Records a skip once per distinct reason, not once per tick.
     *
     * A scheduler that skips silently is indistinguishable from one that is broken, so a skip has
     * to be visible. But a tick runs every few seconds, and writing the same "AAPL halted" row all
     * weekend would bury the audit log in exactly the rows nobody needs. The reason changing is the
     * event worth recording; the reason persisting is not.
     */
    private fun skip(
        shardId: Int,
        name: String,
        expected: String,
        observed: String?,
        why: String,
    ): ScheduleDecision {
        if (schedules.lastRun(shardId) != SKIPPED to why) {
            schedules.record(
                ScheduleRunRow(
                    shardId = shardId, action = SKIPPED, phase = expected, tradingDate = null,
                    sent = false, confirmed = false, detail = why,
                ),
            )
            log.info("scheduler: shard {} skipped -- {}", shardId, why)
        }
        return ScheduleDecision(shardId, name, expected, observed, emptyList(), why)
    }

    /**
     * Records what a command did. A send is always recorded; a failure is recorded when its reason
     * appears, for the same reason a skip is — a market that is down all weekend would otherwise
     * write the same "no cluster link" row every five seconds.
     */
    private fun record(
        shardId: Int,
        action: String,
        phase: String?,
        tradingDate: Int,
        result: CommandResult,
    ) {
        if (!result.sent && schedules.lastRun(shardId) == action to result.detail) return
        schedules.record(
            ScheduleRunRow(
                shardId = shardId, action = action, phase = phase, tradingDate = tradingDate,
                sent = result.sent, confirmed = result.confirmed, detail = result.detail,
            ),
        )
    }

    private companion object {
        const val SESSION = "session"
        const val PURGE = "purge"
        const val SKIPPED = "skipped"
    }
}
