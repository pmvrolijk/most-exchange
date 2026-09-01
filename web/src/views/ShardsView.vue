<script setup lang="ts">
import AsyncTable from '../components/AsyncTable.vue'
import type { Shard } from '../api/types'
</script>

<template>
  <h1>Shards</h1>
  <p class="lede">
    A shard is one Aeron Cluster and one gateway serving at most ten securities. The channels below
    are the <em>gateway's</em> client endpoints, not the cluster's ingress — an adapter connecting
    to the cluster directly would bypass validation and cumQty reconstruction.
  </p>

  <AsyncTable path="/shards" v-slot="{ rows }">
    <table>
      <thead>
        <tr>
          <th class="num">Shard</th>
          <th>Order entry</th>
          <th class="num">Stream</th>
          <th>Execution reports</th>
          <th class="num">Stream</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="s in (rows as Shard[])" :key="s.shardId">
          <td class="num">{{ s.shardId }}</td>
          <td class="mono">{{ s.orderEntryChannel }}</td>
          <td class="num">{{ s.orderEntryStreamId }}</td>
          <td class="mono">{{ s.executionReportChannel }}</td>
          <td class="num">{{ s.executionReportStreamId }}</td>
        </tr>
      </tbody>
    </table>
  </AsyncTable>
</template>
