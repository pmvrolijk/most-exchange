package com.engine.tools

import com.engine.reference.OperatorCommands
import com.engine.reference.ParticipantRequests
import com.engine.reference.PriceCodec
import com.engine.reference.RoutedSecurity
import com.engine.sbe.ClientExecutionReportDecoder
import com.engine.sbe.ExecType
import com.engine.sbe.MessageHeaderDecoder
import com.engine.sbe.MessageHeaderEncoder
import com.engine.sbe.NewOrderSingleEncoder
import com.engine.sbe.OrderMassStatusCompleteDecoder
import com.engine.sbe.RejectReason
import com.engine.sbe.ReportResendCompleteDecoder
import com.engine.sbe.ReportResendRequestEncoder
import com.engine.sbe.RequestStatus
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

        val overrides = GatewayOverride.listFrom(args)
        val securities = ArrayList<RoutedSecurity>(symbols.size)
        for (symbol in symbols) {
            val security = directory.routeForSymbol(symbol)
            if (security == null) {
                System.err.println("most: unknown symbol '$symbol' -- try `most securities`")
                return
            }
            securities += overrides.first().applyTo(security)
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

        val gateways = overrides.map { it.applyTo(securities.first()) }
        execute(aeron, spec, securities, gateways)
    }
}

private fun execute(
    aeron: Aeron,
    spec: LoadSpec,
    securities: List<RoutedSecurity>,
    gateways: List<RoutedSecurity>,
) {
    println(
        "load: generating ${spec.count} orders for " +
            "${securities.joinToString(",") { it.symbol }} (seed ${spec.seed})"
    )
    val pool = OrderPool.generate(spec, securities)
    val run = LoadRun(spec)

    // Listen before publishing: an acknowledgement can beat the subscription going live, and a
    // report missed at the start is indistinguishable from one the engine never sent. Every
    // gateway's reports, since with co-located gateways the one answering moves on a failover.
    val reports = gateways.map { aeron.addSubscription(it.executionReportChannel, it.executionReportStreamId) }
    // Exclusive: one thread sends, and an exclusive publication's tryClaim avoids the shared
    // publication's term-position CAS on every message.
    val orders = gateways.map { aeron.addExclusivePublication(it.orderEntryChannel, it.orderEntryStreamId) }

    val idle = SleepingIdleStrategy(Duration.ofMillis(1).toNanos())
    val connectDeadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
    while ((orders.any { !it.isConnected } || reports.any { !it.isConnected }) &&
        System.nanoTime() < connectDeadline
    ) {
        idle.idle(0)
    }
    for ((i, route) in gateways.withIndex()) {
        if (!orders[i].isConnected) {
            System.err.println("most: no gateway listening on ${route.orderEntryChannel}:${route.orderEntryStreamId}")
        }
        if (!reports[i].isConnected) {
            System.err.println(
                "most: warning -- not subscribed to ${route.executionReportChannel}:" +
                    "${route.executionReportStreamId}; orders sent there will look unanswered"
            )
        }
    }
    val first = orders.indexOfFirst { it.isConnected }
    if (first < 0) return
    run.activeGateway = first
    if (gateways.size > 1) {
        println("load: ${gateways.size} gateways; starting on ${gateways[first].orderEntryChannel}")
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

    // The last orders are still in flight; without a drain they all count as unanswered. A final
    // fence settles any that never got an answer: after it completes, an unanswered order was never
    // sequenced (Design.md §5, "Report sequence and resend").
    val drainDeadline = System.nanoTime() + spec.drainMs * 1_000_000L
    while (System.nanoTime() < drainDeadline && run.answered < run.sent) Thread.onSpinWait()
    if (!run.aborted) {
        var fencedOn = run.activeGateway
        sendFence(orders, run, ordersBefore = spec.count)
        while (System.nanoTime() < drainDeadline + spec.drainMs * 1_000_000L &&
            (run.pendingCompletions > 0 || run.statusPending > 0 || run.statusOwed())
        ) {
            // Refused by a standby: the receiver has moved on, so fence again where it went.
            if (run.activeGateway != fencedOn) {
                fencedOn = run.activeGateway
                sendFence(orders, run, ordersBefore = spec.count)
            }
            if (run.statusOwed()) sendStatusRequests(orders, run)
            Thread.onSpinWait()
        }
    }
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
private fun send(gateways: List<Publication>, spec: LoadSpec, pool: OrderPool, run: LoadRun): Long {
    val header = MessageHeaderEncoder()
    val encoder = NewOrderSingleEncoder()
    val claim = BufferClaim()

    val startNs = System.nanoTime()
    run.startNs = startNs
    var nextProgressNs = startNs + spec.intervalMs * 1_000_000L
    var fencedGateway = run.activeGateway

    for (i in 0..<spec.count) {
        // A gateway switch is a failover, or a standby refusing: whatever the old path held is
        // settled by a fence on the new one, before any further order (Design.md §5).
        if (run.activeGateway != fencedGateway && gateways.size > 1) {
            sendFence(gateways, run, ordersBefore = i)
            fencedGateway = run.activeGateway
        } else if (run.fenceWanted && run.pendingCompletions == 0) {
            // A gap in some participant's reportSeq: reports were lost on the way -- a failover the
            // client cannot otherwise see, or a drop under load. Ask, once, from the first missing.
            run.fenceWanted = false
            sendFence(gateways, run, ordersBefore = i)
        }
        // A resend came back TRUNCATED: the reports it could not send are gone, so ask what is open.
        if (run.statusOwed()) sendStatusRequests(gateways, run)
        val dueNs = startNs + i * spec.delayNs
        if (spec.delayNs > 0L) awaitDue(dueNs)

        val slot = pool.slotFor(i)
        run.participantOfOrder[i] = (pool.participantId[slot] - spec.participantBase).toInt()
        val sentNs = System.nanoTime()
        // Written before the claim is committed, so the release store in commit() publishes it.
        // The receiver reads it only after acquiring the report that this send caused, and every
        // Aeron hop in between is a release/acquire pair, so the chain is ordered without a fence.
        run.sendNs[i] = sentNs
        run.dueNs[i] = if (spec.delayNs > 0L) dueNs else sentNs

        if (!offer(gateways, claim, header, encoder, spec, pool, slot, spec.clOrdIdBase + i, run)) {
            // Never sent, so no report is owed: take it out of what the fence has to account for.
            run.sendNs[i] = 0L
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
    gateways: List<Publication>,
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
        // Read on every attempt: the receiver moves it when the gateway in use refuses to forward,
        // and a disconnect below moves it too.
        val result = gateways[run.activeGateway].tryClaim(FRAME_LENGTH, claim)
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

            Publication.NOT_CONNECTED -> {
                // The gateway is gone, not refusing: a co-located gateway dies with its node and
                // sends no GATEWAY_UNAVAILABLE. Aeron says so only once its publication has heard
                // nothing for the connection timeout, and every order sent to it until then is lost
                // without a reply -- the cost of this placement that no reject can cover.
                if (!switchGateway(gateways, run)) {
                    System.err.println("most: no gateway is connected (${describeOfferResult(result)}); stopping")
                    run.aborted = true
                    return false
                }
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

/**
 * One `ReportResendRequest` per participant, from each one's first missing report, on the gateway
 * in use. Sequenced after every order sent before it on this path, so once all of them complete,
 * an order below [ordersBefore] with no report was never sequenced. A request the gateway cannot
 * forward moves to the next gateway, and the fence starts again there.
 */
private fun sendFence(gateways: List<Publication>, run: LoadRun, ordersBefore: Int) {
    val header = MessageHeaderEncoder()
    val encoder = ReportResendRequestEncoder()
    val claim = BufferClaim()
    val length = ParticipantRequests.RESEND_REQUEST_LENGTH
    val generation = run.fenceGeneration + 1
    run.fenceOrders[generation.toInt() and FENCE_SLOTS_MASK] = ordersBefore
    run.pendingCompletions = run.spec.participantCount
    run.fenceGeneration = generation
    run.fences++
    for (p in 0..<run.spec.participantCount) {
        var attempts = 0
        while (true) {
            val result = gateways[run.activeGateway].tryClaim(length, claim)
            if (result > 0L) {
                // A plain publication: encode at claim.offset(), with no cluster session header.
                ParticipantRequests.encodeReportResendRequest(
                    claim.buffer(), claim.offset(), run.ledger.participantId(p), generation,
                    run.ledger.firstMissing(p), encoder, header,
                )
                claim.commit()
                break
            }
            if (result == Publication.NOT_CONNECTED && switchGateway(gateways, run)) {
                return sendFence(gateways, run, ordersBefore)
            }
            if (result != Publication.BACK_PRESSURED && result != Publication.ADMIN_ACTION ||
                ++attempts >= BACKPRESSURE_RETRY_LIMIT
            ) {
                System.err.println("most: could not send a resend request (${describeOfferResult(result)})")
                run.pendingCompletions = 0
                return
            }
            Thread.onSpinWait()
        }
    }
}

/**
 * An `OrderMassStatusRequest` for each participant whose resend came back `TRUNCATED` (Design.md §5,
 * "Order mass status"): every one of its open orders is then stated, and its report sequence resumes
 * from the completion. Sent after the fence on the same path, so the status is at least as new.
 */
private fun sendStatusRequests(gateways: List<Publication>, run: LoadRun) {
    val claim = BufferClaim()
    for (p in 0..<run.spec.participantCount) {
        if (run.statusWanted.getAndSet(p, 0) == 0) continue
        var attempts = 0
        while (true) {
            val result = gateways[run.activeGateway].tryClaim(ParticipantRequests.MASS_STATUS_REQUEST_LENGTH, claim)
            if (result > 0L) {
                ParticipantRequests.encodeOrderMassStatusRequest(
                    claim.buffer(), claim.offset(), run.ledger.participantId(p), run.fenceGeneration,
                    OperatorCommands.ALL_SECURITIES,
                )
                claim.commit()
                run.statusPending++
                run.statusRequests++
                break
            }
            if (result != Publication.BACK_PRESSURED && result != Publication.ADMIN_ACTION ||
                ++attempts >= BACKPRESSURE_RETRY_LIMIT
            ) {
                System.err.println("most: could not send a mass status request (${describeOfferResult(result)})")
                break
            }
            Thread.onSpinWait()
        }
    }
}

/** Moves to the next connected gateway after the one in use; false when there is none. */
private fun switchGateway(gateways: List<Publication>, run: LoadRun): Boolean {
    val from = run.activeGateway
    for (step in 1..<gateways.size) {
        val next = (from + step) % gateways.size
        if (gateways[next].isConnected) {
            run.activeGateway = next
            run.disconnectSwitches++
            return true
        }
    }
    return false
}

private fun describeOfferResult(result: Long): String = when (result) {
    Publication.NOT_CONNECTED -> "the gateway is not connected"
    Publication.CLOSED -> "closed"
    Publication.MAX_POSITION_EXCEEDED -> "max position exceeded; the term buffer wrapped"
    else -> "result $result"
}

// ---------------------------------------------------------------- receiving

private fun receiveReports(subscriptions: List<Subscription>, run: LoadRun) {
    val header = MessageHeaderDecoder()
    val decoder = ClientExecutionReportDecoder()
    val spec = run.spec
    // Which gateway the fragment being handled came from; set before each poll.
    var polling = 0

    val completion = ReportResendCompleteDecoder()
    val statusCompletion = OrderMassStatusCompleteDecoder()
    val assembler = FragmentAssembler { buffer, offset, length, _ ->
        if (length >= MessageHeaderDecoder.ENCODED_LENGTH) {
            header.wrap(buffer, offset)
            if (header.templateId() == ReportResendCompleteDecoder.TEMPLATE_ID) {
                completion.wrap(
                    buffer, offset + MessageHeaderDecoder.ENCODED_LENGTH,
                    header.blockLength(), header.version(),
                )
                run.onCompletion(completion)
                // A standby refusing the fence moves the run on, exactly as it would an order.
                if (completion.status() == RequestStatus.GATEWAY_UNAVAILABLE &&
                    polling == run.activeGateway && subscriptions.size > 1
                ) {
                    run.activeGateway = (polling + 1) % subscriptions.size
                    run.rejectSwitches++
                }
            } else if (header.templateId() == OrderMassStatusCompleteDecoder.TEMPLATE_ID) {
                statusCompletion.wrap(
                    buffer, offset + MessageHeaderDecoder.ENCODED_LENGTH,
                    header.blockLength(), header.version(),
                )
                run.onStatusCompletion(statusCompletion)
            } else if (header.templateId() == ClientExecutionReportDecoder.TEMPLATE_ID) {
                decoder.wrap(
                    buffer, offset + MessageHeaderDecoder.ENCODED_LENGTH,
                    header.blockLength(), header.version(),
                )
                // A replay re-sends reports the run already has; count each report once.
                val gapsBefore = run.ledger.gapsOpened
                if (!run.ledger.accept(decoder.participantId(), decoder.reportSeq())) return@FragmentAssembler
                if (run.ledger.gapsOpened != gapsBefore) run.fenceWanted = true
                if (decoder.execType() == ExecType.ORDER_STATUS) {
                    run.onOrderStatus(decoder, System.nanoTime())
                    return@FragmentAssembler
                }
                run.onReport(decoder, System.nanoTime())
                // A co-located gateway on a node that does not lead refuses every order this way.
                // Move on from it once -- the rejects still in flight from it must not move again.
                if (decoder.execType() == ExecType.REJECTED &&
                    decoder.rejectReason() == RejectReason.GATEWAY_UNAVAILABLE &&
                    polling == run.activeGateway && subscriptions.size > 1
                ) {
                    run.activeGateway = (polling + 1) % subscriptions.size
                    run.rejectSwitches++
                }
            }
        }
    }
    val idle = BusySpinIdleStrategy()
    while (!run.stopped) {
        var work = 0
        for (i in subscriptions.indices) {
            polling = i
            work += subscriptions[i].poll(assembler, FRAGMENT_LIMIT)
        }
        idle.idle(work)
        if (run.progressDue) {
            run.progressDue = false
            printProgress(run)
        }
    }
    // One last sweep: reports can be sitting in the image when the drain deadline expires.
    for (i in subscriptions.indices) {
        polling = i
        subscriptions[i].poll(assembler, FRAGMENT_LIMIT)
    }
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

    /** The gateway orders go to. Written by the receiver on a GATEWAY_UNAVAILABLE, read per send. */
    @Volatile var activeGateway = 0
    /** Which reports arrived, by participant and reportSeq. Receiver thread. */
    val ledger = ReportLedger(spec.participantBase, spec.participantCount)

    // The fence. The sender writes a generation's order count, then the generation; the receiver
    // reads the generation, then its count -- volatile on both ends, so the count is visible.
    val fenceOrders = IntArray(FENCE_SLOTS)
    @Volatile var fenceGeneration = 0L
    @Volatile var pendingCompletions = 0

    /** Set by the receiver on a gap in some participant's reportSeq; the sender then fences. */
    @Volatile var fenceWanted = false
    var fences = 0L

    /** Orders below this index were settled by a completed fence. Receiver thread. */
    @Volatile var settledBefore = 0
    var replayed = 0L
    var truncated = 0L
    var refusedFences = 0L
    var missingAfterComplete = 0L

    /** Which participant sent each order, as an index into the run's participants. Sender thread. */
    val participantOfOrder = IntArray(spec.count)

    // Mass status after a truncated resend (Design.md §5, "Order mass status").
    val statusWanted = java.util.concurrent.atomic.AtomicIntegerArray(spec.participantCount)
    val truncatedParticipant = BooleanArray(spec.participantCount)
    @Volatile var statusPending = 0
    var statusRequests = 0L
    var foundOpenByStatus = 0L
    var lostReports = 0L
    var statusReportsSeen = 0L

    fun statusOwed(): Boolean {
        for (p in 0..<spec.participantCount) if (statusWanted.get(p) != 0) return true
        return false
    }

    fun onStatusCompletion(decoder: OrderMassStatusCompleteDecoder) {
        val p = (decoder.participantId() - spec.participantBase).toInt()
        if (p !in 0..<spec.participantCount) return
        if (decoder.status() == RequestStatus.COMPLETE) ledger.resumeFrom(p, decoder.nextSeq())
        statusPending--
    }

    /**
     * An open order stated by a mass status. If the run never heard of it, this is its answer: it was
     * sequenced and it rests. Not a round trip, so not timed.
     */
    fun onOrderStatus(decoder: ClientExecutionReportDecoder, nowNs: Long) {
        statusReportsSeen++
        val index = decoder.clOrdId() - spec.clOrdIdBase
        if (index < 0L || index >= spec.count) return
        val i = index.toInt()
        if (sendNs[i] == 0L || ackNs[i] != 0L) return
        ackNs[i] = nowNs
        answered++
        foundOpenByStatus++
    }

    fun onCompletion(decoder: ReportResendCompleteDecoder) {
        if (decoder.requestId() != fenceGeneration) return // an older fence, superseded by a switch
        val p = (decoder.participantId() - spec.participantBase).toInt()
        when (decoder.status()) {
            RequestStatus.COMPLETE, RequestStatus.TRUNCATED -> {
                replayed += decoder.replayedCount()
                if (decoder.status() == RequestStatus.TRUNCATED && p in 0..<spec.participantCount) {
                    truncated++
                    // Every sequenced order has at least one report, so no more orders than this
                    // can hide in the window the ring no longer reaches.
                    lostReports += (decoder.oldestRetainedSeq() - decoder.fromSeq()).coerceAtLeast(0L)
                    // Reports are gone that no resend can bring back; ask what is still open.
                    truncatedParticipant[p] = true
                    statusWanted.set(p, 1)
                }
                else if (p in 0..<spec.participantCount) missingAfterComplete += ledger.missingBelow(p, decoder.nextSeq())
                if (--pendingCompletions == 0) {
                    settledBefore = fenceOrders[decoder.requestId().toInt() and FENCE_SLOTS_MASK]
                }
            }
            else -> refusedFences++ // the receiver moves the gateway; the sender fences again
        }
    }

    /** Each written by one thread only: the receiver on a reject, the sender on a disconnect. */
    @Volatile var rejectSwitches = 0L
    @Volatile var disconnectSwitches = 0L
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

    if (run.rejectSwitches + run.disconnectSwitches > 0L) {
        println(
            "  failover       gateway switches: %,d on GATEWAY_UNAVAILABLE, %,d on a disconnect".format(
                Locale.ROOT, run.rejectSwitches, run.disconnectSwitches,
            )
        )
    }

    if (run.fences > 0L) {
        println(
            "  recovery       %,d fences (%,d gaps seen), %,d reports replayed (%,d already received, %,d recovered), %,d truncated%s".format(
                Locale.ROOT, run.fences, run.ledger.gapsOpened, run.replayed, run.ledger.duplicates,
                (run.replayed - run.ledger.duplicates).coerceAtLeast(0), run.truncated,
                if (run.missingAfterComplete > 0L) ", ${run.missingAfterComplete} STILL MISSING after a complete resend" else "",
            )
        )
    }

    if (run.statusRequests > 0L) {
        println(
            "  status         %,d mass status requests after a truncated resend: %,d open orders stated, %,d of them otherwise unanswered".format(
                Locale.ROOT, run.statusRequests, run.statusReportsSeen, run.foundOpenByStatus,
            )
        )
    }

    val unanswered = run.sent - run.answered
    println("  unanswered     %,d orders never saw a report".format(Locale.ROOT, unanswered.coerceAtLeast(0)))
    if (unanswered > 0) {
        // Settled by a fence: sequenced orders have their reports by now, so these never were.
        var neverSequenced = 0
        var unknown = 0
        var finishedOrNeverSent = 0
        for (i in 0..<spec.count) {
            if (run.sendNs[i] == 0L || run.ackNs[i] != 0L) continue
            when {
                i >= run.settledBefore -> unknown++
                // Its participant's resend was TRUNCATED, and the mass status did not find it open:
                // it either never reached the log or finished in the window no resend reaches back to.
                run.truncatedParticipant[run.participantOfOrder[i]] -> finishedOrNeverSent++
                else -> neverSequenced++
            }
        }
        println("  never sent     %,d of them never sequenced, proven by a completed resend fence".format(Locale.ROOT, neverSequenced))
        if (finishedOrNeverSent > 0) {
            println(
                (
                    "  ambiguous      %,d not open, after a TRUNCATED resend: never sequenced, or finished in the " +
                        "lost window -- at most %,d of them, the reports lost"
                    ).format(Locale.ROOT, finishedOrNeverSent, run.lostReports)
            )
        }
        println("  unknown        %,d orders whose fate no fence settled".format(Locale.ROOT, unknown))
    }
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

/** Fence generations in flight at once; a switch supersedes the previous one long before this wraps. */
private const val FENCE_SLOTS = 64
private const val FENCE_SLOTS_MASK = FENCE_SLOTS - 1
