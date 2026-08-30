package com.engine.tools

/**
 * Operator CLI. Every subcommand starts by listening for a discovery broadcast, because the
 * routing table is the only thing that maps a symbol to the shard and gateway serving it.
 */
private val USAGE = """
    most -- matching engine operator tools

    Usage:
      most securities [--verbose]
      most send   --symbol SYM --side buy|sell --price P --qty Q [options]
      most cancel --symbol SYM --side buy|sell --order-id ID --orig-clordid ID [options]
      most book   [--symbol SYM[,SYM...]] [--depth N] [--refresh MS]
      most load   --symbol SYM[,SYM...] --price-min P --price-max P [options]
      most define --symbol SYM --reference P [--static-collar BPS] [--dynamic-collar BPS]
      most session --phase closed|pre-open|open-auction|continuous [--shard N]
      most purge  [--trading-date YYYYMMDD] [--shard N]
      most cluster [--dir DIR]

    Commands:
      securities  List the tradable universe and the shard serving each security.
      send        Submit an order, routed to the gateway that owns the symbol.
      cancel      Cancel a resting order.
      book        Rebuild and print order books from the L2 depth feed.
      load        Drive a shard at a fixed rate and measure round-trip latency and throughput.
      define      Seed a security's reference price and collar widths.
      session     Move a shard to a trading phase; the uncross runs on the way to continuous.
      purge       Run the off-session expiry sweep.
      cluster     Run a single-node cluster host for local development.

    Load options (the shard must already be defined and CONTINUOUS):
      --count N                Orders to send (default 1000000)
      --delay-us N             Microseconds between orders (default 10); 0 sends unpaced
      --rate N                 Orders per second, instead of --delay-us
      --warmup N               Leading orders excluded from the histograms
      --qty-min Q --qty-max Q  Quantity range (default 1..100)
      --participants N         Participant ids from --participant upward (default 4)
      --seed N                 Generator seed, so a run repeats exactly (default 42)
      --drain-ms N             Keep collecting reports this long after the last send (default 2000)
      --interval-ms N          Progress line cadence (default 1000; 0 is silent)
      --histogram FILE         Write the latency distribution for plotting

    Order options:
      --clordid ID             Client order id (default: current millis)
      --smp-id ID              Self-match prevention id (default: the participant id)
      --smp STRATEGY           aggressor (default) or resting
      --expire-date YYYYMMDD   0, the default, is good-til-cancelled
      --follow SECONDS         How long to print execution reports for (default 3)

    Connection options (defaults match the sample configs):
      --aeron-dir DIR
      --discovery-channel URI  --discovery-stream N
      --l1-channel URI         --l1-stream N
      --l2-channel URI         --l2-stream N
      --participant ID         Participant id to trade as (default 1)
      --timeout SECONDS        How long to wait for a directory (default 15)
""".trimIndent()

fun main(argv: Array<String>) {
    val command = argv.firstOrNull()
    if (command == null || command in setOf("--help", "-h", "help")) {
        println(USAGE)
        return
    }

    val args = Args(argv.drop(1).toTypedArray())
    try {
        when (command) {
            "securities" -> runSecurities(args)
            "send" -> runSend(args)
            "cancel" -> runCancel(args)
            "book" -> runBook(args)
            "load" -> runLoad(args)
            "define" -> runDefine(args)
            "session" -> runSession(args)
            "purge" -> runPurge(args)
            "cluster" -> runCluster(args)
            else -> {
                System.err.println("most: unknown command '$command'")
                println()
                println(USAGE)
            }
        }
    } catch (e: IllegalArgumentException) {
        System.err.println("most: ${e.message}")
    }
}
