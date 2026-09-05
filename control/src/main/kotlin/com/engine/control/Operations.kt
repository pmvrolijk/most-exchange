package com.engine.control

import com.engine.reference.OperatorCommands
import com.engine.reference.PriceCodec
import com.engine.sbe.Phase
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.LocalDate

/**
 * The outcome of one command.
 *
 * [sent] and [confirmed] are separate on purpose. Operator commands are not acknowledged: the
 * engine applies or rejects them without replying. `sent` means the bytes reached the gateway;
 * `confirmed` means the control plane afterwards *observed* the effect on the book event stream.
 * Collapsing the two into one boolean would be a lie in whichever direction it fell.
 */
data class CommandResult(
    val command: String,
    val sent: Boolean,
    val confirmed: Boolean,
    val detail: String,
)

/** What a reopen did, step by step, and what it moved along with the halted security. */
data class ReopenResult(
    val shardId: Int,
    val securities: List<String>,
    val steps: List<CommandResult>,
    val succeeded: Boolean,
    val warning: String?,
)

/**
 * Live cluster control: seeding definitions, moving sessions, purging, and reopening a halted
 * security.
 *
 * Everything here goes to the gateway's client channel as ordinary SBE and is sequenced through the
 * replicated log, so a command is applied at the same log position on every node.
 */
@Service
class OperationsService(
    private val topology: TopologyService,
    private val link: ClusterLink,
) {

    /**
     * Seeds or re-seeds a security's reference price and collars.
     *
     * Geometry is taken from the database, never from the caller: the engine rejects a definition
     * whose `priceFloor`, `tickSize` or `levelCount` disagrees with the book it already allocated,
     * and it rejects it *silently* — a counter, no reply. Sending geometry that came from the same
     * place the shard booted from is what keeps that from being possible.
     *
     * Never reports `confirmed`: nothing on any feed acknowledges a definition. This is the one
     * real hole in feed-based confirmation and it is stated rather than papered over.
     */
    fun define(
        securityId: Int,
        referencePrice: Long? = null,
        staticCollarBps: Int? = null,
        dynamicCollarBps: Int? = null,
    ): CommandResult {
        val security = requireNotNull(topology.security(securityId)) {
            "no such security: $securityId"
        }
        val reference = referencePrice ?: security.referencePrice
        requireNotNull(reference) {
            "security ${security.symbol} has no reference price: set one on the security, " +
                "or pass referencePrice with this request"
        }
        require(reference > 0L) { "referencePrice must be positive, got $reference" }
        val staticBps = staticCollarBps ?: security.staticCollarBps ?: 0
        val dynamicBps = dynamicCollarBps ?: security.dynamicCollarBps ?: 0

        // The band must sit inside the pre-allocated ladder or the engine refuses the definition,
        // again silently. Checking here turns that into a message rather than a mystery.
        val ceiling = security.priceFloor + security.tickSize * (security.levelCount - 1L)
        val upper = reference + reference * staticBps / BPS
        val lower = reference - reference * staticBps / BPS
        require(lower >= security.priceFloor && upper <= ceiling) {
            "a ${staticBps}bp static band around ${PriceCodec.format(reference)} spans " +
                "${PriceCodec.format(lower)}..${PriceCodec.format(upper)}, outside " +
                "${security.symbol}'s ladder ${PriceCodec.format(security.priceFloor)}.." +
                "${PriceCodec.format(ceiling)}; the engine would reject it without replying"
        }

        val outcome = link.send(security.shardId) { buffer ->
            OperatorCommands.encodeSecurityDefinition(
                buffer,
                securityId = security.securityId,
                referencePrice = reference,
                priceFloor = security.priceFloor,
                tickSize = security.tickSize,
                levelCount = security.levelCount,
                staticCollarBps = staticBps,
                dynamicCollarBps = dynamicBps,
            )
        }
        return CommandResult(
            command = "define ${security.symbol}",
            sent = outcome.sent,
            confirmed = false,
            detail = if (outcome.sent) {
                "seeded reference ${PriceCodec.format(reference)}, static ${staticBps}bps, " +
                    "dynamic ${dynamicBps}bps -- not confirmable: no feed acknowledges a " +
                    "SecurityDefinition, so a rejection would be silent"
            } else {
                outcome.detail
            },
        )
    }

    /**
     * Moves a shard to a phase, then waits to see it on the feed.
     *
     * **Shard-wide.** The engine applies the target phase to every book it hosts; there is no
     * per-security session command. Reopening one halted security therefore moves all of them, and
     * callers are told which.
     */
    fun session(
        shardId: Int,
        phase: Byte,
        tradingDate: Int = OperatorCommands.tradingDateOf(LocalDate.now()),
        await: Duration = CONFIRM_TIMEOUT,
    ): CommandResult {
        val securities = topology.securitiesOfShardOrThrow(shardId)
        val name = Phase.get(phase).name
        val outcome = link.send(shardId) { buffer ->
            OperatorCommands.encodeSessionTransition(buffer, phase, tradingDate)
        }
        if (!outcome.sent) {
            return CommandResult("session $name shard $shardId", false, false, outcome.detail)
        }

        val confirmed = awaitPhase(securities.map { it.securityId }, name, await)
        return CommandResult(
            command = "session $name shard $shardId",
            sent = true,
            confirmed = confirmed,
            detail = if (confirmed) {
                "every book on shard $shardId reported $name"
            } else {
                "sent, but ${name} was not observed on the feed within ${await.toMillis()}ms " +
                    "(is market-data running and is control.l3 pointed at it?)"
            },
        )
    }

    /** The off-session expiry sweep. Nothing on the feed confirms it except the orders it removes. */
    fun purge(
        shardId: Int,
        tradingDate: Int = OperatorCommands.tradingDateOf(LocalDate.now()),
    ): CommandResult {
        topology.securitiesOfShardOrThrow(shardId)
        val outcome = link.send(shardId) { buffer ->
            OperatorCommands.encodePurgeExpiredOrders(buffer, tradingDate)
        }
        return CommandResult(
            command = "purge shard $shardId",
            sent = outcome.sent,
            confirmed = false,
            detail = if (outcome.sent) "purged for trading date $tradingDate" else outcome.detail,
        )
    }

    /**
     * Asks the shard to republish every book as a level image.
     *
     * For a market data process that restarted while the engine kept running. Depth is derived
     * entirely from the book event stream, so a restarted one has no book and nothing to rebuild
     * from — and unlike the engine it has no snapshot of its own to fall back on.
     *
     * Goes through the gateway like the other four, and is unacknowledged like them: what it
     * produces is output on a feed nobody replies on. It changes no book and moves no market, so
     * repeating it is harmless — which is worth knowing, because the way to tell whether it worked
     * is to look at whether the books came back.
     */
    fun requestBookImage(shardId: Int): CommandResult {
        topology.securitiesOfShardOrThrow(shardId)
        val outcome = link.send(shardId) { buffer ->
            OperatorCommands.encodeRequestBookImage(buffer)
        }
        return CommandResult(
            command = "book image shard $shardId",
            sent = outcome.sent,
            confirmed = false,
            detail = if (outcome.sent) "asked shard $shardId to republish its books"
            else outcome.detail,
        )
    }

    /**
     * The halt-recovery runbook, as one operation (Design.md §4.6, which lists writing it down as
     * an open item).
     *
     * Re-seeding the definition **first** is the part that is easy to get wrong and impossible to
     * debug: `staticReference` is not reset until the reopening uncross *executes*, but orders are
     * accepted from `PRE_OPEN` onward. If the halt moved price outside the old static band, the
     * very orders needed to reopen are rejected with `PRICE_OUT_OF_BOUNDS` before the auction that
     * would have reset the anchor can run — a deadlock that looks like nothing happening.
     *
     * Then `PRE_OPEN → OPEN_AUCTION → CONTINUOUS`, in that order, because the uncross runs only on
     * the last transition. Jumping straight to `CONTINUOUS` is accepted by the engine and skips the
     * auction entirely.
     */
    fun reopen(
        shardId: Int,
        referencePrice: Long? = null,
        staticCollarBps: Int? = null,
        dynamicCollarBps: Int? = null,
        securityId: Int? = null,
        tradingDate: Int = OperatorCommands.tradingDateOf(LocalDate.now()),
    ): ReopenResult {
        val securities = topology.securitiesOfShardOrThrow(shardId)
        val steps = mutableListOf<CommandResult>()

        // Re-seed before PRE_OPEN, never after.
        val toDefine = when {
            securityId != null -> securities.filter { it.securityId == securityId }
                .ifEmpty { throw IllegalArgumentException("security $securityId is not on shard $shardId") }
            referencePrice != null -> throw IllegalArgumentException(
                "referencePrice needs a securityId: a reference price belongs to one security, " +
                    "and a shard hosts up to ten"
            )
            else -> securities.filter { it.referencePrice != null }
        }
        for (security in toDefine) {
            steps += define(security.securityId, referencePrice, staticCollarBps, dynamicCollarBps)
        }

        for (phase in OperatorCommands.REOPEN_SEQUENCE) {
            val step = session(shardId, phase, tradingDate)
            steps += step
            if (!step.sent) break
        }

        val moved = securities.map { it.symbol }
        val halted = securities.filter { link.state.securityState(it.securityId)?.halt != null }
        return ReopenResult(
            shardId = shardId,
            securities = moved,
            steps = steps,
            succeeded = steps.all { it.sent } && steps.last().confirmed,
            // Said every time, not only when it bites: an operator reopening one broken security
            // has just reopened every other book on the shard as well.
            warning = if (moved.size > 1) {
                "a session transition is shard-wide: this moved ${moved.joinToString(", ")}" +
                    if (halted.size == 1) ", not only ${halted.single().symbol}" else ""
            } else {
                null
            },
        )
    }

    private fun awaitPhase(securityIds: List<Int>, phase: String, timeout: Duration): Boolean {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            if (securityIds.all { link.state.securityState(it)?.phase == phase }) return true
            Thread.sleep(CONFIRM_POLL_MS)
        }
        return false
    }

    private companion object {
        const val BPS = 10_000L
        const val CONFIRM_POLL_MS = 20L
        val CONFIRM_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}
