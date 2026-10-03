package nl.lamia.most.exchange.client

/**
 * How a client reaches one shard: the **gateway's** client-facing endpoints.
 *
 * Not the cluster's ingress and egress. Those belong to the gateway process, which is the only
 * thing that holds a cluster session; an adapter that connected to them would bypass the
 * validation and the cumQty reconstruction the gateway exists to perform.
 */
data class ShardRoute(
    val shardId: Int,
    val orderEntryChannel: String,
    val orderEntryStreamId: Int,
    val executionReportChannel: String,
    val executionReportStreamId: Int,
) {
    init {
        require(shardId >= 0) { "shardId must be non-negative: $shardId" }
        require(orderEntryChannel.isNotBlank()) { "shard $shardId has no order entry channel" }
        require(executionReportChannel.isNotBlank()) {
            "shard $shardId has no execution report channel"
        }
        require(orderEntryChannel.length <= MAX_CHANNEL_LENGTH) {
            "shard $shardId order entry channel exceeds $MAX_CHANNEL_LENGTH characters"
        }
        require(executionReportChannel.length <= MAX_CHANNEL_LENGTH) {
            "shard $shardId execution report channel exceeds $MAX_CHANNEL_LENGTH characters"
        }
    }

    companion object {
        const val MAX_CHANNEL_LENGTH = 128
    }
}
