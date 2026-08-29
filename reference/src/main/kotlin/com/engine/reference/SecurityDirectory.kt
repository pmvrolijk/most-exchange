package com.engine.reference

import com.engine.sbe.DirectoryBeginDecoder
import com.engine.sbe.DirectoryBeginEncoder
import com.engine.sbe.DirectoryEndDecoder
import com.engine.sbe.DirectoryEndEncoder
import com.engine.sbe.MessageHeaderDecoder
import com.engine.sbe.MessageHeaderEncoder
import com.engine.sbe.SecurityEntryDecoder
import com.engine.sbe.SecurityEntryEncoder
import com.engine.sbe.ShardEntryDecoder
import com.engine.sbe.ShardEntryEncoder
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

/** One security as an adapter sees it: what to send, and where to send it. */
data class RoutedSecurity(
    val securityId: Int,
    val shardId: Int,
    val symbol: String,
    val isin: String,
    val name: String,
    val currency: String,
    val priceFloor: Long,
    val tickSize: Long,
    val levelCount: Int,
    val orderEntryChannel: String,
    val orderEntryStreamId: Int,
    val executionReportChannel: String,
    val executionReportStreamId: Int,
)

/**
 * Consumes the directory broadcast and maintains a routing table. This is what an upstream
 * protocol adapter embeds: it answers "which shard serves this symbol, and how do I reach it".
 *
 * A directory is **staged and committed atomically**. Entries accumulate in a pending table and
 * only replace the live one when `DirectoryEnd` arrives carrying the version `DirectoryBegin`
 * announced. A broadcast truncated by a lost datagram therefore leaves the previous table intact
 * rather than routing against half a universe.
 */
class DirectoryClient {

    private val header = MessageHeaderDecoder()
    private val begin = DirectoryBeginDecoder()
    private val shard = ShardEntryDecoder()
    private val security = SecurityEntryDecoder()
    private val end = DirectoryEndDecoder()

    private var pendingVersion = 0L
    private var pendingShardCount = 0
    private var pendingSecurityCount = 0
    private val pendingShards = mutableMapOf<Int, ShardRoute>()
    private val pendingSecurities = mutableListOf<Pair<Int, SecurityEntryFields>>()
    private var inProgress = false

    private var bySymbol: Map<String, RoutedSecurity> = emptyMap()
    private var bySecurityId: Map<Int, RoutedSecurity> = emptyMap()

    var version = 0L
        private set

    var incompleteBroadcasts = 0L
        private set

    val isReady: Boolean get() = bySecurityId.isNotEmpty()

    val securities: Collection<RoutedSecurity> get() = bySecurityId.values

    fun routeFor(securityId: Int): RoutedSecurity? = bySecurityId[securityId]

    fun routeForSymbol(symbol: String): RoutedSecurity? = bySymbol[symbol]

    fun onDirectoryMessage(buffer: DirectBuffer, offset: Int, length: Int) {
        if (length < MessageHeaderDecoder.ENCODED_LENGTH) return
        header.wrap(buffer, offset)
        val body = offset + MessageHeaderDecoder.ENCODED_LENGTH
        val blockLength = header.blockLength()
        val schemaVersion = header.version()

        when (header.templateId()) {
            DirectoryBeginDecoder.TEMPLATE_ID -> {
                begin.wrap(buffer, body, blockLength, schemaVersion)
                if (inProgress) incompleteBroadcasts++
                pendingVersion = begin.universeVersion()
                pendingShardCount = begin.shardCount()
                pendingSecurityCount = begin.securityCount()
                pendingShards.clear()
                pendingSecurities.clear()
                inProgress = true
            }

            ShardEntryDecoder.TEMPLATE_ID -> {
                if (!inProgress) return
                shard.wrap(buffer, body, blockLength, schemaVersion)
                val id = shard.shardId()
                pendingShards[id] = ShardRoute(
                    id,
                    shard.orderEntryChannel(), shard.orderEntryStreamId(),
                    shard.executionReportChannel(), shard.executionReportStreamId(),
                )
            }

            SecurityEntryDecoder.TEMPLATE_ID -> {
                if (!inProgress) return
                security.wrap(buffer, body, blockLength, schemaVersion)
                pendingSecurities += security.shardId() to SecurityEntryFields(
                    securityId = security.securityId(),
                    symbol = security.symbol(),
                    isin = security.isin(),
                    currency = security.currency(),
                    name = security.name(),
                    priceFloor = security.priceFloor(),
                    tickSize = security.tickSize(),
                    levelCount = security.levelCount(),
                )
            }

            DirectoryEndDecoder.TEMPLATE_ID -> {
                if (!inProgress) return
                end.wrap(buffer, body, blockLength, schemaVersion)
                commitIfComplete(end.universeVersion())
            }

            else -> return
        }
    }

    /** Only a broadcast that arrived whole replaces the live table. */
    private fun commitIfComplete(endVersion: Long) {
        inProgress = false
        val complete = endVersion == pendingVersion &&
            pendingShards.size == pendingShardCount &&
            pendingSecurities.size == pendingSecurityCount
        if (!complete) {
            incompleteBroadcasts++
            return
        }

        val byId = HashMap<Int, RoutedSecurity>(pendingSecurities.size)
        val bySym = HashMap<String, RoutedSecurity>(pendingSecurities.size)
        for ((shardId, fields) in pendingSecurities) {
            val route = pendingShards[shardId] ?: continue
            val routed = RoutedSecurity(
                securityId = fields.securityId,
                shardId = shardId,
                symbol = fields.symbol,
                isin = fields.isin,
                name = fields.name,
                currency = fields.currency,
                priceFloor = fields.priceFloor,
                tickSize = fields.tickSize,
                levelCount = fields.levelCount,
                orderEntryChannel = route.orderEntryChannel,
                orderEntryStreamId = route.orderEntryStreamId,
                executionReportChannel = route.executionReportChannel,
                executionReportStreamId = route.executionReportStreamId,
            )
            byId[routed.securityId] = routed
            bySym[routed.symbol] = routed
        }
        if (byId.size != pendingSecurityCount) {
            incompleteBroadcasts++
            return
        }
        bySecurityId = byId
        bySymbol = bySym
        version = pendingVersion
    }

    private data class SecurityEntryFields(
        val securityId: Int,
        val symbol: String,
        val isin: String,
        val currency: String,
        val name: String,
        val priceFloor: Long,
        val tickSize: Long,
        val levelCount: Int,
    )
}
