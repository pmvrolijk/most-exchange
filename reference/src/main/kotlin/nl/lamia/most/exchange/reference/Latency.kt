package nl.lamia.most.exchange.reference

import org.HdrHistogram.Histogram
import java.io.File
import java.io.PrintStream
import java.util.Locale

/**
 * A latency histogram for a single measurement point, recorded on a hot path.
 *
 * Shared by the engine, the gateway and `most load` so that a stage timing and the client round
 * trip it is a part of are printed in the same units and the same shape — the whole purpose of the
 * instrumentation is to subtract one from the other, which is only sound if they are rendered by
 * one implementation.
 *
 * Two properties matter more than the percentiles it produces.
 *
 * **It never allocates on [record].** HdrHistogram writes into a `long[]` sized at construction;
 * auto-resize is off, which is what would otherwise allocate. `engine`'s `AllocationTest` asserts
 * this by driving the service with metrics enabled.
 *
 * **It never throws.** HdrHistogram throws when a value exceeds the tracked range, and in the
 * engine a throw is not an error — it is every cluster node dying at the same log position
 * (Design.md §1). A sample past the ceiling is therefore clamped and counted in [clamped] rather
 * than raised, so an outlier costs a distorted maximum instead of the exchange. That is also why
 * the ceiling is generous: [DEFAULT_MAX_NANOS] is a full second, far past anything this code
 * should produce, so a non-zero [clamped] is a signal in itself.
 */
class LatencyHistogram(
    val name: String,
    private val maxTrackedNanos: Long = DEFAULT_MAX_NANOS,
) {
    private val histogram = Histogram(1L, maxTrackedNanos, SIGNIFICANT_DIGITS)

    /** Samples that exceeded [maxTrackedNanos] and were recorded at the ceiling instead. */
    var clamped = 0L
        private set

    val count: Long get() = histogram.totalCount

    fun record(nanos: Long) {
        when {
            // A backwards or zero delta is possible on a coarse clock and says nothing; recording
            // it as 0 keeps the count honest without inventing a duration.
            nanos <= 0L -> histogram.recordValue(0L)
            nanos > maxTrackedNanos -> {
                clamped++
                histogram.recordValue(maxTrackedNanos)
            }
            else -> histogram.recordValue(nanos)
        }
    }

    fun reset() {
        histogram.reset()
        clamped = 0L
    }

    /** One line, microseconds, matching what `most load` prints for the round trip. */
    fun summary(): String =
        if (histogram.totalCount == 0L) {
            "no samples"
        } else {
            val clampNote = if (clamped == 0L) "" else "  clamped=%,d".format(Locale.ROOT, clamped)
            "n=%,d  p50=%s p90=%s p99=%s p99.9=%s max=%s (µs)%s".format(
                Locale.ROOT,
                histogram.totalCount,
                micros(histogram.getValueAtPercentile(P50)),
                micros(histogram.getValueAtPercentile(P90)),
                micros(histogram.getValueAtPercentile(P99)),
                micros(histogram.getValueAtPercentile(P99_9)),
                micros(histogram.maxValue),
                clampNote,
            )
        }

    /** Appends this histogram's percentile distribution, in the format `most load` writes. */
    fun writeDistribution(out: PrintStream) {
        out.println("# $name -- values in microseconds")
        histogram.outputPercentileDistribution(out, NANOS_PER_MICRO)
        out.println()
    }

    companion object {
        /** A full second. Nothing here should approach it, so a clamp is itself a finding. */
        const val DEFAULT_MAX_NANOS = 1_000_000_000L
        const val NANOS_PER_MICRO = 1_000.0

        private const val SIGNIFICANT_DIGITS = 3
        private const val P50 = 50.0
        private const val P90 = 90.0
        private const val P99 = 99.0
        private const val P99_9 = 99.9

        /**
         * Two decimals, which is 10 ns — the resolution of the clock that produced the sample
         * ([measureClockOverheadNanos]) and no finer, so the last digit means something.
         *
         * `most load` prints one decimal for the same unit and that is right for it: it measures a
         * round trip in tens of microseconds, where a second decimal is noise. These stages run in
         * hundreds of nanoseconds, where one decimal reported the engine's match step as "0.0" and
         * hid the entire range it works in.
         */
        fun micros(nanos: Long): String = "%.2f".format(Locale.ROOT, nanos / NANOS_PER_MICRO)

        /**
         * Writes every histogram to one file, so a run is one artifact that can be diffed against
         * a later one. Returns the absolute path written, or null when there was nothing to write —
         * an empty file would look like a run that measured zero rather than one that never ran.
         */
        fun writeAll(path: String, header: String, histograms: List<LatencyHistogram>): String? {
            val populated = histograms.filter { it.count > 0L }
            if (populated.isEmpty()) return null
            val file = File(path)
            PrintStream(file.outputStream().buffered()).use { out ->
                out.println("# $header")
                for (h in populated) h.writeDistribution(out)
            }
            return file.absolutePath
        }
    }
}

/**
 * What one clock read costs on this machine, in nanoseconds.
 *
 * Every latency figure this package produces contains roughly one of these — an interval is closed
 * by a read whose own cost falls inside it — and at the grain the engine works at that is not a
 * rounding error. A 400 ns median next to a 25 ns clock read means the instrument is 6% of the
 * number it reports, and a reader deserves to be told rather than left to assume the probe is free.
 * It is also the reason Design.md §2's finer rows are not timed individually: §2 estimates a 2 ns
 * book lookup, and this is what it would cost to look at it.
 *
 * Measured as total elapsed over many reads rather than as a median of back-to-back deltas.
 * `nanoTime` granularity is coarser than its cost on some platforms — about 41 ns on Apple silicon,
 * where consecutive reads frequently return the *same* value — so per-read deltas are a mixture of
 * zeros and quantised jumps whose median says more about the timebase than about the call. The
 * average over a long run is stable, and it is the number that actually sits inside a measurement.
 *
 * Warmed first, because this runs once at shutdown and an un-JIT'd loop reports several times the
 * steady-state cost — the first version of this reported 167 ns for a call that costs about 20.
 */
fun measureClockOverheadNanos(
    clock: org.agrona.concurrent.NanoClock,
    samples: Int = 200_000,
): Long {
    var sink = 0L
    repeat(samples) { sink += clock.nanoTime() }
    val start = clock.nanoTime()
    repeat(samples) { sink += clock.nanoTime() }
    val elapsed = clock.nanoTime() - start
    // Makes the accumulator observable so the loop cannot be optimised away. Never true.
    if (sink == 0L) return -1L
    return elapsed / samples
}
