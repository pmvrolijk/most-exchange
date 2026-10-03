package nl.lamia.most.exchange.reference

import io.aeron.Aeron
import org.agrona.concurrent.BackoffIdleStrategy
import org.agrona.concurrent.BusySpinIdleStrategy
import org.agrona.concurrent.IdleStrategy
import org.agrona.concurrent.NanoClock
import org.agrona.concurrent.SleepingIdleStrategy
import org.agrona.concurrent.SystemNanoClock
import org.agrona.concurrent.YieldingIdleStrategy
import org.agrona.concurrent.status.AtomicCounter
import java.util.Locale
import java.util.function.Supplier

/**
 * Measures how much of its core a loop spends working (Design.md §7, "Duty cycle").
 *
 * Every loop here, Aeron's agents included, does some work and then hands the amount done to an
 * [IdleStrategy]. This wraps that strategy and times the stretch between one `idle` call returning
 * and the next being made: if the next is `idle(n)` with `n > 0` the stretch was work, otherwise it
 * was polling and finding nothing. The sum is [busyNanos], and its rate of change is the fraction of
 * a core the loop is using, which is the number `ps` cannot give for a busy-spinning thread.
 *
 * **It changes nothing about idling.** Every call goes to [delegate] unchanged; wrapping costs two
 * clock reads per iteration and nothing else. Time spent inside [delegate] is never counted, so the
 * figure means the same thing whether the loop spins, yields or parks.
 *
 * **Not thread-safe, and does not need to be.** One loop owns one instance, as Aeron's agent runners
 * do. [attach] is the exception, called once from the thread that created the counter; a value it
 * publishes may be one iteration stale, which the next busy iteration corrects.
 *
 * It allocates nothing and never throws, because in the engine it runs on the service thread
 * (Design.md §7, "Instrumentation").
 */
class DutyCycleIdleStrategy(
    private val delegate: IdleStrategy,
    private val clock: NanoClock,
    counter: AtomicCounter? = null,
) : IdleStrategy {

    /** Cumulative busy time, in nanoseconds. Written only by the owning loop. */
    var busyNanos = 0L
        private set

    @Volatile
    private var counter: AtomicCounter? = counter

    private var lastReturnNanos = 0L
    private var started = false

    /**
     * Whether a loop has called `idle(workCount)` since [clearLooping]. Aeron calls some suppliers
     * more than once and runs its agent on only one of the results -- and the consensus module runs a
     * spare through start-up and then drops it -- so this is how [DutyCycles] tells the thread's
     * strategy from one that would publish a permanent, misleading 0%.
     */
    @Volatile
    var isLooping = false
        private set

    /** Clears [isLooping], so the next read says whether a loop has run here since. */
    fun clearLooping() {
        isLooping = false
    }

    /**
     * Publishes to [counter] from now on, starting with whatever has accumulated already. Aeron asks
     * for an agent's idle strategy before its own counters exist, so this cannot always be a
     * constructor argument.
     */
    fun attach(counter: AtomicCounter) {
        this.counter = counter
        counter.setRelease(busyNanos)
    }

    override fun idle(workCount: Int) {
        if (!isLooping) isLooping = true
        val now = clock.nanoTime()
        if (workCount > 0 && started) {
            val elapsed = now - lastReturnNanos
            if (elapsed > 0L) {
                busyNanos += elapsed
                counter?.setRelease(busyNanos)
            }
        }
        delegate.idle(workCount)
        markReturned()
    }

    override fun idle() {
        delegate.idle()
        markReturned()
    }

    override fun reset() = delegate.reset()

    override fun alias(): String = delegate.alias()

    private fun markReturned() {
        lastReturnNanos = clock.nanoTime()
        started = true
    }
}

/**
 * The duty-cycle wrappers of one process, and the counters they publish to.
 *
 * Each loop is wrapped under a name as its idle strategy is created; [attach] then gives every
 * wrapper not yet attached its own Aeron counter, labelled [LABEL_PREFIX] + name, once the process
 * has an Aeron client to make one with. The order matters only in the cluster-host, where Aeron
 * creates the driver's, archive's and consensus module's idle strategies while launching and the
 * client that makes their counters can only connect afterwards.
 *
 * A process with metrics off never constructs one of these, so nothing is wrapped and no clock is
 * read (Design.md §7).
 */
class DutyCycles(private val clock: NanoClock = SystemNanoClock.INSTANCE) {
    private class Entry(val name: String, val strategy: DutyCycleIdleStrategy) {
        var label: String? = null
        var sightings = 0
    }

    private val entries = ArrayList<Entry>()

    /** Wraps [delegate]. Thread-safe, because Aeron may create agents on more than one thread. */
    fun wrap(name: String, delegate: IdleStrategy): IdleStrategy = synchronized(entries) {
        DutyCycleIdleStrategy(delegate, clock).also { entries += Entry(name, it) }
    }

    /** Wraps every strategy [delegate] supplies, for Aeron's `idleStrategySupplier`. */
    fun supplier(name: String, delegate: Supplier<IdleStrategy>): Supplier<IdleStrategy> =
        Supplier { wrap(name, delegate.get()) }

    /**
     * Gives a counter to every wrapper that has no counter yet and has been looped on since each of
     * the last [confirmations] calls; returns their labels. A wrapper that is not being looped on is
     * left alone: Aeron calls some suppliers more than once and runs its agent on only one result,
     * and the consensus module runs a spare through start-up before dropping it, and a counter for a
     * spare would read as an idle thread. Two loops wrapped under one name are told apart by a
     * suffix, since two counters with one label would read as one thread at twice the rate.
     */
    fun attach(confirmations: Int = 1, newCounter: (String) -> AtomicCounter): List<String> = synchronized(entries) {
        val labels = ArrayList<String>()
        for (entry in entries) {
            if (entry.label != null) continue
            entry.sightings = if (entry.strategy.isLooping) entry.sightings + 1 else 0
            entry.strategy.clearLooping()
            if (entry.sightings < confirmations) continue
            val sameName = entries.count { it.label != null && it.name == entry.name }
            val label = LABEL_PREFIX + entry.name + if (sameName == 0) "" else " #${sameName + 1}"
            entry.strategy.attach(newCounter(label))
            entry.label = label
            labels += label
        }
        labels
    }

    /**
     * Attaches from a daemon thread for [windowMs] after start-up, reporting each batch of labels.
     * A process's loops start on their own threads once it has launched, so at the moment launch
     * returns some may not have run yet; polling for a few seconds catches them without making the
     * process wait. A loop must still be running a second after it is first seen, which is what
     * leaves the consensus module's start-up spare out. An Aeron client that closes meanwhile ends
     * the thread quietly. Wall clock is fine here: this thread is not the state machine.
     */
    fun attachInBackground(aeron: Aeron, report: (List<String>) -> Unit, windowMs: Long = ATTACH_WINDOW_MS) {
        val thread = Thread({
            val deadline = System.currentTimeMillis() + windowMs
            try {
                while (System.currentTimeMillis() < deadline && !aeron.isClosed) {
                    val labels = attach(CONFIRMATIONS) { label -> aeron.addCounter(COUNTER_TYPE_ID, label) }
                    if (labels.isNotEmpty()) report(labels)
                    Thread.sleep(ATTACH_POLL_MS)
                }
            } catch (_: InterruptedException) {
                // Shutting down; nothing left to attach to.
            } catch (_: io.aeron.exceptions.AeronException) {
                // The client closed between the check and the call.
            }
        }, "duty-cycle-attach")
        thread.isDaemon = true
        thread.start()
    }

    companion object {
        /**
         * Outside Aeron's own ranges, so `most counters` can recognise a duty counter by type and
         * print it as a share of a core.
         */
        const val COUNTER_TYPE_ID = 9_100
        const val LABEL_PREFIX = "duty-ns: "
        private const val ATTACH_WINDOW_MS = 10_000L
        private const val ATTACH_POLL_MS = 50L
        private const val CONFIRMATIONS = 20 // one second of polls
    }
}

/**
 * A configured idle strategy: `busyspin` (the default), `backoff`, `yielding`, or `sleeping` /
 * `sleeping:<µs>` (Design.md §7, "Duty cycle").
 *
 * A spec rather than an instance because Aeron gives each agent its own strategy — several of them
 * keep per-thread state — so [create] makes a new one on every call.
 *
 * Busy-spin is the default because it is what production runs and what every figure in
 * `Measurements.md` was taken with. The others spare a development machine's cores. They are not a
 * way to measure utilisation: that is [DutyCycleIdleStrategy], which reads the same under any of them.
 */
class IdleStrategySpec private constructor(val name: String, val sleepNanos: Long) {

    fun create(): IdleStrategy = when (name) {
        BUSYSPIN -> BusySpinIdleStrategy()
        BACKOFF -> BackoffIdleStrategy()
        YIELDING -> YieldingIdleStrategy()
        else -> SleepingIdleStrategy(sleepNanos)
    }

    override fun equals(other: Any?): Boolean =
        other is IdleStrategySpec && other.name == name && other.sleepNanos == sleepNanos

    override fun hashCode(): Int = 31 * name.hashCode() + sleepNanos.hashCode()

    override fun toString(): String =
        if (name == SLEEPING) "$SLEEPING:${sleepNanos / NANOS_PER_MICRO}" else name

    companion object {
        private const val BUSYSPIN = "busyspin"
        private const val BACKOFF = "backoff"
        private const val YIELDING = "yielding"
        private const val SLEEPING = "sleeping"
        private const val NANOS_PER_MICRO = 1_000L
        private const val DEFAULT_SLEEP_MICROS = 1L
        private const val ACCEPTED = "busyspin, backoff, yielding, sleeping or sleeping:<µs>"

        val BUSY_SPIN = IdleStrategySpec(BUSYSPIN, 0L)

        /** Parses a configured value; null or blank is [BUSY_SPIN]. */
        fun parse(text: String?): IdleStrategySpec {
            val value = text?.trim()?.lowercase(Locale.ROOT)
            if (value.isNullOrEmpty()) return BUSY_SPIN
            val name = value.substringBefore(':')
            val argument = if (':' in value) value.substringAfter(':') else null
            return when (name) {
                BUSYSPIN, BACKOFF, YIELDING -> {
                    require(argument == null) { "idle strategy '$name' takes no argument, got '$text'" }
                    if (name == BUSYSPIN) BUSY_SPIN else IdleStrategySpec(name, 0L)
                }
                SLEEPING -> {
                    val micros = if (argument == null) DEFAULT_SLEEP_MICROS else argument.toLongOrNull()
                    require(micros != null && micros > 0L) {
                        "sleeping takes a positive number of microseconds, got '$text'"
                    }
                    IdleStrategySpec(SLEEPING, micros * NANOS_PER_MICRO)
                }
                else -> throw IllegalArgumentException("unknown idle strategy '$text': expected $ACCEPTED")
            }
        }
    }
}
