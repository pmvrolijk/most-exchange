/**
 * The one place that talks to the control plane.
 *
 * Two things every call depends on and neither is optional:
 *
 * - `credentials: 'same-origin'` sends the session cookie. Without it every call is anonymous and
 *   the API answers 401 to a signed-in operator.
 * - `X-XSRF-TOKEN`, copied from the cookie Spring Security sets, on anything that mutates. The
 *   server rejects a mutating request without it even when the session is valid — which is the
 *   point: a session cookie alone must not be enough for another page to act as the operator.
 */

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message)
  }

  /** The session is gone or was never established. The router turns this into the login screen. */
  get unauthenticated(): boolean {
    return this.status === 401
  }
}

function csrfToken(): string | undefined {
  return document.cookie
    .split('; ')
    .find((c) => c.startsWith('XSRF-TOKEN='))
    ?.slice('XSRF-TOKEN='.length)
}

/**
 * `text` covers the two endpoints that are not JSON in either direction: importing a shard security
 * file, which is posted verbatim so the parser that boots one is what validates it, and reading a
 * published artifact, which is served as the exact bytes a process will boot from.
 */
type Wire = 'json' | 'text'

async function request<T>(
  method: string,
  path: string,
  body?: unknown,
  wire: Wire = 'json',
): Promise<T> {
  const headers: Record<string, string> = {}
  if (body !== undefined) headers['Content-Type'] = wire === 'json' ? 'application/json' : 'text/plain'
  if (wire === 'text') headers['Accept'] = 'text/plain'

  if (method !== 'GET') {
    const token = csrfToken()
    // No token yet means no GET has been made since the session started. Rather than fail with a
    // 403 the operator cannot act on, fetch something harmless to have the cookie issued.
    if (token === undefined) {
      await fetch('/api/auth/me', { credentials: 'same-origin' })
    }
    const refreshed = csrfToken()
    if (refreshed !== undefined) headers['X-XSRF-TOKEN'] = refreshed
  }

  const response = await fetch(`/api${path}`, {
    method,
    headers,
    credentials: 'same-origin',
    body: body === undefined ? undefined : wire === 'json' ? JSON.stringify(body) : (body as string),
  })

  if (!response.ok) {
    // Every refusal from this API is an ApiError body — `{error, message}` — including the 401 and
    // the 403. Falling back to the status text covers a proxy or gateway answering instead.
    const problem = await response.json().catch(() => null)
    throw new ApiError(
      response.status,
      problem?.error ?? 'error',
      problem?.message ?? response.statusText,
    )
  }

  if (response.status === 204) return undefined as T
  if (wire === 'text') return (await response.text()) as T
  return (await response.json()) as T
}

export const api = {
  get: <T>(path: string) => request<T>('GET', path),
  /** A published release artifact, as text rather than as a description of one. */
  getText: (path: string) => request<string>('GET', path, undefined, 'text'),
  post: <T>(path: string, body?: unknown) => request<T>('POST', path, body),
  postText: <T>(path: string, body: string) => request<T>('POST', path, body, 'text'),
  put: <T>(path: string, body?: unknown) => request<T>('PUT', path, body),
  del: <T>(path: string) => request<T>('DELETE', path),
}
