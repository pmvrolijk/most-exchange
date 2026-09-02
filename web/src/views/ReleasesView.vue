<script setup lang="ts">
import { ref } from 'vue'
import DataState from '../components/DataState.vue'
import Modal from '../components/Modal.vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import { api } from '../api/client'
import { useCollection, useMutation, useResource, reason } from '../api/collection'
import type { ImportResult, Release, TopologyView } from '../api/types'
import { at } from '../format'

const { rows, loading, error, reload } = useCollection<Release>('/releases')
const { value: topology, reload: reloadTopology } = useResource<TopologyView>('/topology')
const { busy: publishing, error: publishError, run: runPublish } = useMutation()
const { busy: importing, error: importError, run: runImport } = useMutation()

const note = ref('')
const confirmingPublish = ref(false)

const importText = ref('')
const imported = ref<ImportResult | null>(null)

const viewing = ref<Release | null>(null)
const viewingName = ref<string | null>(null)
const viewingText = ref<string | null>(null)
const viewingError = ref<string | null>(null)

/** Whatever the release actually wrote: the registry, the manifest, and one file per shard. */
function artifactNames(release: Release): string[] {
  return [
    'manifest.json',
    'discovery.properties',
    ...Object.keys(release.fingerprints).map((shard) => `shard-${shard}-securities.properties`),
  ]
}

async function show(release: Release, name: string) {
  viewing.value = release
  viewingName.value = name
  viewingText.value = null
  viewingError.value = null
  try {
    viewingText.value = await api.getText(`/releases/${release.version}/files/${name}`)
  } catch (e) {
    viewingError.value = reason(e)
  }
}

async function publish() {
  const ok = await runPublish(async () => {
    const query = note.value.trim() === '' ? '' : `?note=${encodeURIComponent(note.value.trim())}`
    await api.post<Release>(`/releases${query}`)
  })
  if (ok) {
    confirmingPublish.value = false
    note.value = ''
    await Promise.all([reload(), reloadTopology()])
  }
}

async function runTheImport() {
  imported.value = null
  const ok = await runImport(async () => {
    imported.value = await api.postText<ImportResult>('/import', importText.value)
  })
  if (ok) {
    importText.value = ''
    await reloadTopology()
  }
}
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

  <div class="card">
    <h2 style="font-size: 15px; margin: 0 0 8px">Publish</h2>
    <div v-if="topology && topology.problems.length" class="problem">
      <p style="margin-top: 0">
        The draft cannot be published — validation constructs the same domain objects a process
        would build at boot, so a topology that fails here is one that would fail to start:
      </p>
      <ul><li v-for="p in topology.problems" :key="p">{{ p }}</li></ul>
    </div>
    <div v-else-if="topology" class="toolbar">
      <input
        v-model="note"
        placeholder="Note (optional) — what changed, for whoever reads this at 3am"
        style="max-width: 480px"
      />
      <button @click="confirmingPublish = true">Publish release</button>
      <span class="hint">Draft universe version {{ topology.universeVersion }}.</span>
    </div>
    <p v-if="publishError" class="error">{{ publishError }}</p>
  </div>

  <div class="card">
    <h2 style="font-size: 15px; margin: 0 0 8px">Import a shard security file</h2>
    <p class="lede" style="margin-bottom: 12px">
      The bootstrap path. Parsing goes through the same <code>ShardSpec.from</code> a process boots
      with, so an imported file is validated by the code that would have booted it — and retyping a
      tick size into a form is exactly where the typo this module exists to prevent gets in. The
      shard itself must exist first: endpoints are not in a security file.
    </p>
    <textarea v-model="importText" rows="6" placeholder="shard.id=0&#10;securities=..."></textarea>
    <div class="toolbar" style="margin-top: 12px">
      <button :disabled="importing || importText.trim() === ''" @click="runTheImport">
        {{ importing ? 'Importing…' : 'Import' }}
      </button>
    </div>
    <p v-if="importError" class="error">{{ importError }}</p>
    <div v-if="imported" class="outcome ok">
      <div class="command">shard {{ imported.shardId }} · {{ imported.fingerprint }}</div>
      <p class="detail">
        {{ imported.inserted.length }} inserted{{ imported.inserted.length ? ` (${imported.inserted.join(', ')})` : '' }},
        {{ imported.updated.length }} updated{{ imported.updated.length ? ` (${imported.updated.join(', ')})` : '' }}.
      </p>
    </div>
  </div>

  <div class="card">
    <DataState :loading="loading" :error="error" :empty="rows.length === 0">
      <template #empty>Nothing published yet.</template>
      <table>
        <thead>
          <tr>
            <th class="num">Version</th>
            <th>Published</th>
            <th>Universe</th>
            <th>Directory</th>
            <th>Fingerprints</th>
            <th>Note</th>
            <th>Files</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in rows" :key="r.version">
            <td class="num">{{ r.version }}</td>
            <td>{{ at(r.createdAt) }}</td>
            <td class="mono">{{ r.universeVersion }}</td>
            <td class="mono">{{ r.directory }}</td>
            <td class="mono">
              <span v-for="(fp, shard) in r.fingerprints" :key="shard">
                shard {{ shard }}: {{ fp }}<br />
              </span>
            </td>
            <td>{{ r.note ?? '—' }}</td>
            <td class="row-actions">
              <button
                v-for="name in artifactNames(r)"
                :key="name"
                class="ghost small"
                @click="show(r, name)"
              >
                {{ name.replace('-securities.properties', '').replace('.properties', '').replace('.json', '') }}
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </DataState>
  </div>

  <ConfirmDialog
    v-if="confirmingPublish"
    title="Publish release"
    :message="`Version ${(rows[0]?.version ?? 0) + 1} is written from the draft topology and becomes immutable. It changes nothing by itself: a node reads a release directory when it boots, so this takes effect on the next restart of the processes pointed at it.`"
    confirm-label="Publish"
    :busy="publishing"
    :error="publishError"
    @confirm="publish"
    @close="confirmingPublish = false"
  />

  <Modal
    v-if="viewing"
    :title="`Release ${viewing.version} · ${viewingName}`"
    submit-label="Close"
    hide-cancel
    @submit="viewing = null"
    @close="viewing = null"
  >
    <p v-if="viewingError" class="error">{{ viewingError }}</p>
    <p v-else-if="viewingText === null" class="empty">Loading…</p>
    <textarea v-else :value="viewingText" rows="18" readonly></textarea>
  </Modal>
</template>
