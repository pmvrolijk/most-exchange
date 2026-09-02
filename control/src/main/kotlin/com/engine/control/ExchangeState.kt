package com.engine.control

import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** What the L3 feed last said about one security. */
data class SecurityState(
    val securityId: Int,
    val shardId: Int,
    val phase: String? = null,
    val phaseAt: String? = null,
    val halt: HaltState? = null,
    val lastUncrossPrice: Long? = null,
    val lastUncrossQty: Long? = null,
    val lastTradePrice: Long? = null,
    val lastTradeQty: Long? = null,
    val lastTradeAt: String? = null,
)

/**
 * A volatility halt, as reported by the `VolatilityHalted` book event.
 *
 * There is no halt *phase* — a halt sets the security to `CLOSED`, and this event is the only thing
 * distinguishing it from a scheduled close (Design.md §4.6). Keeping it means an operator can tell
 * "the market closed" from "the market broke", which is the difference between doing nothing and
 * running the reopen sequence.
 */
data class HaltState(
    val at: String,
    val collarReference: Long,
    val attemptedPrice: Long,
    val breachedBound: Long,
    val aggressorSide: String,
    val clearedAt: String? = null,
)

/**
 * What discovery last broadcast, and whether it agrees with the database.
 *
 * A universe version is a 64-bit hash, and it is serialized as a JSON *string*.
 *
 * Not a style choice: JSON numbers are IEEE 754 doubles in every browser, so a value above 2^53
 * loses its low digits on the way through `JSON.parse` — `9181280125937456696` renders as
 * `9181280125937457000`. This value is an identity and never arithmetic: it is compared against
 * what discovery broadcasts and against a published manifest, and a console that displayed digits
 * the exchange never produced would be the exact failure the fingerprint exists to prevent. The
 * Kotlin stays a Long; only the wire representation changes.
 */
data class DirectoryState(
    @get:JsonSerialize(using = ToStringSerializer::class)
    val version: Long,
    val securities: Int,
    val shards: List<Int>,
    val lastSeenAt: String,
    val incompleteBroadcasts: Long,
)

/** The whole observed picture, plus how much of the feed was missed getting it. */
data class ExchangeStatus(
    val connected: Boolean,
    val detail: String,
    val directory: DirectoryState?,
    val securities: List<SecurityState>,
    val feedGaps: Long,
    val eventsMissed: Long,
    val eventsSeen: Long,
    val routingDrift: List<String>,
)

/**
 * Everything the control plane has *observed*, as opposed to everything it has authored.
 *
 * Written only by the feed poller and read by request threads, so every field is behind a
 * concurrent structure. The distinction from the database matters and is deliberate: the database
 * records what an operator asked for, this records what the exchange actually did, and the two
 * legitimately differ — an executing uncross moves `staticReference` with nobody asking.
 */
class ExchangeState {

    private val securities = ConcurrentHashMap<Int, SecurityState>()

    @Volatile
    var directory: DirectoryState? = null

    private val seen = AtomicLong()
    private val gaps = AtomicLong()
    private val missed = AtomicLong()

    val eventsSeen: Long get() = seen.get()
    val feedGaps: Long get() = gaps.get()
    val eventsMissed: Long get() = missed.get()

    fun securities(): List<SecurityState> = securities.values.sortedBy { it.securityId }

    /**
     * Forgets everything observed. Used when a test needs a clean slate, and available to an
     * operator whose backend has been watching a cluster that was rebuilt underneath it — stale
     * phases would otherwise make the scheduler reconcile against a market that no longer exists.
     */
    fun clear() {
        securities.clear()
        directory = null
        seen.set(0)
        gaps.set(0)
        missed.set(0)
    }

    fun securityState(securityId: Int): SecurityState? = securities[securityId]

    fun recordEvent(missedCount: Long) {
        seen.incrementAndGet()
        if (missedCount > 0L) {
            gaps.incrementAndGet()
            missed.addAndGet(missedCount)
        }
    }

    fun onSessionChanged(securityId: Int, shardId: Int, phase: String) {
        update(securityId, shardId) {
            // A phase change away from CLOSED is what clears a halt: the reopen sequence has run.
            val halt = if (phase == "CLOSED") it.halt else it.halt?.copy(clearedAt = now())
            it.copy(phase = phase, phaseAt = now(), halt = halt)
        }
    }

    fun onVolatilityHalted(
        securityId: Int,
        shardId: Int,
        collarReference: Long,
        attemptedPrice: Long,
        breachedBound: Long,
        aggressorSide: String,
    ) {
        update(securityId, shardId) {
            it.copy(
                halt = HaltState(
                    at = now(),
                    collarReference = collarReference,
                    attemptedPrice = attemptedPrice,
                    breachedBound = breachedBound,
                    aggressorSide = aggressorSide,
                ),
            )
        }
    }

    fun onAuctionUncrossed(securityId: Int, shardId: Int, price: Long, qty: Long) {
        update(securityId, shardId) { it.copy(lastUncrossPrice = price, lastUncrossQty = qty) }
    }

    fun onTrade(securityId: Int, shardId: Int, price: Long, qty: Long) {
        update(securityId, shardId) {
            it.copy(lastTradePrice = price, lastTradeQty = qty, lastTradeAt = now())
        }
    }

    private fun update(securityId: Int, shardId: Int, change: (SecurityState) -> SecurityState) {
        securities.compute(securityId) { _, existing ->
            change(existing ?: SecurityState(securityId, shardId))
        }
    }

    private fun now(): String = Instant.now().toString()
}
