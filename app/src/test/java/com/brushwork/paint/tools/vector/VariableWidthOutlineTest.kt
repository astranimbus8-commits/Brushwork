package com.brushwork.paint.tools.vector

import com.brushwork.paint.core.Vec2
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.5 F2: the reference [VariableWidthOutline] (JVM): containment and half-widths. */
class VariableWidthOutlineTest {

    /** Non-zero winding of [p] in the flattened [path] (how android fills it by default). */
    private fun inside(path: VectorPath, p: Vec2): Boolean {
        var winding = 0
        for (poly in path.flatten(0.05f)) {
            val pts = poly.points
            for (i in pts.indices) {
                val a = pts[i]
                val b = pts[(i + 1) % pts.size]
                val cross = (b.x - a.x) * (p.y - a.y) - (p.x - a.x) * (b.y - a.y)
                if (a.y <= p.y) { if (b.y > p.y && cross > 0f) winding++ } else if (b.y <= p.y && cross < 0f) winding--
            }
        }
        return winding != 0
    }

    private fun build(points: List<Vec2>, widths: List<Float>, closed: Boolean = false) =
        VariableWidthOutline.build(points.map { it.x }.toFloatArray(), points.map { it.y }.toFloatArray(), widths.toFloatArray(), points.size, closed)

    @Test
    fun aConstantWidthLineIsACapsule() {
        val path = build(listOf(Vec2(0f, 0f), Vec2(50f, 0f), Vec2(100f, 0f)), listOf(10f, 10f, 10f))
        assertTrue(inside(path, Vec2(50f, 4.5f)))
        assertTrue(inside(path, Vec2(25f, -4.5f)))
        assertFalse(inside(path, Vec2(50f, 5.5f)))
        assertFalse(inside(path, Vec2(75f, -5.5f)))
        // Round caps.
        assertTrue(inside(path, Vec2(-4.5f, 0f)))
        assertTrue(inside(path, Vec2(103f, 3f)))
        assertFalse(inside(path, Vec2(-5.5f, 0f)))
        assertFalse(inside(path, Vec2(104f, 4f)))
    }

    @Test
    fun aTaperFollowsTheWidthsWithinHalfAPixel() {
        val pts = List(11) { Vec2(it * 10f, 0f) }
        val widths = List(11) { 20f * (1f - it / 10f) } // 20 px down to a point
        val path = build(pts, widths)
        for (i in 1 until 10) {
            val x = i * 10f + 5f
            val half = (20f * (1f - (i + 0.5f) / 10f)) / 2f
            assertTrue("inside at $x", inside(path, Vec2(x, half - 0.5f)))
            assertFalse("outside at $x", inside(path, Vec2(x, half + 0.5f)))
        }
        // A 300 % middle widens only the middle.
        val bulge = build(listOf(Vec2(0f, 0f), Vec2(50f, 0f), Vec2(100f, 0f)), listOf(4f, 12f, 4f))
        assertTrue(inside(bulge, Vec2(50f, 5.5f)))
        assertFalse(inside(bulge, Vec2(2f, 3f)))
    }

    @Test
    fun cornersAndClosedLoopsAreCovered() {
        val corner = build(listOf(Vec2(0f, 0f), Vec2(100f, 0f), Vec2(100f, 100f)), listOf(10f, 10f, 10f))
        // The outer side of the corner is round (the disc fills the wedge between the two bands).
        assertTrue(inside(corner, Vec2(103f, -3f)))
        assertFalse(inside(corner, Vec2(105f, -5f)))
        assertTrue(inside(corner, Vec2(100f, 50f)))
        val square = listOf(Vec2(0f, 0f), Vec2(100f, 0f), Vec2(100f, 100f), Vec2(0f, 100f))
        val loop = build(square, List(4) { 8f }, closed = true)
        assertTrue("closing edge", inside(loop, Vec2(0f, 50f)))
        assertFalse("hollow", inside(loop, Vec2(50f, 50f)))
        val open = build(square, List(4) { 8f }, closed = false)
        assertFalse("no closing edge", inside(open, Vec2(0f, 50f)))
    }

    @Test
    fun degenerateInputGivesNothingOrDiscs() {
        assertTrue(build(listOf(Vec2(0f, 0f), Vec2(10f, 0f)), listOf(0f, 0f)).isEmpty)
        assertTrue(VariableWidthOutline.build(FloatArray(0), FloatArray(0), FloatArray(0), 0).isEmpty)
        // n beyond the arrays is clamped; NaN widths count as 0.
        val clamped = VariableWidthOutline.build(floatArrayOf(0f, 10f), floatArrayOf(0f, 0f), floatArrayOf(4f, Float.NaN), 9)
        assertTrue(inside(clamped, Vec2(0f, 1.5f)))
        // One point: a dot of that width.
        val dot = build(listOf(Vec2(5f, 5f)), listOf(6f))
        assertTrue(inside(dot, Vec2(7.5f, 5f)))
        assertFalse(inside(dot, Vec2(8.5f, 5f)))
    }
}
