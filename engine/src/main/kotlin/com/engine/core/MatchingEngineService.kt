package com.engine.core

import com.engine.sbe.AuctionUncrossedEncoder
import com.engine.sbe.ExecType
import com.engine.sbe.ExecutionReportEncoder
import com.engine.sbe.MessageHeaderDecoder
import com.engine.sbe.MessageHeaderEncoder
import com.engine.sbe.NewOrderSingleDecoder
import com.engine.sbe.OrderAddedEncoder
import com.engine.sbe.OrderCancelRequestDecoder
import com.engine.sbe.OrderReducedEncoder
import com.engine.sbe.OrderRemovedEncoder
import com.engine.sbe.PurgeExpiredOrdersDecoder
import com.engine.sbe.RemoveReason
import com.engine.sbe.SecurityDefinitionDecoder
import com.engine.sbe.SessionChangedEncoder
import com.engine.sbe.SessionTransitionDecoder
import com.engine.sbe.SnapshotBookDecoder
import com.engine.sbe.SnapshotBookEncoder
import com.engine.sbe.SnapshotEndEncoder
import com.engine.sbe.SnapshotEngineStateDecoder
import com.engine.sbe.SnapshotEngineStateEncoder
import com.engine.sbe.SnapshotOrderDecoder
import com.engine.sbe.SnapshotOrderEncoder
import com.engine.sbe.TradeExecutedEncoder
import com.engine.sbe.VolatilityHaltedEncoder
import io.aeron.ExclusivePublication
import io.aeron.Image
import io.aeron.Publication
import io.aeron.cluster.client.AeronCluster
import io.aeron.cluster.codecs.CloseReason
import io.aeron.cluster.service.ClientSession
import io.aeron.cluster.service.Cluster
import io.aeron.cluster.service.ClusteredService
import io.aeron.logbuffer.BufferClaim
import io.aeron.logbuffer.Header
import org.agrona.DirectBuffer
import org.agrona.MutableDirectBuffer
import org.agrona.collections.Long2LongHashMap
import com.engine.sbe.Phase as SbePhase
import com.engine.sbe.RejectReason as SbeRejectReason
import com.engine.sbe.Side as SbeSide
import com.engine.sbe.SmpStrategy as SbeSmpStrategy

/**
 * The deterministic matching engine as an Aeron Cluster service. See Design.md §1 and §6.
 *
 * Every node runs this over the identical replicated log, so the same input must produce the
 * same state everywhere. That constrains the implementation in two ways worth stating:
 *
 *  - **No wall clock.** Time comes only from the sequenced `timestamp` argument or from
 *    [Cluster.time]; never from `System.currentTimeMillis`.
 *  - **No crashing on bad input.** A throw is deterministic, so it kills every node at the same
 *    log position. Malformed input is rejected, not thrown on.
 *
 * Execution reports go out as cluster egress, which Aeron already mutes on followers
 * ([ClientSession.offer] returns `MOCKED_OFFER`). The book event stream is a plain publication
 * and is therefore muted explicitly via [onRoleChange].
 */
class MatchingEngineService(
    /**
     * Stamped on every book event. Shards number their streams independently, so without it two
     * shards publishing to one multicast group would interleave two sequences starting at 1 and
     * every subscriber would read the whole feed as gaps.
     */
    private val shardId: Int,
    private val books: Array<OrderBook>,
    private val bookEventChannel: String,
    private val bookEventStreamId: Int,
    private val levelCount: Int = 65_536,
    private val auctionMaxPasses: Int = 64,
    private val backpressureAlertThreshold: Int = 1_000_000,
) : ClusteredService {

    /**
     * At most ten securities per shard (Design.md §2), so a linear scan over a single
     * cache line beats any hash lookup.
     */
    private val securityIds = IntArray(books.size) { books[it].securityId }

    private lateinit var cluster: Cluster
    private var bookEventPub: ExclusivePublication? = null
    private var isLeader = false

    var nextExchangeOrderId = 1L
        private set

    /**
     * Stamped on every book event so downstream feeds can detect gaps. Incremented wherever the
     * event is *generated*, which happens identically on every node, so a new leader continues
     * the sequence rather than restarting it. Snapshotted with the rest of the engine state.
     */
    var nextBookEventSeqNum = 1L
        private set

    /**
     * participantId to cluster session id, learned from inbound traffic. A session belongs to
     * a gateway, not an end participant, and the maker side of a fill needs a route back
     * (Design.md §1). Deterministic: every node sees the same messages in the same order.
     */
    private val participantToSession = Long2LongHashMap(NULL_SESSION)

    private val matchOutcome = MatchOutcome()
    private val cancelOutcome = CancelOutcome()
    private val uncrossScratch = LongArray(levelCount)
    private val claim = BufferClaim()

    private val headerDecoder = MessageHeaderDecoder()
    private val headerEncoder = MessageHeaderEncoder()
    private val newOrderDecoder = NewOrderSingleDecoder()
    private val cancelDecoder = OrderCancelRequestDecoder()
    private val sessionTransitionDecoder = SessionTransitionDecoder()
    private val purgeDecoder = PurgeExpiredOrdersDecoder()
    private val securityDefinitionDecoder = SecurityDefinitionDecoder()

    private val execReportEncoder = ExecutionReportEncoder()
    private val orderAddedEncoder = OrderAddedEncoder()
    private val orderReducedEncoder = OrderReducedEncoder()
    private val orderRemovedEncoder = OrderRemovedEncoder()
    private val tradeExecutedEncoder = TradeExecutedEncoder()
    private val auctionUncrossedEncoder = AuctionUncrossedEncoder()
    private val sessionChangedEncoder = SessionChangedEncoder()
    private val volatilityHaltedEncoder = VolatilityHaltedEncoder()

    private val snapshotStateEncoder = SnapshotEngineStateEncoder()
    private val snapshotBookEncoder = SnapshotBookEncoder()
    private val snapshotOrderEncoder = SnapshotOrderEncoder()
    private val snapshotEndEncoder = SnapshotEndEncoder()

    // Counters. Not state: they never influence a decision, so they cannot diverge nodes.
    var undeliverableReports = 0L
        private set
    var droppedBookEvents = 0L
        private set
    var backpressureStalls = 0L
        private set
    var rejectedDefinitions = 0L
        private set
    var auctionPassLimitBreaches = 0L
        private set

    // ------------------------------------------------------------ lifecycle

    override fun onStart(cluster: Cluster, snapshotImage: Image?) {
        this.cluster = cluster
        this.isLeader = cluster.role() == Cluster.Role.LEADER
        this.bookEventPub =
            cluster.aeron().addExclusivePublication(bookEventChannel, bookEventStreamId)
        if (snapshotImage != null) loadSnapshot(snapshotImage)
    }

    override fun onRoleChange(newRole: Cluster.Role) {
        isLeader = newRole == Cluster.Role.LEADER
    }

    override fun onTerminate(cluster: Cluster) {
        bookEventPub?.close()
        bookEventPub = null
    }

    override fun onSessionOpen(session: ClientSession, timestamp: Long) = Unit

    override fun onSessionClose(
        session: ClientSession,
        timestamp: Long,
        closeReason: CloseReason,
    ) = Unit // Stale ids simply fail to resolve; no bookkeeping needed.

    override fun onTimerEvent(correlationId: Long, timestamp: Long) = Unit

    // -------------------------------------------------------------- ingress

    override fun onSessionMessage(
        session: ClientSession,
        timestamp: Long,
        buffer: DirectBuffer,
        offset: Int,
        length: Int,
        header: Header,
    ) {
        if (length < MessageHeaderDecoder.ENCODED_LENGTH) return
        headerDecoder.wrap(buffer, offset)
        val body = offset + MessageHeaderDecoder.ENCODED_LENGTH
        val blockLength = headerDecoder.blockLength()
        val version = headerDecoder.version()

        when (headerDecoder.templateId()) {
            NewOrderSingleDecoder.TEMPLATE_ID -> {
                newOrderDecoder.wrap(buffer, body, blockLength, version)
                onNewOrder(session)
            }

            OrderCancelRequestDecoder.TEMPLATE_ID -> {
                cancelDecoder.wrap(buffer, body, blockLength, version)
                onCancel(session)
            }

            SessionTransitionDecoder.TEMPLATE_ID -> {
                sessionTransitionDecoder.wrap(buffer, body, blockLength, version)
                onSessionTransition()
            }

            PurgeExpiredOrdersDecoder.TEMPLATE_ID -> {
                purgeDecoder.wrap(buffer, body, blockLength, version)
                onPurge()
            }

            SecurityDefinitionDecoder.TEMPLATE_ID -> {
                securityDefinitionDecoder.wrap(buffer, body, blockLength, version)
                onSecurityDefinition()
            }

            else -> Unit // Unknown template: ignore rather than throw. See the class KDoc.
        }
    }

    private fun onNewOrder(session: ClientSession) {
        val participantId = newOrderDecoder.participantId()
        val clOrdId = newOrderDecoder.clOrdId()
        val price = newOrderDecoder.price()
        val qty = newOrderDecoder.qty()
        val rawSmpId = newOrderDecoder.smpId()
        val securityId = newOrderDecoder.securityId()
        val expireDate = newOrderDecoder.expireDate()
        val side = newOrderDecoder.side().value()
        val smpStrategy = newOrderDecoder.smpStrategy().value()

        participantToSession.put(participantId, session.id())

        val bookIndex = indexOfSecurity(securityId)
        if (bookIndex < 0) {
            reject(participantId, clOrdId, securityId, side, price, RejectReason.UNKNOWN_SECURITY)
            return
        }
        val book = books[bookIndex]

        val reason = validateNewOrder(book, qty, price, expireDate)
        if (reason != RejectReason.NONE) {
            reject(participantId, clOrdId, securityId, side, price, reason)
            return
        }

        val smpId = effectiveSmpId(rawSmpId, participantId)
        val orderId = nextExchangeOrderId++

        sendExecutionReport(
            participantId, clOrdId, orderId, securityId, ExecType.NEW, side,
            price = price, lastQty = 0L, leavesQty = qty, rejectReason = RejectReason.NONE,
        )

        if (book.phase != Phase.CONTINUOUS) {
            // Booked in every phase except CLOSED; only matching is gated (Design.md §4.1).
            book.book(orderId, participantId, smpId, clOrdId, price, qty, expireDate, side, smpStrategy)
            publishOrderAdded(securityId, orderId, price, qty, side)
            return
        }

        var remaining = qty
        book.matchAggressive(
            takerPrice = price,
            takerQty = qty,
            takerSide = side,
            takerSmpId = smpId,
            takerSmpStrategy = smpStrategy,
            outcome = matchOutcome,
            onFill = { maker, fillPrice, fillQty, makerLeaves ->
                remaining -= fillQty
                val makerId = book.exchangeOrderIdOf(maker)
                val makerParticipant = book.participantIdOf(maker)
                val makerClOrdId = book.clOrdIdOf(maker)
                val makerSide = book.sideOfOrder(maker)

                // Taker's leavesQty is the RUNNING remainder, not origQty - thisFill.
                sendExecutionReport(
                    participantId, clOrdId, orderId, securityId, ExecType.TRADE, side,
                    price = fillPrice, lastQty = fillQty, leavesQty = remaining,
                    rejectReason = RejectReason.NONE,
                )
                sendExecutionReport(
                    makerParticipant, makerClOrdId, makerId, securityId, ExecType.TRADE, makerSide,
                    price = fillPrice, lastQty = fillQty, leavesQty = makerLeaves,
                    rejectReason = RejectReason.NONE,
                )
                publishTrade(securityId, fillPrice, fillQty, orderId, makerId, side)
                if (makerLeaves > 0L) {
                    publishOrderReduced(securityId, makerId, fillPrice, fillQty, makerLeaves, makerSide)
                } else {
                    publishOrderRemoved(securityId, makerId, fillPrice, 0L, makerSide, RemoveReason.FILLED)
                }
            },
            onSelfMatchCancelResting = { maker ->
                val makerId = book.exchangeOrderIdOf(maker)
                val makerPrice = book.orderPrice(maker)
                val makerSide = book.sideOfOrder(maker)
                sendExecutionReport(
                    book.participantIdOf(maker), book.clOrdIdOf(maker), makerId, securityId,
                    ExecType.CANCELED, makerSide, price = makerPrice, lastQty = 0L, leavesQty = 0L,
                    rejectReason = RejectReason.SELF_MATCH_PREVENTED,
                )
                publishOrderRemoved(
                    securityId, makerId, makerPrice, book.leavesQtyOf(maker), makerSide,
                    RemoveReason.CANCELED,
                )
            },
        )

        when (matchOutcome.status) {
            MatchStatus.COMPLETE -> if (remaining > 0L) {
                book.book(
                    orderId, participantId, smpId, clOrdId, price, remaining,
                    expireDate, side, smpStrategy,
                )
                publishOrderAdded(securityId, orderId, price, remaining, side)
            }

            MatchStatus.SELF_MATCH_STOP -> sendExecutionReport(
                participantId, clOrdId, orderId, securityId, ExecType.CANCELED, side,
                price = price, lastQty = 0L, leavesQty = 0L,
                rejectReason = RejectReason.SELF_MATCH_PREVENTED,
            )

            MatchStatus.COLLAR_BREACH -> {
                sendExecutionReport(
                    participantId, clOrdId, orderId, securityId, ExecType.CANCELED, side,
                    price = price, lastQty = 0L, leavesQty = 0L,
                    rejectReason = RejectReason.VOLATILITY_HALT,
                )
                haltSecurity(book, side)
            }

            else -> Unit
        }
    }

    /** Validation order follows Design.md §6; the static collar precedes the ladder backstop. */
    private fun validateNewOrder(
        book: OrderBook,
        qty: Long,
        price: Long,
        expireDate: Int,
    ): Int = when {
        book.phase == Phase.CLOSED -> RejectReason.MARKET_CLOSED
        expireDate in 1 until book.tradingDate -> RejectReason.ORDER_EXPIRED
        !book.hasCapacity() -> RejectReason.BOOK_CAPACITY
        qty <= 0L -> RejectReason.PRICE_OUT_OF_BOUNDS
        book.isPriceOutOfBounds(price) -> RejectReason.PRICE_OUT_OF_BOUNDS
        !book.isPriceOnTick(price) -> RejectReason.PRICE_OUT_OF_LADDER
        !book.isLevelInRange(book.levelOf(price)) -> RejectReason.PRICE_OUT_OF_LADDER
        else -> RejectReason.NONE
    }

    /**
     * A dynamic-collar breach closes the security and leaves the resting book intact
     * (Design.md §4.6). No timer is scheduled: recovery is operator-driven.
     */
    private fun haltSecurity(book: OrderBook, aggressorSide: Byte) {
        book.phase = Phase.CLOSED
        publishVolatilityHalted(
            book.securityId,
            matchOutcome.collarReference,
            matchOutcome.attemptedPrice,
            matchOutcome.breachedBound,
            aggressorSide,
        )
        publishSessionChanged(book.securityId, Phase.CLOSED)
    }

    private fun onCancel(session: ClientSession) {
        val participantId = cancelDecoder.participantId()
        val origClOrdId = cancelDecoder.origClOrdId()
        val clOrdId = cancelDecoder.clOrdId()
        val exchangeOrderId = cancelDecoder.exchangeOrderId()
        val securityId = cancelDecoder.securityId()
        val side = cancelDecoder.side().value()

        participantToSession.put(participantId, session.id())

        val bookIndex = indexOfSecurity(securityId)
        if (bookIndex < 0) {
            reject(participantId, clOrdId, securityId, side, 0L, RejectReason.UNKNOWN_SECURITY)
            return
        }
        val book = books[bookIndex]

        if (!book.prepareCancel(exchangeOrderId, participantId, origClOrdId, cancelOutcome)) {
            reject(participantId, clOrdId, securityId, side, 0L, cancelOutcome.rejectReason)
            return
        }

        val price = cancelOutcome.price
        val cancelSide = cancelOutcome.side
        sendExecutionReport(
            participantId, clOrdId, exchangeOrderId, securityId, ExecType.CANCELED, cancelSide,
            price = price, lastQty = 0L, leavesQty = 0L, rejectReason = RejectReason.NONE,
        )
        publishOrderRemoved(
            securityId, exchangeOrderId, price, cancelOutcome.leavesQty, cancelSide,
            RemoveReason.CANCELED,
        )
        book.unlink(cancelOutcome.nodeIndex)
    }

    private fun onSessionTransition() {
        val tradingDate = sessionTransitionDecoder.tradingDate()
        val targetPhase = sessionTransitionDecoder.targetPhase().value()

        for (book in books) {
            book.tradingDate = tradingDate
            if (targetPhase == Phase.CONTINUOUS && book.phase == Phase.OPEN_AUCTION) {
                runUncross(book)
            }
            book.phase = targetPhase
            publishSessionChanged(book.securityId, targetPhase)
        }
    }

    /** The opening uncross, including the SMP fixed-point loop (Design.md §4.2 and §4.5). */
    private fun runUncross(book: OrderBook) {
        val securityId = book.securityId
        var executedQty = 0L
        val price = book.uncross(
            scratch = uncrossScratch,
            maxPasses = auctionMaxPasses,
            onSelfMatchCancel = { node ->
                val orderId = book.exchangeOrderIdOf(node)
                val orderPrice = book.orderPrice(node)
                val side = book.sideOfOrder(node)
                sendExecutionReport(
                    book.participantIdOf(node), book.clOrdIdOf(node), orderId, securityId,
                    ExecType.CANCELED, side, price = orderPrice, lastQty = 0L, leavesQty = 0L,
                    rejectReason = RejectReason.SELF_MATCH_PREVENTED,
                )
                publishOrderRemoved(
                    securityId, orderId, orderPrice, book.leavesQtyOf(node), side,
                    RemoveReason.CANCELED,
                )
            },
            onPassLimitExceeded = { auctionPassLimitBreaches++ },
            onFill = { buy, sell, fillPrice, fillQty ->
                executedQty += fillQty
                reportAuctionSide(book, buy, securityId, fillPrice, fillQty)
                reportAuctionSide(book, sell, securityId, fillPrice, fillQty)
                publishTrade(
                    securityId, fillPrice, fillQty,
                    book.exchangeOrderIdOf(buy), book.exchangeOrderIdOf(sell), Side.BUY,
                )
            },
        )
        if (price != NO_UNCROSS) publishAuctionUncrossed(securityId, price, executedQty)
    }

    private fun reportAuctionSide(
        book: OrderBook,
        node: Int,
        securityId: Int,
        fillPrice: Long,
        fillQty: Long,
    ) {
        val orderId = book.exchangeOrderIdOf(node)
        val side = book.sideOfOrder(node)
        val leaves = book.leavesQtyOf(node)
        sendExecutionReport(
            book.participantIdOf(node), book.clOrdIdOf(node), orderId, securityId,
            ExecType.TRADE, side, price = fillPrice, lastQty = fillQty, leavesQty = leaves,
            rejectReason = RejectReason.NONE,
        )
        if (leaves > 0L) {
            publishOrderReduced(securityId, orderId, fillPrice, fillQty, leaves, side)
        } else {
            publishOrderRemoved(securityId, orderId, book.orderPrice(node), 0L, side, RemoveReason.FILLED)
        }
    }

    private fun onPurge() {
        val tradingDate = purgeDecoder.tradingDate()
        for (book in books) {
            val securityId = book.securityId
            book.purgeExpired(tradingDate) { node ->
                val orderId = book.exchangeOrderIdOf(node)
                val price = book.orderPrice(node)
                val side = book.sideOfOrder(node)
                sendExecutionReport(
                    book.participantIdOf(node), book.clOrdIdOf(node), orderId, securityId,
                    ExecType.EXPIRED, side, price = price, lastQty = 0L, leavesQty = 0L,
                    rejectReason = RejectReason.NONE,
                )
                publishOrderRemoved(
                    securityId, orderId, price, book.leavesQtyOf(node), side, RemoveReason.EXPIRED,
                )
            }
        }
    }

    /**
     * Seeds or re-seeds both references and the collar widths (Design.md §4.4). Re-issuing this
     * is the operator's lever for reopening a security whose halt price sits outside the static
     * band (§4.6).
     *
     * Geometry (`priceFloor`, `tickSize`, `levelCount`) is fixed when the book is constructed,
     * because the ladders are pre-allocated. A definition whose geometry disagrees with the
     * configured book is a configuration error and is rejected wholesale rather than half
     * applied.
     */
    private fun onSecurityDefinition() {
        val securityId = securityDefinitionDecoder.securityId()
        val bookIndex = indexOfSecurity(securityId)
        if (bookIndex < 0) {
            rejectedDefinitions++
            return
        }
        val book = books[bookIndex]
        if (securityDefinitionDecoder.levelCount() != book.levelCount ||
            securityDefinitionDecoder.tickSize() != book.tickSize ||
            securityDefinitionDecoder.priceFloor() != book.priceFloor
        ) {
            rejectedDefinitions++
            return
        }
        val reference = securityDefinitionDecoder.referencePrice()
        val staticBps = securityDefinitionDecoder.staticCollarBps()

        // Design.md §3.2: the ladder must be strictly wider than the static collar band, so that
        // collar rejection always fires first and PRICE_OUT_OF_LADDER stays unreachable. A
        // definition that breaks the invariant would have orders inside the band rejected by the
        // backstop instead, which is confusing and hides the real limit. Reject it instead.
        if (staticBps > 0) {
            val bound = reference * staticBps / BPS_DENOMINATOR
            if (!book.isLevelInRange(book.levelOf(reference - bound)) ||
                !book.isLevelInRange(book.levelOf(reference + bound))
            ) {
                rejectedDefinitions++
                return
            }
        }

        book.staticReference = reference
        book.dynamicReference = reference
        book.staticCollarBps = staticBps
        book.dynamicCollarBps = securityDefinitionDecoder.dynamicCollarBps()
    }

    private fun indexOfSecurity(securityId: Int): Int {
        for (i in securityIds.indices) if (securityIds[i] == securityId) return i
        return -1
    }

    // ------------------------------------------------------------- snapshot

    override fun onTakeSnapshot(snapshotPublication: ExclusivePublication) {
        offerToSnapshot(
            snapshotPublication,
            MessageHeaderEncoder.ENCODED_LENGTH + SnapshotEngineStateEncoder.BLOCK_LENGTH,
        ) { buffer, offset ->
            snapshotStateEncoder.wrapAndApplyHeader(buffer, offset, headerEncoder)
                .nextExchangeOrderId(nextExchangeOrderId)
                .nextBookEventSeqNum(nextBookEventSeqNum)
        }

        var total = 0L
        for (book in books) {
            offerToSnapshot(
                snapshotPublication,
                MessageHeaderEncoder.ENCODED_LENGTH + SnapshotBookEncoder.BLOCK_LENGTH,
            ) { buffer, offset ->
                snapshotBookEncoder.wrapAndApplyHeader(buffer, offset, headerEncoder)
                    .staticReference(book.staticReference)
                    .dynamicReference(book.dynamicReference)
                    .securityId(book.securityId)
                    .tradingDate(book.tradingDate)
                    .staticCollarBps(book.staticCollarBps)
                    .dynamicCollarBps(book.dynamicCollarBps)
                    .phase(SbePhase.get(book.phase))
            }
            book.forEachRestingOrder { node ->
                total++
                offerToSnapshot(
                    snapshotPublication,
                    MessageHeaderEncoder.ENCODED_LENGTH + SnapshotOrderEncoder.BLOCK_LENGTH,
                ) { buffer, offset ->
                    snapshotOrderEncoder.wrapAndApplyHeader(buffer, offset, headerEncoder)
                        .participantId(book.participantIdOf(node))
                        .smpId(book.smpIdOf(node))
                        .clOrdId(book.clOrdIdOf(node))
                        .exchangeOrderId(book.exchangeOrderIdOf(node))
                        .price(book.orderPrice(node))
                        .leavesQty(book.leavesQtyOf(node))
                        .securityId(book.securityId)
                        .expireDate(book.expireDateOfOrder(node))
                        .side(SbeSide.get(book.sideOfOrder(node)))
                        .smpStrategy(SbeSmpStrategy.get(book.smpStrategyOfOrder(node)))
                }
            }
        }

        offerToSnapshot(
            snapshotPublication,
            MessageHeaderEncoder.ENCODED_LENGTH + SnapshotEndEncoder.BLOCK_LENGTH,
        ) { buffer, offset ->
            snapshotEndEncoder.wrapAndApplyHeader(buffer, offset, headerEncoder)
                .restingOrderCount(total)
        }
    }

    /** Restores from a snapshot image. Orders arrive in ladder order, so FIFO is preserved. */
    private fun loadSnapshot(image: Image) {
        val bookDecoder = SnapshotBookDecoder()
        val orderDecoder = SnapshotOrderDecoder()
        val stateDecoder = SnapshotEngineStateDecoder()
        var done = false

        while (!done && !image.isEndOfStream) {
            image.poll({ buffer, offset, _, _ ->
                headerDecoder.wrap(buffer, offset)
                val body = offset + MessageHeaderDecoder.ENCODED_LENGTH
                val blockLength = headerDecoder.blockLength()
                val version = headerDecoder.version()
                when (headerDecoder.templateId()) {
                    SnapshotEngineStateDecoder.TEMPLATE_ID -> {
                        stateDecoder.wrap(buffer, body, blockLength, version)
                        nextExchangeOrderId = stateDecoder.nextExchangeOrderId()
                        nextBookEventSeqNum = stateDecoder.nextBookEventSeqNum()
                    }

                    SnapshotBookDecoder.TEMPLATE_ID -> {
                        bookDecoder.wrap(buffer, body, blockLength, version)
                        val index = indexOfSecurity(bookDecoder.securityId())
                        if (index >= 0) {
                            val book = books[index]
                            book.staticReference = bookDecoder.staticReference()
                            book.dynamicReference = bookDecoder.dynamicReference()
                            book.tradingDate = bookDecoder.tradingDate()
                            book.staticCollarBps = bookDecoder.staticCollarBps()
                            book.dynamicCollarBps = bookDecoder.dynamicCollarBps()
                            book.phase = bookDecoder.phase().value()
                        }
                    }

                    SnapshotOrderDecoder.TEMPLATE_ID -> {
                        orderDecoder.wrap(buffer, body, blockLength, version)
                        val index = indexOfSecurity(orderDecoder.securityId())
                        if (index >= 0) {
                            books[index].book(
                                exchangeOrderId = orderDecoder.exchangeOrderId(),
                                participantId = orderDecoder.participantId(),
                                smpId = orderDecoder.smpId(),
                                clOrdId = orderDecoder.clOrdId(),
                                price = orderDecoder.price(),
                                leavesQty = orderDecoder.leavesQty(),
                                expireDate = orderDecoder.expireDate(),
                                side = orderDecoder.side().value(),
                                smpStrategy = orderDecoder.smpStrategy().value(),
                            )
                        }
                    }

                    else -> done = true // SnapshotEnd
                }
            }, SNAPSHOT_POLL_LIMIT)
        }
    }

    private inline fun offerToSnapshot(
        publication: ExclusivePublication,
        length: Int,
        encode: (MutableDirectBuffer, Int) -> Unit,
    ) {
        val idle = cluster.idleStrategy()
        idle.reset()
        while (true) {
            val result = publication.tryClaim(length, claim)
            if (result > 0) {
                encode(claim.buffer(), claim.offset())
                claim.commit()
                return
            }
            check(result != Publication.CLOSED) { "snapshot publication closed" }
            idle.idle()
        }
    }

    // ---------------------------------------------------------------- egress

    private fun reject(
        participantId: Long,
        clOrdId: Long,
        securityId: Int,
        side: Byte,
        price: Long,
        rejectReason: Int,
    ) = sendExecutionReport(
        participantId, clOrdId, 0L, securityId, ExecType.REJECTED, side,
        price = price, lastQty = 0L, leavesQty = 0L, rejectReason = rejectReason,
    )

    /**
     * Routes an execution report to the session that owns [participantId]. Followers are muted
     * by Aeron itself: [ClientSession.offer] returns `MOCKED_OFFER` off the leader.
     *
     * A participant with no live session cannot be reached; the report is counted and dropped,
     * because blocking the engine thread on an absent consumer is worse than losing the report.
     */
    @Suppress("LongParameterList")
    private fun sendExecutionReport(
        participantId: Long,
        clOrdId: Long,
        exchangeOrderId: Long,
        securityId: Int,
        execType: ExecType,
        side: Byte,
        price: Long,
        lastQty: Long,
        leavesQty: Long,
        rejectReason: Int,
    ) {
        val sessionId = participantToSession.get(participantId)
        val session = if (sessionId == NULL_SESSION) null else cluster.getClientSession(sessionId)
        if (session == null) {
            undeliverableReports++
            return
        }

        val length = MessageHeaderEncoder.ENCODED_LENGTH + ExecutionReportEncoder.BLOCK_LENGTH
        var attempts = 0
        while (true) {
            val result = session.tryClaim(length, claim)
            if (result > 0 || result == ClientSession.MOCKED_OFFER) {
                if (result > 0) {
                    // Aeron reserves the cluster session header in front of the payload; writing
                    // at claim.offset() would overwrite it and the egress adapter would reject
                    // the message for carrying the wrong schema.
                    val payloadOffset = claim.offset() + AeronCluster.SESSION_HEADER_LENGTH
                    execReportEncoder.wrapAndApplyHeader(claim.buffer(), payloadOffset, headerEncoder)
                        .participantId(participantId)
                        .clOrdId(clOrdId)
                        .exchangeOrderId(exchangeOrderId)
                        .price(price)
                        .lastQty(lastQty)
                        .leavesQty(leavesQty)
                        .securityId(securityId)
                        .rejectReason(SbeRejectReason.get(rejectReason))
                        .execType(execType)
                        .side(SbeSide.get(side))
                    claim.commit()
                }
                return
            }
            if (result == Publication.CLOSED || result == Publication.NOT_CONNECTED ||
                result == Publication.MAX_POSITION_EXCEEDED
            ) {
                undeliverableReports++
                return
            }
            if (++attempts >= backpressureAlertThreshold) {
                backpressureStalls++
                attempts = 0
            }
        }
    }

    // ----------------------------------------------------------- book events

    private fun publishOrderAdded(
        securityId: Int,
        exchangeOrderId: Long,
        price: Long,
        qty: Long,
        side: Byte,
    ) = publishBookEvent(
        MessageHeaderEncoder.ENCODED_LENGTH + OrderAddedEncoder.BLOCK_LENGTH,
    ) { buffer, offset ->
        orderAddedEncoder.wrapAndApplyHeader(buffer, offset, headerEncoder)
            .seqNum(nextBookEventSeqNum++)
            .exchangeOrderId(exchangeOrderId)
            .price(price)
            .qty(qty)
            .securityId(securityId)
            .shardId(shardId)
            .side(SbeSide.get(side))
    }

    private fun publishOrderReduced(
        securityId: Int,
        exchangeOrderId: Long,
        price: Long,
        lastQty: Long,
        leavesQty: Long,
        side: Byte,
    ) = publishBookEvent(
        MessageHeaderEncoder.ENCODED_LENGTH + OrderReducedEncoder.BLOCK_LENGTH,
    ) { buffer, offset ->
        orderReducedEncoder.wrapAndApplyHeader(buffer, offset, headerEncoder)
            .seqNum(nextBookEventSeqNum++)
            .exchangeOrderId(exchangeOrderId)
            .price(price)
            .lastQty(lastQty)
            .leavesQty(leavesQty)
            .securityId(securityId)
            .shardId(shardId)
            .side(SbeSide.get(side))
    }

    private fun publishOrderRemoved(
        securityId: Int,
        exchangeOrderId: Long,
        price: Long,
        leavesQty: Long,
        side: Byte,
        reason: RemoveReason,
    ) = publishBookEvent(
        MessageHeaderEncoder.ENCODED_LENGTH + OrderRemovedEncoder.BLOCK_LENGTH,
    ) { buffer, offset ->
        orderRemovedEncoder.wrapAndApplyHeader(buffer, offset, headerEncoder)
            .seqNum(nextBookEventSeqNum++)
            .exchangeOrderId(exchangeOrderId)
            .price(price)
            .leavesQty(leavesQty)
            .securityId(securityId)
            .shardId(shardId)
            .side(SbeSide.get(side))
            .reason(reason)
    }

    private fun publishTrade(
        securityId: Int,
        price: Long,
        qty: Long,
        takerOrderId: Long,
        makerOrderId: Long,
        aggressorSide: Byte,
    ) = publishBookEvent(
        MessageHeaderEncoder.ENCODED_LENGTH + TradeExecutedEncoder.BLOCK_LENGTH,
    ) { buffer, offset ->
        tradeExecutedEncoder.wrapAndApplyHeader(buffer, offset, headerEncoder)
            .seqNum(nextBookEventSeqNum++)
            .price(price)
            .qty(qty)
            .takerOrderId(takerOrderId)
            .makerOrderId(makerOrderId)
            .securityId(securityId)
            .shardId(shardId)
            .aggressorSide(SbeSide.get(aggressorSide))
    }

    private fun publishAuctionUncrossed(securityId: Int, price: Long, executedQty: Long) =
        publishBookEvent(
            MessageHeaderEncoder.ENCODED_LENGTH + AuctionUncrossedEncoder.BLOCK_LENGTH,
        ) { buffer, offset ->
            auctionUncrossedEncoder.wrapAndApplyHeader(buffer, offset, headerEncoder)
                .seqNum(nextBookEventSeqNum++)
                .uncrossPrice(price)
                .executedQty(executedQty)
                .securityId(securityId)
                .shardId(shardId)
        }

    private fun publishSessionChanged(securityId: Int, phase: Byte) = publishBookEvent(
        MessageHeaderEncoder.ENCODED_LENGTH + SessionChangedEncoder.BLOCK_LENGTH,
    ) { buffer, offset ->
        sessionChangedEncoder.wrapAndApplyHeader(buffer, offset, headerEncoder)
            .seqNum(nextBookEventSeqNum++)
            .securityId(securityId)
            .shardId(shardId)
            .phase(SbePhase.get(phase))
    }

    private fun publishVolatilityHalted(
        securityId: Int,
        collarReference: Long,
        attemptedPrice: Long,
        breachedBound: Long,
        aggressorSide: Byte,
    ) = publishBookEvent(
        MessageHeaderEncoder.ENCODED_LENGTH + VolatilityHaltedEncoder.BLOCK_LENGTH,
    ) { buffer, offset ->
        volatilityHaltedEncoder.wrapAndApplyHeader(buffer, offset, headerEncoder)
            .seqNum(nextBookEventSeqNum++)
            .collarReference(collarReference)
            .attemptedPrice(attemptedPrice)
            .breachedBound(breachedBound)
            .securityId(securityId)
            .shardId(shardId)
            .aggressorSide(SbeSide.get(aggressorSide))
    }

    /**
     * Encodes straight into the log buffer via `tryClaim` — no scratch buffer, no copy.
     *
     * Muted on followers. A closed or unsubscribed stream drops rather than blocks: nothing is
     * listening, so spinning would stall the engine for no one. Genuine backpressure does spin,
     * because dropping would tear the feed for a consumer that is still there, but the stall is
     * counted so it surfaces as an alert instead of a silent freeze (Design.md §7).
     */
    private inline fun publishBookEvent(length: Int, encode: (MutableDirectBuffer, Int) -> Unit) {
        if (!isLeader) return
        val publication = bookEventPub ?: return
        var attempts = 0
        while (true) {
            val result = publication.tryClaim(length, claim)
            if (result > 0) {
                encode(claim.buffer(), claim.offset())
                claim.commit()
                return
            }
            if (result == Publication.CLOSED || result == Publication.NOT_CONNECTED ||
                result == Publication.MAX_POSITION_EXCEEDED
            ) {
                droppedBookEvents++
                return
            }
            if (++attempts >= backpressureAlertThreshold) {
                backpressureStalls++
                attempts = 0
            }
        }
    }

    private companion object {
        const val NULL_SESSION = -1L
        const val SNAPSHOT_POLL_LIMIT = 64
    }
}
