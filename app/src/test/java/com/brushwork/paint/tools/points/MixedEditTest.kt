package com.brushwork.paint.tools.points

import com.brushwork.paint.ui.points.MixedFieldMath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** v1.7 (item 1, design §3.1 and §3.15): "Mixed" values and the edits applied to each selected point. */
class MixedEditTest {

    @Test
    fun mixedOfValues() {
        assertNull(Mixed.of(FloatArray(0)))
        assertEquals(Mixed.Same(4f), Mixed.of(floatArrayOf(4f, 4f, 4f)))
        assertEquals(Mixed.Spread(0.2f, 0.8f), Mixed.of(floatArrayOf(0.5f, 0.2f, 0.8f)))
        assertNull(Mixed.of(BooleanArray(0)))
        assertEquals(Mixed.Same(true), Mixed.of(booleanArrayOf(true, true)))
        assertEquals(Mixed.Same(false), Mixed.of(booleanArrayOf(false)))
        assertEquals(Mixed.Spread(false, true), Mixed.of(booleanArrayOf(true, false, true)))
    }

    @Test
    fun dragsScaleOrShiftEachValue() {
        val v = floatArrayOf(0f, 2f, 5f, 40f)
        // ×2: 0 stays 0, 40 × 2 is clamped to 50.
        assertArrayEquals(floatArrayOf(0f, 4f, 10f, 50f), MixedEdit.scaled(v, 10f, 20f, 0f, 50f), 0f)
        // A start of 0 cannot scale.
        assertArrayEquals(v, MixedEdit.scaled(v, 0f, 20f, 0f, 50f), 0f)
        // Min above 0: values clamp, 0 stays 0.
        assertArrayEquals(floatArrayOf(0f, 1f, 1f, 4f), MixedEdit.scaled(v, 10f, 1f, 1f, 50f), 0f)
        assertArrayEquals(floatArrayOf(0f, 0f, 2f, 37f), MixedEdit.shifted(v, -3f, 0f, 500f), 0f)
        assertArrayEquals(floatArrayOf(10f, 12f, 15f, 45f), MixedEdit.shifted(v, 10f, 0f, 45f), 0f)
        // The input is never changed.
        assertArrayEquals(floatArrayOf(0f, 2f, 5f, 40f), v, 0f)
    }

    @Test
    fun typedTextSetsAllOrAppliesToEach() {
        val v = floatArrayOf(20f, 50f, 80f)
        assertArrayEquals(floatArrayOf(30f, 30f, 30f), MixedEdit.typed(v, "30", 0f, 100f)!!, 0f)
        assertArrayEquals(floatArrayOf(25f, 25f, 25f), MixedEdit.typed(v, "50/2", 0f, 100f)!!, 0f)
        assertArrayEquals(floatArrayOf(12.5f, 12.5f, 12.5f), MixedEdit.typed(v, "12,5", 0f, 100f)!!, 0f)
        assertArrayEquals(floatArrayOf(100f, 100f, 100f), MixedEdit.typed(v, "150", 0f, 100f)!!, 0f)
        // Relative: each value.
        assertArrayEquals(floatArrayOf(10f, 25f, 40f), MixedEdit.typed(v, "/2", 0f, 100f)!!, 0f)
        assertArrayEquals(floatArrayOf(40f, 100f, 100f), MixedEdit.typed(v, "*2", 0f, 100f)!!, 0f)
        assertArrayEquals(floatArrayOf(60f, 100f, 100f), MixedEdit.typed(v, "×(1+2)", 0f, 100f)!!, 0f)
        // A leading sign is a sign, not relative.
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), MixedEdit.typed(v, "-5", 0f, 100f)!!, 0f)
        // Invalid.
        assertNull(MixedEdit.typed(v, "abc", 0f, 100f))
        assertNull(MixedEdit.typed(v, "/0", 0f, 100f))
        assertNull(MixedEdit.typed(v, "*", 0f, 100f))
    }

    @Test
    fun theFieldShowsMixedWithItsSpread() {
        assertEquals("Mixed", MixedFieldMath.shown(Mixed.Spread(20f, 80f), "%", 1))
        assertEquals("Mixed, 20 to 80 %", MixedFieldMath.stateDescription(Mixed.Spread(20f, 80f), "%", 1))
        assertEquals("12.5 px", MixedFieldMath.shown(Mixed.Same(12.5f), "px", 1))
        assertEquals("3", MixedFieldMath.stateDescription(Mixed.Same(3f), "", 1))
        assertEquals(1.0, MixedFieldMath.dragStep(0f, 500f), 0.0)
        assertEquals(0.01, MixedFieldMath.dragStep(0.1f, 10f), 1e-12)
        assertEquals(80f, MixedFieldMath.dragStart(Mixed.Spread(20f, 80f)))
        assertEquals(50f, MixedFieldMath.dialogStart(Mixed.Spread(20f, 80f), 0f, 100f), 0f)
    }
}
