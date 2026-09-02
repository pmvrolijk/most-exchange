import { onUnmounted, ref, type Ref } from 'vue'
import { api } from './client'
import { reason } from './collection'
import type { BookImage } from './types'

/**
 * Live books, over server-sent events.
 *
 * `EventSource` rather than a WebSocket because the traffic is one-way: the browser has nothing to
 * say back that the URL does not already carry. It also reconnects by itself, which is the whole
 * of the resilience story a console needs and would otherwise be a retry loop written here.
 *
 * The fallback matters more than it looks. Streams are the first thing an unhelpful corporate
 * proxy breaks, and an operator staring at a book that stopped updating an hour ago has no way to
 * tell. If the stream fails, this polls — visibly, so the screen says which mode it is in.
 */
export function useBooks(): {
  books: Ref<Record<number, BookImage>>
  live: Ref<boolean>
  error: Ref<string | null>
  stop: () => void
} {
  const books = ref<Record<number, BookImage>>({})
  const live = ref(false)
  const error = ref<string | null>(null)

  let source: EventSource | null = null
  let poller: number | undefined
  let stopped = false

  function apply(image: BookImage) {
    books.value = { ...books.value, [image.securityId]: image }
  }

  async function pollOnce() {
    try {
      for (const image of await api.get<BookImage[]>('/books')) apply(image)
      error.value = null
    } catch (e) {
      error.value = reason(e)
    }
  }

  function startPolling() {
    if (poller !== undefined || stopped) return
    live.value = false
    pollOnce()
    poller = window.setInterval(pollOnce, 1000)
  }

  function start() {
    // Same origin, so the session cookie goes with it: EventSource cannot set headers, which is
    // one more reason the SPA and the API sit behind one host.
    source = new EventSource('/api/books/stream')
    source.addEventListener('book', (event) => {
      apply(JSON.parse((event as MessageEvent).data) as BookImage)
      live.value = true
      error.value = null
      // The stream came back: stop the fallback rather than leaving both running for ever.
      if (poller !== undefined) {
        window.clearInterval(poller)
        poller = undefined
      }
    })
    source.onopen = () => {
      live.value = true
    }
    source.onerror = () => {
      // EventSource retries on its own; what it cannot do is tell us it succeeded. Falling back
      // to polling keeps the screen truthful in the meantime, and a later `book` event flips it
      // back to live.
      live.value = false
      startPolling()
    }
  }

  start()

  function stop() {
    stopped = true
    source?.close()
    window.clearInterval(poller)
  }

  onUnmounted(stop)
  return { books, live, error, stop }
}
