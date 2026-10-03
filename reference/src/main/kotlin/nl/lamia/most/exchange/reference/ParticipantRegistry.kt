package nl.lamia.most.exchange.reference

import java.io.File
import java.security.MessageDigest
import java.util.Properties

/**
 * One gateway's identity: what it proves at connect, and what it may do (Design.md §1,
 * "Enforcement, at the gateway"):
 *
 *  - [participants] may place orders and cancel them;
 *  - [cancelOnly] may cancel and not place -- a revoked participant withdrawing what is resting;
 *  - [operator] may send operator commands. An operator-only identity (no participants at all) is
 *    how the control plane and the CLI are named, and the only kind allowed to list nobody.
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
    val cancelOnly: List<Long> = emptyList(),
    val operator: Boolean = false,
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
        require(operator || participants.isNotEmpty() || cancelOnly.isNotEmpty()) {
            "gateway $gatewayId speaks for no participants and is not an operator"
        }
        val listed = participants + cancelOnly
        require(listed.all { it > 0L }) {
            "gateway $gatewayId has a non-positive participantId: $listed"
        }
        val duplicates = listed.groupBy { it }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) {
            "gateway $gatewayId lists a participant twice (in participants and cancelOnly, or " +
                "twice in one): $duplicates"
        }
    }

    // Sorted once, so the checks a gateway makes on every message are a binary search and allocate
    // nothing. Not constructor properties, so they stay out of equals and the data-class copy.
    private val placeIds: LongArray = participants.sorted().toLongArray()
    private val listedIds: LongArray = listed().sorted().toLongArray()

    /** Everyone this gateway speaks for, cancel-only included: all of them still fill. */
    fun listed(): List<Long> = participants + cancelOnly

    /** The participant ids the engine binds to this gateway's session, sorted. */
    fun participantIds(): LongArray = listedIds.copyOf()

    /** True when [participantId] may place orders through this gateway. Allocates nothing. */
    fun mayPlace(participantId: Long): Boolean = placeIds.binarySearch(participantId) >= 0

    /** True when [participantId] may cancel through this gateway. Allocates nothing. */
    fun mayCancel(participantId: Long): Boolean = listedIds.binarySearch(participantId) >= 0

    /**
     * The fields every node must agree on. The new ones are appended only when they are not their
     * defaults, so a registry that uses none of them hashes exactly as it did before they existed --
     * releases already published record that value.
     */
    internal fun canonical(): String = buildString {
        append("$gatewayId:$secretSha256:${participants.sorted().joinToString("/")}")
        if (cancelOnly.isNotEmpty()) append(":cancelOnly=${cancelOnly.sorted().joinToString("/")}")
        if (operator) append(":operator")
    }

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
 * A participant may be listed on **several gateways** -- a primary and a failover -- and then
 * [primaries] must name the one it is bound to at session open, which must list it. Without that,
 * two claims on one participant would be an ambiguity resolved by whichever session opened last.
 * A primary for a participant listed only once is refused as a typo.
 *
 * The **gateway** enforces what each entry grants ([mayPlace], [mayCancel], [mayOperate]); the
 * engine never rejects on this file, because each node reads it on its own schedule
 * (Design.md §1, "Enforcement, at the gateway").
 */
data class ParticipantRegistry(
    val shardId: Int,
    val gateways: List<GatewayIdentity>,
    /** participantId → gatewayId, only for participants listed on more than one gateway. */
    val primaries: Map<Long, String> = emptyMap(),
) {
    init {
        require(shardId >= 0) { "shardId must be non-negative: $shardId" }
        require(gateways.isNotEmpty()) { "shard $shardId registers no gateways" }
        val duplicateIds = gateways.groupBy { it.gatewayId }.filterValues { it.size > 1 }.keys
        require(duplicateIds.isEmpty()) { "duplicate gateway id in shard $shardId: $duplicateIds" }
        val listings = gateways
            .flatMap { g -> g.listed().map { it to g.gatewayId } }
            .groupBy({ it.first }, { it.second })
        val undecided = listings.filter { (participant, by) -> by.size > 1 && participant !in primaries }
        require(undecided.isEmpty()) {
            "a participant listed on more than one gateway needs a primary " +
                "(participant.<id>.primary): " +
                undecided.entries.joinToString(", ") { "${it.key} by ${it.value.sorted()}" }
        }
        for ((participant, primary) in primaries) {
            val by = listings[participant].orEmpty()
            require(by.size > 1) {
                "participant $participant has a primary but is listed on ${by.size} gateway(s); " +
                    "a primary is only for a participant on several"
            }
            require(primary in by) {
                "participant $participant's primary is $primary, which does not list it " +
                    "(listed by ${by.sorted()})"
            }
        }
    }

    private val byGatewayId: Map<String, GatewayIdentity> = gateways.associateBy { it.gatewayId }

    private val soleListing: Map<Long, String> = gateways
        .flatMap { g -> g.listed().map { it to g.gatewayId } }
        .groupBy({ it.first }, { it.second })
        .filterValues { it.size == 1 }
        .mapValues { it.value.single() }

    /**
     * Precomputed so a session open costs no allocation beyond decoding the principal. Session
     * open is not the hot path, but it is on the same thread as one.
     */
    private val participantsByGatewayId: Map<String, LongArray> =
        gateways.associate { it.gatewayId to it.participantIds() }

    fun gateway(gatewayId: String): GatewayIdentity? = byGatewayId[gatewayId]

    /**
     * The participants [gatewayId] speaks for, cancel-only included, or null if the registry has
     * never heard of it.
     */
    fun participantsOf(gatewayId: String): LongArray? = participantsByGatewayId[gatewayId]

    /** The gateway [participantId] is bound to at session open, or null if nobody lists it. */
    fun primaryOf(participantId: Long): String? =
        primaries[participantId] ?: soleListing[participantId]

    fun mayPlace(gatewayId: String, participantId: Long): Boolean =
        byGatewayId[gatewayId]?.mayPlace(participantId) ?: false

    fun mayCancel(gatewayId: String, participantId: Long): Boolean =
        byGatewayId[gatewayId]?.mayCancel(participantId) ?: false

    fun mayOperate(gatewayId: String): Boolean = byGatewayId[gatewayId]?.operator ?: false

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
        val canonical = gateways.sortedBy { it.gatewayId }.joinToString(",") { it.canonical() } +
            // Appended only when present, for the reason GatewayIdentity.canonical gives.
            if (primaries.isEmpty()) "" else
                "|primary=" + primaries.toSortedMap().entries.joinToString(",") { "${it.key}>${it.value}" }
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
            if (gateway.participants.isNotEmpty() || !gateway.operator) {
                appendLine(
                    "gateway.${gateway.gatewayId}.participants=" +
                        gateway.participants.sorted().joinToString(",")
                )
            }
            if (gateway.cancelOnly.isNotEmpty()) {
                appendLine(
                    "gateway.${gateway.gatewayId}.cancelOnly=" +
                        gateway.cancelOnly.sorted().joinToString(",")
                )
            }
            if (gateway.operator) appendLine("gateway.${gateway.gatewayId}.operator=true")
        }
        if (primaries.isNotEmpty()) {
            appendLine()
            for ((participant, primary) in primaries.toSortedMap()) {
                appendLine("participant.$participant.primary=$primary")
            }
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
         * gateway.gw-b.participants=200,100
         * gateway.gw-b.cancelOnly=201           # optional: may cancel, may not place
         *
         * gateway.control.secret=<sha-256 of the shared secret, hex>
         * gateway.control.operator=true         # optional, default false
         *
         * participant.100.primary=gw-a          # required iff listed on several gateways
         * ```
         *
         * Nothing has a default that widens access. `participants` is required unless the gateway
         * is an operator, a missing secret would otherwise become "authenticates with anything",
         * and an `operator` value that is not `true` or `false` is refused rather than read as
         * false.
         */
        fun from(properties: Properties): ParticipantRegistry {
            val shardId = required(properties, "shard.id").toIntOrNull()
                ?: error("shard.id must be a number")

            val ids = required(properties, "registry.gateways")
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }

            val gateways = ids.map { id ->
                val operator = when (val flag = properties.getProperty("gateway.$id.operator")?.trim()) {
                    null, "false" -> false
                    "true" -> true
                    else -> error("gateway.$id.operator must be true or false, not '$flag'")
                }
                val participantsKey = "gateway.$id.participants"
                GatewayIdentity(
                    gatewayId = id,
                    secretSha256 = required(properties, "gateway.$id.secret").lowercase(),
                    participants = ids(
                        participantsKey,
                        if (operator) properties.getProperty(participantsKey)
                        else required(properties, participantsKey),
                    ),
                    cancelOnly = ids(
                        "gateway.$id.cancelOnly",
                        properties.getProperty("gateway.$id.cancelOnly"),
                    ),
                    operator = operator,
                )
            }

            val primaries = properties.stringPropertyNames()
                .mapNotNull { PRIMARY_KEY.matchEntire(it) }
                .associate { match ->
                    val participant = match.groupValues[1].toLongOrNull()
                        ?: error("${match.value} names a non-numeric participant")
                    participant to properties.getProperty(match.value).trim()
                }
            return ParticipantRegistry(shardId, gateways, primaries)
        }

        private val PRIMARY_KEY = Regex("""participant\.([^.]+)\.primary""")

        private fun ids(key: String, value: String?): List<Long> =
            value.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.map {
                it.toLongOrNull() ?: error("$key holds a non-numeric id: $it")
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
