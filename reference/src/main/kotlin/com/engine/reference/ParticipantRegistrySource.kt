package com.engine.reference

import java.util.concurrent.atomic.AtomicBoolean

/**
 * A [ParticipantRegistry] that can be replaced while the process runs.
 *
 * Onboarding a participant, moving one between gateways or rotating a gateway secret is a change
 * to this file and to nothing else. Before this existed it was also a restart of every process
 * that reads it — the gateway, the consensus module and the engine — which made adding a client to
 * a shard a maintenance event on the cluster itself. The gateway still restarts, because a session
 * is bound at open and it holds no state that makes restarting it cost anything (Design.md §1);
 * the two node processes swap the file underneath themselves instead.
 *
 * **Why that is legal in the engine, and the line it must not cross.** The map the engine derives
 * from a principal is node-local *egress routing*: it is rebuilt in `onStart` from
 * `Cluster.clientSessions()`, is deliberately not snapshotted, and only the leader's egress reaches
 * anyone. So two nodes briefly holding different versions of this file cannot diverge the log, the
 * books or a snapshot — they can only disagree about where to send a report, and only one of them
 * is sending. That stops being true the moment the engine *rejects* an order on a binding, which is
 * why enforcement (`UNAUTHORIZED_PARTICIPANT`) has to arrive through the log rather than from a
 * file each node reads on its own schedule.
 *
 * Three rules, each of which is the conservative half of a choice:
 *
 *  - **A file that cannot be read or parsed is reported and ignored.** The registry in hand keeps
 *    authenticating. Standing down on a bad file would turn a typo in an editor into a shard that
 *    rejects every gateway.
 *  - **A registry for another shard is refused**, for the reason [ParticipantRegistry] is checked
 *    against the [ShardSpec] at boot: it would authenticate gateways this node never serves.
 *  - **A swap is announced with both fingerprints.** The fingerprint is the only thing that makes
 *    two nodes' registries comparable, and a reload that happened silently would be invisible at
 *    exactly the moment someone is trying to work out why a report went somewhere unexpected.
 */
class ParticipantRegistrySource(
    private val path: String,
    initial: ParticipantRegistry,
    /** Where a swap or a failure is reported. A parameter so a test can read it back. */
    private val onEvent: (String) -> Unit = { println("registry: $it") },
) : AutoCloseable {

    @Volatile
    private var current: ParticipantRegistry = initial

    private val running = AtomicBoolean(false)
    private var poller: Thread? = null

    /** Registries adopted since start, not counting the one this was constructed with. */
    @Volatile
    var reloads: Long = 0L
        private set

    /** Reads that produced no usable registry. */
    @Volatile
    var failures: Long = 0L
        private set

    /**
     * The registry in force. Read on the consensus module's and the engine's own threads, so it is
     * a single volatile read of an immutable object and never a lock.
     */
    fun registry(): ParticipantRegistry = current

    /** Re-reads [path], adopting it if it is usable and different. Returns true if it swapped. */
    fun reload(): Boolean {
        val loaded = try {
            ParticipantRegistry.load(path)
        } catch (e: Exception) {
            failures++
            onEvent("could not reload $path, keeping fingerprint ${current.fingerprint()}: ${e.message}")
            return false
        }
        val inForce = current
        if (loaded.shardId != inForce.shardId) {
            failures++
            onEvent(
                "refused $path: it is for shard ${loaded.shardId}, this node serves " +
                    "shard ${inForce.shardId}"
            )
            return false
        }
        if (loaded.fingerprintValue() == inForce.fingerprintValue()) return false
        current = loaded
        reloads++
        onEvent(
            "reloaded $path: fingerprint ${inForce.fingerprint()} -> ${loaded.fingerprint()}, " +
                "gateways=${loaded.gateways.map { it.gatewayId }}"
        )
        return true
    }

    /**
     * Polls [path] every [intervalMs] on a daemon thread. Content is compared by fingerprint
     * rather than by modification time, because a release is published to a new directory and put
     * in force by moving a symlink — the path's own timestamp need never change.
     */
    fun startPolling(intervalMs: Long) {
        require(intervalMs > 0) { "reload interval must be positive" }
        check(running.compareAndSet(false, true)) { "already polling" }
        poller = Thread({
            while (running.get()) {
                try {
                    Thread.sleep(intervalMs)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (running.get()) reload()
            }
        }, "participant-registry-reload").apply {
            isDaemon = true
            start()
        }
    }

    override fun close() {
        running.set(false)
        poller?.interrupt()
        poller = null
    }
}
