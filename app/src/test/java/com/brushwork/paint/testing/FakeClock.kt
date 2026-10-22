package com.brushwork.paint.testing

import java.util.concurrent.atomic.AtomicLong

/**
 * v1.10 F1 (design 2.16; lead, frozen kit): a clock that moves only when the test moves it, for
 * the engine's time rules without waiting: the park delay (`PARK_DELAY_MS`, P2), the activation
 * ring after 250 ms (section 2.7), the notice and exit times of sections 2.11 and 2.12, the
 * `EngineStats` rings. Pass [millis] or [nanos] where the code under test takes a clock function
 * (for example a `() -> Long` field set by the test), and [advance] between steps.
 *
 * [nowMillis] is wall time (starting at [startMillis], a fixed date in 2023); [nowNanos] is a
 * monotonic count from 0 that moves with it. It reads no real clock, so a test using only this
 * clock is not a timing test (TimingTaggedTest). Any thread.
 */
class FakeClock(startMillis: Long = 1_700_000_000_000L) {
    private val nanosElapsed = AtomicLong()
    private val start = startMillis

    /** Wall time in milliseconds. */
    val nowMillis: Long get() = start + nanosElapsed.get() / 1_000_000L

    /** Monotonic nanoseconds since the clock was made. */
    val nowNanos: Long get() = nanosElapsed.get()

    /** [nowMillis] as a function, for code that takes a millisecond clock. */
    val millis: () -> Long = { nowMillis }

    /** [nowNanos] as a function, for code that takes a nanosecond clock. */
    val nanos: () -> Long = { nowNanos }

    /** Moves the clock forward by [ms] milliseconds. */
    fun advance(ms: Long) {
        require(ms >= 0) { "a clock never goes back ($ms ms)" }
        nanosElapsed.addAndGet(ms * 1_000_000L)
    }

    /** Moves the clock forward by [ns] nanoseconds. */
    fun advanceNanos(ns: Long) {
        require(ns >= 0) { "a clock never goes back ($ns ns)" }
        nanosElapsed.addAndGet(ns)
    }
}
