package com.engine.marketdata

import com.engine.sbe.DepthUpdateEncoder
import com.engine.sbe.LastTradeEncoder
import com.engine.sbe.MessageHeaderEncoder
import com.engine.sbe.Side
import com.engine.sbe.TopOfBookEncoder
import io.aeron.Publication
import org.agrona.DirectBuffer
import org.agrona.MutableDirectBuffer

/**
 * Publishes the derived feeds over Aeron, each on its own publication so a subscriber takes only
 * what it needs (Design.md §5).
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
class AeronFeedPublisher(
    /**
     * Stamped on every derived message so several shards can share one multicast group: each
     * numbers its feeds independently, and subscribers track a sequence per shard.
     */
    private val shardId: Int,
    private val l3: Publication,
    private val l2: Publication,
    private val l1: Publication,
    private val buffer: MutableDirectBuffer,
) : FeedPublisher {

    private val headerEncoder = MessageHeaderEncoder()
    private val depthEncoder = DepthUpdateEncoder()
    private val topOfBookEncoder = TopOfBookEncoder()
    private val lastTradeEncoder = LastTradeEncoder()

    private var l3Seq = 1L
    private var l2Seq = 1L
    private var l1Seq = 1L

    var droppedL3 = 0L
        private set
    var droppedL2 = 0L
        private set
    var droppedL1 = 0L
        private set

    override fun publishL3(buffer: DirectBuffer, offset: Int, length: Int) {
        // Forwarded verbatim: the engine's event already carries its own sequence number.
        if (l3.offer(buffer, offset, length) < 0) droppedL3++
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
        if (l2.offer(
                buffer, 0,
                MessageHeaderEncoder.ENCODED_LENGTH + DepthUpdateEncoder.BLOCK_LENGTH,
            ) < 0
        ) {
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
        if (l1.offer(
                buffer, 0,
                MessageHeaderEncoder.ENCODED_LENGTH + TopOfBookEncoder.BLOCK_LENGTH,
            ) < 0
        ) {
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
        if (l1.offer(
                buffer, 0,
                MessageHeaderEncoder.ENCODED_LENGTH + LastTradeEncoder.BLOCK_LENGTH,
            ) < 0
        ) {
            droppedL1++
        }
    }
}
