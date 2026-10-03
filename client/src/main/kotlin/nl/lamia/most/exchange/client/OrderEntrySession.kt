package nl.lamia.most.exchange.client

import io.aeron.FragmentAssembler
import io.aeron.Publication
import nl.lamia.most.exchange.sbe.ClientExecutionReportDecoder
import nl.lamia.most.exchange.sbe.ExecType
import nl.lamia.most.exchange.sbe.MessageHeaderDecoder
import nl.lamia.most.exchange.sbe.MessageHeaderEncoder
import nl.lamia.most.exchange.sbe.NewOrderSingleEncoder
import nl.lamia.most.exchange.sbe.OrderCancelRequestEncoder
import nl.lamia.most.exchange.sbe.OrderMassStatusCompleteDecoder
import nl.lamia.most.exchange.sbe.OrderMassStatusRequestEncoder
import nl.lamia.most.exchange.sbe.RejectReason
import nl.lamia.most.exchange.sbe.ReportResendCompleteDecoder
import nl.lamia.most.exchange.sbe.ReportResendRequestEncoder
import nl.lamia.most.exchange.sbe.RequestStatus
import nl.lamia.most.exchange.sbe.Side
import nl.lamia.most.exchange.sbe.SmpStrategy
import org.agrona.DirectBuffer
import org.agrona.collections.Long2ObjectHashMap
import org.agrona.collections.LongArrayList
import org.agrona.concurrent.Agent
import org.agrona.concurrent.NanoClock
import org.agrona.concurrent.SystemNanoClock
import org.agrona.concurrent.UnsafeBuffer
import java.util.concurrent.TimeUnit

/** What became of a request [OrderEntrySession.newOrder] or [OrderEntrySession.cancel] was asked to send. */
enum class SendResult {
    /** On its way. Its outcome arrives as a report, or as [OrderEntryListener.onSettled]. */
    SENT,

    /** Not sent, and not lost: try again (docs/Adapters.md §2). A fence owed first counts as this. */
    BACK_PRESSURED,

    /** The session has not finished its opening mass status (docs/Adapters.md §1). */
    NOT_READY,

    /** No gateway of the shard is connected. */
    NO_GATEWAY,

    /** The participant is not one this session was created for. */
    UNKNOWN_PARTICIPANT,
}

/** How a request with no report was settled (docs/Adapters.md §4). */
enum class OrderFate {
    /**
     * Proven never to have reached the log: a completed resend fence was sequenced after it and
     * replayed nothing for it, or the gateway refused it `GATEWAY_UNAVAILABLE`. Rejecting it to the
     * client and sending it again are both safe; it executes at most once.
     */
    NEVER_SEQUENCED,

    /**
     * Not open, after a resend that came back `TRUNCATED`: either never sequenced, or finished in the
     * window the ring no longer reached. **Never present this as a reject.** Reconcile fills from
     * trade records.
     */
    AMBIGUOUS,
}

/**
 * What an [OrderEntrySession] tells its adapter. Called on the session's thread, from
 * [OrderEntrySession.doWork], [OrderEntrySession.newOrder] or [OrderEntrySession.cancel].
 */
interface OrderEntryListener {
    /**
     * Every execution report for one of the session's participants, **once**: a replayed report
     * already received is not delivered again. `ORDER_STATUS` reports from a mass status are
     * included. A gateway's `GATEWAY_UNAVAILABLE` reject is not — it is not the market's answer, and
     * arrives as [onSettled] instead.
     *
     * The decoder is valid only during the call.
     */
    fun onReport(report: ClientExecutionReportDecoder)

    /** A request that had no report was settled without one. */
    fun onSettled(participantId: Long, clOrdId: Long, fate: OrderFate)

    /** A mass status finished: every open order was stated by an `ORDER_STATUS` report before this. */
    fun onMassStatusComplete(participantId: Long, status: RequestStatus, orderCount: Int, nextSeq: Long) {}

    /** A resend or status request the gateway refused for a reason switching cannot fix. */
    fun onRequestRefused(participantId: Long, status: RequestStatus) {}

    fun onGatewaySwitch(from: GatewayEndpoint, to: GatewayEndpoint) {}

    /** The opening mass status has completed for every participant, and orders may be sent. */
    fun onReady() {}
}

/**
 * Where each participant's report sequence resumes after the adapter restarts (docs/Adapters.md §5).
 *
 * With nothing stored, a restart begins with a mass status: open orders are recovered exactly, and
 * anything that finished while the adapter was down is learned only from trade records. With the
 * first missing `reportSeq` stored, the restart also resends from there. Reports above that point
 * that had already been delivered before the restart are delivered again, so a listener that
 * persists reports should be idempotent on (`participantId`, `reportSeq`).
 */
interface SessionStore {
    /** The first `reportSeq` not yet received for [participantId]; 1 when nothing is stored. */
    fun firstMissing(participantId: Long): Long

    /** Called from the session's thread whenever [firstMissing] has moved. */
    fun save(participantId: Long, firstMissing: Long)

    /** Nothing survives the process: every restart begins from a mass status alone. */
    class InMemory : SessionStore {
        private val stored = HashMap<Long, Long>()
        override fun firstMissing(participantId: Long): Long = stored[participantId] ?: 1L
        override fun save(participantId: Long, firstMissing: Long) {
            stored[participantId] = firstMissing
        }
    }
}

/**
 * Order entry to one shard, for the participants an adapter acts for: the whole of
 * docs/Adapters.md §1–§4 in one single-threaded [Agent].
 *
 * - **Connecting.** Waits for a gateway with both legs connected, then runs a mass status for every
 *   participant before it is [isReady] ([Config.massStatusOnStart]).
 * - **Back-pressure** is returned, never dropped: [SendResult.BACK_PRESSURED].
 * - **Switching.** On `NOT_CONNECTED`, and on a `GATEWAY_UNAVAILABLE` from the gateway in use, moves
 *   to the next connected gateway of [links], and fences before the next order.
 * - **The fence.** After a switch, on a `reportSeq` gap, and when a request has gone unanswered for
 *   [Config.unansweredFenceNs], one `ReportResendRequest` per participant from its first missing
 *   report. When it completes, every earlier request still without a report is
 *   [OrderFate.NEVER_SEQUENCED]. When it is `TRUNCATED`, a mass status follows: open orders are
 *   stated, the sequence resumes from its `nextSeq`, and the rest are [OrderFate.AMBIGUOUS].
 *
 * The session tracks only requests still waiting for their first report, keyed by `clOrdId`, which
 * must be unique per participant for the trading day (docs/Adapters.md §2). Mapping the client's own
 * identifiers, and keeping `exchangeOrderId` for cancels, are the adapter's.
 *
 * Not thread-safe: [doWork], [newOrder], [cancel] and [requestMassStatus] belong to one thread, as
 * an Artio library's poll loop does.
 */
class OrderEntrySession(
    private val links: List<GatewayLink>,
    participantIds: LongArray,
    private val listener: OrderEntryListener,
    private val config: Config = Config(),
    private val store: SessionStore = SessionStore.InMemory(),
    private val clock: NanoClock = SystemNanoClock.INSTANCE,
) : Agent {

    data class Config(
        /**
         * Reconcile at connection, as docs/Adapters.md §1 requires before trading. Its `nextSeq` is
         * also where the report sequence starts. Without it (and with nothing in the [SessionStore])
         * the sequence starts at 1, so the participant's first live report opens a gap and the
         * fence replays everything the ring still holds for it. That suits a one-shot request such
         * as `most status`, which asks for its own status and receives no live reports.
         */
        val massStatusOnStart: Boolean = true,
        /** A request unanswered this long is fenced. Longer than a failover takes (Adapters.md §6). */
        val unansweredFenceNs: Long = TimeUnit.SECONDS.toNanos(10),
        /** How long a refused request waits before it is sent again, so a standby is not flooded. */
        val retryIntervalNs: Long = TimeUnit.MILLISECONDS.toNanos(100),
        /**
         * The first `requestId` this session uses. Distinct from any earlier run's, so a completion
         * still in flight from a previous process is never taken for one of this one's.
         */
        val requestIdBase: Long = System.currentTimeMillis() * 1_000L,
        val fragmentLimit: Int = 32,
    )

    init {
        require(links.isNotEmpty()) { "a session needs at least one gateway" }
        require(participantIds.isNotEmpty()) { "a session needs at least one participant" }
    }

    private val ledger = ReportLedger(participantIds)
    private val participants = Array(participantIds.size) { Participant(participantIds[it]) }

    private var active = -1
    private var activeDown = false
    private var startupPending = 0
    private var nextRequestId = config.requestIdBase
    /** Requests this session has sent, in order: the position a fence settles against. */
    private var sendPosition = 0L
    private var nextUnansweredCheckNs = 0L

    var isReady = false
        private set

    val activeGateway: GatewayEndpoint? get() = if (active < 0) null else links[active].endpoint

    var gatewaySwitches = 0L
        private set
    var fencesSent = 0L
        private set
    val duplicateReports: Long get() = ledger.duplicates

    private val buffer = UnsafeBuffer(ByteArray(BUFFER_LENGTH))
    private val headerEncoder = MessageHeaderEncoder()
    private val orderEncoder = NewOrderSingleEncoder()
    private val cancelEncoder = OrderCancelRequestEncoder()
    private val resendEncoder = ReportResendRequestEncoder()
    private val statusEncoder = OrderMassStatusRequestEncoder()

    private val header = MessageHeaderDecoder()
    private val report = ClientExecutionReportDecoder()
    private val resendComplete = ReportResendCompleteDecoder()
    private val statusComplete = OrderMassStatusCompleteDecoder()
    private val handlers = Array(links.size) { link ->
        FragmentAssembler { buffer, offset, length, _ -> onFragment(link, buffer, offset, length) }
    }
    private val settling = LongArrayList()

    override fun roleName(): String = "order-entry-session"

    override fun doWork(): Int {
        var work = 0
        if (active < 0) {
            if (!connect()) return 0
            work++
        }
        for (i in links.indices) work += links[i].poll(handlers[i], config.fragmentLimit)
        work += watchActiveGateway()
        work += flushRequests()
        checkUnanswered()
        persist()
        return work
    }

    /** Sends a `NewOrderSingle`. Prices and quantities are fixed-point, 8 implied decimals ([PriceCodec]). */
    @Suppress("LongParameterList")
    fun newOrder(
        participantId: Long,
        clOrdId: Long,
        securityId: Int,
        side: Side,
        price: Long,
        qty: Long,
        expireDate: Int = 0,
        smpId: Long = 0L,
        smpStrategy: SmpStrategy = SmpStrategy.CANCEL_AGGRESSOR,
    ): SendResult {
        val p = participantOf(participantId) ?: return SendResult.UNKNOWN_PARTICIPANT
        readyToSend()?.let { return it }
        val length = OrderRequests.encodeNewOrder(
            buffer, 0, participantId, clOrdId, securityId, side, price, qty, expireDate, smpId, smpStrategy,
            orderEncoder, headerEncoder,
        )
        return send(p, clOrdId, length)
    }

    /** Sends an `OrderCancelRequest`. Its report carries [clOrdId], this request's own id. */
    @Suppress("LongParameterList")
    fun cancel(
        participantId: Long,
        clOrdId: Long,
        origClOrdId: Long,
        exchangeOrderId: Long,
        securityId: Int,
        side: Side,
    ): SendResult {
        val p = participantOf(participantId) ?: return SendResult.UNKNOWN_PARTICIPANT
        readyToSend()?.let { return it }
        val length = OrderRequests.encodeCancel(
            buffer, 0, participantId, clOrdId, origClOrdId, exchangeOrderId, securityId, side,
            cancelEncoder, headerEncoder,
        )
        return send(p, clOrdId, length)
    }

    /**
     * Asks for every open order of [participantId], on [securityId] or on every book of the shard.
     * The answer arrives as `ORDER_STATUS` reports and [OrderEntryListener.onMassStatusComplete]. A
     * status already owed or in flight for the participant is replaced.
     */
    fun requestMassStatus(participantId: Long, securityId: Int = ParticipantRequests.ALL_SECURITIES): Boolean {
        val p = participantOf(participantId) ?: return false
        p.statusOwed = true
        p.statusRequestId = 0L
        p.statusSecurityId = securityId
        return true
    }

    // ------------------------------------------------------------------ connecting and switching

    private fun connect(): Boolean {
        val first = links.indexOfFirst { it.isConnected }
        if (first < 0) return false
        active = first
        for ((i, p) in participants.withIndex()) {
            val stored = store.firstMissing(p.id)
            if (stored > 1L) {
                ledger.resumeFrom(i, stored)
                p.savedFirstMissing = stored
                p.fenceOwed = true
            }
            if (config.massStatusOnStart) {
                p.statusOwed = true
                p.opening = true
                startupPending++
            }
        }
        if (startupPending == 0) becomeReady()
        return true
    }

    private fun watchActiveGateway(): Int {
        if (links[active].isConnected) {
            if (activeDown) {
                // Back after an outage with nowhere else to go: what it held is settled by a fence.
                activeDown = false
                oweFences()
                return 1
            }
            return 0
        }
        if (switchGateway()) return 1
        activeDown = true
        return 0
    }

    /** Moves to the next connected gateway after the one in use; false when there is none. */
    private fun switchGateway(): Boolean {
        val from = active
        for (step in 1..<links.size) {
            val next = (from + step) % links.size
            if (links[next].isConnected) {
                active = next
                activeDown = false
                gatewaySwitches++
                oweFences()
                listener.onGatewaySwitch(links[from].endpoint, links[next].endpoint)
                return true
            }
        }
        return false
    }

    /**
     * After any switch, every participant fences before the next order (docs/Adapters.md §3). A
     * fence or status in flight on the old path may never be answered, so both are owed again.
     */
    private fun oweFences() {
        for (p in participants) {
            p.fenceOwed = true
            p.fenceRequestId = 0L
            if (p.statusRequestId != 0L) {
                p.statusRequestId = 0L
                p.statusOwed = true
            }
        }
    }

    // ------------------------------------------------------------------ sending

    private fun readyToSend(): SendResult? {
        if (!isReady) return SendResult.NOT_READY
        flushRequests()
        if (!links[active].isConnected && !switchGateway()) return SendResult.NO_GATEWAY
        for (p in participants) if (p.fenceOwed) return SendResult.BACK_PRESSURED
        return null
    }

    private fun send(p: Participant, clOrdId: Long, length: Int): SendResult {
        val result = links[active].offer(buffer, 0, length)
        if (result > 0L) {
            p.inFlight.put(clOrdId, InFlight(sendPosition++, clock.nanoTime()))
            return SendResult.SENT
        }
        return when (result) {
            Publication.BACK_PRESSURED, Publication.ADMIN_ACTION -> SendResult.BACK_PRESSURED
            // Gone, not refusing: a co-located gateway dies with its node and sends no reject.
            else -> if (switchGateway()) SendResult.BACK_PRESSURED else SendResult.NO_GATEWAY
        }
    }

    /** Sends every owed fence, then every owed status, in that order, on the gateway in use. */
    private fun flushRequests(): Int {
        if (active < 0) return 0
        val now = clock.nanoTime()
        var sent = 0
        for ((i, p) in participants.withIndex()) {
            if (p.fenceOwed && now >= p.retryAtNs) {
                val requestId = nextRequestId++
                val length = ParticipantRequests.encodeReportResendRequest(
                    buffer, 0, p.id, requestId, ledger.firstMissing(i), resendEncoder, headerEncoder,
                )
                if (!offerRequest(length)) return sent
                p.fenceOwed = false
                p.fenceRequestId = requestId
                p.fenceAt = sendPosition
                fencesSent++
                sent++
            }
            if (p.statusOwed && !p.fenceOwed && p.fenceRequestId == 0L && now >= p.retryAtNs) {
                val requestId = nextRequestId++
                val length = ParticipantRequests.encodeOrderMassStatusRequest(
                    buffer, 0, p.id, requestId, p.statusSecurityId, statusEncoder, headerEncoder,
                )
                if (!offerRequest(length)) return sent
                p.statusOwed = false
                p.statusRequestId = requestId
                sent++
            }
        }
        return sent
    }

    private fun offerRequest(length: Int): Boolean {
        val result = links[active].offer(buffer, 0, length)
        if (result > 0L) return true
        if (result != Publication.BACK_PRESSURED && result != Publication.ADMIN_ACTION) switchGateway()
        return false
    }

    // ------------------------------------------------------------------ receiving

    private fun onFragment(link: Int, buffer: DirectBuffer, offset: Int, length: Int) {
        if (length < MessageHeaderDecoder.ENCODED_LENGTH) return
        header.wrap(buffer, offset)
        val body = offset + MessageHeaderDecoder.ENCODED_LENGTH
        when (header.templateId()) {
            ClientExecutionReportDecoder.TEMPLATE_ID -> {
                report.wrap(buffer, body, header.blockLength(), header.version())
                onReport(link)
            }
            ReportResendCompleteDecoder.TEMPLATE_ID -> {
                resendComplete.wrap(buffer, body, header.blockLength(), header.version())
                onResendComplete(link)
            }
            OrderMassStatusCompleteDecoder.TEMPLATE_ID -> {
                statusComplete.wrap(buffer, body, header.blockLength(), header.version())
                onStatusComplete(link)
            }
        }
    }

    private fun onReport(link: Int) {
        val i = ledger.indexOf(report.participantId())
        if (i < 0) return
        val p = participants[i]
        val clOrdId = report.clOrdId()
        if (report.execType() == ExecType.REJECTED && report.rejectReason() == RejectReason.GATEWAY_UNAVAILABLE) {
            // The gateway's own refusal: it never forwarded the request, so it never reached the log.
            if (p.inFlight.remove(clOrdId) != null) listener.onSettled(p.id, clOrdId, OrderFate.NEVER_SEQUENCED)
            // Move on once: the rejects still arriving from the same gateway must not move again.
            if (link == active) switchGateway()
            return
        }
        val gapsBefore = ledger.gapsOpened
        if (!ledger.accept(p.id, report.reportSeq())) return
        if (ledger.gapsOpened != gapsBefore && p.fenceRequestId == 0L) p.fenceOwed = true
        p.inFlight.remove(clOrdId)
        listener.onReport(report)
    }

    private fun onResendComplete(link: Int) {
        val i = ledger.indexOf(resendComplete.participantId())
        if (i < 0) return
        val p = participants[i]
        // Another fence's, superseded by a switch, or another session's.
        if (resendComplete.requestId() != p.fenceRequestId) return
        p.fenceRequestId = 0L
        when (val status = resendComplete.status()) {
            RequestStatus.COMPLETE -> {
                settle(p, p.fenceAt, OrderFate.NEVER_SEQUENCED)
                if (ledger.hasGap(i)) p.fenceOwed = true
            }
            RequestStatus.TRUNCATED -> {
                // Reports are gone that no resend brings back: ask what is still open.
                p.statusOwed = true
                p.statusSecurityId = ParticipantRequests.ALL_SECURITIES
                p.statusSettlesBefore = p.fenceAt
            }
            RequestStatus.GATEWAY_UNAVAILABLE -> {
                p.fenceOwed = true
                p.retryAtNs = clock.nanoTime() + config.retryIntervalNs
                if (link == active) switchGateway()
            }
            else -> listener.onRequestRefused(p.id, status)
        }
    }

    private fun onStatusComplete(link: Int) {
        val i = ledger.indexOf(statusComplete.participantId())
        if (i < 0) return
        val p = participants[i]
        if (statusComplete.requestId() != p.statusRequestId) return
        p.statusRequestId = 0L
        val status = statusComplete.status()
        if (status == RequestStatus.GATEWAY_UNAVAILABLE) {
            p.statusOwed = true
            p.retryAtNs = clock.nanoTime() + config.retryIntervalNs
            if (link == active) switchGateway()
            return
        }
        if (status == RequestStatus.COMPLETE) {
            // The status stands in for every report below nextSeq, the ones the ring lost included.
            ledger.resumeFrom(i, statusComplete.nextSeq())
            if (p.statusSettlesBefore >= 0L) settle(p, p.statusSettlesBefore, OrderFate.AMBIGUOUS)
        } else {
            listener.onRequestRefused(p.id, status)
        }
        p.statusSettlesBefore = -1L
        listener.onMassStatusComplete(p.id, status, statusComplete.orderCount(), statusComplete.nextSeq())
        if (p.opening) {
            p.opening = false
            if (--startupPending == 0) becomeReady()
        }
    }

    /** Every request of [p] sent before [before] and still without a report is settled as [fate]. */
    private fun settle(p: Participant, before: Long, fate: OrderFate) {
        settling.clear()
        // Collected first: removing while iterating an open-addressing map can skip entries.
        val keys = p.inFlight.keys.iterator()
        while (keys.hasNext()) {
            val clOrdId = keys.nextLong()
            if (p.inFlight.get(clOrdId)!!.sentPosition < before) settling.addLong(clOrdId)
        }
        for (k in 0..<settling.size) {
            val clOrdId = settling.getLong(k)
            p.inFlight.remove(clOrdId)
            listener.onSettled(p.id, clOrdId, fate)
        }
    }

    private fun checkUnanswered() {
        val now = clock.nanoTime()
        if (now < nextUnansweredCheckNs) return
        nextUnansweredCheckNs = now + UNANSWERED_CHECK_INTERVAL_NS
        val cutoff = now - config.unansweredFenceNs
        for (p in participants) {
            if (p.fenceOwed || p.fenceRequestId != 0L || p.statusRequestId != 0L) continue
            for (request in p.inFlight.values) {
                if (request.sentNs <= cutoff) {
                    p.fenceOwed = true
                    break
                }
            }
        }
    }

    private fun persist() {
        for ((i, p) in participants.withIndex()) {
            val firstMissing = ledger.firstMissing(i)
            if (firstMissing != p.savedFirstMissing) {
                store.save(p.id, firstMissing)
                p.savedFirstMissing = firstMissing
            }
        }
    }

    private fun becomeReady() {
        isReady = true
        listener.onReady()
    }

    private fun participantOf(participantId: Long): Participant? {
        val i = ledger.indexOf(participantId)
        return if (i < 0) null else participants[i]
    }

    private class InFlight(val sentPosition: Long, val sentNs: Long)

    private class Participant(val id: Long) {
        val inFlight = Long2ObjectHashMap<InFlight>()
        var fenceOwed = false
        var fenceRequestId = 0L
        /** [sendPosition] when the fence in flight was sent: it settles every request below. */
        var fenceAt = 0L
        var statusOwed = false
        var statusRequestId = 0L
        var statusSecurityId = ParticipantRequests.ALL_SECURITIES
        /** For a status that closes a truncated resend, the fence's position; -1 otherwise. */
        var statusSettlesBefore = -1L
        var retryAtNs = 0L
        var opening = false
        var savedFirstMissing = 1L
    }

    private companion object {
        const val BUFFER_LENGTH = 128
        val UNANSWERED_CHECK_INTERVAL_NS: Long = TimeUnit.MILLISECONDS.toNanos(100)
    }
}
