package com.brushwork.paint.tools.transform

import com.brushwork.paint.core.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [TransformHandles.pinch] / [TransformHandles.pinchRotation] (pure JVM). */
class TransformPinchMathTest {

    private fun assertVec(expected: Vec2, actual: Vec2, eps: Float = 1e-3f) {
        assertEquals("x", expected.x, actual.x, eps)
        assertEquals("y", expected.y, actual.y, eps)
    }

    @Test
    fun scalesUniformlyAboutTheStartFocus() {
        val start = TransformState.identity(100, 100, 100, 80) // 100..200 x 100..180
        val focus = Vec2(120f, 140f)
        val out = TransformHandles.pinch(start, focus, Vec2.ZERO, 2f, 0f)
        assertEquals(200f, out.width, 1e-3f)
        assertEquals(160f, out.height, 1e-3f)
        // The point under the focus stays put; the rest grows away from it.
        assertVec(Vec2(80f, 60f), out.corner(0))
        assertVec(Vec2(280f, 220f), out.corner(2))
        assertEquals(0f, out.rotationDeg, 0f)
    }

    @Test
    fun rotatesAboutTheFocusAndThenTranslates() {
        val start = TransformState.identity(0, 0, 100, 100)
        val focus = Vec2(50f, 50f)
        val out = TransformHandles.pinch(start, focus, Vec2(10f, -5f), 1f, 90f)
        assertEquals(90f, out.rotationDeg, 1e-3f)
        // TL (0,0) turns clockwise (y down) around the center to the top-right, then moves.
        assertVec(Vec2(100f + 10f, 0f - 5f), out.corner(0))
        assertVec(Vec2(60f, 45f), out.center())
    }

    @Test
    fun distortedQuadIsTransformedAsAWhole() {
        val base = TransformState.identity(0, 0, 100, 100)
        val skewed = base.withCorners(listOf(Vec2(10f, 0f), Vec2(100f, 20f), Vec2(90f, 100f), Vec2(0f, 80f)))!!
        val focus = Vec2(50f, 50f)
        val out = TransformHandles.pinch(skewed, focus, Vec2(5f, 5f), 1.5f, 30f)
        assertTrue(out.isDistorted)
        val rad = Math.toRadians(30.0)
        val c = Math.cos(rad).toFloat(); val s = Math.sin(rad).toFloat()
        for (i in 0 until 4) {
            val p = skewed.corner(i) - focus
            val expected = Vec2(focus.x + 5f + 1.5f * (p.x * c - p.y * s), focus.y + 5f + 1.5f * (p.x * s + p.y * c))
            assertVec(expected, out.corner(i), 1e-2f)
        }
    }

    @Test
    fun smallTurnsKeepTheOriginalAngleWhileScaling() {
        val start = TransformState(50, 50, 100f, 100f, rotationDeg = 17f)
        val out = TransformHandles.pinch(start, Vec2(100f, 100f), Vec2.ZERO, 1.5f, 3f)
        assertEquals(17f, out.rotationDeg, 1e-4f)
        assertEquals(75f, out.width, 1e-3f)
    }

    @Test
    fun rotationSnapsToMultiplesOf45() {
        assertEquals(0f, TransformHandles.pinchRotation(0f, 3.5f), 0f)
        assertEquals(-17f, TransformHandles.pinchRotation(17f, -20f), 1e-4f) // 17 - 20 = -3 -> 0
        assertEquals(28f, TransformHandles.pinchRotation(17f, 25f), 1e-4f)   // 42 -> 45
        assertEquals(20f, TransformHandles.pinchRotation(0f, 20f), 1e-4f)    // free
        assertEquals(90f, TransformHandles.pinchRotation(0f, 92f), 1e-4f)
        assertEquals(0f, TransformHandles.pinchRotation(179f, 360f), 0f) // a full turn is no turn
    }

    @Test
    fun nothingCollapsesAndBadInputIsIgnored() {
        val start = TransformState.identity(0, 0, 40, 20)
        val tiny = TransformHandles.pinch(start, Vec2(20f, 10f), Vec2.ZERO, 0.001f, 0f)
        assertEquals(TransformState.MIN_SIZE, tiny.height, 1e-4f)
        val same = TransformHandles.pinch(start, Vec2(20f, 10f), Vec2(Float.NaN, 0f), Float.NaN, Float.POSITIVE_INFINITY)
        assertTrue(same.sameGeometry(start))
    }
}
