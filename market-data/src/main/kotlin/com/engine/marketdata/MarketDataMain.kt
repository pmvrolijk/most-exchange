package com.engine.marketdata

import com.engine.reference.ShardSpec
import io.aeron.Aeron
import io.aeron.FragmentAssembler
import org.agrona.concurrent.BusySpinIdleStrategy
import org.agrona.concurrent.ShutdownSignalBarrier
import org.agrona.concurrent.UnsafeBuffer
import java.io.File
import java.util.Properties

/**
 * Consumes the engine's book event stream and republishes L1, L2 and L3.
 *
 * A separate process on purpose (Design.md §1): feed fan-out never touches the matching thread,
 * and a slow feed consumer cannot reach back into the engine.
 *
 * Like the engine, this attaches to an already-running media driver rather than embedding one.
 */
fun main(args: Array<String>) {
    val config = try {
        MarketDataConfig.load(args.firstOrNull())
    } catch (e: IllegalArgumentException) {
        System.err.println("market-data: ${e.message}")
        return
    } catch (e: IllegalStateException) {
        System.err.println("market-data: ${e.message}")
        return
    }

    println(
        "market-data: shard=${config.shard.shardId} fingerprint=${config.shard.fingerprint()} " +
            "securities=${config.shard.securities.map { it.symbol }} " +
            "in=${config.bookEventChannel}:${config.bookEventStreamId} " +
            "l1=${config.l1Channel} l2=${config.l2Channel} l3=${config.l3Channel}"
    )

    val aeronContext = Aeron.Context()
    config.aeronDirectoryName?.let(aeronContext::aeronDirectoryName)

    val aeron = try {
        Aeron.connect(aeronContext)
    } catch (e: io.aeron.exceptions.DriverTimeoutException) {
        System.err.println(
            "market-data: no Aeron media driver found (${e.message}).\n" +
                "  Start the driver first and point both at the same Aeron directory."
        )
        return
    }

    aeron.use {
        val subscription = aeron.addSubscription(config.bookEventChannel, config.bookEventStreamId)
        val l3 = aeron.addPublication(config.l3Channel, config.l3StreamId)
        val l2 = aeron.addPublication(config.l2Channel, config.l2StreamId)
        val l1 = aeron.addPublication(config.l1Channel, config.l1StreamId)

        val publisher =
            AeronFeedPublisher(config.shard.shardId, l3, l2, l1, UnsafeBuffer(ByteArray(1024)))
        val service = MarketDataService(config.shard.shardId, config.securities(), publisher)
        val assembler = FragmentAssembler { buffer, offset, length, _ ->
            service.onBookEvent(buffer, offset, length)
        }

        val idle = BusySpinIdleStrategy()
        val barrier = ShutdownSignalBarrier()
        println("market-data: started")

        val worker = Thread({
            while (!Thread.currentThread().isInterrupted) {
                idle.idle(subscription.poll(assembler, FRAGMENT_LIMIT))
            }
        }, "market-data-poller")
        worker.start()

        barrier.use {
            it.await()
            worker.interrupt()
            // Inside the barrier: closing it releases the signal and the JVM exits at once.
            println(
                "market-data: stopped. gaps=${service.gapsDetected} " +
                    "missed=${service.eventsMissed} " +
                    "foreignShard=${service.foreignShardEvents} " +
                    "droppedL1=${publisher.droppedL1} droppedL2=${publisher.droppedL2} " +
                    "droppedL3=${publisher.droppedL3}"
            )
            System.out.flush()
        }
        worker.join(SHUTDOWN_TIMEOUT_MS)
    }
}

private const val FRAGMENT_LIMIT = 64
private const val SHUTDOWN_TIMEOUT_MS = 5_000L

data class MarketDataConfig(
    val shard: ShardSpec,
    val aeronDirectoryName: String?,
    val bookEventChannel: String,
    val bookEventStreamId: Int,
    val l1Channel: String,
    val l1StreamId: Int,
    val l2Channel: String,
    val l2StreamId: Int,
    val l3Channel: String,
    val l3StreamId: Int,
) {
    /**
     * Depth geometry comes from the shard's own security file, so the price that becomes ladder
     * level N in the engine becomes depth level N here by construction rather than by agreement.
     */
    fun securities(): List<MarketDataSecurity> = shard.securities.map {
        MarketDataSecurity(it.securityId, it.priceFloor, it.tickSize, it.levelCount)
    }

    companion object {
        const val SECURITIES_FILE = "md.securitiesFile"

        fun from(properties: Properties, shard: ShardSpec): MarketDataConfig = MarketDataConfig(
            shard = shard,
            aeronDirectoryName = properties.getProperty("md.aeronDir"),
            bookEventChannel = properties.getProperty("md.bookEvent.channel") ?: "aeron:ipc",
            bookEventStreamId = properties.getProperty("md.bookEvent.streamId")?.toInt() ?: 12,
            l1Channel = properties.getProperty("md.l1.channel")
                ?: "aeron:udp?endpoint=239.10.1.1:40001",
            l1StreamId = properties.getProperty("md.l1.streamId")?.toInt() ?: 1,
            l2Channel = properties.getProperty("md.l2.channel")
                ?: "aeron:udp?endpoint=239.10.1.2:40002",
            l2StreamId = properties.getProperty("md.l2.streamId")?.toInt() ?: 2,
            l3Channel = properties.getProperty("md.l3.channel")
                ?: "aeron:udp?endpoint=239.10.1.3:40003",
            l3StreamId = properties.getProperty("md.l3.streamId")?.toInt() ?: 3,
        )

        fun load(path: String?): MarketDataConfig {
            val properties = Properties()
            if (path != null) {
                val file = File(path)
                require(file.isFile) { "config file not found: $path" }
                file.inputStream().use(properties::load)
            }
            for ((key, value) in System.getProperties()) {
                val name = key as String
                if (name.startsWith("md.")) properties.setProperty(name, value as String)
            }
            val securitiesFile = properties.getProperty(SECURITIES_FILE)
                ?: error("missing required configuration key: $SECURITIES_FILE")
            return from(properties, ShardSpec.load(securitiesFile))
        }
    }
}
