package nl.lamia.most.exchange.tools

import nl.lamia.most.exchange.client.PriceCodec
import nl.lamia.most.exchange.client.RoutedSecurity
import nl.lamia.most.exchange.sbe.Side

/**
 * The run's parameters, resolved from the command line and validated before anything connects.
 *
 * Aeron-free on purpose: generation and pacing are the two things that decide whether a
 * measurement means anything, and both are testable without a cluster.
 */
data class LoadSpec(
    val count: Int,
    val delayNs: Long,
    val warmup: Int,
    val priceMin: Long,
    val priceMax: Long,
    val qtyMin: Long,
    val qtyMax: Long,
    val participantBase: Long,
    val participantCount: Int,
    val smpStrategy: Byte,
    val seed: Long,
    val clOrdIdBase: Long,
    val drainMs: Long,
    val intervalMs: Long,
    val histogramFile: String?,
) {
    /** Orders per second the schedule asks for; 0 when unpaced. */
    val targetRate: Double get() = if (delayNs <= 0L) 0.0 else 1e9 / delayNs

    companion object {
        const val DEFAULT_COUNT = 1_000_000
        const val DEFAULT_DELAY_US = 10L
        const val MAX_WARMUP = 50_000

        /**
         * `--delay-us` and `--rate` are two spellings of one knob. Accepting both and silently
         * preferring one would make a run's actual rate a matter of argument order.
         */
        fun delayNsFrom(args: Args): Long {
            require(!(args.has("delay-us") && args.has("rate"))) {
                "--delay-us and --rate set the same thing; pass one"
            }
            if (args.has("rate")) {
                val rate = args.requiredLong("rate")
                require(rate > 0L) { "--rate must be positive, got $rate" }
                return 1_000_000_000L / rate
            }
            val delayUs = args.long("delay-us", DEFAULT_DELAY_US)
            require(delayUs >= 0L) { "--delay-us must not be negative, got $delayUs" }
            return delayUs * 1_000L
        }

        fun from(args: Args, participantBase: Long): LoadSpec {
            val count = args.int("count", DEFAULT_COUNT)
            require(count > 0) { "--count must be positive, got $count" }

            val priceMin = PriceCodec.parse(args.required("price-min"))
            val priceMax = PriceCodec.parse(args.required("price-max"))
            require(priceMin > 0L) { "--price-min must be positive" }
            require(priceMax >= priceMin) {
                "--price-max ${PriceCodec.format(priceMax)} is below " +
                    "--price-min ${PriceCodec.format(priceMin)}"
            }

            val qtyMin = args.long("qty-min", 1L)
            val qtyMax = args.long("qty-max", 100L)
            require(qtyMin > 0L) { "--qty-min must be positive, got $qtyMin" }
            require(qtyMax >= qtyMin) { "--qty-max $qtyMax is below --qty-min $qtyMin" }

            val participantCount = args.int("participants", 4)
            require(participantCount > 0) {
                "--participants must be positive, got $participantCount"
            }

            val warmup = args.int("warmup", minOf(count / 10, MAX_WARMUP))
            require(warmup in 0..<count) {
                "--warmup $warmup must be between 0 and ${count - 1}"
            }

            return LoadSpec(
                count = count,
                delayNs = delayNsFrom(args),
                warmup = warmup,
                priceMin = priceMin,
                priceMax = priceMax,
                qtyMin = qtyMin,
                qtyMax = qtyMax,
                participantBase = participantBase,
                participantCount = participantCount,
                smpStrategy = OrderParser.parseStrategy(args.optional("smp")),
                seed = args.long("seed", 42L),
                clOrdIdBase = args.long("clordid-base", 1L),
                drainMs = args.long("drain-ms", 2_000L),
                intervalMs = args.long("interval-ms", 1_000L),
                histogramFile = args.optional("histogram"),
            )
        }
    }
}

/**
 * `xorshift64*`. Deterministic across JVMs and allocation-free, neither of which is guaranteed of
 * `java.util.Random`, and a run has to be reproducible to be worth comparing against another.
 */
class Xorshift64(seed: Long) {
    // Golden-ratio odd constant, standing in for a zero seed: xorshift is absorbing at zero.
    private var state = if (seed == 0L) GOLDEN_GAMMA else seed

    fun nextLong(): Long {
        var x = state
        x = x xor (x ushr 12)
        x = x xor (x shl 25)
        x = x xor (x ushr 27)
        state = x
        return x * GOLDEN_GAMMA
    }

    /** Uniform in `[0, bound)`. */
    fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive" }
        return ((nextLong() ushr 1) % bound).toInt()
    }

    /** Uniform in `[0, bound)`. */
    fun nextLong(bound: Long): Long {
        require(bound > 0L) { "bound must be positive" }
        return (nextLong() ushr 1) % bound
    }

    private companion object {
        const val GOLDEN_GAMMA = -0x61c8864680b583ebL
    }
}

/**
 * The preallocated orders, generated once before the run starts.
 *
 * Held as parallel primitive arrays rather than objects so the send loop reads five array slots and
 * allocates nothing: under load the generator must not be what is being measured. The arrays are a
 * pool cycled by `index and mask`, so a 10M-order run costs the same memory as a 1M-order one.
 */
class OrderPool(
    val size: Int,
    val securityId: IntArray,
    val side: ByteArray,
    val price: LongArray,
    val qty: LongArray,
    val participantId: LongArray,
) {
    private val mask = size - 1

    fun slotFor(sequence: Int): Int = sequence and mask

    companion object {
        const val MAX_POOL = 1 shl 20

        /** The pool is a power of two so slot selection is a mask, not a division. */
        fun poolSizeFor(count: Int): Int {
            var size = 1
            while (size < count && size < MAX_POOL) size = size shl 1
            return size
        }

        /**
         * Generates a pool of orders uniform over side, security, tick and quantity.
         *
         * Prices are drawn as **tick indices** and materialised as `floor + tick * index`, so being
         * on tick is true by construction. Deriving a price and then checking it would only tell us
         * afterwards that the run was going to be rejected.
         */
        fun generate(
            spec: LoadSpec,
            securities: List<RoutedSecurity>,
            count: Int = spec.count,
        ): OrderPool {
            require(securities.isNotEmpty()) { "at least one security is needed" }
            val size = poolSizeFor(count)
            val random = Xorshift64(spec.seed)

            val securityIds = IntArray(size)
            val sides = ByteArray(size)
            val prices = LongArray(size)
            val quantities = LongArray(size)
            val participants = LongArray(size)

            // Per-security tick geometry, resolved once rather than per order.
            val floors = LongArray(securities.size) { securities[it].priceFloor }
            val ticks = LongArray(securities.size) { securities[it].tickSize }
            // Clamped at the floor: a band reaching below the ladder is warned about, not
            // silently turned into prices the engine cannot index.
            val firstTick = IntArray(securities.size) {
                ticksBetween(floors[it], spec.priceMin, ticks[it]).coerceAtLeast(0)
            }
            val tickSpan = IntArray(securities.size) {
                ticksBetween(spec.priceMin, spec.priceMax, ticks[it]) + 1
            }

            val qtySpan = spec.qtyMax - spec.qtyMin + 1
            for (i in 0..<size) {
                val s = random.nextInt(securities.size)
                securityIds[i] = securities[s].securityId
                sides[i] = if (random.nextLong() < 0L) Side.BUY.value() else Side.SELL.value()
                prices[i] = floors[s] + ticks[s] * (firstTick[s] + random.nextInt(tickSpan[s]))
                quantities[i] = spec.qtyMin + random.nextLong(qtySpan)
                participants[i] =
                    spec.participantBase + random.nextInt(spec.participantCount)
            }
            return OrderPool(size, securityIds, sides, prices, quantities, participants)
        }

        /** Whole ticks from [from] up to [to], rounding down. */
        private fun ticksBetween(from: Long, to: Long, tickSize: Long): Int {
            require(tickSize > 0L) { "tick size must be positive, got $tickSize" }
            return ((to - from) / tickSize).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        }
    }
}

/**
 * Checks the price band against the geometry the directory carries.
 *
 * The static collar is *not* in the directory, so a band outside it can only be discovered from the
 * reject counts at the end of a run. The ladder range can be checked here, and it is the one that
 * turns into `PRICE_OUT_OF_LADDER` rather than a number.
 */
fun bandWarnings(spec: LoadSpec, securities: List<RoutedSecurity>): List<String> {
    val warnings = mutableListOf<String>()
    for (security in securities) {
        val ceiling = security.priceFloor + security.tickSize * (security.levelCount - 1L)
        if (spec.priceMin < security.priceFloor || spec.priceMax > ceiling) {
            warnings += "${security.symbol}: band ${PriceCodec.format(spec.priceMin)}.." +
                "${PriceCodec.format(spec.priceMax)} falls outside its ladder " +
                "${PriceCodec.format(security.priceFloor)}..${PriceCodec.format(ceiling)}"
        }
        if (!PriceCodec.isOnTick(spec.priceMin, security.priceFloor, security.tickSize)) {
            warnings += "${security.symbol}: --price-min ${PriceCodec.format(spec.priceMin)} " +
                "is not on its ${PriceCodec.format(security.tickSize)} tick; " +
                "orders will be generated from the tick below"
        }
        val span = (spec.priceMax - spec.priceMin) / security.tickSize
        if (span > WIDE_BAND_TICKS) {
            warnings += "${security.symbol}: a band $span ticks wide crosses rarely, so the book " +
                "fills and the run ends up measuring BOOK_CAPACITY rejects"
        }
    }
    return warnings
}

private const val WIDE_BAND_TICKS = 1_000L
