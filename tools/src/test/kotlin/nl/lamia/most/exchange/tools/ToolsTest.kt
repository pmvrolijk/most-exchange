package nl.lamia.most.exchange.tools

import nl.lamia.most.exchange.client.AggregatedBook
import nl.lamia.most.exchange.client.DepthFeedAssembler
import nl.lamia.most.exchange.client.PriceCodec
import nl.lamia.most.exchange.client.RoutedSecurity
import nl.lamia.most.exchange.client.SyncState
import nl.lamia.most.exchange.sbe.Side
import nl.lamia.most.exchange.sbe.SmpStrategy
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun routed(
    securityId: Int = 1,
    symbol: String = "AAPL",
    tickSize: Long = 1_000_000L,
) = RoutedSecurity(
    securityId = securityId,
    shardId = 1,
    symbol = symbol,
    isin = "US0378331005",
    name = "Apple Inc.",
    currency = "USD",
    priceFloor = 0,
    tickSize = tickSize,
    levelCount = 65536,
    orderEntryChannel = "aeron:udp?endpoint=gw1:20001",
    orderEntryStreamId = 20,
    executionReportChannel = "aeron:udp?endpoint=gw1:20002",
    executionReportStreamId = 21,
)

class ArgsTest {

    @Test
    fun `parses options flags and positionals`() {
        val args = Args(arrayOf("--symbol", "AAPL", "--verbose", "extra", "--qty", "10"))
        assertEquals("AAPL", args.required("symbol"))
        assertEquals(10L, args.requiredLong("qty"))
        assertTrue(args.has("verbose"))
        assertEquals(listOf("extra"), args.positional)
    }

    @Test
    fun `a missing option names itself`() {
        val error = assertFailsWith<IllegalArgumentException> { Args(emptyArray()).required("symbol") }
        assertContains(error.message!!, "--symbol")
    }

    @Test
    fun `defaults apply when absent`() {
        val args = Args(arrayOf("--depth", "3"))
        assertEquals(3, args.int("depth", 5))
        assertEquals(5, args.int("missing", 5))
    }

    @Test
    fun `a flag before another option is not swallowed as its value`() {
        val args = Args(arrayOf("--verbose", "--symbol", "AAPL"))
        assertTrue(args.has("verbose"))
        assertEquals("AAPL", args.required("symbol"))
    }
}

class OrderParserTest {

    private fun args(vararg pairs: String) = Args(arrayOf(*pairs))

    @Test
    fun `parses a valid order`() {
        val request = OrderParser.bind(
            OrderParser.parseLocal(
                args("--symbol", "AAPL", "--side", "buy", "--price", "100.25", "--qty", "10"),
                participantId = 7,
            ),
            routed(),
        )
        assertEquals(Side.BUY.value(), request.side)
        assertEquals(PriceCodec.parse("100.25"), request.price)
        assertEquals(10L, request.qty)
        assertEquals(7L, request.participantId)
        assertEquals(SmpStrategy.CANCEL_AGGRESSOR.value(), request.smpStrategy)
    }

    @Test
    fun `accepts side aliases`() {
        assertEquals(Side.BUY.value(), OrderParser.parseSide("b"))
        assertEquals(Side.BUY.value(), OrderParser.parseSide("BUY"))
        assertEquals(Side.SELL.value(), OrderParser.parseSide("sell"))
        assertEquals(Side.SELL.value(), OrderParser.parseSide("offer"))
        assertFailsWith<IllegalArgumentException> { OrderParser.parseSide("sideways") }
    }

    @Test
    fun `parses the smp strategy`() {
        assertEquals(SmpStrategy.CANCEL_AGGRESSOR.value(), OrderParser.parseStrategy(null))
        assertEquals(SmpStrategy.CANCEL_RESTING.value(), OrderParser.parseStrategy("resting"))
        assertFailsWith<IllegalArgumentException> { OrderParser.parseStrategy("both") }
    }

    @Test
    fun `a malformed price is caught without needing the directory`() {
        // A typo must report the typo, not whatever the network fails with first.
        val error = assertFailsWith<IllegalArgumentException> {
            OrderParser.parseLocal(
                args("--symbol", "AAPL", "--side", "buy", "--price", "1.2.3", "--qty", "1"),
                participantId = 1,
            )
        }
        assertContains(error.message!!, "not a decimal number")
    }

    @Test
    fun `a missing option is caught without needing the directory`() {
        val error = assertFailsWith<IllegalArgumentException> {
            OrderParser.parseLocal(args("--symbol", "AAPL"), participantId = 1)
        }
        assertContains(error.message!!, "--side")
    }

    @Test
    fun `a price off the tick is rejected once the geometry is known`() {
        // Tick size comes from the directory, so this need not cost a round trip to the engine.
        val local = OrderParser.parseLocal(
            args("--symbol", "AAPL", "--side", "buy", "--price", "100.005", "--qty", "1"),
            participantId = 1,
        )
        val error = assertFailsWith<IllegalArgumentException> {
            OrderParser.bind(local, routed(tickSize = 1_000_000L))
        }
        assertContains(error.message!!, "not a multiple")
    }

    @Test
    fun `a non-positive quantity is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            OrderParser.parseLocal(
                args("--symbol", "AAPL", "--side", "buy", "--price", "100.00", "--qty", "0"),
                participantId = 1,
            )
        }
    }
}

/**
 * Rendering only. The book's own semantics -- an aggregate replaces rather than accumulates, a
 * zero quantity removes a level -- moved to `reference`'s AggregatedBookTest along with the book,
 * so they are asserted once, next to the single implementation every consumer shares.
 */
class BookViewTest {

    private val view = BookView(1, "AAPL")

    private fun book(vararg levels: Triple<Boolean, String, Long>) = AggregatedBook(1).apply {
        levels.forEach { (isBid, price, qty) ->
            applyDepth(isBid, PriceCodec.parse(price), qty, 1)
        }
    }

    private fun render(book: AggregatedBook?, state: SyncState = SyncState.SYNCHRONISED, depth: Int = 5) =
        view.render(book, state, depth)

    @Test
    fun `bids print high to low and asks low to high`() {
        val rendered = render(
            book(
                Triple(true, "98.00", 1L), Triple(true, "99.00", 1L), Triple(true, "97.00", 1L),
                Triple(false, "102.00", 1L), Triple(false, "101.00", 1L), Triple(false, "103.00", 1L),
            ),
        )

        assertTrue(rendered.indexOf("99.00") < rendered.indexOf("98.00"))
        assertTrue(rendered.indexOf("101.00") < rendered.indexOf("102.00"))
    }

    @Test
    fun `a synchronised but empty book says empty`() {
        assertContains(render(book()), "(empty)")
    }

    @Test
    fun `an unsynchronised book says waiting, never empty`() {
        // The distinction the snapshot feed exists to make: "no liquidity" and "I cannot yet know"
        // are different answers, and drawing the first when the second is true is the lie this
        // tool used to tell any operator who started it mid-session.
        val rendered = render(null, SyncState.WAITING)
        assertContains(rendered, "waiting for a snapshot")
        assertFalse(rendered.contains("(empty)"))
    }

    @Test
    fun `a snapshot in flight says so`() {
        assertContains(render(null, SyncState.BUILDING), "receiving snapshot")
    }

    @Test
    fun `spread is absent when a side is empty`() {
        assertContains(render(book(Triple(true, "99.00", 10L))), "spread n/a")
    }

    @Test
    fun `the last trade is shown`() {
        val book = book(Triple(true, "99.00", 10L))
        book.applyTrade(PriceCodec.parse("99.50"), 4)
        assertContains(render(book), "last 99.50 x 4")
    }
}

class BookInspectorTest {

    private val symbols = mapOf(1 to "AAPL", 2 to "MSFT")
    private var seq = 0L

    /** A complete snapshot cycle, which is what entitles a book to be displayed at all. */
    private fun BookInspector.synchronise(securityId: Int, shardId: Int = 1, bid: Long? = null) {
        val levels = if (bid == null) 0 else 1
        assembler.onSnapshotBegin(securityId, shardId, ++seq, 0, DepthFeedAssembler.NO_PRICE, 0, levels)
        if (bid != null) assembler.onSnapshotLevel(securityId, shardId, ++seq, true, bid, 10, 1)
        assembler.onSnapshotEnd(securityId, shardId, ++seq, 0, levels)
    }

    @Test
    fun `depth is aggregated per security`() {
        val inspector = BookInspector(symbols, emptySet())
        inspector.synchronise(1, bid = PriceCodec.parse("99.00"))
        inspector.synchronise(2, bid = PriceCodec.parse("50.00"))

        val rendered = inspector.render(5)
        assertContains(rendered, "AAPL")
        assertContains(rendered, "MSFT")
        assertEquals(PriceCodec.parse("99.00"), inspector.assembler.book(1)?.bestBid())
        assertEquals(PriceCodec.parse("50.00"), inspector.assembler.book(2)?.bestBid())
    }

    @Test
    fun `a watch list filters everything else out`() {
        val inspector = BookInspector(symbols, setOf(1))
        inspector.synchronise(1, bid = PriceCodec.parse("99.00"))
        inspector.synchronise(2, bid = PriceCodec.parse("50.00"))

        assertFalse(inspector.render(5).contains("MSFT"))
    }

    @Test
    fun `a gap is surfaced and the book stops being drawn`() {
        // Under Max flow control a slow subscriber takes an unrecoverable gap. Before recovery
        // existed the only honest thing to do was warn; now the book is withdrawn until the next
        // snapshot restores it, and the warning says which.
        val inspector = BookInspector(symbols, emptySet())
        inspector.synchronise(1, bid = PriceCodec.parse("99.00"))
        inspector.assembler.onDepthUpdate(1, 1, 1, true, PriceCodec.parse("98.00"), 5, 1)
        inspector.assembler.onDepthUpdate(1, 1, 9, true, PriceCodec.parse("97.00"), 5, 1)

        assertEquals(1L, inspector.gapsDetected)
        assertEquals(7L, inspector.messagesMissed)
        val rendered = inspector.render(5)
        assertContains(rendered, "resynchronise from the next snapshot")
        assertContains(rendered, "waiting for a snapshot")
    }

    @Test
    fun `shards on one channel do not manufacture gaps`() {
        val inspector = BookInspector(symbols, emptySet())
        inspector.synchronise(1, shardId = 1)
        inspector.synchronise(2, shardId = 2)
        inspector.assembler.onDepthUpdate(1, 1, 1, true, 100, 1, 1)
        inspector.assembler.onDepthUpdate(2, 2, 1, true, 100, 1, 1)
        inspector.assembler.onDepthUpdate(1, 1, 2, true, 100, 2, 1)
        inspector.assembler.onDepthUpdate(2, 2, 2, true, 100, 2, 1)

        assertEquals(0L, inspector.gapsDetected)
    }

    @Test
    fun `a security missing from the directory is counted`() {
        val inspector = BookInspector(symbols, emptySet())
        inspector.synchronise(99)

        assertContains(inspector.render(5), "absent from the directory")
        assertEquals(1L, inspector.unknownSecurities)
    }

    @Test
    fun `an idle inspector says so`() {
        assertContains(BookInspector(symbols, emptySet()).render(5), "waiting for the depth feed")
    }
}

class SecuritiesReportTest {

    @Test
    fun `lists securities sorted by symbol`() {
        val report = SecuritiesReport.render(
            listOf(routed(2, "MSFT"), routed(1, "AAPL")),
            version = 42, verbose = false,
        )
        assertTrue(report.indexOf("AAPL") < report.indexOf("MSFT"))
        assertContains(report, "2 securities, universe version 42")
    }

    @Test
    fun `verbose adds geometry and endpoints`() {
        val plain = SecuritiesReport.render(listOf(routed()), 1, verbose = false)
        val verbose = SecuritiesReport.render(listOf(routed()), 1, verbose = true)

        assertFalse(plain.contains("gw1:20001"))
        assertContains(verbose, "gw1:20001")
        assertContains(verbose, "tick=0.01")
    }

    @Test
    fun `an empty directory says so`() {
        assertContains(SecuritiesReport.render(emptyList(), 0, false), "no securities")
    }
}
