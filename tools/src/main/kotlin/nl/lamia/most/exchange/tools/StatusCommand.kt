package nl.lamia.most.exchange.tools

import nl.lamia.most.exchange.client.AeronGatewayLink
import nl.lamia.most.exchange.client.GatewayEndpoint
import nl.lamia.most.exchange.client.OrderEntryListener
import nl.lamia.most.exchange.client.OrderEntrySession
import nl.lamia.most.exchange.client.OrderFate
import nl.lamia.most.exchange.client.ParticipantRequests
import nl.lamia.most.exchange.sbe.ClientExecutionReportDecoder
import nl.lamia.most.exchange.sbe.ExecType
import nl.lamia.most.exchange.sbe.RequestStatus
import org.agrona.concurrent.SleepingIdleStrategy
import java.time.Duration

/**
 * `most status`: every open order of one participant, as the engine holds it now (Design.md §5,
 * "Order mass status").
 *
 * Answered, unlike `cancel-all`: one `ORDER_STATUS` report per open order, then the completion with
 * the participant's `nextSeq`, which is where its report sequence stands at the moment the status
 * was taken. The reconciliation a participant runs at the open, or after a resend answered
 * `TRUNCATED`. Sent through the participant's own gateway, which checks it as it checks a cancel.
 */
fun runStatus(args: Args) {
    val config = ToolsConfig.from(args)
    val participantId = args.requiredLong("participant")
    val symbol = args.optional("symbol")
    val shardId = args.int("shard", 0)
    val requestId = args.long("request-id", System.currentTimeMillis())

    val aeron = connect(config) ?: return
    aeron.use {
        val directory = awaitDirectory(aeron, config, Duration.ofSeconds(args.long("timeout", 15L)))
        if (directory == null) {
            reportNoDirectory(config)
            return
        }
        var securityId = ParticipantRequests.ALL_SECURITIES
        val listed = if (symbol == null) {
            directory.shardRoute(shardId)
        } else {
            val security = directory.routeForSymbol(symbol)
            if (security == null) {
                System.err.println("most: unknown symbol '$symbol' -- try `most securities`")
                return
            }
            securityId = security.securityId
            directory.shardRoute(security.shardId)
        }
        if (listed == null) {
            System.err.println("most: shard $shardId is not in the directory -- try `most securities`")
            return
        }
        val route = GatewayOverride.from(args).applyTo(listed)

        // The SDK's session, as an adapter would hold it (docs/Adapters.md): it waits for both legs,
        // and the status is the one an adapter runs at the open, asked for explicitly here so that
        // `--symbol` can narrow it.
        val link = AeronGatewayLink(aeron, GatewayEndpoint.of(route))
        var done = false
        val session = OrderEntrySession(
            listOf(link), longArrayOf(participantId),
            object : OrderEntryListener {
                override fun onReport(report: ClientExecutionReportDecoder) {
                    if (report.execType() != ExecType.ORDER_STATUS) return
                    val name = directory.routeFor(report.securityId())?.symbol ?: "#${report.securityId()}"
                    println(formatReport(report, name))
                }

                override fun onSettled(participantId: Long, clOrdId: Long, fate: OrderFate) = Unit

                override fun onMassStatusComplete(participantId: Long, status: RequestStatus, orderCount: Int, nextSeq: Long) {
                    println(
                        "status: $status -- $orderCount open orders, " +
                            "as of reportSeq ${nextSeq - 1} (next report is $nextSeq)"
                    )
                    done = true
                }
            },
            OrderEntrySession.Config(massStatusOnStart = false, requestIdBase = requestId),
        )
        val idle = SleepingIdleStrategy(Duration.ofMillis(1).toNanos())
        val connectDeadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (!session.isReady && System.nanoTime() < connectDeadline) idle.idle(session.doWork())
        if (!session.isReady) {
            System.err.println("most: no gateway listening on ${route.orderEntryChannel}:${route.orderEntryStreamId}")
            return
        }
        session.requestMassStatus(participantId, securityId)
        println(
            "status: participant $participantId, " +
                (if (symbol == null) "every book on shard ${route.shardId}" else "$symbol (shard ${route.shardId})")
        )

        val deadline = System.nanoTime() + Duration.ofSeconds(args.long("follow", 5L)).toNanos()
        while (!done && System.nanoTime() < deadline) idle.idle(session.doWork())
        if (!done) System.err.println("most: no completion within the follow window; the list above may be partial")
        link.close()
    }
}
