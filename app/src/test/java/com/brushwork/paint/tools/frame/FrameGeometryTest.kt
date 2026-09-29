package com.brushwork.paint.tools.frame

import com.brushwork.paint.core.Geometry
import com.brushwork.paint.core.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class FrameGeometryTest {
    private val square = FrameRect(0f, 0f, 100f, 100f).toPolygon()

    private fun area(p: List<Vec2>) = abs(Geometry.signedArea(p))
    private fun minX(p: List<Vec2>) = p.minOf { it.x }
    private fun maxX(p: List<Vec2>) = p.maxOf { it.x }
    private fun minY(p: List<Vec2>) = p.minOf { it.y }
    private fun maxY(p: List<Vec2>) = p.maxOf { it.y }

    @Test
    fun verticalSplitLeavesGutter() {
        val (a, b) = FrameMath.splitPolygon(square, Vec2(50f, -10f), Vec2(50f, 110f), gutter = 10f, minSize = 4f)!!
        val left = if (maxX(a) < maxX(b)) a else b
        val right = if (left === a) b else a
        assertEquals(0f, minX(left), 1e-3f)
        assertEquals(45f, maxX(left), 1e-3f)
        assertEquals(55f, minX(right), 1e-3f)
        assertEquals(100f, maxX(right), 1e-3f)
        assertEquals(4500f, area(left), 1e-2f)
        assertEquals(4500f, area(right), 1e-2f)
    }

    @Test
    fun angledSplitOffsetsAlongNormal() {
        val g = 8f
        val a = Vec2(0f, 20f)
        val b = Vec2(100f, 70f)
        val (p1, p2) = FrameMath.splitPolygon(square, a, b, g, 4f)!!
        val n = (b - a).normalized().perpendicular()
        val d1 = p1.map { (it - a).dot(n) }
        val d2 = p2.map { (it - a).dot(n) }
        // Pieces lie on opposite sides, each exactly gutter/2 from the cut line at its closest.
        assertTrue(d1.all { it >= g / 2f - 1e-3f } || d1.all { it <= -g / 2f + 1e-3f })
        assertEquals(g / 2f, d1.minOf { abs(it) }, 1e-3f)
        assertEquals(g / 2f, d2.minOf { abs(it) }, 1e-3f)
        assertTrue(d1.first() * d2.first() < 0f)
        // Area removed = gutter strip across the square.
        val cutLength = (b - a).length
        assertEquals(10000f - g * cutLength, area(p1) + area(p2), 1f)
    }

    @Test
    fun splitRejectsMissesAndSlivers() {
        assertNull(FrameMath.splitPolygon(square, Vec2(150f, 0f), Vec2(150f, 100f), 10f, 4f))
        assertNull(FrameMath.splitPolygon(square, Vec2(4f, 0f), Vec2(4f, 100f), 2f, 4f)) // 3 px piece
        assertNull(FrameMath.splitPolygon(square, Vec2(50f, 0f), Vec2(50f, 100f), 99f, 4f)) // gutter eats all
        assertNotNull(FrameMath.splitPolygon(square, Vec2(10f, 0f), Vec2(10f, 100f), 2f, 4f))
    }

    @Test
    fun clipHalfPlaneKeepsConvexPiece() {
        val clipped = FrameMath.clipHalfPlane(square, Vec2(0f, 30f), Vec2(0f, 1f), 0f)
        assertEquals(4, clipped.size)
        assertEquals(30f, minY(clipped), 1e-4f)
        assertEquals(7000f, area(clipped), 1e-2f)
        assertTrue(FrameMath.clipHalfPlane(square, Vec2(0f, 200f), Vec2(0f, 1f), 0f).isEmpty())
    }

    @Test
    fun snapCutWithinFiveDegrees() {
        val a = Vec2(10f, 10f)
        assertEquals(Vec2(110f, 10f), FrameMath.snapCut(a, Vec2(110f, 14f)))      // 2.3° -> horizontal
        assertEquals(Vec2(-90f, 10f), FrameMath.snapCut(a, Vec2(-90f, 18f)))      // leftwards, 4.6°
        assertEquals(Vec2(110f, 20f), FrameMath.snapCut(a, Vec2(110f, 20f)))      // 5.7° stays angled
        assertEquals(Vec2(10f, 110f), FrameMath.snapCut(a, Vec2(15f, 110f)))      // near vertical
        assertEquals(Vec2(10f, -90f), FrameMath.snapCut(a, Vec2(18f, -90f)))      // 4.6° from vertical, upwards
        assertEquals(Vec2(30f, -90f), FrameMath.snapCut(a, Vec2(30f, -90f)))      // 11° stays angled
    }

    @Test
    fun cutOrientationPicksGutter() {
        assertTrue(FrameMath.isVerticalCut(Vec2(0f, 0f), Vec2(10f, 100f)))
        assertFalse(FrameMath.isVerticalCut(Vec2(0f, 0f), Vec2(100f, 10f)))
        val two = FrameMath.grid(FrameRect(0f, 0f, 210f, 100f), 1, 2, gutterH = 20f, gutterV = 10f)!!
        // A horizontal cut across both panels splits each with the horizontal gutter (20).
        val cut = FrameMath.divide(two, Vec2(-5f, 50f), Vec2(215f, 50f), gutterH = 20f, gutterV = 10f, minSize = 4f)!!
        assertEquals(4, cut.size)
        val tops = cut.filter { minY(it.points) < 1f }
        val bottoms = cut.filter { minY(it.points) > 1f }
        assertEquals(2, tops.size)
        assertTrue(tops.all { abs(maxY(it.points) - 40f) < 1e-3f })
        assertTrue(bottoms.all { abs(minY(it.points) - 60f) < 1e-3f })
        // A vertical cut inside the left panel only splits that one, with the vertical gutter (10).
        val v = FrameMath.divide(two, Vec2(50f, 30f), Vec2(50f, 60f), gutterH = 20f, gutterV = 10f, minSize = 4f)!!
        assertEquals(3, v.size)
        assertTrue(v.any { abs(maxX(it.points) - 45f) < 1e-3f })
        assertTrue(v.any { abs(minX(it.points) - 55f) < 1e-3f && abs(maxX(it.points) - 100f) < 1e-3f })
        // A cut that only runs through the gutter/margins changes nothing.
        assertNull(FrameMath.divide(two, Vec2(105f, -10f), Vec2(105f, 110f), 20f, 10f, 4f))
    }

    @Test
    fun segmentCrossing() {
        assertTrue(FrameMath.segmentCrossesPolygon(Vec2(40f, 40f), Vec2(60f, 60f), square)) // fully inside
        assertTrue(FrameMath.segmentCrossesPolygon(Vec2(-10f, 50f), Vec2(20f, 50f), square)) // enters
        assertFalse(FrameMath.segmentCrossesPolygon(Vec2(-10f, 50f), Vec2(-1f, 50f), square)) // outside
        assertFalse(FrameMath.segmentCrossesPolygon(Vec2(-10f, 0f), Vec2(110f, 0f), square)) // along an edge
        assertFalse(FrameMath.segmentCrossesPolygon(Vec2(-10f, 10f), Vec2(10f, -10f), square)) // touches a corner
        // Winding direction doesn't matter.
        assertTrue(FrameMath.segmentCrossesPolygon(Vec2(40f, 40f), Vec2(60f, 60f), square.reversed()))
    }

    @Test
    fun gridFillsFrameArea() {
        val area = FrameRect(10f, 20f, 110f, 220f)
        val panels = FrameMath.grid(area, rows = 2, cols = 3, gutterH = 10f, gutterV = 5f)!!
        assertEquals(6, panels.size)
        val cellW = (100f - 2 * 5f) / 3f
        val cellH = (200f - 10f) / 2f
        for (p in panels) {
            assertEquals(cellW, maxX(p.points) - minX(p.points), 1e-3f)
            assertEquals(cellH, maxY(p.points) - minY(p.points), 1e-3f)
        }
        assertEquals(area.left, panels.minOf { minX(it.points) }, 0f)
        assertEquals(area.right, panels.maxOf { maxX(it.points) }, 0f)
        assertEquals(area.top, panels.minOf { minY(it.points) }, 0f)
        assertEquals(area.bottom, panels.maxOf { maxY(it.points) }, 0f)
        // Panels + gutters cover the area exactly.
        val covered = panels.sumOf { it.area.toDouble() } + 2 * 5.0 * 200.0 + 10.0 * 100.0 - 2 * 5.0 * 10.0
        assertEquals(area.width.toDouble() * area.height, covered, 0.1)
        // Neighbouring columns are exactly one gutter apart.
        val row0 = panels.take(3).sortedBy { minX(it.points) }
        assertEquals(5f, minX(row0[1].points) - maxX(row0[0].points), 1e-3f)
        assertNull(FrameMath.grid(area, 1, 30, 0f, 5f)) // gutters leave no room
    }

    @Test
    fun frameAreaFromMargins() {
        val s = FrameSettings.defaultsFor(1000, 2000).copy(marginTop = 10f, marginRight = 20f, marginBottom = 30f, marginLeft = 40f)
        assertEquals(FrameRect(40f, 10f, 980f, 1970f), FrameMath.frameArea(1000, 2000, s))
        assertNull(FrameMath.frameArea(100, 100, s.withUniformMargin(60f)))
    }

    @Test
    fun insetKeepsMiteredCorners() {
        val inner = FrameMath.inset(square, 10f)!!
        assertEquals(10f, minX(inner), 1e-3f)
        assertEquals(90f, maxX(inner), 1e-3f)
        assertEquals(6400f, area(inner), 1e-2f)
        // Triangle: every inset edge is exactly d away from the original edge.
        val tri = listOf(Vec2(0f, 0f), Vec2(100f, 0f), Vec2(0f, 100f))
        val ti = FrameMath.inset(tri, 5f)!!
        assertEquals(3, ti.size)
        val hyp = (Vec2(0f, 100f) - Vec2(100f, 0f)).normalized().perpendicular()
        val dist = ti.minOf { abs((it - Vec2(100f, 0f)).dot(hyp)) }
        assertEquals(5f, dist, 1e-3f)
        assertNull(FrameMath.inset(square, 60f))
    }

    @Test
    fun minWidthOfShapes() {
        assertEquals(100f, FrameMath.minWidth(square), 1e-3f)
        val tri = listOf(Vec2(0f, 0f), Vec2(100f, 0f), Vec2(0f, 100f))
        assertEquals(100f / sqrt(2f), FrameMath.minWidth(tri), 1e-3f)
    }

    @Test
    fun panelHitTest() {
        val panels = FrameMath.grid(FrameRect(0f, 0f, 100f, 100f), 2, 2, 10f, 10f)!!
        assertEquals(-1, FrameMath.panelAt(panels, Vec2(50f, 50f))) // gutter crossing
        val i = FrameMath.panelAt(panels, Vec2(80f, 80f))
        assertTrue(i >= 0)
        assertTrue(minX(panels[i].points) >= 55f && minY(panels[i].points) >= 55f)
    }
}
