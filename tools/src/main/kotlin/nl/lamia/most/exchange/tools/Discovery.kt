package nl.lamia.most.exchange.tools

import nl.lamia.most.exchange.client.DirectoryClient
import nl.lamia.most.exchange.client.RoutedSecurity
import io.aeron.Aeron
import io.aeron.FragmentAssembler
import org.agrona.concurrent.SleepingIdleStrategy
import java.time.Duration

/** Connection settings shared by every subcommand. */
data class ToolsConfig(
    val aeronDirectoryName: String?,
    val discoveryChannel: String,
    val discoveryStreamId: Int,
    val l1Channel: String,
    val l1StreamId: Int,
    val l2Channel: String,
    val l2StreamId: Int,
    val snapshotChannel: String,
    val snapshotStreamId: Int,
    val participantId: Long,
) {
    companion object {
        fun from(args: Args) = ToolsConfig(
            aeronDirectoryName = args.optional("aeron-dir"),
            discoveryChannel = args.optional("discovery-channel")
                ?: "aeron:udp?endpoint=239.10.0.1:40000",
            discoveryStreamId = args.int("discovery-stream", 100),
            l1Channel = args.optional("l1-channel") ?: "aeron:udp?endpoint=239.10.1.1:40001",
            l1StreamId = args.int("l1-stream", 1),
            l2Channel = args.optional("l2-channel") ?: "aeron:udp?endpoint=239.10.1.2:40002",
            l2StreamId = args.int("l2-stream", 2),
            // The recovery feed. Without it a book can only be built by having been listening
            // since the security opened, which is not a thing an operator running a CLI has done.
            snapshotChannel = args.optional("snapshot-channel")
                ?: "aeron:udp?endpoint=239.10.1.4:40004",
            snapshotStreamId = args.int("snapshot-stream", 4),
            participantId = args.long("participant", 1L),
        )
    }
}

/**
 * Waits for a complete directory broadcast before doing anything else.
 *
 * Every tool needs the routing table first: the CLI is told a symbol, and only the directory
 * knows which shard serves it and which gateway to send to. Because discovery repeats on a cycle
 * rather than answering requests, "connect" here means "listen until a whole cycle arrives" — at
 * most one interval, and a truncated cycle simply does not count.
 */
fun awaitDirectory(aeron: Aeron, config: ToolsConfig, timeout: Duration): DirectoryClient? {
    val client = DirectoryClient()
    val subscription = aeron.addSubscription(config.discoveryChannel, config.discoveryStreamId)
    val assembler = FragmentAssembler { buffer, offset, length, _ ->
        client.onDirectoryMessage(buffer, offset, length)
    }
    val idle = SleepingIdleStrategy(Duration.ofMillis(1).toNanos())
    val deadline = System.nanoTime() + timeout.toNanos()

    while (System.nanoTime() < deadline) {
        idle.idle(subscription.poll(assembler, FRAGMENT_LIMIT))
        if (client.isReady) return client
    }
    return null
}

fun connect(config: ToolsConfig): Aeron? {
    val context = Aeron.Context()
    config.aeronDirectoryName?.let(context::aeronDirectoryName)
    return try {
        Aeron.connect(context)
    } catch (e: io.aeron.exceptions.DriverTimeoutException) {
        System.err.println(
            "most: no Aeron media driver found (${e.message}).\n" +
                "  Start one, or pass --aeron-dir pointing at a running driver."
        )
        null
    }
}

fun reportNoDirectory(config: ToolsConfig) {
    System.err.println(
        "most: no directory received on ${config.discoveryChannel}:${config.discoveryStreamId}.\n" +
            "  Is the discovery process running? It broadcasts on a cycle, so allow one interval."
    )
}

fun describe(security: RoutedSecurity): String =
    "${security.symbol} (${security.isin}) shard ${security.shardId}"

const val FRAGMENT_LIMIT = 32
