package nl.lamia.most.exchange.client

import nl.lamia.most.exchange.sbe.DirectoryBeginDecoder
import nl.lamia.most.exchange.sbe.DirectoryEndDecoder
import nl.lamia.most.exchange.sbe.MessageHeaderDecoder
import nl.lamia.most.exchange.sbe.SecurityEntryDecoder
import nl.lamia.most.exchange.sbe.ShardEntryDecoder
import org.agrona.DirectBuffer

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
    private var routesByShard: Map<Int, ShardRoute> = emptyMap()

    var version = 0L
        private set

    var incompleteBroadcasts = 0L
        private set

    val isReady: Boolean get() = bySecurityId.isNotEmpty()

    val securities: Collection<RoutedSecurity> get() = bySecurityId.values

    fun routeFor(securityId: Int): RoutedSecurity? = bySecurityId[securityId]

    fun routeForSymbol(symbol: String): RoutedSecurity? = bySymbol[symbol]

    /** Shard-wide commands -- a session transition, a purge -- address a shard, not a security. */
    fun shardRoute(shardId: Int): ShardRoute? = routesByShard[shardId]

    val shards: Collection<ShardRoute> get() = routesByShard.values

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
        routesByShard = HashMap(pendingShards)
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
