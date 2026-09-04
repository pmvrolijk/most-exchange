package com.engine.core

import com.engine.sbe.MessageHeaderEncoder
import com.engine.sbe.NewOrderSingleEncoder
import com.engine.sbe.OrderCancelRequestEncoder
import com.engine.sbe.PurgeExpiredOrdersEncoder
import com.engine.sbe.SessionTransitionEncoder
import io.aeron.Aeron
import io.aeron.DirectBufferVector
import io.aeron.cluster.client.AeronCluster
import io.aeron.cluster.service.ClientSession
import io.aeron.cluster.service.Cluster
import io.aeron.cluster.service.ClusteredServiceContainer
import io.aeron.logbuffer.BufferClaim
import io.aeron.logbuffer.Header
import io.aeron.protocol.DataHeaderFlyweight
import org.agrona.DirectBuffer
import org.agrona.concurrent.IdleStrategy
import org.agrona.concurrent.UnsafeBuffer
import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.engine.sbe.Phase as SbePhase
import com.engine.sbe.Side as SbeSide
import com.engine.sbe.SmpStrategy as SbeSmpStrategy

/**
 * Shared apparatus for the allocation measurements: fakes that allocate nothing themselves, a
 * driver that encodes and dispatches without allocating, and the window assertion.
 *
 * One copy, used by both [AllocationTest] (fakes only, fast, runs in every build) and
 * [AeronAllocationTest] (a live media driver, for the two paths a fake cannot reach). The window
 * assertion in particular must not be duplicated: it encodes the whole argument for why these
 * numbers mean anything, and two copies is how one of them quietly gets its tolerance widened.
 */

/**
 * Services `tryClaim` out of one preallocated buffer and decodes nothing. Reserves the cluster
 * session header exactly as Aeron does, so the service encodes at the offset it uses in
 * production rather than one this fake made convenient (see CLAUDE.md).
 */
internal class CountingSession(private val id: Long) : ClientSession {
    private val buffer = UnsafeBuffer(ByteArray(64 * 1024))
    var claims = 0L
        private set

    override fun id(): Long = id
    override fun responseStreamId(): Int = 1
    override fun responseChannel(): String = "fake"
    override fun encodedPrincipal(): ByteArray = EMPTY
    override fun close() = Unit
    override fun isClosing(): Boolean = false
    override fun offer(b: DirectBuffer, offset: Int, length: Int): Long = 1L
    override fun offer(vectors: Array<out DirectBufferVector>): Long = 1L

    override fun tryClaim(length: Int, bufferClaim: BufferClaim): Long {
        val framed =
            length + AeronCluster.SESSION_HEADER_LENGTH + DataHeaderFlyweight.HEADER_LENGTH
        // Always claim at 0: nothing reads it back, and a moving cursor would need bounds
        // logic that is itself a source of allocation.
        bufferClaim.wrap(buffer, 0, framed)
        claims++
        return 1L
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}

/**
 * Single-session [Cluster]. Resolves by comparison, not a map: a boxed `Long` key allocates.
 *
 * [aeron] is null for the fake-only tests, which never reach it. Supplying a real one lets
 * [MatchingEngineService.onStart] run for real and build a live book-event publication, which is
 * the only way to measure that path — `ExclusivePublication` is `final` and cannot be faked.
 */
internal class SingleSessionCluster(
    private val session: CountingSession,
    private val aeron: Aeron? = null,
) : Cluster {
    private val sessions = mutableListOf<ClientSession>(session)
    private val noopIdle = object : IdleStrategy {
        override fun idle(workCount: Int) = Unit
        override fun idle() = Unit
        override fun reset() = Unit
    }

    override fun memberId(): Int = 0
    override fun role(): Cluster.Role = Cluster.Role.LEADER
    override fun logPosition(): Long = 0
    override fun aeron(): Aeron = aeron ?: throw UnsupportedOperationException("no Aeron in this fake")
    override fun context(): ClusteredServiceContainer.Context = throw UnsupportedOperationException()
    override fun getClientSession(clusterSessionId: Long): ClientSession? =
        if (clusterSessionId == session.id()) session else null
    override fun clientSessions(): MutableCollection<ClientSession> = sessions
    override fun forEachClientSession(action: java.util.function.Consumer<in ClientSession>) =
        action.accept(session)
    override fun closeClientSession(clusterSessionId: Long): Boolean = true
    override fun time(): Long = 0
    override fun timeUnit(): TimeUnit = TimeUnit.MILLISECONDS
    override fun scheduleTimer(correlationId: Long, deadline: Long): Boolean = true
    override fun cancelTimer(correlationId: Long): Boolean = true
    override fun offer(b: DirectBuffer, offset: Int, length: Int): Long = 1
    override fun offer(vectors: Array<out DirectBufferVector>): Long = 1
    override fun tryClaim(length: Int, bufferClaim: BufferClaim): Long = 1
    override fun idleStrategy(): IdleStrategy = noopIdle
}

/**
 * Encodes inbound messages into one reused buffer with one reused encoder, and drives the
 * service. Every object it needs exists before the first measured iteration.
 */
internal class Driver(
    maxOrders: Int = 4096,
    /** A real Aeron makes the book-event publication live; null leaves it muted. */
    aeron: Aeron? = null,
    bookEventChannel: String = "aeron:ipc",
    bookEventStreamId: Int = 12,
    /** Hot-path timing, exactly as a node would run it. Null is the production default. */
    metrics: EngineMetrics? = null,
) {
    val book = OrderBook(
        securityId = Alloc.SECURITY_ID,
        priceFloor = 0L,
        tickSize = 1L,
        levelCount = Alloc.LEVELS,
        maxOrders = maxOrders,
    )
    private val books = arrayOf(book)
    val session = CountingSession(Alloc.SESSION_ID)
    private val cluster = SingleSessionCluster(session, aeron)
    val service = MatchingEngineService(
        shardId = 1,
        books = books,
        shardFingerprint = 0x1234_5678_9abc_def0L,
        bookEventChannel = bookEventChannel,
        bookEventStreamId = bookEventStreamId,
        levelCount = Alloc.LEVELS,
        metrics = metrics,
    )
    private val useRealAeron = aeron != null

    private val buffer = UnsafeBuffer(ByteArray(4096))
    private val headerEncoder = MessageHeaderEncoder()
    private val newOrderEncoder = NewOrderSingleEncoder()
    private val cancelEncoder = OrderCancelRequestEncoder()
    private val sessionEncoder = SessionTransitionEncoder()
    private val purgeEncoder = PurgeExpiredOrdersEncoder()
    private val header = Header(0, 0)

    private val newOrderLength =
        MessageHeaderEncoder.ENCODED_LENGTH + NewOrderSingleEncoder.BLOCK_LENGTH
    private val cancelLength =
        MessageHeaderEncoder.ENCODED_LENGTH + OrderCancelRequestEncoder.BLOCK_LENGTH
    private val sessionLength =
        MessageHeaderEncoder.ENCODED_LENGTH + SessionTransitionEncoder.BLOCK_LENGTH
    private val purgeLength =
        MessageHeaderEncoder.ENCODED_LENGTH + PurgeExpiredOrdersEncoder.BLOCK_LENGTH

    init {
        if (useRealAeron) {
            // The shipped startup path, which is what builds the book-event publication.
            service.onStart(cluster, null)
        } else {
            // onStart would need a live Aeron for a publication these tests never reach, so the
            // lifecycle is driven directly instead.
            MatchingEngineService::class.java.getDeclaredField("cluster")
                .apply { isAccessible = true }.set(service, cluster)
        }
        service.onRoleChange(Cluster.Role.LEADER)
        book.phase = Phase.CONTINUOUS
        book.tradingDate = Alloc.TRADING_DATE
    }

    fun newOrder(participantId: Long, clOrdId: Long, side: Byte, price: Long, qty: Long) {
        newOrderEncoder.wrapAndApplyHeader(buffer, 0, headerEncoder)
            .participantId(participantId)
            .clOrdId(clOrdId)
            .price(price)
            .qty(qty)
            .smpId(0L)
            .securityId(Alloc.SECURITY_ID)
            .expireDate(0)
            .side(SbeSide.get(side))
            .smpStrategy(SbeSmpStrategy.get(SmpStrategy.CANCEL_AGGRESSOR))
        service.onSessionMessage(session, 0L, buffer, 0, newOrderLength, header)
    }

    fun sessionTransition(phase: Byte) {
        sessionEncoder.wrapAndApplyHeader(buffer, 0, headerEncoder)
            .transitionTime(0L)
            .tradingDate(Alloc.TRADING_DATE)
            .targetPhase(SbePhase.get(phase))
        service.onSessionMessage(session, 0L, buffer, 0, sessionLength, header)
    }

    fun purge(tradingDate: Int) {
        purgeEncoder.wrapAndApplyHeader(buffer, 0, headerEncoder)
            .purgeTime(0L)
            .tradingDate(tradingDate)
        service.onSessionMessage(session, 0L, buffer, 0, purgeLength, header)
    }

    fun newOrderExpiring(participantId: Long, clOrdId: Long, side: Byte, price: Long, expires: Int) {
        newOrderEncoder.wrapAndApplyHeader(buffer, 0, headerEncoder)
            .participantId(participantId)
            .clOrdId(clOrdId)
            .price(price)
            .qty(1L)
            .smpId(0L)
            .securityId(Alloc.SECURITY_ID)
            .expireDate(expires)
            .side(SbeSide.get(side))
            .smpStrategy(SbeSmpStrategy.get(SmpStrategy.CANCEL_AGGRESSOR))
        service.onSessionMessage(session, 0L, buffer, 0, newOrderLength, header)
    }

    fun cancel(participantId: Long, origClOrdId: Long, clOrdId: Long, orderId: Long, side: Byte) {
        cancelEncoder.wrapAndApplyHeader(buffer, 0, headerEncoder)
            .participantId(participantId)
            .origClOrdId(origClOrdId)
            .clOrdId(clOrdId)
            .exchangeOrderId(orderId)
            .securityId(Alloc.SECURITY_ID)
            .side(SbeSide.get(side))
        service.onSessionMessage(session, 0L, buffer, 0, cancelLength, header)
    }
}

// ------------------------------------------------------------ measurement

internal object Alloc {
    const val SECURITY_ID = 1
    const val SESSION_ID = 7L
    const val LEVELS = 4096
    const val TRADING_DATE = 20260903
    const val PRICE = 1_000L

    /** Rounds run before anything is measured, to reach a steady state. */
    const val WARMUP = 100_000

    /** Rounds per measured window. */
    const val WINDOW = 100_000

    /** Successive measured windows. A rate shows in all of them; a one-off in at most one. */
    const val WINDOWS = 8

    val threads: com.sun.management.ThreadMXBean =
        ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
}

/** Bytes allocated by this thread while running [body]. */
internal inline fun allocatedBytes(body: () -> Unit): Long {
    val before = Alloc.threads.currentThreadAllocatedBytes
    body()
    return Alloc.threads.currentThreadAllocatedBytes - before
}

/**
 * Drives [round] over [Alloc.WINDOWS] successive windows and asserts it has no steady-state
 * allocation cost.
 *
 * The criterion is **a strict majority of windows reading exactly zero**, plus a magnitude backstop
 * of under one byte per operation across all of them. The asymmetry is deliberate and it is safe.
 *
 * A steady-state cost appears in *every* window — that is what steady-state means. Even one byte
 * per operation is tens of thousands of bytes per window here, so a rate produces **zero** zero
 * windows and fails on the first clause. What the majority rule tolerates is the one-off: a late
 * JIT recompilation or a deoptimisation lands in whichever window it falls in, as a few hundred
 * bytes. Those are unpredictable in number — running one test alone typically produces one, running
 * the whole suite together produces two or three, because there is more compilation in flight — and
 * an "at most one" rule turns that into a flaky test rather than a strict one.
 *
 * Tuning warm-up counts per workload was the alternative and it is the wrong shape: how long the
 * one-time work takes to drain depends on how heavy a round is, so a count tuned for one test is
 * wrong for the next, and "not warmed up enough" becomes indistinguishable from "found a bug".
 *
 * **Do not widen this to make a failure go away.** A failure here means either a real per-operation
 * allocation, or a workload so light that a blip is a large share of it — in which case the fix is
 * more rounds, not more tolerance.
 */
internal fun assertNoSteadyStateAllocation(
    what: String,
    opsPerRound: Int,
    rounds: Int = Alloc.WINDOW,
    round: () -> Unit,
) {
    repeat((Alloc.WARMUP / opsPerRound).coerceAtLeast(1)) { round() }

    var zeroWindows = 0
    var total = 0L
    var worst = 0L
    repeat(Alloc.WINDOWS) {
        val bytes = allocatedBytes { repeat(rounds) { round() } }
        if (bytes == 0L) zeroWindows++ else if (bytes > worst) worst = bytes
        total += bytes
    }

    val ops = rounds.toLong() * opsPerRound * Alloc.WINDOWS
    val required = Alloc.WINDOWS / 2 + 1
    assertTrue(
        zeroWindows >= required,
        "$what allocated in ${Alloc.WINDOWS - zeroWindows} of ${Alloc.WINDOWS} windows " +
            "(worst $worst bytes, $total total). Fewer than $required clean windows means a rate, " +
            "not warm-up, and under Epsilon a rate is heap exhaustion on every node at once " +
            "(Design.md §7).",
    )
    assertTrue(
        total < ops,
        "$what allocated $total bytes over $ops operations — at or above one byte per operation, " +
            "which is not a steady state this can run under Epsilon.",
    )
}

// ----------------------------------------------------------------- tests

/**
 * The control: the instrument must read zero on something known to allocate nothing, or a zero
 * anywhere else in this class means only that the instrument is broken.
 *
 * It needs the same settle window the workload assertions do, and for the same reason — run
 * immediately after a warmup it reports ~1,400 bytes across 100,000 reads of a native counter,
 * which is late JIT work landing in the window rather than anything the reads did.
 *
 * The *harness* is proven separately and more strongly: the four workload tests below drive
 * the service through this class's own encoders and fakes and come out at exactly zero, which
 * they could not do if the driving loop allocated.
 */
