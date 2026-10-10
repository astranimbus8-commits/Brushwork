package com.brushwork.paint.testing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.8 F0 (design §8.5): [PerfBudget]'s retrying guards. The timed runs use a scripted clock that
 * only moves when a block runs (each run "takes" the next scripted duration), so nothing here
 * depends on the machine; the real clock is only used where the outcome can't vary (an endless
 * budget, a negative one).
 */
class PerfBudgetTest {

    /** A clock moved by [block] only: run k takes `ms[k]` milliseconds. */
    private class Script(vararg ms: Double) {
        private val left = ArrayDeque(ms.toList())
        var now = 0L
        var runs = 0
        val clock: () -> Long = { now }
        val block: () -> Unit = {
            runs++
            now += (left.removeFirst() * 1e6).toLong()
        }
    }

    @Test
    fun withinStopsAtTheFirstTryUnderBudget() {
        // The warm-up (500 ms) is not timed; tries 30, 25, then 4 ms: the third try passes.
        val s = Script(500.0, 30.0, 25.0, 4.0, 1.0, 1.0)
        val ms = PerfBudget.withinOn(s.clock, 10.0, 5, "scripted", s.block)
        assertEquals(4.0, ms, 1e-9)
        assertEquals("the warm-up and three tries", 4, s.runs)
    }

    @Test
    fun withinPassesOnTheFirstTryWithOneWarmUp() {
        val s = Script(80.0, 10.0)
        assertEquals(10.0, PerfBudget.withinOn(s.clock, 10.0, 5, "", s.block), 1e-9)
        assertEquals("a healthy guard pays one timed run", 2, s.runs)
    }

    @Test
    fun withinFailsAfterAllTriesWithEveryTimeAndTheBest() {
        val s = Script(1.0, 50.0, 20.0, 40.0, 30.0, 60.0)
        val e = assertThrows(AssertionError::class.java) { PerfBudget.withinOn(s.clock, 10.0, 5, "the scripted block", s.block) }
        assertEquals("the warm-up and five tries", 6, s.runs)
        val m = e.message!!
        assertTrue(m, m.startsWith("the scripted block: over the budget of 10.000 ms in all 5 tries"))
        assertTrue(m, m.contains("50.000, 20.000, 40.000, 30.000, 60.000 ms"))
        assertTrue(m, m.contains("best 20.000 ms"))
    }

    @Test
    fun withinHonoursTheNumberOfTries() {
        val s = Script(1.0, 50.0, 50.0)
        assertThrows(AssertionError::class.java) { PerfBudget.withinOn(s.clock, 10.0, 2, "", s.block) }
        assertEquals(3, s.runs)
        assertThrows(IllegalArgumentException::class.java) { PerfBudget.withinOn(s.clock, 10.0, 0, "", s.block) }
    }

    @Test
    fun ratioWithinStopsAtTheFirstPassingPair() {
        // a and b share the clock: warm-ups a 9, b 9; then (a 30, b 10), (a 25, b 10), (a 18, b 10).
        var now = 0L
        val aMs = ArrayDeque(listOf(9.0, 30.0, 25.0, 18.0, 1.0))
        val bMs = ArrayDeque(listOf(9.0, 10.0, 10.0, 10.0, 1.0))
        var aRuns = 0
        var bRuns = 0
        val clock = { now }
        val p = PerfBudget.ratioWithinOn(
            clock, 1.5, 5,
            a = { aRuns++; now += (aMs.removeFirst() * 1e6).toLong() },
            b = { bRuns++; now += (bMs.removeFirst() * 1e6).toLong() },
            slackMs = 3.0, what = "pair",
        )
        assertEquals(18.0 to 10.0, p)
        assertEquals("a: the warm-up and three tries", 4, aRuns)
        assertEquals("b: the warm-up and three tries", 4, bRuns)
    }

    @Test
    fun ratioWithinFailsAfterAllTriesWithTheClosestPair() {
        var now = 0L
        val aMs = ArrayDeque(listOf(1.0, 40.0, 30.0, 35.0, 50.0, 45.0))
        val bMs = ArrayDeque(listOf(1.0, 10.0, 10.0, 10.0, 10.0, 10.0))
        var aRuns = 0
        val e = assertThrows(AssertionError::class.java) {
            PerfBudget.ratioWithinOn(
                { now }, 2.0, 5,
                a = { aRuns++; now += (aMs.removeFirst() * 1e6).toLong() },
                b = { now += (bMs.removeFirst() * 1e6).toLong() },
                slackMs = 0.0, what = "",
            )
        }
        assertEquals(6, aRuns)
        val m = e.message!!
        assertTrue(m, m.startsWith("timing guard: a <= 2.000 × b failed in all 5 tries"))
        assertTrue(m, m.contains("closest a 30.000 ms vs b 10.000 ms"))
    }

    @Test
    fun measuredGuardsHaveNoWarmUpAndPassTheTryIndex() {
        val attempts = mutableListOf<Int>()
        val ms = PerfBudget.measuredWithin(5.0, what = "median") { k -> attempts += k; listOf(9.0, 7.0, 5.0, 1.0)[k] }
        assertEquals(5.0, ms, 0.0)
        assertEquals(listOf(0, 1, 2), attempts)

        val pairs = mutableListOf<Int>()
        val p = PerfBudget.measuredRatioWithin(0.35, slackMs = 15.0) { k -> pairs += k; (if (k == 0) 60.0 else 40.0) to 100.0 }
        assertEquals(40.0 to 100.0, p)
        assertEquals(listOf(0, 1), pairs)

        val e = assertThrows(AssertionError::class.java) { PerfBudget.measuredWithin(1.0, tries = 3) { 2.0 + it } }
        assertTrue(e.message!!, e.message!!.contains("2.000, 3.000, 4.000 ms (best 2.000 ms)"))
    }

    @Test
    fun untilChecksSeveralBoundsTogether() {
        val seen = mutableListOf<Int>()
        val m = PerfBudget.until(what = "both", measure = { k -> seen += k; listOf(10.0 to 1.0, 1.0 to 10.0, 1.0 to 1.0)[k] }) { (x, y) ->
            when {
                x > 5.0 -> "x $x"
                y > 5.0 -> "y $y"
                else -> null
            }
        }
        assertEquals(1.0 to 1.0, m)
        assertEquals(listOf(0, 1, 2), seen)
        val e = assertThrows(AssertionError::class.java) { PerfBudget.until<Int>(tries = 2, what = "never", measure = { k -> k }) { k -> "reason $k" } }
        assertEquals("never: over budget in all 2 tries:\n  try 1: reason 0\n  try 2: reason 1", e.message)
    }

    @Test
    fun theRealClockStopsOnTheFirstTryOrRunsEveryTry() {
        var runs = 0
        PerfBudget.within(1e9) { runs++ }
        assertEquals("an endless budget: the warm-up and one try", 2, runs)
        runs = 0
        assertThrows(AssertionError::class.java) { PerfBudget.within(-1.0) { runs++ } }
        assertEquals("a negative budget: the warm-up and ${PerfBudget.TRIES} tries", 1 + PerfBudget.TRIES, runs)
        var a = 0
        var b = 0
        PerfBudget.ratioWithin(1.0, a = { a++ }, b = { b++ }, slackMs = 1e9)
        assertEquals(2, a)
        assertEquals(2, b)
    }

    @Test
    fun timedWithinHasNoWarmUpAndKeepsThePassingRun() {
        val s = Script(7.0)
        assertEquals(PerfBudget.Timed("value", 7.0), PerfBudget.timedOn(s.clock) { s.block(); "value" })
        var runs = 0
        val first = PerfBudget.timedWithin(1e9) { ++runs }
        assertEquals("no warm-up: the first run counts", 1, first.value)
        assertEquals(1, runs)
        runs = 0
        val e = assertThrows(AssertionError::class.java) { PerfBudget.timedWithin(-1.0, tries = 3, what = "cold") { ++runs } }
        assertEquals("three tries, no warm-up", 3, runs)
        assertTrue(e.message!!, e.message!!.startsWith("cold: over the budget of -1.000 ms in all 3 tries"))
    }

    @Test
    fun budgetsScaleOnCiOnly() {
        assertEquals(4.0, PerfBudget.CI_FACTOR, 0.0)
        assertEquals(5, PerfBudget.TRIES)
        val ci = !System.getenv("CI").isNullOrEmpty()
        assertEquals(if (ci) 4.0 else 1.0, PerfBudget.factor, 0.0)
        assertEquals(12.0 * PerfBudget.factor, PerfBudget.ms(12.0), 0.0)
    }
}
