package com.engine.control

import com.engine.reference.Universe
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.file.Files
import java.nio.file.Path

/**
 * Publishes the database as the artifacts the exchange processes boot from.
 *
 * This is the whole point of the "database authors, files boot" split. The engine, gateway,
 * market-data and discovery keep reading `.properties` files exactly as they do today, so a
 * Postgres outage cannot stop a cluster node from starting, and two nodes cannot pick up different
 * geometry because a write landed between their boots — the one class of misconfiguration
 * consensus cannot catch (Design.md §7).
 *
 * A release is immutable. Republishing allocates the next version rather than rewriting one, so a
 * directory a running process was pointed at never changes underneath it.
 */
@Service
class ReleasePublisher(
    private val topology: TopologyService,
    private val jdbc: NamedParameterJdbcTemplate,
    @Value("\${control.releaseDir}") private val releaseRoot: String,
) {

    /**
     * Validates the whole universe, writes a numbered release directory, and records what it
     * contains. Validation is [TopologyService.universe] constructing the real domain types, so a
     * release that cannot be booted cannot be published.
     */
    @Transactional
    fun publish(note: String?): ReleaseRow {
        val universe = topology.universe()
        val specs = universe.shardSpecs()

        val version = insertRelease(universe.version, note)
        val directory = Path.of(releaseRoot).resolve(directoryName(version)).toAbsolutePath()
        Files.createDirectories(directory)

        // Absolute paths, because discovery reads this registry from wherever it happens to be
        // started and must find the shard files regardless of its working directory.
        val securitiesFile = { shardId: Int -> directory.resolve(shardFileName(shardId)).toString() }

        for (spec in specs) {
            Files.writeString(directory.resolve(shardFileName(spec.shardId)), spec.render())
        }
        Files.writeString(directory.resolve(REGISTRY_FILE), universe.render(securitiesFile))

        // A shard with no gateways publishes no registry rather than an empty one: its gateways
        // connect anonymously and the engine learns routes from traffic, which is the behaviour
        // that shipped before the registry existed and is a legitimate configuration.
        val registries = specs.mapNotNull { spec ->
            topology.participantRegistry(spec.shardId)?.let { spec.shardId to it }
        }.toMap()
        for ((shardId, registry) in registries) {
            Files.writeString(directory.resolve(participantsFileName(shardId)), registry.render())
        }

        val fingerprints = specs.associate { it.shardId to it.fingerprint() }
        val registryFingerprints = registries.mapValues { (_, registry) -> registry.fingerprint() }
        recordFingerprints(version, fingerprints, registryFingerprints)
        Files.writeString(
            directory.resolve(MANIFEST_FILE),
            manifest(version, universe, fingerprints, registryFingerprints, note),
        )
        updateDirectory(version, directory.toString())

        return ReleaseRow(
            version = version,
            createdAt = createdAt(version),
            universeVersion = universe.version,
            directory = directory.toString(),
            note = note,
            fingerprints = fingerprints,
            registryFingerprints = registryFingerprints,
        )
    }

    fun releases(): List<ReleaseRow> =
        jdbc.query("SELECT * FROM spec_release ORDER BY version DESC", RELEASE)
            .map { it.withFingerprints() }

    fun release(version: Long): ReleaseRow? = jdbc.query(
        "SELECT * FROM spec_release WHERE version = :v",
        mapOf("v" to version),
        RELEASE,
    ).firstOrNull()?.withFingerprints()

    fun latest(): ReleaseRow? =
        jdbc.query("SELECT * FROM spec_release ORDER BY version DESC LIMIT 1", RELEASE)
            .firstOrNull()?.withFingerprints()

    /** The rendered text of one artifact, served so a deploy step can pull rather than share a mount. */
    fun artifact(version: Long, name: String): String? {
        val release = release(version) ?: return null
        // Resolved and re-checked against the release directory: a version is a number from a URL,
        // and a file name from a URL has no business escaping the directory it names.
        val directory = Path.of(release.directory).toAbsolutePath().normalize()
        val file = directory.resolve(name).normalize()
        if (!file.startsWith(directory) || !Files.isRegularFile(file)) return null
        return Files.readString(file)
    }

    private fun insertRelease(universeVersion: Long, note: String?): Long {
        val keys = GeneratedKeyHolder()
        jdbc.update(
            """
            INSERT INTO spec_release (universe_version, directory, note)
            VALUES (:universeVersion, :directory, :note)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("universeVersion", universeVersion)
                // Placeholder: the directory name contains the version the database is allocating.
                .addValue("directory", "")
                .addValue("note", note),
            keys,
            arrayOf("version"),
        )
        return checkNotNull(keys.key) {
            "the database did not return a release version"
        }.toLong()
    }

    private fun updateDirectory(version: Long, directory: String) {
        jdbc.update(
            "UPDATE spec_release SET directory = :directory WHERE version = :v",
            mapOf("directory" to directory, "v" to version),
        )
    }

    private fun recordFingerprints(
        version: Long,
        fingerprints: Map<Int, String>,
        registryFingerprints: Map<Int, String>,
    ) {
        for ((shardId, fingerprint) in fingerprints) {
            jdbc.update(
                "INSERT INTO spec_release_shard (version, shard_id, fingerprint, registry_fingerprint) " +
                    "VALUES (:v, :shardId, :fingerprint, :registryFingerprint)",
                mapOf(
                    "v" to version,
                    "shardId" to shardId,
                    "fingerprint" to fingerprint,
                    "registryFingerprint" to registryFingerprints[shardId],
                ),
            )
        }
    }

    private fun ReleaseRow.withFingerprints(): ReleaseRow {
        val rows = jdbc.query(
            "SELECT shard_id, fingerprint, registry_fingerprint FROM spec_release_shard " +
                "WHERE version = :v ORDER BY shard_id",
            mapOf("v" to version),
        ) { rs, _ ->
            Triple(rs.getInt("shard_id"), rs.getString("fingerprint"), rs.getString("registry_fingerprint"))
        }
        return copy(
            fingerprints = rows.associate { it.first to it.second },
            registryFingerprints = rows.filter { it.third != null }
                .associate { it.first to it.third },
        )
    }

    private fun createdAt(version: Long): String = jdbc.queryForObject(
        "SELECT created_at FROM spec_release WHERE version = :v",
        mapOf("v" to version),
        String::class.java,
    ) ?: ""

    /**
     * Hand-built rather than serialized: the manifest is something an operator reads beside a log
     * line, so key order is fixed and the fingerprints come first.
     */
    private fun manifest(
        version: Long,
        universe: Universe,
        fingerprints: Map<Int, String>,
        registryFingerprints: Map<Int, String>,
        note: String?,
    ): String = buildString {
        appendLine("{")
        appendLine("""  "version": $version,""")
        appendLine("""  "universeVersion": ${universe.version},""")
        appendLine("""  "securities": ${universe.entries.size},""")
        appendLine("""  "shards": [""")
        val ordered = fingerprints.entries.sortedBy { it.key }
        ordered.forEachIndexed { index, (shardId, fingerprint) ->
            val comma = if (index == ordered.lastIndex) "" else ","
            // The registry has its own fingerprint and its own file, and both are absent for a
            // shard with no gateways. Widening `fingerprint` to cover it would have invalidated
            // every value recorded in a release published so far.
            val registry = registryFingerprints[shardId]?.let {
                """, "registryFingerprint": "$it", """ +
                    """"participantsFile": "${participantsFileName(shardId)}""""
            } ?: ""
            appendLine(
                """    { "shardId": $shardId, "fingerprint": "$fingerprint", """ +
                    """"securitiesFile": "${shardFileName(shardId)}"$registry }$comma""",
            )
        }
        appendLine("  ],")
        appendLine("""  "registry": "$REGISTRY_FILE",""")
        appendLine("""  "note": ${note?.let { "\"${it.replace("\"", "\\\"")}\"" } ?: "null"}""")
        appendLine("}")
    }

    companion object {
        const val REGISTRY_FILE = "discovery.properties"
        const val MANIFEST_FILE = "manifest.json"

        fun directoryName(version: Long): String = "%06d".format(version)

        fun shardFileName(shardId: Int): String = "shard-$shardId-securities.properties"

        fun participantsFileName(shardId: Int): String = "shard-$shardId-participants.properties"

        private val RELEASE = RowMapper { rs, _ ->
            ReleaseRow(
                version = rs.getLong("version"),
                createdAt = rs.getString("created_at"),
                universeVersion = rs.getLong("universe_version"),
                directory = rs.getString("directory"),
                note = rs.getString("note"),
            )
        }
    }
}
