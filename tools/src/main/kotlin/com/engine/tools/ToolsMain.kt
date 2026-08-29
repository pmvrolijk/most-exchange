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

    Commands:
      securities  List the tradable universe and the shard serving each security.
      send        Submit an order, routed to the gateway that owns the symbol.
      cancel      Cancel a resting order.
      book        Rebuild and print order books from the L2 depth feed.

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
