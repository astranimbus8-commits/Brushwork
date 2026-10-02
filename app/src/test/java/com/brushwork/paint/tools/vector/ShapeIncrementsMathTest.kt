package com.brushwork.paint.tools.vector

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.vector.ShapeGeometry.Handle
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.6 §3.4 (G, JVM): the shape geometry of the Length increment. A resize handle puts the
 * dragged sizes on multiples of the step (at least one step) and keeps the fixed sides; with
 * proportions the leading size is stepped; a size a guide decided is left alone; a new box drag
 * rounds to the nearest multiple (0 included). A bad step is the v1.5 geometry.
 */
class ShapeIncrementsMathTest {

    private fun assertBox(expected: ShapeBox, actual: ShapeBox) {
        assertEquals("cx of $actual", expected.cx, actual.cx, 1e-3f)
        assertEquals("cy of $actual", expected.cy, actual.cy, 1e-3f)
        assertEquals("w of $actual", expected.w, actual.w, 1e-3f)
        assertEquals("h of $actual", expected.h, actual.h, 1e-3f)
        assertEquals("rotation of $actual", expected.rotationDeg, actual.rotationDeg, 1e-3f)
    }

    private val box = ShapeBox(80f, 120f, 80f, 80f)

    @Test
    fun resizeHandlesStepTheDraggedSizes() {
        // Bottom-right to (133, 166): 93 x 86 → 90 x 90, the top-left corner (40, 80) stays.
        assertBox(ShapeBox(85f, 125f, 90f, 90f), ShapeGeometry.resizeStepped(box, Handle.BOTTOM_RIGHT, Vec2(133f, 166f), false, null, 10f))
        // Top-left to (33, 74): 87 x 86 → 90 x 90, the bottom-right (120, 160) stays.
        assertBox(ShapeBox(75f, 115f, 90f, 90f), ShapeGeometry.resizeStepped(box, Handle.TOP_LEFT, Vec2(33f, 74f), false, null, 10f))
        // The right side: only the width; the height stays 80.
        assertBox(ShapeBox(85f, 120f, 90f, 80f), ShapeGeometry.resizeStepped(box, Handle.RIGHT, Vec2(128f, 300f), false, null, 10f))
        // From the center: 2 × 47 = 94 → 90, centered.
        assertBox(ShapeBox(80f, 120f, 90f, 80f), ShapeGeometry.resizeStepped(box, Handle.RIGHT, Vec2(127f, 120f), true, null, 10f))
        // Never below one step.
        assertBox(ShapeBox(45f, 120f, 10f, 80f), ShapeGeometry.resizeStepped(box, Handle.RIGHT, Vec2(41f, 120f), false, null, 10f))
        // A width a guide decided stays as dragged; the height is stepped.
        assertBox(ShapeBox(86.5f, 125f, 93f, 90f), ShapeGeometry.resizeStepped(box, Handle.BOTTOM_RIGHT, Vec2(133f, 166f), false, null, 10f, stepW = false))
        // Proportions 2:1, a corner: the longer side (the width) leads: 2 × 46.5... → 100 x 50.
        val wide = ShapeBox(80f, 120f, 80f, 40f)
        val p = ShapeGeometry.resizeStepped(wide, Handle.BOTTOM_RIGHT, Vec2(135f, 150f), false, 2f, 10f)
        assertEquals(100f, p.w, 1e-3f)
        assertEquals(50f, p.h, 1e-3f)
        // A turned box keeps its rotation and its fixed corner.
        val turned = ShapeBox(0f, 0f, 80f, 40f, 30f)
        val fixed = turned.toDoc(Vec2(-40f, -20f))
        val t = ShapeGeometry.resizeStepped(turned, Handle.BOTTOM_RIGHT, turned.toDoc(Vec2(47f, 23f)), false, null, 10f)
        assertEquals(90f, t.w, 1e-3f)
        assertEquals(40f, t.h, 1e-3f)
        assertEquals(30f, t.rotationDeg, 0f)
        val tf = t.toDoc(Vec2(-45f, -20f))
        assertEquals(fixed.x, tf.x, 1e-3f)
        assertEquals(fixed.y, tf.y, 1e-3f)
        // No usable step: the v1.5 resize.
        for (bad in listOf(0f, -1f, Float.NaN)) {
            assertBox(ShapeGeometry.resize(box, Handle.BOTTOM_RIGHT, Vec2(133f, 166f), false, null), ShapeGeometry.resizeStepped(box, Handle.BOTTOM_RIGHT, Vec2(133f, 166f), false, null, bad))
        }
    }

    @Test
    fun newBoxesRoundToTheNearestMultiples() {
        assertBox(ShapeBox(80f, 120f, 80f, 80f), ShapeGeometry.dragBoxStepped(Vec2(40f, 80f), Vec2(123f, 157f), false, null, 10f))
        // Up and to the left of the first corner.
        assertBox(ShapeBox(15f, 55f, 50f, 50f), ShapeGeometry.dragBoxStepped(Vec2(40f, 80f), Vec2(-8f, 34f), false, null, 10f))
        // From the center: 2 × 23 = 46 → 50.
        assertBox(ShapeBox(40f, 80f, 50f, 50f), ShapeGeometry.dragBoxStepped(Vec2(40f, 80f), Vec2(63f, 103f), true, null, 10f))
        // Too small for one step: 0 (nothing is made).
        assertEquals(0f, ShapeGeometry.dragBoxStepped(Vec2(40f, 80f), Vec2(43f, 140f), false, null, 10f).w, 0f)
        // A size a guide decided stays.
        assertEquals(83f, ShapeGeometry.dragBoxStepped(Vec2(40f, 80f), Vec2(123f, 157f), false, null, 10f, stepW = false).w, 1e-3f)
        // Proportions 1 (a circle): the longer side leads.
        val c = ShapeGeometry.dragBoxStepped(Vec2(0f, 0f), Vec2(57f, 31f), false, 1f, 10f)
        assertEquals(60f, c.w, 1e-3f)
        assertEquals(60f, c.h, 1e-3f)
        assertBox(ShapeGeometry.dragBox(Vec2(40f, 80f), Vec2(123f, 157f), false, null), ShapeGeometry.dragBoxStepped(Vec2(40f, 80f), Vec2(123f, 157f), false, null, Float.NaN))
    }
}
