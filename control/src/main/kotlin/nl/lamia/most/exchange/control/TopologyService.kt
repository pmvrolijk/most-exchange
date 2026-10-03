package nl.lamia.most.exchange.control

import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer
import nl.lamia.most.exchange.client.ShardRoute
import nl.lamia.most.exchange.reference.ParticipantRegistry
import nl.lamia.most.exchange.reference.ShardSpec
import nl.lamia.most.exchange.reference.Universe
import nl.lamia.most.exchange.reference.UniverseEntry
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** A shard as an operator reads it: its endpoints, its securities, and the fingerprint they hash to. */
data class ShardView(
    val shard: ShardRow,
    val securities: List<SecurityRow>,
    val fingerprint: String?,
    /** The participant registry's fingerprint, or null when the shard has no gateways. */
    val registryFingerprint: String?,
    val problem: String?,
)

/**
 * The whole draft universe, with whatever is wrong with it.
 *
 * [problems] being empty is exactly the condition for publishing, so the same check answers
 * "can I publish?" and "what is stopping me?" — an operator never has to publish to find out.
 */
data class TopologyView(
    val shards: List<ShardView>,
    // A 64-bit identity, so it goes out as a JSON string; see DirectoryState.version.
    @get:JsonSerialize(using = ToStringSerializer::class)
    val universeVersion: Long?,
    val problems: List<String>,
)

/**
 * Reference data and topology, validated by constructing the real `reference` domain types.
 *
 * Nothing here reimplements a rule. The ISIN check digit, the sixteen-character symbol limit, ten
 * securities per shard, one shard per security, globally unique symbols and ISINs, and the
 * fingerprint itself all come from `SecuritySpec`, `ShardSpec`, `ShardRoute` and `Universe`. A
 * second implementation that drifted by one separator would report agreement between processes
 * that disagree, which is worse than not checking at all.
 */
@Service
class TopologyService(private val repository: TopologyRepository) {

    // ------------------------------------------------------------------ reads

    fun shards(): List<ShardRow> = repository.shards()

    fun shard(shardId: Int): ShardRow? = repository.shard(shardId)

    fun securities(): List<SecurityRow> = repository.securities()

    fun security(securityId: Int): SecurityRow? = repository.security(securityId)

    fun securitiesOfShard(shardId: Int): List<SecurityRow> = repository.securitiesOfShard(shardId)

    /** For command paths, where an empty or unknown shard is a caller error rather than a state. */
    fun securitiesOfShardOrThrow(shardId: Int): List<SecurityRow> {
        requireNotNull(repository.shard(shardId)) { "no such shard: $shardId" }
        val securities = repository.securitiesOfShard(shardId)
        require(securities.isNotEmpty()) { "shard $shardId serves no securities" }
        return securities
    }

    fun participants(): List<ParticipantRow> = repository.participants()

    fun participant(participantId: Long): ParticipantRow? = repository.participant(participantId)

    // ----------------------------------------------------------------- writes

    /**
     * Writes validate the row, not the whole universe: a shard legitimately has no securities yet
     * when it is first created, and refusing that would make the topology impossible to author in
     * any order. Whole-universe rules are checked by [view] on demand and enforced at publish.
     */
    fun createShard(row: ShardRow): ShardRow {
        row.toRoute()
        repository.insertShard(row)
        return row
    }

    fun updateShard(row: ShardRow): ShardRow? {
        row.toRoute()
        return if (repository.updateShard(row)) row else null
    }

    fun deleteShard(shardId: Int): Boolean {
        val securities = repository.securitiesOfShard(shardId)
        require(securities.isEmpty()) {
            "shard $shardId still serves ${securities.map { it.symbol }}; move or delete them first"
        }
        return repository.deleteShard(shardId)
    }

    @Transactional
    fun createSecurity(row: SecurityRow): SecurityRow {
        row.toSpec()
        requireShardExists(row.shardId)
        requireShardHasRoom(row.shardId, incoming = row.securityId)
        repository.insertSecurity(row)
        return row
    }

    @Transactional
    fun updateSecurity(row: SecurityRow): SecurityRow? {
        row.toSpec()
        requireShardExists(row.shardId)
        requireShardHasRoom(row.shardId, incoming = row.securityId)
        return if (repository.updateSecurity(row)) row else null
    }

    fun deleteSecurity(securityId: Int): Boolean = repository.deleteSecurity(securityId)

    fun createParticipant(row: ParticipantRow): ParticipantRow {
        repository.insertParticipant(row)
        return row
    }

    fun updateParticipant(row: ParticipantRow): ParticipantRow? =
        if (repository.updateParticipant(row)) row else null

    fun deleteParticipant(participantId: Long): Boolean = repository.deleteParticipant(participantId)

    // --------------------------------------------------------------- gateways

    fun gateways(): List<GatewayRow> = repository.gateways()

    fun gateway(gatewayId: String): GatewayRow? = repository.gateway(gatewayId)

    fun createGateway(row: GatewayRow): GatewayRow {
        row.toIdentity()
        requireShardExists(row.shardId)
        requireSinglePrimary(row)
        repository.insertGateway(row)
        return row
    }

    fun updateGateway(row: GatewayRow): GatewayRow? {
        requireShardExists(row.shardId)
        // Validated against the stored digest rather than the row's empty one: an update does not
        // carry the secret, and constructing the identity is what checks the id and the claims.
        val stored = repository.gateway(row.gatewayId) ?: return null
        row.copy(secretSha256 = stored.secretSha256).toIdentity()
        requireSinglePrimary(row)
        return if (repository.updateGateway(row)) row.copy(secretSha256 = stored.secretSha256)
        else null
    }

    fun rotateGatewaySecret(gatewayId: String, secret: String): Boolean =
        repository.updateGatewaySecret(gatewayId, ParticipantRegistry.sha256Hex(secret))

    fun deleteGateway(gatewayId: String): Boolean = repository.deleteGateway(gatewayId)

    /**
     * The registry this shard would publish, or null when no gateway is registered for it.
     *
     * Null rather than an empty registry, because `ParticipantRegistry` requires at least one
     * gateway -- a shard with none is not a broken registry, it is a shard whose gateways still
     * connect anonymously, which is the behaviour that shipped before the registry existed.
     */
    fun participantRegistry(shardId: Int): ParticipantRegistry? {
        // Every enabled gateway, an operator-only one included: dropping it would leave the control
        // plane or the CLI anonymous, which a node with a registry refuses (Design.md §1).
        val gateways = repository.gatewaysOfShard(shardId)
        if (gateways.isEmpty()) return null
        // A primary flag is published only where it means something -- a participant several of
        // this shard's gateways list. On a participant only one lists, the file format refuses it
        // as a typo, and here it would be a leftover of a failover since disabled or deleted.
        val listings = gateways.flatMap { g -> (g.participants + g.cancelOnly).map { it to g.gatewayId } }
            .groupBy({ it.first }, { it.second })
        val primaries = gateways
            .flatMap { g -> g.primaryFor.map { it to g.gatewayId } }
            .filter { (participantId, _) -> listings[participantId].orEmpty().size > 1 }
            .toMap()
        return ParticipantRegistry(shardId, gateways.map { it.toIdentity() }, primaries)
    }

    /**
     * One primary per participant per shard. The published registry cannot even express two -- it
     * is a map -- so this is a storage-shape rule rather than a domain one, and the database cannot
     * check it without the gateway's shard beside each listing.
     */
    private fun requireSinglePrimary(row: GatewayRow) {
        if (row.primaryFor.isEmpty()) return
        val contested = repository.gateways()
            .filter { it.shardId == row.shardId && it.gatewayId != row.gatewayId }
            .flatMap { other -> other.primaryFor.filter { it in row.primaryFor }.map { it to other.gatewayId } }
        require(contested.isEmpty()) {
            "gateway ${row.gatewayId} cannot be primary for " +
                contested.joinToString(", ") { "${it.first}, whose primary on shard ${row.shardId} is ${it.second}" }
        }
    }

    // ------------------------------------------------------------- validation

    /**
     * Builds the [Universe] the database currently describes. Throws with the domain's own message
     * — "a security must be served by exactly one shard", "shard 0 hosts at most 10 securities" —
     * so an operator reads the same sentence the processes would have printed at boot.
     */
    fun universe(): Universe {
        val shards = repository.shards()
        val securities = repository.securities()
        check(shards.isNotEmpty()) { "the universe defines no shards" }
        check(securities.isNotEmpty()) { "the universe defines no securities" }
        return Universe(
            shards = shards.map { it.toRoute() },
            entries = securities.map { UniverseEntry(it.toSpec(), it.shardId) },
        )
    }

    /** Per-shard specs, in the order they will be published. */
    fun shardSpecs(): List<ShardSpec> = universe().shardSpecs()

    /**
     * The draft as an operator should see it: valid parts rendered with their fingerprints, invalid
     * parts named. Deliberately does not throw — a half-built topology is the normal state while
     * someone is editing it, and an editor that only reports the first error is unusable.
     */
    fun view(): TopologyView {
        val shardRows = repository.shards()
        val securityRows = repository.securities()
        val problems = mutableListOf<String>()

        val views = shardRows.map { shard ->
            val members = securityRows.filter { it.shardId == shard.shardId }
            var fingerprint: String? = null
            var problem: String? = null
            try {
                shard.toRoute()
                check(members.isNotEmpty()) { "shard ${shard.shardId} defines no securities" }
                fingerprint = ShardSpec(shard.shardId, members.map { it.toSpec() }).fingerprint()
            } catch (e: IllegalArgumentException) {
                problem = e.message
            } catch (e: IllegalStateException) {
                problem = e.message
            }
            var registryFingerprint: String? = null
            try {
                registryFingerprint = participantRegistry(shard.shardId)?.fingerprint()
            } catch (e: IllegalArgumentException) {
                problem = problem ?: e.message
            } catch (e: IllegalStateException) {
                problem = problem ?: e.message
            }
            problem?.let { problems += it }
            ShardView(shard, members, fingerprint, registryFingerprint, problem)
        }

        // Cross-shard rules -- one shard per security, globally unique symbols and ISINs, a
        // security naming a shard that does not exist -- only exist at the universe level, so they
        // are a second pass.
        //
        // Its message is recorded only when the per-shard pass found nothing. A universe built from
        // a shard that is already broken fails for the same reason, one level more vaguely: "shard
        // 0 defines no securities" is worth reading, "the universe defines no securities" beside it
        // is noise, and an operator scanning a list cannot tell which of the two to act on.
        val clean = problems.isEmpty()
        val universeVersion = try {
            universe().version
        } catch (e: IllegalArgumentException) {
            if (clean) e.message?.let { problems += it }
            null
        } catch (e: IllegalStateException) {
            if (clean) e.message?.let { problems += it }
            null
        }

        return TopologyView(views, universeVersion, problems.distinct())
    }

    private fun requireShardExists(shardId: Int) {
        requireNotNull(repository.shard(shardId)) { "no such shard: $shardId" }
    }

    /**
     * Ten per shard is a hard cap in `ShardSpec`, and the engine pre-allocates against it. Checked
     * on write as well as at publish so the operator is told at the point of the mistake rather
     * than at the point of release.
     */
    private fun requireShardHasRoom(shardId: Int, incoming: Int) {
        val existing = repository.securitiesOfShard(shardId).count { it.securityId != incoming }
        require(existing < ShardSpec.MAX_SECURITIES_PER_SHARD) {
            "shard $shardId already hosts ${ShardSpec.MAX_SECURITIES_PER_SHARD} securities, " +
                "which is the maximum"
        }
    }
}
