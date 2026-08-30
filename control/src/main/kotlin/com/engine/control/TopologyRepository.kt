package com.engine.control

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

/**
 * Plain JDBC rather than an ORM.
 *
 * Every primary key here is operator-assigned — `shardId` and `securityId` are the identifiers the
 * wire uses, not surrogates — and mapping frameworks read a non-null id as "this row already
 * exists", which turns an insert into a silent no-op update. The model is six flat tables; hand
 * writing the statements costs less than defending against that.
 */
@Repository
class TopologyRepository(private val jdbc: NamedParameterJdbcTemplate) {

    // ------------------------------------------------------------------ shards

    fun shards(): List<ShardRow> =
        jdbc.query("SELECT * FROM shard ORDER BY shard_id", SHARD)

    fun shard(shardId: Int): ShardRow? =
        jdbc.query("SELECT * FROM shard WHERE shard_id = :id", mapOf("id" to shardId), SHARD)
            .firstOrNull()

    fun insertShard(row: ShardRow) {
        jdbc.update(
            """
            INSERT INTO shard (shard_id, order_entry_channel, order_entry_stream_id,
                               execution_report_channel, execution_report_stream_id)
            VALUES (:shardId, :orderEntryChannel, :orderEntryStreamId,
                    :executionReportChannel, :executionReportStreamId)
            """.trimIndent(),
            shardParameters(row),
        )
    }

    /** Returns false when no such shard exists, so the caller can answer 404 rather than 200. */
    fun updateShard(row: ShardRow): Boolean = jdbc.update(
        """
        UPDATE shard SET order_entry_channel = :orderEntryChannel,
                         order_entry_stream_id = :orderEntryStreamId,
                         execution_report_channel = :executionReportChannel,
                         execution_report_stream_id = :executionReportStreamId
        WHERE shard_id = :shardId
        """.trimIndent(),
        shardParameters(row),
    ) > 0

    fun deleteShard(shardId: Int): Boolean =
        jdbc.update("DELETE FROM shard WHERE shard_id = :id", mapOf("id" to shardId)) > 0

    private fun shardParameters(row: ShardRow) = MapSqlParameterSource()
        .addValue("shardId", row.shardId)
        .addValue("orderEntryChannel", row.orderEntryChannel.trim())
        .addValue("orderEntryStreamId", row.orderEntryStreamId)
        .addValue("executionReportChannel", row.executionReportChannel.trim())
        .addValue("executionReportStreamId", row.executionReportStreamId)

    // -------------------------------------------------------------- securities

    fun securities(): List<SecurityRow> =
        jdbc.query("SELECT * FROM security ORDER BY security_id", SECURITY)

    fun security(securityId: Int): SecurityRow? =
        jdbc.query("SELECT * FROM security WHERE security_id = :id", mapOf("id" to securityId), SECURITY)
            .firstOrNull()

    fun securitiesOfShard(shardId: Int): List<SecurityRow> = jdbc.query(
        "SELECT * FROM security WHERE shard_id = :id ORDER BY security_id",
        mapOf("id" to shardId),
        SECURITY,
    )

    fun insertSecurity(row: SecurityRow) {
        jdbc.update(
            """
            INSERT INTO security (security_id, shard_id, symbol, isin, name, currency,
                                  price_floor, tick_size, level_count, max_orders)
            VALUES (:securityId, :shardId, :symbol, :isin, :name, :currency,
                    :priceFloor, :tickSize, :levelCount, :maxOrders)
            """.trimIndent(),
            securityParameters(row),
        )
    }

    fun updateSecurity(row: SecurityRow): Boolean = jdbc.update(
        """
        UPDATE security SET shard_id = :shardId, symbol = :symbol, isin = :isin, name = :name,
                            currency = :currency, price_floor = :priceFloor,
                            tick_size = :tickSize, level_count = :levelCount,
                            max_orders = :maxOrders
        WHERE security_id = :securityId
        """.trimIndent(),
        securityParameters(row),
    ) > 0

    fun deleteSecurity(securityId: Int): Boolean =
        jdbc.update("DELETE FROM security WHERE security_id = :id", mapOf("id" to securityId)) > 0

    private fun securityParameters(row: SecurityRow) = MapSqlParameterSource()
        .addValue("securityId", row.securityId)
        .addValue("shardId", row.shardId)
        .addValue("symbol", row.symbol.trim())
        .addValue("isin", row.isin.trim().uppercase())
        .addValue("name", row.name.trim())
        .addValue("currency", row.currency.trim().uppercase())
        .addValue("priceFloor", row.priceFloor)
        .addValue("tickSize", row.tickSize)
        .addValue("levelCount", row.levelCount)
        .addValue("maxOrders", row.maxOrders)

    // ------------------------------------------------------------ participants

    fun participants(): List<ParticipantRow> =
        jdbc.query("SELECT * FROM participant ORDER BY participant_id", PARTICIPANT)

    fun participant(participantId: Long): ParticipantRow? = jdbc.query(
        "SELECT * FROM participant WHERE participant_id = :id",
        mapOf("id" to participantId),
        PARTICIPANT,
    ).firstOrNull()

    fun insertParticipant(row: ParticipantRow) {
        jdbc.update(
            """
            INSERT INTO participant (participant_id, name, smp_id, enabled)
            VALUES (:participantId, :name, :smpId, :enabled)
            """.trimIndent(),
            participantParameters(row),
        )
    }

    fun updateParticipant(row: ParticipantRow): Boolean = jdbc.update(
        "UPDATE participant SET name = :name, smp_id = :smpId, enabled = :enabled " +
            "WHERE participant_id = :participantId",
        participantParameters(row),
    ) > 0

    fun deleteParticipant(participantId: Long): Boolean =
        jdbc.update("DELETE FROM participant WHERE participant_id = :id", mapOf("id" to participantId)) > 0

    private fun participantParameters(row: ParticipantRow) = MapSqlParameterSource()
        .addValue("participantId", row.participantId)
        .addValue("name", row.name.trim())
        .addValue("smpId", row.smpId)
        .addValue("enabled", row.enabled)

    private companion object {
        val SHARD = RowMapper { rs, _ ->
            ShardRow(
                shardId = rs.getInt("shard_id"),
                orderEntryChannel = rs.getString("order_entry_channel"),
                orderEntryStreamId = rs.getInt("order_entry_stream_id"),
                executionReportChannel = rs.getString("execution_report_channel"),
                executionReportStreamId = rs.getInt("execution_report_stream_id"),
            )
        }

        val SECURITY = RowMapper { rs, _ ->
            SecurityRow(
                securityId = rs.getInt("security_id"),
                shardId = rs.getInt("shard_id"),
                symbol = rs.getString("symbol"),
                // CHAR(12) and CHAR(3) come back space-padded; the domain types reject the padding.
                isin = rs.getString("isin").trim(),
                name = rs.getString("name"),
                currency = rs.getString("currency").trim(),
                priceFloor = rs.getLong("price_floor"),
                tickSize = rs.getLong("tick_size"),
                levelCount = rs.getInt("level_count"),
                maxOrders = rs.getInt("max_orders"),
            )
        }

        val PARTICIPANT = RowMapper { rs, _ ->
            ParticipantRow(
                participantId = rs.getLong("participant_id"),
                name = rs.getString("name"),
                smpId = rs.getLong("smp_id"),
                enabled = rs.getBoolean("enabled"),
            )
        }
    }
}
