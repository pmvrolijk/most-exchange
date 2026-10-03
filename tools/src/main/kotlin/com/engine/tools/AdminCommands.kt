package com.engine.tools

import com.engine.reference.OperatorCommands
import com.engine.reference.PriceCodec
import com.engine.reference.ShardRoute
import org.agrona.MutableDirectBuffer
import org.agrona.concurrent.SleepingIdleStrategy
import org.agrona.concurrent.UnsafeBuffer
import java.time.Duration
import java.time.LocalDate

fun parsePhase(text: String): Byte = OperatorCommands.parsePhase(text)

fun todayAsTradingDate(): Int = OperatorCommands.tradingDateOf(LocalDate.now())

/**
 * Operator commands go to the gateway like any other message and pass through untouched -- but only
 * a gateway whose registry entry has `operator=true` forwards them (Design.md §1), so
 * `--order-entry-channel` / `--order-entry-stream` exist to address one the directory does not name.
 */
private fun sendToShard(
    args: Args,
    resolveShard: (com.engine.reference.DirectoryClient) -> ShardRoute?,
    build: (MutableDirectBuffer) -> Int,
    describe: (ShardRoute) -> String,
) {
    val config = ToolsConfig.from(args)
    val aeron = connect(config) ?: return
    aeron.use {
        val directory = awaitDirectory(aeron, config, Duration.ofSeconds(args.long("timeout", 15L)))
        if (directory == null) {
            reportNoDirectory(config)
            return
        }
        val listed = resolveShard(directory)
        if (listed == null) {
            System.err.println("most: no such shard in the directory")
            return
        }
        val route = GatewayOverride.from(args).applyTo(listed)

        val publication = aeron.addPublication(route.orderEntryChannel, route.orderEntryStreamId)
        val idle = SleepingIdleStrategy(Duration.ofMillis(1).toNanos())
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (!publication.isConnected && System.nanoTime() < deadline) idle.idle(0)
        if (!publication.isConnected) {
            System.err.println(
                "most: no gateway listening on ${route.orderEntryChannel}:${route.orderEntryStreamId}"
            )
            return
        }

        val buffer = UnsafeBuffer(ByteArray(512))
        val length = build(buffer)
        if (publication.offer(buffer, 0, length) < 0) {
            System.err.println("most: gateway did not accept the command")
            return
        }
        println(describe(route))
        // The command is asynchronous; give the gateway a moment to forward it before exiting.
        Thread.sleep(args.long("settle", 500L))
    }
}

/**
 * Seeds or re-seeds a security's reference price and collar widths. This is also the operator's
 * lever for reopening a security after a volatility halt whose price sits outside the static
 * band (Design.md §4.6).
 */
fun runDefine(args: Args) {
    val symbol = args.required("symbol")
    val reference = PriceCodec.parse(args.required("reference"))
    val staticBps = args.int("static-collar", 0)
    val dynamicBps = args.int("dynamic-collar", 0)

    var securityId = 0
    var shardId = -1
    var priceFloor = 0L
    var tickSize = 0L
    var levelCount = 0

    sendToShard(
        args,
        resolveShard = { directory ->
            val security = directory.routeForSymbol(symbol)
            if (security == null) {
                System.err.println("most: unknown symbol '$symbol' -- try `most securities`")
                null
            } else {
                securityId = security.securityId
                shardId = security.shardId
                priceFloor = security.priceFloor
                tickSize = security.tickSize
                levelCount = security.levelCount
                directory.shardRoute(security.shardId)
            }
        },
        build = { buffer ->
            OperatorCommands.encodeSecurityDefinition(
                buffer,
                securityId = securityId,
                referencePrice = reference,
                priceFloor = priceFloor,
                tickSize = tickSize,
                levelCount = levelCount,
                staticCollarBps = staticBps,
                dynamicCollarBps = dynamicBps,
            )
        },
        describe = {
            "defined $symbol on shard $shardId: reference ${PriceCodec.format(reference)}, " +
                "static ${staticBps}bps, dynamic ${dynamicBps}bps"
        },
    )
}

/** Session transitions are shard-wide: the engine applies the phase to every book it hosts. */
fun runSession(args: Args) {
    val phase = parsePhase(args.required("phase"))
    val tradingDate = args.int("trading-date", todayAsTradingDate())
    val shardId = args.int("shard", 0)

    sendToShard(
        args,
        resolveShard = { it.shardRoute(shardId) },
        build = { buffer ->
            OperatorCommands.encodeSessionTransition(buffer, phase, tradingDate)
        },
        describe = { "shard $shardId -> ${args.required("phase")} (trading date $tradingDate)" },
    )
}

/**
 * Cancels every resting order of one participant (Design.md §4.8): on one security with
 * `--symbol`, or on every book of `--shard` without it.
 *
 * `--participant` is required here, unlike everywhere else it defaults to 1: a default would make
 * a forgotten flag cancel somebody's whole book. The operator side of revocation -- publish the
 * participant's move to `cancelOnly` first, then send this, or an order can land between the two.
 */
fun runCancelAll(args: Args) {
    val participantId = args.requiredLong("participant")
    val symbol = args.optional("symbol")
    val shardId = args.int("shard", 0)
    var securityId = OperatorCommands.ALL_SECURITIES
    var resolvedShard = shardId

    sendToShard(
        args,
        resolveShard = { directory ->
            if (symbol == null) {
                directory.shardRoute(shardId)
            } else {
                val security = directory.routeForSymbol(symbol)
                if (security == null) {
                    System.err.println("most: unknown symbol '$symbol' -- try `most securities`")
                    null
                } else {
                    securityId = security.securityId
                    resolvedShard = security.shardId
                    directory.shardRoute(security.shardId)
                }
            }
        },
        build = { buffer ->
            OperatorCommands.encodeCancelParticipantOrders(buffer, participantId, securityId)
        },
        describe = {
            "participant $participantId: every resting order on " +
                (if (symbol == null) "shard $resolvedShard" else "$symbol (shard $resolvedShard)") +
                " sent for cancellation; nothing acknowledges it -- each order leaves the book as an " +
                "ordinary cancel"
        },
    )
}

/** The off-session expiry sweep (Design.md §4.3), run well before PRE_OPEN. */
/**
 * Asks the shard to republish every book as a level image.
 *
 * For a market data process that restarted while the engine kept running: depth is derived from
 * the book event stream, so a restarted one has no book and nothing to rebuild from. Changes no
 * book and moves no market, so it is safe to repeat.
 */
fun runImage(args: Args) {
    val shardId = args.int("shard", 0)

    sendToShard(
        args,
        resolveShard = { it.shardRoute(shardId) },
        build = { buffer -> OperatorCommands.encodeRequestBookImage(buffer) },
        describe = { "shard $shardId asked to republish its books" },
    )
}

fun runPurge(args: Args) {
    val tradingDate = args.int("trading-date", todayAsTradingDate())
    val shardId = args.int("shard", 0)

    sendToShard(
        args,
        resolveShard = { it.shardRoute(shardId) },
        build = { buffer ->
            OperatorCommands.encodePurgeExpiredOrders(buffer, tradingDate)
        },
        describe = { "shard $shardId purged for trading date $tradingDate" },
    )
}
