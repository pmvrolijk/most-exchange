package nl.lamia.most.exchange.reference

import java.util.Properties

/** Real ISINs, so the check-digit validator is exercised against genuine reference data. */
const val ISIN_APPLE = "US0378331005"
const val ISIN_MICROSOFT = "US5949181045"
const val ISIN_BMW = "DE0005190003"
const val ISIN_BAE = "GB0002634946"

fun shardProperties(
    shardId: Int = 1,
    securities: List<Int> = listOf(1),
    isins: List<String> = listOf(ISIN_APPLE),
    symbols: List<String> = listOf("AAPL"),
    overrides: Map<String, String> = emptyMap(),
): Properties = Properties().apply {
    setProperty("shard.id", shardId.toString())
    setProperty("shard.securities", securities.joinToString(","))
    securities.forEachIndexed { i, id ->
        setProperty("security.$id.symbol", symbols[i])
        setProperty("security.$id.isin", isins[i])
        setProperty("security.$id.name", "Test Security $id")
        setProperty("security.$id.currency", "USD")
        setProperty("security.$id.priceFloor", "0")
        setProperty("security.$id.tickSize", "1000000")
        setProperty("security.$id.levelCount", "65536")
        setProperty("security.$id.maxOrders", "1000000")
    }
    overrides.forEach { (k, v) -> setProperty(k, v) }
}

fun spec(
    securityId: Int = 1,
    symbol: String = "AAPL",
    isin: String = ISIN_APPLE,
    levelCount: Int = 65536,
): SecuritySpec = SecuritySpec(
    securityId = securityId,
    symbol = symbol,
    isin = isin,
    name = "Test Security",
    currency = "USD",
    priceFloor = 0,
    tickSize = 1_000_000,
    levelCount = levelCount,
    maxOrders = 1_000_000,
)
