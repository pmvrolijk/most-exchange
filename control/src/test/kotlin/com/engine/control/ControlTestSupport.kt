package com.engine.control

import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.nio.file.Files

/** Real ISINs, so the check-digit validator is exercised against genuine reference data. */
const val ISIN_APPLE = "US0378331005"
const val ISIN_MICROSOFT = "US5949181045"
const val ISIN_BMW = "DE0005190003"
const val ISIN_BAE = "GB0002634946"

/**
 * One Postgres, started once and shared by every test class.
 *
 * A real database rather than an in-memory stand-in, because half of what this module relies on is
 * schema behaviour: `CHAR(12)` space-padding an ISIN on the way out, a unique constraint catching a
 * reused symbol, a foreign key refusing to drop a shard still serving securities. None of that
 * survives substitution, and each is a rule the design depends on.
 */
abstract class PostgresTest {

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var clusterLink: ClusterLink

    /**
     * Truncated rather than rolled back. The publisher writes files as well as rows, and a test
     * transaction that unwound the rows while leaving the directories behind would let a broken
     * publish pass.
     */
    @BeforeEach
    fun clean() {
        jdbc.execute(
            "TRUNCATE spec_release_shard, spec_release, security, participant, shard, " +
                "session_schedule, session_schedule_entry, market_holiday, schedule_run " +
                "RESTART IDENTITY CASCADE",
        )
        // The Spring context is shared across test classes, so observed feed state outlives a
        // truncate. A halt left behind by one test would make the next one skip.
        clusterLink.state.clear()
    }

    companion object {
        @JvmStatic
        protected val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine")).apply { start() }

        @JvmStatic
        protected val releaseDir: String =
            Files.createTempDirectory("control-releases").toAbsolutePath().toString()

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("control.releaseDir") { releaseDir }
            // No media driver in the suite: the link is exercised through a fake, and the point of
            // it being optional is that everything else works without one.
            registry.add("control.aeron.enabled") { "false" }
            // The scheduler is driven explicitly through reconcile(instant) in tests: a background
            // tick reading the wall clock would make them depend on what time the suite runs.
            registry.add("control.scheduler.enabled") { "false" }
        }
    }
}

fun shardRow(
    shardId: Int = 0,
    orderEntryStreamId: Int = 20,
    executionReportStreamId: Int = 21,
) = ShardRow(
    shardId = shardId,
    orderEntryChannel = "aeron:udp?endpoint=gw$shardId:20001",
    orderEntryStreamId = orderEntryStreamId,
    executionReportChannel = "aeron:udp?endpoint=gw$shardId:20002",
    executionReportStreamId = executionReportStreamId,
)

@Suppress("LongParameterList")
fun securityRow(
    securityId: Int = 1,
    shardId: Int = 0,
    symbol: String = "AAPL",
    isin: String = ISIN_APPLE,
    name: String = "Apple Inc.",
    currency: String = "USD",
    priceFloor: Long = 0L,
    tickSize: Long = 1_000_000L,
    levelCount: Int = 65_536,
    maxOrders: Int = 1_000_000,
) = SecurityRow(
    securityId = securityId,
    shardId = shardId,
    symbol = symbol,
    isin = isin,
    name = name,
    currency = currency,
    priceFloor = priceFloor,
    tickSize = tickSize,
    levelCount = levelCount,
    maxOrders = maxOrders,
)
