package com.engine.reference

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Converts between the wire's fixed-point prices and human decimal text.
 *
 * Prices are `int64` with 8 implied decimals throughout (Design.md §3). Parsing goes through
 * [BigDecimal] rather than `Double`: a price is exact by definition, and binary floating point
 * cannot represent most decimal ticks, so a round trip through it would corrupt values that must
 * survive intact.
 */
object PriceCodec {

    const val IMPLIED_DECIMALS = 8
    const val SCALE = 100_000_000L

    /** Parses decimal text, rejecting anything that does not fit the implied scale exactly. */
    fun parse(text: String): Long {
        val trimmed = text.trim()
        require(trimmed.isNotEmpty()) { "price is empty" }
        val decimal = try {
            BigDecimal(trimmed)
        } catch (_: NumberFormatException) {
            throw IllegalArgumentException("price '$text' is not a decimal number")
        }
        require(decimal.scale() <= IMPLIED_DECIMALS) {
            "price '$text' has more than $IMPLIED_DECIMALS decimal places"
        }
        return try {
            decimal.setScale(IMPLIED_DECIMALS, RoundingMode.UNNECESSARY).unscaledValue().longValueExact()
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("price '$text' is out of range")
        }
    }

    /** Renders a fixed-point price, trimming trailing zeros but keeping at least two decimals. */
    fun format(price: Long): String {
        val decimal = BigDecimal.valueOf(price, IMPLIED_DECIMALS).stripTrailingZeros()
        val scale = if (decimal.scale() < 2) 2 else decimal.scale()
        return decimal.setScale(scale, RoundingMode.UNNECESSARY).toPlainString()
    }

    /** True when [price] sits exactly on a tick of the given geometry. */
    fun isOnTick(price: Long, priceFloor: Long, tickSize: Long): Boolean =
        tickSize > 0 && (price - priceFloor) % tickSize == 0L
}
