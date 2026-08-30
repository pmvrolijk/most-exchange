package com.engine.control

import com.engine.sbe.Phase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reconciliation against a real database, with the Aeron link down.
 *
 * With no link every command reports `sent = false`, which is exactly what makes the *decisions*
 * legible: what the scheduler chose to do, and what it deliberately refused to do, is visible
 * without a cluster. Whether the bytes move is proved against a live exchange separately.
 */
@SpringBootTest
class SchedulerTest : PostgresTest() {

    @Autowired
    private lateinit var topology: TopologyService

    @Autowired
    private lateinit var schedules: ScheduleRepository

    @Autowired
    private lateinit var scheduler: Scheduler

    @Autowired
    private lateinit var link: ClusterLink

    private val amsterdam: ZoneId = ZoneId.of("Europe/Amsterdam")

    private fun tuesday(hour: Int, minute: Int = 0) =
        LocalDate.of(2026, 3, 3).atTime(hour, minute).atZone(amsterdam).toInstant()

    private fun seed(purgeTime: LocalTime? = LocalTime.of(7, 0)) {
        topology.createShard(shardRow(0))
        topology.createSecurity(securityRow(1, 0, "AAPL", ISIN_APPLE))
        topology.createSecurity(securityRow(2, 0, "MSFT", ISIN_MICROSOFT))
        schedules.upsert(
            SessionSchedule(
                name = "equities",
                zone = amsterdam,
                weekdays = setOf(
                    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                    DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
                ),
                entries = listOf(
                    ScheduleEntry(LocalTime.of(8, 0), Phase.PRE_OPEN.value()),
                    ScheduleEntry(LocalTime.of(8, 55), Phase.OPEN_AUCTION.value()),
                    ScheduleEntry(LocalTime.of(9, 0), Phase.CONTINUOUS.value()),
                    ScheduleEntry(LocalTime.of(17, 30), Phase.CLOSED.value()),
                ),
                purgeTime = purgeTime,
            ),
        )
        schedules.assign(0, "equities")
    }

    /** Puts the observed phase where the feed would have put it. */
    private fun observe(phase: Phase, vararg securityIds: Int) {
        securityIds.forEach { link.state.onSessionChanged(it, 0, phase.name) }
    }

    @Test
    fun `a schedule round trips through the database`() {
        seed()
        val stored = schedules.schedule("equities")!!
        assertEquals(amsterdam, stored.zone)
        assertEquals(5, stored.weekdays.size)
        assertEquals(LocalTime.of(7, 0), stored.purgeTime)
        assertEquals(
            listOf(LocalTime.of(8, 0), LocalTime.of(8, 55), LocalTime.of(9, 0), LocalTime.of(17, 30)),
            stored.ordered.map { it.at },
        )
        assertEquals(listOf(0 to "equities"), schedules.scheduledShards())
    }

    @Test
    fun `holidays attach to the schedule and make the day closed`() {
        seed()
        schedules.addHoliday("equities", Holiday(LocalDate.of(2026, 3, 3), "Test holiday"))

        val stored = schedules.schedule("equities")!!
        assertTrue(LocalDate.of(2026, 3, 3) in stored.holidays)
        assertEquals(Phase.CLOSED.value(), stored.phaseAt(tuesday(12)))
    }

    @Test
    fun `nothing is sent when the market is already where the schedule wants it`() {
        seed()
        observe(Phase.CONTINUOUS, 1, 2)

        val decision = scheduler.reconcile(tuesday(10)).single()
        assertEquals("CONTINUOUS", decision.expectedPhase)
        assertEquals("CONTINUOUS", decision.observedPhase)
        assertTrue(decision.actions.isEmpty(), "an already-correct market must not be transitioned")
        assertNull(decision.skipped)
    }

    @Test
    fun `catching up from closed walks the auction rather than jumping to continuous`() {
        // The backend was down through the open. Sending CONTINUOUS directly would skip the
        // uncross and leave a crossed resting book crossed.
        seed()
        observe(Phase.CLOSED, 1, 2)

        val decision = scheduler.reconcile(tuesday(10)).single()
        assertEquals(
            listOf("session PRE_OPEN shard 0"),
            decision.actions.map { it.command },
            "the walk stops at the first step that could not be sent",
        )
        assertEquals("CONTINUOUS", decision.expectedPhase)
    }

    @Test
    fun `a halted security is never reopened automatically`() {
        // Recovery is operator-driven by design. Worse than overriding that, reconciling back to
        // CONTINUOUS would do it without an auction.
        seed()
        observe(Phase.CONTINUOUS, 2)
        link.state.onVolatilityHalted(1, 0, 1L, 2L, 3L, "BUY")
        link.state.onSessionChanged(1, 0, "CLOSED")

        val decision = scheduler.reconcile(tuesday(10)).single()
        assertTrue(decision.actions.isEmpty())
        assertContains(decision.skipped.orEmpty(), "AAPL halted")
        assertContains(decision.skipped.orEmpty(), "operator-driven")
    }

    @Test
    fun `once a halt is cleared the scheduler resumes`() {
        seed()
        link.state.onVolatilityHalted(1, 0, 1L, 2L, 3L, "BUY")
        link.state.onSessionChanged(1, 0, "CLOSED")
        assertContains(scheduler.reconcile(tuesday(10)).single().skipped.orEmpty(), "halted")

        // The operator reopened it; the reopen sequence cleared the halt.
        observe(Phase.CONTINUOUS, 1, 2)
        val resumed = scheduler.reconcile(tuesday(10)).single()
        assertNull(resumed.skipped)
    }

    @Test
    fun `a shard whose books disagree is left alone and says so`() {
        seed()
        observe(Phase.CONTINUOUS, 1)
        observe(Phase.PRE_OPEN, 2)

        val decision = scheduler.reconcile(tuesday(10)).single()
        assertTrue(decision.actions.isEmpty())
        assertContains(decision.skipped.orEmpty(), "disagree")
        assertContains(decision.skipped.orEmpty(), "AAPL=CONTINUOUS")
    }

    @Test
    fun `a shard with no phase on the feed yet waits, and says what unblocks it`() {
        // The engine starts CLOSED and emits SessionChanged only on a transition, so a cold cluster
        // has a genuinely unknown phase here. Guessing CLOSED would be right after a cold boot and
        // catastrophic after a control-plane restart mid-session, which looks identical.
        seed()
        val decision = scheduler.reconcile(tuesday(10)).single()
        assertTrue(decision.actions.isEmpty())
        assertContains(decision.skipped.orEmpty(), "no phase seen yet for AAPL, MSFT")
        assertContains(decision.skipped.orEmpty(), "establish a baseline")
    }

    @Test
    fun `an unscheduled shard is not touched at all`() {
        seed()
        schedules.assign(0, null)
        assertTrue(scheduler.reconcile(tuesday(10)).isEmpty())
    }

    @Test
    fun `a weekend produces no transitions because the market is already closed`() {
        seed()
        observe(Phase.CLOSED, 1, 2)
        val saturday = LocalDate.of(2026, 3, 7).atTime(12, 0).atZone(amsterdam).toInstant()

        val decision = scheduler.reconcile(saturday).single()
        assertEquals("CLOSED", decision.expectedPhase)
        assertTrue(decision.actions.isEmpty())
    }

    @Test
    fun `the purge fires in its window and only once a day`() {
        seed()
        observe(Phase.CLOSED, 1, 2)

        val first = scheduler.reconcile(tuesday(7, 30)).single()
        assertEquals(listOf("purge shard 0"), first.actions.map { it.command })

        // With no link the purge could not be sent, so it is not recorded as run and is retried --
        // which is the behaviour wanted: an unsent purge is not a purge.
        assertTrue(schedules.runs().any { it.action == "purge" && !it.sent })
        assertTrue(!schedules.hasRun(0, "purge", 20260303))
    }

    @Test
    fun `a skip is recorded when its reason appears, not on every tick`() {
        // A tick runs every few seconds; writing "AAPL halted" all weekend would bury the log.
        seed()
        link.state.onVolatilityHalted(1, 0, 1L, 2L, 3L, "BUY")
        link.state.onSessionChanged(1, 0, "CLOSED")
        observe(Phase.CONTINUOUS, 2)

        repeat(5) { scheduler.reconcile(tuesday(10)) }

        val skips = schedules.runs().filter { it.action == "skipped" }
        assertEquals(1, skips.size, "one row per distinct reason, not one per tick")
        assertContains(skips.single().detail.orEmpty(), "halted")
    }

    @Test
    fun `the preview shows a day without running it`() {
        seed()
        val preview = scheduler.preview("equities", tuesday(3))
        assertEquals(
            listOf("07:00 purge expired orders", "08:00 PRE_OPEN", "08:55 OPEN_AUCTION", "09:00 CONTINUOUS", "17:30 CLOSED"),
            preview,
        )
        assertTrue(schedules.runs().isEmpty(), "a preview must not act")

        schedules.addHoliday("equities", Holiday(LocalDate.of(2026, 3, 3), "Test"))
        assertContains(scheduler.preview("equities", tuesday(3)).single(), "holiday")
    }
}
