package com.engine.discovery

import com.engine.reference.DirectoryEncoder
import com.engine.reference.DirectorySink
import com.engine.reference.Universe
import io.aeron.Aeron
import io.aeron.Publication
import org.agrona.concurrent.ShutdownSignalBarrier
import java.io.File
import java.util.Properties

/**
 * Publishes the tradable universe: every security, and the shard that serves it.
 *
 * Upstream protocol adapters (FIX to SBE, and anything else outside this project) listen to this
 * to build their routing table at start-up, so they can send an order for a symbol to the one
 * gateway whose shard actually hosts it. Without it an adapter would have to be told the shard
 * map out of band and kept in step by hand.
 *
 * Broadcast on a repeating cycle rather than served on request: an adapter joins, waits at most
 * one interval, and is ready — no session state, no request protocol, and a restarted adapter
 * recovers on its own.
 *
 * The directory is derived from the same security files the shards boot from, so the universe and
 * the engines cannot describe different geometry.
 */
fun main(args: Array<String>) {
    val config = try {
        DiscoveryConfig.load(args.firstOrNull())
    } catch (e: IllegalArgumentException) {
        System.err.println("discovery: ${e.message}")
        return
    } catch (e: IllegalStateException) {
        System.err.println("discovery: ${e.message}")
        return
    }

    val universe = config.universe
    println(
        "discovery: version=${universe.version} shards=${universe.shards.map { it.shardId }} " +
            "securities=${universe.entries.size} " +
            "channel=${config.channel}:${config.streamId} every ${config.intervalMs}ms"
    )
    for (entry in universe.entries) {
        val route = universe.routeFor(entry.shardId)
        println(
            "discovery:   ${entry.spec.symbol} (${entry.spec.isin}) -> shard ${entry.shardId} " +
                "@ ${route?.orderEntryChannel}:${route?.orderEntryStreamId}"
        )
    }

    val aeronContext = Aeron.Context()
    config.aeronDirectoryName?.let(aeronContext::aeronDirectoryName)

    val aeron = try {
        Aeron.connect(aeronContext)
    } catch (e: io.aeron.exceptions.DriverTimeoutException) {
        System.err.println("discovery: no Aeron media driver found (${e.message}).")
        return
    }

    aeron.use {
        val publication: Publication = aeron.addPublication(config.channel, config.streamId)
        var dropped = 0L
        val encoder = DirectoryEncoder(
            DirectorySink { buffer, offset, length ->
                // Never block. A directory that stalls waiting for one listener would delay every
                // other adapter's start-up; the next cycle carries the same content anyway.
                if (publication.offer(buffer, offset, length) < 0) dropped++
            },
        )

        val barrier = ShutdownSignalBarrier()
        val worker = Thread({
            try {
                while (!Thread.currentThread().isInterrupted) {
                    encoder.broadcast(universe)
                    Thread.sleep(config.intervalMs)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }, "discovery-publisher")
        worker.start()

        println("discovery: started")
        barrier.await()
        worker.interrupt()
        worker.join(SHUTDOWN_TIMEOUT_MS)
        println("discovery: stopped. droppedFragments=$dropped")
    }
}

private const val SHUTDOWN_TIMEOUT_MS = 5_000L

data class DiscoveryConfig(
    val universe: Universe,
    val aeronDirectoryName: String?,
    val channel: String,
    val streamId: Int,
    val intervalMs: Long,
) {
    init {
        require(intervalMs > 0) { "discovery.intervalMs must be positive" }
    }

    companion object {
        fun from(properties: Properties): DiscoveryConfig = DiscoveryConfig(
            universe = Universe.from(properties),
            aeronDirectoryName = properties.getProperty("discovery.aeronDir"),
            channel = properties.getProperty("discovery.channel")
                ?: "aeron:udp?endpoint=239.10.0.1:40000",
            streamId = properties.getProperty("discovery.streamId")?.toInt() ?: 100,
            intervalMs = properties.getProperty("discovery.intervalMs")?.toLong() ?: 5_000L,
        )

        fun load(path: String?): DiscoveryConfig {
            val properties = Properties()
            if (path != null) {
                val file = File(path)
                require(file.isFile) { "config file not found: $path" }
                file.inputStream().use(properties::load)
            }
            for ((key, value) in System.getProperties()) {
                val name = key as String
                if (name.startsWith("discovery.")) properties.setProperty(name, value as String)
            }
            return from(properties)
        }
    }
}
