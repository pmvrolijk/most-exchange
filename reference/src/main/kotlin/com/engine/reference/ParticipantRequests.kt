package com.engine.reference

import com.engine.sbe.MessageHeaderEncoder
import com.engine.sbe.OrderMassStatusRequestEncoder
import com.engine.sbe.ReportResendRequestEncoder
import org.agrona.MutableDirectBuffer

/**
 * Encoders for a participant's own recovery requests (Design.md §5, "Report sequence and resend"
 * and "Order mass status"), shared by every sender for the reason [OperatorCommands] is: a second
 * encoding of a wire message is a second thing to get wrong.
 *
 * Unlike an operator command, both are **answered** -- a `ReportResendComplete` or an
 * `OrderMassStatusComplete` on the participant's report stream, carrying the `requestId` given here
 * -- and the gateway checks the participant as it checks a cancel.
 *
 * Each takes an [offset], so a sender can encode straight into a publication's claim, and optional
 * encoders, so one on a hot path can pass its own and allocate nothing.
 */
object ParticipantRequests {

    /** Every retained report of [participantId] from [fromSeq] on, then the completion. */
    fun encodeReportResendRequest(
        buffer: MutableDirectBuffer,
        offset: Int,
        participantId: Long,
        requestId: Long,
        fromSeq: Long,
        encoder: ReportResendRequestEncoder = ReportResendRequestEncoder(),
        header: MessageHeaderEncoder = MessageHeaderEncoder(),
    ): Int {
        encoder.wrapAndApplyHeader(buffer, offset, header)
            .participantId(participantId)
            .requestId(requestId)
            .fromSeq(fromSeq)
        return MessageHeaderEncoder.ENCODED_LENGTH + ReportResendRequestEncoder.BLOCK_LENGTH
    }

    /**
     * The state of every open order of [participantId], on [securityId] or, with
     * [OperatorCommands.ALL_SECURITIES], on every book of the shard.
     */
    fun encodeOrderMassStatusRequest(
        buffer: MutableDirectBuffer,
        offset: Int,
        participantId: Long,
        requestId: Long,
        securityId: Int = OperatorCommands.ALL_SECURITIES,
        encoder: OrderMassStatusRequestEncoder = OrderMassStatusRequestEncoder(),
        header: MessageHeaderEncoder = MessageHeaderEncoder(),
    ): Int {
        encoder.wrapAndApplyHeader(buffer, offset, header)
            .participantId(participantId)
            .requestId(requestId)
            .securityId(securityId)
        return MessageHeaderEncoder.ENCODED_LENGTH + OrderMassStatusRequestEncoder.BLOCK_LENGTH
    }

    /** The encoded length of either request, for a sender sizing a claim. */
    const val RESEND_REQUEST_LENGTH = MessageHeaderEncoder.ENCODED_LENGTH + ReportResendRequestEncoder.BLOCK_LENGTH
    const val MASS_STATUS_REQUEST_LENGTH = MessageHeaderEncoder.ENCODED_LENGTH + OrderMassStatusRequestEncoder.BLOCK_LENGTH
}
