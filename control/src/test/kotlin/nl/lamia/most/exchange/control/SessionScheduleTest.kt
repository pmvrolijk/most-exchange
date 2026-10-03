package nl.lamia.most.exchange.control

import nl.lamia.most.exchange.sbe.Phase
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure: no database, no Aeron, and the instant is passed in rather than read from a clock, so a
 * test can stand at 09:00 on a Tuesday in March without waiting for March.
 */
class SessionScheduleTest {

    private val amsterdam: ZoneId = ZoneId.of("Europe/Amsterdam")

    private fun schedule(
        purgeTime: LocalTime? = LocalTime.of(7, 0),
        enabled: Boolean = true,
        holidays: Set<LocalDate> = emptySet(),
        weekdays: Set<DayOfWeek> = setOf(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
        ),
    ) = SessionSchedule(
        name = "equities",
        zone = amsterdam,
        weekdays = weekdays,
        entries = listOf(
            ScheduleEntry(LocalTime.of(8, 0), Phase.PRE_OPEN.value()),
            ScheduleEntry(LocalTime.of(8, 55), Phase.OPEN_AUCTION.value()),
            ScheduleEntry(LocalTime.of(9, 0), Phase.CONTINUOUS.value()),
            ScheduleEntry(LocalTime.of(17, 30), Phase.CLOSED.value()),
        ),
        purgeTime = purgeTime,
        enabled = enabled,
        holidays = holidays,
    )

    /** A local wall-clock time on a Tuesday, in the schedule's own zone. */
    private fun tuesday(hour: Int, minute: Int = 0) =
        LocalDate.of(2026, 3, 3).atTime(hour, minute).atZone(amsterdam).toInstant()

    private fun on(date: LocalDate, hour: Int, minute: Int = 0) =
        date.atTime(hour, minute).atZone(amsterdam).toInstant()

    // ------------------------------------------------------------- phaseAt

    @Test
    fun `the phase follows the entries through the day`() {
        val s = schedule()
        assertEquals(Phase.CLOSED.value(), s.phaseAt(tuesday(6)))
        assertEquals(Phase.PRE_OPEN.value(), s.phaseAt(tuesday(8)))
        assertEquals(Phase.PRE_OPEN.value(), s.phaseAt(tuesday(8, 54)))
        assertEquals(Phase.OPEN_AUCTION.value(), s.phaseAt(tuesday(8, 55)))
        assertEquals(Phase.CONTINUOUS.value(), s.phaseAt(tuesday(9)))
        assertEquals(Phase.CONTINUOUS.value(), s.phaseAt(tuesday(17, 29)))
        assertEquals(Phase.CLOSED.value(), s.phaseAt(tuesday(17, 30)))
        assertEquals(Phase.CLOSED.value(), s.phaseAt(tuesday(23)))
    }

    @Test
    fun `a weekend, a holiday and a disabled schedule are all simply closed`() {
        val saturday = LocalDate.of(2026, 3, 7)
        assertEquals(Phase.CLOSED.value(), schedule().phaseAt(on(saturday, 12)))

        val christmas = LocalDate.of(2026, 12, 25)
        assertEquals(
            Phase.CLOSED.value(),
            schedule(holidays = setOf(christmas)).phaseAt(on(christmas, 12)),
        )
        assertEquals(Phase.CLOSED.value(), schedule(enabled = false).phaseAt(tuesday(12)))
    }

    @Test
    fun `times are local, so the open does not move when the clocks do`() {
        // Amsterdam goes to summer time on 2026-03-29. 09:00 local is 08:00 UTC before and 07:00
        // UTC after; storing instants rather than local times would shift the open by an hour.
        val beforeDst = LocalDate.of(2026, 3, 27)
        val afterDst = LocalDate.of(2026, 3, 31)
        val s = schedule()

        assertEquals(Phase.CONTINUOUS.value(), s.phaseAt(on(beforeDst, 9)))
        assertEquals(Phase.CONTINUOUS.value(), s.phaseAt(on(afterDst, 9)))
        assertEquals(Phase.OPEN_AUCTION.value(), s.phaseAt(on(beforeDst, 8, 56)))
        assertEquals(Phase.OPEN_AUCTION.value(), s.phaseAt(on(afterDst, 8, 56)))
    }

    @Test
    fun `the trading date is the local date, not the UTC one`() {
        // 00:30 local on the 4th is 23:30 UTC on the 3rd. The trading day is named locally.
        val s = schedule()
        assertEquals(20260304, s.tradingDateAt(on(LocalDate.of(2026, 3, 4), 0, 30)))
        assertEquals(20260303, s.tradingDateAt(tuesday(9)))
    }

    // --------------------------------------------------------------- purge

    @Test
    fun `the purge is due only in the window before the first transition`() {
        val s = schedule(purgeTime = LocalTime.of(7, 0))
        assertFalse(s.purgeDue(tuesday(6, 59)))
        assertTrue(s.purgeDue(tuesday(7)))
        assertTrue(s.purgeDue(tuesday(7, 59)))
        // Once PRE_OPEN has started, orders are being accepted; a late sweep would expire orders
        // someone just placed, so a missed purge is skipped rather than run late.
        assertFalse(s.purgeDue(tuesday(8)))
        assertFalse(s.purgeDue(tuesday(12)))
    }

    @Test
    fun `no purge time means no purge, and never on a non-trading day`() {
        assertFalse(schedule(purgeTime = null).purgeDue(tuesday(7)))
        assertFalse(schedule().purgeDue(on(LocalDate.of(2026, 3, 7), 7)))
    }

    @Test
    fun `a purge at or after the first transition is refused when the schedule is built`() {
        val e = assertFailsWith<IllegalArgumentException> {
            SessionSchedule(
                name = "bad",
                zone = amsterdam,
                weekdays = setOf(DayOfWeek.MONDAY),
                entries = listOf(ScheduleEntry(LocalTime.of(8, 0), Phase.PRE_OPEN.value())),
                purgeTime = LocalTime.of(8, 30),
            )
        }
        assertTrue(e.message!!.contains("before any order is accepted"), e.message!!)
    }

    // ---------------------------------------------------------------- path

    @Test
    fun `opening walks every phase, because the uncross runs only on the last transition`() {
        // This is the one that matters for catch-up. A backend that was down through the open and
        // sends CONTINUOUS straight from CLOSED skips the auction, leaving a crossed resting book
        // crossed until an aggressor happens to arrive.
        assertEquals(
            listOf(Phase.PRE_OPEN.value(), Phase.OPEN_AUCTION.value(), Phase.CONTINUOUS.value()),
            SessionSchedule.pathTo(Phase.CLOSED.value(), Phase.CONTINUOUS.value()),
        )
        assertEquals(
            listOf(Phase.OPEN_AUCTION.value(), Phase.CONTINUOUS.value()),
            SessionSchedule.pathTo(Phase.PRE_OPEN.value(), Phase.CONTINUOUS.value()),
        )
        assertEquals(
            listOf(Phase.PRE_OPEN.value()),
            SessionSchedule.pathTo(Phase.CLOSED.value(), Phase.PRE_OPEN.value()),
        )
    }

    @Test
    fun `closing is a single step, since nothing is computed on the way down`() {
        assertEquals(
            listOf(Phase.CLOSED.value()),
            SessionSchedule.pathTo(Phase.CONTINUOUS.value(), Phase.CLOSED.value()),
        )
        assertEquals(
            listOf(Phase.PRE_OPEN.value()),
            SessionSchedule.pathTo(Phase.CONTINUOUS.value(), Phase.PRE_OPEN.value()),
        )
    }

    @Test
    fun `a path to where you already are is a single no-op step`() {
        assertEquals(
            listOf(Phase.CONTINUOUS.value()),
            SessionSchedule.pathTo(Phase.CONTINUOUS.value(), Phase.CONTINUOUS.value()),
        )
    }

    // ---------------------------------------------------------- validation

    @Test
    fun `a schedule with no trading days or duplicate times is refused`() {
        assertFailsWith<IllegalArgumentException> {
            SessionSchedule("x", amsterdam, emptySet(), emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            SessionSchedule(
                "x", amsterdam, setOf(DayOfWeek.MONDAY),
                listOf(
                    ScheduleEntry(LocalTime.of(9, 0), Phase.CONTINUOUS.value()),
                    ScheduleEntry(LocalTime.of(9, 0), Phase.CLOSED.value()),
                ),
            )
        }
    }

    @Test
    fun `entries are ordered by time however they were declared`() {
        val s = SessionSchedule(
            "x", amsterdam, setOf(DayOfWeek.MONDAY),
            listOf(
                ScheduleEntry(LocalTime.of(17, 30), Phase.CLOSED.value()),
                ScheduleEntry(LocalTime.of(9, 0), Phase.CONTINUOUS.value()),
            ),
        )
        assertEquals(listOf(LocalTime.of(9, 0), LocalTime.of(17, 30)), s.ordered.map { it.at })
    }

    @Test
    fun `a venue that trades every day can say so`() {
        val always = schedule(weekdays = DayOfWeek.entries.toSet())
        assertTrue(always.isTradingDay(LocalDate.of(2026, 3, 7)))
        assertEquals(Phase.CONTINUOUS.value(), always.phaseAt(on(LocalDate.of(2026, 3, 7), 12)))
    }
}
