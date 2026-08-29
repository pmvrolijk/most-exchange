package com.engine.tools

import com.engine.reference.PriceCodec
import com.engine.reference.RoutedSecurity
import com.engine.sbe.ClientExecutionReportDecoder
import com.engine.sbe.MessageHeaderDecoder
import com.engine.sbe.MessageHeaderEncoder
import com.engine.sbe.NewOrderSingleEncoder
import com.engine.sbe.OrderCancelRequestEncoder
import com.engine.sbe.Side
import com.engine.sbe.SmpStrategy
import io.aeron.FragmentAssembler
import org.agrona.MutableDirectBuffer
import org.agrona.concurrent.SleepingIdleStrategy
import org.agrona.concurrent.UnsafeBuffer
import java.time.Duration

/** A parsed and locally validated order, before it is encoded. */
data class OrderRequest(
    val security: RoutedSecurity,
    val side: Byte,
    val price: Long,
    val qty: Long,
    val clOrdId: Long,
    val participantId: Long,
    val smpId: Long,
    val smpStrategy: Byte,
    val expireDate: Int,
)

object OrderParser {

    fun parseSide(text: String): Byte = when (text.lowercase()) {
        "buy", "b", "bid" -> Side.BUY.value()
        "sell", "s", "ask", "offer" -> Side.SELL.value()
        else -> throw IllegalArgumentException("side must be buy or sell, got '$text'")
    }

    fun parseStrategy(text: String?): Byte = when (text?.lowercase()) {
        null, "aggressor", "cancel-aggressor" -> SmpStrategy.CANCEL_AGGRESSOR.value()
        "resting", "cancel-resting" -> SmpStrategy.CANCEL_RESTING.value()
        else -> throw IllegalArgumentException(
            "smp strategy must be aggressor or resting, got '$text'"
        )
    }

    /**
     * Everything checkable without the directory: required options present, side and price
     * well-formed, quantity positive. Run before connecting, so a typo is reported as a typo
     * rather than as whatever the network happens to fail with first.
     */
    fun parseLocal(args: Args, participantId: Long): LocalOrder {
        val symbol = args.required("symbol")
        val side = parseSide(args.required("side"))
        val price = PriceCodec.parse(args.required("price"))
        val qty = args.requiredLong("qty")
        require(qty > 0) { "qty must be positive, got $qty" }

        return LocalOrder(
            symbol = symbol,
            side = side,
            price = price,
            qty = qty,
            clOrdId = args.long("clordid", System.currentTimeMillis()),
            participantId = participantId,
            smpId = args.long("smp-id", 0L),
            smpStrategy = parseStrategy(args.optional("smp")),
            expireDate = args.int("expire-date", 0),
        )
    }

    /**
     * Binds a locally valid order to its security. Tick alignment needs the geometry, which only
     * the directory carries; phase, collars and capacity depend on book state that only the
     * engine has, and duplicating those here would mean two places to get them wrong.
     */
    fun bind(local: LocalOrder, security: RoutedSecurity): OrderRequest {
        require(PriceCodec.isOnTick(local.price, security.priceFloor, security.tickSize)) {
            "price ${PriceCodec.format(local.price)} is not a multiple of " +
                "${security.symbol}'s ${PriceCodec.format(security.tickSize)} tick"
        }
        return OrderRequest(
            security = security,
            side = local.side,
            price = local.price,
            qty = local.qty,
            clOrdId = local.clOrdId,
            participantId = local.participantId,
            smpId = local.smpId,
            smpStrategy = local.smpStrategy,
            expireDate = local.expireDate,
        )
    }
}

/** A locally valid order, not yet bound to a security from the directory. */
data class LocalOrder(
    val symbol: String,
    val side: Byte,
    val price: Long,
    val qty: Long,
    val clOrdId: Long,
    val participantId: Long,
    val smpId: Long,
    val smpStrategy: Byte,
    val expireDate: Int,
)

fun encodeNewOrder(buffer: MutableDirectBuffer, request: OrderRequest): Int {
    NewOrderSingleEncoder().wrapAndApplyHeader(buffer, 0, MessageHeaderEncoder())
        .participantId(request.participantId)
        .clOrdId(request.clOrdId)
        .price(request.price)
        .qty(request.qty)
        .smpId(request.smpId)
        .securityId(request.security.securityId)
        .expireDate(request.expireDate)
        .side(Side.get(request.side))
        .smpStrategy(SmpStrategy.get(request.smpStrategy))
    return MessageHeaderEncoder.ENCODED_LENGTH + NewOrderSingleEncoder.BLOCK_LENGTH
}

fun encodeCancel(
    buffer: MutableDirectBuffer,
    security: RoutedSecurity,
    participantId: Long,
    origClOrdId: Long,
    clOrdId: Long,
    exchangeOrderId: Long,
    side: Byte,
): Int {
    OrderCancelRequestEncoder().wrapAndApplyHeader(buffer, 0, MessageHeaderEncoder())
        .participantId(participantId)
        .origClOrdId(origClOrdId)
        .clOrdId(clOrdId)
        .exchangeOrderId(exchangeOrderId)
        .securityId(security.securityId)
        .side(Side.get(side))
    return MessageHeaderEncoder.ENCODED_LENGTH + OrderCancelRequestEncoder.BLOCK_LENGTH
}

/** Formats one execution report as the operator wants to read it. */
fun formatReport(decoder: ClientExecutionReportDecoder, symbol: String): String {
    val execType = decoder.execType().name
    val reject = decoder.rejectReason()
    val detail = when (execType) {
        "TRADE" -> "last ${decoder.lastQty()} @ ${PriceCodec.format(decoder.price())} " +
            "cum ${decoder.cumQty()} leaves ${decoder.leavesQty()}"
        "REJECTED" -> "reason $reject"
        else -> "leaves ${decoder.leavesQty()} cum ${decoder.cumQty()} of ${decoder.origQty()}"
    }
    return "  $execType  $symbol  orderId=${decoder.exchangeOrderId()} " +
        "clOrdId=${decoder.clOrdId()}  $detail"
}

private fun sendAndFollow(
    args: Args,
    build: (RoutedSecurity, MutableDirectBuffer) -> Int,
    resolveSymbol: String,
) {
    val config = ToolsConfig.from(args)
    val aeron = connect(config) ?: return
    aeron.use {
        val directory = awaitDirectory(aeron, config, Duration.ofSeconds(args.long("timeout", 15L)))
        if (directory == null) {
            reportNoDirectory(config)
            return
        }
        val security = directory.routeForSymbol(resolveSymbol)
        if (security == null) {
            System.err.println("most: unknown symbol '$resolveSymbol' -- try `most securities`")
            return
        }

        // Listen before sending, so a fast acknowledgement is not missed.
        val reports = aeron.addSubscription(
            security.executionReportChannel, security.executionReportStreamId,
        )
        val orders = aeron.addPublication(security.orderEntryChannel, security.orderEntryStreamId)

        val idle = SleepingIdleStrategy(Duration.ofMillis(1).toNanos())
        val connectDeadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (!orders.isConnected && System.nanoTime() < connectDeadline) idle.idle(0)
        if (!orders.isConnected) {
            System.err.println(
                "most: no gateway listening on ${security.orderEntryChannel}:" +
                    "${security.orderEntryStreamId} for ${security.symbol}"
            )
            return
        }

        val buffer = UnsafeBuffer(ByteArray(512))
        val length = build(security, buffer)
        if (orders.offer(buffer, 0, length) < 0) {
            System.err.println("most: gateway did not accept the message (backpressure or closed)")
            return
        }
        println("sent to shard ${security.shardId} via ${security.orderEntryChannel}")

        val header = MessageHeaderDecoder()
        val decoder = ClientExecutionReportDecoder()
        val assembler = FragmentAssembler { buf, offset, len, _ ->
            if (len >= MessageHeaderDecoder.ENCODED_LENGTH) {
                header.wrap(buf, offset)
                if (header.templateId() == ClientExecutionReportDecoder.TEMPLATE_ID) {
                    decoder.wrap(
                        buf, offset + MessageHeaderDecoder.ENCODED_LENGTH,
                        header.blockLength(), header.version(),
                    )
                    if (decoder.participantId() == config.participantId) {
                        println(formatReport(decoder, security.symbol))
                    }
                }
            }
        }

        val follow = Duration.ofSeconds(args.long("follow", 3L))
        val deadline = System.nanoTime() + follow.toNanos()
        while (System.nanoTime() < deadline) idle.idle(reports.poll(assembler, FRAGMENT_LIMIT))
    }
}

fun runSend(args: Args) {
    val config = ToolsConfig.from(args)
    // Validate before touching the network: a bad price should report a bad price.
    val local = OrderParser.parseLocal(args, config.participantId)
    sendAndFollow(
        args,
        build = { security, buffer ->
            val request = OrderParser.bind(local, security)
            println(
                "sending ${if (request.side == Side.BUY.value()) "buy" else "sell"} ${request.qty} " +
                    "${security.symbol} @ ${PriceCodec.format(request.price)} " +
                    "clOrdId=${request.clOrdId}"
            )
            encodeNewOrder(buffer, request)
        },
        resolveSymbol = local.symbol,
    )
}

fun runCancel(args: Args) {
    val config = ToolsConfig.from(args)
    val symbol = args.required("symbol")
    val side = OrderParser.parseSide(args.required("side"))
    val origClOrdId = args.requiredLong("orig-clordid")
    val exchangeOrderId = args.requiredLong("order-id")
    val clOrdId = args.long("clordid", System.currentTimeMillis())

    sendAndFollow(
        args,
        build = { security, buffer ->
            println("cancelling orderId=$exchangeOrderId on ${security.symbol}")
            encodeCancel(
                buffer, security, config.participantId,
                origClOrdId = origClOrdId, clOrdId = clOrdId,
                exchangeOrderId = exchangeOrderId, side = side,
            )
        },
        resolveSymbol = symbol,
    )
}
