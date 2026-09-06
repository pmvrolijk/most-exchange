<script setup lang="ts">
import { ref } from 'vue'
import DataState from '../components/DataState.vue'
import Modal from '../components/Modal.vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import { api } from '../api/client'
import { useCollection, useMutation } from '../api/collection'
import type { Gateway, GatewaySecretIssued, Participant } from '../api/types'

const { rows, loading, error, reload } = useCollection<Gateway>('/gateways')
const participants = useCollection<Participant>('/participants')
const { busy: saving, error: saveError, run: runSave } = useMutation()
const { busy: removing, error: removeError, run: runRemove } = useMutation()

const draft = ref<Gateway | null>(null)
const editingId = ref<string | null>(null)
const deleting = ref<Gateway | null>(null)
const rotating = ref<Gateway | null>(null)

/**
 * Shown exactly once. Only the SHA-256 is stored — the cluster verifies that digest against what a
 * gateway presents, so it has to be reproducible and therefore cannot be a one-way password hash.
 * There is no second chance to read this, which is the honest consequence.
 */
const issued = ref<GatewaySecretIssued | null>(null)

function create() {
  editingId.value = null
  saveError.value = null
  draft.value = { gatewayId: '', shardId: 0, enabled: true, participants: [] }
}

function edit(g: Gateway) {
  editingId.value = g.gatewayId
  saveError.value = null
  draft.value = { ...g, participants: [...g.participants] }
}

async function submit() {
  const row = draft.value
  if (!row) return
  const ok = await runSave(async () => {
    if (editingId.value === null) {
      issued.value = await api.post<GatewaySecretIssued>('/gateways', row)
    } else {
      await api.put<Gateway>(`/gateways/${editingId.value}`, row)
    }
  })
  if (ok) {
    draft.value = null
    await reload()
  }
}

async function confirmRotate() {
  const g = rotating.value
  if (!g) return
  const ok = await runSave(async () => {
    issued.value = await api.put<GatewaySecretIssued>(`/gateways/${g.gatewayId}/secret`, {})
  })
  if (ok) rotating.value = null
}

async function confirmDelete() {
  const g = deleting.value
  if (!g) return
  const ok = await runRemove(() => api.del<void>(`/gateways/${g.gatewayId}`))
  if (ok) {
    deleting.value = null
    await reload()
  }
}
</script>

<template>
  <h1>Gateways</h1>
  <p class="lede">
    Which gateway process speaks for which participant. The consensus module authenticates a
    connecting gateway against this, stamps its id on the session as the encoded principal, and the
    engine binds every one of that gateway's participants at session open — so a participant that
    has been quiet since its gateway connected is still sent its own fills. A participant belongs to
    at most one gateway; two claims on one would be settled by whichever session happened to open
    last.
  </p>
  <p class="lede">
    Publishing a release renders this as each shard's participants file. The nodes re-read it while
    they run, so a change here costs a gateway restart rather than a node restart — and a gateway
    holds no state, so restarting one loses nothing.
  </p>

  <div class="toolbar">
    <button @click="create">New gateway</button>
  </div>

  <div class="card">
    <DataState :loading="loading" :error="error" :empty="rows.length === 0">
      <template #empty>
        No gateways registered. Every client then connects anonymously and the engine learns its
        routes from traffic, which is the behaviour that shipped before this existed.
      </template>
      <table>
        <thead>
          <tr>
            <th>Gateway</th>
            <th class="num">Shard</th>
            <th>Participants</th>
            <th>State</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="g in rows" :key="g.gatewayId">
            <td><code>{{ g.gatewayId }}</code></td>
            <td class="num">{{ g.shardId }}</td>
            <td>{{ g.participants.length ? g.participants.join(', ') : '—' }}</td>
            <td>
              <span class="pill" :class="g.enabled ? 'good' : 'bad'">
                {{ g.enabled ? 'enabled' : 'disabled' }}
              </span>
            </td>
            <td class="row-actions">
              <button class="ghost small" @click="edit(g)">Edit</button>
              <button class="ghost small" @click="rotating = g">Rotate secret</button>
              <button class="ghost small" @click="deleting = g">Delete</button>
            </td>
          </tr>
        </tbody>
      </table>
    </DataState>
  </div>

  <Modal
    v-if="draft"
    :title="editingId === null ? 'New gateway' : `Gateway ${editingId}`"
    :busy="saving"
    :error="saveError"
    @submit="submit"
    @close="draft = null"
  >
    <div class="form-grid">
      <div>
        <label for="g-id">Gateway id</label>
        <input id="g-id" v-model="draft.gatewayId" :disabled="editingId !== null" />
        <p class="hint">Letters, digits, dot, dash, underscore. It travels as <code>id:secret</code>.</p>
      </div>
      <div>
        <label for="g-shard">Shard</label>
        <input id="g-shard" v-model.number="draft.shardId" type="number" />
      </div>
      <div class="wide">
        <label for="g-participants">Participants</label>
        <select id="g-participants" v-model="draft.participants" multiple size="8">
          <option v-for="p in participants.rows.value" :key="p.participantId" :value="p.participantId">
            {{ p.participantId }} — {{ p.name }}
          </option>
        </select>
        <p class="hint">
          Each of these is bound to this gateway's session the moment it authenticates. A
          participant already claimed by another gateway is refused.
        </p>
      </div>
      <div class="wide">
        <label>
          <input type="checkbox" v-model="draft.enabled" />
          Enabled
        </label>
        <p class="hint">A disabled gateway is left out of the published registry entirely.</p>
      </div>
    </div>
  </Modal>

  <ConfirmDialog
    v-if="rotating"
    title="Rotate gateway secret"
    :message="`A new secret is issued for ${rotating.gatewayId} and shown once. It takes effect only when the next release is published and the nodes have re-read it — until then the old secret still authenticates. The gateway must be restarted with the new secret afterwards.`"
    confirm-label="Rotate"
    :busy="saving"
    :error="saveError"
    @confirm="confirmRotate"
    @close="rotating = null"
  />

  <ConfirmDialog
    v-if="deleting"
    title="Delete gateway"
    :message="`${deleting.gatewayId} is removed, and the participants it claimed become unclaimed. Once the next release is published and reloaded, a gateway presenting this id is rejected rather than downgraded to an anonymous session.`"
    confirm-label="Delete"
    danger
    :busy="removing"
    :error="removeError"
    @confirm="confirmDelete"
    @close="deleting = null"
  />

  <Modal
    v-if="issued"
    :title="`Secret for ${issued.gatewayId}`"
    :busy="false"
    :error="null"
    submit-label="Done"
    @submit="issued = null"
    @close="issued = null"
  >
    <p>
      Copy this now. Only its SHA-256 is stored, so it cannot be shown again — rotating issues a new
      one rather than recovering this.
    </p>
    <pre class="secret">{{ issued.secret }}</pre>
  </Modal>
</template>

<style scoped>
.secret {
  user-select: all;
  word-break: break-all;
  white-space: pre-wrap;
}
</style>
