package com.engine.tools

import com.engine.reference.DirectoryClient
import com.engine.reference.FeedSequenceTracker
import com.engine.sbe.DepthUpdateDecoder
import com.engine.sbe.LastTradeDecoder
import com.engine.sbe.MessageHeaderDecoder
import com.engine.sbe.Side
import io.aeron.FragmentAssembler
import org.agrona.DirectBuffer
import org.agrona.concurrent.ShutdownSignalBarrier
import org.agrona.concurrent.SleepingIdleStrategy
import java.time.Duration

private val ESC = 27.toChar()

/** Clears the terminal between frames. */
private val CLEAR_SCREEN = "$ESC[H$ESC[2J"

/**
 * Rebuilds books from the L2 depth feed and renders them.
 *
 * Kept free of Aeron so the aggregation and the display can be tested directly. The tool needs
 * discovery for one thing the feed does not carry: a security's symbol, so the operator reads
 * `AAPL` rather than `securityId=1`.
 */
class BookInspector(
    private val symbols: Map<Int, String>,
    private val watch: Set<Int>,
) {
    private val books = LinkedHashMap<Int, BookView>()
    private val sequences = FeedSequenceTracker()

    val gapsDetected: Long get() = sequences.gapsDetected
    val messagesMissed: Long get() = sequences.messagesMissed

    var unknownSecurities = 0L
        private set

    fun onDepth(
        securityId: Int,
        shardId: Int,
        seqNum: Long,
        side: Byte,
        price: Long,
        qty: Long,
        orders: Int,
    ) {
        sequences.accept(shardId, seqNum)
        bookFor(securityId)?.applyDepth(side == Side.BUY.value(), price, qty, orders)
    }

    fun onTrade(securityId: Int, shardId: Int, seqNum: Long, price: Long, qty: Long) {
        sequences.accept(shardId, seqNum)
        bookFor(securityId)?.applyTrade(price, qty)
    }

    fun bookOf(securityId: Int): BookView? = books[securityId]

    private fun bookFor(securityId: Int): BookView? {
        if (watch.isNotEmpty() && securityId !in watch) return null
        val symbol = symbols[securityId]
        if (symbol == null) {
            // On the feed but absent from the directory: this tool holds a stale universe.
            unknownSecurities++
            return null
        }
        return books.getOrPut(securityId) { BookView(securityId, symbol) }
    }

    fun render(depth: Int): String = buildString {
        val active = books.values.filter { !it.isEmpty() || it.updates > 0 }
        if (active.isEmpty()) {
            append("waiting for depth updates...\n")
        }
        active.sortedBy { it.symbol }.forEach { append(it.render(depth)).append('\n') }
        // Warnings are printed even with nothing to draw: a tool receiving updates it cannot
        // name must say so rather than sit there claiming to be waiting.
        if (gapsDetected > 0) {
            append("!! $gapsDetected gaps, $messagesMissed messages missed -- ")
            append("depth shown may be stale; resubscribe to resynchronise\n")
        }
        if (unknownSecurities > 0) {
            append("!! $unknownSecurities updates for securities absent from the directory\n")
        }
    }
}

fun runBook(args: Args) {
    val config = ToolsConfig.from(args)
    val depth = args.int("depth", 5)
    val refreshMs = args.long("refresh", 1000L)

    val aeron = connect(config) ?: return
    aeron.use {
        val directory: DirectoryClient? =
            awaitDirectory(aeron, config, Duration.ofSeconds(args.long("timeout", 15L)))
        if (directory == null) {
            reportNoDirectory(config)
            return
        }

        val symbols = directory.securities.associate { it.securityId to it.symbol }
        val requested = args.optional("symbol")
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        val watch = requested.orEmpty().mapNotNull { symbol ->
            val found = directory.routeForSymbol(symbol)
            if (found == null) System.err.println("most: unknown symbol '$symbol', ignoring")
            found?.securityId
        }.toSet()

        val scope = if (watch.isEmpty()) "all ${symbols.size} securities"
        else requested!!.joinToString(",")
        println("watching $scope on ${config.l2Channel}:${config.l2StreamId}")

        val inspector = BookInspector(symbols, watch)
        val header = MessageHeaderDecoder()
        val depthDecoder = DepthUpdateDecoder()
        val tradeDecoder = LastTradeDecoder()

        val handler = FragmentAssembler { buffer: DirectBuffer, offset: Int, length: Int, _ ->
            if (length >= MessageHeaderDecoder.ENCODED_LENGTH) {
                header.wrap(buffer, offset)
                val body = offset + MessageHeaderDecoder.ENCODED_LENGTH
                when (header.templateId()) {
                    DepthUpdateDecoder.TEMPLATE_ID -> {
                        depthDecoder.wrap(buffer, body, header.blockLength(), header.version())
                        inspector.onDepth(
                            depthDecoder.securityId(), depthDecoder.shardId(),
                            depthDecoder.seqNum(), depthDecoder.side().value(),
                            depthDecoder.price(), depthDecoder.aggregateQty(),
                            depthDecoder.orderCount(),
                        )
                    }

                    LastTradeDecoder.TEMPLATE_ID -> {
                        tradeDecoder.wrap(buffer, body, header.blockLength(), header.version())
                        inspector.onTrade(
                            tradeDecoder.securityId(), tradeDecoder.shardId(),
                            tradeDecoder.seqNum(), tradeDecoder.price(), tradeDecoder.qty(),
                        )
                    }

                    else -> Unit
                }
            }
        }

        val l2 = aeron.addSubscription(config.l2Channel, config.l2StreamId)
        val l1 = aeron.addSubscription(config.l1Channel, config.l1StreamId)
        val idle = SleepingIdleStrategy(Duration.ofMillis(1).toNanos())
        val barrier = ShutdownSignalBarrier()

        val worker = Thread({
            var nextRender = System.currentTimeMillis()
            while (!Thread.currentThread().isInterrupted) {
                var work = l2.poll(handler, FRAGMENT_LIMIT)
                work += l1.poll(handler, FRAGMENT_LIMIT)
                idle.idle(work)
                val now = System.currentTimeMillis()
                if (now >= nextRender) {
                    nextRender = now + refreshMs
                    print(CLEAR_SCREEN)
                    print(inspector.render(depth))
                    System.out.flush()
                }
            }
        }, "book-inspector")
        worker.isDaemon = true
        worker.start()

        barrier.use { it.await() }
        worker.interrupt()
    }
}
