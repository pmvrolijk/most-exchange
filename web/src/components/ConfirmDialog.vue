<script setup lang="ts">
import Modal from './Modal.vue'

/**
 * Confirmation for something that cannot be taken back, saying what it will do rather than asking
 * "are you sure?". The market-moving screens spell out their own consequences in `message`; a
 * generic prompt would train an operator to click through the one that mattered.
 */
defineProps<{
  title: string
  message: string
  confirmLabel: string
  busy?: boolean
  error?: string | null
  danger?: boolean
}>()

const emit = defineEmits<{ confirm: []; close: [] }>()
</script>

<template>
  <Modal
    :title="title"
    :busy="busy"
    :error="error"
    :submit-label="confirmLabel"
    :danger="danger"
    @submit="emit('confirm')"
    @close="emit('close')"
  >
    <p class="lede" style="margin: 8px 0 0">{{ message }}</p>
    <slot />
  </Modal>
</template>
