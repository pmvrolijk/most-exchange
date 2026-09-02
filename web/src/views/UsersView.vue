<script setup lang="ts">
import { ref } from 'vue'
import DataState from '../components/DataState.vue'
import Modal from '../components/Modal.vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import { api } from '../api/client'
import { useCollection, useMutation } from '../api/collection'
import { session } from '../api/session'
import type { ControlUser } from '../api/types'

const { rows, loading, error, reload } = useCollection<ControlUser>('/users')
const { busy: saving, error: saveError, run: runSave } = useMutation()
const { busy: removing, error: removeError, run: runRemove } = useMutation()

const creating = ref<{ username: string; password: string } | null>(null)
const resetting = ref<{ user: ControlUser; password: string } | null>(null)
const deleting = ref<ControlUser | null>(null)

async function create() {
  const c = creating.value
  if (!c) return
  const ok = await runSave(() => api.post<ControlUser>('/users', c))
  if (ok) {
    creating.value = null
    await reload()
  }
}

async function resetPassword() {
  const r = resetting.value
  if (!r) return
  const ok = await runSave(() =>
    api.put<void>(`/users/${encodeURIComponent(r.user.username)}/password`, { password: r.password }),
  )
  if (ok) resetting.value = null
}

async function setEnabled(user: ControlUser, enabled: boolean) {
  await runSave(() => api.put<void>(`/users/${encodeURIComponent(user.username)}/enabled`, enabled))
  await reload()
}

async function confirmDelete() {
  const u = deleting.value
  if (!u) return
  const ok = await runRemove(() => api.del<void>(`/users/${encodeURIComponent(u.username)}`))
  if (ok) {
    deleting.value = null
    await reload()
  }
}
</script>

<template>
  <h1>Operators</h1>
  <p class="lede">
    One role, full access. Every account here can create another account, move a session and reopen
    a halted security, so an account is not a permission boundary — the
    <router-link to="/audit">audit</router-link> is what distinguishes who did what. A read-only
    tier is a data change rather than a migration (the role column exists), but nothing yet
    separates an observer from someone who can move a market.
  </p>

  <div class="toolbar">
    <button @click="creating = { username: '', password: '' }">New operator</button>
  </div>
  <p v-if="saveError && !creating && !resetting" class="error">{{ saveError }}</p>

  <div class="card">
    <DataState :loading="loading" :error="error" :empty="rows.length === 0">
      <table>
        <thead>
          <tr><th>Operator</th><th>Role</th><th>State</th><th></th></tr>
        </thead>
        <tbody>
          <tr v-for="u in rows" :key="u.username">
            <td>
              {{ u.username }}
              <span v-if="u.username === session.identity?.username" class="pill">you</span>
            </td>
            <td>{{ u.role }}</td>
            <td>
              <span class="pill" :class="u.enabled ? 'good' : 'bad'">
                {{ u.enabled ? 'enabled' : 'disabled' }}
              </span>
            </td>
            <td class="row-actions">
              <button class="ghost small" @click="resetting = { user: u, password: '' }">
                Set password
              </button>
              <button class="ghost small" :disabled="saving" @click="setEnabled(u, !u.enabled)">
                {{ u.enabled ? 'Disable' : 'Enable' }}
              </button>
              <button class="ghost small" @click="deleting = u">Delete</button>
            </td>
          </tr>
        </tbody>
      </table>
    </DataState>
  </div>

  <Modal
    v-if="creating"
    title="New operator"
    :busy="saving"
    :error="saveError"
    submit-label="Create"
    @submit="create"
    @close="creating = null"
  >
    <div class="form-grid">
      <div>
        <label for="u-name">Username</label>
        <input id="u-name" v-model="creating.username" autocomplete="off" />
      </div>
      <div>
        <label for="u-pass">Password</label>
        <input id="u-pass" v-model="creating.password" type="password" autocomplete="new-password" />
        <p class="hint">At least 12 characters. Stored as a BCrypt hash and never read back.</p>
      </div>
    </div>
  </Modal>

  <Modal
    v-if="resetting"
    :title="`Set password · ${resetting.user.username}`"
    :busy="saving"
    :error="saveError"
    submit-label="Set password"
    @submit="resetPassword"
    @close="resetting = null"
  >
    <label for="u-newpass">New password</label>
    <input id="u-newpass" v-model="resetting.password" type="password" autocomplete="new-password" />
    <p class="hint">
      Existing sessions are unaffected: the session is a cookie the server already accepted, not a
      credential it re-checks.
    </p>
  </Modal>

  <ConfirmDialog
    v-if="deleting"
    title="Delete operator"
    :message="
      deleting.username === session.identity?.username
        ? `${deleting.username} is your own account. Deleting it signs you out of a control plane that is still driving a market on a schedule.`
        : `${deleting.username} can no longer sign in. Their rows in the audit stay — the audit records who asked for a command, and deleting the account does not unask it.`
    "
    confirm-label="Delete"
    danger
    :busy="removing"
    :error="removeError"
    @confirm="confirmDelete"
    @close="deleting = null"
  />
</template>
