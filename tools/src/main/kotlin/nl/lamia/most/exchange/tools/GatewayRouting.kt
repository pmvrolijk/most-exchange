package nl.lamia.most.exchange.tools

import nl.lamia.most.exchange.client.RoutedSecurity
import nl.lamia.most.exchange.client.ShardRoute
import java.io.File

/**
 * A gateway endpoint given on the command line, in place of the one the directory advertises.
 *
 * The directory names one order-entry endpoint per shard. That is not always the one to use
 * (Design.md §1): a participant split onto another gateway is told that gateway's endpoint out of
 * band, and an operator command passes only through a gateway whose registry entry has
 * `operator=true`. Each option replaces only its own part of the route; the directory is still the
 * source of everything else about a security.
 *
 * Four options rather than one `channel:stream`, because an Aeron channel URI is full of colons of
 * its own and a port would silently be read as a stream id.
 */
data class GatewayOverride(
    val orderEntryChannel: String? = null,
    val orderEntryStreamId: Int? = null,
    val reportChannel: String? = null,
    val reportStreamId: Int? = null,
) {
    fun applyTo(security: RoutedSecurity): RoutedSecurity = security.copy(
        orderEntryChannel = orderEntryChannel ?: security.orderEntryChannel,
        orderEntryStreamId = orderEntryStreamId ?: security.orderEntryStreamId,
        executionReportChannel = reportChannel ?: security.executionReportChannel,
        executionReportStreamId = reportStreamId ?: security.executionReportStreamId,
    )

    fun applyTo(route: ShardRoute): ShardRoute = route.copy(
        orderEntryChannel = orderEntryChannel ?: route.orderEntryChannel,
        orderEntryStreamId = orderEntryStreamId ?: route.orderEntryStreamId,
        executionReportChannel = reportChannel ?: route.executionReportChannel,
        executionReportStreamId = reportStreamId ?: route.executionReportStreamId,
    )

    companion object {
        fun from(args: Args) = GatewayOverride(
            orderEntryChannel = args.optional("order-entry-channel"),
            orderEntryStreamId = stream(args, "order-entry-stream"),
            reportChannel = args.optional("report-channel"),
            reportStreamId = stream(args, "report-stream"),
        )

        /**
         * One override per gateway, for `most load`'s failover between co-located gateways
         * (Design.md §7, "Gateway placement"): `--order-entry-channel` and `--report-channel` may
         * each be a comma-separated list, paired by position, and a single value is shared by every
         * gateway. An Aeron channel URI separates its parameters with `|`, never a comma.
         */
        fun listFrom(args: Args): List<GatewayOverride> {
            val single = from(args)
            val orders = split(single.orderEntryChannel)
            val reports = split(single.reportChannel)
            val count = maxOf(orders.size, reports.size, 1)
            require(orders.size <= 1 || reports.size <= 1 || orders.size == reports.size) {
                "--order-entry-channel names ${orders.size} gateways and --report-channel ${reports.size}; " +
                    "pair them by position, or give one report channel for all"
            }
            return List(count) { i ->
                single.copy(
                    orderEntryChannel = orders.getOrNull(i) ?: orders.singleOrNull(),
                    reportChannel = reports.getOrNull(i) ?: reports.singleOrNull(),
                )
            }
        }

        private fun split(value: String?): List<String> =
            value?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

        private fun stream(args: Args, name: String): Int? = args.optional(name)?.let {
            requireNotNull(it.toIntOrNull()) { "--$name must be a stream id, not '$it'" }
        }
    }
}

/**
 * The credentials the CLI presents when it connects to the cluster itself (`cluster snapshot
 * --ingress`), or null for none.
 *
 * A node running with a participant registry refuses a session with no credentials, since an
 * anonymous session skips every gateway check (Design.md §1). The CLI is then named by an
 * operator-only registry entry, and presents it exactly as a gateway does. The secret comes from a
 * file only: a secret on a command line ends up in shell history and in `ps`.
 */
fun clusterCredentials(args: Args): Pair<String, String>? {
    val identity = args.optional("identity")?.trim()?.ifEmpty { null }
    val secretFile = args.optional("secret-file")?.trim()?.ifEmpty { null }
    require((identity == null) == (secretFile == null)) {
        "--identity and --secret-file go together: an identity with no secret cannot " +
            "authenticate, and a secret with no identity has nothing to authenticate as"
    }
    if (identity == null || secretFile == null) return null
    val file = File(secretFile)
    require(file.isFile) { "--secret-file not found: $secretFile" }
    val secret = file.readText().trim()
    require(secret.isNotEmpty()) { "--secret-file is empty: $secretFile" }
    return identity to secret
}
