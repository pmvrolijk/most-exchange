<script setup lang="ts">
import { ref } from 'vue'
import DataState from '../components/DataState.vue'
import Modal from '../components/Modal.vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import { api } from '../api/client'
import { useCollection, useMutation } from '../api/collection'
import type { Participant } from '../api/types'

const { rows, loading, error, reload } = useCollection<Participant>('/participants')
const { busy: saving, error: saveError, run: runSave } = useMutation()
const { busy: removing, error: removeError, run: runRemove } = useMutation()

const draft = ref<Participant | null>(null)
const editingId = ref<number | null>(null)
const deleting = ref<Participant | null>(null)

function create() {
  editingId.value = null
  saveError.value = null
  draft.value = { participantId: 0, name: '', smpId: 0, enabled: true }
}

function edit(p: Participant) {
  editingId.value = p.participantId
  saveError.value = null
  draft.value = { ...p }
}

async function submit() {
  const row = draft.value
  if (!row) return
  const ok = await runSave(async () => {
    if (editingId.value === null) await api.post<Participant>('/participants', row)
    else await api.put<Participant>(`/participants/${editingId.value}`, row)
  })
  if (ok) {
    draft.value = null
    await reload()
  }
}

async function confirmDelete() {
  const p = deleting.value
  if (!p) return
  const ok = await runRemove(() => api.del<void>(`/participants/${p.participantId}`))
  if (ok) {
    deleting.value = null
    await reload()
  }
}
</script>

<template>
  <h1>Participants</h1>
  <p class="lede">
    Authored here, not yet on the wire. The engine has an UNAUTHORIZED_PARTICIPANT reject reason
    that nothing currently raises; binding a participant to an authenticated session is an open
    item, and this table is where that binding will be looked up. An SMP id of 0 means "default to
    the participant id", matching SmpId on the wire — so two desks that must never trade with each
    other are given the same non-zero SMP id.
  </p>

  <div class="toolbar">
    <button @click="create">New participant</button>
  </div>

  <div class="card">
    <DataState :loading="loading" :error="error" :empty="rows.length === 0">
      <template #empty>No participants recorded.</template>
      <table>
        <thead>
          <tr>
            <th class="num">Id</th>
            <th>Name</th>
            <th class="num">SMP id</th>
            <th>State</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="p in rows" :key="p.participantId">
            <td class="num">{{ p.participantId }}</td>
            <td>{{ p.name }}</td>
            <td class="num">{{ p.smpId === 0 ? `${p.participantId} (default)` : p.smpId }}</td>
            <td>
              <span class="pill" :class="p.enabled ? 'good' : 'bad'">
                {{ p.enabled ? 'enabled' : 'disabled' }}
              </span>
            </td>
            <td class="row-actions">
              <button class="ghost small" @click="edit(p)">Edit</button>
              <button class="ghost small" @click="deleting = p">Delete</button>
            </td>
          </tr>
        </tbody>
      </table>
    </DataState>
  </div>

  <Modal
    v-if="draft"
    :title="editingId === null ? 'New participant' : `Participant ${editingId}`"
    :busy="saving"
    :error="saveError"
    @submit="submit"
    @close="draft = null"
  >
    <div class="form-grid">
      <div>
        <label for="p-id">Participant id</label>
        <input id="p-id" v-model.number="draft.participantId" type="number" :disabled="editingId !== null" />
      </div>
      <div>
        <label for="p-smp">SMP id</label>
        <input id="p-smp" v-model.number="draft.smpId" type="number" />
        <p class="hint">0 defaults to the participant id.</p>
      </div>
      <div class="wide">
        <label for="p-name">Name</label>
        <input id="p-name" v-model="draft.name" />
      </div>
      <div class="wide">
        <label>
          <input type="checkbox" v-model="draft.enabled" />
          Enabled
        </label>
        <p class="hint">
          Nothing on the wire reads this yet: the gateway infers a participant from traffic rather
          than from an authenticated session, so disabling one here does not stop it trading.
        </p>
      </div>
    </div>
  </Modal>

  <ConfirmDialog
    v-if="deleting"
    title="Delete participant"
    :message="`${deleting.name} (${deleting.participantId}) is removed. Orders already resting on a book carry a participant id the engine does not look up, so nothing live is affected.`"
    confirm-label="Delete"
    danger
    :busy="removing"
    :error="removeError"
    @confirm="confirmDelete"
    @close="deleting = null"
  />
</template>
