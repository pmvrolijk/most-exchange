package com.engine.control

import com.engine.reference.ShardSpec
import com.engine.reference.Universe
import com.engine.reference.UniverseEntry
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** A shard as an operator reads it: its endpoints, its securities, and the fingerprint they hash to. */
data class ShardView(
    val shard: ShardRow,
    val securities: List<SecurityRow>,
    val fingerprint: String?,
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
            problem?.let { problems += it }
            ShardView(shard, members, fingerprint, problem)
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
