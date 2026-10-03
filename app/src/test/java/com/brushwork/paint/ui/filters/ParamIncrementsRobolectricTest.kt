package com.brushwork.paint.ui.filters

import com.brushwork.paint.AppSettings
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.model.IncrementKind
import com.brushwork.paint.snap.Increments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.math.abs

/**
 * v1.6 §3.4: a filter parameter slider's increment (kind or custom key, the design's inference
 * made explicit) and its −/+ buttons: one press goes to the next multiple of the step, the range
 * ends stay reachable; with increments off (or no step for the parameter) the −/+ are exactly
 * v1.5's (I8).
 */
@RunWith(RobolectricTestRunner::class)
class ParamIncrementsRobolectricTest {
    private val exposure = FilterParam.Slider("exposure", "Exposure", -5f, 5f, 0f, 0.01f, suffix = "EV")
    private val hue = FilterParam.Slider("hue", "Hue", -180f, 180f, 0f, 1f, "°")
    private val amount = FilterParam.Slider("amount", "Amount", 0f, 100f, 100f, 1f, "%")
    private val smoothing = FilterParam.Slider("smoothing", "Smoothing", 0f, 10f, 0f, 0.1f, pixels = true)
    private val contrast = FilterParam.Slider("contrast", "Contrast", -100f, 100f, 0f, 1f)
    private val all = listOf(exposure, hue, amount, smoothing, contrast)

    private fun increments(on: Boolean): Increments {
        val settings = AppSettings(RuntimeEnvironment.getApplication())
        settings.prefs.edit().clear().commit()
        return Increments(settings).also { inc -> if (on) inc.update { it.copy(enabled = true) } }
    }

    @Test
    fun theKindOrCustomKeyFollowsTheDesignsInference() {
        assertNull(ParamIncrements.kindOf(exposure))
        assertEquals("Exposure|EV", ParamIncrements.keyOf(exposure))
        assertEquals(IncrementKind.ANGLE, ParamIncrements.kindOf(hue))
        assertNull(ParamIncrements.keyOf(hue))
        assertEquals(IncrementKind.PERCENT, ParamIncrements.kindOf(amount))
        // A distance in image px steps by Size, like every other px slider of the app.
        assertEquals(IncrementKind.SIZE, ParamIncrements.kindOf(smoothing))
        assertNull(ParamIncrements.keyOf(smoothing))
        assertNull(ParamIncrements.kindOf(contrast))
        assertEquals("Contrast|", ParamIncrements.keyOf(contrast))
    }

    /**
     * The unit table, kept equal to the shared number controls' own inference
     * (`IncrementStepping.kindForSuffix` in ui/common/NumberSliderMath.kt, area G): the filter
     * sliders pass their kind or key explicitly, and a slider elsewhere showing the same unit must
     * step the same way. Change both together.
     */
    @Test
    fun theUnitTableIsTheSharedNumberControlsOne() {
        assertEquals(IncrementKind.PERCENT, ParamIncrements.kindForSuffix("%"))
        assertEquals(IncrementKind.PERCENT, ParamIncrements.kindForSuffix(" % of the path"))
        assertEquals(IncrementKind.ANGLE, ParamIncrements.kindForSuffix("°"))
        assertEquals(IncrementKind.ANGLE, ParamIncrements.kindForSuffix("°/s"))
        assertEquals(IncrementKind.SIZE, ParamIncrements.kindForSuffix("px"))
        assertEquals(IncrementKind.SIZE, ParamIncrements.kindForSuffix(" px "))
        assertNull(ParamIncrements.kindForSuffix("px/s"))
        assertNull(ParamIncrements.kindForSuffix("EV"))
        assertNull(ParamIncrements.kindForSuffix(""))
        // Every slider of every registered filter: a kind or a key, never both, never neither.
        for (f in FilterRegistry.all) for (p in f.params.filterIsInstance<FilterParam.Slider>()) {
            val kind = ParamIncrements.kindOf(p)
            val key = ParamIncrements.keyOf(p)
            assertTrue("${f.id}.${p.key}: exactly one of kind / key", (kind == null) != (key == null))
            if (p.pixels || p.suffix == "px") assertEquals("${f.id}.${p.key}", IncrementKind.SIZE, kind)
        }
    }

    @Test
    fun aPxSliderStepsByTheSizeStep() {
        val on = increments(on = true)
        // Size defaults to 1 px.
        assertEquals(1f, ParamIncrements.stepOf(smoothing, on)!!, 0f)
        assertEquals(1f, ParamIncrements.nudge(smoothing, 0.3f, 1, on), 1e-6f)
        assertEquals(0f, ParamIncrements.nudge(smoothing, 0.3f, -1, on), 1e-6f)
        on.update { it.with(IncrementKind.SIZE, 2.5f) }
        assertEquals(2.5f, ParamIncrements.nudge(smoothing, 0f, 1, on), 1e-6f)
        assertEquals(5f, ParamIncrements.nudge(smoothing, 2.5f, 1, on), 1e-6f)
        assertEquals(10f, ParamIncrements.nudge(smoothing, 9f, 1, on), 1e-6f)
        // A custom step under the old "label|px" key no longer applies (the kind wins).
        on.update { it.withCustom("Smoothing|px", 0.2f) }
        assertEquals(2.5f, ParamIncrements.stepOf(smoothing, on)!!, 0f)
    }

    @Test
    fun offTheButtonsAreExactlyV15() {
        val off = increments(on = false)
        off.update { it.withCustom("Exposure|EV", 0.25f) }
        for (p in all) for (v in listOf(p.min, p.default, (p.min + p.max) / 3f, p.max)) for (d in listOf(-1, 1)) {
            assertEquals("${p.label} $v $d", SliderFormat.nudge(p, v, d), ParamIncrements.nudge(p, v, d, off), 0f)
            assertEquals(SliderFormat.nudge(p, v, d), ParamIncrements.nudge(p, v, d, null), 0f)
        }
        // On, but no step for the parameter: its own nudge.
        val on = increments(on = true)
        assertEquals(SliderFormat.nudge(contrast, 7f, 1), ParamIncrements.nudge(contrast, 7f, 1, on), 0f)
        assertEquals(SliderFormat.nudge(exposure, 0.13f, 1), ParamIncrements.nudge(exposure, 0.13f, 1, on), 0f)
    }

    @Test
    fun onEachPressGoesToTheNextMultipleOfTheStep() {
        val on = increments(on = true)
        on.update { it.withCustom("Exposure|EV", 0.25f) }
        assertEquals(0.25f, ParamIncrements.nudge(exposure, 0f, 1, on), 1e-6f)
        assertEquals(0.5f, ParamIncrements.nudge(exposure, 0.25f, 1, on), 1e-6f)
        // Between two multiples: the nearer one that way.
        assertEquals(0.25f, ParamIncrements.nudge(exposure, 0.13f, 1, on), 1e-6f)
        assertEquals(0f, ParamIncrements.nudge(exposure, 0.13f, -1, on), 1e-6f)
        assertEquals(-0.25f, ParamIncrements.nudge(exposure, 0f, -1, on), 1e-6f)
        // The range ends stay reachable, and the value never leaves the range.
        assertEquals(5f, ParamIncrements.nudge(exposure, 4.9f, 1, on), 1e-6f)
        assertEquals(5f, ParamIncrements.nudge(exposure, 5f, 1, on), 1e-6f)
        assertEquals(-5f, ParamIncrements.nudge(exposure, -4.9f, -1, on), 1e-6f)
        // Angles (15°) and percentages (5 %) in the slider's own units.
        assertEquals(15f, ParamIncrements.nudge(hue, 7f, 1, on), 0f)
        assertEquals(0f, ParamIncrements.nudge(hue, 7f, -1, on), 0f)
        assertEquals(-15f, ParamIncrements.nudge(hue, 0f, -1, on), 0f)
        assertEquals(100f, ParamIncrements.nudge(amount, 98f, 1, on), 0f)
        assertEquals(95f, ParamIncrements.nudge(amount, 100f, -1, on), 0f)
        assertEquals(0f, ParamIncrements.nudge(amount, 3f, -1, on), 0f)
        assertEquals(0.25f, ParamIncrements.stepOf(exposure, on)!!, 0f)
        assertEquals(5f, ParamIncrements.stepOf(amount, on)!!, 0f)
        assertEquals(15f, ParamIncrements.stepOf(hue, on)!!, 0f)
    }

    @Test
    fun aStepFinerThanTheParametersResolutionAllowsStillMovesOnEveryPress() {
        val on = increments(on = true)
        // Hue shows whole degrees; in 7.5° steps 7.5 rounds to 8, and a press from 8 must not land on 8 again.
        on.update { it.with(IncrementKind.ANGLE, 7.5f) }
        assertEquals(8f, ParamIncrements.nudge(hue, 0f, 1, on), 0f)
        assertEquals(15f, ParamIncrements.nudge(hue, 8f, 1, on), 0f)
        assertEquals(0f, ParamIncrements.nudge(hue, 8f, -1, on), 0f)
        for (d in listOf(1, -1)) {
            var v = if (d > 0) hue.min else hue.max
            var presses = 0
            while (abs(v - (if (d > 0) hue.max else hue.min)) > 1e-4f) {
                val next = ParamIncrements.nudge(hue, v, d, on)
                assertTrue("Hue from $v by $d moved to $next", if (d > 0) next > v else next < v)
                v = next
                assertTrue(++presses < 100)
            }
        }
        // Exposure (0.01 EV resolution) in 0.333 EV steps: 0.333 shows as 0.33, the next press goes on.
        on.update { it.withCustom("Exposure|EV", 0.333f) }
        var v = 0f
        repeat(16) {
            val next = ParamIncrements.nudge(exposure, v, 1, on)
            assertTrue("Exposure from $v moved to $next", next > v || abs(next - exposure.max) < 1e-4f)
            v = next
        }
        assertEquals(exposure.max, v, 1e-4f)
    }
}
