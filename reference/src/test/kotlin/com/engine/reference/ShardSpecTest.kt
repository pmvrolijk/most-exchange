package com.engine.reference

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class IsinValidationTest {

    @Test
    fun `accepts real ISINs`() {
        listOf(ISIN_APPLE, ISIN_MICROSOFT, ISIN_BMW, ISIN_BAE).forEach {
            assertTrue(SecuritySpec.isValidIsin(it), "expected $it to validate")
        }
    }

    @Test
    fun `rejects a wrong check digit`() {
        // A single transposed digit is the classic reference-data typo, and the check digit is
        // the only thing that catches it before the directory is published.
        assertFalse(SecuritySpec.isValidIsin("US0378331006"))
        assertFalse(SecuritySpec.isValidIsin("US0378313005"))
    }

    @Test
    fun `rejects malformed ISINs`() {
        assertFalse(SecuritySpec.isValidIsin(""))
        assertFalse(SecuritySpec.isValidIsin("US037833100"))
        assertFalse(SecuritySpec.isValidIsin("US03783310055"))
        assertFalse(SecuritySpec.isValidIsin("1S0378331005"))
        assertFalse(SecuritySpec.isValidIsin("us0378331005"))
        assertFalse(SecuritySpec.isValidIsin("US03783310-5"))
    }
}

class ShardSpecTest {

    @Test
    fun `parses securities with their metadata`() {
        val shard = ShardSpec.from(shardProperties())
        val security = shard.securities.single()

        assertEquals(1, shard.shardId)
        assertEquals("AAPL", security.symbol)
        assertEquals(ISIN_APPLE, security.isin)
        assertEquals("USD", security.currency)
        assertEquals(1_000_000L, security.tickSize)
    }

    @Test
    fun `parses several securities`() {
        val shard = ShardSpec.from(
            shardProperties(
                securities = listOf(1, 2),
                isins = listOf(ISIN_APPLE, ISIN_BMW),
                symbols = listOf("AAPL", "BMW"),
            ),
        )
        assertEquals(listOf("AAPL", "BMW"), shard.securities.map { it.symbol })
        assertEquals(2, shard.securityIds.size)
    }

    @Test
    fun `metadata has no defaults`() {
        val incomplete = shardProperties().apply { remove("security.1.isin") }
        val error = assertFailsWith<IllegalStateException> { ShardSpec.from(incomplete) }
        assertContains(error.message!!, "security.1.isin")
    }

    @Test
    fun `a bad ISIN is rejected at boot`() {
        val bad = shardProperties(overrides = mapOf("security.1.isin" to "US0378331006"))
        val error = assertFailsWith<IllegalArgumentException> { ShardSpec.from(bad) }
        assertContains(error.message!!, "invalid ISIN")
    }

    @Test
    fun `a bad currency code is rejected`() {
        val bad = shardProperties(overrides = mapOf("security.1.currency" to "DOLLAR"))
        val error = assertFailsWith<IllegalArgumentException> { ShardSpec.from(bad) }
        assertContains(error.message!!, "currency")
    }

    @Test
    fun `an over-long symbol is rejected`() {
        val bad = shardProperties(overrides = mapOf("security.1.symbol" to "A".repeat(20)))
        assertFailsWith<IllegalArgumentException> { ShardSpec.from(bad) }
    }

    @Test
    fun `the shard cap is enforced`() {
        val ids = (1..11).toList()
        val error = assertFailsWith<IllegalArgumentException> {
            ShardSpec.from(
                shardProperties(
                    securities = ids,
                    isins = ids.map { ISIN_APPLE },
                    symbols = ids.map { "S$it" },
                ),
            )
        }
        assertContains(error.message!!, "at most 10")
    }

    @Test
    fun `duplicate symbols and ISINs are rejected`() {
        val dupSymbol = assertFailsWith<IllegalArgumentException> {
            ShardSpec.from(
                shardProperties(
                    securities = listOf(1, 2),
                    isins = listOf(ISIN_APPLE, ISIN_BMW),
                    symbols = listOf("AAPL", "AAPL"),
                ),
            )
        }
        assertContains(dupSymbol.message!!, "duplicate symbol")

        val dupIsin = assertFailsWith<IllegalArgumentException> {
            ShardSpec.from(
                shardProperties(
                    securities = listOf(1, 2),
                    isins = listOf(ISIN_APPLE, ISIN_APPLE),
                    symbols = listOf("AAPL", "BMW"),
                ),
            )
        }
        assertContains(dupIsin.message!!, "duplicate ISIN")
    }

    @Test
    fun `the fingerprint covers geometry and identity but not display name`() {
        val base = ShardSpec.from(shardProperties())
        val renamed = ShardSpec.from(
            shardProperties(overrides = mapOf("security.1.name" to "Something Else")),
        )
        assertEquals(base.fingerprint(), renamed.fingerprint())

        val retick = ShardSpec.from(
            shardProperties(overrides = mapOf("security.1.tickSize" to "500000")),
        )
        assertNotEquals(base.fingerprint(), retick.fingerprint())

        val resymbol = ShardSpec.from(
            shardProperties(overrides = mapOf("security.1.symbol" to "OTHER")),
        )
        assertNotEquals(base.fingerprint(), resymbol.fingerprint())
    }

    @Test
    fun `the fingerprint distinguishes shards`() {
        assertNotEquals(
            ShardSpec.from(shardProperties(shardId = 1)).fingerprint(),
            ShardSpec.from(shardProperties(shardId = 2)).fingerprint(),
        )
    }

    @Test
    fun `lookup by security id`() {
        val shard = ShardSpec.from(shardProperties())
        assertEquals("AAPL", shard.securityById(1)?.symbol)
        assertEquals(null, shard.securityById(99))
    }
}
