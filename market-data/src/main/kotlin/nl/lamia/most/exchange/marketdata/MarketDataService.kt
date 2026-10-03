package nl.lamia.most.exchange.marketdata

import nl.lamia.most.exchange.client.FeedSequenceTracker
import nl.lamia.most.exchange.sbe.AuctionUncrossedDecoder
import nl.lamia.most.exchange.sbe.BookImageBeginDecoder
import nl.lamia.most.exchange.sbe.BookImageEndDecoder
import nl.lamia.most.exchange.sbe.BookImageLevelDecoder
import nl.lamia.most.exchange.sbe.MessageHeaderDecoder
import nl.lamia.most.exchange.sbe.OrderAddedDecoder
import nl.lamia.most.exchange.sbe.OrderReducedDecoder
import nl.lamia.most.exchange.sbe.OrderRemovedDecoder
import nl.lamia.most.exchange.sbe.SessionChangedDecoder
import nl.lamia.most.exchange.sbe.Side
import nl.lamia.most.exchange.sbe.TradeExecutedDecoder
import nl.lamia.most.exchange.sbe.VolatilityHaltedDecoder
import org.agrona.DirectBuffer

/**
 * Where the derived feeds go. An interface so the derivation logic is testable without Aeron,
 * and so the transport (multicast, unicast fallback) is chosen at the edge.
 */
interface FeedPublisher {
    /** L3: the engine's book event, forwarded byte for byte. */
    fun publishL3(buffer: DirectBuffer, offset: Int, length: Int)

    fun publishDepth(securityId: Int, side: Byte, price: Long, aggregateQty: Long, orderCount: Int)

    fun publishTopOfBook(
        securityId: Int,
        bidPrice: Long,
        bidQty: Long,
        askPrice: Long,
        askQty: Long,
    )

    fun publishLastTrade(securityId: Int, price: Long, qty: Long, aggressorSide: Byte)

    /**
     * The L2 sequence last published, which is the point in the incremental stream a snapshot
     * image is valid at. Read *before* any level is written and repeated on both ends of the
     * cycle: it is the whole of the splice a late joiner performs.
     */
    val lastDepthSeqNum: Long

    fun publishSnapshotBegin(
        securityId: Int,
        l2SeqNum: Long,
        lastTradePrice: Long,
        lastTradeQty: Long,
        levelCount: Int,
    )

    fun publishSnapshotLevel(
        securityId: Int,
        side: Byte,
        price: Long,
        aggregateQty: Long,
        orderCount: Int,
    )

    fun publishSnapshotEnd(securityId: Int, l2SeqNum: Long, levelCount: Int)
}

data class MarketDataSecurity(
    val securityId: Int,
    val priceFloor: Long,
    val tickSize: Long,
    val levelCount: Int,
)

/**
 * Derives L1, L2 and L3 from the engine's book event stream (Design.md §5).
 *
 * L3 is a verbatim forward: the engine's per-order events *are* market by order, so re-encoding
 * them would only add latency and a chance to disagree with the engine.
 *
 * L2 is maintained incrementally in a [DepthBook] and emitted as a `DepthUpdate` for the one
 * level each event touches. L1 is emitted only when the touch actually moves, which is what keeps
 * a busy book from flooding the top-of-book feed.
 *
 * Gap detection is the subscriber's job (Design.md §5): under `MaxMulticastFlowControl` a slow
 * consumer takes an unrecoverable gap rather than slowing the publisher. This process tracks the
 * engine's own `seqNum` for the same reason, and counts what it misses.
 */
class MarketDataService(
    private val shardId: Int,
    securities: List<MarketDataSecurity>,
    private val publisher: FeedPublisher,
) {
    private val securityIds = IntArray(securities.size) { securities[it].securityId }
    private val books = Array(securities.size) {
        DepthBook(securities[it].levelCount, securities[it].priceFloor, securities[it].tickSize)
    }
    private val lastTradePrice = LongArray(securities.size) { NO_PRICE }
    private val lastTradeQty = LongArray(securities.size)
    private var snapshotCursor = 0
    private val lastBidPrice = LongArray(securities.size)
    private val lastBidQty = LongArray(securities.size)
    private val lastAskPrice = LongArray(securities.size)
    private val lastAskQty = LongArray(securities.size)

    private val header = MessageHeaderDecoder()
    private val orderAdded = OrderAddedDecoder()
    private val orderReduced = OrderReducedDecoder()
    private val orderRemoved = OrderRemovedDecoder()
    private val tradeExecuted = TradeExecutedDecoder()
    private val auctionUncrossed = AuctionUncrossedDecoder()
    private val bookImageBegin = BookImageBeginDecoder()
    private val bookImageLevel = BookImageLevelDecoder()
    private val bookImageEnd = BookImageEndDecoder()
    private val sessionChanged = SessionChangedDecoder()
    private val volatilityHalted = VolatilityHaltedDecoder()

    private val sequences = FeedSequenceTracker()

    val gapsDetected: Long get() = sequences.gapsDetected
    val eventsMissed: Long get() = sequences.messagesMissed

    var unknownSecurityEvents = 0L
        private set
    var outOfRangeEvents = 0L
        private set

    /**
     * Book events stamped with a different shard. This process subscribes to one engine, so a
     * non-zero count means it is pointed at the wrong one — a misconfiguration that would
     * otherwise aggregate another shard's orders into these books silently.
     */
    var foreignShardEvents = 0L
        private set

    /** Book images installed, discarded for a bad level count, and messages outside a bracket. */
    var imagesApplied = 0L
        private set
    var imagesDiscarded = 0L
        private set
    var imageMessagesOutOfBand = 0L
        private set

    /** Which book an image is being read into, or [NOT_IN_PROGRESS]. */
    private var imageInProgress = NOT_IN_PROGRESS
    private var imageLevels = 0

    var snapshotsPublished = 0L
        private set

    init {
        for (i in securities.indices) {
            lastBidPrice[i] = NO_PRICE
            lastAskPrice[i] = NO_PRICE
        }
    }

    /**
     * The derived depth this process is holding for a security, or null if it hosts no such book.
     *
     * Read access to state this process already publishes: the snapshot cycle sends exactly this,
     * so anything examining a book here is looking at what a subscriber is being told, not at a
     * second copy that could disagree with it.
     */
    fun bookOf(securityId: Int): DepthBook? {
        val index = indexOf(securityId)
        return if (index < 0) null else books[index]
    }

    fun onBookEvent(buffer: DirectBuffer, offset: Int, length: Int) {
        if (length < MessageHeaderDecoder.ENCODED_LENGTH) return
        header.wrap(buffer, offset)
        val body = offset + MessageHeaderDecoder.ENCODED_LENGTH
        val blockLength = header.blockLength()
        val version = header.version()

        when (header.templateId()) {
            OrderAddedDecoder.TEMPLATE_ID -> {
                orderAdded.wrap(buffer, body, blockLength, version)
                if (!accept(orderAdded.seqNum(), orderAdded.shardId())) return
                applyAdd(
                    orderAdded.securityId(), orderAdded.side(),
                    orderAdded.price(), orderAdded.qty(),
                )
            }

            OrderReducedDecoder.TEMPLATE_ID -> {
                orderReduced.wrap(buffer, body, blockLength, version)
                if (!accept(orderReduced.seqNum(), orderReduced.shardId())) return
                // A fill reduces quantity; the order itself survives while leavesQty > 0.
                applyReduce(
                    orderReduced.securityId(), orderReduced.side(),
                    orderReduced.price(), orderReduced.lastQty(), removesOrder = false,
                )
            }

            OrderRemovedDecoder.TEMPLATE_ID -> {
                orderRemoved.wrap(buffer, body, blockLength, version)
                if (!accept(orderRemoved.seqNum(), orderRemoved.shardId())) return
                applyReduce(
                    orderRemoved.securityId(), orderRemoved.side(),
                    orderRemoved.price(), orderRemoved.leavesQty(), removesOrder = true,
                )
            }

            TradeExecutedDecoder.TEMPLATE_ID -> {
                tradeExecuted.wrap(buffer, body, blockLength, version)
                if (!accept(tradeExecuted.seqNum(), tradeExecuted.shardId())) return
                rememberTrade(tradeExecuted.securityId(), tradeExecuted.price(), tradeExecuted.qty())
                publisher.publishLastTrade(
                    tradeExecuted.securityId(), tradeExecuted.price(), tradeExecuted.qty(),
                    tradeExecuted.aggressorSide().value(),
                )
            }

            AuctionUncrossedDecoder.TEMPLATE_ID -> {
                auctionUncrossed.wrap(buffer, body, blockLength, version)
                if (!accept(auctionUncrossed.seqNum(), auctionUncrossed.shardId())) return
            }

            SessionChangedDecoder.TEMPLATE_ID -> {
                sessionChanged.wrap(buffer, body, blockLength, version)
                if (!accept(sessionChanged.seqNum(), sessionChanged.shardId())) return
            }

            VolatilityHaltedDecoder.TEMPLATE_ID -> {
                volatilityHalted.wrap(buffer, body, blockLength, version)
                if (!accept(volatilityHalted.seqNum(), volatilityHalted.shardId())) return
            }

            BookImageBeginDecoder.TEMPLATE_ID -> {
                bookImageBegin.wrap(buffer, body, blockLength, version)
                onBookImageBegin(bookImageBegin.securityId(), bookImageBegin.shardId())
                return
            }

            BookImageLevelDecoder.TEMPLATE_ID -> {
                bookImageLevel.wrap(buffer, body, blockLength, version)
                onBookImageLevel(
                    bookImageLevel.securityId(), bookImageLevel.shardId(),
                    bookImageLevel.side(), bookImageLevel.price(),
                    bookImageLevel.qty(), bookImageLevel.orderCount(),
                )
                return
            }

            BookImageEndDecoder.TEMPLATE_ID -> {
                bookImageEnd.wrap(buffer, body, blockLength, version)
                onBookImageEnd(
                    bookImageEnd.securityId(), bookImageEnd.shardId(),
                    bookImageEnd.levelCount(),
                )
                return
            }

            else -> return // Unknown template: forward nothing rather than guess.
        }

        // L3 is the engine's own event, unaltered.
        publisher.publishL3(buffer, offset, length)
    }

    // ------------------------------------------------------------- book image

    /**
     * A book image replaces this security's book wholesale.
     *
     * The engine sends one when it has restored from a snapshot, or when asked, and both mean the
     * same thing: this process has no idea what the book is. Depth here is derived purely from the
     * event stream, and a restored engine publishes no events for the orders it restored — so
     * without an image the book comes back empty and stays empty until the next order arrives on
     * that security.
     *
     * The image's `seqNum` is deliberately **not** passed to [accept]. It is the baseline the image
     * is consistent at rather than a feed sequence: the engine consumes no sequence numbers to send
     * one, so counting them would report a gap on every image.
     */
    private fun onBookImageBegin(securityId: Int, eventShardId: Int) {
        if (eventShardId != shardId) {
            foreignShardEvents++
            return
        }
        val index = indexOf(securityId)
        if (index < 0) {
            unknownSecurityEvents++
            imageInProgress = NOT_IN_PROGRESS
            return
        }
        // Cleared at Begin rather than at End, so a cycle that is cut off leaves an empty book
        // rather than a merged one. An empty book is visibly wrong and recovers on the next image;
        // a book half-replaced by a truncated image looks entirely plausible.
        books[index].rememberOccupancy()
        books[index].clear()
        imageInProgress = index
        imageLevels = 0
    }

    private fun onBookImageLevel(
        securityId: Int,
        eventShardId: Int,
        side: Side,
        price: Long,
        qty: Long,
        orders: Int,
    ) {
        if (eventShardId != shardId) {
            foreignShardEvents++
            return
        }
        val index = indexOf(securityId)
        if (index < 0 || index != imageInProgress) {
            imageMessagesOutOfBand++
            return
        }
        if (books[index].install(price, qty, orders, side == Side.BUY) == NULL_LEVEL) {
            outOfRangeEvents++
            return
        }
        imageLevels++
    }

    private fun onBookImageEnd(securityId: Int, eventShardId: Int, levelCount: Int) {
        if (eventShardId != shardId) {
            foreignShardEvents++
            return
        }
        val index = indexOf(securityId)
        if (index < 0 || index != imageInProgress) {
            imageMessagesOutOfBand++
            imageInProgress = NOT_IN_PROGRESS
            return
        }
        imageInProgress = NOT_IN_PROGRESS
        if (imageLevels != levelCount) {
            // Begin and End repeat the count so a cycle that lost its middle is discarded rather
            // than installed. Leaving the book empty is recoverable; a partial book that looks
            // whole is not.
            books[index].clear()
            imagesDiscarded++
            return
        }
        imagesApplied++
        republish(index, securityId)
    }

    /**
     * Pushes a recovered book downstream, as **increments as well as a snapshot**.
     *
     * The snapshot alone is not enough, and assuming it was is the mistake this exists to correct.
     * A subscriber that is already synchronised **ignores snapshots** — deliberately, because
     * installing an image over a book whose later increments were applied straight through would
     * rewind it. So a console that synchronised to this process's empty book a moment before the
     * image arrived would ignore every snapshot that followed and sit on an empty book for ever,
     * on a market with real depth, with nothing about it looking wrong.
     *
     * An increment carries a level's absolute quantity, so replacing a level is self-correcting. A
     * level the image *removed* is not: it would simply stop being mentioned, and would sit in a
     * consumer's book undisturbed. Hence the vacated levels first, each zeroed explicitly.
     *
     * The snapshot still goes out, for the subscribers that are not yet synchronised and for whom
     * the increments are unusable on their own.
     */
    private fun republish(index: Int, securityId: Int) {
        val book = books[index]
        book.forEachVacated(isBid = true) { price ->
            publisher.publishDepth(securityId, Side.BUY.value(), price, 0L, 0)
        }
        book.forEachVacated(isBid = false) { price ->
            publisher.publishDepth(securityId, Side.SELL.value(), price, 0L, 0)
        }
        book.forEachOccupied(isBid = true) { price, qty, orders ->
            publisher.publishDepth(securityId, Side.BUY.value(), price, qty, orders)
        }
        book.forEachOccupied(isBid = false) { price, qty, orders ->
            publisher.publishDepth(securityId, Side.SELL.value(), price, qty, orders)
        }
        publishSnapshot(index)
        emitTopOfBookIfChanged(index, securityId)
    }

    /**
     * The engine stamps a monotonic sequence on every book event, so a gap here means this
     * process fell behind and its L2 aggregates are now wrong. Counted rather than thrown on;
     * recovery is a resynchronisation concern, not a crash.
     *
     * Returns false for an event belonging to another shard, which must not be aggregated or
     * forwarded — its sequence is a different stream entirely.
     */
    private fun accept(seqNum: Long, eventShardId: Int): Boolean {
        if (eventShardId != shardId) {
            foreignShardEvents++
            return false
        }
        sequences.accept(eventShardId, seqNum)
        return true
    }

    private fun applyAdd(securityId: Int, side: Side, price: Long, qty: Long) {
        val index = indexOf(securityId)
        if (index < 0) {
            unknownSecurityEvents++
            return
        }
        val isBid = side == Side.BUY
        val level = books[index].add(price, qty, isBid)
        if (level == NULL_LEVEL) {
            outOfRangeEvents++
            return
        }
        emitDepth(index, securityId, side, level, isBid)
        emitTopOfBookIfChanged(index, securityId)
    }

    private fun applyReduce(
        securityId: Int,
        side: Side,
        price: Long,
        qty: Long,
        removesOrder: Boolean,
    ) {
        val index = indexOf(securityId)
        if (index < 0) {
            unknownSecurityEvents++
            return
        }
        val isBid = side == Side.BUY
        val level = books[index].reduce(price, qty, isBid, removesOrder)
        if (level == NULL_LEVEL) {
            outOfRangeEvents++
            return
        }
        emitDepth(index, securityId, side, level, isBid)
        emitTopOfBookIfChanged(index, securityId)
    }

    private fun emitDepth(index: Int, securityId: Int, side: Side, level: Int, isBid: Boolean) {
        val book = books[index]
        publisher.publishDepth(
            securityId, side.value(), book.priceOf(level),
            book.qtyAt(level, isBid), book.ordersAt(level, isBid),
        )
    }

    /** Only when the touch actually moves — a busy book must not flood the L1 feed. */
    private fun emitTopOfBookIfChanged(index: Int, securityId: Int) {
        val book = books[index]
        val bidPrice = if (book.bestBidLevel == NULL_LEVEL) NO_PRICE else book.priceOf(book.bestBidLevel)
        val askPrice = if (book.bestAskLevel == NULL_LEVEL) NO_PRICE else book.priceOf(book.bestAskLevel)
        val bidQty = book.bestBidQty()
        val askQty = book.bestAskQty()

        if (bidPrice == lastBidPrice[index] && bidQty == lastBidQty[index] &&
            askPrice == lastAskPrice[index] && askQty == lastAskQty[index]
        ) {
            return
        }
        lastBidPrice[index] = bidPrice
        lastBidQty[index] = bidQty
        lastAskPrice[index] = askPrice
        lastAskQty[index] = askQty
        publisher.publishTopOfBook(securityId, bidPrice, bidQty, askPrice, askQty)
    }

    /**
     * Publishes a complete image of the next security's book, rotating one security per call.
     *
     * **Called from the same thread as `onBookEvent`, and it must stay that way.** The image and
     * the `l2SeqNum` stamped on it are consistent only because no book event can be applied
     * between reading the sequence and walking the ladders. Moving this to a timer thread would
     * produce a torn image that no consumer could detect: every message would be individually
     * valid and the book they assemble would never have existed.
     *
     * One security per call, rather than the whole shard, so the burst on the poll loop is bounded
     * by one book rather than by ten.
     *
     * Returns the securityId snapshotted, or -1 when this shard hosts nothing.
     */
    fun publishNextSnapshot(): Int {
        if (securityIds.isEmpty()) return -1
        val index = snapshotCursor
        snapshotCursor = (snapshotCursor + 1) % securityIds.size
        publishSnapshot(index)
        return securityIds[index]
    }

    private fun publishSnapshot(index: Int) {
        val securityId = securityIds[index]
        val book = books[index]
        // Read before the first level is written: every DepthUpdate published after this point is
        // one the consumer must replay on top of the image, and it decides that by comparing
        // sequences. A value read afterwards would silently swallow the updates in between.
        val seqNum = publisher.lastDepthSeqNum
        val levels = book.occupiedLevels(isBid = true) + book.occupiedLevels(isBid = false)

        publisher.publishSnapshotBegin(
            securityId = securityId,
            l2SeqNum = seqNum,
            lastTradePrice = lastTradePrice[index],
            lastTradeQty = lastTradeQty[index],
            levelCount = levels,
        )
        book.forEachOccupied(isBid = true) { price, qty, orders ->
            publisher.publishSnapshotLevel(securityId, Side.BUY.value(), price, qty, orders)
        }
        book.forEachOccupied(isBid = false) { price, qty, orders ->
            publisher.publishSnapshotLevel(securityId, Side.SELL.value(), price, qty, orders)
        }
        publisher.publishSnapshotEnd(securityId, seqNum, levels)
        snapshotsPublished++
    }

    private fun rememberTrade(securityId: Int, price: Long, qty: Long) {
        val index = indexOf(securityId)
        if (index < 0) return
        lastTradePrice[index] = price
        lastTradeQty[index] = qty
    }

    private fun indexOf(securityId: Int): Int {
        for (i in securityIds.indices) if (securityIds[i] == securityId) return i
        return -1
    }

    companion object {
        /** No bid or no offer. Distinct from a real price so consumers can tell them apart. */
        const val NO_PRICE = Long.MIN_VALUE

        /** No book image is being read. */
        const val NOT_IN_PROGRESS = -1
    }
}
