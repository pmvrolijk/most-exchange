package nl.lamia.most.exchange.control

import nl.lamia.most.exchange.sbe.Phase
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** One transition in a trading day: at this local time, be in this phase. */
data class ScheduleEntry(val at: LocalTime, val phase: Byte) {
    init {
        require(phase in Phase.CLOSED.value()..Phase.CONTINUOUS.value()) {
            "unknown phase value: $phase"
        }
    }

    val phaseName: String get() = Phase.get(phase).name
}

/** A non-trading date. Separate from the weekday set because holidays are exceptions, not rules. */
data class Holiday(val date: LocalDate, val description: String?)

/**
 * A trading day, as a named template a shard follows.
 *
 * Times are **local** and the zone is stored with them, because a market opens at 09:00 local
 * whatever the offset happens to be that week. Storing UTC instants would silently shift the open
 * by an hour twice a year.
 */
data class SessionSchedule(
    val name: String,
    val zone: ZoneId,
    val weekdays: Set<DayOfWeek>,
    val entries: List<ScheduleEntry>,
    val purgeTime: LocalTime? = null,
    val enabled: Boolean = true,
    val holidays: Set<LocalDate> = emptySet(),
) {
    init {
        require(name.isNotBlank()) { "a schedule needs a name" }
        require(name.length <= MAX_NAME) { "schedule name exceeds $MAX_NAME characters" }
        require(weekdays.isNotEmpty()) { "schedule $name trades on no days" }
        val times = entries.map { it.at }
        require(times.size == times.toSet().size) {
            "schedule $name has two entries at the same time: " +
                "${times.groupBy { it }.filterValues { it.size > 1 }.keys}"
        }
        // A purge is off-session housekeeping and must complete before orders are accepted, which
        // begins at PRE_OPEN. Running it after that would sweep a live book.
        val first = entries.minByOrNull { it.at }
        if (purgeTime != null && first != null) {
            require(purgeTime < first.at) {
                "schedule $name purges at $purgeTime, at or after its first transition at " +
                    "${first.at}; the purge must run before any order is accepted"
            }
        }
    }

    /** Ordered as the day runs, whatever order they were declared in. */
    val ordered: List<ScheduleEntry> get() = entries.sortedBy { it.at }

    fun isTradingDay(date: LocalDate): Boolean =
        enabled && date.dayOfWeek in weekdays && date !in holidays

    /**
     * The phase this schedule says a shard should be in at [instant].
     *
     * `CLOSED` before the first entry, on a weekend, on a holiday, or when the schedule is
     * disabled — a market that is not scheduled to be open is closed, which is the same state.
     */
    fun phaseAt(instant: Instant): Byte {
        val local = instant.atZone(zone)
        val date = local.toLocalDate()
        if (!isTradingDay(date)) return Phase.CLOSED.value()
        val time = local.toLocalTime()
        return ordered.lastOrNull { it.at <= time }?.phase ?: Phase.CLOSED.value()
    }

    /** `YYYYMMDD` in the schedule's own zone, which is the date a trading day is named by. */
    fun tradingDateAt(instant: Instant): Int {
        val date = instant.atZone(zone).toLocalDate()
        return date.year * 10_000 + date.monthValue * 100 + date.dayOfMonth
    }

    /**
     * Whether a purge is due: on a trading day, at or after the purge time, and still before the
     * first transition.
     *
     * Bounded on both sides on purpose. A purge that missed its window is skipped rather than run
     * late — the sweep walks the price ladders of a book that is about to start accepting orders,
     * and running it after `PRE_OPEN` would expire orders someone had just placed.
     */
    fun purgeDue(instant: Instant): Boolean {
        val purge = purgeTime ?: return false
        val local = instant.atZone(zone)
        if (!isTradingDay(local.toLocalDate())) return false
        val time = local.toLocalTime()
        val first = ordered.firstOrNull()?.at ?: LocalTime.MAX
        return time >= purge && time < first
    }

    companion object {
        const val MAX_NAME = 64

        /**
         * The transitions needed to get from [from] to [to].
         *
         * Not simply `[to]`, and this is the part that is easy to get wrong. The lifecycle is
         * ordered — `CLOSED → PRE_OPEN → OPEN_AUCTION → CONTINUOUS` — and **the uncross runs only
         * on `OPEN_AUCTION → CONTINUOUS`**. A backend that was down through the open and catches up
         * by sending `CONTINUOUS` straight from `CLOSED` would skip the auction entirely, leaving
         * any crossed resting book sitting crossed until an aggressor happened to arrive.
         *
         * So opening walks every intermediate phase; closing is a single step, since there is
         * nothing to compute on the way down.
         */
        fun pathTo(from: Byte, to: Byte): List<Byte> =
            if (to <= from) listOf(to) else (from + 1..to.toInt()).map { it.toByte() }
    }
}
