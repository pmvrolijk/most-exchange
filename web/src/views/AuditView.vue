<script setup lang="ts">
import AsyncTable from '../components/AsyncTable.vue'
import type { AuditEntry } from '../api/types'
import { at } from '../format'
</script>

<template>
  <h1>Audit</h1>
  <p class="lede">
    Every market-moving command and who asked for it. <strong>Sent is not applied</strong>: operator
    commands are unacknowledged by design — the engine applies or rejects a definition, a session
    transition or a purge without replying — so a row saying sent claims only that the bytes left
    for the gateway. Unattended transitions are the scheduler's and are recorded in its own run log
    instead, with the reasons it skipped.
  </p>

  <AsyncTable path="/audit?limit=200" >
    <template #default="{ rows }">
    <table>
      <thead>
        <tr>
          <th>At</th>
          <th>Operator</th>
          <th>Action</th>
          <th>Target</th>
          <th>Sent</th>
          <th>Detail</th>
          <th>From</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="e in (rows as AuditEntry[])" :key="e.id">
          <td>{{ at(e.at) }}</td>
          <td>{{ e.username }}</td>
          <td>{{ e.action }}</td>
          <td class="mono">{{ e.target ?? '—' }}</td>
          <td>
            <span class="pill" :class="e.sent ? 'good' : 'bad'">
              {{ e.sent ? 'sent' : 'not sent' }}
            </span>
          </td>
          <td>{{ e.detail ?? '—' }}</td>
          <td class="mono">{{ e.remoteAddr ?? '—' }}</td>
        </tr>
      </tbody>
    </table>
    </template>
    <template #empty>No operator commands recorded.</template>
  </AsyncTable>
</template>
