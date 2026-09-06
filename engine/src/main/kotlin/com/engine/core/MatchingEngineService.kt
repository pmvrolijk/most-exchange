package com.engine.core

import com.engine.sbe.AuctionUncrossedEncoder
import com.engine.sbe.BookImageBeginEncoder
import com.engine.sbe.BookImageEndEncoder
import com.engine.sbe.BookImageLevelEncoder
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
import com.engine.sbe.RequestBookImageDecoder
import com.engine.sbe.SecurityDefinitionDecoder
import com.engine.sbe.SessionChangedEncoder
import com.engine.sbe.SessionTransitionDecoder
import com.engine.sbe.SnapshotBookDecoder
import com.engine.sbe.SnapshotBookEncoder
import com.engine.sbe.SnapshotEndDecoder
import com.engine.sbe.SnapshotEndEncoder
import com.engine.sbe.SnapshotEngineStateDecoder
import com.engine.sbe.SnapshotEngineStateEncoder
import com.engine.sbe.SnapshotOrderDecoder
import com.engine.sbe.SnapshotOrderEncoder
import com.engine.sbe.TradeExecutedEncoder
import com.engine.sbe.VolatilityHaltedEncoder
import com.engine.reference.ParticipantRegistry
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
    /**
     * `ShardSpec.fingerprintValue()` for the geometry this node booted with, stamped into the
     * snapshot so a restore can tell at once whether it is being poured into the shape it was
     * taken from. Never recomputed here: the hash has exactly one implementation, in `reference`.
     */
    private val shardFingerprint: Long,
    private val bookEventChannel: String,
    private val bookEventStreamId: Int,
    private val levelCount: Int = 65_536,
    private val auctionMaxPasses: Int = 64,
    private val backpressureAlertThreshold: Int = 1_000_000,
    /**
     * Which gateway speaks for which participant, or null to keep the old behaviour of learning
     * every route from traffic.
     *
     * When present, a session that authenticated as one of these gateways has all of that
     * gateway's participants bound to it the moment the session opens -- so a maker that has said
     * nothing since the gateway last connected is still reachable when its resting order fills.
     * Every node must hold an identical copy; see [ParticipantRegistry].
     */
    private val participantRegistry: ParticipantRegistry? = null,
    /**
     * Hot-path timing, or null to compile it out of the path entirely. Null is the default and the
     * production posture; see [EngineMetrics] for why an engine may read a clock at all.
     */
    private val metrics: EngineMetrics? = null,
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
     * participantId to cluster session id. A session belongs to a gateway, not an end participant,
     * and the maker side of a fill needs a route back (Design.md §1).
     *
     * Filled from two sources, in this order of authority:
     *
     *  - **Declared**, at session open, from the gateway id the consensus module authenticated and
     *    stamped on the session as its encoded principal, resolved through [participantRegistry].
     *    This is what makes a quiet maker reachable: without it a participant that has sent nothing
     *    since its gateway last connected has no route at all, and its fills are counted
     *    undeliverable and dropped.
     *  - **Learned**, from inbound traffic, which still wins for the order it arrived on. That is
     *    not a weaker fallback grudgingly kept: the gateway that forwarded an order is the one
     *    holding its `origQty`, and so the only one that can restore `cumQty` on the way back. A
     *    report must follow the order, not the registry.
     *
     * Deterministic under both: session opens and closes are log events, and every node sees the
     * same messages in the same order.
     */
    private val participantToSession = Long2LongHashMap(NULL_SESSION)

    /**
     * A book image is owed to the book event stream. Set by a snapshot restore and by
     * `RequestBookImage`; cleared once one has been written. Node-local by design, so it is not
     * snapshotted and cannot make one node's state differ from another's.
     */
    private var bookImagePending = false

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

    private val bookImageBeginEncoder = BookImageBeginEncoder()
    private val bookImageLevelEncoder = BookImageLevelEncoder()
    private val bookImageEndEncoder = BookImageEndEncoder()
    private val requestBookImageDecoder = RequestBookImageDecoder()

    private val snapshotStateEncoder = SnapshotEngineStateEncoder()
    private val snapshotBookEncoder = SnapshotBookEncoder()
    private val snapshotOrderEncoder = SnapshotOrderEncoder()
    private val snapshotEndEncoder = SnapshotEndEncoder()

    // Counters. Not state: they never influence a decision, so they cannot diverge nodes.
    var undeliverableReports = 0L
        private set
    var droppedBookEvents = 0L
        private set

    /** Book images written. A market data process that has no book expects this to be non-zero. */
    var bookImagesPublished = 0L
        private set
    var backpressureStalls = 0L
        private set
    var rejectedDefinitions = 0L
        private set
    var auctionPassLimitBreaches = 0L
        private set

    /**
     * Participants bound to a session at session open from an authenticated gateway principal, as
     * opposed to learned from traffic. Zero on a node with no registry, and zero on one whose
     * gateways connect without credentials -- which is the difference between "configured" and
     * "working", and is why it is counted rather than assumed.
     */
    var declaredBindings = 0L
        private set

    /** Sessions that opened carrying a principal this node's registry does not know. */
    var unknownPrincipals = 0L
        private set

    /** Routes currently held, declared and learned together. */
    val participantRoutes: Int get() = participantToSession.size

    // ------------------------------------------------------------ lifecycle

    override fun onStart(cluster: Cluster, snapshotImage: Image?) {
        this.cluster = cluster
        this.isLeader = cluster.role() == Cluster.Role.LEADER
        this.bookEventPub =
            cluster.aeron().addExclusivePublication(bookEventChannel, bookEventStreamId)
        if (snapshotImage != null) loadSnapshot(snapshotImage)
        // Sessions that outlived this service. A restore does *not* replay onSessionOpen for them
        // -- Aeron's ServiceSnapshotLoader adds them straight to the container's session map -- so
        // rebuilding here is what makes a declared binding survive a restart. It also means the
        // binding needs no snapshot state of its own: the principal is already consensus state,
        // and this derives the same map from it on every node.
        rebindDeclaredParticipants()
    }

    /**
     * Rebinds every session that already exists. Called from [onStart], and separately reachable
     * so a test can drive it without a live Aeron: [onStart] also creates the book event
     * publication, which needs a media driver.
     */
    internal fun rebindDeclaredParticipants() {
        for (session in cluster.clientSessions()) bindDeclaredParticipants(session)
    }

    override fun onRoleChange(newRole: Cluster.Role) {
        isLeader = newRole == Cluster.Role.LEADER
    }

    /**
     * Publishes a pending book image once there is somewhere for it to go.
     *
     * Deferred to here rather than done at the point it is asked for, because both things that ask
     * for one happen when a subscriber is least likely to be listening: a snapshot restore runs
     * during `onStart`, before the market data process has necessarily subscribed, and the operator
     * command exists precisely for a market data process that has only just restarted. Publishing
     * into an unconnected publication would count a drop and lose the image silently.
     *
     * [bookImagePending] is node-local and deliberately not snapshotted: it records an intention to
     * write output, not any part of the replicated state. A follower keeps it pending, since its
     * book event publication is muted, and publishes if it is ever made leader.
     */
    override fun doBackgroundWork(nowNs: Long): Int {
        if (!bookImagePending || !isLeader) return 0
        val publication = bookEventPub ?: return 0
        if (!publication.isConnected) return 0
        for (book in books) publishBookImage(book)
        bookImagePending = false
        bookImagesPublished++
        return 1
    }

    override fun onTerminate(cluster: Cluster) {
        bookEventPub?.close()
        bookEventPub = null
    }

    override fun onSessionOpen(session: ClientSession, timestamp: Long) {
        bindDeclaredParticipants(session)
    }

    /**
     * Drops the routes this session declared, and only those it still owns.
     *
     * The ownership test is not defensive tidiness. A participant registered to gateway A that has
     * been sending through gateway B is bound to B by its own traffic, and B is the gateway holding
     * its `origQty`; A going away must not take that route with it.
     *
     * Learned routes are left alone, as they always were -- a stale session id simply fails to
     * resolve, and [Cluster.getClientSession] is the thing that decides.
     */
    override fun onSessionClose(
        session: ClientSession,
        timestamp: Long,
        closeReason: CloseReason,
    ) {
        val participants = declaredParticipantsOf(session) ?: return
        for (participantId in participants) {
            if (participantToSession.get(participantId) == session.id()) {
                participantToSession.remove(participantId)
            }
        }
    }

    /**
     * Resolves a session's authenticated principal to the participants it speaks for.
     *
     * Allocates: decoding the principal makes a String, and this is the one place that does. It is
     * called on session open, on session close and once per surviving session at startup -- never
     * on the message path -- so it costs nothing that `AllocationTest` measures. Keep it that way.
     */
    private fun declaredParticipantsOf(session: ClientSession): LongArray? {
        val registry = participantRegistry ?: return null
        val principal = session.encodedPrincipal()
        if (principal == null || principal.isEmpty()) return null
        return registry.participantsOf(String(principal, Charsets.US_ASCII))
    }

    /**
     * Binds every participant a session speaks for, replacing whatever route each had.
     *
     * A session that opens carrying a principal no registry entry matches is counted rather than
     * refused. Refusing is the consensus module's job and it already did it: a principal only
     * exists because the module verified a secret against this same file. One that resolves here
     * to nothing therefore means the two files disagree, which is a configuration fault to make
     * visible, not an order to reject.
     */
    private fun bindDeclaredParticipants(session: ClientSession) {
        val registry = participantRegistry ?: return
        val principal = session.encodedPrincipal()
        if (principal == null || principal.isEmpty()) return
        val participants = registry.participantsOf(String(principal, Charsets.US_ASCII))
        if (participants == null) {
            unknownPrincipals++
            return
        }
        for (participantId in participants) {
            participantToSession.put(participantId, session.id())
            declaredBindings++
        }
    }

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

        // The clock is read only when metrics are on, and what it produces never re-enters the
        // state machine (see EngineMetrics). Nothing below this line branches on `started`.
        val started = if (metrics != null) metrics.nanoTime() else 0L

        when (headerDecoder.templateId()) {
            NewOrderSingleDecoder.TEMPLATE_ID -> {
                newOrderDecoder.wrap(buffer, body, blockLength, version)
                onNewOrder(session)
                metrics?.newOrder?.record(metrics.nanoTime() - started)
            }

            OrderCancelRequestDecoder.TEMPLATE_ID -> {
                cancelDecoder.wrap(buffer, body, blockLength, version)
                onCancel(session)
                metrics?.cancel?.record(metrics.nanoTime() - started)
            }

            SessionTransitionDecoder.TEMPLATE_ID -> {
                sessionTransitionDecoder.wrap(buffer, body, blockLength, version)
                onSessionTransition()
                metrics?.sessionTransition?.record(metrics.nanoTime() - started)
            }

            PurgeExpiredOrdersDecoder.TEMPLATE_ID -> {
                purgeDecoder.wrap(buffer, body, blockLength, version)
                onPurge()
                metrics?.purge?.record(metrics.nanoTime() - started)
            }

            SecurityDefinitionDecoder.TEMPLATE_ID -> {
                securityDefinitionDecoder.wrap(buffer, body, blockLength, version)
                onSecurityDefinition()
                metrics?.securityDefinition?.record(metrics.nanoTime() - started)
            }

            RequestBookImageDecoder.TEMPLATE_ID -> {
                requestBookImageDecoder.wrap(buffer, body, blockLength, version)
                // Deferred rather than published here: the point of the command is a market data
                // process that has just restarted, and its subscription may not be connected yet.
                // Setting the flag makes both triggers -- this and a snapshot restore -- one path.
                bookImagePending = true
            }

            else -> Unit // Unknown template: ignore rather than throw. See the class KDoc.
        }
    }

    private fun onNewOrder(session: ClientSession) {
        // Stage timing. Every exit below records `admit`, so admit + match + settle account for
        // the whole of this method on every path — a rejected order is admit only, an order booked
        // outside continuous trading is admit + settle. Timestamps, never state (see EngineMetrics).
        val stages = metrics != null && metrics.stages
        val stageStart = if (stages) metrics.nanoTime() else 0L
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
            if (stages) metrics.admit.record(metrics.nanoTime() - stageStart)
            return
        }
        val book = books[bookIndex]

        val reason = validateNewOrder(book, qty, price, expireDate)
        if (reason != RejectReason.NONE) {
            reject(participantId, clOrdId, securityId, side, price, reason)
            if (stages) metrics.admit.record(metrics.nanoTime() - stageStart)
            return
        }

        val smpId = effectiveSmpId(rawSmpId, participantId)
        val orderId = nextExchangeOrderId++

        sendExecutionReport(
            participantId, clOrdId, orderId, securityId, ExecType.NEW, side,
            price = price, lastQty = 0L, leavesQty = qty, rejectReason = RejectReason.NONE,
        )

        val admitted = if (stages) metrics.nanoTime() else 0L
        if (stages) metrics.admit.record(admitted - stageStart)

        if (book.phase != Phase.CONTINUOUS) {
            // Booked in every phase except CLOSED; only matching is gated (Design.md §4.1).
            book.book(orderId, participantId, smpId, clOrdId, price, qty, expireDate, side, smpStrategy)
            publishOrderAdded(securityId, orderId, price, qty, side)
            // No matching happened, so this booking is the whole of the settle stage.
            if (stages) metrics.settle.record(metrics.nanoTime() - admitted)
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

        val matched = if (stages) metrics.nanoTime() else 0L
        if (stages) metrics.match.record(matched - admitted)

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

        if (stages) metrics.settle.record(metrics.nanoTime() - matched)
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
                .shardFingerprint(shardFingerprint)
                .shardId(shardId)
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
                    .priceFloor(book.priceFloor)
                    .tickSize(book.tickSize)
                    .levelCount(book.levelCount)
                    .maxOrders(book.maxOrders)
                    // O(1), and read before this book's orders are walked, which is what lets the
                    // restore decide about a removed security before booking any of them.
                    .restingOrderCount(book.restingOrderCount())
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

    /**
     * Restores from a snapshot image, reconciling the state it holds against the geometry this
     * node booted with.
     *
     * Orders arrive in ladder order, so replaying them through the ordinary [OrderBook.book]
     * reproduces each level's FIFO exactly. What the ordering additionally buys is that a book's
     * header always precedes its orders: every decision about a security can be taken from the
     * header, before a single one of its orders has been booked.
     *
     * The rules, and why each is what it is:
     *
     *  * **The fingerprints agree** -- nothing changed, restore and say nothing.
     *  * **A security is in both, with identical geometry** -- restore.
     *  * **A security is in both, with different geometry** -- fatal. The ladder moved under the
     *    orders; re-laddering them is a decision, not a restore.
     *  * **A security is in the snapshot with resting orders and gone from the shard** -- fatal.
     *    Those orders are live as far as their owners know.
     *  * **A security is in the snapshot with an empty book and gone from the shard** -- dropped,
     *    with a line. This is how a security leaves a shard, and it is deliberately the quiet case.
     *  * **A security is in the shard and not in the snapshot** -- a new empty book, with a line.
     *  * **The orders restored do not match the counts the snapshot claims** -- fatal, because a
     *    reconciliation that cannot count is not a reconciliation.
     *
     * Every fatal is collected rather than thrown at once, so one restart tells an operator
     * everything that is wrong. Once poisoned, the restore keeps decoding and stops applying.
     */
    private fun loadSnapshot(image: Image) {
        val bookDecoder = SnapshotBookDecoder()
        val orderDecoder = SnapshotOrderDecoder()
        val stateDecoder = SnapshotEngineStateDecoder()
        val endDecoder = SnapshotEndDecoder()
        val report = SnapshotRestoreReport(shardId)

        // Null until the state message says otherwise. A version 1 snapshot carries no fingerprint
        // and no geometry, so it can only be reconciled on security ids -- which is weaker, and
        // has to be said out loud rather than silently treated as agreement.
        var geometryKnown = false
        var fingerprintAgrees = false

        val seen = BooleanArray(books.size)
        var currentIndex = NOT_FOUND          // book the following orders belong to, or NOT_FOUND
        var currentSecurityId = 0
        var currentClaimed = SnapshotBookDecoder.restingOrderCountNullValue()
        var currentRestored = 0
        var totalRestored = 0L
        var done = false

        fun closeCurrentBook() {
            if (currentIndex != NOT_FOUND &&
                currentClaimed != SnapshotBookDecoder.restingOrderCountNullValue() &&
                currentClaimed != currentRestored
            ) {
                report.fatal(
                    "security $currentSecurityId: the snapshot claims $currentClaimed resting " +
                        "orders but carries $currentRestored"
                )
            }
            currentIndex = NOT_FOUND
            currentRestored = 0
        }

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

                        val snapshotShard = stateDecoder.shardId()
                        val snapshotFingerprint = stateDecoder.shardFingerprint()
                        geometryKnown =
                            snapshotFingerprint != SnapshotEngineStateDecoder.shardFingerprintNullValue()
                        if (!geometryKnown) {
                            report.note(
                                "the snapshot predates schema version 2 and carries no geometry; " +
                                    "reconciling on security ids alone"
                            )
                        } else if (snapshotShard != shardId) {
                            // Almost always a node pointed at another shard's cluster directory.
                            report.fatal(
                                "the snapshot was taken by shard $snapshotShard, but this node is " +
                                    "shard $shardId"
                            )
                        } else {
                            fingerprintAgrees = snapshotFingerprint == shardFingerprint
                        }
                    }

                    SnapshotBookDecoder.TEMPLATE_ID -> {
                        closeCurrentBook()
                        bookDecoder.wrap(buffer, body, blockLength, version)
                        val securityId = bookDecoder.securityId()
                        val index = indexOfSecurity(securityId)
                        val claimed = bookDecoder.restingOrderCount()
                        val claimedKnown =
                            claimed != SnapshotBookDecoder.restingOrderCountNullValue()

                        currentSecurityId = securityId
                        currentClaimed = claimed
                        currentRestored = 0

                        if (index < 0) {
                            // A security may leave a shard, but only once nothing is resting on it.
                            if (!claimedKnown) {
                                report.fatal(
                                    "security $securityId is no longer on this shard, and the " +
                                        "snapshot is too old to say whether its book was empty"
                                )
                            } else if (claimed > 0) {
                                report.fatal(
                                    "security $securityId is no longer on this shard, but the " +
                                        "snapshot holds $claimed resting orders for it"
                                )
                            } else {
                                report.note("security $securityId left the shard; its book was empty")
                            }
                            currentIndex = NOT_FOUND
                            return@poll
                        }

                        seen[index] = true
                        val book = books[index]
                        if (!fingerprintAgrees && geometryKnown) {
                            val floor = bookDecoder.priceFloor()
                            val tick = bookDecoder.tickSize()
                            val levels = bookDecoder.levelCount()
                            val capacity = bookDecoder.maxOrders()
                            if (floor != book.priceFloor || tick != book.tickSize ||
                                levels != book.levelCount || capacity != book.maxOrders
                            ) {
                                report.fatal(
                                    "security $securityId changed geometry: snapshot " +
                                        "priceFloor=$floor tickSize=$tick levelCount=$levels " +
                                        "maxOrders=$capacity, this node priceFloor=" +
                                        "${book.priceFloor} tickSize=${book.tickSize} " +
                                        "levelCount=${book.levelCount} maxOrders=${book.maxOrders}" +
                                        (if (claimedKnown) " ($claimed resting orders)" else "")
                                )
                            }
                        }
                        if (claimedKnown && claimed > book.maxOrders) {
                            report.fatal(
                                "security $securityId: the snapshot holds $claimed resting orders " +
                                    "but this node's pool is ${book.maxOrders}"
                            )
                        }

                        if (report.hasFatal) {
                            currentIndex = NOT_FOUND
                            return@poll
                        }

                        book.staticReference = bookDecoder.staticReference()
                        book.dynamicReference = bookDecoder.dynamicReference()
                        book.tradingDate = bookDecoder.tradingDate()
                        book.staticCollarBps = bookDecoder.staticCollarBps()
                        book.dynamicCollarBps = bookDecoder.dynamicCollarBps()
                        book.phase = bookDecoder.phase().value()
                        currentIndex = index
                    }

                    SnapshotOrderDecoder.TEMPLATE_ID -> {
                        orderDecoder.wrap(buffer, body, blockLength, version)
                        if (report.hasFatal) return@poll
                        val securityId = orderDecoder.securityId()
                        if (currentIndex == NOT_FOUND || securityId != currentSecurityId) {
                            // Either an order for a book the header said was empty, or an image
                            // whose ordering has been lost. Both mean the counts cannot be trusted.
                            report.fatal(
                                "the snapshot carries an order for security $securityId outside " +
                                    "that security's own section"
                            )
                            return@poll
                        }
                        val book = books[currentIndex]
                        val price = orderDecoder.price()
                        if (!book.isLevelInRange(book.levelOf(price))) {
                            // Never let this reach book(): it indexes the ladder unchecked. And the
                            // throw does not even surface here -- Image.poll catches an exception
                            // from its handler, hands it to the client error handler and advances
                            // the position anyway, so the order is silently dropped and the book
                            // comes back quietly wrong. Checking is the only way to see it; the
                            // count reconciliation below is the backstop if one ever slips past.
                            report.fatal(
                                "security $securityId: a resting order at price $price falls " +
                                    "outside this node's ladder"
                            )
                            return@poll
                        }
                        book.book(
                            exchangeOrderId = orderDecoder.exchangeOrderId(),
                            participantId = orderDecoder.participantId(),
                            smpId = orderDecoder.smpId(),
                            clOrdId = orderDecoder.clOrdId(),
                            price = price,
                            leavesQty = orderDecoder.leavesQty(),
                            expireDate = orderDecoder.expireDate(),
                            side = orderDecoder.side().value(),
                            smpStrategy = orderDecoder.smpStrategy().value(),
                        )
                        currentRestored++
                        totalRestored++
                    }

                    SnapshotEndDecoder.TEMPLATE_ID -> {
                        closeCurrentBook()
                        endDecoder.wrap(buffer, body, blockLength, version)
                        val claimed = endDecoder.restingOrderCount()
                        if (!report.hasFatal && claimed != totalRestored) {
                            report.fatal(
                                "the snapshot claims $claimed resting orders in total but " +
                                    "$totalRestored were restored"
                            )
                        }
                        done = true
                    }

                    else -> {
                        // Previously this was the terminator, which meant an unknown fragment
                        // silently truncated the restore and left a partial book looking whole.
                        report.fatal(
                            "unknown snapshot message ${headerDecoder.templateId()}; " +
                                "the snapshot cannot be restored by this build"
                        )
                        done = true
                    }
                }
            }, SNAPSHOT_POLL_LIMIT)
        }

        if (!done) {
            // The image ran out before SnapshotEnd. Every count check hangs off that message, so a
            // truncated snapshot must not be mistaken for a complete one that happened to be short.
            closeCurrentBook()
            report.fatal("the snapshot ended without a terminator; it is truncated")
        }
        for (i in books.indices) {
            if (!seen[i]) {
                report.note("security ${books[i].securityId} joined the shard; its book starts empty")
            }
        }
        if (report.hasFatal) throw SnapshotRestoreFailed(report.render())
        val notes = report.renderNotes()
        if (notes.isNotEmpty()) println(notes)
        // Market data derives its books purely from the book event stream and a restore publishes
        // no events, so without this it comes back empty and stays empty until the next order on
        // that security. Deferred to doBackgroundWork: nothing is subscribed yet at this point.
        bookImagePending = true

        // Always printed, because the alternative is indistinguishable from a full log replay that
        // happened to rebuild the same books -- and those are very different operational events.
        println(
            "matching-engine: restored $totalRestored resting orders across ${books.size} books " +
                "from a snapshot, nextExchangeOrderId=$nextExchangeOrderId " +
                "nextBookEventSeqNum=$nextBookEventSeqNum"
        )
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
    /**
     * Writes one security's book as a bracketed level image.
     *
     * **Reads `nextBookEventSeqNum` and never advances it.** That is the whole reason this is
     * allowed to happen when a publication happens to connect, or when an operator asks: the
     * sequence is replicated state and is snapshotted, so an image that consumed sequence numbers
     * would make a node that took longer to connect than its peers produce a different snapshot.
     * The number on these messages is the sequence the image is consistent at, exactly as
     * `DepthSnapshotBegin` carries `l2SeqNum`, and a subscriber must not count it as a gap.
     *
     * An empty book still sends a bracketed zero-level cycle: "there is no liquidity" and "I cannot
     * yet know" are different answers, and a subscriber that cannot tell them apart will sit on an
     * empty book waiting for an image that already came.
     */
    private fun publishBookImage(book: OrderBook) {
        val baseline = nextBookEventSeqNum
        val securityId = book.securityId
        val levels = book.occupiedLevelCount()

        publishBookEvent(
            MessageHeaderEncoder.ENCODED_LENGTH + BookImageBeginEncoder.BLOCK_LENGTH,
        ) { buffer, offset ->
            bookImageBeginEncoder.wrapAndApplyHeader(buffer, offset, headerEncoder)
                .seqNum(baseline)
                .securityId(securityId)
                .shardId(shardId)
                .levelCount(levels)
        }

        publishImageSide(book, baseline, Side.BUY)
        publishImageSide(book, baseline, Side.SELL)

        publishBookEvent(
            MessageHeaderEncoder.ENCODED_LENGTH + BookImageEndEncoder.BLOCK_LENGTH,
        ) { buffer, offset ->
            bookImageEndEncoder.wrapAndApplyHeader(buffer, offset, headerEncoder)
                .seqNum(baseline)
                .securityId(securityId)
                .shardId(shardId)
                .levelCount(levels)
        }
    }

    private fun publishImageSide(book: OrderBook, baseline: Long, side: Byte) {
        val securityId = book.securityId
        book.forEachOccupiedLevel(side == Side.BUY) { price, qty, orders ->
            publishBookEvent(
                MessageHeaderEncoder.ENCODED_LENGTH + BookImageLevelEncoder.BLOCK_LENGTH,
            ) { buffer, offset ->
                bookImageLevelEncoder.wrapAndApplyHeader(buffer, offset, headerEncoder)
                    .seqNum(baseline)
                    .price(price)
                    .qty(qty)
                    .securityId(securityId)
                    .shardId(shardId)
                    .orderCount(orders)
                    .side(SbeSide.get(side))
            }
        }
    }

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

        /** What [indexOfSecurity] returns for a security this shard does not host. */
        const val NOT_FOUND = -1
    }
}
