package com.engine.core

/**
 * Books use tickSize 1 and priceFloor 0, so price == level and the tests read as prices.
 */
fun newBook(
    levelCount: Int = 1024,
    maxOrders: Int = 512,
    staticCollarBps: Int = 0,
    dynamicCollarBps: Int = 0,
    reference: Long = 100L,
): OrderBook = OrderBook(
    securityId = 1,
    priceFloor = 0L,
    tickSize = 1L,
    levelCount = levelCount,
    maxOrders = maxOrders,
).apply {
    phase = Phase.CONTINUOUS
    tradingDate = 20260829
    staticReference = reference
    dynamicReference = reference
    this.staticCollarBps = staticCollarBps
    this.dynamicCollarBps = dynamicCollarBps
}

/** Monotonic ids, mirroring the engine's assignment order (later order == higher id). */
class Ids {
    private var next = 1L
    fun next(): Long = next++
}

/**
 * Note the default [participantId]: two orders added with it share an effective SMP id and
 * will self-match. Auction tests that want a clean cross must pass distinct participants.
 */
fun OrderBook.add(
    ids: Ids,
    side: Byte,
    price: Long,
    qty: Long,
    participantId: Long = 1L,
    smpId: Long = 0L,
    clOrdId: Long = 0L,
    expireDate: Int = 0,
    smpStrategy: Byte = SmpStrategy.CANCEL_AGGRESSOR,
    origQty: Long = qty,
): Long {
    val id = ids.next()
    book(
        exchangeOrderId = id,
        participantId = participantId,
        smpId = effectiveSmpId(smpId, participantId),
        clOrdId = if (clOrdId != 0L) clOrdId else id,
        price = price,
        leavesQty = qty,
        origQty = origQty,
        expireDate = expireDate,
        side = side,
        smpStrategy = smpStrategy,
    )
    return id
}

class Fill(val makerOrderId: Long, val price: Long, val qty: Long, val makerLeaves: Long)

/** Drives an aggressive order and records the fills in order. */
fun OrderBook.aggress(
    side: Byte,
    price: Long,
    qty: Long,
    smpId: Long,
    smpStrategy: Byte = SmpStrategy.CANCEL_AGGRESSOR,
    outcome: MatchOutcome = MatchOutcome(),
    canceledResting: MutableList<Long> = mutableListOf(),
): Pair<MatchOutcome, List<Fill>> {
    val fills = mutableListOf<Fill>()
    matchAggressive(
        takerPrice = price,
        takerQty = qty,
        takerSide = side,
        takerSmpId = smpId,
        takerSmpStrategy = smpStrategy,
        outcome = outcome,
        onFill = { maker, fillPrice, fillQty, makerLeaves ->
            fills += Fill(exchangeOrderIdOf(maker), fillPrice, fillQty, makerLeaves)
        },
        onSelfMatchCancelResting = { maker -> canceledResting += exchangeOrderIdOf(maker) },
    )
    return outcome to fills
}

class AuctionTrade(val buyOrderId: Long, val sellOrderId: Long, val price: Long, val qty: Long)

class AuctionResult(
    val price: Long,
    val trades: List<AuctionTrade>,
    val canceled: List<Long>,
    val passLimitHit: Boolean,
)

fun OrderBook.runUncross(maxPasses: Int = 16, levelCount: Int = 1024): AuctionResult {
    val trades = mutableListOf<AuctionTrade>()
    val canceled = mutableListOf<Long>()
    var limitHit = false
    val price = uncross(
        scratch = LongArray(levelCount),
        maxPasses = maxPasses,
        onSelfMatchCancel = { node -> canceled += exchangeOrderIdOf(node) },
        onPassLimitExceeded = { limitHit = true },
        onFill = { buy, sell, p, q ->
            trades += AuctionTrade(exchangeOrderIdOf(buy), exchangeOrderIdOf(sell), p, q)
        },
    )
    return AuctionResult(price, trades, canceled, limitHit)
}
