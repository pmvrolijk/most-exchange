package nl.lamia.most.exchange.reference

/**
 * Everything static about one tradable security: identity for humans and downstream systems,
 * geometry for the engine and the depth aggregator.
 *
 * This is the single definition every process in a shard reads. The engine, the gateway and the
 * market data process previously each kept their own security list with nothing checking they
 * agreed; now they load the same file and print the same [ShardSpec.fingerprint].
 *
 * What is *not* here: reference prices and collar widths. Those change during a session and
 * arrive as `SecurityDefinition` commands through the replicated log, so that every node applies
 * them at the same log position (Design.md §4.4).
 */
data class SecuritySpec(
    val securityId: Int,
    val symbol: String,
    val isin: String,
    val name: String,
    val currency: String,
    // Geometry. Fixed at book construction because the ladders are pre-allocated against it.
    val priceFloor: Long,
    val tickSize: Long,
    val levelCount: Int,
    val maxOrders: Int,
) {
    init {
        require(securityId >= 0) { "securityId must be non-negative: $securityId" }
        require(symbol.isNotBlank()) { "symbol is required for security $securityId" }
        require(symbol.length <= MAX_SYMBOL) {
            "symbol '$symbol' exceeds $MAX_SYMBOL characters for security $securityId"
        }
        require(name.length <= MAX_NAME) {
            "name for security $securityId exceeds $MAX_NAME characters"
        }
        require(currency.length == CURRENCY_LENGTH) {
            "currency must be a $CURRENCY_LENGTH-letter code, got '$currency' for security $securityId"
        }
        require(isValidIsin(isin)) { "invalid ISIN '$isin' for security $securityId" }
        require(tickSize > 0) { "tickSize must be positive for security $securityId: $tickSize" }
        require(levelCount > 0) { "levelCount must be positive for security $securityId: $levelCount" }
        require(maxOrders > 0) { "maxOrders must be positive for security $securityId: $maxOrders" }
    }

    /** Only the fields every process must agree on. Display metadata is deliberately excluded. */
    fun canonical(): String =
        "$securityId:$symbol:$isin:$currency:$priceFloor:$tickSize:$levelCount:$maxOrders"

    companion object {
        const val MAX_SYMBOL = 16
        const val ISIN_LENGTH = 12
        const val CURRENCY_LENGTH = 3
        const val MAX_NAME = 48

        /**
         * ISIN format plus its check digit. Reference data typos are cheap to make and expensive
         * to find once they are in a published directory, and the check digit costs nothing to
         * verify at boot.
         *
         * Two letters of country code, nine alphanumeric characters, one check digit, validated
         * with the Luhn variant ISO 6166 specifies: letters expand to two digits each.
         */
        fun isValidIsin(isin: String): Boolean {
            if (isin.length != ISIN_LENGTH) return false
            if (!isin[0].isLetter() || !isin[1].isLetter()) return false
            if (!isin.all { it.isLetterOrDigit() }) return false
            if (!isin.all { it.isDigit() || it.isUpperCase() }) return false
            if (!isin[ISIN_LENGTH - 1].isDigit()) return false

            // Expand letters to their alphabet position + 9, then Luhn over the digit string.
            val digits = StringBuilder(ISIN_LENGTH * 2)
            for (c in isin) {
                if (c.isDigit()) digits.append(c) else digits.append(c - 'A' + 10)
            }
            var sum = 0
            var double = true // rightmost digit is the check digit, so doubling starts left of it
            for (i in digits.length - 2 downTo 0) {
                var value = digits[i] - '0'
                if (double) {
                    value *= 2
                    if (value > 9) value -= 9
                }
                sum += value
                double = !double
            }
            val check = (10 - (sum % 10)) % 10
            return check == digits[digits.length - 1] - '0'
        }
    }
}
