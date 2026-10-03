package nl.lamia.most.exchange.client

import nl.lamia.most.exchange.sbe.MessageHeaderEncoder
import nl.lamia.most.exchange.sbe.NewOrderSingleEncoder
import nl.lamia.most.exchange.sbe.OrderCancelRequestEncoder
import nl.lamia.most.exchange.sbe.Side
import nl.lamia.most.exchange.sbe.SmpStrategy
import org.agrona.MutableDirectBuffer

/**
 * Encoders for a participant's orders and cancels, shared by every sender for the reason
 * [ParticipantRequests] is: a second encoding of a wire message is a second thing to get wrong.
 *
 * Both go to a gateway on a plain publication, so they are encoded at [offset] with no cluster
 * session header (CLAUDE.md, "The wire"). Optional encoders let a sender on a hot path pass its own
 * and allocate nothing. Prices and quantities are fixed-point with 8 implied decimals ([PriceCodec]).
 */
object OrderRequests {

    const val NEW_ORDER_LENGTH = MessageHeaderEncoder.ENCODED_LENGTH + NewOrderSingleEncoder.BLOCK_LENGTH
    const val CANCEL_LENGTH = MessageHeaderEncoder.ENCODED_LENGTH + OrderCancelRequestEncoder.BLOCK_LENGTH

    @Suppress("LongParameterList")
    fun encodeNewOrder(
        buffer: MutableDirectBuffer,
        offset: Int,
        participantId: Long,
        clOrdId: Long,
        securityId: Int,
        side: Side,
        price: Long,
        qty: Long,
        expireDate: Int = 0,
        smpId: Long = 0L,
        smpStrategy: SmpStrategy = SmpStrategy.CANCEL_AGGRESSOR,
        encoder: NewOrderSingleEncoder = NewOrderSingleEncoder(),
        header: MessageHeaderEncoder = MessageHeaderEncoder(),
    ): Int {
        encoder.wrapAndApplyHeader(buffer, offset, header)
            .participantId(participantId)
            .clOrdId(clOrdId)
            .price(price)
            .qty(qty)
            .smpId(smpId)
            .securityId(securityId)
            .expireDate(expireDate)
            .side(side)
            .smpStrategy(smpStrategy)
        return NEW_ORDER_LENGTH
    }

    /** A cancel. Its report carries [clOrdId], the cancel's own id, not [origClOrdId]. */
    @Suppress("LongParameterList")
    fun encodeCancel(
        buffer: MutableDirectBuffer,
        offset: Int,
        participantId: Long,
        clOrdId: Long,
        origClOrdId: Long,
        exchangeOrderId: Long,
        securityId: Int,
        side: Side,
        encoder: OrderCancelRequestEncoder = OrderCancelRequestEncoder(),
        header: MessageHeaderEncoder = MessageHeaderEncoder(),
    ): Int {
        encoder.wrapAndApplyHeader(buffer, offset, header)
            .participantId(participantId)
            .origClOrdId(origClOrdId)
            .clOrdId(clOrdId)
            .exchangeOrderId(exchangeOrderId)
            .securityId(securityId)
            .side(side)
        return CANCEL_LENGTH
    }
}
