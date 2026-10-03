package nl.lamia.most.exchange.marketdata

import nl.lamia.most.exchange.reference.DutyCycles
import nl.lamia.most.exchange.reference.IdleStrategySpec
import nl.lamia.most.exchange.reference.ShardSpec
import io.aeron.Aeron
import io.aeron.FragmentAssembler
import org.agrona.concurrent.ShutdownSignalBarrier
import org.agrona.concurrent.SystemNanoClock
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
            "l1=${config.l1Channel} l2=${config.l2Channel} l3=${config.l3Channel} " +
            "snapshot=${config.snapshotChannel} every ${config.snapshotCycleMs}ms"
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
        val snapshot = aeron.addPublication(config.snapshotChannel, config.snapshotStreamId)

        // The one place Aeron and the encoders meet. `offer` returning a negative value is a
        // drop for every feed here -- there is no back-channel to a multicast group and blocking
        // would be the coupling this process exists to avoid.
        val publisher = SbeFeedPublisher(
            shardId = config.shard.shardId,
            l3 = FeedSink { buffer, offset, length -> l3.offer(buffer, offset, length) >= 0 },
            l2 = FeedSink { buffer, offset, length -> l2.offer(buffer, offset, length) >= 0 },
            l1 = FeedSink { buffer, offset, length -> l1.offer(buffer, offset, length) >= 0 },
            snapshot = FeedSink { buffer, offset, length -> snapshot.offer(buffer, offset, length) >= 0 },
            buffer = UnsafeBuffer(ByteArray(1024)),
        )
        val service = MarketDataService(config.shard.shardId, config.securities(), publisher)
        val assembler = FragmentAssembler { buffer, offset, length, _ ->
            service.onBookEvent(buffer, offset, length)
        }

        // The one metric this process has: how full its single poll thread is, since it consumes
        // every book event the engine publishes (Design.md §7, "Duty cycle").
        val duty = if (config.metricsEnabled) DutyCycles(SystemNanoClock.INSTANCE) else null
        val idle = config.idleStrategy.create().let { duty?.wrap("market-data", it) ?: it }
        duty?.attachInBackground(aeron, { println("market-data: duty cycle counters $it") })
        println("market-data: idle strategy ${config.idleStrategy}")
        val barrier = ShutdownSignalBarrier()
        println("market-data: started")

        // One security per slice, so a full cycle takes `snapshotCycleMs` however many securities
        // the shard hosts: what a late joiner waits is one cycle, not one cycle per book.
        val sliceMs = (config.snapshotCycleMs / maxOf(1, config.securities().size)).coerceAtLeast(1L)

        val worker = Thread({
            // Wall clock, deliberately. This process is not the deterministic state machine -- it
            // derives a feed and holds no replicated state -- so a clock here costs nothing the
            // engine's ban on one is protecting.
            var nextSnapshot = System.currentTimeMillis() + sliceMs
            while (!Thread.currentThread().isInterrupted) {
                var work = subscription.poll(assembler, FRAGMENT_LIMIT)
                val now = System.currentTimeMillis()
                if (now >= nextSnapshot) {
                    nextSnapshot = now + sliceMs
                    // On the poll thread by design: the image and the sequence stamped on it are
                    // consistent only while no book event can land between them.
                    if (service.publishNextSnapshot() >= 0) work++
                }
                idle.idle(work)
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
                    "droppedL3=${publisher.droppedL3} " +
                    "snapshots=${service.snapshotsPublished} " +
                    "droppedSnapshot=${publisher.droppedSnapshot} " +
                    // A process that restarted and has no book expects imagesApplied to be
                    // non-zero. Zero with a book that stayed empty is the tell that the engine
                    // never sent one, which is a different problem from a feed that is quiet.
                    "imagesApplied=${service.imagesApplied} " +
                    "imagesDiscarded=${service.imagesDiscarded} " +
                    "imageOutOfBand=${service.imageMessagesOutOfBand}"
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
    val snapshotChannel: String,
    val snapshotStreamId: Int,
    /**
     * How long a full pass over this shard's books takes. It is the worst case a joining
     * subscriber waits before it can trust a book, and the interval over which a gap is repaired.
     */
    val snapshotCycleMs: Long,
    /** Publishes the poll thread's duty cycle as an Aeron counter. Off unless asked for. */
    val metricsEnabled: Boolean = false,
    /** The poll thread's idle strategy; busy-spin unless configured (Design.md §7, "Duty cycle"). */
    val idleStrategy: IdleStrategySpec = IdleStrategySpec.BUSY_SPIN,
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
        const val METRICS_ENABLED = "md.metrics"
        const val IDLE_STRATEGY = "md.idleStrategy"

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
            snapshotChannel = properties.getProperty("md.snapshot.channel")
                ?: "aeron:udp?endpoint=239.10.1.4:40004",
            snapshotStreamId = properties.getProperty("md.snapshot.streamId")?.toInt() ?: 4,
            snapshotCycleMs = properties.getProperty("md.snapshot.cycleMs")?.toLong() ?: 1_000L,
            metricsEnabled = properties.getProperty(METRICS_ENABLED).toBoolean(),
            idleStrategy = IdleStrategySpec.parse(properties.getProperty(IDLE_STRATEGY)),
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
