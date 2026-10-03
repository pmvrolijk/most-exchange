package nl.lamia.most.exchange.reference

import io.aeron.cluster.client.EgressListener
import io.aeron.cluster.codecs.AdminRequestType
import io.aeron.cluster.codecs.AdminResponseCode
import io.aeron.logbuffer.Header
import org.agrona.DirectBuffer
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Collects the cluster's answers to admin requests from egress, by correlation id.
 *
 * Installed as the `EgressListener` of a client that sends admin requests, because the answer
 * arrives on egress and is otherwise dropped. It was: every snapshot request was reported as
 * confirmed on the strength of the offer alone, while the cluster was refusing each one
 * (Design.md §1). Egress *messages* are not expected on such a session and are ignored.
 */
class AdminResponses : EgressListener {

    private val answers = ConcurrentHashMap<Long, Pair<AdminResponseCode, String>>()

    override fun onMessage(
        clusterSessionId: Long,
        timestamp: Long,
        buffer: DirectBuffer,
        offset: Int,
        length: Int,
        header: Header?,
    ) = Unit

    override fun onAdminResponse(
        clusterSessionId: Long,
        correlationId: Long,
        requestType: AdminRequestType,
        responseCode: AdminResponseCode,
        message: String,
        payload: DirectBuffer,
        payloadOffset: Int,
        payloadLength: Int,
    ) {
        answers[correlationId] = responseCode to message
    }

    internal fun take(correlationId: Long): Pair<AdminResponseCode, String>? = answers.remove(correlationId)
}

/** What became of a snapshot request: whether it was sent, and what the cluster answered. */
data class SnapshotOutcome(
    val sent: Boolean,
    /** The cluster's answer, or null when there was none. */
    val code: AdminResponseCode?,
    val message: String,
) {
    /** True only when the cluster answered OK -- never on the strength of the offer. */
    val confirmed: Boolean get() = code == AdminResponseCode.OK
}

/**
 * Sends a snapshot request and waits for the cluster's answer to *this* request (Design.md §1).
 *
 * [send] offers the request under [correlationId] -- `AeronCluster.sendAdminRequestToTakeASnapshot`
 * -- and [poll] drives egress so [responses] can see the answer. Functions rather than an
 * `AeronCluster`, which is final and cannot be faked, so the waiting logic is testable on its own.
 */
fun requestSnapshot(
    responses: AdminResponses,
    correlationId: Long,
    timeout: Duration,
    send: (Long) -> Boolean,
    poll: () -> Int,
): SnapshotOutcome {
    if (!send(correlationId)) {
        return SnapshotOutcome(sent = false, code = null, message = "the request could not be offered to the cluster")
    }
    val deadline = System.nanoTime() + timeout.toNanos()
    while (System.nanoTime() < deadline) {
        val answer = responses.take(correlationId)
        if (answer != null) {
            val (code, message) = answer
            return SnapshotOutcome(
                sent = true,
                code = code,
                message = if (code == AdminResponseCode.OK) "snapshot taken" else "$code: $message",
            )
        }
        if (poll() == 0) Thread.sleep(1)
    }
    return SnapshotOutcome(sent = true, code = null, message = "no answer from the cluster within $timeout")
}
