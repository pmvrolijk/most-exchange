import { reactive } from 'vue'
import { api, ApiError } from './client'
import type { Identity } from './types'

/**
 * Who is signed in, as one reactive object.
 *
 * No store library. There is exactly one piece of global state in this app and it is this; adding
 * Pinia to hold it would be more dependency than the thing it holds.
 *
 * `identity === null` after `resolved` is true means signed out. Before that it means "we have not
 * asked yet", and the router waits rather than bouncing the operator to the login screen on every
 * reload — the session lives in an HttpOnly cookie the SPA cannot read, so asking the server is
 * the only way to know.
 */
export const session = reactive({
  identity: null as Identity | null,
  resolved: false,

  async resolve(): Promise<void> {
    try {
      this.identity = await api.get<Identity>('/auth/me')
    } catch (e) {
      if (e instanceof ApiError && e.unauthenticated) this.identity = null
      else throw e
    } finally {
      this.resolved = true
    }
  },

  async login(username: string, password: string): Promise<void> {
    this.identity = await api.post<Identity>('/auth/login', { username, password })
    this.resolved = true
  },

  async logout(): Promise<void> {
    await api.post<void>('/auth/logout')
    this.identity = null
  },
})
