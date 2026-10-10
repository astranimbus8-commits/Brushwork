package com.brushwork.paint.testing

import java.util.Locale

/**
 * Time budgets of performance guard tests. The budgets are tuned on a developer desktop; the
 * shared CI runners (GitHub Actions sets CI=true) are several times slower under Robolectric, so
 * there every budget is multiplied by [CI_FACTOR]. The guards still catch order-of-magnitude
 * regressions there.
 *
 * v1.8 (F0, design §8.5): a timing guard measures until it passes. [within] times a block,
 * [ratioWithin] a pair of blocks back to back; [measuredWithin], [measuredRatioWithin] and
 * [until] take the caller's own measurement (a median, an average, a step with side effects that
 * each try sets up again). Every guard stops at the FIRST try within its budget, so a healthy
 * guard pays one run; it fails only when all its tries are over, with every measurement (and the
 * best one) in the message. Budgets are used as given: callers scale them with [ms] where they
 * always did, so a guard's budget reads the same before and after it retries. Every test class
 * that times anything carries `@Category(Timing::class)` ([Timing], checked by TimingTaggedTest).
 */
object PerfBudget {
    const val CI_FACTOR = 4.0

    /** Tries of a timing guard before it fails. */
    const val TRIES = 5

    val factor: Double = if (System.getenv("CI").isNullOrEmpty()) 1.0 else CI_FACTOR

    /** [desktopMs] scaled for the machine the tests run on. */
    fun ms(desktopMs: Double): Double = desktopMs * factor

    /** Wall time of one run of [block], ms. */
    fun timeMs(block: () -> Unit): Double = timeMsOn(System::nanoTime, block)

    /** A block's result and the wall time (ms) of the run that gave it. */
    data class Timed<T>(val value: T, val ms: Double)

    /** One run of [block]: its result and its wall time. */
    fun <T> timed(block: () -> T): Timed<T> = timedOn(System::nanoTime, block)

    /**
     * Up to [tries] runs of [block], WITHOUT a warm-up (a one-off step whose first run is the one
     * that counts, as a cold start), stopping at the first that takes at most [budgetMs]. Returns
     * that run's result and time; throws [AssertionError] with every time and the best one when
     * all [tries] are over. For a block whose result the test goes on with.
     */
    fun <T> timedWithin(budgetMs: Double, tries: Int = TRIES, what: String = "", block: () -> T): Timed<T> {
        var last: Timed<T>? = null
        measuredWithin(budgetMs, tries, what) { timed(block).also { last = it }.ms }
        return last!!
    }

    /**
     * One untimed warm-up run of [block], then up to [tries] timed runs, stopping at the first one
     * that takes at most [budgetMs]. Returns that run's time (ms); throws [AssertionError] naming
     * [what], the budget, every run's time and the best one when all [tries] are over.
     */
    fun within(budgetMs: Double, tries: Int = TRIES, what: String = "", block: () -> Unit): Double =
        withinOn(System::nanoTime, budgetMs, tries, what, block)

    /**
     * A paired ratio: one untimed warm-up of [a] and of [b], then up to [tries] tries, each timing
     * [a] (the thing under test) and right after it [b] (the reference). A try passes when
     * `a <= limit · b + slackMs`; the first one that does stops the guard. Returns that try's
     * times (a, b) in ms; throws [AssertionError] with every try's pair and the closest one when
     * all [tries] are over.
     */
    fun ratioWithin(
        limit: Double,
        tries: Int = TRIES,
        a: () -> Unit,
        b: () -> Unit,
        slackMs: Double = 0.0,
        what: String = "",
    ): Pair<Double, Double> = ratioWithinOn(System::nanoTime, limit, tries, a, b, slackMs, what)

    /**
     * [within] for a measurement the caller makes: [measure] (given the try's index, 0-based)
     * returns ms, for instance the median of several runs or a step set up again each try. No
     * warm-up is added (the measurement includes its own). Returns the first measurement within
     * [budgetMs]; throws [AssertionError] with every measurement and the best one otherwise.
     */
    fun measuredWithin(budgetMs: Double, tries: Int = TRIES, what: String = "", measure: (attempt: Int) -> Double): Double {
        require(tries >= 1) { "tries $tries" }
        val seen = ArrayList<Double>(tries)
        for (attempt in 0 until tries) {
            val ms = measure(attempt)
            if (ms <= budgetMs) return ms
            seen += ms
        }
        throw AssertionError("${label(what)}: over the budget of ${f(budgetMs)} ms in all $tries tries: ${seen.joinToString { f(it) }} ms (best ${f(seen.min())} ms)")
    }

    /**
     * [ratioWithin] for a measurement the caller makes: [measure] (given the try's index) returns
     * the pair (a, b) in ms, a the thing under test and b its reference. A try passes when
     * `a <= limit · b + slackMs`. Returns the first passing pair; throws [AssertionError] with
     * every pair and the closest one otherwise.
     */
    fun measuredRatioWithin(
        limit: Double,
        tries: Int = TRIES,
        slackMs: Double = 0.0,
        what: String = "",
        measure: (attempt: Int) -> Pair<Double, Double>,
    ): Pair<Double, Double> {
        require(tries >= 1) { "tries $tries" }
        val seen = ArrayList<Pair<Double, Double>>(tries)
        for (attempt in 0 until tries) {
            val p = measure(attempt)
            if (p.first <= limit * p.second + slackMs) return p
            seen += p
        }
        throw ratioFailure(what, limit, slackMs, seen)
    }

    /**
     * The general form: up to [tries] measurements by [measure] (given the try's index), stopping
     * at the first one for which [failure] returns null (it returns the reason otherwise, for
     * instance two bounds checked together). Returns that measurement; throws [AssertionError]
     * listing every try's reason when all [tries] fail.
     */
    fun <T> until(tries: Int = TRIES, what: String = "", measure: (attempt: Int) -> T, failure: (T) -> String?): T {
        require(tries >= 1) { "tries $tries" }
        val reasons = ArrayList<String>(tries)
        for (attempt in 0 until tries) {
            val m = measure(attempt)
            val reason = failure(m) ?: return m
            reasons += reason
        }
        throw AssertionError("${label(what)}: over budget in all $tries tries:\n" + reasons.mapIndexed { i, r -> "  try ${i + 1}: $r" }.joinToString("\n"))
    }

    // ------------------------------------------------------------------ with a given clock (PerfBudgetTest)

    /** [timeMs] reading [clock] (nanoseconds). */
    internal fun timeMsOn(clock: () -> Long, block: () -> Unit): Double {
        val t0 = clock()
        block()
        return (clock() - t0) / 1e6
    }

    /** [timed] reading [clock] (nanoseconds). */
    internal fun <T> timedOn(clock: () -> Long, block: () -> T): Timed<T> {
        val t0 = clock()
        val v = block()
        return Timed(v, (clock() - t0) / 1e6)
    }

    /** [within] reading [clock] (nanoseconds). */
    internal fun withinOn(clock: () -> Long, budgetMs: Double, tries: Int, what: String, block: () -> Unit): Double {
        require(tries >= 1) { "tries $tries" }
        block()
        return measuredWithin(budgetMs, tries, what) { timeMsOn(clock, block) }
    }

    /** [ratioWithin] reading [clock] (nanoseconds). */
    internal fun ratioWithinOn(
        clock: () -> Long,
        limit: Double,
        tries: Int,
        a: () -> Unit,
        b: () -> Unit,
        slackMs: Double,
        what: String,
    ): Pair<Double, Double> {
        require(tries >= 1) { "tries $tries" }
        a()
        b()
        return measuredRatioWithin(limit, tries, slackMs, what) { timeMsOn(clock, a) to timeMsOn(clock, b) }
    }

    private fun ratioFailure(what: String, limit: Double, slackMs: Double, seen: List<Pair<Double, Double>>): AssertionError {
        val closest = seen.minBy { it.first - (limit * it.second + slackMs) }
        val rule = "a <= ${f(limit)} × b" + if (slackMs != 0.0) " + ${f(slackMs)} ms" else ""
        return AssertionError(
            "${label(what)}: $rule failed in all ${seen.size} tries: " +
                seen.joinToString { "a ${f(it.first)} / b ${f(it.second)} ms" } +
                " (closest a ${f(closest.first)} ms vs b ${f(closest.second)} ms)",
        )
    }

    private fun label(what: String) = what.ifEmpty { "timing guard" }

    private fun f(v: Double) = String.format(Locale.ROOT, "%.3f", v)
}
