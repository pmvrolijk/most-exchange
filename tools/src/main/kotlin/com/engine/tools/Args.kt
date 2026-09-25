package com.engine.tools

/**
 * Minimal `--key value` / `--flag` parsing. A dependency-free CLI is easier to ship than a nice one.
 *
 * [booleanFlags] must name every option that takes no value. Without it `--verbose report` reads
 * as `verbose=report` and silently eats the following argument, because a parser with no schema
 * cannot tell a flag from an option whose value happens not to start with a dash.
 */
class Args(argv: Array<String>, booleanFlags: Set<String> = BOOLEAN_FLAGS) {

    private val values = mutableMapOf<String, String>()
    private val flags = mutableSetOf<String>()
    val positional = mutableListOf<String>()

    init {
        var i = 0
        while (i < argv.size) {
            val token = argv[i]
            if (token.startsWith("--")) {
                val name = token.removePrefix("--")
                val next = argv.getOrNull(i + 1)
                if (name in booleanFlags || next == null || next.startsWith("--")) {
                    flags += name
                    i++
                } else {
                    values[name] = next
                    i += 2
                }
            } else {
                positional += token
                i++
            }
        }
    }

    fun has(name: String): Boolean = name in flags || name in values

    fun optional(name: String): String? = values[name]

    fun required(name: String): String =
        values[name] ?: throw IllegalArgumentException("missing required option --$name")

    fun int(name: String, default: Int): Int =
        values[name]?.toIntOrNull() ?: values[name]?.let {
            throw IllegalArgumentException("--$name must be a number, got '$it'")
        } ?: default

    fun long(name: String, default: Long): Long =
        values[name]?.toLongOrNull() ?: values[name]?.let {
            throw IllegalArgumentException("--$name must be a number, got '$it'")
        } ?: default

    fun requiredLong(name: String): Long =
        required(name).toLongOrNull()
            ?: throw IllegalArgumentException("--$name must be a number, got '${values[name]}'")

    companion object {
        val BOOLEAN_FLAGS = setOf("verbose", "help", "fresh", "keep", "all")
    }
}
