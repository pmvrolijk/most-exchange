package nl.lamia.most.exchange.reference

import nl.lamia.most.exchange.sbe.DirectoryBeginEncoder
import nl.lamia.most.exchange.sbe.DirectoryEndEncoder
import nl.lamia.most.exchange.sbe.MessageHeaderEncoder
import nl.lamia.most.exchange.sbe.SecurityEntryEncoder
import nl.lamia.most.exchange.sbe.ShardEntryEncoder
import org.agrona.DirectBuffer
import org.agrona.MutableDirectBuffer
import org.agrona.concurrent.UnsafeBuffer

/** Where a directory broadcast goes. Separated so encoding is testable without Aeron. */
fun interface DirectorySink {
    fun send(buffer: DirectBuffer, offset: Int, length: Int)
}

/**
 * Encodes a [Universe] as a directory broadcast: `DirectoryBegin`, one `ShardEntry` per shard,
 * one `SecurityEntry` per security, then `DirectoryEnd`.
 *
 * Sent whole every cycle rather than as deltas. The universe is small and changes rarely, so a
 * late-joining adapter simply waits for the next cycle instead of needing a replay protocol.
 */
class DirectoryEncoder(private val sink: DirectorySink) {

    private val buffer: MutableDirectBuffer = UnsafeBuffer(ByteArray(1024))
    private val header = MessageHeaderEncoder()
    private val begin = DirectoryBeginEncoder()
    private val shard = ShardEntryEncoder()
    private val security = SecurityEntryEncoder()
    private val end = DirectoryEndEncoder()

    fun broadcast(universe: Universe) {
        begin.wrapAndApplyHeader(buffer, 0, header)
            .universeVersion(universe.version)
            .shardCount(universe.shards.size)
            .securityCount(universe.entries.size)
        sink.send(buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + DirectoryBeginEncoder.BLOCK_LENGTH)

        for (route in universe.shards) {
            shard.wrapAndApplyHeader(buffer, 0, header)
                .shardId(route.shardId)
                .orderEntryStreamId(route.orderEntryStreamId)
                .executionReportStreamId(route.executionReportStreamId)
                .orderEntryChannel(route.orderEntryChannel)
                .executionReportChannel(route.executionReportChannel)
            sink.send(buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + ShardEntryEncoder.BLOCK_LENGTH)
        }

        for (entry in universe.entries) {
            val spec = entry.spec
            security.wrapAndApplyHeader(buffer, 0, header)
                .priceFloor(spec.priceFloor)
                .tickSize(spec.tickSize)
                .securityId(spec.securityId)
                .shardId(entry.shardId)
                .levelCount(spec.levelCount)
                .symbol(spec.symbol)
                .isin(spec.isin)
                .currency(spec.currency)
                .name(spec.name)
            sink.send(buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + SecurityEntryEncoder.BLOCK_LENGTH)
        }

        end.wrapAndApplyHeader(buffer, 0, header).universeVersion(universe.version)
        sink.send(buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + DirectoryEndEncoder.BLOCK_LENGTH)
    }
}
