<script setup lang="ts">
import { computed, ref } from 'vue'
import DataState from '../components/DataState.vue'
import Modal from '../components/Modal.vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import { api } from '../api/client'
import { useCollection, useMutation } from '../api/collection'
import type { Security, Shard } from '../api/types'
import { price, parsePrice, priceInput } from '../format'

const { rows, loading, error, reload } = useCollection<Security>('/securities')
const { rows: shards } = useCollection<Shard>('/shards')
const { busy: saving, error: saveError, run: runSave } = useMutation()
const { busy: removing, error: removeError, run: runRemove } = useMutation()

/**
 * Prices are held as the strings the operator typed and converted once, on submit.
 *
 * `v-model.number` on a price field would put an IEEE 754 double between the keyboard and an int64
 * with eight implied decimals, which is precisely where a tick size ends up one unit out.
 */
interface Draft {
  securityId: number
  shardId: number
  symbol: string
  isin: string
  name: string
  currency: string
  priceFloor: string
  tickSize: string
  levelCount: number
  maxOrders: number
  referencePrice: string
  staticCollarBps: string
  dynamicCollarBps: string
}

function blank(): Draft {
  return {
    securityId: 0,
    shardId: shards.value[0]?.shardId ?? 0,
    symbol: '',
    isin: '',
    name: '',
    currency: 'EUR',
    priceFloor: '',
    tickSize: '',
    levelCount: 0,
    maxOrders: 0,
    referencePrice: '',
    staticCollarBps: '',
    dynamicCollarBps: '',
  }
}

function toDraft(s: Security): Draft {
  return {
    ...s,
    priceFloor: priceInput(s.priceFloor),
    tickSize: priceInput(s.tickSize),
    referencePrice: priceInput(s.referencePrice),
    staticCollarBps: s.staticCollarBps === null || s.staticCollarBps === undefined ? '' : String(s.staticCollarBps),
    dynamicCollarBps: s.dynamicCollarBps === null || s.dynamicCollarBps === undefined ? '' : String(s.dynamicCollarBps),
  }
}

const draft = ref<Draft | null>(null)
const editingId = ref<number | null>(null)
const deleting = ref<Security | null>(null)

/**
 * The top of the ladder, shown while the geometry is being typed.
 *
 * The ladder is a flat array allocated at boot, and §3.2's invariant is that it must be strictly
 * wider than the static circuit-breaker band so band rejection always fires before ladder
 * overflow. An operator setting a floor, a tick and a level count is choosing that range without
 * ever being shown it; this shows it.
 */
const ceiling = computed(() => {
  const d = draft.value
  if (!d) return null
  try {
    const floor = parsePrice(d.priceFloor)
    const tick = parsePrice(d.tickSize)
    if (d.levelCount < 1) return null
    return floor + tick * (d.levelCount - 1)
  } catch {
    return null
  }
})

function optionalPrice(text: string): number | null {
  return text.trim() === '' ? null : parsePrice(text)
}

function optionalInt(text: string): number | null {
  if (text.trim() === '') return null
  const value = Number(text)
  if (!Number.isInteger(value)) throw new Error(`not a whole number of basis points: "${text}"`)
  return value
}

function create() {
  editingId.value = null
  saveError.value = null
  draft.value = blank()
}

function edit(security: Security) {
  editingId.value = security.securityId
  saveError.value = null
  draft.value = toDraft(security)
}

async function submit() {
  const d = draft.value
  if (!d) return
  const ok = await runSave(async () => {
    // Parsing before the request, so a malformed price is reported against the field rather than
    // arriving at the API as a NaN.
    const body: Security = {
      securityId: d.securityId,
      shardId: d.shardId,
      symbol: d.symbol,
      isin: d.isin,
      name: d.name,
      currency: d.currency,
      priceFloor: parsePrice(d.priceFloor),
      tickSize: parsePrice(d.tickSize),
      levelCount: d.levelCount,
      maxOrders: d.maxOrders,
      referencePrice: optionalPrice(d.referencePrice),
      staticCollarBps: optionalInt(d.staticCollarBps),
      dynamicCollarBps: optionalInt(d.dynamicCollarBps),
    }
    if (editingId.value === null) await api.post<Security>('/securities', body)
    else await api.put<Security>(`/securities/${editingId.value}`, body)
  })
  if (ok) {
    draft.value = null
    await reload()
  }
}

async function confirmDelete() {
  const s = deleting.value
  if (!s) return
  const ok = await runRemove(() => api.del<void>(`/securities/${s.securityId}`))
  if (ok) {
    deleting.value = null
    await reload()
  }
}
</script>

<template>
  <h1>Securities</h1>
  <p class="lede">
    Geometry — the floor, tick and ladder size — is published in the shard's security file and read
    at boot. Reference price and collars are not: they arrive at runtime as SecurityDefinition
    commands through the replicated log, so what is shown here is what an operator seeded, not what
    the engine currently holds. Changing either only edits the draft; geometry reaches a node
    through a published release and a restart, collars through
    <router-link to="/operations">Operations</router-link>.
  </p>

  <div class="toolbar">
    <button @click="create" :disabled="shards.length === 0">New security</button>
    <span v-if="shards.length === 0" class="hint">Define a shard first — a security has to be hosted somewhere.</span>
  </div>

  <div class="card">
    <DataState :loading="loading" :error="error" :empty="rows.length === 0">
      <template #empty>No securities. Add one, or import a shard security file from Releases.</template>
      <table>
        <thead>
          <tr>
            <th class="num">Id</th>
            <th>Symbol</th>
            <th>ISIN</th>
            <th>Name</th>
            <th>Ccy</th>
            <th class="num">Shard</th>
            <th class="num">Floor</th>
            <th class="num">Tick</th>
            <th class="num">Levels</th>
            <th class="num">Max orders</th>
            <th class="num">Reference</th>
            <th class="num">Static bps</th>
            <th class="num">Dynamic bps</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="s in rows" :key="s.securityId">
            <td class="num">{{ s.securityId }}</td>
            <td>{{ s.symbol }}</td>
            <td class="mono">{{ s.isin }}</td>
            <td>{{ s.name }}</td>
            <td>{{ s.currency }}</td>
            <td class="num">{{ s.shardId }}</td>
            <td class="num">{{ price(s.priceFloor) }}</td>
            <td class="num">{{ price(s.tickSize) }}</td>
            <td class="num">{{ s.levelCount.toLocaleString() }}</td>
            <td class="num">{{ s.maxOrders.toLocaleString() }}</td>
            <td class="num">{{ price(s.referencePrice) }}</td>
            <td class="num">{{ s.staticCollarBps ?? '—' }}</td>
            <td class="num">{{ s.dynamicCollarBps ?? '—' }}</td>
            <td class="row-actions">
              <button class="ghost small" @click="edit(s)">Edit</button>
              <button class="ghost small" @click="deleting = s">Delete</button>
            </td>
          </tr>
        </tbody>
      </table>
    </DataState>
  </div>

  <Modal
    v-if="draft"
    :title="editingId === null ? 'New security' : `${draft.symbol || 'Security'} ${editingId}`"
    :busy="saving"
    :error="saveError"
    @submit="submit"
    @close="draft = null"
  >
    <div class="form-grid">
      <div>
        <label for="sec-id">Security id</label>
        <input id="sec-id" v-model.number="draft.securityId" type="number" :disabled="editingId !== null" />
      </div>
      <div>
        <label for="sec-shard">Shard</label>
        <select id="sec-shard" v-model.number="draft.shardId">
          <option v-for="s in shards" :key="s.shardId" :value="s.shardId">shard {{ s.shardId }}</option>
        </select>
        <p class="hint">One shard per security, at most ten securities per shard.</p>
      </div>
      <div>
        <label for="sec-symbol">Symbol</label>
        <input id="sec-symbol" v-model="draft.symbol" />
      </div>
      <div>
        <label for="sec-isin">ISIN</label>
        <input id="sec-isin" v-model="draft.isin" class="mono" />
        <p class="hint">Check digit validated by the same code the processes run at boot.</p>
      </div>
      <div>
        <label for="sec-name">Name</label>
        <input id="sec-name" v-model="draft.name" />
      </div>
      <div>
        <label for="sec-ccy">Currency</label>
        <input id="sec-ccy" v-model="draft.currency" />
      </div>

      <div>
        <label for="sec-floor">Price floor</label>
        <input id="sec-floor" v-model="draft.priceFloor" class="mono" placeholder="10.00" />
      </div>
      <div>
        <label for="sec-tick">Tick size</label>
        <input id="sec-tick" v-model="draft.tickSize" class="mono" placeholder="0.01" />
      </div>
      <div>
        <label for="sec-levels">Ladder levels</label>
        <input id="sec-levels" v-model.number="draft.levelCount" type="number" />
      </div>
      <div>
        <label for="sec-max">Max orders</label>
        <input id="sec-max" v-model.number="draft.maxOrders" type="number" />
        <p class="hint">Rejects with BOOK_CAPACITY above the high-water mark; at most 1M.</p>
      </div>
      <p v-if="ceiling !== null" class="hint wide">
        Ladder spans {{ price(parsePrice(draft.priceFloor)) }} … {{ price(ceiling) }}. It must stay
        strictly wider than the static collar band around the reference price, so a price outside
        the band is rejected before it can overflow the ladder.
      </p>

      <div>
        <label for="sec-ref">Reference price</label>
        <input id="sec-ref" v-model="draft.referencePrice" class="mono" placeholder="optional" />
      </div>
      <div>
        <label for="sec-static">Static collar (bps)</label>
        <input id="sec-static" v-model="draft.staticCollarBps" class="mono" placeholder="optional" />
      </div>
      <div>
        <label for="sec-dynamic">Dynamic collar (bps)</label>
        <input id="sec-dynamic" v-model="draft.dynamicCollarBps" class="mono" placeholder="optional" />
      </div>
      <p class="hint wide">
        These three are not geometry and are outside the fingerprint. Saving them here stores what
        a definition would carry; the engine learns them only when one is sent from Operations.
      </p>
    </div>
  </Modal>

  <ConfirmDialog
    v-if="deleting"
    title="Delete security"
    :message="`${deleting.symbol} (${deleting.securityId}) is removed from the draft topology. A running engine keeps the book it already allocated — this changes what the next release publishes, nothing that is live.`"
    confirm-label="Delete"
    danger
    :busy="removing"
    :error="removeError"
    @confirm="confirmDelete"
    @close="deleting = null"
  />
</template>
