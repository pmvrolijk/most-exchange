package nl.lamia.most.exchange.marketdata

import nl.lamia.most.exchange.sbe.DepthSnapshotBeginEncoder
import nl.lamia.most.exchange.sbe.DepthSnapshotEndEncoder
import nl.lamia.most.exchange.sbe.DepthSnapshotLevelEncoder
import nl.lamia.most.exchange.sbe.DepthUpdateEncoder
import nl.lamia.most.exchange.sbe.LastTradeEncoder
import nl.lamia.most.exchange.sbe.MessageHeaderEncoder
import nl.lamia.most.exchange.sbe.Side
import nl.lamia.most.exchange.sbe.TopOfBookEncoder
import org.agrona.DirectBuffer
import org.agrona.MutableDirectBuffer

/**
 * Where an encoded feed message goes.
 *
 * The one line of Aeron in this file, hoisted into an interface so the encoders can be driven by a
 * test that decodes what they produce. The alternative is a hand-written test double that encodes
 * the same messages a second time, which is precisely the fake-easier-than-reality that let the
 * cluster session header bug through: a test double cannot validate a layout it also invents.
 *
 * Returns false when the message was not sent, which every caller here treats as a drop.
 */
fun interface FeedSink {
    fun send(buffer: DirectBuffer, offset: Int, length: Int): Boolean
}

/**
 * Encodes the derived feeds, each to its own sink so a subscriber takes only what it needs
 * (Design.md §5).
 *
 * Every message carries its feed's own monotonic sequence number. Under
 * `MaxMulticastFlowControl` — Aeron's default, and the correct one here — a slow subscriber takes
 * an unrecoverable gap rather than throttling the publisher, so the sequence is how a consumer
 * knows to resynchronise.
 *
 * Nothing here blocks. A market data feed that stalls its publisher to wait for one subscriber
 * would defeat the reason this process is separate from the engine at all, so a failed offer is
 * counted and dropped.
 */
class SbeFeedPublisher(
    /**
     * Stamped on every derived message so several shards can share one multicast group: each
     * numbers its feeds independently, and subscribers track a sequence per shard.
     */
    private val shardId: Int,
    private val l3: FeedSink,
    private val l2: FeedSink,
    private val l1: FeedSink,
    /**
     * The recovery feed. Its own publication so a subscriber that does not want images pays
     * nothing for them, and so a snapshot burst is never queued ahead of the incremental updates
     * a synchronised consumer is waiting on.
     */
    private val snapshot: FeedSink,
    private val buffer: MutableDirectBuffer,
) : FeedPublisher {

    private val headerEncoder = MessageHeaderEncoder()
    private val depthEncoder = DepthUpdateEncoder()
    private val topOfBookEncoder = TopOfBookEncoder()
    private val lastTradeEncoder = LastTradeEncoder()
    private val snapshotBeginEncoder = DepthSnapshotBeginEncoder()
    private val snapshotLevelEncoder = DepthSnapshotLevelEncoder()
    private val snapshotEndEncoder = DepthSnapshotEndEncoder()

    private var l3Seq = 1L
    private var l2Seq = 1L
    private var l1Seq = 1L
    private var snapshotSeq = 1L

    var droppedL3 = 0L
        private set
    var droppedL2 = 0L
        private set
    var droppedL1 = 0L
        private set
    var droppedSnapshot = 0L
        private set

    /**
     * The sequence of the last DepthUpdate actually written, which is what a snapshot is stamped
     * with. `l2Seq` is the *next* number to use, so the last one used is one less; a snapshot
     * taken before anything has been published is stamped 0, which no update can carry and which
     * therefore replays everything.
     */
    override val lastDepthSeqNum: Long get() = l2Seq - 1

    override fun publishL3(buffer: DirectBuffer, offset: Int, length: Int) {
        // Forwarded verbatim: the engine's event already carries its own sequence number.
        if (!l3.send(buffer, offset, length)) droppedL3++
    }

    override fun publishDepth(
        securityId: Int,
        side: Byte,
        price: Long,
        aggregateQty: Long,
        orderCount: Int,
    ) {
        depthEncoder.wrapAndApplyHeader(buffer, 0, headerEncoder)
            .seqNum(l2Seq++)
            .price(price)
            .aggregateQty(aggregateQty)
            .securityId(securityId)
            .shardId(shardId)
            .orderCount(orderCount)
            .side(Side.get(side))
        if (!l2.send(buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + DepthUpdateEncoder.BLOCK_LENGTH)) {
            droppedL2++
        }
    }

    override fun publishTopOfBook(
        securityId: Int,
        bidPrice: Long,
        bidQty: Long,
        askPrice: Long,
        askQty: Long,
    ) {
        topOfBookEncoder.wrapAndApplyHeader(buffer, 0, headerEncoder)
            .seqNum(l1Seq++)
            .bidPrice(bidPrice)
            .bidQty(bidQty)
            .askPrice(askPrice)
            .askQty(askQty)
            .securityId(securityId)
            .shardId(shardId)
        if (!l1.send(buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + TopOfBookEncoder.BLOCK_LENGTH)) {
            droppedL1++
        }
    }

    override fun publishLastTrade(securityId: Int, price: Long, qty: Long, aggressorSide: Byte) {
        lastTradeEncoder.wrapAndApplyHeader(buffer, 0, headerEncoder)
            .seqNum(l1Seq++)
            .price(price)
            .qty(qty)
            .securityId(securityId)
            .shardId(shardId)
            .aggressorSide(Side.get(aggressorSide))
        if (!l1.send(buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + LastTradeEncoder.BLOCK_LENGTH)) {
            droppedL1++
        }
    }

    override fun publishSnapshotBegin(
        securityId: Int,
        l2SeqNum: Long,
        lastTradePrice: Long,
        lastTradeQty: Long,
        levelCount: Int,
    ) {
        snapshotBeginEncoder.wrapAndApplyHeader(buffer, 0, headerEncoder)
            .seqNum(snapshotSeq++)
            .l2SeqNum(l2SeqNum)
            .lastTradePrice(lastTradePrice)
            .lastTradeQty(lastTradeQty)
            .securityId(securityId)
            .shardId(shardId)
            .levelCount(levelCount)
        offerSnapshot(MessageHeaderEncoder.ENCODED_LENGTH + DepthSnapshotBeginEncoder.BLOCK_LENGTH)
    }

    override fun publishSnapshotLevel(
        securityId: Int,
        side: Byte,
        price: Long,
        aggregateQty: Long,
        orderCount: Int,
    ) {
        snapshotLevelEncoder.wrapAndApplyHeader(buffer, 0, headerEncoder)
            .seqNum(snapshotSeq++)
            .price(price)
            .aggregateQty(aggregateQty)
            .securityId(securityId)
            .shardId(shardId)
            .orderCount(orderCount)
            .side(Side.get(side))
        offerSnapshot(MessageHeaderEncoder.ENCODED_LENGTH + DepthSnapshotLevelEncoder.BLOCK_LENGTH)
    }

    override fun publishSnapshotEnd(securityId: Int, l2SeqNum: Long, levelCount: Int) {
        snapshotEndEncoder.wrapAndApplyHeader(buffer, 0, headerEncoder)
            .seqNum(snapshotSeq++)
            .l2SeqNum(l2SeqNum)
            .securityId(securityId)
            .shardId(shardId)
            .levelCount(levelCount)
        offerSnapshot(MessageHeaderEncoder.ENCODED_LENGTH + DepthSnapshotEndEncoder.BLOCK_LENGTH)
    }

    /**
     * A dropped snapshot message is not retried. The cycle it belonged to is now truncated, its
     * End will not match its Begin, and every consumer will discard it and wait for the next one
     * -- which is the whole point of bracketing the image. Blocking here to protect one cycle
     * would stall the incremental feed for every subscriber to fix something that repairs itself
     * within one interval.
     */
    private fun offerSnapshot(length: Int) {
        if (!snapshot.send(buffer, 0, length)) droppedSnapshot++
    }
}
