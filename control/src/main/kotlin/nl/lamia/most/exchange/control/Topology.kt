package nl.lamia.most.exchange.control

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer
import nl.lamia.most.exchange.reference.GatewayIdentity
import nl.lamia.most.exchange.reference.SecuritySpec
import nl.lamia.most.exchange.client.ShardRoute

/**
 * The editable shape of a shard, as the API and the database see it.
 *
 * Deliberately separate from [ShardRoute]: the domain type validates on construction, so a request
 * body has to be able to hold an invalid value long enough to be reported as one. [toRoute] is the
 * single crossing point, and it is where a bad channel becomes a 400 rather than a stack trace.
 */
data class ShardRow(
    val shardId: Int,
    val orderEntryChannel: String,
    val orderEntryStreamId: Int,
    val executionReportChannel: String,
    val executionReportStreamId: Int,
) {
    fun toRoute(): ShardRoute = ShardRoute(
        shardId = shardId,
        orderEntryChannel = orderEntryChannel.trim(),
        orderEntryStreamId = orderEntryStreamId,
        executionReportChannel = executionReportChannel.trim(),
        executionReportStreamId = executionReportStreamId,
    )

    companion object {
        fun from(route: ShardRoute) = ShardRow(
            shardId = route.shardId,
            orderEntryChannel = route.orderEntryChannel,
            orderEntryStreamId = route.orderEntryStreamId,
            executionReportChannel = route.executionReportChannel,
            executionReportStreamId = route.executionReportStreamId,
        )
    }
}

/** The editable shape of a security. [toSpec] is where ISIN check digits and lengths are enforced. */
data class SecurityRow(
    val securityId: Int,
    val shardId: Int,
    val symbol: String,
    val isin: String,
    val name: String,
    val currency: String,
    val priceFloor: Long,
    val tickSize: Long,
    val levelCount: Int,
    val maxOrders: Int,
    // Not geometry, and deliberately not published: reference prices and collar widths arrive at
    // runtime as SecurityDefinition commands, and are outside the fingerprint for that reason.
    // Null until an operator seeds them; a definition cannot be sent without them.
    val referencePrice: Long? = null,
    val staticCollarBps: Int? = null,
    val dynamicCollarBps: Int? = null,
) {
    fun toSpec(): SecuritySpec = SecuritySpec(
        securityId = securityId,
        symbol = symbol.trim(),
        isin = isin.trim().uppercase(),
        name = name.trim(),
        currency = currency.trim().uppercase(),
        priceFloor = priceFloor,
        tickSize = tickSize,
        levelCount = levelCount,
        maxOrders = maxOrders,
    )

    companion object {
        fun from(spec: SecuritySpec, shardId: Int) = SecurityRow(
            securityId = spec.securityId,
            shardId = shardId,
            symbol = spec.symbol,
            isin = spec.isin,
            name = spec.name,
            currency = spec.currency,
            priceFloor = spec.priceFloor,
            tickSize = spec.tickSize,
            levelCount = spec.levelCount,
            maxOrders = spec.maxOrders,
        )
    }
}

/**
 * A trading participant. Not yet on the wire — nothing today raises the engine's
 * `UNAUTHORIZED_PARTICIPANT` — but authored here so the binding has somewhere to live.
 */
data class ParticipantRow(
    val participantId: Long,
    val name: String,
    /** 0 means "default to participantId", exactly as `SmpId` does on the wire. */
    val smpId: Long = 0L,
    val enabled: Boolean = true,
) {
    init {
        require(participantId > 0L) { "participantId must be positive: $participantId" }
        require(name.isNotBlank()) { "participant $participantId has no name" }
        require(name.length <= MAX_NAME) { "participant name exceeds $MAX_NAME characters" }
        require(smpId >= 0L) { "smpId must be non-negative: $smpId" }
    }

    companion object {
        const val MAX_NAME = 64
    }
}

/**
 * A gateway process's identity on a shard, and what it may do (Design.md §1): the participants it
 * may place for, those it may only cancel for, and whether it may send operator commands.
 *
 * The editable shape of `reference`'s `GatewayIdentity`. [toIdentity] is the crossing point where
 * the domain type validates -- the alphabet, the digest's shape, the participant list -- exactly as
 * `SecurityRow.toSpec()` does. The secret is here only as its SHA-256: the cluster verifies that
 * digest against what a gateway presents, so it must be reproducible, and it is never recoverable.
 */
data class GatewayRow(
    val gatewayId: String,
    val shardId: Int,
    val enabled: Boolean = true,
    /** Participants that may place and cancel through this gateway. */
    val participants: List<Long> = emptyList(),
    /** Participants that may cancel and not place: revocation made graceful. */
    val cancelOnly: List<Long> = emptyList(),
    /** May send operator commands. With no participants, this is how an operator is named. */
    val operator: Boolean = false,
    /**
     * Among the participants this gateway lists, those it is the primary for -- the gateway they
     * are bound to when more than one listing gateway is connected. Meaningful only for a
     * participant several gateways on the shard list; published only then.
     */
    val primaryFor: List<Long> = emptyList(),
    /** Never serialised out; see [GatewayController]. */
    @get:JsonIgnore
    val secretSha256: String = "",
) {
    init {
        require(shardId >= 0) { "shardId must be non-negative: $shardId" }
        val listed = participants + cancelOnly
        require(listed.all { it > 0L }) { "participant ids must be positive" }
        require(listed.distinct().size == listed.size) {
            "gateway $gatewayId lists a participant twice, or as both placing and cancel-only"
        }
        require(primaryFor.all { it in listed }) {
            "gateway $gatewayId is primary for ${primaryFor.filterNot { it in listed }}, " +
                "which it does not list"
        }
    }

    /** Builds the real domain type, which is what enforces every rule this row does not. */
    fun toIdentity(): GatewayIdentity = GatewayIdentity(
        gatewayId = gatewayId,
        secretSha256 = secretSha256,
        participants = participants.sorted(),
        cancelOnly = cancelOnly.sorted(),
        operator = operator,
    )
}

/** One published release, and the fingerprint each shard's processes should print. */
data class ReleaseRow(
    val version: Long,
    val createdAt: String,
    // A 64-bit identity, so it goes out as a JSON string; see DirectoryState.version.
    @get:JsonSerialize(using = ToStringSerializer::class)
    val universeVersion: Long,
    val directory: String,
    val note: String?,
    val fingerprints: Map<Int, String> = emptyMap(),
    /**
     * The participant registry's fingerprint per shard, for the shards that have one.
     *
     * Deliberately beside [fingerprints] rather than folded into it: that hash is over shard
     * geometry and is already written down in every release published so far, and rotating a
     * gateway secret is not a change of geometry.
     */
    val registryFingerprints: Map<Int, String> = emptyMap(),
)
