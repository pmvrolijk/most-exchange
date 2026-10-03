package nl.lamia.most.exchange.reference

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import nl.lamia.most.exchange.client.DirectoryClient
import nl.lamia.most.exchange.client.ShardRoute
import org.agrona.concurrent.UnsafeBuffer

/** Captures each broadcast fragment so a partial delivery can be replayed to the client. */
class CapturingSink : DirectorySink {
    val fragments = mutableListOf<ByteArray>()

    override fun send(buffer: org.agrona.DirectBuffer, offset: Int, length: Int) {
        val copy = ByteArray(length)
        buffer.getBytes(offset, copy)
        fragments += copy
    }
}

class DirectoryTest {

    private fun universe(): Universe = Universe(
        shards = listOf(
            ShardRoute(1, "aeron:udp?endpoint=gw1:20001", 20, "aeron:udp?endpoint=gw1:20002", 21),
            ShardRoute(2, "aeron:udp?endpoint=gw2:20001", 20, "aeron:udp?endpoint=gw2:20002", 21),
        ),
        entries = listOf(
            UniverseEntry(spec(1, "AAPL", ISIN_APPLE), 1),
            UniverseEntry(spec(2, "BMW", ISIN_BMW), 2),
        ),
    )

    private fun deliver(client: DirectoryClient, fragments: List<ByteArray>) {
        for (f in fragments) client.onDirectoryMessage(UnsafeBuffer(f), 0, f.size)
    }

    @Test
    fun `a broadcast round-trips into a routing table`() {
        val sink = CapturingSink()
        val universe = universe()
        DirectoryEncoder(sink).broadcast(universe)

        val client = DirectoryClient()
        deliver(client, sink.fragments)

        assertTrue(client.isReady)
        assertEquals(universe.version, client.version)
        assertEquals(2, client.securities.size)

        val apple = client.routeForSymbol("AAPL")!!
        assertEquals(1, apple.securityId)
        assertEquals(1, apple.shardId)
        assertEquals(ISIN_APPLE, apple.isin)
        assertEquals("USD", apple.currency)
        assertEquals("aeron:udp?endpoint=gw1:20001", apple.orderEntryChannel)
        assertEquals(20, apple.orderEntryStreamId)
        assertEquals("aeron:udp?endpoint=gw1:20002", apple.executionReportChannel)
        assertEquals(21, apple.executionReportStreamId)

        val bmw = client.routeFor(2)!!
        assertEquals(2, bmw.shardId)
        assertEquals("aeron:udp?endpoint=gw2:20001", bmw.orderEntryChannel)
    }

    @Test
    fun `geometry survives the round trip`() {
        val sink = CapturingSink()
        DirectoryEncoder(sink).broadcast(universe())
        val client = DirectoryClient()
        deliver(client, sink.fragments)

        val apple = client.routeFor(1)!!
        assertEquals(0L, apple.priceFloor)
        assertEquals(1_000_000L, apple.tickSize)
        assertEquals(65536, apple.levelCount)
    }

    @Test
    fun `an unknown symbol routes nowhere`() {
        val sink = CapturingSink()
        DirectoryEncoder(sink).broadcast(universe())
        val client = DirectoryClient()
        deliver(client, sink.fragments)

        assertNull(client.routeForSymbol("NOPE"))
        assertNull(client.routeFor(99))
    }

    @Test
    fun `a truncated broadcast leaves the previous table intact`() {
        // A lost datagram must not leave an adapter routing against half a universe.
        val sink = CapturingSink()
        DirectoryEncoder(sink).broadcast(universe())
        val client = DirectoryClient()
        deliver(client, sink.fragments)
        val goodVersion = client.version

        val truncated = CapturingSink()
        DirectoryEncoder(truncated).broadcast(universe())
        deliver(client, truncated.fragments.dropLast(2)) // lose a security entry and the end

        assertEquals(goodVersion, client.version)
        assertEquals(2, client.securities.size)
        assertNull(client.routeForSymbol("NOPE"))
    }

    @Test
    fun `a broadcast interrupted by a new one is counted`() {
        val sink = CapturingSink()
        DirectoryEncoder(sink).broadcast(universe())
        val client = DirectoryClient()

        deliver(client, sink.fragments.dropLast(1)) // begin + entries, no end
        deliver(client, sink.fragments)             // a fresh, complete cycle

        assertTrue(client.incompleteBroadcasts >= 1)
        assertTrue(client.isReady)
    }

    @Test
    fun `an entry arriving outside a broadcast is ignored`() {
        val sink = CapturingSink()
        DirectoryEncoder(sink).broadcast(universe())
        val client = DirectoryClient()

        // Only the security entries, with no DirectoryBegin to open the cycle.
        deliver(client, sink.fragments.drop(3).dropLast(1))
        assertFalse(client.isReady)
    }

    @Test
    fun `a later broadcast replaces the table`() {
        val sink = CapturingSink()
        DirectoryEncoder(sink).broadcast(universe())
        val client = DirectoryClient()
        deliver(client, sink.fragments)

        val smaller = Universe(
            shards = listOf(ShardRoute(1, "aeron:ipc", 20, "aeron:ipc", 21)),
            entries = listOf(UniverseEntry(spec(1, "AAPL", ISIN_APPLE), 1)),
        )
        val next = CapturingSink()
        DirectoryEncoder(next).broadcast(smaller)
        deliver(client, next.fragments)

        assertEquals(smaller.version, client.version)
        assertEquals(1, client.securities.size)
        assertNull(client.routeForSymbol("BMW"))
    }
}
