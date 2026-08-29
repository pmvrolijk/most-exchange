package com.engine.reference

import java.io.File
import java.util.Properties

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

/** One security, and the shard that serves it. */
data class UniverseEntry(val spec: SecuritySpec, val shardId: Int)

/**
 * The whole tradable universe: every security, and which shard serves it.
 *
 * Assembled from the shard security files the shards themselves boot from, so the directory and
 * the engines cannot describe different geometry — there is one definition, read twice.
 *
 * The invariant that matters is **exactly one shard per security**. Books are independent and
 * there is no cross-instrument matching, so a security served by two shards would give a client
 * two disjoint books under one identifier, with no error anywhere to say so.
 */
data class Universe(
    val shards: List<ShardRoute>,
    val entries: List<UniverseEntry>,
) {
    init {
        require(shards.isNotEmpty()) { "the universe defines no shards" }
        require(entries.isNotEmpty()) { "the universe defines no securities" }

        val shardIds = shards.map { it.shardId }
        requireUnique(shardIds, "shardId")

        val unknown = entries.map { it.shardId }.filter { it !in shardIds }.distinct()
        require(unknown.isEmpty()) { "securities reference undeclared shards: $unknown" }

        val bySecurityId = entries.groupBy { it.spec.securityId }.filterValues { it.size > 1 }
        require(bySecurityId.isEmpty()) {
            val detail = bySecurityId.entries.joinToString(", ") { (id, e) ->
                "$id on shards ${e.map { it.shardId }}"
            }
            "a security must be served by exactly one shard: $detail"
        }
        requireUnique(entries.map { it.spec.symbol }, "symbol")
        requireUnique(entries.map { it.spec.isin }, "ISIN")
    }

    /**
     * Changes if and only if the universe's content changes, so an adapter can tell a repeat
     * broadcast from a genuine update without diffing.
     */
    val version: Long = run {
        val canonical = buildString {
            shards.sortedBy { it.shardId }.forEach {
                append(it.shardId).append('|').append(it.orderEntryChannel)
                    .append('|').append(it.orderEntryStreamId)
                    .append('|').append(it.executionReportChannel)
                    .append('|').append(it.executionReportStreamId).append(';')
            }
            entries.sortedBy { it.spec.securityId }.forEach {
                append(it.shardId).append('|').append(it.spec.canonical()).append(';')
            }
        }
        var hash = 1125899906842597L
        for (c in canonical) hash = hash * 31 + c.code
        hash and Long.MAX_VALUE
    }

    fun shardFor(securityId: Int): Int? =
        entries.firstOrNull { it.spec.securityId == securityId }?.shardId

    fun bySymbol(symbol: String): UniverseEntry? = entries.firstOrNull { it.spec.symbol == symbol }

    fun routeFor(shardId: Int): ShardRoute? = shards.firstOrNull { it.shardId == shardId }

    companion object {
        private fun requireUnique(values: List<Any>, what: String) {
            val duplicates = values.groupBy { it }.filterValues { it.size > 1 }.keys
            require(duplicates.isEmpty()) { "duplicate $what in universe: $duplicates" }
        }

        /**
         * Reads a shard registry:
         *
         * ```
         * discovery.shards=1,2
         * discovery.shard.1.securitiesFile=/etc/most-exchange/shard-1-securities.properties
         * discovery.shard.1.ingressChannel=aeron:udp?endpoint=shard1:9010
         * discovery.shard.1.egressChannel=aeron:udp?endpoint=shard1:9020
         * ```
         *
         * The security files are the shards' own, so the directory is derived from what the
         * shards actually run rather than maintained beside it.
         */
        fun from(properties: Properties, resolve: (String) -> ShardSpec = ShardSpec::load): Universe {
            val ids = (properties.getProperty("discovery.shards")
                ?: error("missing required configuration key: discovery.shards"))
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }
                .map { it.toIntOrNull() ?: error("discovery.shards contains a non-numeric id: $it") }
            require(ids.isNotEmpty()) { "discovery.shards is empty" }

            val shards = mutableListOf<ShardRoute>()
            val entries = mutableListOf<UniverseEntry>()

            for (id in ids) {
                val path = properties.getProperty("discovery.shard.$id.securitiesFile")
                    ?: error("missing required configuration key: discovery.shard.$id.securitiesFile")
                val spec = resolve(path)
                require(spec.shardId == id) {
                    "shard $id points at a security file declaring shard.id=${spec.shardId}"
                }
                shards += ShardRoute(
                    shardId = id,
                    orderEntryChannel = required(properties, "discovery.shard.$id.orderEntryChannel"),
                    orderEntryStreamId =
                        required(properties, "discovery.shard.$id.orderEntryStreamId").toIntOrNull()
                            ?: error("discovery.shard.$id.orderEntryStreamId must be a number"),
                    executionReportChannel =
                        required(properties, "discovery.shard.$id.executionReportChannel"),
                    executionReportStreamId =
                        required(properties, "discovery.shard.$id.executionReportStreamId").toIntOrNull()
                            ?: error("discovery.shard.$id.executionReportStreamId must be a number"),
                )
                spec.securities.forEach { entries += UniverseEntry(it, id) }
            }
            return Universe(shards, entries)
        }

        private fun required(properties: java.util.Properties, key: String): String =
            properties.getProperty(key)?.trim()
                ?: error("missing required configuration key: $key")

        fun load(path: String): Universe {
            val file = File(path)
            require(file.isFile) { "discovery registry file not found: $path" }
            val properties = Properties()
            file.inputStream().use(properties::load)
            return from(properties)
        }
    }
}
