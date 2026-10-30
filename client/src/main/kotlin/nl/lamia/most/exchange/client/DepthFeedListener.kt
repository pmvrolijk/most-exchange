package nl.lamia.most.exchange.client

/**
 * What a [DepthFeedAssembler] did with the feed, for a consumer that forwards it rather than only
 * reading the book: a protocol adapter's incremental refresh is these events, and its snapshot is
 * the book at [onSynchronised].
 *
 * Called on the thread that feeds the assembler, after the book has changed, with primitives only,
 * so nothing allocates. Every method defaults to doing nothing.
 */
interface DepthFeedListener {

    /**
     * An increment applied to a trusted book: the level's new aggregate, zero when the level is gone.
     * Never called for a level an image installs, nor for an update buffered behind one: those are in
     * the book by the next [onSynchronised].
     */
    fun onDepthChanged(securityId: Int, isBid: Boolean, price: Long, aggregateQty: Long, orderCount: Int) {}

    /** A trade, whatever the book's state: it is one message that arrived, as the book keeps it. */
    fun onTrade(securityId: Int, price: Long, qty: Long) {}

    /** The book became trusted: an image was installed, with every update after it. Render it whole. */
    fun onSynchronised(securityId: Int) {}

    /**
     * The book stopped being trusted: a gap on its shard, or an image begun over it. `book()` is
     * null until the next [onSynchronised], and no [onDepthChanged] comes in between.
     */
    fun onDesynchronised(securityId: Int) {}

    companion object {
        /** Tells nothing. */
        val NONE: DepthFeedListener = object : DepthFeedListener {}
    }
}
