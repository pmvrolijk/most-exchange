package com.engine.tools

import com.engine.reference.PriceCodec
import com.engine.reference.RoutedSecurity
import com.engine.sbe.ClientExecutionReportDecoder
import com.engine.sbe.ExecType
import com.engine.sbe.MessageHeaderDecoder
import com.engine.sbe.MessageHeaderEncoder
import com.engine.sbe.NewOrderSingleEncoder
import com.engine.sbe.RejectReason
import com.engine.sbe.Side
import com.engine.sbe.SmpStrategy
import io.aeron.Aeron
import io.aeron.FragmentAssembler
import io.aeron.Publication
import io.aeron.Subscription
import io.aeron.logbuffer.BufferClaim
import org.HdrHistogram.Histogram
import org.agrona.concurrent.BusySpinIdleStrategy
import org.agrona.concurrent.SleepingIdleStrategy
import java.io.File
import java.io.PrintStream
import java.time.Duration
import java.util.Locale
import java.util.concurrent.locks.LockSupport

/**
 * Load generator and latency harness (`most load`).
 *
 * What it measures is the **whole round trip a client sees**: publication to the gateway, cluster
 * ingress, Raft append and archive write, the engine, cluster egress, the gateway's `cumQty`
 * restoration, and back down the client publication. On a single-node local cluster the archive's
 * disk I/O dominates that, so the number is an end-to-end capacity figure and emphatically not the
 * matching engine's internal budget from Design.md §2. Separating the stages needs timestamps in
 * the gateway and the engine, which this deliberately does not add.
 *
 * The design constraints that matter:
 * - **Nothing allocates once the run starts.** Orders are generated into primitive arrays up front
 *   ([OrderPool]) and encoded straight into the log buffer with `tryClaim`.
 * - **The schedule is absolute**, `start + i * delay`, not a sleep between sends. A relative delay
 *   drifts, and recovering from a stall by sending flat out turns a paced run into a burst.
 * - **Both response time and service time are reported.** Reporting only service time is the
 *   coordinated-omission mistake: when the sender falls behind, an order's real latency includes
 *   the time it spent waiting to be sent, and the two numbers diverge by orders of magnitude at
 *   exactly the rate one is trying to find.
 */
fun runLoad(args: Args) {
    val config = ToolsConfig.from(args)
    val symbols = args.required("symbol").split(",").map { it.trim() }.filter { it.isNotEmpty() }
    require(symbols.isNotEmpty()) { "--symbol needs at least one symbol" }
    // Parsed before connecting, so a bad band is reported as a bad band.
    val spec = LoadSpec.from(args, config.participantId)

    val aeron = connect(config) ?: return
    aeron.use {
        val directory = awaitDirectory(aeron, config, Duration.ofSeconds(args.long("timeout", 15L)))
        if (directory == null) {
            reportNoDirectory(config)
            return
        }

        val securities = ArrayList<RoutedSecurity>(symbols.size)
        for (symbol in symbols) {
            val security = directory.routeForSymbol(symbol)
            if (security == null) {
                System.err.println("most: unknown symbol '$symbol' -- try `most securities`")
                return
            }
            securities += security
        }
        // One publication drives the run, so every symbol has to be reachable through it. Two
        // shards would need two schedules, and interleaving them on one clock is a different
        // measurement from the one this claims to make.
        val shardIds = securities.map { it.shardId }.distinct()
        if (shardIds.size > 1) {
            System.err.println(
                "most: --symbol spans shards $shardIds; load one shard at a time"
            )
            return
        }

        for (warning in bandWarnings(spec, securities)) System.err.println("most: warning -- $warning")

        execute(aeron, spec, securities)
    }
}

private fun execute(aeron: Aeron, spec: LoadSpec, securities: List<RoutedSecurity>) {
    val route = securities.first()
    println(
        "load: generating ${spec.count} orders for " +
            "${securities.joinToString(",") { it.symbol }} (seed ${spec.seed})"
    )
    val pool = OrderPool.generate(spec, securities)
    val run = LoadRun(spec)

    // Listen before publishing: an acknowledgement can beat the subscription going live, and a
    // report missed at the start is indistinguishable from one the engine never sent.
    val reports = aeron.addSubscription(route.executionReportChannel, route.executionReportStreamId)
    // Exclusive: one thread sends, and an exclusive publication's tryClaim avoids the shared
    // publication's term-position CAS on every message.
    val orders = aeron.addExclusivePublication(route.orderEntryChannel, route.orderEntryStreamId)

    val idle = SleepingIdleStrategy(Duration.ofMillis(1).toNanos())
    val connectDeadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
    while ((!orders.isConnected || !reports.isConnected) && System.nanoTime() < connectDeadline) {
        idle.idle(0)
    }
    if (!orders.isConnected) {
        System.err.println(
            "most: no gateway listening on ${route.orderEntryChannel}:${route.orderEntryStreamId}"
        )
        return
    }
    if (!reports.isConnected) {
        System.err.println(
            "most: warning -- not subscribed to ${route.executionReportChannel}:" +
                "${route.executionReportStreamId}; every order will look unanswered"
        )
    }

    val receiver = Thread({ receiveReports(reports, run) }, "load-receiver")
    receiver.start()

    println(
        "load: sending ${spec.count} orders" +
            if (spec.delayNs > 0L) " at %,.0f/s (%d µs apart)".format(Locale.ROOT,
                spec.targetRate, spec.delayNs / 1_000L
            ) else " unpaced"
    )
    val elapsedNs = send(orders, spec, pool, run)

    // The last orders are still in flight; without a drain they all count as unanswered.
    val drainDeadline = System.nanoTime() + spec.drainMs * 1_000_000L
    while (System.nanoTime() < drainDeadline && run.answered < run.sent) Thread.onSpinWait()
    run.stopped = true
    receiver.join(SHUTDOWN_JOIN_MS)

    printSummary(spec, securities, run, elapsedNs)
    spec.histogramFile?.let { writeHistogram(it, run) }
}

// ------------------------------------------------------------------ sending

/**
 * Sends the run and returns the elapsed nanoseconds. Allocation-free: one encoder, one header, one
 * claim, all reused.
 */
private fun send(orders: Publication, spec: LoadSpec, pool: OrderPool, run: LoadRun): Long {
    val header = MessageHeaderEncoder()
    val encoder = NewOrderSingleEncoder()
    val claim = BufferClaim()

    val startNs = System.nanoTime()
    run.startNs = startNs
    var nextProgressNs = startNs + spec.intervalMs * 1_000_000L

    for (i in 0..<spec.count) {
        val dueNs = startNs + i * spec.delayNs
        if (spec.delayNs > 0L) awaitDue(dueNs)

        val slot = pool.slotFor(i)
        val sentNs = System.nanoTime()
        // Written before the claim is committed, so the release store in commit() publishes it.
        // The receiver reads it only after acquiring the report that this send caused, and every
        // Aeron hop in between is a release/acquire pair, so the chain is ordered without a fence.
        run.sendNs[i] = sentNs
        run.dueNs[i] = if (spec.delayNs > 0L) dueNs else sentNs

        if (!offer(orders, claim, header, encoder, spec, pool, slot, spec.clOrdIdBase + i, run)) {
            if (run.aborted) return System.nanoTime() - startNs
            continue
        }

        // Only a paced run has a schedule to be late against. Unpaced, every order is due at the
        // start, so recording it would report the run's own elapsed time as jitter.
        if (i >= spec.warmup && spec.delayNs > 0L) {
            run.lateness.recordValue((sentNs - dueNs).coerceAtLeast(0L))
        }
        run.sent++

        if (spec.intervalMs > 0L && sentNs >= nextProgressNs) {
            run.progressDue = true
            nextProgressNs = sentNs + spec.intervalMs * 1_000_000L
        }
    }
    return System.nanoTime() - startNs
}

/** Spins to the deadline, parking first when there is enough time to make it worthwhile. */
private fun awaitDue(dueNs: Long) {
    var remaining = dueNs - System.nanoTime()
    if (remaining > PARK_THRESHOLD_NS) LockSupport.parkNanos(remaining - PARK_MARGIN_NS)
    while (remaining > 0L) {
        Thread.onSpinWait()
        remaining = dueNs - System.nanoTime()
    }
}

/** Claims, encodes in place and commits. Returns false when the order could not be sent. */
@Suppress("LongParameterList")
private fun offer(
    orders: Publication,
    claim: BufferClaim,
    header: MessageHeaderEncoder,
    encoder: NewOrderSingleEncoder,
    spec: LoadSpec,
    pool: OrderPool,
    slot: Int,
    clOrdId: Long,
    run: LoadRun,
): Boolean {
    var attempts = 0
    while (true) {
        val result = orders.tryClaim(FRAME_LENGTH, claim)
        if (result > 0L) {
            // A plain publication, NOT a cluster ClientSession: encode at claim.offset() with no
            // SESSION_HEADER_LENGTH allowance. That reservation is the engine's egress rule
            // (MatchingEngineService.kt); applying it here would shift every message by 32 bytes.
            encoder.wrapAndApplyHeader(claim.buffer(), claim.offset(), header)
                .participantId(pool.participantId[slot])
                .clOrdId(clOrdId)
                .price(pool.price[slot])
                .qty(pool.qty[slot])
                .smpId(0L)
                .securityId(pool.securityId[slot])
                .expireDate(0)
                .side(Side.get(pool.side[slot]))
                .smpStrategy(SmpStrategy.get(spec.smpStrategy))
            claim.commit()
            return true
        }
        when (result) {
            Publication.BACK_PRESSURED, Publication.ADMIN_ACTION -> {
                run.backpressureRetries++
                if (++attempts >= BACKPRESSURE_RETRY_LIMIT) {
                    run.dropped++
                    return false
                }
                Thread.onSpinWait()
            }

            else -> {
                System.err.println(
                    "most: publication unusable (${describeOfferResult(result)}); stopping"
                )
                run.aborted = true
                return false
            }
        }
    }
}

private fun describeOfferResult(result: Long): String = when (result) {
    Publication.NOT_CONNECTED -> "the gateway is not connected"
    Publication.CLOSED -> "closed"
    Publication.MAX_POSITION_EXCEEDED -> "max position exceeded; the term buffer wrapped"
    else -> "result $result"
}

// ---------------------------------------------------------------- receiving

private fun receiveReports(subscription: Subscription, run: LoadRun) {
    val header = MessageHeaderDecoder()
    val decoder = ClientExecutionReportDecoder()
    val spec = run.spec

    val assembler = FragmentAssembler { buffer, offset, length, _ ->
        if (length >= MessageHeaderDecoder.ENCODED_LENGTH) {
            header.wrap(buffer, offset)
            if (header.templateId() == ClientExecutionReportDecoder.TEMPLATE_ID) {
                decoder.wrap(
                    buffer, offset + MessageHeaderDecoder.ENCODED_LENGTH,
                    header.blockLength(), header.version(),
                )
                run.onReport(decoder, System.nanoTime())
            }
        }
    }

    val idle = BusySpinIdleStrategy()
    while (!run.stopped) {
        idle.idle(subscription.poll(assembler, FRAGMENT_LIMIT))
        if (run.progressDue) {
            run.progressDue = false
            printProgress(run)
        }
    }
    // One last sweep: reports can be sitting in the image when the drain deadline expires.
    subscription.poll(assembler, FRAGMENT_LIMIT)
    if (spec.intervalMs > 0L) printProgress(run)
}

/**
 * Everything the receiver accumulates. Only the receiver thread writes the report-side fields and
 * only the sender thread writes the send-side ones, so nothing here needs a lock.
 */
private class LoadRun(val spec: LoadSpec) {
    val sendNs = LongArray(spec.count)
    val dueNs = LongArray(spec.count)
    val ackNs = LongArray(spec.count)

    @Volatile var sent = 0
    @Volatile var answered = 0
    @Volatile var stopped = false
    @Volatile var progressDue = false
    var aborted = false
    var startNs = 0L

    var backpressureRetries = 0L
    var dropped = 0L
    var reports = 0L
    var foreignReports = 0L
    var tradedQty = 0L
    var takerFills = 0L
    val execTypes = LongArray(EXEC_TYPE_SLOTS)
    val rejects = LongArray(REJECT_REASON_SLOTS)

    val lateness = newHistogram()
    val ackResponse = newHistogram()
    val ackService = newHistogram()
    val takerFill = newHistogram()

    /**
     * The highest order index acknowledged so far. Egress is one ordered stream from one
     * deterministic engine, so every report the *aggressor* generates arrives before any report for
     * a later order. A TRADE while this still points at its own order is therefore an immediate
     * fill; one arriving later is the resting side of somebody else's aggression, whose latency
     * would be the time it sat on the book rather than anything the engine did.
     */
    private var maxAckedIndex = -1

    fun onReport(decoder: ClientExecutionReportDecoder, nowNs: Long) {
        val index = decoder.clOrdId() - spec.clOrdIdBase
        val participant = decoder.participantId()
        if (index < 0L || index >= spec.count ||
            participant < spec.participantBase ||
            participant >= spec.participantBase + spec.participantCount
        ) {
            foreignReports++
            return
        }
        val i = index.toInt()
        if (sendNs[i] == 0L) {
            foreignReports++
            return
        }

        reports++
        val execType = decoder.execType()
        execTypes[execType.value().toInt().coerceIn(0, EXEC_TYPE_SLOTS - 1)]++
        if (execType == ExecType.REJECTED) {
            rejects[decoder.rejectReason().value().coerceIn(0, REJECT_REASON_SLOTS - 1)]++
        }
        if (execType == ExecType.TRADE) {
            tradedQty += decoder.lastQty()
            if (i == maxAckedIndex && i >= spec.warmup) {
                takerFills++
                takerFill.recordValue(nowNs - sendNs[i])
            }
        }

        if (ackNs[i] == 0L) {
            ackNs[i] = nowNs
            maxAckedIndex = i
            answered++
            if (i >= spec.warmup) {
                ackService.recordValue((nowNs - sendNs[i]).coerceAtLeast(1L))
                ackResponse.recordValue((nowNs - dueNs[i]).coerceAtLeast(1L))
            }
        }
    }
}

// ----------------------------------------------------------------- reporting

private fun printProgress(run: LoadRun) {
    val elapsed = (System.nanoTime() - run.startNs) / 1e9
    if (elapsed <= 0.0) return
    val sent = run.sent
    println(
        "  %6.1fs  sent %,d (%,.0f/s)  reports %,d  answered %,d  in-flight %,d  ack p99 %s".format(Locale.ROOT,
            elapsed, sent, sent / elapsed, run.reports, run.answered,
            (sent - run.answered).coerceAtLeast(0), micros(run.ackService.getValueAtPercentile(99.0)),
        )
    )
}

private fun printSummary(
    spec: LoadSpec,
    securities: List<RoutedSecurity>,
    run: LoadRun,
    elapsedNs: Long,
) {
    val seconds = elapsedNs / 1e9
    val target = if (spec.targetRate > 0.0) "%,.0f/s".format(Locale.ROOT, spec.targetRate) else "unpaced"
    val symbols = securities.joinToString(",") { it.symbol }

    println()
    println(
        "load: $symbols  %,d orders in %.2fs -- %,.0f/s achieved (target %s)".format(Locale.ROOT,
            run.sent, seconds, run.sent / seconds, target
        )
    )
    println(
        "  measured path  client -> gateway -> cluster consensus -> engine -> gateway -> client"
    )
    println(
        "  band           ${PriceCodec.format(spec.priceMin)}..${PriceCodec.format(spec.priceMax)}" +
            "  qty ${spec.qtyMin}..${spec.qtyMax}" +
            "  participants ${spec.participantBase}..${spec.participantBase + spec.participantCount - 1}" +
            "  warmup ${spec.warmup}"
    )
    println(
        "  offers         ok=%,d  backpressure-retries=%,d  dropped=%,d".format(Locale.ROOT,
            run.sent, run.backpressureRetries, run.dropped
        )
    )
    println(
        "  reports        %,d   new=%,d trade=%,d canceled=%,d expired=%,d rejected=%,d".format(Locale.ROOT,
            run.reports, run.execTypes[ExecType.NEW.value().toInt()],
            run.execTypes[ExecType.TRADE.value().toInt()],
            run.execTypes[ExecType.CANCELED.value().toInt()],
            run.execTypes[ExecType.EXPIRED.value().toInt()],
            run.execTypes[ExecType.REJECTED.value().toInt()],
        )
    )
    val tradePercent = if (run.sent > 0) 100.0 * run.takerFills / run.sent else 0.0
    println(
        "  fills          %,d qty traded, %,d orders filled on arrival (%.1f%%)".format(Locale.ROOT,
            run.tradedQty, run.takerFills, tradePercent
        )
    )

    val unanswered = run.sent - run.answered
    println("  unanswered     %,d orders never saw a report".format(Locale.ROOT, unanswered.coerceAtLeast(0)))
    if (run.foreignReports > 0L) {
        println("  ignored        %,d reports from outside this run".format(Locale.ROOT, run.foreignReports))
    }

    // A run that mostly rejects is measuring the reject path, so say which reject and how often.
    for (reason in RejectReason.values()) {
        val slot = reason.value()
        if (reason != RejectReason.NONE && slot in 0..<REJECT_REASON_SLOTS && run.rejects[slot] > 0) {
            println("  REJECTED       %,d x ${reason.name}".format(Locale.ROOT, run.rejects[slot]))
        }
    }

    if (spec.delayNs > 0L) {
        println("  pacing         lateness ${percentiles(run.lateness)}")
        println("  ack  response  ${percentiles(run.ackResponse)}")
    }
    println("  ack  service   ${percentiles(run.ackService)}")
    if (run.takerFill.totalCount > 0L) {
        println("  fill service   ${percentiles(run.takerFill)}")
    }
    if (unanswered > 0) {
        println(
            "  note: unanswered orders usually mean the gateway dropped them (its shutdown " +
                "counters say which leg) or --drain-ms was too short"
        )
    }
}

private fun writeHistogram(path: String, run: LoadRun) {
    val file = File(path)
    PrintStream(file.outputStream().buffered()).use { out ->
        out.println("# most load -- ack service time, values in microseconds")
        run.ackService.outputPercentileDistribution(out, NANOS_PER_MICRO)
    }
    println("  histogram      written to ${file.absolutePath}")
}

private fun percentiles(histogram: Histogram): String =
    if (histogram.totalCount == 0L) {
        "no samples"
    } else {
        "n=%,d  p50=%s p90=%s p99=%s p99.9=%s max=%s (µs)".format(Locale.ROOT,
            histogram.totalCount,
            micros(histogram.getValueAtPercentile(50.0)),
            micros(histogram.getValueAtPercentile(90.0)),
            micros(histogram.getValueAtPercentile(99.0)),
            micros(histogram.getValueAtPercentile(99.9)),
            micros(histogram.maxValue),
        )
    }

private fun micros(nanos: Long): String = "%.1f".format(Locale.ROOT, nanos / NANOS_PER_MICRO)

/** Auto-resizing: a stalled cluster can produce a sample past any cap, and throwing there would
 *  lose the whole run rather than the one outlier. */
private fun newHistogram(): Histogram =
    Histogram(1L, MAX_TRACKED_NS, HISTOGRAM_SIGNIFICANT_DIGITS).apply { isAutoResize = true }

private val FRAME_LENGTH =
    MessageHeaderEncoder.ENCODED_LENGTH + NewOrderSingleEncoder.BLOCK_LENGTH

/** Below this a park costs more than it saves, and its wake-up jitter is the run's jitter. */
private const val PARK_THRESHOLD_NS = 100_000L
private const val PARK_MARGIN_NS = 50_000L
private const val BACKPRESSURE_RETRY_LIMIT = 10_000
private const val SHUTDOWN_JOIN_MS = 2_000L
private const val NANOS_PER_MICRO = 1_000.0
private const val MAX_TRACKED_NS = 60_000_000_000L
private const val HISTOGRAM_SIGNIFICANT_DIGITS = 3
private const val EXEC_TYPE_SLOTS = 8
private const val REJECT_REASON_SLOTS = 16
