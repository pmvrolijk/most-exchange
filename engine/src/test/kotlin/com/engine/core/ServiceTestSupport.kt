package com.engine.core

import com.engine.sbe.ExecutionReportDecoder
import com.engine.sbe.MessageHeaderDecoder
import com.engine.sbe.MessageHeaderEncoder
import com.engine.sbe.NewOrderSingleEncoder
import com.engine.sbe.OrderCancelRequestEncoder
import com.engine.sbe.PurgeExpiredOrdersEncoder
import com.engine.sbe.SecurityDefinitionEncoder
import com.engine.sbe.SessionTransitionEncoder
import io.aeron.Aeron
import io.aeron.DirectBufferVector
import io.aeron.cluster.service.ClientSession
import io.aeron.cluster.service.Cluster
import io.aeron.cluster.service.ClusteredServiceContainer
import io.aeron.cluster.client.AeronCluster
import io.aeron.logbuffer.BufferClaim
import io.aeron.protocol.DataHeaderFlyweight
import org.agrona.DirectBuffer
import org.agrona.concurrent.IdleStrategy
import org.agrona.concurrent.UnsafeBuffer
import java.util.concurrent.TimeUnit
import com.engine.sbe.Phase as SbePhase
import com.engine.sbe.Side as SbeSide
import com.engine.sbe.SmpStrategy as SbeSmpStrategy

/** A decoded execution report, captured off a fake session's egress. */
data class Report(
    val participantId: Long,
    val clOrdId: Long,
    val exchangeOrderId: Long,
    val securityId: Int,
    val execType: String,
    val side: Byte,
    val price: Long,
    val lastQty: Long,
    val leavesQty: Long,
    val rejectReason: Int,
)

/**
 * Captures egress by servicing tryClaim out of its own buffer, which is how the service
 * writes in production — this exercises the real encode path rather than a stub.
 */
/**
 * Captures egress by servicing tryClaim out of its own buffer, which is how the service writes
 * in production — this exercises the real encode path rather than a stub.
 *
 * Each claim gets a fresh region so a single inbound message that produces several reports (a
 * multi-fill order reports both sides of every fill) is captured in full, in order.
 */
class FakeSession(
    private val sessionId: Long,
    private val sink: MutableList<Report> = mutableListOf(),
) : ClientSession {

    private val buffer = UnsafeBuffer(ByteArray(256 * 1024))
    private val claimed = mutableListOf<Int>()
    private var position = 0

    private val header = MessageHeaderDecoder()
    private val decoder = ExecutionReportDecoder()

    val reports: List<Report> get() = sink

    override fun id(): Long = sessionId
    override fun responseStreamId(): Int = 1
    override fun responseChannel(): String = "fake"
    override fun encodedPrincipal(): ByteArray = ByteArray(0)
    override fun close() = Unit
    override fun isClosing(): Boolean = false
    override fun offer(b: DirectBuffer, offset: Int, length: Int): Long = 1L
    override fun offer(vectors: Array<out DirectBufferVector>): Long = 1L

    /**
     * Reserves room for the cluster session header exactly as Aeron does, so the payload offset
     * the service must use is the one it would use in production.
     */
    override fun tryClaim(length: Int, bufferClaim: BufferClaim): Long {
        val framed = length + AeronCluster.SESSION_HEADER_LENGTH + DataHeaderFlyweight.HEADER_LENGTH
        bufferClaim.wrap(buffer, position, framed)
        claimed += position + DataHeaderFlyweight.HEADER_LENGTH + AeronCluster.SESSION_HEADER_LENGTH
        position += (framed + 31) and 31.inv()
        return 1L
    }

    /** Decodes everything claimed since the last drain, in claim order. */
    fun drain() {
        for (offset in claimed) {
            header.wrap(buffer, offset)
            if (header.templateId() != ExecutionReportDecoder.TEMPLATE_ID) continue
            decoder.wrap(
                buffer,
                offset + MessageHeaderDecoder.ENCODED_LENGTH,
                header.blockLength(),
                header.version(),
            )
            sink += Report(
                decoder.participantId(),
                decoder.clOrdId(),
                decoder.exchangeOrderId(),
                decoder.securityId(),
                decoder.execType().name,
                decoder.side().value(),
                decoder.price(),
                decoder.lastQty(),
                decoder.leavesQty(),
                decoder.rejectReason().value(),
            )
        }
        claimed.clear()
        position = 0
    }
}

class FakeCluster(private val sessions: Map<Long, ClientSession>) : Cluster {
    var currentRole: Cluster.Role = Cluster.Role.LEADER

    override fun memberId(): Int = 0
    override fun role(): Cluster.Role = currentRole
    override fun logPosition(): Long = 0
    override fun aeron(): Aeron = throw UnsupportedOperationException("not needed in tests")
    override fun context(): ClusteredServiceContainer.Context =
        throw UnsupportedOperationException("not needed in tests")

    override fun getClientSession(clusterSessionId: Long): ClientSession? = sessions[clusterSessionId]
    override fun clientSessions(): MutableCollection<ClientSession> = sessions.values.toMutableList()
    override fun forEachClientSession(action: java.util.function.Consumer<in ClientSession>) =
        sessions.values.forEach(action::accept)

    override fun closeClientSession(clusterSessionId: Long): Boolean = true
    override fun time(): Long = 0
    override fun timeUnit(): TimeUnit = TimeUnit.MILLISECONDS
    override fun scheduleTimer(correlationId: Long, deadline: Long): Boolean = true
    override fun cancelTimer(correlationId: Long): Boolean = true
    override fun offer(b: DirectBuffer, offset: Int, length: Int): Long = 1
    override fun offer(vectors: Array<out DirectBufferVector>): Long = 1
    override fun tryClaim(length: Int, bufferClaim: BufferClaim): Long = 1
    private val noopIdle = object : IdleStrategy {
        override fun idle(workCount: Int) = Unit
        override fun idle() = Unit
        override fun reset() = Unit
    }

    override fun idleStrategy(): IdleStrategy = noopIdle
}

/** Builds inbound SBE messages and feeds them through the service. */
class Harness(
    val books: Array<OrderBook>,
    levelCount: Int = 1024,
    auctionMaxPasses: Int = 64,
    metrics: EngineMetrics? = null,
    /**
     * A stand-in for `ShardSpec.fingerprintValue()`. Deliberately a plain parameter rather than
     * something derived from [books]: deriving it would be a second implementation of a hash whose
     * only job is agreement. A test that changes geometry passes a different value, which is what
     * publishing a new security file does.
     */
    shardFingerprint: Long = FINGERPRINT,
) {
    val session = FakeSession(SESSION_ID)
    private val cluster = FakeCluster(mapOf(SESSION_ID to session))
    val service = MatchingEngineService(
        shardId = SHARD_ID,
        books = books,
        shardFingerprint = shardFingerprint,
        bookEventChannel = "aeron:ipc",
        bookEventStreamId = 12,
        levelCount = levelCount,
        auctionMaxPasses = auctionMaxPasses,
        metrics = metrics,
    )

    private val buffer = UnsafeBuffer(ByteArray(4096))
    private val headerEncoder = MessageHeaderEncoder()

    /**
     * Drives the service's snapshot restore directly.
     *
     * `onStart` cannot be used here: it would also create the book-event publication, which needs
     * a live Aeron. The reflection sits beside the `cluster` field poke below for the same reason
     * and in the same one place.
     */
    fun restore(image: io.aeron.Image) {
        val method = service.javaClass
            .getDeclaredMethod("loadSnapshot", io.aeron.Image::class.java)
            .apply { isAccessible = true }
        try {
            method.invoke(service, image)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }
    }

    init {
        // onStart would create the book-event publication, which needs a live Aeron. Drive the
        // lifecycle directly instead: this test exercises engine logic, not Aeron plumbing.
        service.javaClass.getDeclaredField("cluster").apply { isAccessible = true }
            .set(service, cluster)
        service.onRoleChange(Cluster.Role.LEADER)
    }

    val reports: List<Report> get() = session.reports

    private fun submit(length: Int) {
        service.onSessionMessage(session, 0L, buffer, 0, length, DUMMY_HEADER)
        session.drain()
    }

    fun newOrder(
        participantId: Long,
        clOrdId: Long,
        securityId: Int,
        side: Byte,
        price: Long,
        qty: Long,
        smpId: Long = 0L,
        expireDate: Int = 0,
        smpStrategy: Byte = SmpStrategy.CANCEL_AGGRESSOR,
    ) {
        NewOrderSingleEncoder().wrapAndApplyHeader(buffer, 0, headerEncoder)
            .participantId(participantId)
            .clOrdId(clOrdId)
            .price(price)
            .qty(qty)
            .smpId(smpId)
            .securityId(securityId)
            .expireDate(expireDate)
            .side(SbeSide.get(side))
            .smpStrategy(SbeSmpStrategy.get(smpStrategy))
        submit(MessageHeaderEncoder.ENCODED_LENGTH + NewOrderSingleEncoder.BLOCK_LENGTH)
    }

    fun cancel(
        participantId: Long,
        origClOrdId: Long,
        clOrdId: Long,
        exchangeOrderId: Long,
        securityId: Int,
        side: Byte,
    ) {
        OrderCancelRequestEncoder().wrapAndApplyHeader(buffer, 0, headerEncoder)
            .participantId(participantId)
            .origClOrdId(origClOrdId)
            .clOrdId(clOrdId)
            .exchangeOrderId(exchangeOrderId)
            .securityId(securityId)
            .side(SbeSide.get(side))
        submit(MessageHeaderEncoder.ENCODED_LENGTH + OrderCancelRequestEncoder.BLOCK_LENGTH)
    }

    fun sessionTransition(phase: Byte, tradingDate: Int = 20260829) {
        SessionTransitionEncoder().wrapAndApplyHeader(buffer, 0, headerEncoder)
            .transitionTime(0L)
            .tradingDate(tradingDate)
            .targetPhase(SbePhase.get(phase))
        submit(MessageHeaderEncoder.ENCODED_LENGTH + SessionTransitionEncoder.BLOCK_LENGTH)
    }

    fun purge(tradingDate: Int) {
        PurgeExpiredOrdersEncoder().wrapAndApplyHeader(buffer, 0, headerEncoder)
            .purgeTime(0L)
            .tradingDate(tradingDate)
        submit(MessageHeaderEncoder.ENCODED_LENGTH + PurgeExpiredOrdersEncoder.BLOCK_LENGTH)
    }

    fun defineSecurity(
        book: OrderBook,
        referencePrice: Long,
        staticCollarBps: Int = 0,
        dynamicCollarBps: Int = 0,
        levelCount: Int = book.levelCount,
        tickSize: Long = book.tickSize,
        priceFloor: Long = book.priceFloor,
    ) {
        SecurityDefinitionEncoder().wrapAndApplyHeader(buffer, 0, headerEncoder)
            .referencePrice(referencePrice)
            .priceFloor(priceFloor)
            .tickSize(tickSize)
            .securityId(book.securityId)
            .staticCollarBps(staticCollarBps)
            .dynamicCollarBps(dynamicCollarBps)
            .levelCount(levelCount)
        submit(MessageHeaderEncoder.ENCODED_LENGTH + SecurityDefinitionEncoder.BLOCK_LENGTH)
    }

    private companion object {
        const val SHARD_ID = 1
        const val SESSION_ID = 7L

        /** Stands in for one particular published security file. */
        const val FINGERPRINT = 0x1234_5678_9abc_def0L
        val DUMMY_HEADER = io.aeron.logbuffer.Header(0, 0)
    }
}

fun serviceBook(
    securityId: Int = 1,
    levelCount: Int = 1024,
    maxOrders: Int = 512,
    priceFloor: Long = 0L,
    tickSize: Long = 1L,
): OrderBook =
    OrderBook(
        securityId = securityId,
        priceFloor = priceFloor,
        tickSize = tickSize,
        levelCount = levelCount,
        maxOrders = maxOrders,
    )
