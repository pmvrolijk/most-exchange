package com.engine.core

import org.agrona.collections.Long2LongHashMap

/**
 * One security's book: a packed order pool plus two price ladders. See Design.md §3 and §4.
 *
 * Single-threaded and allocation-free in steady state. Every hot-path callback is taken by an
 * `inline fun`, which is why the pool, the ladders and the best-level hints are
 * `@PublishedApi internal` rather than `private` — a public inline function cannot read
 * private members.
 */
class OrderBook(
    val securityId: Int,
    // Geometry is fixed at construction: the ladders are pre-allocated against it. Exposed so
    // the service can validate an incoming SecurityDefinition rather than half-apply it.
    val priceFloor: Long,
    val tickSize: Long,
    val levelCount: Int = 65_536,
    val maxOrders: Int = 1_000_000,
    private val capacityHighWaterMark: Int = (maxOrders * 0.95).toInt(),
) {
    var phase: Byte = Phase.CLOSED
    var tradingDate: Int = 0

    /** Session anchor for the static collar; reset by every executing uncross (§4.4). */
    var staticReference: Long = 0L

    /** Tracks every trade; anchors the dynamic collar and the auction tie-break (§4.4). */
    var dynamicReference: Long = 0L

    var staticCollarBps: Int = 0
    var dynamicCollarBps: Int = 0

    @PublishedApi internal val orders = LongArray(maxOrders * OrderField.STRIDE)

    // The cold word of Design.md §3.1, in its own array so the packed pool above stays exactly
    // one cache line per order. Read where a report is generated or the book is snapshotted,
    // never inside the matching loop.
    @PublishedApi internal val cold = LongArray(maxOrders * ColdField.STRIDE)
    @PublishedApi internal val bids = PriceLadder(levelCount)
    @PublishedApi internal val asks = PriceLadder(levelCount)
    @PublishedApi internal var bestBidLevel = NULL_LEVEL
    @PublishedApi internal var bestAskLevel = NULL_LEVEL

    // Agrona has no Long2IntHashMap; Long2LongHashMap is the narrowest primitive map with a
    // long key, so the int node index is widened to a long value.
    private val orderIdToIndex =
        Long2LongHashMap(maxOrders * 2, 0.65f, NULL_INDEX.toLong())
    private var freeHead = 0
    private var usedCount = 0

    // Auction cursor state, reused across passes. Safe as instance fields: single-threaded.
    @PublishedApi internal var curBuyLevel = NULL_LEVEL
    @PublishedApi internal var curBuyNode = NULL_INDEX
    @PublishedApi internal var curBuyRem = 0L
    @PublishedApi internal var curSellLevel = NULL_LEVEL
    @PublishedApi internal var curSellNode = NULL_INDEX
    @PublishedApi internal var curSellRem = 0L

    init {
        for (i in 0 until maxOrders - 1) {
            orders[i * OrderField.STRIDE + OrderField.LINKS] = packLinks(i + 1, NULL_INDEX)
        }
        orders[(maxOrders - 1) * OrderField.STRIDE + OrderField.LINKS] =
            packLinks(NULL_INDEX, NULL_INDEX)
    }

    // ---------------------------------------------------------------- geometry

    fun levelOf(price: Long): Int = ((price - priceFloor) / tickSize).toInt()

    fun priceOf(level: Int): Long = priceFloor + level.toLong() * tickSize

    fun isLevelInRange(level: Int): Boolean = level in 0 until levelCount

    fun isPriceOnTick(price: Long): Boolean = (price - priceFloor) % tickSize == 0L

    fun hasCapacity(): Boolean = usedCount < capacityHighWaterMark

    /**
     * Orders currently resting, in O(1). The snapshot reads it at the moment it writes a book's
     * header -- before walking that book's orders -- so that a restore can decide what to do about
     * a security the new geometry no longer hosts before it has booked any of them.
     */
    fun restingOrderCount(): Int = usedCount

    fun bestBid(): Int = bestBidLevel

    fun bestAsk(): Int = bestAskLevel

    fun indexOf(exchangeOrderId: Long): Int = orderIdToIndex.get(exchangeOrderId).toInt()

    // ------------------------------------------------------------ field access

    fun orderPrice(node: Int): Long = orders[node * OrderField.STRIDE + OrderField.PRICE]

    fun leavesQtyOf(node: Int): Long = orders[node * OrderField.STRIDE + OrderField.LEAVES_QTY]

    fun participantIdOf(node: Int): Long =
        orders[node * OrderField.STRIDE + OrderField.PARTICIPANT_ID]

    fun smpIdOf(node: Int): Long = orders[node * OrderField.STRIDE + OrderField.SMP_ID]

    fun clOrdIdOf(node: Int): Long = orders[node * OrderField.STRIDE + OrderField.CL_ORD_ID]

    fun exchangeOrderIdOf(node: Int): Long =
        orders[node * OrderField.STRIDE + OrderField.EXCHANGE_ORDER_ID]

    fun sideOfOrder(node: Int): Byte = sideOf(orders[node * OrderField.STRIDE + OrderField.META])

    fun expireDateOfOrder(node: Int): Int =
        expireDateOf(orders[node * OrderField.STRIDE + OrderField.META])

    /** The quantity the order was entered with. 0 means unknown — see [ColdField]. */
    fun origQtyOf(node: Int): Long = cold[node * ColdField.STRIDE + ColdField.ORIG_QTY]

    // ----------------------------------------------------------------- collars

    /**
     * Static collar, applied at order acceptance against the session anchor (§4.4). The ladder
     * range is configured wider than this band, so it always fires before a ladder overflow.
     */
    fun isPriceOutOfBounds(price: Long): Boolean {
        if (staticCollarBps == 0) return false
        val bound = staticReference * staticCollarBps / BPS_DENOMINATOR
        val deviation = if (price >= staticReference) price - staticReference
        else staticReference - price
        return deviation > bound
    }

    /**
     * Dynamic collar bound for an aggressing order. [collarReference] is the snapshot taken at
     * the order's arrival, deliberately not the live [dynamicReference] which that order's own
     * fills are advancing — see Design.md §4.4 on ratcheting.
     */
    fun dynamicBound(collarReference: Long): Long =
        collarReference * dynamicCollarBps / BPS_DENOMINATOR

    fun breachesDynamicCollar(price: Long, collarReference: Long, bound: Long): Boolean {
        if (dynamicCollarBps == 0) return false
        val deviation = if (price >= collarReference) price - collarReference
        else collarReference - price
        return deviation > bound
    }

    // ----------------------------------------------------------------- booking

    /**
     * Links a new order at the tail of its price level, preserving time priority.
     * [smpId] must already be resolved via [effectiveSmpId]. Returns the pool index.
     */
    fun book(
        exchangeOrderId: Long,
        participantId: Long,
        smpId: Long,
        clOrdId: Long,
        price: Long,
        leavesQty: Long,
        origQty: Long,
        expireDate: Int,
        side: Byte,
        smpStrategy: Byte,
    ): Int {
        val node = freeHead
        check(node != NULL_INDEX) { "order pool exhausted despite high-water-mark guard" }
        freeHead = nextOf(orders[node * OrderField.STRIDE + OrderField.LINKS])
        usedCount++

        val base = node * OrderField.STRIDE
        orders[base + OrderField.PRICE] = price
        orders[base + OrderField.LEAVES_QTY] = leavesQty
        orders[base + OrderField.PARTICIPANT_ID] = participantId
        orders[base + OrderField.SMP_ID] = smpId
        orders[base + OrderField.CL_ORD_ID] = clOrdId
        orders[base + OrderField.EXCHANGE_ORDER_ID] = exchangeOrderId
        orders[base + OrderField.META] = packMeta(expireDate, side, smpStrategy)
        cold[node * ColdField.STRIDE + ColdField.ORIG_QTY] = origQty

        val ladder = if (side == Side.BUY) bids else asks
        val level = levelOf(price)
        val prevTail = ladder.tail[level]
        orders[base + OrderField.LINKS] = packLinks(NULL_INDEX, prevTail)

        if (prevTail == NULL_INDEX) {
            ladder.head[level] = node
            ladder.markOccupied(level)
            if (side == Side.BUY) {
                if (bestBidLevel == NULL_LEVEL || level > bestBidLevel) bestBidLevel = level
            } else {
                if (bestAskLevel == NULL_LEVEL || level < bestAskLevel) bestAskLevel = level
            }
        } else {
            val pb = prevTail * OrderField.STRIDE + OrderField.LINKS
            orders[pb] = packLinks(node, prevOf(orders[pb]))
        }
        ladder.tail[level] = node
        ladder.levelQty[level] += leavesQty
        ladder.orderCount[level]++

        orderIdToIndex.put(exchangeOrderId, node.toLong())
        return node
    }

    /**
     * Removes an order from its level FIFO, the id map and the aggregates, and returns its slot
     * to the free list. The caller must read any fields it needs for reporting first.
     */
    @PublishedApi
    internal fun unlink(node: Int) {
        val base = node * OrderField.STRIDE
        val meta = orders[base + OrderField.META]
        val side = sideOf(meta)
        val ladder = if (side == Side.BUY) bids else asks
        val level = levelOf(orders[base + OrderField.PRICE])
        val links = orders[base + OrderField.LINKS]
        val next = nextOf(links)
        val prev = prevOf(links)

        if (prev == NULL_INDEX) {
            ladder.head[level] = next
        } else {
            val pb = prev * OrderField.STRIDE + OrderField.LINKS
            orders[pb] = packLinks(next, prevOf(orders[pb]))
        }
        if (next == NULL_INDEX) {
            ladder.tail[level] = prev
        } else {
            val nb = next * OrderField.STRIDE + OrderField.LINKS
            orders[nb] = packLinks(nextOf(orders[nb]), prev)
        }

        ladder.levelQty[level] -= orders[base + OrderField.LEAVES_QTY]
        ladder.orderCount[level]--

        if (ladder.head[level] == NULL_INDEX) {
            ladder.markEmpty(level)
            if (side == Side.BUY && level == bestBidLevel) {
                bestBidLevel = bids.highestOccupiedAtOrBelow(level - 1)
            } else if (side == Side.SELL && level == bestAskLevel) {
                bestAskLevel = asks.lowestOccupiedAtOrAbove(level + 1)
            }
        }

        orderIdToIndex.remove(orders[base + OrderField.EXCHANGE_ORDER_ID])
        orders[base + OrderField.LINKS] = packLinks(freeHead, NULL_INDEX)
        freeHead = node
        usedCount--
    }

    /**
     * Validates a cancel request and captures the order's details into [outcome] for reporting.
     * Does not unlink — the caller emits its reports, then calls [unlink] on
     * [CancelOutcome.nodeIndex]. Full cancellation only; there are no partial cancels (§4.7).
     */
    fun prepareCancel(
        exchangeOrderId: Long,
        participantId: Long,
        origClOrdId: Long,
        outcome: CancelOutcome,
    ): Boolean {
        val node = orderIdToIndex.get(exchangeOrderId).toInt()
        outcome.nodeIndex = node
        if (node == NULL_INDEX) {
            outcome.rejectReason = RejectReason.UNKNOWN_ORDER
            return false
        }
        val base = node * OrderField.STRIDE
        if (orders[base + OrderField.PARTICIPANT_ID] != participantId ||
            orders[base + OrderField.CL_ORD_ID] != origClOrdId
        ) {
            outcome.rejectReason = RejectReason.UNAUTHORIZED_PARTICIPANT
            return false
        }
        outcome.rejectReason = RejectReason.NONE
        outcome.price = orders[base + OrderField.PRICE]
        outcome.leavesQty = orders[base + OrderField.LEAVES_QTY]
        outcome.side = sideOf(orders[base + OrderField.META])
        return true
    }

    // ------------------------------------------------------- continuous matching

    /**
     * Walks the opposite ladder from the touch while the taker price crosses, consuming each
     * level's FIFO in time priority.
     *
     * Two gates, in this order (§4.5): the dynamic collar per **level**, before any fill at that
     * level; then self-match prevention per **resting order**, before the fill is applied.
     *
     * Results land in [outcome]; both callbacks are inlined, so no lambda is materialised and
     * the pool fields are read without boxing.
     */
    inline fun matchAggressive(
        takerPrice: Long,
        takerQty: Long,
        takerSide: Byte,
        takerSmpId: Long,
        takerSmpStrategy: Byte,
        outcome: MatchOutcome,
        onFill: (makerNode: Int, fillPrice: Long, fillQty: Long, makerLeavesQty: Long) -> Unit,
        onSelfMatchCancelResting: (makerNode: Int) -> Unit,
    ) {
        val collarReference = dynamicReference
        val bound = dynamicBound(collarReference)
        var remaining = takerQty

        outcome.status = MatchStatus.COMPLETE
        outcome.collarReference = collarReference
        outcome.attemptedPrice = 0L
        outcome.breachedBound = 0L

        levels@ while (remaining > 0) {
            val level = if (takerSide == Side.BUY) bestAskLevel else bestBidLevel
            if (level == NULL_LEVEL) break
            val levelPrice = priceOf(level)
            val crosses =
                if (takerSide == Side.BUY) levelPrice <= takerPrice else levelPrice >= takerPrice
            if (!crosses) break

            if (breachesDynamicCollar(levelPrice, collarReference, bound)) {
                outcome.status = MatchStatus.COLLAR_BREACH
                outcome.attemptedPrice = levelPrice
                outcome.breachedBound = bound
                break@levels
            }

            val ladder = if (takerSide == Side.BUY) asks else bids
            while (remaining > 0) {
                val maker = ladder.head[level]
                if (maker == NULL_INDEX) break
                val mb = maker * OrderField.STRIDE

                if (orders[mb + OrderField.SMP_ID] == takerSmpId) {
                    if (takerSmpStrategy == SmpStrategy.CANCEL_AGGRESSOR) {
                        outcome.status = MatchStatus.SELF_MATCH_STOP
                        break@levels
                    }
                    onSelfMatchCancelResting(maker)
                    unlink(maker)
                    continue
                }

                val makerLeaves = orders[mb + OrderField.LEAVES_QTY]
                val fill = if (makerLeaves <= remaining) makerLeaves else remaining
                val makerAfter = makerLeaves - fill
                orders[mb + OrderField.LEAVES_QTY] = makerAfter
                ladder.levelQty[level] -= fill
                remaining -= fill

                dynamicReference = levelPrice
                onFill(maker, levelPrice, fill, makerAfter)
                if (makerAfter == 0L) unlink(maker)
            }
        }
        outcome.filledQty = takerQty - remaining
    }

    // ------------------------------------------------------------- auction

    /**
     * The uncrossing price (§4.2): maximum executable volume, then minimum absolute imbalance,
     * then surplus side, then nearest [dynamicReference].
     *
     * Two passes so the surplus-side rule is applied across *all* tied prices rather than
     * greedily: a single scan cannot tell whether the surplus is consistently on one side.
     * [scratch] must have at least `levelCount` entries and is reused, never allocated.
     *
     * Returns [NO_UNCROSS] when the book does not cross.
     */
    fun computeUncrossPrice(scratch: LongArray): Long {
        val bb = bestBidLevel
        val ba = bestAskLevel
        if (bb == NULL_LEVEL || ba == NULL_LEVEL || ba > bb) return NO_UNCROSS

        var demand = 0L
        for (level in bb downTo ba) {
            demand += bids.levelQty[level]
            scratch[level] = demand
        }

        var supply = 0L
        var bestVolume = -1L
        var bestImbalance = Long.MAX_VALUE
        for (level in ba..bb) {
            supply += asks.levelQty[level]
            val d = scratch[level]
            val volume = if (d < supply) d else supply
            val imbalance = d - supply
            val absImbalance = if (imbalance < 0) -imbalance else imbalance
            if (volume > bestVolume || (volume == bestVolume && absImbalance < bestImbalance)) {
                bestVolume = volume
                bestImbalance = absImbalance
            }
        }
        if (bestVolume <= 0L) return NO_UNCROSS

        var anyBuySurplus = false
        var anySellSurplus = false
        var lowestTied = NULL_LEVEL
        var highestTied = NULL_LEVEL
        var nearestTied = NULL_LEVEL

        supply = 0L
        for (level in ba..bb) {
            supply += asks.levelQty[level]
            val d = scratch[level]
            val volume = if (d < supply) d else supply
            val imbalance = d - supply
            val absImbalance = if (imbalance < 0) -imbalance else imbalance
            if (volume != bestVolume || absImbalance != bestImbalance) continue

            if (imbalance > 0) anyBuySurplus = true
            if (imbalance < 0) anySellSurplus = true
            if (lowestTied == NULL_LEVEL) lowestTied = level
            highestTied = level

            val price = priceOf(level)
            if (nearestTied == NULL_LEVEL) {
                nearestTied = level
            } else {
                val best = priceOf(nearestTied)
                val dNew = if (price >= dynamicReference) price - dynamicReference
                else dynamicReference - price
                val dOld = if (best >= dynamicReference) best - dynamicReference
                else dynamicReference - best
                if (dNew < dOld) nearestTied = level
            }
        }

        val chosen = when {
            anyBuySurplus && !anySellSurplus -> highestTied
            anySellSurplus && !anyBuySurplus -> lowestTied
            else -> nearestTied
        }
        return priceOf(chosen)
    }

    /**
     * Runs the auction to a fixed point (§4.5). The uncross price and the set of SMP
     * cancellations are mutually dependent, so recompute until a walk cancels nothing.
     *
     * Terminates because every iteration either exits or cancels at least one order, and
     * cancellation is monotonic over a finite book. [maxPasses] is a safety valve, not part of
     * the algorithm: exceeding it signals a pathological book or a defect.
     *
     * Returns the executed uncross price, or [NO_UNCROSS] when the book does not cross.
     */
    inline fun uncross(
        scratch: LongArray,
        maxPasses: Int,
        onSelfMatchCancel: (node: Int) -> Unit,
        onPassLimitExceeded: () -> Unit,
        onFill: (buyNode: Int, sellNode: Int, price: Long, qty: Long) -> Unit,
    ): Long {
        var pass = 0
        while (true) {
            val price = computeUncrossPrice(scratch)
            if (price == NO_UNCROSS) return NO_UNCROSS

            if (++pass > maxPasses) {
                onPassLimitExceeded()
                return NO_UNCROSS
            }

            val uncrossLevel = levelOf(price)
            if (resolveSelfMatches(uncrossLevel, onSelfMatchCancel) == 0) {
                executeAllocation(uncrossLevel, price, onFill)
                onUncrossExecuted(price)
                return price
            }
            // Cancellations removed volume, so the price may have moved. Recompute.
        }
    }

    /**
     * One two-cursor merge over both sides in price-time priority, pairing every buy that
     * crosses [uncrossLevel] against every such sell, and resolving each self-match it meets.
     *
     * On a pair sharing an effective smpId the later order (higher exchangeOrderId) plays the
     * aggressor and its strategy decides which side is canceled. The cursor then advances past
     * the canceled order and the walk continues, which is exactly a recompute at the same
     * price — so no restart is needed, and no order is canceled on a pairing that an earlier
     * cancellation in this same walk already invalidated.
     *
     * Cancels and reports; prints no trades. Returns the number of orders canceled.
     */
    inline fun resolveSelfMatches(uncrossLevel: Int, onSelfMatchCancel: (node: Int) -> Unit): Int {
        startAuctionCursors(uncrossLevel)
        var canceled = 0

        while (curBuyNode != NULL_INDEX && curSellNode != NULL_INDEX) {
            val bBase = curBuyNode * OrderField.STRIDE
            val sBase = curSellNode * OrderField.STRIDE

            if (orders[bBase + OrderField.SMP_ID] == orders[sBase + OrderField.SMP_ID]) {
                val buyIsLater = orders[bBase + OrderField.EXCHANGE_ORDER_ID] >
                    orders[sBase + OrderField.EXCHANGE_ORDER_ID]
                val laterBase = if (buyIsLater) bBase else sBase
                val cancelBuy =
                    if (smpStrategyOf(orders[laterBase + OrderField.META]) ==
                        SmpStrategy.CANCEL_AGGRESSOR
                    ) buyIsLater else !buyIsLater

                if (cancelBuy) {
                    val successor = nextOf(orders[bBase + OrderField.LINKS])
                    val node = curBuyNode
                    onSelfMatchCancel(node)
                    unlink(node)
                    setBuyCursor(successor, uncrossLevel)
                } else {
                    val successor = nextOf(orders[sBase + OrderField.LINKS])
                    val node = curSellNode
                    onSelfMatchCancel(node)
                    unlink(node)
                    setSellCursor(successor, uncrossLevel)
                }
                canceled++
                continue
            }

            val fill = if (curBuyRem <= curSellRem) curBuyRem else curSellRem
            curBuyRem -= fill
            curSellRem -= fill
            if (curBuyRem == 0L) {
                setBuyCursor(nextOf(orders[bBase + OrderField.LINKS]), uncrossLevel)
            }
            if (curSellRem == 0L) {
                setSellCursor(nextOf(orders[sBase + OrderField.LINKS]), uncrossLevel)
            }
        }
        return canceled
    }

    /**
     * The same merge, run once the walk is stable, printing every fill at the single uncross
     * price. Guaranteed self-match free: [resolveSelfMatches] returned 0 for this exact book
     * and price.
     *
     * Needs no cursor state. A fully filled order is unlinked, which advances its level's head;
     * a partial fill only ever happens on the side that outlasts the other, which ends the walk.
     */
    inline fun executeAllocation(
        uncrossLevel: Int,
        price: Long,
        onFill: (buyNode: Int, sellNode: Int, price: Long, qty: Long) -> Unit,
    ) {
        while (bestBidLevel != NULL_LEVEL && bestBidLevel >= uncrossLevel &&
            bestAskLevel != NULL_LEVEL && bestAskLevel <= uncrossLevel
        ) {
            val buyLevel = bestBidLevel
            val sellLevel = bestAskLevel
            val buy = bids.head[buyLevel]
            val sell = asks.head[sellLevel]
            val bBase = buy * OrderField.STRIDE
            val sBase = sell * OrderField.STRIDE

            val buyLeaves = orders[bBase + OrderField.LEAVES_QTY]
            val sellLeaves = orders[sBase + OrderField.LEAVES_QTY]
            val fill = if (buyLeaves <= sellLeaves) buyLeaves else sellLeaves

            orders[bBase + OrderField.LEAVES_QTY] = buyLeaves - fill
            orders[sBase + OrderField.LEAVES_QTY] = sellLeaves - fill
            bids.levelQty[buyLevel] -= fill
            asks.levelQty[sellLevel] -= fill
            dynamicReference = price

            onFill(buy, sell, price, fill)

            if (buyLeaves - fill == 0L) unlink(buy)
            if (sellLeaves - fill == 0L) unlink(sell)
        }
    }

    /**
     * An uncross that executes sets both references (§4.4). Not called when the auction
     * produces no trade: an uncrossed book leaves both anchors untouched.
     */
    fun onUncrossExecuted(uncrossPrice: Long) {
        staticReference = uncrossPrice
        dynamicReference = uncrossPrice
    }

    @PublishedApi
    internal fun startAuctionCursors(uncrossLevel: Int) {
        curBuyLevel = bestBidLevel
        curSellLevel = bestAskLevel
        curBuyNode = NULL_INDEX
        curSellNode = NULL_INDEX
        if (curBuyLevel != NULL_LEVEL && curBuyLevel >= uncrossLevel) {
            setBuyCursor(bids.head[curBuyLevel], uncrossLevel)
        }
        if (curSellLevel != NULL_LEVEL && curSellLevel <= uncrossLevel) {
            setSellCursor(asks.head[curSellLevel], uncrossLevel)
        }
    }

    /** Positions the buy cursor on [node], or on the next crossing order below it. */
    @PublishedApi
    internal fun setBuyCursor(node: Int, uncrossLevel: Int) {
        var n = node
        while (n == NULL_INDEX) {
            if (curBuyLevel == NULL_LEVEL || curBuyLevel <= uncrossLevel) {
                curBuyNode = NULL_INDEX
                return
            }
            curBuyLevel = bids.highestOccupiedAtOrBelow(curBuyLevel - 1)
            if (curBuyLevel == NULL_LEVEL || curBuyLevel < uncrossLevel) {
                curBuyNode = NULL_INDEX
                return
            }
            n = bids.head[curBuyLevel]
        }
        curBuyNode = n
        curBuyRem = orders[n * OrderField.STRIDE + OrderField.LEAVES_QTY]
    }

    /** Positions the sell cursor on [node], or on the next crossing order above it. */
    @PublishedApi
    internal fun setSellCursor(node: Int, uncrossLevel: Int) {
        var n = node
        while (n == NULL_INDEX) {
            if (curSellLevel == NULL_LEVEL || curSellLevel >= uncrossLevel) {
                curSellNode = NULL_INDEX
                return
            }
            curSellLevel = asks.lowestOccupiedAtOrAbove(curSellLevel + 1)
            if (curSellLevel == NULL_LEVEL || curSellLevel > uncrossLevel) {
                curSellNode = NULL_INDEX
                return
            }
            n = asks.head[curSellLevel]
        }
        curSellNode = n
        curSellRem = orders[n * OrderField.STRIDE + OrderField.LEAVES_QTY]
    }

    // ---------------------------------------------------------------- snapshot

    /**
     * Visits every occupied level on one side, ascending, with the aggregates already kept there.
     *
     * This is what a book image is built from, and it is why an image costs occupied levels rather
     * than resting orders: [PriceLadder] already maintains `levelQty` and `orderCount` per level for
     * the auction's volume curves, so nothing has to be counted or stored to produce one.
     */
    inline fun forEachOccupiedLevel(
        isBid: Boolean,
        action: (price: Long, qty: Long, orders: Int) -> Unit,
    ) {
        val ladder = if (isBid) bids else asks
        var level = ladder.lowestOccupiedAtOrAbove(0)
        while (level != NULL_LEVEL) {
            action(priceOf(level), ladder.levelQty[level], ladder.orderCount[level])
            level = ladder.lowestOccupiedAtOrAbove(level + 1)
        }
    }

    /** Occupied levels across both sides, so an image can state its own length up front. */
    fun occupiedLevelCount(): Int = countOccupied(bids) + countOccupied(asks)

    @PublishedApi
    internal fun countOccupied(ladder: PriceLadder): Int {
        var count = 0
        var level = ladder.lowestOccupiedAtOrAbove(0)
        while (level != NULL_LEVEL) {
            count++
            level = ladder.lowestOccupiedAtOrAbove(level + 1)
        }
        return count
    }

    /**
     * Visits every resting order, in ladder order and FIFO within each level. Restoring by
     * replaying this order through [book] reproduces each level's queue exactly, because
     * booking appends at the tail.
     *
     * Walks only occupied levels, so the cost is proportional to resting orders rather than to
     * the 1M-slot pool (Design.md §6).
     */
    inline fun forEachRestingOrder(action: (node: Int) -> Unit) {
        forEachInLadder(bids, action)
        forEachInLadder(asks, action)
    }

    @PublishedApi
    internal inline fun forEachInLadder(ladder: PriceLadder, action: (node: Int) -> Unit) {
        var level = ladder.lowestOccupiedAtOrAbove(0)
        while (level != NULL_LEVEL) {
            var node = ladder.head[level]
            while (node != NULL_INDEX) {
                val successor = nextOf(orders[node * OrderField.STRIDE + OrderField.LINKS])
                action(node)
                node = successor
            }
            level = ladder.lowestOccupiedAtOrAbove(level + 1)
        }
    }

    fun smpStrategyOfOrder(node: Int): Byte =
        smpStrategyOf(orders[node * OrderField.STRIDE + OrderField.META])

    // ------------------------------------------------------------------ expiry

    /**
     * Off-session purge (§4.3). Walks the ladders rather than the id map: the map is
     * open-addressed and compacts its probe chain on removal, so iterator-based removal can
     * silently skip entries. An order expires when `0 < expireDate < currentTradingDate`;
     * `expireDate == 0` is good-til-cancelled.
     */
    inline fun purgeExpired(currentTradingDate: Int, onExpire: (node: Int) -> Unit) {
        purgeLadder(bids, currentTradingDate, onExpire)
        purgeLadder(asks, currentTradingDate, onExpire)
    }

    @PublishedApi
    internal inline fun purgeLadder(
        ladder: PriceLadder,
        currentTradingDate: Int,
        onExpire: (node: Int) -> Unit,
    ) {
        var level = ladder.lowestOccupiedAtOrAbove(0)
        while (level != NULL_LEVEL) {
            var node = ladder.head[level]
            while (node != NULL_INDEX) {
                val base = node * OrderField.STRIDE
                val successor = nextOf(orders[base + OrderField.LINKS])
                val expireDate = expireDateOf(orders[base + OrderField.META])
                if (expireDate in 1 until currentTradingDate) {
                    onExpire(node)
                    unlink(node)
                }
                node = successor
            }
            level = ladder.lowestOccupiedAtOrAbove(level + 1)
        }
    }
}
