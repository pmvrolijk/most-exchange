package nl.lamia.most.exchange.tools

import nl.lamia.most.exchange.reference.DutyCycles
import io.aeron.CncFileDescriptor
import io.aeron.CommonContext
import org.agrona.IoUtil
import org.agrona.concurrent.status.CountersReader
import java.io.File
import java.nio.MappedByteBuffer
import java.util.Locale

/**
 * Reads the media driver's counters straight out of the CnC file.
 *
 * **No Aeron client.** Everything here is a read of a memory-mapped file the driver, the archive and
 * the consensus module are already writing, so running this against a shard under load adds a
 * subscription to nothing and cannot perturb what it is measuring. That is the whole point of it:
 * the counters that matter are the ones that only misbehave at the rate where adding a client would
 * change the answer.
 *
 * The driver, the archive and every cluster component publish into the same counter set, labelled.
 * What a saturating shard looks like here is a *position* counter that stops advancing while the one
 * feeding it keeps going — a publisher pinned at its limit, an archive recording falling behind the
 * log, a consensus module whose commit position trails its append position. `--interval-ms` is
 * therefore the useful mode: a value is rarely interesting, a rate of change usually is.
 */
internal fun runCounters(args: Args) {
    val aeronDir = args.optional("aeron-dir")
    val match = args.optional("match")?.let(::Regex)
    val intervalMs = args.long("interval-ms", 0L)
    val samples = args.int("samples", if (intervalMs > 0L) 2 else 1)
    val showAll = args.has("all")

    // Built by hand rather than via CommonContext.conclude(), which would create the directory it
    // was asked to look in -- wrong for a tool whose whole contract is that it only reads.
    val cncFile = File(aeronDir ?: CommonContext.getAeronDirectoryName(), CncFileDescriptor.CNC_FILE)
    if (!cncFile.exists()) {
        System.err.println("most: no CnC file at $cncFile -- is a media driver running there?")
        return
    }

    var mapped: MappedByteBuffer? = null
    try {
        mapped = IoUtil.mapExistingFile(cncFile, "cnc")
        val metaData = CncFileDescriptor.createMetaDataBuffer(mapped)
        CncFileDescriptor.checkVersion(metaData.getInt(CncFileDescriptor.cncVersionOffset(0)))
        val counters = CountersReader(
            CncFileDescriptor.createCountersMetaDataBuffer(mapped, metaData),
            CncFileDescriptor.createCountersValuesBuffer(mapped, metaData),
        )
        println("counters: $cncFile  pid=${metaData.getLong(CncFileDescriptor.pidOffset(0))}")
        if (intervalMs <= 0L) {
            printSnapshot(counters, match, showAll)
        } else {
            printDeltas(counters, match, showAll, intervalMs, samples)
        }
    } finally {
        mapped?.let(IoUtil::unmap)
    }
}

private fun labelledCounters(
    counters: CountersReader,
    match: Regex?,
): List<Pair<Int, String>> {
    val found = mutableListOf<Pair<Int, String>>()
    counters.forEach { id, label ->
        if (match == null || match.containsMatchIn(label)) found += id to label
    }
    return found
}

private fun printSnapshot(counters: CountersReader, match: Regex?, showAll: Boolean) {
    val rows = labelledCounters(counters, match)
        .filter { showAll || counters.getCounterValue(it.first) != 0L }
    println("  %5s %8s %20s  %s".format("id", "type", "value", "label"))
    for ((id, label) in rows) {
        println(
            "  %5d %8d %20d  %s".format(
                id, counters.getCounterTypeId(id), counters.getCounterValue(id), label,
            ),
        )
    }
    println()
    println("  ${rows.size} counters shown. --all includes the zeros; --match REGEX filters by label.")
}

/**
 * A duty counter's rate is busy nanoseconds per second of wall time, so dividing by 1e9 gives the
 * share of one core its thread spent working (Design.md §7, "Duty cycle").
 */
private fun dutyShare(typeId: Int, delta: Long, elapsedSeconds: Double): String =
    if (typeId != DutyCycles.COUNTER_TYPE_ID) ""
    else "   = %.1f%% of a core".format(Locale.ROOT, 100.0 * delta / (elapsedSeconds * NANOS_PER_SECOND))

private const val NANOS_PER_SECOND = 1e9

/**
 * Samples twice or more and reports the rate of change.
 *
 * Wall clock, deliberately: this is an observer process holding no replicated state, and the ban on
 * reading a clock (CLAUDE.md, Design.md §1) is about the engine's determinism, not about a tool.
 */
private fun printDeltas(
    counters: CountersReader,
    match: Regex?,
    showAll: Boolean,
    intervalMs: Long,
    samples: Int,
) {
    val tracked = labelledCounters(counters, match)
    var previous = LongArray(tracked.size) { counters.getCounterValue(tracked[it].first) }
    var previousAt = System.nanoTime()

    for (sample in 1..<samples) {
        Thread.sleep(intervalMs)
        val now = System.nanoTime()
        val elapsedSeconds = (now - previousAt) / 1e9
        val current = LongArray(tracked.size) { counters.getCounterValue(tracked[it].first) }

        val rows = tracked.indices
            .map { Triple(tracked[it], current[it], current[it] - previous[it]) }
            .filter { showAll || it.third != 0L }
            .sortedByDescending { kotlin.math.abs(it.third) }

        println()
        println("  sample $sample of ${samples - 1} over %.2fs".format(Locale.ROOT, elapsedSeconds))
        println("  %5s %20s %18s  %s".format("id", "value", "per second", "label"))
        for ((counter, value, delta) in rows) {
            println(
                "  %5d %20d %18s  %s%s".format(
                    counter.first, value, "%,.0f".format(Locale.ROOT, delta / elapsedSeconds), counter.second,
                    dutyShare(counters.getCounterTypeId(counter.first), delta, elapsedSeconds),
                ),
            )
        }
        if (rows.isEmpty()) println("  nothing moved. --all shows the still counters too.")

        previous = current
        previousAt = now
    }
    println()
    println("  A duty-ns counter's rate is busy nanoseconds per second: the share of one core its")
    println("  thread spent working, which CPU% cannot show for a thread that spins while idle.")
    println("  A position counter that stops advancing while its feeder keeps going is the")
    println("  saturating stage. Sorted by absolute change, so the busiest is at the top.")
}
