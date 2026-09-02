import { ref, onMounted } from 'vue'
import type { Ref } from 'vue'
import { api, ApiError } from './client'

/** An ApiError's own words, or whatever else went wrong, as one line an operator can act on. */
export function reason(e: unknown): string {
  return e instanceof ApiError ? `${e.code}: ${e.message}` : String(e)
}

export interface Collection<T> {
  rows: Ref<T[]>
  loading: Ref<boolean>
  error: Ref<string | null>
  reload: () => Promise<void>
}

/**
 * A list endpoint, with the three states kept apart.
 *
 * The same contract `AsyncTable` has always had — empty and failed never render the same way —
 * lifted out of the component so a view that also *writes* can reload after a mutation without
 * reaching through a template ref. `loading` stays false on a reload: blanking a table an operator
 * is reading, every time they change one row in it, loses their place for no information gained.
 */
export function useCollection<T>(path: string): Collection<T> {
  const rows = ref<T[]>([]) as Ref<T[]>
  const loading = ref(true)
  const error = ref<string | null>(null)

  async function reload() {
    try {
      rows.value = await api.get<T[]>(path)
      error.value = null
    } catch (e) {
      error.value = reason(e)
    } finally {
      loading.value = false
    }
  }

  onMounted(reload)
  return { rows, loading, error, reload }
}

export interface Mutation {
  busy: Ref<boolean>
  error: Ref<string | null>
  /** Runs `fn`, holding its refusal for the form to display. True when it went through. */
  run: <T>(fn: () => Promise<T>) => Promise<boolean>
}

/**
 * One in-flight write, and whatever the server said about it.
 *
 * The error is kept rather than thrown on: the domain's refusals are the useful half of this API —
 * "a security must be served by exactly one shard", "shard 0 hosts at most 10 securities" — and
 * they are written to be read by the person who caused them.
 */
export function useMutation(): Mutation {
  const busy = ref(false)
  const error = ref<string | null>(null)

  async function run<T>(fn: () => Promise<T>): Promise<boolean> {
    busy.value = true
    error.value = null
    try {
      await fn()
      return true
    } catch (e) {
      error.value = reason(e)
      return false
    } finally {
      busy.value = false
    }
  }

  return { busy, error, run }
}

export interface Resource<T> {
  value: Ref<T | null>
  loading: Ref<boolean>
  error: Ref<string | null>
  reload: () => Promise<void>
}

/** The same three states for an endpoint that answers one object rather than a list. */
export function useResource<T>(path: string): Resource<T> {
  const value = ref<T | null>(null) as Ref<T | null>
  const loading = ref(true)
  const error = ref<string | null>(null)

  async function reload() {
    try {
      value.value = await api.get<T>(path)
      error.value = null
    } catch (e) {
      error.value = reason(e)
    } finally {
      loading.value = false
    }
  }

  onMounted(reload)
  return { value, loading, error, reload }
}
