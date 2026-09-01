<script setup lang="ts" generic="T">
import { onMounted, ref } from 'vue'
import { api, ApiError } from '../api/client'

/**
 * Fetch a list, and be honest about the three states it can be in.
 *
 * Empty and failed are not the same thing and must never render the same way. A control plane that
 * showed "no securities" when it meant "the request was refused" would be lying about the state of
 * the exchange, which is the one thing this UI exists not to do.
 */
const props = defineProps<{ path: string }>()

const rows = ref<T[]>([])
const error = ref<string | null>(null)
const loading = ref(true)

async function load() {
  loading.value = true
  error.value = null
  try {
    rows.value = await api.get<T[]>(props.path)
  } catch (e) {
    error.value = e instanceof ApiError ? `${e.code}: ${e.message}` : String(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)
defineExpose({ reload: load })
</script>

<template>
  <div class="card">
    <p v-if="loading" class="empty">Loading…</p>
    <p v-else-if="error" class="error">{{ error }}</p>
    <p v-else-if="rows.length === 0" class="empty"><slot name="empty">Nothing here yet.</slot></p>
    <div v-else class="table-wrap"><slot :rows="rows" /></div>
  </div>
</template>
