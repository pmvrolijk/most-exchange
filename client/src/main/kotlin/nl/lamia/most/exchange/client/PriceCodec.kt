package nl.lamia.most.exchange.client

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

    /** What [fromDecimal] answers for a decimal it cannot represent exactly. Never a valid value. */
    const val INVALID = Long.MIN_VALUE

    /**
     * A decimal [mantissa] × 10^-[scale], as Artio's `DecimalFloat` holds a FIX price or quantity, at
     * the implied scale; [INVALID] when that is not exact or does not fit (docs/Adapters.md §2). Never
     * rounds, and allocates nothing. The other way needs no conversion: a fixed-point value is the
     * mantissa at scale [IMPLIED_DECIMALS].
     */
    fun fromDecimal(mantissa: Long, scale: Int): Long {
        if (scale < 0 || mantissa == Long.MIN_VALUE) return INVALID
        if (mantissa == 0L) return 0L
        if (scale <= IMPLIED_DECIMALS) {
            val factor = POWERS_OF_TEN[IMPLIED_DECIMALS - scale]
            val limit = Long.MAX_VALUE / factor
            return if (mantissa > limit || mantissa < -limit) INVALID else mantissa * factor
        }
        val excess = scale - IMPLIED_DECIMALS
        if (excess >= POWERS_OF_TEN.size) return INVALID // |mantissa| < 10^19: nothing that small is exact
        val divisor = POWERS_OF_TEN[excess]
        return if (mantissa % divisor != 0L) INVALID else mantissa / divisor
    }

    private val POWERS_OF_TEN = LongArray(19).also { p ->
        p[0] = 1L
        for (i in 1..<p.size) p[i] = p[i - 1] * 10L
    }

    /** True when [price] sits exactly on a tick of the given geometry. */
    fun isOnTick(price: Long, priceFloor: Long, tickSize: Long): Boolean =
        tickSize > 0 && (price - priceFloor) % tickSize == 0L
}
