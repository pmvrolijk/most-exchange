<script setup lang="ts">
/**
 * Loading, failed, empty, or content — and never two of them confused for each other.
 *
 * This is the rule `AsyncTable` was written around, extracted so views that write as well as read
 * can honour it too. A control plane that showed "no securities" when it meant "the request was
 * refused" would be lying about the state of the exchange, which is the one thing this UI exists
 * not to do.
 */
defineProps<{ loading: boolean; error: string | null; empty: boolean }>()
</script>

<template>
  <p v-if="loading" class="empty">Loading…</p>
  <p v-else-if="error" class="error">{{ error }}</p>
  <p v-else-if="empty" class="empty"><slot name="empty">Nothing here yet.</slot></p>
  <div v-else class="table-wrap"><slot /></div>
</template>
