package com.engine.tools

import com.engine.reference.DirectoryClient
import com.engine.reference.PriceCodec
import com.engine.reference.RoutedSecurity

/** Renders the tradable universe. Separated from Aeron so the formatting is testable. */
object SecuritiesReport {

    fun render(securities: Collection<RoutedSecurity>, version: Long, verbose: Boolean): String =
        buildString {
            if (securities.isEmpty()) {
                append("no securities in the directory\n")
                return@buildString
            }
            append(
                String.format(
                    "%-10s %-14s %-5s %-6s %-28s%n",
                    "SYMBOL", "ISIN", "SHARD", "CCY", "NAME",
                ),
            )
            securities.sortedBy { it.symbol }.forEach {
                append(
                    String.format(
                        "%-10s %-14s %-5d %-6s %-28s%n",
                        it.symbol, it.isin, it.shardId, it.currency, it.name,
                    ),
                )
                if (verbose) {
                    append(
                        String.format(
                            "           id=%d tick=%s floor=%s levels=%d%n",
                            it.securityId,
                            PriceCodec.format(it.tickSize),
                            PriceCodec.format(it.priceFloor),
                            it.levelCount,
                        ),
                    )
                    append(
                        String.format(
                            "           orders -> %s:%d   reports <- %s:%d%n",
                            it.orderEntryChannel, it.orderEntryStreamId,
                            it.executionReportChannel, it.executionReportStreamId,
                        ),
                    )
                }
            }
            append("\n${securities.size} securities, universe version $version\n")
        }
}

fun runSecurities(args: Args) {
    val config = ToolsConfig.from(args)
    val aeron = connect(config) ?: return
    aeron.use {
        val directory: DirectoryClient? =
            awaitDirectory(aeron, config, java.time.Duration.ofSeconds(args.long("timeout", 15L)))
        if (directory == null) {
            reportNoDirectory(config)
            return
        }
        print(SecuritiesReport.render(directory.securities, directory.version, args.has("verbose")))
    }
}
