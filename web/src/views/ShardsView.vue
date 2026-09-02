<script setup lang="ts">
import { ref } from 'vue'
import DataState from '../components/DataState.vue'
import Modal from '../components/Modal.vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import { api } from '../api/client'
import { useCollection, useMutation } from '../api/collection'
import type { Shard } from '../api/types'

// Destructured so the template gets unwrapped refs: a ref nested in a plain object is not
// unwrapped in a template, and `shards.loading.value` throughout a view is noise.
const { rows, loading, error, reload } = useCollection<Shard>('/shards')
const { busy: saving, error: saveError, run: runSave } = useMutation()
const { busy: removing, error: removeError, run: runRemove } = useMutation()

/** Blank rather than plausible: a default channel that half-worked would be copied into production. */
function blank(): Shard {
  return {
    shardId: 0,
    orderEntryChannel: '',
    orderEntryStreamId: 0,
    executionReportChannel: '',
    executionReportStreamId: 0,
  }
}

const draft = ref<Shard | null>(null)
// The id is the key, so an edit cannot move it — the form disables the field, and this remembers
// which row a PUT is for.
const editingId = ref<number | null>(null)
const deleting = ref<Shard | null>(null)

function create() {
  editingId.value = null
  saveError.value = null
  draft.value = blank()
}

function edit(shard: Shard) {
  editingId.value = shard.shardId
  saveError.value = null
  draft.value = { ...shard }
}

async function submit() {
  const row = draft.value
  if (!row) return
  const ok = await runSave(async () => {
    if (editingId.value === null) await api.post<Shard>('/shards', row)
    else await api.put<Shard>(`/shards/${editingId.value}`, row)
  })
  if (ok) {
    draft.value = null
    await reload()
  }
}

async function confirmDelete() {
  const shard = deleting.value
  if (!shard) return
  const ok = await runRemove(() => api.del<void>(`/shards/${shard.shardId}`))
  if (ok) {
    deleting.value = null
    await reload()
  }
}
</script>

<template>
  <h1>Shards</h1>
  <p class="lede">
    A shard is one Aeron Cluster and one gateway serving at most ten securities. The channels below
    are the <em>gateway's</em> client endpoints, not the cluster's ingress — an adapter connecting
    to the cluster directly would bypass validation and cumQty reconstruction. Editing one changes
    the draft topology only: what a process boots from is the published release, so nothing here
    reaches a running node until Releases → Publish.
  </p>

  <div class="toolbar">
    <button @click="create">New shard</button>
  </div>

  <div class="card">
    <DataState :loading="loading" :error="error" :empty="rows.length === 0">
      <template #empty>No shards. Add one, or import an existing shard security file.</template>
      <table>
        <thead>
          <tr>
            <th class="num">Shard</th>
            <th>Order entry</th>
            <th class="num">Stream</th>
            <th>Execution reports</th>
            <th class="num">Stream</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="s in rows" :key="s.shardId">
            <td class="num">{{ s.shardId }}</td>
            <td class="mono">{{ s.orderEntryChannel }}</td>
            <td class="num">{{ s.orderEntryStreamId }}</td>
            <td class="mono">{{ s.executionReportChannel }}</td>
            <td class="num">{{ s.executionReportStreamId }}</td>
            <td class="row-actions">
              <button class="ghost small" @click="edit(s)">Edit</button>
              <button class="ghost small" @click="deleting = s">Delete</button>
            </td>
          </tr>
        </tbody>
      </table>
    </DataState>
  </div>

  <Modal
    v-if="draft"
    :title="editingId === null ? 'New shard' : `Shard ${editingId}`"
    :busy="saving"
    :error="saveError"
    @submit="submit"
    @close="draft = null"
  >
    <div class="form-grid">
      <div>
        <label for="shard-id">Shard id</label>
        <input id="shard-id" v-model.number="draft.shardId" type="number" :disabled="editingId !== null" />
        <p v-if="editingId !== null" class="hint">
          The id is the key every process, file and feed sequence is namespaced by, so it cannot be
          moved. Delete and recreate to renumber.
        </p>
      </div>
      <div></div>
      <div class="wide">
        <label for="oe">Order entry channel</label>
        <input id="oe" v-model="draft.orderEntryChannel" class="mono" placeholder="aeron:udp?endpoint=..." />
      </div>
      <div>
        <label for="oes">Order entry stream</label>
        <input id="oes" v-model.number="draft.orderEntryStreamId" type="number" />
      </div>
      <div></div>
      <div class="wide">
        <label for="er">Execution report channel</label>
        <input id="er" v-model="draft.executionReportChannel" class="mono" />
      </div>
      <div>
        <label for="ers">Execution report stream</label>
        <input id="ers" v-model.number="draft.executionReportStreamId" type="number" />
      </div>
    </div>
    <p class="hint">
      These are what discovery broadcasts to upstream adapters. In a container the endpoint belongs
      to whichever host runs the media driver, not to the process that asks for it.
    </p>
  </Modal>

  <ConfirmDialog
    v-if="deleting"
    title="Delete shard"
    :message="`Shard ${deleting.shardId} is removed from the draft topology. A shard still holding securities cannot be deleted, and a published release keeps whatever it captured — this changes nothing a running node reads.`"
    confirm-label="Delete"
    danger
    :busy="removing"
    :error="removeError"
    @confirm="confirmDelete"
    @close="deleting = null"
  />
</template>
