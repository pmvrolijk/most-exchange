<script setup lang="ts">
import { ref, watch } from 'vue'
import DataState from '../components/DataState.vue'
import Modal from '../components/Modal.vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import { api } from '../api/client'
import { useCollection, useMutation, reason } from '../api/collection'
import type { Schedule, ScheduleDecision, ScheduleEntry, ScheduleRun, Shard } from '../api/types'
import { at } from '../format'

/**
 * The trading calendar, which lives here and deliberately not in the engine.
 *
 * Scheduling outside the state machine costs no determinism — the SessionTransition it emits is
 * sequenced through the replicated log like any other command — and keeps weekends, holidays and
 * daylight saving out of code where a bug kills every node at the same log position at once.
 */
const { rows: schedules, loading, error, reload } = useCollection<Schedule>('/schedules')
const { rows: shards } = useCollection<Shard>('/shards')
const { rows: runs, reload: reloadRuns } = useCollection<ScheduleRun>('/scheduler/runs?limit=100')
const { busy: saving, error: saveError, run: runSave } = useMutation()
const { busy: removing, error: removeError, run: runRemove } = useMutation()
const { busy: ticking, error: tickError, run: runTick } = useMutation()

const WEEKDAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY']
const PHASES = ['CLOSED', 'PRE_OPEN', 'OPEN_AUCTION', 'CONTINUOUS']

const draft = ref<Schedule | null>(null)
const creating = ref(false)
const deleting = ref<Schedule | null>(null)
const decisions = ref<ScheduleDecision[] | null>(null)

/** Shard → schedule assignment, read once per shard and kept as one map the selects bind to. */
const assignment = ref<Record<number, string>>({})
const assignError = ref<string | null>(null)

async function loadAssignments() {
  for (const shard of shards.value) {
    try {
      const row = await api.get<{ shardId: string; schedule: string | null }>(
        `/shards/${shard.shardId}/schedule`,
      )
      assignment.value[shard.shardId] = row.schedule ?? ''
    } catch (e) {
      assignError.value = reason(e)
    }
  }
}

async function assign(shardId: number, name: string) {
  assignError.value = null
  try {
    await api.put(`/shards/${shardId}/schedule`, { scheduleName: name === '' ? null : name })
    assignment.value[shardId] = name
  } catch (e) {
    assignError.value = reason(e)
  }
}

function create() {
  creating.value = true
  saveError.value = null
  draft.value = {
    name: '',
    zone: Intl.DateTimeFormat().resolvedOptions().timeZone,
    weekdays: ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY'],
    entries: [
      { at: '08:00', phase: 'PRE_OPEN' },
      { at: '09:00', phase: 'OPEN_AUCTION' },
      { at: '09:05', phase: 'CONTINUOUS' },
      { at: '17:30', phase: 'CLOSED' },
    ],
    purgeTime: '07:00',
    enabled: true,
    holidays: [],
  }
}

function edit(schedule: Schedule) {
  creating.value = false
  saveError.value = null
  draft.value = JSON.parse(JSON.stringify(schedule)) as Schedule
}

function toggleWeekday(day: string) {
  const d = draft.value
  if (!d) return
  d.weekdays = d.weekdays.includes(day) ? d.weekdays.filter((x) => x !== day) : [...d.weekdays, day]
}

function addEntry() {
  draft.value?.entries.push({ at: '12:00', phase: 'CLOSED' } as ScheduleEntry)
}

function removeEntry(index: number) {
  draft.value?.entries.splice(index, 1)
}

async function submit() {
  const d = draft.value
  if (!d) return
  const ok = await runSave(async () => {
    // Holidays are their own endpoint — the upsert body carries none, and sending an empty list
    // would read as "remove them all" to anyone comparing this with the response.
    await api.put<Schedule>(`/schedules/${encodeURIComponent(d.name)}`, {
      name: d.name,
      zone: d.zone,
      weekdays: d.weekdays,
      entries: d.entries,
      purgeTime: d.purgeTime === '' ? null : d.purgeTime,
      enabled: d.enabled,
    })
  })
  if (ok) {
    draft.value = null
    await reload()
  }
}

async function confirmDelete() {
  const s = deleting.value
  if (!s) return
  const ok = await runRemove(() => api.del<void>(`/schedules/${encodeURIComponent(s.name)}`))
  if (ok) {
    deleting.value = null
    await Promise.all([reload(), loadAssignments()])
  }
}

const holidayDate = ref('')
const holidayNote = ref('')
const holidayFor = ref<Schedule | null>(null)
const { busy: savingHoliday, error: holidayError, run: runHoliday } = useMutation()

async function addHoliday() {
  const s = holidayFor.value
  if (!s) return
  const ok = await runHoliday(async () => {
    await api.post(`/schedules/${encodeURIComponent(s.name)}/holidays`, {
      date: holidayDate.value,
      description: holidayNote.value === '' ? null : holidayNote.value,
    })
  })
  if (ok) {
    holidayDate.value = ''
    holidayNote.value = ''
    holidayFor.value = null
    await reload()
  }
}

async function removeHoliday(schedule: Schedule, date: string) {
  await runHoliday(async () => {
    await api.del(`/schedules/${encodeURIComponent(schedule.name)}/holidays/${date}`)
  })
  await reload()
}

async function tick() {
  await runTick(async () => {
    decisions.value = await api.post<ScheduleDecision[]>('/scheduler/tick')
  })
  await reloadRuns()
}

// The assignments need the shard list, which arrives asynchronously; watching it is what fetches
// them once it exists, without putting a second loading state on screen.
watch(shards, loadAssignments, { immediate: true })
</script>

<template>
  <h1>Schedules</h1>
  <p class="lede">
    Every tick <strong>reconciles</strong>: the scheduler compares the phase the calendar wants
    against the phase the L3 feed reports and sends the difference, which makes it idempotent and
    self-healing after an outage. Two rules it keeps and an operator should know — the difference
    between phases is a path, not a destination, so catching up walks the intermediate phases
    (the uncross runs only on OPEN_AUCTION → CONTINUOUS); and a halted security is never reconciled
    back open, because that too would skip the auction. Recovery is
    <router-link to="/operations">operator-driven</router-link>.
  </p>

  <div class="toolbar">
    <button @click="create">New schedule</button>
    <button class="ghost" :disabled="ticking" @click="tick">
      {{ ticking ? 'Reconciling…' : 'Reconcile now' }}
    </button>
    <span class="hint">A tick runs on its own every few seconds; this is for not waiting.</span>
  </div>
  <p v-if="tickError" class="error">{{ tickError }}</p>

  <div v-if="decisions" class="card">
    <h2 style="font-size: 15px; margin: 0 0 12px">Last reconciliation</h2>
    <p v-if="decisions.length === 0" class="empty">No shard is on a schedule.</p>
    <div v-for="d in decisions" :key="`${d.shardId}-${d.schedule}`" class="outcome" :class="d.skipped ? 'pending' : 'ok'">
      <div class="command">
        shard {{ d.shardId }} · {{ d.schedule }} · wants {{ d.expectedPhase }}, sees
        {{ d.observedPhase ?? 'unknown' }}
      </div>
      <p v-if="d.skipped" class="detail">skipped: {{ d.skipped }}</p>
      <p v-for="(a, i) in d.actions" :key="i" class="detail">{{ a.command }} — {{ a.detail }}</p>
      <p v-if="!d.skipped && d.actions.length === 0" class="detail">already in the wanted phase.</p>
    </div>
  </div>

  <div class="card">
    <DataState :loading="loading" :error="error" :empty="schedules.length === 0">
      <template #empty>No schedules. Without one, every transition is manual.</template>
      <table>
        <thead>
          <tr>
            <th>Name</th>
            <th>Zone</th>
            <th>Trading days</th>
            <th>Day</th>
            <th>Purge</th>
            <th>Holidays</th>
            <th>State</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="s in schedules" :key="s.name">
            <td>{{ s.name }}</td>
            <td class="mono">{{ s.zone }}</td>
            <td>{{ s.weekdays.map((d) => d.slice(0, 3)).join(' ') }}</td>
            <td class="mono">
              <span v-for="e in s.entries" :key="e.at">{{ e.at }} {{ e.phase }}<br /></span>
            </td>
            <td class="mono">{{ s.purgeTime ?? '—' }}</td>
            <td class="mono">
              <span v-for="h in s.holidays" :key="h.date">
                {{ h.date }}
                <button class="link" @click="removeHoliday(s, h.date)">×</button><br />
              </span>
              <span v-if="s.holidays.length === 0">—</span>
            </td>
            <td>
              <span class="pill" :class="s.enabled ? 'good' : 'bad'">
                {{ s.enabled ? 'enabled' : 'disabled' }}
              </span>
            </td>
            <td class="row-actions">
              <button class="ghost small" @click="edit(s)">Edit</button>
              <button class="ghost small" @click="holidayFor = s">Holiday</button>
              <button class="ghost small" @click="deleting = s">Delete</button>
            </td>
          </tr>
        </tbody>
      </table>
    </DataState>
    <p v-if="holidayError" class="error">{{ holidayError }}</p>
  </div>

  <div class="card">
    <h2 style="font-size: 15px; margin: 0 0 8px">Which shard runs which schedule</h2>
    <p class="lede" style="margin-bottom: 12px">
      A shard on no schedule is never moved by the scheduler; every transition on it is manual.
    </p>
    <p v-if="assignError" class="error">{{ assignError }}</p>
    <table>
      <thead><tr><th class="num">Shard</th><th>Schedule</th></tr></thead>
      <tbody>
        <tr v-for="shard in shards" :key="shard.shardId">
          <td class="num">{{ shard.shardId }}</td>
          <td>
            <select
              :value="assignment[shard.shardId] ?? ''"
              @change="assign(shard.shardId, ($event.target as HTMLSelectElement).value)"
            >
              <option value="">— none, manual only —</option>
              <option v-for="s in schedules" :key="s.name" :value="s.name">{{ s.name }}</option>
            </select>
          </td>
        </tr>
      </tbody>
    </table>
  </div>

  <div class="card">
    <h2 style="font-size: 15px; margin: 0 0 8px">Scheduler runs</h2>
    <p class="lede" style="margin-bottom: 12px">
      What it did, and what it deliberately did not. A skip is recorded when its <em>reason</em>
      changes rather than on every tick, or a halted security would write the same row every few
      seconds all weekend.
    </p>
    <p v-if="runs.length === 0" class="empty">Nothing yet.</p>
    <div v-else class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>At</th><th class="num">Shard</th><th>Action</th><th>Phase</th>
            <th class="num">Trading date</th><th>Sent</th><th>Confirmed</th><th>Detail</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in runs" :key="r.id">
            <td>{{ at(r.at) }}</td>
            <td class="num">{{ r.shardId }}</td>
            <td>{{ r.action }}</td>
            <td>{{ r.phase ?? '—' }}</td>
            <td class="num">{{ r.tradingDate ?? '—' }}</td>
            <td><span class="pill" :class="r.sent ? 'good' : 'bad'">{{ r.sent ? 'sent' : 'no' }}</span></td>
            <td><span class="pill" :class="r.confirmed ? 'good' : 'warn'">{{ r.confirmed ? 'yes' : 'no' }}</span></td>
            <td>{{ r.detail ?? '—' }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>

  <Modal
    v-if="draft"
    :title="creating ? 'New schedule' : `Schedule ${draft.name}`"
    :busy="saving"
    :error="saveError"
    @submit="submit"
    @close="draft = null"
  >
    <div class="form-grid">
      <div>
        <label for="sch-name">Name</label>
        <input id="sch-name" v-model="draft.name" :disabled="!creating" />
      </div>
      <div>
        <label for="sch-zone">Time zone</label>
        <input id="sch-zone" v-model="draft.zone" class="mono" placeholder="Europe/Amsterdam" />
        <p class="hint">An IANA zone, so daylight saving is handled by the calendar, not by hand.</p>
      </div>
      <div class="wide">
        <label>Trading days</label>
        <div class="toolbar">
          <button
            v-for="day in WEEKDAYS"
            :key="day"
            type="button"
            class="small"
            :class="draft.weekdays.includes(day) ? '' : 'ghost'"
            @click="toggleWeekday(day)"
          >
            {{ day.slice(0, 3) }}
          </button>
        </div>
      </div>
      <div class="wide">
        <label>Transitions</label>
        <div v-for="(entry, i) in draft.entries" :key="i" class="toolbar" style="margin-bottom: 6px">
          <input v-model="entry.at" class="mono" style="width: 110px" placeholder="09:00" />
          <select v-model="entry.phase" style="width: 200px">
            <option v-for="p in PHASES" :key="p" :value="p">{{ p }}</option>
          </select>
          <button type="button" class="ghost small" @click="removeEntry(i)">Remove</button>
        </div>
        <button type="button" class="ghost small" @click="addEntry">Add transition</button>
        <p class="hint">
          Times are local to the zone above. Order does not matter — the schedule sorts them — but
          the walk does: reaching CONTINUOUS through OPEN_AUCTION is what runs the uncross.
        </p>
      </div>
      <div>
        <label for="sch-purge">Purge at</label>
        <input id="sch-purge" v-model="draft.purgeTime" class="mono" placeholder="07:00" />
        <p class="hint">Must be before the first transition: a purge sweeps books, and orders are accepted from PRE_OPEN.</p>
      </div>
      <div>
        <label>
          <input type="checkbox" v-model="draft.enabled" />
          Enabled
        </label>
        <p class="hint">A disabled schedule reads as CLOSED, which is the same state as a market not scheduled to open.</p>
      </div>
    </div>
  </Modal>

  <Modal
    v-if="holidayFor"
    :title="`Holiday · ${holidayFor.name}`"
    :busy="savingHoliday"
    :error="holidayError"
    submit-label="Add holiday"
    @submit="addHoliday"
    @close="holidayFor = null"
  >
    <div class="form-grid">
      <div>
        <label for="hol-date">Date</label>
        <input id="hol-date" v-model="holidayDate" type="date" />
      </div>
      <div>
        <label for="hol-note">Description</label>
        <input id="hol-note" v-model="holidayNote" placeholder="optional" />
      </div>
    </div>
    <p class="hint">A holiday makes the day read as CLOSED for every shard on this schedule.</p>
  </Modal>

  <ConfirmDialog
    v-if="deleting"
    title="Delete schedule"
    :message="`${deleting.name} is removed and any shard on it becomes manual-only — nothing will move its phase, including back to CLOSED at the end of the day.`"
    confirm-label="Delete"
    danger
    :busy="removing"
    :error="removeError"
    @confirm="confirmDelete"
    @close="deleting = null"
  />
</template>
