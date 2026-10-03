package nl.lamia.most.exchange.client

import io.aeron.Aeron
import io.aeron.ExclusivePublication
import io.aeron.Subscription
import io.aeron.logbuffer.FragmentHandler
import org.agrona.DirectBuffer

/**
 * One order entry gateway's client endpoints: where orders go, and where its reports come back.
 *
 * The directory names one per shard ([ShardRoute]). A shard served by several gateways — co-located
 * gateways are one per node — has the rest configured out of band (docs/Adapters.md §1), so an
 * adapter holds a list of these per shard.
 */
data class GatewayEndpoint(
    val orderEntryChannel: String,
    val orderEntryStreamId: Int,
    val executionReportChannel: String,
    val executionReportStreamId: Int,
) {
    override fun toString(): String = "$orderEntryChannel:$orderEntryStreamId"

    companion object {
        fun of(route: ShardRoute) = GatewayEndpoint(
            route.orderEntryChannel, route.orderEntryStreamId,
            route.executionReportChannel, route.executionReportStreamId,
        )

        fun of(security: RoutedSecurity) = GatewayEndpoint(
            security.orderEntryChannel, security.orderEntryStreamId,
            security.executionReportChannel, security.executionReportStreamId,
        )
    }
}

/**
 * Both legs of one gateway, as [OrderEntrySession] uses them. [AeronGatewayLink] in production; a
 * model of the exchange in tests, which is why this is an interface.
 *
 * [offer] returns what `io.aeron.Publication.offer` returns: a positive position when sent,
 * `BACK_PRESSURED` or `ADMIN_ACTION` to retry, `NOT_CONNECTED` when the gateway is gone, `CLOSED`
 * or `MAX_POSITION_EXCEEDED` when this link is unusable.
 */
interface GatewayLink {
    val endpoint: GatewayEndpoint

    /** Both legs: the order publication *and* the report subscription (docs/Adapters.md §1). */
    val isConnected: Boolean

    fun offer(buffer: DirectBuffer, offset: Int, length: Int): Long

    fun poll(handler: FragmentHandler, fragmentLimit: Int): Int
}

/**
 * A [GatewayLink] over an Aeron client. The publication is exclusive because a session has one
 * sending thread, and each adapter has its own endpoints: two adapters on one inbound channel would
 * each forward every order (docs/Adapters.md §1).
 */
class AeronGatewayLink(aeron: Aeron, override val endpoint: GatewayEndpoint) : GatewayLink, AutoCloseable {
    // Subscribe first: an acknowledgement can beat a subscription that is still coming up.
    private val reports: Subscription =
        aeron.addSubscription(endpoint.executionReportChannel, endpoint.executionReportStreamId)
    private val orders: ExclusivePublication =
        aeron.addExclusivePublication(endpoint.orderEntryChannel, endpoint.orderEntryStreamId)

    override val isConnected: Boolean get() = orders.isConnected && reports.isConnected

    override fun offer(buffer: DirectBuffer, offset: Int, length: Int): Long = orders.offer(buffer, offset, length)

    override fun poll(handler: FragmentHandler, fragmentLimit: Int): Int = reports.poll(handler, fragmentLimit)

    override fun close() {
        orders.close()
        reports.close()
    }
}
