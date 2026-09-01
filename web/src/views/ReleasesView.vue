<script setup lang="ts">
import AsyncTable from '../components/AsyncTable.vue'
import type { Release } from '../api/types'
import { at } from '../format'
</script>

<template>
  <h1>Releases</h1>
  <p class="lede">
    A release is an immutable numbered set of the same properties files the engine, gateway,
    market-data and discovery processes boot from. Republishing allocates the next version rather
    than editing one, so a directory a node booted from cannot change underneath it. The
    fingerprint is the value every process prints at startup: two nodes showing different ones have
    different geometry, which is the one misconfiguration consensus cannot catch.
  </p>

  <AsyncTable path="/releases" >
    <template #default="{ rows }">
    <table>
      <thead>
        <tr>
          <th class="num">Version</th>
          <th>Published</th>
          <th class="num">Universe</th>
          <th>Directory</th>
          <th>Fingerprints</th>
          <th>Note</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="r in (rows as Release[])" :key="r.version">
          <td class="num">{{ r.version }}</td>
          <td>{{ at(r.createdAt) }}</td>
          <td class="num">{{ r.universeVersion }}</td>
          <td class="mono">{{ r.directory }}</td>
          <td class="mono">
            <span v-for="(fp, shard) in r.fingerprints" :key="shard">
              shard {{ shard }}: {{ fp }}<br />
            </span>
          </td>
          <td>{{ r.note ?? '—' }}</td>
        </tr>
      </tbody>
    </table>
    </template>
    <template #empty>Nothing published yet.</template>
  </AsyncTable>
</template>
