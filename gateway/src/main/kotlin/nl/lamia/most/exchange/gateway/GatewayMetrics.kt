package nl.lamia.most.exchange.gateway

import nl.lamia.most.exchange.reference.LatencyHistogram
import nl.lamia.most.exchange.reference.measureClockOverheadNanos
import org.agrona.concurrent.NanoClock

/**
 * Hot-path timing for the gateway (Design.md §2, §7).
 *
 * The gateway is not a replicated state machine, so none of [EngineMetrics][nl.lamia.most.exchange.core]'s
 * determinism argument is needed here — it may read a clock freely. What it shares is the cost
 * model: two clock reads per message, off unless asked for.
 *
 * The two legs are deliberately separate because they fail differently. [inbound] carries the
 * cluster offer, so cluster backpressure shows up in its tail and nowhere else. [outbound] carries
 * the `cumQty` reconstruction and the client publication, which is the leg that drops rather than
 * blocks (CLAUDE.md) — so a rising [outbound] tail and a rising `droppedToClient` are the same
 * story told twice.
 *
 * Together with the engine's own numbers these are what turn a client round trip into an
 * attribution: what the round trip does not spend in this process or in the engine, it spent in
 * consensus, the archive write, and the wire.
 */
class GatewayMetrics(private val clock: NanoClock) {
    /** Client fragment to cluster offer: validation, local rejection, and the offer itself. */
    val inbound = LatencyHistogram("gateway.inbound")

    /** Cluster egress to client: decode, `cumQty` reconstruction, and the client publication. */
    val outbound = LatencyHistogram("gateway.outbound")

    fun nanoTime(): Long = clock.nanoTime()

    /**
     * What one clock read costs here; see [measureClockOverheadNanos].
     *
     * Measured at construction, which is start-up, because the process is quiet then. Measuring it
     * at shutdown instead put it in the middle of a container tearing itself down and reported
     * ~69 ns where a quiet machine reports ~15 — a calibration is only useful if it is taken when
     * nothing else is happening.
     */
    val clockOverheadNanos: Long = measureClockOverheadNanos(clock)

    fun all(): List<LatencyHistogram> = listOf(inbound, outbound)

    fun summary(): String = buildString {
        append(" (clock read costs ~").append(clockOverheadNanos)
            .append(" ns, which is inside every figure below)")
        for (h in all()) {
            if (h.count == 0L) continue
            append("\n  ").append(h.name.removePrefix("gateway.").padEnd(NAME_WIDTH))
            append(' ').append(h.summary())
        }
    }

    private companion object {
        const val NAME_WIDTH = 10
    }
}
