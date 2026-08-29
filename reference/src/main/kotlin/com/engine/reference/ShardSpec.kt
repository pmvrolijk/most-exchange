package com.engine.reference

import java.io.File
import java.util.Properties

/**
 * One shard's tradable securities. Loaded identically by the engine, the gateway and the market
 * data process, so a shard has exactly one security list rather than three that must be kept in
 * step by hand.
 */
data class ShardSpec(
    val shardId: Int,
    val securities: List<SecuritySpec>,
) {
    init {
        require(shardId >= 0) { "shardId must be non-negative: $shardId" }
        require(securities.isNotEmpty()) { "shard $shardId defines no securities" }
        require(securities.size <= MAX_SECURITIES_PER_SHARD) {
            "a shard hosts at most $MAX_SECURITIES_PER_SHARD securities, got ${securities.size}"
        }
        requireUnique(securities.map { it.securityId }, "securityId")
        requireUnique(securities.map { it.symbol }, "symbol")
        requireUnique(securities.map { it.isin }, "ISIN")
    }

    val securityIds: IntArray get() = IntArray(securities.size) { securities[it].securityId }

    fun securityById(securityId: Int): SecuritySpec? =
        securities.firstOrNull { it.securityId == securityId }

    fun maxLevelCount(): Int = securities.maxOf { it.levelCount }

    /**
     * A digest of everything that must match. Every process in the shard prints it at startup, and
     * so does every cluster node: geometry decides how a price maps to a ladder level, so a
     * mismatch would diverge the books rather than fail loudly.
     */
    fun fingerprint(): String {
        val canonical = securities.sortedBy { it.securityId }.joinToString(",") { it.canonical() }
        var hash = 1125899906842597L
        for (c in "$shardId|$canonical") hash = hash * 31 + c.code
        return java.lang.Long.toHexString(hash)
    }

    companion object {
        const val MAX_SECURITIES_PER_SHARD = 10

        private fun requireUnique(values: List<Any>, what: String) {
            val duplicates = values.groupBy { it }.filterValues { it.size > 1 }.keys
            require(duplicates.isEmpty()) { "duplicate $what in shard: $duplicates" }
        }

        /**
         * Reads a shard security file:
         *
         * ```
         * shard.id=1
         * shard.securities=1,2
         *
         * security.1.symbol=ACME
         * security.1.isin=US0378331005
         * security.1.name=Acme Corporation
         * security.1.currency=USD
         * security.1.priceFloor=0
         * security.1.tickSize=1000000
         * security.1.levelCount=65536
         * security.1.maxOrders=1000000
         * ```
         *
         * Nothing has a default. A wrong tick size misprices every order silently, and a missing
         * ISIN would be published to every downstream adapter, so both must be stated.
         */
        fun from(properties: Properties): ShardSpec {
            val shardId = required(properties, "shard.id").toIntOrNull()
                ?: error("shard.id must be a number")

            val ids = required(properties, "shard.securities")
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }
                .map { it.toIntOrNull() ?: error("shard.securities contains a non-numeric id: $it") }

            val securities = ids.map { id ->
                SecuritySpec(
                    securityId = id,
                    symbol = required(properties, "security.$id.symbol"),
                    isin = required(properties, "security.$id.isin"),
                    name = required(properties, "security.$id.name"),
                    currency = required(properties, "security.$id.currency"),
                    priceFloor = requiredLong(properties, "security.$id.priceFloor"),
                    tickSize = requiredLong(properties, "security.$id.tickSize"),
                    levelCount = requiredInt(properties, "security.$id.levelCount"),
                    maxOrders = requiredInt(properties, "security.$id.maxOrders"),
                )
            }
            return ShardSpec(shardId, securities)
        }

        fun load(path: String): ShardSpec {
            val file = File(path)
            require(file.isFile) { "shard security file not found: $path" }
            val properties = Properties()
            file.inputStream().use(properties::load)
            return from(properties)
        }

        private fun required(properties: Properties, key: String): String =
            properties.getProperty(key)?.trim()
                ?: error("missing required configuration key: $key")

        private fun requiredLong(properties: Properties, key: String): Long =
            required(properties, key).toLongOrNull() ?: error("$key must be a number")

        private fun requiredInt(properties: Properties, key: String): Int =
            required(properties, key).toIntOrNull() ?: error("$key must be a number")
    }
}
