package com.engine.reference

import java.io.File
import java.security.MessageDigest
import java.util.Properties

/**
 * One gateway's identity: what it proves at connect, and which participants it speaks for.
 *
 * [secretSha256] is the SHA-256 of the shared secret, hex-encoded. The secret itself is never in
 * this file — the file is published to every node and to the gateway hosts, and a value that has
 * to travel that far should not be the value that grants access. This is a shared secret, not a
 * password store: it defends against a misconfigured gateway claiming another's participants, not
 * against someone who can already read the node's filesystem.
 */
data class GatewayIdentity(
    val gatewayId: String,
    val secretSha256: String,
    val participants: List<Long>,
) {
    init {
        require(gatewayId.isNotBlank()) { "a gateway has no id" }
        require(gatewayId.length <= MAX_GATEWAY_ID) {
            "gateway id exceeds $MAX_GATEWAY_ID characters: $gatewayId"
        }
        require(gatewayId.all { it in ID_ALPHABET }) {
            "gateway id may hold only letters, digits, '.', '_' and '-': $gatewayId"
        }
        require(secretSha256.length == SHA256_HEX_LENGTH && secretSha256.all { it in HEX_ALPHABET }) {
            "gateway $gatewayId: secret must be the SHA-256 of the shared secret as " +
                "$SHA256_HEX_LENGTH lowercase hex characters"
        }
        require(participants.isNotEmpty()) { "gateway $gatewayId speaks for no participants" }
        require(participants.all { it > 0L }) {
            "gateway $gatewayId has a non-positive participantId: $participants"
        }
        val duplicates = participants.groupBy { it }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) {
            "gateway $gatewayId lists a participant twice: $duplicates"
        }
    }

    /** The participant ids, sorted, as the engine indexes them. */
    fun participantIds(): LongArray = participants.sorted().toLongArray()

    internal fun canonical(): String =
        "$gatewayId:$secretSha256:${participants.sorted().joinToString("/")}"

    companion object {
        const val MAX_GATEWAY_ID = 64
        private const val SHA256_HEX_LENGTH = 64
        private val ID_ALPHABET = ('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('.', '_', '-')
        private val HEX_ALPHABET = ('0'..'9') + ('a'..'f')
    }
}

/**
 * Which gateway speaks for which participant, on one shard.
 *
 * This is what turns the engine's `participantId → clusterSessionId` map from something *learned*
 * into something *declared*. A cluster session belongs to a gateway, not to an end participant
 * (Design.md §1), so the maker side of a fill needs a route back that its own inbound message
 * cannot supply. Learning that route from traffic means a maker who has said nothing since the
 * gateway last connected cannot be reached at all, and its fills are counted undeliverable and
 * dropped — the gateway then cannot journal a fill it was never told about, and the `cumQty` it
 * reports for that order silently stops advancing.
 *
 * The file is read by two processes for two different reasons:
 *
 *  - the **consensus module** authenticates a connecting gateway against [GatewayIdentity.secretSha256]
 *    and stamps the gateway id on the session as its encoded principal, which travels through the
 *    replicated log and is therefore identical on every node;
 *  - the **engine** maps that principal back to a participant list at session open, and binds
 *    every one of them to the session.
 *
 * Every node must hold an identical copy, for exactly the reason every node must hold an identical
 * security list: two nodes that disagreed would route the same report to different places. Hence
 * [fingerprint], which each process prints at startup.
 *
 * A participant belongs to **at most one gateway**. Two gateways claiming one participant is not a
 * merge, it is an ambiguity — the last session opened would win, silently and by timing.
 */
data class ParticipantRegistry(
    val shardId: Int,
    val gateways: List<GatewayIdentity>,
) {
    init {
        require(shardId >= 0) { "shardId must be non-negative: $shardId" }
        require(gateways.isNotEmpty()) { "shard $shardId registers no gateways" }
        val duplicateIds = gateways.groupBy { it.gatewayId }.filterValues { it.size > 1 }.keys
        require(duplicateIds.isEmpty()) { "duplicate gateway id in shard $shardId: $duplicateIds" }
        val claimed = gateways.flatMap { g -> g.participants.map { it to g.gatewayId } }
        val contested = claimed.groupBy({ it.first }, { it.second }).filterValues { it.size > 1 }
        require(contested.isEmpty()) {
            "a participant is claimed by more than one gateway: " +
                contested.entries.joinToString(", ") { "${it.key} by ${it.value.sorted()}" }
        }
    }

    private val byGatewayId: Map<String, GatewayIdentity> = gateways.associateBy { it.gatewayId }

    /**
     * Precomputed so a session open costs no allocation beyond decoding the principal. Session
     * open is not the hot path, but it is on the same thread as one.
     */
    private val participantsByGatewayId: Map<String, LongArray> =
        gateways.associate { it.gatewayId to it.participantIds() }

    fun gateway(gatewayId: String): GatewayIdentity? = byGatewayId[gatewayId]

    /** The participants [gatewayId] speaks for, or null if the registry has never heard of it. */
    fun participantsOf(gatewayId: String): LongArray? = participantsByGatewayId[gatewayId]

    /**
     * True when [token] is the shared secret [gatewayId] was registered with.
     *
     * [MessageDigest.isEqual] rather than `==`: it is the constant-time comparison, and comparing
     * a secret's digest with early exit leaks how much of it was right.
     */
    fun verify(gatewayId: String, token: String): Boolean {
        val identity = byGatewayId[gatewayId] ?: return false
        return MessageDigest.isEqual(
            sha256Hex(token).toByteArray(Charsets.US_ASCII),
            identity.secretSha256.toByteArray(Charsets.US_ASCII),
        )
    }

    /**
     * A digest of everything every node must agree on. Deliberately **not** folded into
     * `ShardSpec.fingerprint()`: that hash is recorded by the control plane and published in every
     * release, so changing what it covers would invalidate every value already written down.
     * Rotating one gateway's secret must not look like a change of shard geometry.
     */
    fun fingerprint(): String = java.lang.Long.toHexString(fingerprintValue())

    fun fingerprintValue(): Long {
        val canonical = gateways.sortedBy { it.gatewayId }.joinToString(",") { it.canonical() }
        var hash = 1125899906842597L
        for (c in "$shardId|$canonical") hash = hash * 31 + c.code
        return hash
    }

    /**
     * The inverse of [from], beside it so the two cannot drift. See `ShardSpec.render` for why
     * `Properties.store` is not used.
     */
    fun render(): String = buildString {
        appendLine("# Generated by the control plane. Do not edit; edit the database and publish.")
        appendLine("# fingerprint=${fingerprint()}")
        appendLine("#")
        appendLine("# Which gateway speaks for which participant. Read by the consensus module, to")
        appendLine("# authenticate a connecting gateway and stamp its id on the session as the")
        appendLine("# encoded principal, and by the engine, to bind those participants to that")
        appendLine("# session at session open (Design.md §1).")
        appendLine("#")
        appendLine("# The secret is stored as its SHA-256, hex. The secret itself is configured on")
        appendLine("# the gateway and appears nowhere in this file.")
        appendLine()
        appendLine("shard.id=$shardId")
        val ordered = gateways.sortedBy { it.gatewayId }
        appendLine("registry.gateways=${ordered.joinToString(",") { it.gatewayId }}")
        for (gateway in ordered) {
            appendLine()
            appendLine("gateway.${gateway.gatewayId}.secret=${gateway.secretSha256}")
            appendLine(
                "gateway.${gateway.gatewayId}.participants=" +
                    gateway.participants.sorted().joinToString(",")
            )
        }
    }

    companion object {
        /** The separator between a gateway's id and its secret in the encoded credentials. */
        private const val CREDENTIAL_SEPARATOR = ':'

        /**
         * The bytes a gateway presents at connect, and the only implementation of that encoding.
         *
         * A second one that drifted by a separator would fail every authentication with nothing to
         * say why, so the client and the authenticator share this pair the same way the CLI and
         * the control plane share `OperatorCommands`.
         */
        fun encodeCredentials(gatewayId: String, token: String): ByteArray =
            "$gatewayId$CREDENTIAL_SEPARATOR$token".toByteArray(Charsets.US_ASCII)

        /** The inverse of [encodeCredentials], or null if the bytes are not that shape. */
        fun decodeCredentials(encoded: ByteArray): Pair<String, String>? {
            if (encoded.isEmpty()) return null
            val text = String(encoded, Charsets.US_ASCII)
            val split = text.indexOf(CREDENTIAL_SEPARATOR)
            if (split <= 0 || split == text.length - 1) return null
            return text.substring(0, split) to text.substring(split + 1)
        }

        /** The digest a registry stores for a secret. Lowercase hex, as [render] writes it. */
        fun sha256Hex(token: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(token.toByteArray(Charsets.UTF_8))
            return buildString(digest.size * 2) {
                for (byte in digest) {
                    val value = byte.toInt() and 0xff
                    append(HEX[value ushr 4])
                    append(HEX[value and 0x0f])
                }
            }
        }

        private const val HEX = "0123456789abcdef"

        /**
         * Reads a participant registry file:
         *
         * ```
         * shard.id=1
         * registry.gateways=gw-a,gw-b
         *
         * gateway.gw-a.secret=<sha-256 of the shared secret, hex>
         * gateway.gw-a.participants=100,101
         *
         * gateway.gw-b.secret=<sha-256 of the shared secret, hex>
         * gateway.gw-b.participants=200
         * ```
         *
         * Nothing has a default. A gateway with no participants is a typo, and a missing secret
         * would otherwise become "authenticates with anything".
         */
        fun from(properties: Properties): ParticipantRegistry {
            val shardId = required(properties, "shard.id").toIntOrNull()
                ?: error("shard.id must be a number")

            val ids = required(properties, "registry.gateways")
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }

            val gateways = ids.map { id ->
                GatewayIdentity(
                    gatewayId = id,
                    secretSha256 = required(properties, "gateway.$id.secret").lowercase(),
                    participants = required(properties, "gateway.$id.participants")
                        .split(',').map { it.trim() }.filter { it.isNotEmpty() }
                        .map {
                            it.toLongOrNull()
                                ?: error("gateway.$id.participants holds a non-numeric id: $it")
                        },
                )
            }
            return ParticipantRegistry(shardId, gateways)
        }

        fun load(path: String): ParticipantRegistry {
            val file = File(path)
            require(file.isFile) { "participant registry file not found: $path" }
            val properties = Properties()
            file.inputStream().use(properties::load)
            return from(properties)
        }

        private fun required(properties: Properties, key: String): String =
            properties.getProperty(key)?.trim()
                ?: error("missing required configuration key: $key")
    }
}
