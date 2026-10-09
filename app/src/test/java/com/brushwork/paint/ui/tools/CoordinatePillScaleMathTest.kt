package com.brushwork.paint.ui.tools

import androidx.compose.ui.text.font.FontWeight
import com.brushwork.paint.core.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.7 item 9 (design §3.9; area I, JVM): the pill's Scale row math and its "# step" text.
 * - [scaleTarget]: what one Scale X / Scale Y value gives `setScale`; with "Keep scale
 *   proportions" the other axis follows by the same factor (X 200 at 100 / 50 is 200 / 100),
 *   without it the other axis is kept (null); a zero or non-finite current value gives no
 *   factor (null: kept, never NaN);
 * - [formatScale]: one decimal at most, none for whole values;
 * - [scaleDragRange]: 1 – 1000 %, widened to include the value the drag starts at;
 * - [stepCellText]: "# 10", the "#" in bold.
 */
class CoordinatePillScaleMathTest {

    @Test
    fun keepProportionsScalesTheOtherAxisByTheSameFactor() {
        assertEquals(200f to 200f, scaleTarget(onX = true, v = 200f, current = Vec2(100f, 100f), keep = true))
        assertEquals("X 200 at 100 / 50", 200f to 100f, scaleTarget(onX = true, v = 200f, current = Vec2(100f, 50f), keep = true))
        assertEquals("Y 25 at 200 / 50", 100f to 25f, scaleTarget(onX = false, v = 25f, current = Vec2(200f, 50f), keep = true))
        val (x, y) = scaleTarget(onX = true, v = 150f, current = Vec2(120f, 80f), keep = true)
        assertEquals(150f, x!!, 0f)
        assertEquals(100f, y!!, 1e-4f)
    }

    @Test
    fun withoutKeepOrWithoutAFactorTheOtherAxisIsKept() {
        assertEquals(200f to null, scaleTarget(onX = true, v = 200f, current = Vec2(100f, 50f), keep = false))
        assertEquals(null to 30f, scaleTarget(onX = false, v = 30f, current = Vec2(100f, 50f), keep = false))
        // A flat box (0 % on the axis being set): no factor, the other axis stays.
        assertEquals(200f to null, scaleTarget(onX = true, v = 200f, current = Vec2(0f, 50f), keep = true))
        assertEquals(null to 40f, scaleTarget(onX = false, v = 40f, current = Vec2(100f, 0f), keep = true))
        assertEquals(200f to null, scaleTarget(onX = true, v = 200f, current = Vec2(Float.NaN, 50f), keep = true))
    }

    @Test
    fun scaleValuesReadWithOneDecimalAtMost() {
        assertEquals("100", formatScale(100f))
        assertEquals("33.3", formatScale(33.333f))
        assertEquals("0.1", formatScale(MIN_SCALE_PERCENT))
        assertEquals("250", formatScale(249.96f))
        assertEquals("1000.5", formatScale(1000.5f))
    }

    @Test
    fun aDragReachesOneToAThousandPercentAndTheValueItStartsAt() {
        assertEquals(SCALE_DRAG_MIN..SCALE_DRAG_MAX, scaleDragRange(100f))
        assertEquals(0.5f..SCALE_DRAG_MAX, scaleDragRange(0.5f))
        assertEquals(SCALE_DRAG_MIN..2500f, scaleDragRange(2500f))
        assertTrue(MIN_SCALE_PERCENT < SCALE_DRAG_MIN)
    }

    @Test
    fun theStepCellDrawsHashAndTheStep() {
        val t = stepCellText(10f)
        assertEquals("# 10", t.text)
        val hash = t.spanStyles.single { it.start == 0 }
        assertEquals(1, hash.end)
        assertEquals(FontWeight.Bold, hash.item.fontWeight)
        assertEquals("# 2.5", stepCellText(2.5f).text)
        assertNull("one span for \"#\", one for the step", t.spanStyles.getOrNull(2))
    }
}
