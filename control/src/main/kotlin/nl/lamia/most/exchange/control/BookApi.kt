package nl.lamia.most.exchange.control

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import nl.lamia.most.exchange.client.DepthFeedAssembler
import nl.lamia.most.exchange.client.FeedSequenceTracker
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/**
 * Live books for the console.
 *
 * **Server-sent events rather than a WebSocket.** The feed is one-way — the browser has nothing to
 * say back that a URL cannot carry — and for a one-way stream `EventSource` brings its own
 * reconnection, needs no new dependency and no protocol upgrade through the proxy. A WebSocket
 * would add a bidirectional channel nothing here uses, plus the reconnect loop that comes with it,
 * in exchange for nothing. If something later needs the browser to talk back on the same channel,
 * that is the moment to change it.
 *
 * What crosses this boundary is a **conflated image**, never the market data protocol. The browser
 * gets no sequence numbers, no gap detection and no snapshot splicing: decoding SBE in TypeScript
 * would mean a second `DepthFeedAssembler` and a second `FeedSequenceTracker` in a language where
 * neither can be tested against the publisher. This feed exists for the operator console; a real
 * consumer takes the SBE feed and uses the same assembler this backend does.
 */
@Component
class BookStreams(private val link: ClusterLink) {

    private val log = LoggerFactory.getLogger(BookStreams::class.java)
    private val subscribers = CopyOnWriteArrayList<Subscriber>()
    private val dropped = AtomicLong()

    /** A watch list of security ids, or empty for every book the feed carries. */
    fun open(securities: Set<Int>, timeoutMs: Long): SseEmitter {
        val emitter = SseEmitter(timeoutMs)
        val subscriber = Subscriber(emitter, securities)
        subscribers += subscriber
        emitter.onCompletion { subscribers.remove(subscriber) }
        emitter.onTimeout { subscribers.remove(subscriber) }
        emitter.onError { subscribers.remove(subscriber) }
        // Immediately, so a console renders on connect rather than after the first interval. It
        // also proves the stream works before anything has changed, which a silent socket does not.
        push(subscriber, link.depth.allImages())
        return emitter
    }

    /**
     * Pushes what changed, on its own thread.
     *
     * Deliberately not the feed poller: writing to a browser that has stopped reading blocks
     * whoever does it, and the one thread that must never block is the one draining the market
     * data subscriptions. A stalled console costs a dropped image and nothing else, which is the
     * same trade the gateway makes on its outbound leg.
     */
    @Scheduled(fixedDelayString = "\${control.depth.publishMs:250}")
    fun pump() {
        if (subscribers.isEmpty()) return
        val images = link.depth.allImages()
        if (images.isEmpty()) return
        for (subscriber in subscribers) push(subscriber, images)
    }

    fun subscriberCount(): Int = subscribers.size

    fun droppedImages(): Long = dropped.get()

    private fun push(subscriber: Subscriber, images: List<BookImage>) {
        for (image in images) {
            if (!subscriber.wants(image)) continue
            try {
                subscriber.emitter.send(SseEmitter.event().name("book").data(image))
                subscriber.sent[image.securityId] = image.version
            } catch (e: IOException) {
                // The ordinary way a browser leaves: a closed tab is an IOException here, not an
                // event. Logged at debug because it is expected, counted because a rising count
                // while consoles stay open is not.
                log.debug("book stream closed: {}", e.message)
                dropped.incrementAndGet()
                subscribers.remove(subscriber)
                subscriber.emitter.complete()
                return
            } catch (e: IllegalStateException) {
                // Already completed by the container, typically a timeout that raced this push.
                subscribers.remove(subscriber)
                return
            }
        }
    }

    private class Subscriber(val emitter: SseEmitter, val securities: Set<Int>) {
        /** The last version sent per security, so an unchanged book is not re-sent every tick. */
        val sent = HashMap<Int, Long>()

        fun wants(image: BookImage): Boolean {
            if (securities.isNotEmpty() && image.securityId !in securities) return false
            return sent[image.securityId] != image.version
        }
    }
}

@RestController
@RequestMapping("/api/books")
class BookApi(private val link: ClusterLink, private val streams: BookStreams) {

    /** Every book the feed is carrying, synchronised or not. */
    @GetMapping
    fun books(): List<BookImage> = link.depth.allImages()

    /**
     * One book. 404 means the feed has never carried this security — which is a different thing
     * from a book that exists and is not yet synchronised, and the console must not merge them.
     */
    @GetMapping("/{securityId}")
    fun book(@PathVariable securityId: Int): ResponseEntity<BookImage> =
        link.depth.image(securityId)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    @GetMapping("/status")
    fun status(): DepthFeedStatus = link.depth.status(link.connected, link.status().detail)

    /**
     * The live stream. `securities=1,2` narrows it; omitted, it carries every book.
     *
     * Authenticated by the session cookie like every other call here: `EventSource` cannot set
     * headers, but it does send same-origin cookies, which is exactly why the SPA and the API are
     * behind one origin.
     */
    @GetMapping("/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(@RequestParam(required = false) securities: String?): SseEmitter {
        val watch = securities
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.toSet()
            .orEmpty()
        return streams.open(watch, STREAM_TIMEOUT_MS)
    }

    private companion object {
        /**
         * Long, but not unbounded. A console left open all day should not be reconnecting every
         * few minutes; a stream nothing is reading should not be held for ever. `EventSource`
         * reconnects on its own when this expires, so the cost of being wrong is one reconnect.
         */
        const val STREAM_TIMEOUT_MS = 3_600_000L
    }
}
