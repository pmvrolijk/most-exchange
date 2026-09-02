package com.engine.tools

import com.engine.reference.DepthFeedAssembler
import com.engine.reference.DepthFeedDecoder
import com.engine.reference.DirectoryClient
import io.aeron.FragmentAssembler
import org.agrona.concurrent.ShutdownSignalBarrier
import org.agrona.concurrent.SleepingIdleStrategy
import java.time.Duration

private val ESC = 27.toChar()

/** Clears the terminal between frames. */
private val CLEAR_SCREEN = "$ESC[H$ESC[2J"

/**
 * Rebuilds books from the depth feed and renders them.
 *
 * The assembly is `reference`'s [DepthFeedAssembler] — the incremental feed spliced onto the
 * periodic snapshot — so this tool recovers exactly as any other consumer does, and shows a book
 * only once it is entitled to. What is left here is the two things the feed does not carry: a
 * symbol, which comes from discovery, and a watch list.
 */
class BookInspector(
    private val symbols: Map<Int, String>,
    private val watch: Set<Int>,
) {
    val assembler = DepthFeedAssembler()
    private val views = LinkedHashMap<Int, BookView>()

    val gapsDetected: Long get() = assembler.gapsDetected
    val messagesMissed: Long get() = assembler.messagesMissed

    var unknownSecurities = 0L
        private set

    /** Registers a security for display. Depth itself goes into the assembler, not through here. */
    fun observe(securityId: Int) {
        if (watch.isNotEmpty() && securityId !in watch) return
        val symbol = symbols[securityId]
        if (symbol == null) {
            // On the feed but absent from the directory: this tool holds a stale universe.
            unknownSecurities++
            return
        }
        views.getOrPut(securityId) { BookView(securityId, symbol) }
    }

    fun render(depth: Int): String = buildString {
        for (securityId in assembler.securities()) observe(securityId)

        if (views.isEmpty()) {
            append("waiting for the depth feed...\n")
        }
        views.values.sortedBy { it.symbol }.forEach { view ->
            append(view.render(assembler.book(view.securityId), assembler.state(view.securityId), depth))
            append('\n')
        }
        // Warnings are printed even with nothing to draw: a tool receiving updates it cannot
        // name must say so rather than sit there claiming to be waiting.
        if (gapsDetected > 0) {
            append("!! $gapsDetected gaps, $messagesMissed messages missed -- ")
            append("books resynchronise from the next snapshot; ")
            append("${assembler.snapshotsApplied} applied, ${assembler.desynchronisations} dropped\n")
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
        println(
            "watching $scope on ${config.l2Channel}:${config.l2StreamId} " +
                "(snapshots ${config.snapshotChannel}:${config.snapshotStreamId})",
        )

        val inspector = BookInspector(symbols, watch)
        val decoder = DepthFeedDecoder(inspector.assembler)

        val handler = FragmentAssembler { buffer, offset, length, _ ->
            decoder.onMessage(buffer, offset, length)
        }

        val l2 = aeron.addSubscription(config.l2Channel, config.l2StreamId)
        val l1 = aeron.addSubscription(config.l1Channel, config.l1StreamId)
        val snapshots = aeron.addSubscription(config.snapshotChannel, config.snapshotStreamId)
        val idle = SleepingIdleStrategy(Duration.ofMillis(1).toNanos())
        val barrier = ShutdownSignalBarrier()

        val worker = Thread({
            var nextRender = System.currentTimeMillis()
            while (!Thread.currentThread().isInterrupted) {
                var work = l2.poll(handler, FRAGMENT_LIMIT)
                work += l1.poll(handler, FRAGMENT_LIMIT)
                work += snapshots.poll(handler, FRAGMENT_LIMIT)
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
