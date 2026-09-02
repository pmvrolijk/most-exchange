<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import Modal from '../components/Modal.vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import { api } from '../api/client'
import { useCollection, useMutation, useResource } from '../api/collection'
import type { CommandResult, ExchangeStatus, ReopenResult, Security, Shard } from '../api/types'
import { at, price, parsePrice, priceInput, todayTradingDate } from '../format'

/**
 * The four commands that move a market.
 *
 * They were left out of the first pass deliberately rather than forgotten. Everything else in this
 * console edits a database row; these put bytes on a replicated log that a live exchange applies
 * without acknowledging, so the screen is built around three things the other views do not need:
 * what the exchange is *observed* to be doing right now, a confirmation that states the specific
 * consequence rather than asking "are you sure?", and an outcome that stays on screen because
 * `sent` without `confirmed` is a real state someone has to act on.
 */
const { rows: shards } = useCollection<Shard>('/shards')
const { rows: securities } = useCollection<Security>('/securities')
const { value: status, reload: reloadStatus } = useResource<ExchangeStatus>('/status')

const { busy, error: commandError, run } = useMutation()

/**
 * All four commands share one mutation, so each dialog clears the last one's refusal as it opens.
 * Without that, a purge that was refused would still be on screen under the confirmation for a
 * session transition — read as a reason not to proceed with a command nobody has attempted yet.
 */
function clearError() {
  commandError.value = null
}

/** Newest first, and never cleared automatically. */
const outcomes = ref<{ at: string; result: CommandResult }[]>([])

function record(results: CommandResult[]) {
  const stamp = new Date().toISOString()
  outcomes.value = [...results.map((result) => ({ at: stamp, result })), ...outcomes.value]
}

// Same five seconds as the scheduler's own reconciliation interval: the phase this shows is
// derived from the L3 feed, and refreshing faster would show states nobody can act on before they
// change again.
let timer: number | undefined
onMounted(() => {
  timer = window.setInterval(reloadStatus, 5000)
})
onUnmounted(() => window.clearInterval(timer))

function securitiesOf(shardId: number): Security[] {
  return securities.value.filter((s) => s.shardId === shardId)
}

function stateOf(securityId: number) {
  return status.value?.securities.find((s) => s.securityId === securityId) ?? null
}

const PHASES = ['CLOSED', 'PRE_OPEN', 'OPEN_AUCTION', 'CONTINUOUS'] as const

/* ---- session ---------------------------------------------------------------------------- */

const session = ref<{ shardId: number; phase: string } | null>(null)

const sessionMessage = computed(() => {
  const s = session.value
  if (!s) return ''
  const symbols = securitiesOf(s.shardId).map((x) => x.symbol).join(', ')
  const auction =
    s.phase === 'CONTINUOUS'
      ? ' The uncross runs only on the OPEN_AUCTION → CONTINUOUS transition, so going to CONTINUOUS from any other phase is accepted and silently skips the auction.'
      : ''
  return (
    `Shard ${s.shardId} moves to ${s.phase}. A session transition is shard-wide — there is no ` +
    `per-security session command — so this moves every book on it: ${symbols || 'none configured'}.${auction}`
  )
})

async function sendSession() {
  const s = session.value
  if (!s) return
  const ok = await run(async () => {
    const result = await api.post<CommandResult>(`/shards/${s.shardId}/session`, { phase: s.phase })
    record([result])
  })
  if (ok) {
    session.value = null
    await reloadStatus()
  }
}

/* ---- purge ------------------------------------------------------------------------------ */

const purge = ref<{ shardId: number; tradingDate: number } | null>(null)

async function sendPurge() {
  const p = purge.value
  if (!p) return
  const ok = await run(async () => {
    const result = await api.post<CommandResult>(`/shards/${p.shardId}/purge`, {
      tradingDate: p.tradingDate,
    })
    record([result])
  })
  if (ok) purge.value = null
}

/* ---- definition ------------------------------------------------------------------------- */

const definition = ref<{
  security: Security
  referencePrice: string
  staticCollarBps: string
  dynamicCollarBps: string
} | null>(null)

function defineFor(security: Security) {
  clearError()
  definition.value = {
    security,
    referencePrice: priceInput(security.referencePrice),
    staticCollarBps: security.staticCollarBps === null || security.staticCollarBps === undefined
      ? ''
      : String(security.staticCollarBps),
    dynamicCollarBps: security.dynamicCollarBps === null || security.dynamicCollarBps === undefined
      ? ''
      : String(security.dynamicCollarBps),
  }
}

async function sendDefinition() {
  const d = definition.value
  if (!d) return
  const ok = await run(async () => {
    // Omitted fields fall back to what the security carries, which is also where geometry comes
    // from: the engine rejects a definition disagreeing with the book it allocated, and rejects it
    // silently, so the caller never sends geometry of its own.
    const body: Record<string, number> = {}
    if (d.referencePrice.trim() !== '') body.referencePrice = parsePrice(d.referencePrice)
    if (d.staticCollarBps.trim() !== '') body.staticCollarBps = Number(d.staticCollarBps)
    if (d.dynamicCollarBps.trim() !== '') body.dynamicCollarBps = Number(d.dynamicCollarBps)
    const result = await api.post<CommandResult>(
      `/securities/${d.security.securityId}/definition`,
      body,
    )
    record([result])
  })
  if (ok) definition.value = null
}

/* ---- reopen ----------------------------------------------------------------------------- */

const reopen = ref<{ shardId: number; securityId: string; referencePrice: string } | null>(null)

const reopenMessage = computed(() => {
  const r = reopen.value
  if (!r) return ''
  const symbols = securitiesOf(r.shardId).map((x) => x.symbol).join(', ')
  return (
    `Re-seeds the definition, then walks PRE_OPEN → OPEN_AUCTION → CONTINUOUS on shard ${r.shardId}. ` +
    `The order matters: staticReference is reset only by an executing uncross, but orders are ` +
    `accepted from PRE_OPEN onward, so seeding afterwards is too late — the orders needed to reopen ` +
    `would be rejected by the stale collar. This reopens every book on the shard: ${symbols}.`
  )
})

async function sendReopen() {
  const r = reopen.value
  if (!r) return
  const ok = await run(async () => {
    const body: Record<string, number> = {}
    if (r.securityId !== '') body.securityId = Number(r.securityId)
    if (r.referencePrice.trim() !== '') body.referencePrice = parsePrice(r.referencePrice)
    const result = await api.post<ReopenResult>(`/shards/${r.shardId}/reopen`, body)
    record(result.steps)
    if (result.warning) {
      record([{ command: 'reopen', sent: true, confirmed: result.succeeded, detail: result.warning }])
    }
  })
  if (ok) {
    reopen.value = null
    await reloadStatus()
  }
}

/** Sent-and-confirmed, sent-and-unconfirmed, and never sent are three different colours. */
function outcomeClass(result: CommandResult): string {
  if (!result.sent) return 'failed'
  return result.confirmed ? 'ok' : 'pending'
}
</script>

<template>
  <h1>Operations</h1>
  <p class="lede">
    Live commands to a running exchange. They are <strong>not acknowledged</strong>: the engine
    applies or rejects a definition, a session transition or a purge without replying, so what the
    control plane can tell you is that the bytes were sent, and — for a phase — that it afterwards
    saw the change on the L3 feed. Nothing acknowledges a SecurityDefinition at all; a rejected one
    increments a counter on the engine and nothing else. Every command here is recorded in the
    <router-link to="/audit">audit</router-link> against the operator who asked for it.
  </p>

  <p v-if="commandError" class="error">{{ commandError }}</p>

  <div v-for="shard in shards" :key="shard.shardId" class="card">
    <div class="toolbar">
      <h2 style="font-size: 15px; margin: 0">Shard {{ shard.shardId }}</h2>
      <span class="spacer"></span>
      <button
        v-for="phase in PHASES"
        :key="phase"
        class="ghost small"
        @click="clearError(); session = { shardId: shard.shardId, phase }"
      >
        {{ phase }}
      </button>
      <button
        class="ghost small"
        @click="clearError(); purge = { shardId: shard.shardId, tradingDate: todayTradingDate() }"
      >
        Purge expired
      </button>
      <button
        class="danger small"
        @click="clearError(); reopen = { shardId: shard.shardId, securityId: '', referencePrice: '' }"
      >
        Reopen
      </button>
    </div>

    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>Symbol</th>
            <th class="num">Id</th>
            <th>Observed phase</th>
            <th>Since</th>
            <th>Halt</th>
            <th class="num">Seeded reference</th>
            <th class="num">Last trade</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="s in securitiesOf(shard.shardId)" :key="s.securityId">
            <td>{{ s.symbol }}</td>
            <td class="num">{{ s.securityId }}</td>
            <td>
              <span class="pill" :class="stateOf(s.securityId)?.phase === 'CONTINUOUS' ? 'good' : 'warn'">
                {{ stateOf(s.securityId)?.phase ?? 'unknown' }}
              </span>
            </td>
            <td>{{ at(stateOf(s.securityId)?.phaseAt) }}</td>
            <td>
              <span
                v-if="stateOf(s.securityId)?.halt && !stateOf(s.securityId)?.halt?.clearedAt"
                class="pill bad"
              >
                halted at {{ price(stateOf(s.securityId)?.halt?.attemptedPrice) }}
              </span>
              <span v-else>—</span>
            </td>
            <td class="num">{{ price(s.referencePrice) }}</td>
            <td class="num">{{ price(stateOf(s.securityId)?.lastTradePrice) }}</td>
            <td class="row-actions">
              <button class="ghost small" @click="defineFor(s)">Seed definition</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <p v-if="securitiesOf(shard.shardId).length === 0" class="empty">
      No securities on this shard.
    </p>
    <p v-else-if="!status?.securities.length" class="hint">
      No phase observed yet. A freshly booted engine has emitted no SessionChanged, so its phase is
      genuinely unknown rather than CLOSED — one session command establishes the baseline.
    </p>
  </div>

  <div v-if="outcomes.length" class="card">
    <h2 style="font-size: 15px; margin: 0 0 12px">Outcomes</h2>
    <div v-for="(entry, i) in outcomes" :key="i" class="outcome" :class="outcomeClass(entry.result)">
      <div class="command">
        {{ at(entry.at) }} · {{ entry.result.command }}
        <span class="pill" :class="entry.result.sent ? 'good' : 'bad'">
          {{ entry.result.sent ? 'sent' : 'not sent' }}
        </span>
        <span class="pill" :class="entry.result.confirmed ? 'good' : 'warn'">
          {{ entry.result.confirmed ? 'confirmed on the feed' : 'unconfirmed' }}
        </span>
      </div>
      <p class="detail">{{ entry.result.detail }}</p>
    </div>
  </div>

  <ConfirmDialog
    v-if="session"
    :title="`Session ${session.phase}`"
    :message="sessionMessage"
    confirm-label="Send transition"
    danger
    :busy="busy"
    :error="commandError"
    @confirm="sendSession"
    @close="session = null"
  />

  <ConfirmDialog
    v-if="purge"
    title="Purge expired orders"
    :message="`Removes every order on shard ${purge.shardId} whose expireDate is before the trading date below. Expiry is date-based only — a GTC order carries 0 and is never purged. Run it well before PRE_OPEN: it is a sequenced command and walks the price ladders of every book on the shard.`"
    confirm-label="Send purge"
    danger
    :busy="busy"
    :error="commandError"
    @confirm="sendPurge"
    @close="purge = null"
  >
    <label for="purge-date">Trading date (YYYYMMDD)</label>
    <input id="purge-date" v-model.number="purge.tradingDate" type="number" />
  </ConfirmDialog>

  <ConfirmDialog
    v-if="reopen"
    title="Reopen after a halt"
    :message="reopenMessage"
    confirm-label="Run the runbook"
    danger
    :busy="busy"
    :error="commandError"
    @confirm="sendReopen"
    @close="reopen = null"
  >
    <div class="form-grid">
      <div>
        <label for="reopen-sec">Re-seed which security</label>
        <select id="reopen-sec" v-model="reopen.securityId">
          <option value="">every security with a reference price</option>
          <option v-for="s in securitiesOf(reopen.shardId)" :key="s.securityId" :value="String(s.securityId)">
            {{ s.symbol }} ({{ s.securityId }})
          </option>
        </select>
      </div>
      <div>
        <label for="reopen-ref">New reference price</label>
        <input id="reopen-ref" v-model="reopen.referencePrice" class="mono" placeholder="leave blank to keep" />
        <p class="hint">A reference price belongs to one security, so it needs one chosen above.</p>
      </div>
    </div>
  </ConfirmDialog>

  <Modal
    v-if="definition"
    :title="`Seed definition · ${definition.security.symbol}`"
    submit-label="Send definition"
    danger
    :busy="busy"
    :error="commandError"
    @submit="sendDefinition"
    @close="definition = null"
  >
    <p class="lede" style="margin-top: 0">
      Seeds staticReference and dynamicReference and sets both collar widths. Geometry is taken
      from the database, never from this form: the engine rejects a definition whose floor, tick or
      level count disagrees with the book it already allocated, and it rejects it silently.
      <strong>Nothing confirms this command</strong> — the control plane checks the band fits inside
      the ladder before sending, because the engine's refusal would be invisible.
    </p>
    <div class="form-grid">
      <div>
        <label for="def-ref">Reference price</label>
        <input id="def-ref" v-model="definition.referencePrice" class="mono" />
      </div>
      <div>
        <label for="def-static">Static collar (bps)</label>
        <input id="def-static" v-model="definition.staticCollarBps" class="mono" />
        <p class="hint">Gates order acceptance: PRICE_OUT_OF_BOUNDS.</p>
      </div>
      <div>
        <label for="def-dynamic">Dynamic collar (bps)</label>
        <input id="def-dynamic" v-model="definition.dynamicCollarBps" class="mono" />
        <p class="hint">Gates execution per price level; a breach halts the security.</p>
      </div>
    </div>
  </Modal>
</template>
