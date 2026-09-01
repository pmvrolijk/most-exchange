<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { session } from '../api/session'
import { ApiError } from '../api/client'

const username = ref('')
const password = ref('')
const error = ref<string | null>(null)
const busy = ref(false)

const route = useRoute()
const router = useRouter()

async function submit() {
  busy.value = true
  error.value = null
  try {
    await session.login(username.value, password.value)
    // Back to wherever the guard interrupted, so a bookmarked deep link survives a session expiry.
    const next = route.query.next
    router.push(typeof next === 'string' ? next : { name: 'status' })
  } catch (e) {
    // The server does not say which half of the guess was wrong, and neither does this.
    error.value = e instanceof ApiError ? e.message : 'could not reach the control plane'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="login">
    <form @submit.prevent="submit">
      <div class="brand" style="padding-left: 0">
        most<small>exchange control</small>
      </div>
      <label for="u">Operator</label>
      <input id="u" v-model="username" autocomplete="username" autofocus />
      <label for="p">Password</label>
      <input id="p" v-model="password" type="password" autocomplete="current-password" />
      <p v-if="error" class="error">{{ error }}</p>
      <button type="submit" :disabled="busy || !username || !password">
        {{ busy ? 'Signing in…' : 'Sign in' }}
      </button>
    </form>
  </div>
</template>
