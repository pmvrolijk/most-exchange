<script setup lang="ts" generic="T">
import DataState from './DataState.vue'
import { useCollection } from '../api/collection'

/**
 * Fetch a list and render it in whichever of its states it is actually in.
 *
 * Kept for the read-only views, where a table needs nothing but its own data. Views that mutate
 * use `useCollection` directly — they need the reload handle, which is the only thing this wrapper
 * hides.
 */
const props = defineProps<{ path: string }>()

const { rows, loading, error, reload } = useCollection<T>(props.path)

defineExpose({ reload })
</script>

<template>
  <div class="card">
    <DataState :loading="loading" :error="error" :empty="rows.length === 0">
      <template #empty><slot name="empty">Nothing here yet.</slot></template>
      <slot :rows="rows" />
    </DataState>
  </div>
</template>
