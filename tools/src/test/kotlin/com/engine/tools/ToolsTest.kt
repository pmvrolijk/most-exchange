package com.engine.tools

import com.engine.reference.PriceCodec
import com.engine.reference.RoutedSecurity
import com.engine.sbe.Side
import com.engine.sbe.SmpStrategy
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

class BookViewTest {

    private fun book() = BookView(1, "AAPL")

    @Test
    fun `depth updates build both sides`() {
        val book = book()
        book.applyDepth(isBid = true, price = PriceCodec.parse("99.00"), aggregateQty = 10, orderCount = 2)
        book.applyDepth(isBid = false, price = PriceCodec.parse("101.00"), aggregateQty = 5, orderCount = 1)

        assertEquals(PriceCodec.parse("99.00"), book.bestBid())
        assertEquals(PriceCodec.parse("101.00"), book.bestAsk())
        assertEquals(PriceCodec.parse("2.00"), book.spread())
    }

    @Test
    fun `bids sort high to low and asks low to high`() {
        val book = book()
        listOf("98.00", "99.00", "97.00").forEach {
            book.applyDepth(true, PriceCodec.parse(it), 1, 1)
        }
        listOf("102.00", "101.00", "103.00").forEach {
            book.applyDepth(false, PriceCodec.parse(it), 1, 1)
        }

        assertEquals(PriceCodec.parse("99.00"), book.bestBid())
        assertEquals(PriceCodec.parse("101.00"), book.bestAsk())

        val rendered = book.render(3)
        assertTrue(rendered.indexOf("99.00") < rendered.indexOf("98.00"))
        assertTrue(rendered.indexOf("101.00") < rendered.indexOf("102.00"))
    }

    @Test
    fun `a zero quantity removes the level`() {
        val book = book()
        val price = PriceCodec.parse("99.00")
        book.applyDepth(true, price, 10, 1)
        book.applyDepth(true, price, 0, 0)

        assertNull(book.bestBid())
        assertTrue(book.isEmpty())
    }

    @Test
    fun `a level is replaced not accumulated`() {
        // DepthUpdate carries the new aggregate, not a delta.
        val book = book()
        val price = PriceCodec.parse("99.00")
        book.applyDepth(true, price, 10, 1)
        book.applyDepth(true, price, 25, 3)

        assertContains(book.render(5), "25")
        assertFalse(book.render(5).contains("35"))
    }

    @Test
    fun `spread is absent when a side is empty`() {
        val book = book()
        book.applyDepth(true, PriceCodec.parse("99.00"), 10, 1)
        assertNull(book.spread())
        assertContains(book.render(5), "spread n/a")
    }

    @Test
    fun `an empty book renders without crashing`() {
        assertContains(book().render(5), "(empty)")
    }

    @Test
    fun `the last trade is shown`() {
        val book = book()
        book.applyDepth(true, PriceCodec.parse("99.00"), 10, 1)
        book.applyTrade(PriceCodec.parse("99.50"), 4)
        assertContains(book.render(5), "last 99.50 x 4")
    }
}

class BookInspectorTest {

    private val symbols = mapOf(1 to "AAPL", 2 to "MSFT")

    @Test
    fun `depth is aggregated per security`() {
        val inspector = BookInspector(symbols, emptySet())
        inspector.onDepth(1, shardId = 1, seqNum = 1, side = Side.BUY.value(),
            price = PriceCodec.parse("99.00"), qty = 10, orders = 1)
        inspector.onDepth(2, shardId = 1, seqNum = 2, side = Side.BUY.value(),
            price = PriceCodec.parse("50.00"), qty = 7, orders = 1)

        val rendered = inspector.render(5)
        assertContains(rendered, "AAPL")
        assertContains(rendered, "MSFT")
        assertEquals(PriceCodec.parse("99.00"), inspector.bookOf(1)?.bestBid())
        assertEquals(PriceCodec.parse("50.00"), inspector.bookOf(2)?.bestBid())
    }

    @Test
    fun `a watch list filters everything else out`() {
        val inspector = BookInspector(symbols, setOf(1))
        inspector.onDepth(1, 1, 1, Side.BUY.value(), PriceCodec.parse("99.00"), 10, 1)
        inspector.onDepth(2, 1, 2, Side.BUY.value(), PriceCodec.parse("50.00"), 7, 1)

        assertNull(inspector.bookOf(2))
        assertFalse(inspector.render(5).contains("MSFT"))
    }

    @Test
    fun `a gap is surfaced as a staleness warning`() {
        // Under Max flow control a slow subscriber takes an unrecoverable gap, so the operator
        // must be told the depth on screen can no longer be trusted.
        val inspector = BookInspector(symbols, emptySet())
        inspector.onDepth(1, 1, 1, Side.BUY.value(), PriceCodec.parse("99.00"), 10, 1)
        inspector.onDepth(1, 1, 9, Side.BUY.value(), PriceCodec.parse("98.00"), 10, 1)

        assertEquals(1L, inspector.gapsDetected)
        assertEquals(7L, inspector.messagesMissed)
        assertContains(inspector.render(5), "may be stale")
    }

    @Test
    fun `shards on one channel do not manufacture gaps`() {
        val inspector = BookInspector(symbols, emptySet())
        inspector.onDepth(1, shardId = 1, seqNum = 1, side = Side.BUY.value(), price = 100, qty = 1, orders = 1)
        inspector.onDepth(2, shardId = 2, seqNum = 1, side = Side.BUY.value(), price = 100, qty = 1, orders = 1)
        inspector.onDepth(1, shardId = 1, seqNum = 2, side = Side.BUY.value(), price = 100, qty = 2, orders = 1)
        inspector.onDepth(2, shardId = 2, seqNum = 2, side = Side.BUY.value(), price = 100, qty = 2, orders = 1)

        assertEquals(0L, inspector.gapsDetected)
    }

    @Test
    fun `a security missing from the directory is counted`() {
        val inspector = BookInspector(symbols, emptySet())
        inspector.onDepth(99, 1, 1, Side.BUY.value(), 100, 10, 1)

        assertEquals(1L, inspector.unknownSecurities)
        assertContains(inspector.render(5), "absent from the directory")
    }

    @Test
    fun `an idle inspector says so`() {
        assertContains(BookInspector(symbols, emptySet()).render(5), "waiting for depth updates")
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
