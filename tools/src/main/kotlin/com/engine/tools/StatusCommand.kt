package com.engine.tools

import com.engine.reference.OperatorCommands
import com.engine.reference.ParticipantRequests
import com.engine.sbe.ClientExecutionReportDecoder
import com.engine.sbe.ExecType
import com.engine.sbe.MessageHeaderDecoder
import com.engine.sbe.OrderMassStatusCompleteDecoder
import io.aeron.FragmentAssembler
import org.agrona.concurrent.SleepingIdleStrategy
import org.agrona.concurrent.UnsafeBuffer
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
        var securityId = OperatorCommands.ALL_SECURITIES
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

        val reports = aeron.addSubscription(route.executionReportChannel, route.executionReportStreamId)
        val requests = aeron.addPublication(route.orderEntryChannel, route.orderEntryStreamId)
        val idle = SleepingIdleStrategy(Duration.ofMillis(1).toNanos())
        val connectDeadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        // Both legs, for the reason `send` waits on both: the answer can beat a subscription that
        // is still coming up.
        while ((!requests.isConnected || !reports.isConnected) && System.nanoTime() < connectDeadline) {
            idle.idle(0)
        }
        if (!requests.isConnected) {
            System.err.println("most: no gateway listening on ${route.orderEntryChannel}:${route.orderEntryStreamId}")
            return
        }

        val buffer = UnsafeBuffer(ByteArray(64))
        val length = ParticipantRequests.encodeOrderMassStatusRequest(buffer, 0, participantId, requestId, securityId)
        if (requests.offer(buffer, 0, length) < 0) {
            System.err.println("most: gateway did not accept the request (backpressure or closed)")
            return
        }
        println(
            "status: participant $participantId, " +
                (if (symbol == null) "every book on shard ${route.shardId}" else "$symbol (shard ${route.shardId})")
        )

        val header = MessageHeaderDecoder()
        val report = ClientExecutionReportDecoder()
        val completion = OrderMassStatusCompleteDecoder()
        var done = false
        val assembler = FragmentAssembler { buf, offset, len, _ ->
            if (len < MessageHeaderDecoder.ENCODED_LENGTH) return@FragmentAssembler
            header.wrap(buf, offset)
            val body = offset + MessageHeaderDecoder.ENCODED_LENGTH
            when (header.templateId()) {
                ClientExecutionReportDecoder.TEMPLATE_ID -> {
                    report.wrap(buf, body, header.blockLength(), header.version())
                    if (report.participantId() == participantId && report.execType() == ExecType.ORDER_STATUS) {
                        val name = directory.routeFor(report.securityId())?.symbol ?: "#${report.securityId()}"
                        println(formatReport(report, name))
                    }
                }
                OrderMassStatusCompleteDecoder.TEMPLATE_ID -> {
                    completion.wrap(buf, body, header.blockLength(), header.version())
                    if (completion.participantId() == participantId && completion.requestId() == requestId) {
                        println(
                            "status: ${completion.status()} -- ${completion.orderCount()} open orders, " +
                                "as of reportSeq ${completion.nextSeq() - 1} (next report is ${completion.nextSeq()})"
                        )
                        done = true
                    }
                }
            }
        }

        val deadline = System.nanoTime() + Duration.ofSeconds(args.long("follow", 5L)).toNanos()
        while (!done && System.nanoTime() < deadline) idle.idle(reports.poll(assembler, FRAGMENT_LIMIT))
        if (!done) System.err.println("most: no completion within the follow window; the list above may be partial")
    }
}
