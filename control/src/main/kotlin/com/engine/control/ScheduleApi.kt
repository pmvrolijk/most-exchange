package com.engine.control

import com.engine.reference.OperatorCommands
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** A schedule as JSON: times and day names as an operator writes them. */
data class ScheduleRequest(
    val name: String,
    val zone: String,
    val weekdays: List<String>,
    val entries: List<EntryRequest>,
    val purgeTime: String? = null,
    val enabled: Boolean = true,
) {
    fun toSchedule(holidays: Set<LocalDate> = emptySet()) = SessionSchedule(
        name = name.trim(),
        zone = ZoneId.of(zone.trim()),
        weekdays = weekdays.map { parseWeekday(it) }.toSet(),
        entries = entries.map { it.toEntry() },
        purgeTime = purgeTime?.takeIf { it.isNotBlank() }?.let { LocalTime.parse(it) },
        enabled = enabled,
        holidays = holidays,
    )
}

data class EntryRequest(val at: String, val phase: String) {
    fun toEntry() = ScheduleEntry(LocalTime.parse(at), OperatorCommands.parsePhase(phase))
}

data class HolidayRequest(val date: String, val description: String? = null) {
    fun toHoliday() = Holiday(LocalDate.parse(date), description)
}

/** A schedule as it reads back, including the holidays attached to it. */
data class ScheduleView(
    val name: String,
    val zone: String,
    val weekdays: List<String>,
    val entries: List<EntryRequest>,
    val purgeTime: String?,
    val enabled: Boolean,
    val holidays: List<HolidayRequest>,
) {
    companion object {
        fun of(schedule: SessionSchedule, holidays: List<Holiday>) = ScheduleView(
            name = schedule.name,
            zone = schedule.zone.id,
            weekdays = schedule.weekdays.sortedBy { it.value }.map { it.name },
            entries = schedule.ordered.map { EntryRequest(it.at.toString(), it.phaseName) },
            purgeTime = schedule.purgeTime?.toString(),
            enabled = schedule.enabled,
            holidays = holidays.map { HolidayRequest(it.date.toString(), it.description) },
        )
    }
}

data class AssignRequest(val scheduleName: String?)

/**
 * Session schedules, holidays, and what the scheduler has been doing.
 *
 * The schedule is authored here and fires here. That costs no determinism — the `SessionTransition`
 * it emits is sequenced through the log like any other command — and it keeps weekends, holidays
 * and daylight saving out of the deterministic matching engine.
 */
@RestController
@RequestMapping("/api/schedules")
class ScheduleApi(
    private val repository: ScheduleRepository,
    private val scheduler: Scheduler,
) {

    @GetMapping
    fun list(): List<ScheduleView> =
        repository.schedules().map { ScheduleView.of(it, repository.holidays(it.name)) }

    @GetMapping("/{name}")
    fun get(@PathVariable name: String): ResponseEntity<ScheduleView> =
        repository.schedule(name)
            ?.let { ResponseEntity.ok(ScheduleView.of(it, repository.holidays(name))) }
            ?: ResponseEntity.notFound().build()

    /**
     * Create or replace. Validated by constructing the [SessionSchedule], so a purge scheduled
     * after the open, a duplicate entry time or an unknown zone is refused with its own message.
     */
    @PutMapping("/{name}")
    fun upsert(
        @PathVariable name: String,
        @RequestBody request: ScheduleRequest,
    ): ResponseEntity<ScheduleView> {
        val schedule = request.copy(name = name).toSchedule(repository.holidays(name).map { it.date }.toSet())
        repository.upsert(schedule)
        return ResponseEntity.ok(ScheduleView.of(schedule, repository.holidays(name)))
    }

    @DeleteMapping("/{name}")
    fun delete(@PathVariable name: String): ResponseEntity<Void> =
        if (repository.delete(name)) ResponseEntity.noContent().build()
        else ResponseEntity.notFound().build()

    @PostMapping("/{name}/holidays")
    fun addHoliday(
        @PathVariable name: String,
        @RequestBody request: HolidayRequest,
    ): ResponseEntity<List<HolidayRequest>> {
        requireNotNull(repository.schedule(name)) { "no such schedule: $name" }
        repository.addHoliday(name, request.toHoliday())
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(repository.holidays(name).map { HolidayRequest(it.date.toString(), it.description) })
    }

    @DeleteMapping("/{name}/holidays/{date}")
    fun removeHoliday(@PathVariable name: String, @PathVariable date: String): ResponseEntity<Void> =
        if (repository.removeHoliday(name, LocalDate.parse(date))) ResponseEntity.noContent().build()
        else ResponseEntity.notFound().build()

    /** What the schedule would do on a given date, without doing any of it. */
    @GetMapping("/{name}/preview")
    fun preview(
        @PathVariable name: String,
        @RequestParam(required = false) date: String?,
    ): List<String> {
        val schedule = repository.schedule(name)
            ?: throw IllegalArgumentException("no such schedule: $name")
        val at = date?.let {
            LocalDate.parse(it).atTime(LocalTime.NOON).atZone(schedule.zone).toInstant()
        } ?: Instant.now()
        return scheduler.preview(name, at)
    }
}

@RestController
@RequestMapping("/api/scheduler")
class SchedulerApi(
    private val repository: ScheduleRepository,
    private val scheduler: Scheduler,
) {

    /**
     * What the scheduler did, and what it deliberately did not do. A skip is recorded when its
     * reason changes rather than on every tick, so a halted security appears once, not every five
     * seconds all weekend.
     */
    @GetMapping("/runs")
    fun runs(@RequestParam(defaultValue = "100") limit: Int): List<ScheduleRunRow> =
        repository.runs(limit.coerceIn(1, MAX_RUNS))

    /** Forces a reconciliation now, for an operator who does not want to wait for the next tick. */
    @PostMapping("/tick")
    fun tick(): List<ScheduleDecision> = scheduler.reconcile(Instant.now())

    private companion object {
        const val MAX_RUNS = 1_000
    }
}

@RestController
@RequestMapping("/api/shards")
class ShardScheduleApi(private val repository: ScheduleRepository) {

    /** Puts a shard on a schedule, or takes it off one with a null name. */
    @PutMapping("/{shardId}/schedule")
    fun assign(
        @PathVariable shardId: Int,
        @RequestBody request: AssignRequest,
    ): ResponseEntity<Map<String, String?>> {
        request.scheduleName?.let {
            requireNotNull(repository.schedule(it)) { "no such schedule: $it" }
        }
        return if (repository.assign(shardId, request.scheduleName)) {
            ResponseEntity.ok(mapOf("shardId" to shardId.toString(), "schedule" to request.scheduleName))
        } else {
            ResponseEntity.notFound().build()
        }
    }

    @GetMapping("/{shardId}/schedule")
    fun get(@PathVariable shardId: Int): ResponseEntity<Map<String, String?>> =
        ResponseEntity.ok(
            mapOf("shardId" to shardId.toString(), "schedule" to repository.scheduleOf(shardId)),
        )
}
