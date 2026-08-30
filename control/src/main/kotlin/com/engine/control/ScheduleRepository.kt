package com.engine.control

import com.engine.reference.OperatorCommands
import com.engine.sbe.Phase
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** One thing the scheduler did, or deliberately did not do. */
data class ScheduleRunRow(
    val id: Long = 0,
    val at: String = "",
    val shardId: Int,
    val action: String,
    val phase: String?,
    val tradingDate: Int?,
    val sent: Boolean,
    val confirmed: Boolean,
    val detail: String?,
)

@Repository
class ScheduleRepository(private val jdbc: NamedParameterJdbcTemplate) {

    fun names(): List<String> =
        jdbc.queryForList("SELECT name FROM session_schedule ORDER BY name", emptyMap<String, Any>(), String::class.java)

    fun schedule(name: String): SessionSchedule? = jdbc.query(
        "SELECT * FROM session_schedule WHERE name = :name",
        mapOf("name" to name),
    ) { rs, _ ->
        SessionSchedule(
            name = rs.getString("name"),
            zone = ZoneId.of(rs.getString("zone")),
            weekdays = parseWeekdays(rs.getString("weekdays")),
            entries = entries(name),
            // Asked for as a LocalTime: a bare getObject hands back java.sql.Time, which is a
            // different type wearing the same name and cannot be cast to one.
            purgeTime = rs.getObject("purge_time", LocalTime::class.java),
            enabled = rs.getBoolean("enabled"),
            holidays = holidays(name).map { it.date }.toSet(),
        )
    }.firstOrNull()

    fun schedules(): List<SessionSchedule> = names().mapNotNull { schedule(it) }

    fun upsert(schedule: SessionSchedule) {
        jdbc.update(
            """
            INSERT INTO session_schedule (name, zone, weekdays, purge_time, enabled)
            VALUES (:name, :zone, :weekdays, :purgeTime, :enabled)
            ON CONFLICT (name) DO UPDATE SET zone = :zone, weekdays = :weekdays,
                                             purge_time = :purgeTime, enabled = :enabled
            """.trimIndent(),
            mapOf(
                "name" to schedule.name,
                "zone" to schedule.zone.id,
                "weekdays" to schedule.weekdays.sortedBy { it.value }.joinToString(",") { it.name },
                "purgeTime" to schedule.purgeTime,
                "enabled" to schedule.enabled,
            ),
        )
        jdbc.update(
            "DELETE FROM session_schedule_entry WHERE schedule_name = :name",
            mapOf("name" to schedule.name),
        )
        for (entry in schedule.entries) {
            jdbc.update(
                "INSERT INTO session_schedule_entry (schedule_name, at_time, phase) " +
                    "VALUES (:name, :at, :phase)",
                mapOf("name" to schedule.name, "at" to entry.at, "phase" to entry.phaseName),
            )
        }
    }

    fun delete(name: String): Boolean =
        jdbc.update("DELETE FROM session_schedule WHERE name = :name", mapOf("name" to name)) > 0

    private fun entries(name: String): List<ScheduleEntry> = jdbc.query(
        "SELECT at_time, phase FROM session_schedule_entry WHERE schedule_name = :name ORDER BY at_time",
        mapOf("name" to name),
    ) { rs, _ ->
        ScheduleEntry(rs.getObject("at_time", LocalTime::class.java), OperatorCommands.parsePhase(rs.getString("phase")))
    }

    // -------------------------------------------------------------- holidays

    fun holidays(name: String): List<Holiday> = jdbc.query(
        "SELECT holiday_date, description FROM market_holiday WHERE schedule_name = :name " +
            "ORDER BY holiday_date",
        mapOf("name" to name),
    ) { rs, _ -> Holiday(rs.getObject("holiday_date", LocalDate::class.java), rs.getString("description")) }

    fun addHoliday(name: String, holiday: Holiday) {
        jdbc.update(
            """
            INSERT INTO market_holiday (schedule_name, holiday_date, description)
            VALUES (:name, :date, :description)
            ON CONFLICT (schedule_name, holiday_date) DO UPDATE SET description = :description
            """.trimIndent(),
            mapOf("name" to name, "date" to holiday.date, "description" to holiday.description),
        )
    }

    fun removeHoliday(name: String, date: LocalDate): Boolean = jdbc.update(
        "DELETE FROM market_holiday WHERE schedule_name = :name AND holiday_date = :date",
        mapOf("name" to name, "date" to date),
    ) > 0

    // ------------------------------------------------------- shard assignment

    fun assign(shardId: Int, scheduleName: String?): Boolean = jdbc.update(
        "UPDATE shard SET schedule_name = :name WHERE shard_id = :shardId",
        mapOf("name" to scheduleName, "shardId" to shardId),
    ) > 0

    /** Shards that have a schedule, as `shardId to scheduleName`. */
    fun scheduledShards(): List<Pair<Int, String>> = jdbc.query(
        "SELECT shard_id, schedule_name FROM shard WHERE schedule_name IS NOT NULL ORDER BY shard_id",
        emptyMap<String, Any>(),
    ) { rs, _ -> rs.getInt("shard_id") to rs.getString("schedule_name") }

    fun scheduleOf(shardId: Int): String? = jdbc.query(
        "SELECT schedule_name FROM shard WHERE shard_id = :id",
        mapOf("id" to shardId),
    ) { rs, _ -> rs.getString("schedule_name") }.firstOrNull()

    // ------------------------------------------------------------- audit log

    fun record(run: ScheduleRunRow) {
        jdbc.update(
            """
            INSERT INTO schedule_run (shard_id, action, phase, trading_date, sent, confirmed, detail)
            VALUES (:shardId, :action, :phase, :tradingDate, :sent, :confirmed, :detail)
            """.trimIndent(),
            mapOf(
                "shardId" to run.shardId,
                "action" to run.action,
                "phase" to run.phase,
                "tradingDate" to run.tradingDate,
                "sent" to run.sent,
                "confirmed" to run.confirmed,
                "detail" to run.detail?.take(MAX_DETAIL),
            ),
        )
    }

    /**
     * The action and detail of the most recent row for a shard, used to suppress a repeat.
     *
     * Deduping against the log rather than against a field in the scheduler keeps the suppression
     * stateless: it needs no reset hook, and a restart does not re-log every condition that was
     * already there.
     */
    fun lastRun(shardId: Int): Pair<String, String?>? = jdbc.query(
        "SELECT action, detail FROM schedule_run WHERE shard_id = :id ORDER BY at DESC, id DESC LIMIT 1",
        mapOf("id" to shardId),
    ) { rs, _ -> rs.getString("action") to rs.getString("detail") }.firstOrNull()

    fun runs(limit: Int = 100): List<ScheduleRunRow> = jdbc.query(
        "SELECT * FROM schedule_run ORDER BY at DESC LIMIT :limit",
        mapOf("limit" to limit),
        RUN,
    )

    /**
     * Whether an action already ran for a shard on a trading date.
     *
     * This is what makes the purge fire once a day rather than on every tick of its window. Phase
     * transitions do not need it — they are guarded by the phase actually observed on the feed,
     * which is a stronger check than "we think we sent one".
     */
    fun hasRun(shardId: Int, action: String, tradingDate: Int): Boolean = jdbc.queryForObject(
        "SELECT count(*) FROM schedule_run WHERE shard_id = :shardId AND action = :action " +
            "AND trading_date = :tradingDate AND sent = TRUE",
        mapOf("shardId" to shardId, "action" to action, "tradingDate" to tradingDate),
        Int::class.java,
    )!! > 0

    /**
     * A Postgres advisory lock, so two control-plane instances do not both open the market.
     *
     * Session transitions are idempotent enough that a double send is survivable, but a duplicated
     * purge is not something to rely on luck for. The lock is released when the connection returns
     * to the pool, so it cannot outlive a crash.
     */
    fun tryLock(): Boolean = jdbc.queryForObject(
        "SELECT pg_try_advisory_xact_lock(:key)",
        mapOf("key" to SCHEDULER_LOCK),
        Boolean::class.java,
    ) ?: false

    private fun parseWeekdays(text: String): Set<DayOfWeek> =
        text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .map { parseWeekday(it) }
            .toSet()

    private companion object {
        const val MAX_DETAIL = 512

        /** Any stable value; it only has to be the same in every instance of this application. */
        const val SCHEDULER_LOCK = 8_051_974_201L

        val RUN = RowMapper { rs, _ ->
            ScheduleRunRow(
                id = rs.getLong("id"),
                at = rs.getString("at"),
                shardId = rs.getInt("shard_id"),
                action = rs.getString("action"),
                phase = rs.getString("phase"),
                tradingDate = rs.getObject("trading_date") as Int?,
                sent = rs.getBoolean("sent"),
                confirmed = rs.getBoolean("confirmed"),
                detail = rs.getString("detail"),
            )
        }
    }
}

/** Phase names as the audit log stores them. */
fun phaseName(phase: Byte): String = Phase.get(phase).name

/** Accepts the day names an operator would type, and says which one it could not read. */
fun parseWeekday(text: String): DayOfWeek {
    val name = text.trim().uppercase()
    return DayOfWeek.entries.firstOrNull { it.name == name || it.name.startsWith(name) }
        ?: throw IllegalArgumentException("unknown weekday: '$text'")
}
