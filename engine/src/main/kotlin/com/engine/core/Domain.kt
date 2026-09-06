package com.engine.core

/**
 * Domain primitives and the packed order-pool layout. See Design.md §3.
 *
 * These value classes are free only in non-generic positions. Storing one in a collection,
 * a nullable, or an `Array<T>` boxes it; so does passing one to a callback that is not an
 * `inline fun`. Under the eventual Epsilon GC profile that allocation is fatal, so the pool
 * below is raw `LongArray` and every hot-path callback stays inline (Design.md §3.3).
 */
@JvmInline value class ParticipantId(val value: Long)
@JvmInline value class ExchangeOrderId(val value: Long)
@JvmInline value class Price(val value: Long)
@JvmInline value class Quantity(val value: Long)

const val NULL_INDEX = -1
const val NULL_LEVEL = -1

/** Auction produced no trade. */
const val NO_UNCROSS = Long.MIN_VALUE

const val BPS_DENOMINATOR = 10_000L

/**
 * Field offsets within the packed order pool. Stride is 8 longs = 64 bytes = exactly one
 * cache line, so touching an order costs one cache miss rather than seven (Design.md §3.1).
 *
 * The eight words are full, so anything else an order needs goes in [ColdField] rather than
 * evicting a field from here or doubling the stride.
 */
object OrderField {
    const val STRIDE = 8

    const val PRICE = 0
    const val LEAVES_QTY = 1
    const val PARTICIPANT_ID = 2

    /** Effective SMP id: the order's smpId, or participantId when smpId == 0. */
    const val SMP_ID = 3
    const val CL_ORD_ID = 4
    const val EXCHANGE_ORDER_ID = 5

    /** next: low int32 | prev: high int32 */
    const val LINKS = 6

    /** expireDate: int32 | side: byte 4 | smpStrategy: byte 5 */
    const val META = 7
}

/**
 * A second, cold word per order, held in a parallel array rather than in the cache line above.
 *
 * It is touched only where an execution report is generated or the book is walked for a snapshot
 * — never inside the matching loop — so it costs a cache miss on paths that were already taking
 * one and nothing at all on the path the budget in Design.md §2 is about. This is where an order
 * attribute that matching does not read belongs: a future validity (at-open, IOC) goes here, and
 * the hot 64 bytes stay 64 bytes.
 *
 * [ORIG_QTY] is 0 for an order restored from a version 2 snapshot, which predates the field. That
 * value means *unknown* and is carried through to `Enrichment.UNKNOWN` on the wire rather than
 * being turned into a number nobody can justify.
 */
object ColdField {
    const val STRIDE = 1

    const val ORIG_QTY = 0
}

fun packLinks(next: Int, prev: Int): Long =
    (next.toLong() and 0xFFFF_FFFFL) or (prev.toLong() shl 32)

fun nextOf(links: Long): Int = links.toInt()

fun prevOf(links: Long): Int = (links ushr 32).toInt()

fun packMeta(expireDate: Int, side: Byte, smpStrategy: Byte): Long =
    (expireDate.toLong() and 0xFFFF_FFFFL) or
        ((side.toLong() and 0xFF) shl 32) or
        ((smpStrategy.toLong() and 0xFF) shl 40)

fun expireDateOf(meta: Long): Int = meta.toInt()

fun sideOf(meta: Long): Byte = ((meta ushr 32) and 0xFF).toByte()

fun smpStrategyOf(meta: Long): Byte = ((meta ushr 40) and 0xFF).toByte()

object Side {
    const val BUY: Byte = 0
    const val SELL: Byte = 1
}

object Phase {
    const val CLOSED: Byte = 0
    const val PRE_OPEN: Byte = 1
    const val OPEN_AUCTION: Byte = 2
    const val CONTINUOUS: Byte = 3
}

object RejectReason {
    const val NONE = 0
    const val PRICE_OUT_OF_BOUNDS = 1
    const val ORDER_EXPIRED = 2
    const val UNKNOWN_ORDER = 3
    const val UNAUTHORIZED_PARTICIPANT = 4
    const val MARKET_CLOSED = 5
    const val UNKNOWN_SECURITY = 6
    const val BOOK_CAPACITY = 7
    const val PRICE_OUT_OF_LADDER = 8
    const val SELF_MATCH_PREVENTED = 9
    const val VOLATILITY_HALT = 10

    /** Emitted only by the gateway; kept here so this stays a faithful mirror of the wire. */
    const val GATEWAY_UNAVAILABLE = 11
}

object RemoveReason {
    const val CANCELED: Byte = 0
    const val FILLED: Byte = 1
    const val EXPIRED: Byte = 2
}

object SmpStrategy {
    const val CANCEL_AGGRESSOR: Byte = 0
    const val CANCEL_RESTING: Byte = 1
}

/**
 * Resolved once, at order entry. Only the effective value is stored. Design.md §4.5.
 *
 * Not `inline`: these helpers take no function parameters and box nothing, so the keyword
 * buys nothing the JIT/AOT compiler does not already do. `inline` is reserved for the
 * hot-path functions that take callbacks, where it prevents lambda materialisation and
 * value-class boxing.
 */
fun effectiveSmpId(smpId: Long, participantId: Long): Long =
    if (smpId != 0L) smpId else participantId

object MatchStatus {
    /** Filled, or rested with no obstruction. */
    const val COMPLETE: Byte = 0

    /** CANCEL_AGGRESSOR fired. */
    const val SELF_MATCH_STOP: Byte = 1

    /** Dynamic collar breached -> volatility halt (Design.md §4.6). */
    const val COLLAR_BREACH: Byte = 2
}

/**
 * Reusable scratch for a cancel request: the engine must read the order's fields for the
 * execution report before [OrderBook.unlink] frees the slot.
 */
class CancelOutcome {
    var rejectReason: Int = RejectReason.NONE
    var nodeIndex: Int = NULL_INDEX
    var price: Long = 0L
    var leavesQty: Long = 0L
    var side: Byte = Side.BUY

    val isSuccess: Boolean get() = rejectReason == RejectReason.NONE
}

/** Reusable scratch describing why an aggressive order stopped. Never allocated per order. */
class MatchOutcome {
    var filledQty: Long = 0L
    var status: Byte = MatchStatus.COMPLETE
    var collarReference: Long = 0L
    var attemptedPrice: Long = 0L
    var breachedBound: Long = 0L

    fun reset() {
        filledQty = 0L
        status = MatchStatus.COMPLETE
        collarReference = 0L
        attemptedPrice = 0L
        breachedBound = 0L
    }
}
