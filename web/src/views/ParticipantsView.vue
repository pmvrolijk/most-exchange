<script setup lang="ts">
import AsyncTable from '../components/AsyncTable.vue'
import type { Participant } from '../api/types'
</script>

<template>
  <h1>Participants</h1>
  <p class="lede">
    Authored here, not yet on the wire. The engine has an UNAUTHORIZED_PARTICIPANT reject reason
    that nothing currently raises; binding a participant to an authenticated session is an open
    item, and this table is where that binding will be looked up. An SMP id of 0 means "default to
    the participant id", matching SmpId on the wire.
  </p>

  <AsyncTable path="/participants" v-slot="{ rows }">
    <table>
      <thead>
        <tr>
          <th class="num">Id</th>
          <th>Name</th>
          <th class="num">SMP id</th>
          <th>State</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="p in (rows as Participant[])" :key="p.participantId">
          <td class="num">{{ p.participantId }}</td>
          <td>{{ p.name }}</td>
          <td class="num">{{ p.smpId === 0 ? `${p.participantId} (default)` : p.smpId }}</td>
          <td>
            <span class="pill" :class="p.enabled ? 'good' : 'bad'">
              {{ p.enabled ? 'enabled' : 'disabled' }}
            </span>
          </td>
        </tr>
      </tbody>
    </table>
  </AsyncTable>
</template>
