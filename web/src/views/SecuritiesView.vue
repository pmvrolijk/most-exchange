<script setup lang="ts">
import AsyncTable from '../components/AsyncTable.vue'
import type { Security } from '../api/types'
import { price } from '../format'
</script>

<template>
  <h1>Securities</h1>
  <p class="lede">
    Geometry — the floor, tick and ladder size — is published in the shard's security file and read
    at boot. Reference price and collars are not: they arrive at runtime as SecurityDefinition
    commands through the replicated log, so what is shown here is what an operator seeded, not what
    the engine currently holds.
  </p>

  <AsyncTable path="/securities" v-slot="{ rows }">
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
        </tr>
      </thead>
      <tbody>
        <tr v-for="s in (rows as Security[])" :key="s.securityId">
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
        </tr>
      </tbody>
    </table>
  </AsyncTable>
</template>
