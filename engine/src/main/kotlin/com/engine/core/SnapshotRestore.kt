package com.engine.core

/**
 * Thrown when a snapshot cannot be restored into the geometry this node booted with.
 *
 * A restart is how geometry is reapplied, so the restore is the only place that can see both the
 * state that existed and the shape it is being poured into. Getting this wrong is not a degraded
 * start: dropping a book because its security is no longer listed destroys resting orders that
 * clients believe are live, and booking an order into a ladder that has moved under it throws an
 * `ArrayIndexOutOfBoundsException` at the same log position on every node at once. Refusing to
 * start is the only outcome an operator can act on.
 */
class SnapshotRestoreFailed(report: String) : RuntimeException(report)

/**
 * Collects everything the restore noticed, so an operator gets the whole picture at once rather
 * than one problem per restart. Once a fatal has been recorded the restore stops applying state
 * and keeps decoding, purely to finish the report.
 *
 * Not on any hot path: this runs once, at boot, before the node joins the cluster.
 */
class SnapshotRestoreReport(private val shardId: Int) {

    private val fatal = mutableListOf<String>()
    private val notes = mutableListOf<String>()

    val hasFatal: Boolean get() = fatal.isNotEmpty()

    fun fatal(message: String) {
        fatal += message
    }

    fun note(message: String) {
        notes += message
    }

    fun render(): String = buildString {
        appendLine("engine: shard $shardId refused to restore its snapshot.")
        appendLine()
        appendLine("The geometry this node booted with cannot hold the state the snapshot carries:")
        for (line in fatal) appendLine("  - $line")
        if (notes.isNotEmpty()) {
            appendLine()
            appendLine("Also noted:")
            for (line in notes) appendLine("  - $line")
        }
        appendLine()
        appendLine("A security may only leave a shard once its book is empty. To reapply geometry")
        appendLine("that removes or reshapes a security holding orders: close the shard, cancel or")
        appendLine("purge its resting orders, take a snapshot, then restart with the new file.")
        appendLine("Restarting with the previous security file will restore this snapshot intact.")
    }

    /** The non-fatal half, for a start that went ahead with something worth saying. */
    fun renderNotes(): String = notes.joinToString("\n") { "engine: $it" }
}
