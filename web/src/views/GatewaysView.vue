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
  draft.value = {
    gatewayId: '',
    shardId: 0,
    enabled: true,
    participants: [],
    cancelOnly: [],
    operator: false,
    primaryFor: [],
  }
}

function edit(g: Gateway) {
  editingId.value = g.gatewayId
  saveError.value = null
  draft.value = {
    ...g,
    participants: [...g.participants],
    cancelOnly: [...g.cancelOnly],
    primaryFor: [...g.primaryFor],
  }
}

/** Everyone the draft lists, which is what it may be primary for. */
function listed(g: Gateway): number[] {
  return [...g.participants, ...g.cancelOnly].sort((a, b) => a - b)
}

async function submit() {
  const row = draft.value
  if (!row) return
  // A primary flag for a participant no longer listed would be refused; drop it quietly instead.
  row.primaryFor = row.primaryFor.filter((p) => listed(row).includes(p))
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
    What each gateway may do. The <strong>gateway enforces it</strong>: an order or cancel for a
    participant it does not list is rejected <code>UNAUTHORIZED_PARTICIPANT</code> before it reaches
    the cluster, a <em>cancel-only</em> participant may withdraw resting orders but not place new
    ones, and operator commands pass only through an <em>operator</em> gateway. The consensus module
    authenticates a connecting gateway against this, and the engine binds its participants at
    session open so a quiet participant is still sent its own fills.
  </p>
  <p class="lede">
    A participant may be listed on several gateways — a primary and a failover — and then one of
    them must be its <em>primary</em>, where it is bound while both are connected. The control
    plane's own live commands go through the shard's advertised order-entry endpoint, so the gateway
    behind it must be an operator. Publishing a release renders this as each shard's participants
    file; the nodes and the gateways re-read it while they run, so no change here costs a restart.
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
            <th>Cancel only</th>
            <th>Operator</th>
            <th>State</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="g in rows" :key="g.gatewayId">
            <td><code>{{ g.gatewayId }}</code></td>
            <td class="num">{{ g.shardId }}</td>
            <td>
              <template v-if="g.participants.length">
                <span v-for="(p, i) in g.participants" :key="p">
                  {{ i ? ', ' : '' }}{{ p }}<sup v-if="g.primaryFor.includes(p)" title="primary">P</sup>
                </span>
              </template>
              <template v-else>—</template>
            </td>
            <td>{{ g.cancelOnly.length ? g.cancelOnly.join(', ') : '—' }}</td>
            <td>
              <span v-if="g.operator" class="pill good">operator</span>
              <template v-else>—</template>
            </td>
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
          May place and cancel. Bound to this gateway's session when it authenticates, if this is the
          participant's primary or nobody live holds it.
        </p>
      </div>
      <div class="wide">
        <label for="g-cancel-only">Cancel only</label>
        <select id="g-cancel-only" v-model="draft.cancelOnly" multiple size="5">
          <option v-for="p in participants.rows.value" :key="p.participantId" :value="p.participantId">
            {{ p.participantId }} — {{ p.name }}
          </option>
        </select>
        <p class="hint">
          May cancel, may not place: revoke a participant gracefully by moving it here, publishing,
          and removing it once its resting orders are gone. Not also in Participants.
        </p>
      </div>
      <div class="wide">
        <label for="g-primary">Primary for</label>
        <select id="g-primary" v-model="draft.primaryFor" multiple size="5">
          <option v-for="p in listed(draft)" :key="p" :value="p">{{ p }}</option>
        </select>
        <p class="hint">
          Needed only for a participant another gateway on this shard also lists: that participant
          is bound here while both are connected. One primary per participant per shard.
        </p>
      </div>
      <div class="wide">
        <label>
          <input type="checkbox" v-model="draft.operator" />
          Operator
        </label>
        <p class="hint">
          May send operator commands (session transitions, purges, definitions, image requests).
          An operator with no participants is how the control plane or the CLI is named to a node
          that refuses anonymous sessions.
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
    :message="`${deleting.gatewayId} is removed, and the participants it listed are no longer listed there. Once the next release is published and reloaded, a gateway presenting this id is rejected rather than downgraded to an anonymous session.`"
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
