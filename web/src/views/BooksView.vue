<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useCollection, useResource } from '../api/collection'
import { useBooks } from '../api/books'
import type { BookImage, DepthFeedStatus, Security } from '../api/types'
import { at, price, qty } from '../format'

const { rows: securities } = useCollection<Security>('/securities')
const { value: feed, reload: reloadFeed } = useResource<DepthFeedStatus>('/books/status')
const { books, live, error } = useBooks()

const selected = ref<number | null>(null)

/** Longer than a snapshot cycle and several publish intervals: a quiet market is not a dead feed. */
const stale = computed(() => (feed.value?.silentMs ?? 0) > 5000)

// Feed health is polled rather than streamed: it is a handful of counters, and a silence detector
// that only ran when a message arrived would be unable to detect silence.
let health: number | undefined
onMounted(() => {
  health = window.setInterval(reloadFeed, 2000)
})
onUnmounted(() => window.clearInterval(health))

/**
 * Every security the console knows about, whether or not the feed has reached it yet.
 *
 * Taken from the database rather than from the feed, so a security that is configured but silent
 * still appears — and says why. Listing only what the feed has mentioned would quietly hide the
 * case an operator most needs to see: a book that is not arriving at all.
 */
const tabs = computed(() =>
  securities.value.map((s) => ({
    securityId: s.securityId,
    symbol: s.symbol,
    image: books.value[s.securityId] ?? null,
  })),
)

watch(tabs, (list) => {
  if (selected.value === null && list.length > 0) selected.value = list[0].securityId
})

const book = computed<BookImage | null>(() =>
  selected.value === null ? null : (books.value[selected.value] ?? null),
)

const symbol = computed(
  () => tabs.value.find((t) => t.securityId === selected.value)?.symbol ?? `security ${selected.value}`,
)

/** The deepest level on either side, so both ladders are drawn to one scale. */
const scale = computed(() => {
  const b = book.value
  if (!b) return 1
  return Math.max(1, ...b.bids.map((l) => l.qty), ...b.asks.map((l) => l.qty))
})

function bar(value: number): string {
  return `${Math.round((value / scale.value) * 100)}%`
}

/** Rows are paired so bid and ask at the same rank sit on one line, as a ladder reads. */
const rows = computed(() => {
  const b = book.value
  if (!b) return []
  const depth = Math.max(b.bids.length, b.asks.length)
  return Array.from({ length: depth }, (_, i) => ({ bid: b.bids[i] ?? null, ask: b.asks[i] ?? null }))
})
</script>

<template>
  <h1>Books</h1>
  <p class="lede">
    Depth as the exchange is publishing it, rebuilt from the L2 feed and its periodic snapshot by
    the same assembler the CLI and any other consumer use. The control plane conflates: a feed can
    carry 100k updates a second, so what arrives here is an image four times a second with
    everything in between folded in. A book that is <em>not synchronised</em> shows no depth at all
    rather than a stale ladder — waiting for a snapshot and having no liquidity are different
    things and must not look the same.
  </p>

  <div class="toolbar">
    <span class="pill" :class="live ? 'good' : 'warn'">{{ live ? 'live' : 'polling' }}</span>
    <span v-if="feed" class="hint">
      {{ feed.synchronised }} of {{ feed.securities.length }} books synchronised ·
      {{ feed.snapshotsApplied }} snapshots applied
      <template v-if="feed.gapsDetected > 0">
        · {{ feed.gapsDetected }} gaps, {{ feed.desynchronisations }} books dropped and rebuilt
      </template>
    </span>
    <!-- Subscribed and silent is a real state: a media driver can die with every object on the
         server still looking healthy. Said in seconds rather than left to be inferred from books
         that quietly stop moving. -->
    <span v-if="stale" class="pill bad">no feed data for {{ Math.round((feed?.silentMs ?? 0) / 1000) }}s</span>
  </div>
  <p v-if="error" class="error">{{ error }}</p>

  <div class="toolbar">
    <button
      v-for="tab in tabs"
      :key="tab.securityId"
      class="small"
      :class="tab.securityId === selected ? '' : 'ghost'"
      @click="selected = tab.securityId"
    >
      {{ tab.symbol }}
      <span v-if="!tab.image" class="dot warn" title="no data on the feed yet"></span>
      <span v-else-if="!tab.image.synchronised" class="dot bad" title="not synchronised"></span>
    </button>
  </div>

  <div v-if="selected === null" class="card">
    <p class="empty">No securities configured.</p>
  </div>

  <div v-else-if="!book" class="card">
    <p class="empty">
      Nothing for {{ symbol }} on the feed yet. The control plane subscribes to L2 and the snapshot
      stream; if this stays empty, check that market data is running and that
      <code>control.l2</code> and <code>control.snapshot</code> point at it.
    </p>
  </div>

  <div v-else-if="!book.synchronised" class="card">
    <p class="problem">
      {{ symbol }} is <strong>{{ book.state === 'BUILDING' ? 'receiving a snapshot' : 'not synchronised' }}</strong>.
    </p>
    <p class="lede" style="margin-bottom: 0">
      No depth is shown on purpose. Either no snapshot has arrived yet — one full cycle is the
      longest this should take — or a gap invalidated the aggregates and the book was withdrawn
      until the next image rebuilds it. Drawing the last good ladder here would be showing a book
      that is no longer true.
    </p>
    <p v-if="book.lastTradePrice !== null" class="hint">
      Last trade {{ price(book.lastTradePrice) }} × {{ qty(book.lastTradeQty) }} — a trade that
      printed stays true whatever happened to the depth.
    </p>
  </div>

  <div v-else class="card">
    <div class="book-head">
      <div>
        <span class="book-symbol">{{ symbol }}</span>
        <!-- The phase belongs next to the ladder, not on another screen: a halted security keeps a
             full, correct book, and one shown on its own looks exactly like a trading one. -->
        <span class="pill" :class="book.halted ? 'bad' : book.phase === 'CONTINUOUS' ? 'good' : 'warn'">
          {{ book.halted ? 'halted' : (book.phase ?? 'phase unknown') }}
        </span>
        <span class="hint">security {{ book.securityId }}<template v-if="book.shardId !== null"> · shard {{ book.shardId }}</template></span>
      </div>
      <div class="book-stats">
        <span><label>bid</label>{{ price(book.bestBid) }}</span>
        <span><label>ask</label>{{ price(book.bestAsk) }}</span>
        <span><label>spread</label>{{ price(book.spread) }}</span>
        <span><label>last</label>{{ price(book.lastTradePrice) }}
          <template v-if="book.lastTradePrice !== null">× {{ qty(book.lastTradeQty) }}</template>
        </span>
      </div>
    </div>

    <div v-if="rows.length" class="table-wrap">
      <table class="ladder">
        <thead>
          <tr>
            <th class="num">orders</th>
            <th class="num">bid qty</th>
            <th class="num">bid</th>
            <th class="num">ask</th>
            <th class="num">ask qty</th>
            <th class="num">orders</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, i) in rows" :key="i">
            <td class="num muted">{{ row.bid ? row.bid.orders : '' }}</td>
            <td class="num bid-cell">
              <span v-if="row.bid" class="depth bid" :style="{ width: bar(row.bid.qty) }"></span>
              <span class="value">{{ row.bid ? qty(row.bid.qty) : '' }}</span>
            </td>
            <td class="num bid-price">{{ row.bid ? price(row.bid.price) : '' }}</td>
            <td class="num ask-price">{{ row.ask ? price(row.ask.price) : '' }}</td>
            <td class="num ask-cell">
              <span v-if="row.ask" class="depth ask" :style="{ width: bar(row.ask.qty) }"></span>
              <span class="value">{{ row.ask ? qty(row.ask.qty) : '' }}</span>
            </td>
            <td class="num muted">{{ row.ask ? row.ask.orders : '' }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <p v-else class="empty">
      The book is empty. This is a synchronised, genuinely empty book — not a missing one.
    </p>

    <p class="hint">
      Showing {{ book.bids.length }} of {{ book.bidLevelsTotal }} bid levels and
      {{ book.asks.length }} of {{ book.askLevelsTotal }} ask levels · image {{ book.version }} at
      {{ at(book.at) }}
    </p>
  </div>
</template>
