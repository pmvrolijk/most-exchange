<script setup lang="ts">
import { onMounted, onUnmounted } from 'vue'

/**
 * A form in a panel over the page.
 *
 * The submit button is disabled while a request is in flight rather than the dialog closing
 * optimistically: every mutation here is a round trip that can be refused by the domain — an ISIN
 * whose check digit is wrong, an eleventh security on a shard — and the operator needs to read
 * that refusal next to the field that caused it, not on a page that has already moved on.
 */
const props = defineProps<{
  title: string
  busy?: boolean
  error?: string | null
  submitLabel?: string
  /** Market-moving, or otherwise not undoable. Colours the submit button and nothing else. */
  danger?: boolean
  /** For a dialog that only shows something: there is nothing to cancel out of. */
  hideCancel?: boolean
}>()

const emit = defineEmits<{ submit: []; close: [] }>()

function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape' && !props.busy) emit('close')
}

onMounted(() => window.addEventListener('keydown', onKey))
onUnmounted(() => window.removeEventListener('keydown', onKey))
</script>

<template>
  <div class="modal-backdrop" @click.self="!busy && emit('close')">
    <form class="modal" @submit.prevent="emit('submit')">
      <h2>{{ title }}</h2>
      <div class="modal-body"><slot /></div>
      <p v-if="error" class="error">{{ error }}</p>
      <div class="actions">
        <button v-if="!hideCancel" type="button" class="ghost" :disabled="busy" @click="emit('close')">
          Cancel
        </button>
        <button type="submit" :class="{ danger }" :disabled="busy">
          {{ busy ? 'Working…' : (submitLabel ?? 'Save') }}
        </button>
      </div>
    </form>
  </div>
</template>
