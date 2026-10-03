package nl.lamia.most.exchange.reference

import org.agrona.concurrent.BackoffIdleStrategy
import org.agrona.concurrent.BusySpinIdleStrategy
import org.agrona.concurrent.IdleStrategy
import org.agrona.concurrent.NanoClock
import org.agrona.concurrent.SleepingIdleStrategy
import org.agrona.concurrent.UnsafeBuffer
import org.agrona.concurrent.YieldingIdleStrategy
import org.agrona.concurrent.status.AtomicCounter
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Expected values are written from Design.md §7, "Duty cycle — how full a thread is", not from the
 * implementation. Each test names the clause it holds.
 */
class DutyCycleIdleStrategyTest {

    private class ManualClock(var now: Long = 1_000L) : NanoClock {
        override fun nanoTime(): Long = now
    }

    /** Records every call, and can spend clock time inside `idle` the way a parking strategy does. */
    private class RecordingIdle(private val clock: ManualClock, var spendInside: Long = 0L) : IdleStrategy {
        val calls = mutableListOf<String>()
        override fun idle(workCount: Int) {
            calls += "idle($workCount)"
            clock.now += spendInside
        }
        override fun idle() {
            calls += "idle()"
            clock.now += spendInside
        }
        override fun reset() {
            calls += "reset()"
        }
        override fun alias(): String = "recording"
    }

    private fun counter(): AtomicCounter = AtomicCounter(UnsafeBuffer(ByteBuffer.allocateDirect(COUNTER_BYTES)), 0)

    // ------------------------------------------------------------------ what counts as busy

    @Test
    fun `an iteration closed by idle with work is busy for its whole length`() {
        val clock = ManualClock()
        val duty = DutyCycleIdleStrategy(RecordingIdle(clock), clock)

        duty.idle(0) // opens the first iteration
        clock.now += 300
        duty.idle(5)

        assertEquals(300L, duty.busyNanos)
    }

    @Test
    fun `an iteration closed by idle with no work is not busy`() {
        // "Polling and finding nothing is idling."
        val clock = ManualClock()
        val duty = DutyCycleIdleStrategy(RecordingIdle(clock), clock)

        duty.idle(0)
        clock.now += 300
        duty.idle(0)

        assertEquals(0L, duty.busyNanos)
    }

    @Test
    fun `an iteration closed by the no-argument idle is not busy`() {
        val clock = ManualClock()
        val duty = DutyCycleIdleStrategy(RecordingIdle(clock), clock)

        duty.idle(0)
        clock.now += 300
        duty.idle()

        assertEquals(0L, duty.busyNanos)
    }

    @Test
    fun `time spent inside the wrapped strategy is never busy`() {
        // A parked or yielding strategy spends 10 µs in each idle call; only the 200 ns of work
        // between the calls is the thread's own.
        val clock = ManualClock()
        val duty = DutyCycleIdleStrategy(RecordingIdle(clock, spendInside = 10_000L), clock)

        duty.idle(0)
        clock.now += 200
        duty.idle(1)
        clock.now += 200
        duty.idle(1)

        assertEquals(400L, duty.busyNanos)
    }

    @Test
    fun `busy time is the sum over busy iterations only`() {
        val clock = ManualClock()
        val duty = DutyCycleIdleStrategy(RecordingIdle(clock), clock)

        duty.idle(0)
        clock.now += 100; duty.idle(3)   // busy 100
        clock.now += 50; duty.idle(0)    // idle
        clock.now += 50; duty.idle()     // idle
        clock.now += 250; duty.idle(1)   // busy 250

        assertEquals(350L, duty.busyNanos)
    }

    @Test
    fun `the first iteration is not counted because it has no start`() {
        val clock = ManualClock(now = 5_000_000L)
        val duty = DutyCycleIdleStrategy(RecordingIdle(clock), clock)

        duty.idle(7) // closes nothing: there was no earlier return to measure from

        assertEquals(0L, duty.busyNanos)
    }

    @Test
    fun `a clock running backwards counts zero and does not throw`() {
        val clock = ManualClock()
        val duty = DutyCycleIdleStrategy(RecordingIdle(clock), clock)

        duty.idle(0)
        clock.now -= 500
        duty.idle(1)

        assertEquals(0L, duty.busyNanos)
    }

    // ------------------------------------------------------------------ it changes nothing

    @Test
    fun `every call reaches the wrapped strategy unchanged`() {
        val clock = ManualClock()
        val inner = RecordingIdle(clock)
        val duty = DutyCycleIdleStrategy(inner, clock)

        duty.idle(4)
        duty.idle()
        duty.reset()
        duty.idle(0)

        assertEquals(listOf("idle(4)", "idle()", "reset()", "idle(0)"), inner.calls)
        assertEquals("recording", duty.alias())
    }

    // ------------------------------------------------------------------ publication

    @Test
    fun `busy time is published as cumulative nanoseconds`() {
        val clock = ManualClock()
        val published = counter()
        val duty = DutyCycleIdleStrategy(RecordingIdle(clock), clock, published)

        duty.idle(0)
        clock.now += 120; duty.idle(1)
        assertEquals(120L, published.get())
        clock.now += 80; duty.idle(1)
        assertEquals(200L, published.get())
    }

    @Test
    fun `busy time accumulated before the counter is attached is published on attach`() {
        // Aeron asks for an agent's idle strategy before its own counters exist.
        val clock = ManualClock()
        val duty = DutyCycleIdleStrategy(RecordingIdle(clock), clock)
        duty.idle(0)
        clock.now += 700; duty.idle(2)

        val published = counter()
        duty.attach(published)
        assertEquals(700L, published.get(), "not lost")

        clock.now += 300; duty.idle(2)
        assertEquals(1_000L, published.get())
    }

    // ------------------------------------------------------------------ allocation

    /**
     * "It allocates nothing." Same criterion as `engine`'s `AllocationTest` (CLAUDE.md, "Zero
     * allocation"): a strict majority of eight windows reading exactly zero, and under one byte per
     * operation overall. A real clock and a real busy-spin delegate, because that is what it wraps
     * in the engine.
     */
    @Test
    fun `wrapping and publishing allocate nothing per iteration`() {
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val duty = DutyCycleIdleStrategy(BusySpinIdleStrategy(), org.agrona.concurrent.SystemNanoClock.INSTANCE, counter())
        var work = 0
        val round = { duty.idle(work++ and 1) }

        repeat(WARMUP) { round() }
        var zeroWindows = 0
        var total = 0L
        repeat(WINDOWS) {
            val before = threads.currentThreadAllocatedBytes
            repeat(WINDOW) { round() }
            val bytes = threads.currentThreadAllocatedBytes - before
            if (bytes == 0L) zeroWindows++
            total += bytes
        }
        assertTrue(zeroWindows > WINDOWS / 2, "allocated in ${WINDOWS - zeroWindows} of $WINDOWS windows ($total bytes)")
        assertTrue(total < WINDOW.toLong() * WINDOWS, "$total bytes over ${WINDOW.toLong() * WINDOWS} iterations")
        assertTrue(duty.busyNanos > 0L, "sanity: busy iterations were measured")
    }

    // ------------------------------------------------------------------ one process's wrappers

    @Test
    fun `each looping thread gets its own labelled counter, and busy time reaches it`() {
        val clock = ManualClock()
        val duty = DutyCycles(clock)
        val conductor = duty.wrap("driver conductor", RecordingIdle(clock))
        val consensus = duty.wrap("consensus-module", RecordingIdle(clock))
        conductor.idle(0)
        consensus.idle(0)

        val made = linkedMapOf<String, AtomicCounter>()
        val labels = duty.attach { label -> counter().also { made[label] = it } }

        assertEquals(listOf("duty-ns: driver conductor", "duty-ns: consensus-module"), labels)
        clock.now += 40; conductor.idle(1)
        assertEquals(40L, made.getValue("duty-ns: driver conductor").get())
        assertEquals(0L, made.getValue("duty-ns: consensus-module").get())
    }

    @Test
    fun `a strategy no loop has run on gets no counter, since it would read as an idle thread`() {
        // Aeron calls some suppliers twice and runs its agent on one result. Observed live: the
        // spare published a permanent 0% beside the real thread in a run-attribution.sh run.
        val clock = ManualClock()
        val duty = DutyCycles(clock)
        val supplier = duty.supplier("archive") { RecordingIdle(clock) }
        supplier.get()                  // the spare
        val agent = supplier.get()      // the one the agent runs on
        agent.idle(0)

        assertEquals(listOf("duty-ns: archive"), duty.attach { counter() })
    }

    @Test
    fun `a strategy looped on at start-up and then dropped gets no counter, the one still looping does`() {
        // The consensus module's case, also observed live: its spare ran a loop through start-up
        // and stopped, so "has ever looped" attached both. The test is "is still looping".
        val clock = ManualClock()
        val duty = DutyCycles(clock)
        val supplier = duty.supplier("consensus-module") { RecordingIdle(clock) }
        val startup = supplier.get()
        val agent = supplier.get()

        startup.idle(0); agent.idle(0)
        assertEquals(emptyList(), duty.attach(confirmations = 2) { counter() }, "one sighting is not enough")
        agent.idle(0) // start-up has finished; only the agent is still looping
        assertEquals(listOf("duty-ns: consensus-module"), duty.attach(confirmations = 2) { counter() })
        startup.idle(0) // even if the spare loops once more, it must start its count again
        assertEquals(emptyList(), duty.attach(confirmations = 2) { counter() })
    }

    @Test
    fun `a loop that starts after attach is attached by the next call, and no counter is made twice`() {
        val clock = ManualClock()
        val duty = DutyCycles(clock)
        duty.wrap("archive", RecordingIdle(clock)).idle(0)
        val recorder = duty.wrap("archive recorder", RecordingIdle(clock))
        assertEquals(listOf("duty-ns: archive"), duty.attach { counter() })

        recorder.idle(0)
        assertEquals(listOf("duty-ns: archive recorder"), duty.attach { counter() })
        assertEquals(emptyList(), duty.attach { counter() })
    }

    @Test
    fun `two looping threads under one name are told apart rather than summed into one label`() {
        val clock = ManualClock()
        val duty = DutyCycles(clock)
        val supplier = duty.supplier("consensus-module") { RecordingIdle(clock) }
        supplier.get().idle(0)
        supplier.get().idle(0)

        assertEquals(
            listOf("duty-ns: consensus-module", "duty-ns: consensus-module #2"),
            duty.attach { counter() },
        )
    }

    // ------------------------------------------------------------------ configuration

    @Test
    fun `each configured name makes the strategy it names`() {
        assertIs<BusySpinIdleStrategy>(IdleStrategySpec.parse("busyspin").create())
        assertIs<BackoffIdleStrategy>(IdleStrategySpec.parse("backoff").create())
        assertIs<YieldingIdleStrategy>(IdleStrategySpec.parse("yielding").create())
        assertIs<SleepingIdleStrategy>(IdleStrategySpec.parse("sleeping").create())
        assertIs<SleepingIdleStrategy>(IdleStrategySpec.parse("sleeping:250").create())
    }

    @Test
    fun `busyspin is the default when nothing is configured`() {
        assertEquals(IdleStrategySpec.BUSY_SPIN, IdleStrategySpec.parse(null))
        assertEquals(IdleStrategySpec.BUSY_SPIN, IdleStrategySpec.parse("  "))
    }

    @Test
    fun `sleeping is 1 µs unless given, and takes microseconds`() {
        assertEquals(1_000L, IdleStrategySpec.parse("sleeping").sleepNanos)
        assertEquals(250_000L, IdleStrategySpec.parse("sleeping:250").sleepNanos)
    }

    @Test
    fun `names are read case-insensitively and trimmed`() {
        assertEquals(IdleStrategySpec.parse("backoff"), IdleStrategySpec.parse(" BackOff "))
    }

    @Test
    fun `each call to create makes a fresh instance, since Aeron gives every agent its own`() {
        val spec = IdleStrategySpec.parse("backoff")
        assertTrue(spec.create() !== spec.create())
    }

    @Test
    fun `an unknown or malformed name is refused, naming what is accepted`() {
        val unknown = assertFailsWith<IllegalArgumentException> { IdleStrategySpec.parse("spinning") }
        assertTrue("busyspin" in unknown.message!!, unknown.message)
        assertFailsWith<IllegalArgumentException> { IdleStrategySpec.parse("sleeping:abc") }
        assertFailsWith<IllegalArgumentException> { IdleStrategySpec.parse("sleeping:0") }
        assertFailsWith<IllegalArgumentException> { IdleStrategySpec.parse("backoff:5") }
    }

    private companion object {
        const val COUNTER_BYTES = 128
        const val WARMUP = 100_000
        const val WINDOW = 100_000
        const val WINDOWS = 8
    }
}
