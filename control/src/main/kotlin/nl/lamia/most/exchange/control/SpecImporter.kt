package nl.lamia.most.exchange.control

import nl.lamia.most.exchange.reference.ShardSpec
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.Properties

/** What an import changed, so the operator can see it took rather than being told it did. */
data class ImportResult(
    val shardId: Int,
    val fingerprint: String,
    val inserted: List<String>,
    val updated: List<String>,
)

/**
 * Seeds the database from an existing shard security file.
 *
 * The bootstrap path: every deployment today has hand-written files, and asking an operator to
 * retype them into a REST API is both tedious and the obvious place to introduce the tick-size
 * typo this whole module exists to prevent. Parsing goes through [ShardSpec.from], so an imported
 * file is validated by the same code that would have booted it.
 *
 * Endpoints are not in a shard security file — they live in the gateway's and discovery's own
 * configuration — so importing securities requires the shard to exist already.
 */
@Service
class SpecImporter(
    private val topology: TopologyService,
    private val repository: TopologyRepository,
) {

    @Transactional
    fun import(text: String): ImportResult {
        val properties = Properties().apply { load(text.reader()) }
        val spec = ShardSpec.from(properties)
        requireNotNull(topology.shard(spec.shardId)) {
            "no such shard: ${spec.shardId}. Create the shard and its endpoints first — a shard " +
                "security file does not carry them."
        }

        val inserted = mutableListOf<String>()
        val updated = mutableListOf<String>()
        for (security in spec.securities) {
            val row = SecurityRow.from(security, spec.shardId)
            if (repository.security(security.securityId) == null) {
                repository.insertSecurity(row)
                inserted += security.symbol
            } else {
                repository.updateSecurity(row)
                updated += security.symbol
            }
        }

        // Recomputed from what was actually stored, not from what was parsed: an import that
        // silently lost a field would otherwise report the fingerprint of the input.
        val stored = ShardSpec(
            spec.shardId,
            repository.securitiesOfShard(spec.shardId).map { it.toSpec() },
        )
        return ImportResult(spec.shardId, stored.fingerprint(), inserted, updated)
    }
}
