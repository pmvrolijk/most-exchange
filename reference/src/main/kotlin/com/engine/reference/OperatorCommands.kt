package com.engine.reference

import com.engine.sbe.MessageHeaderEncoder
import com.engine.sbe.Phase
import com.engine.sbe.PurgeExpiredOrdersEncoder
import com.engine.sbe.SecurityDefinitionEncoder
import com.engine.sbe.SessionTransitionEncoder
import org.agrona.MutableDirectBuffer
import java.time.LocalDate

/**
 * Encoders for the three operator commands, shared by every sender.
 *
 * These go to the **gateway's** client inbound channel like any other message: the gateway does not
 * recognise them, so it forwards them into the cluster untouched, and they are sequenced through
 * the replicated log exactly like an order. There is no separate control channel, which is what
 * makes them deterministic across nodes.
 *
 * Extracted here because the CLI and the control plane both send them, and a second encoding of a
 * wire message is a second thing to get wrong — a field written in the wrong order or a length
 * miscounted produces a command the engine silently ignores.
 *
 * **None of them is acknowledged.** The engine applies or rejects them without replying, and a
 * rejected `SecurityDefinition` increments a counter and nothing else. A sender learns a phase
 * transition happened by seeing `SessionChanged` on the book event stream; nothing at all confirms
 * a definition (Design.md §5).
 */
object OperatorCommands {

    /**
     * Seeds or re-seeds a security's reference prices and collar widths.
     *
     * The geometry fields are not the operator's to choose — the engine rejects a definition whose
     * `priceFloor`, `tickSize` or `levelCount` disagrees with the book it already allocated — so
     * they must come from the same reference data the shard booted from.
     *
     * Re-issuing this is the lever for reopening a security after a volatility halt: it re-seeds
     * `staticReference`, without which orders at the new price level are rejected by the old collar
     * before the auction that would have reset the anchor can run (Design.md §4.6).
     */
    fun encodeSecurityDefinition(
        buffer: MutableDirectBuffer,
        securityId: Int,
        referencePrice: Long,
        priceFloor: Long,
        tickSize: Long,
        levelCount: Int,
        staticCollarBps: Int,
        dynamicCollarBps: Int,
    ): Int {
        SecurityDefinitionEncoder().wrapAndApplyHeader(buffer, 0, MessageHeaderEncoder())
            .referencePrice(referencePrice)
            .priceFloor(priceFloor)
            .tickSize(tickSize)
            .securityId(securityId)
            .staticCollarBps(staticCollarBps)
            .dynamicCollarBps(dynamicCollarBps)
            .levelCount(levelCount)
        return MessageHeaderEncoder.ENCODED_LENGTH + SecurityDefinitionEncoder.BLOCK_LENGTH
    }

    /**
     * Moves a **shard** to a phase. Not a security: the engine applies the target phase to every
     * book it hosts, so reopening one halted security moves all of them.
     *
     * `transitionTime` is zero because the engine ignores it — time comes from the sequenced
     * consensus timestamp, never from a field a client filled in.
     */
    fun encodeSessionTransition(
        buffer: MutableDirectBuffer,
        targetPhase: Byte,
        tradingDate: Int,
    ): Int {
        SessionTransitionEncoder().wrapAndApplyHeader(buffer, 0, MessageHeaderEncoder())
            .transitionTime(0L)
            .tradingDate(tradingDate)
            .targetPhase(Phase.get(targetPhase))
        return MessageHeaderEncoder.ENCODED_LENGTH + SessionTransitionEncoder.BLOCK_LENGTH
    }

    /** The off-session expiry sweep (Design.md §4.3), run well before `PRE_OPEN`. */
    fun encodePurgeExpiredOrders(buffer: MutableDirectBuffer, tradingDate: Int): Int {
        PurgeExpiredOrdersEncoder().wrapAndApplyHeader(buffer, 0, MessageHeaderEncoder())
            .purgeTime(0L)
            .tradingDate(tradingDate)
        return MessageHeaderEncoder.ENCODED_LENGTH + PurgeExpiredOrdersEncoder.BLOCK_LENGTH
    }

    /**
     * `YYYYMMDD` as the wire carries it.
     *
     * A sender's own clock, which is correct here and would not be inside the engine: expiry is
     * decided by comparing an order's `expireDate` against the trading date *carried on the
     * sequenced command*, so the date is an input to the log rather than something a node reads
     * from a clock and could disagree about.
     */
    fun tradingDateOf(date: LocalDate): Int =
        date.year * 10_000 + date.monthValue * 100 + date.dayOfMonth

    /** Parses the phase names the CLI and the API accept. */
    fun parsePhase(text: String): Byte = when (text.lowercase().replace("_", "-")) {
        "closed" -> Phase.CLOSED.value()
        "pre-open", "preopen" -> Phase.PRE_OPEN.value()
        "open-auction", "auction" -> Phase.OPEN_AUCTION.value()
        "continuous" -> Phase.CONTINUOUS.value()
        else -> throw IllegalArgumentException(
            "phase must be closed, pre-open, open-auction or continuous, got '$text'"
        )
    }

    /** The recovery sequence a halted security must be walked through (Design.md §4.6). */
    val REOPEN_SEQUENCE: List<Byte> =
        listOf(Phase.PRE_OPEN.value(), Phase.OPEN_AUCTION.value(), Phase.CONTINUOUS.value())
}
