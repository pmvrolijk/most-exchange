<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { api, ApiError } from '../api/client'
import type { ExchangeStatus, TopologyView } from '../api/types'
import { at, price, qty } from '../format'

const status = ref<ExchangeStatus | null>(null)
const topology = ref<TopologyView | null>(null)
const error = ref<string | null>(null)

async function load() {
  try {
    const [s, t] = await Promise.all([
      api.get<ExchangeStatus>('/status'),
      api.get<TopologyView>('/topology'),
    ])
    status.value = s
    topology.value = t
    error.value = null
  } catch (e) {
    error.value = e instanceof ApiError ? `${e.code}: ${e.message}` : String(e)
  }
}

// The status is derived from a live multicast feed, so it moves on its own. Five seconds is the
// scheduler's own reconciliation interval; refreshing faster would show states no operator can act
// on before they change again.
let timer: number | undefined
onMounted(() => {
  load()
  timer = window.setInterval(load, 5000)
})
onUnmounted(() => window.clearInterval(timer))
</script>

<template>
  <h1>Status</h1>
  <p class="lede">
    What the exchange is actually doing, as opposed to what the database says it should be. This is
    derived from the L3 book event feed — a volatility halt appears nowhere else, so a depth
    subscriber cannot distinguish one from a scheduled close.
  </p>

  <p v-if="error" class="error">{{ error }}</p>

  <div v-if="status" class="card">
    <p>
      <span class="pill" :class="status.connected ? 'good' : 'bad'">
        {{ status.connected ? 'connected' : 'not connected' }}
      </span>
      {{ status.detail }}
    </p>
    <table>
      <tbody>
        <tr>
          <th>Events seen</th>
          <td class="num">{{ status.eventsSeen.toLocaleString() }}</td>
          <th>Feed gaps</th>
          <td class="num" :class="{ problem: status.feedGaps > 0 }">{{ status.feedGaps }}</td>
          <th>Events missed</th>
          <td class="num" :class="{ problem: status.eventsMissed > 0 }">{{ status.eventsMissed }}</td>
        </tr>
      </tbody>
    </table>
    <!-- Gaps are expected, not exceptional: multicast flow control is governed by the fastest
         receiver on purpose, so a slow subscriber takes an unrecoverable gap rather than throttling
         the publisher. The count is here so a persistent one is visible. -->
    <p v-if="status.routingDrift.length" class="problem">
      Routing drift: {{ status.routingDrift.join('; ') }}
    </p>
  </div>

  <div v-if="status?.directory" class="card">
    <h1 style="font-size: 15px">Directory</h1>
    <table>
      <tbody>
        <tr>
          <th>Universe version</th><td class="mono">{{ status.directory.version }}</td>
          <th>Securities</th><td class="num">{{ status.directory.securities }}</td>
          <th>Shards</th><td class="mono">{{ status.directory.shards.join(', ') }}</td>
          <th>Last seen</th><td>{{ at(status.directory.lastSeenAt) }}</td>
        </tr>
      </tbody>
    </table>
    <p v-if="status.directory.incompleteBroadcasts > 0" class="problem">
      {{ status.directory.incompleteBroadcasts }} incomplete broadcast cycle(s) — a truncated cycle
      is staged and discarded rather than replacing a good routing table.
    </p>
  </div>

  <div v-if="status" class="card">
    <h1 style="font-size: 15px">Books</h1>
    <p v-if="status.securities.length === 0" class="empty">
      No phase seen yet. A freshly booted engine has emitted no SessionChanged, so its phase is
      genuinely unknown here — send one session command to establish a baseline.
    </p>
    <div v-else class="table-wrap">
      <table>
        <thead>
          <tr>
            <th class="num">Security</th>
            <th class="num">Shard</th>
            <th>Phase</th>
            <th>Since</th>
            <th>Halt</th>
            <th class="num">Last trade</th>
            <th class="num">Qty</th>
            <th class="num">Last uncross</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="s in status.securities" :key="s.securityId">
            <td class="num">{{ s.securityId }}</td>
            <td class="num">{{ s.shardId }}</td>
            <td>
              <span class="pill" :class="s.phase === 'CONTINUOUS' ? 'good' : 'warn'">
                {{ s.phase ?? 'unknown' }}
              </span>
            </td>
            <td>{{ at(s.phaseAt) }}</td>
            <td>
              <span v-if="s.halt && !s.halt.clearedAt" class="pill bad">
                halted at {{ price(s.halt.attemptedPrice) }} vs {{ price(s.halt.collarReference) }}
              </span>
              <span v-else-if="s.halt" class="pill">cleared {{ at(s.halt.clearedAt) }}</span>
              <span v-else>—</span>
            </td>
            <td class="num">{{ price(s.lastTradePrice) }}</td>
            <td class="num">{{ qty(s.lastTradeQty) }}</td>
            <td class="num">{{ price(s.lastUncrossPrice) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>

  <div v-if="topology" class="card">
    <h1 style="font-size: 15px">Draft topology</h1>
    <p class="lede" style="margin-bottom: 12px">
      The database's view, not the running exchange's. An empty problem list is exactly the
      condition for publishing a release.
    </p>
    <ul v-if="topology.problems.length" class="problem">
      <li v-for="p in topology.problems" :key="p">{{ p }}</li>
    </ul>
    <p v-else class="pill good">ready to publish (universe version {{ topology.universeVersion }})</p>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th class="num">Shard</th><th class="num">Securities</th>
            <th>Fingerprint</th><th>Problem</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="v in topology.shards" :key="v.shard.shardId">
            <td class="num">{{ v.shard.shardId }}</td>
            <td class="num">{{ v.securities.length }}</td>
            <td class="mono">{{ v.fingerprint ?? '—' }}</td>
            <td class="problem">{{ v.problem ?? '' }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
