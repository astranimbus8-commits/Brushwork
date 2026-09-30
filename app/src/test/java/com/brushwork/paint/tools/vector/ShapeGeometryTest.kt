package com.brushwork.paint.tools.vector

import com.brushwork.paint.core.Geometry
import com.brushwork.paint.core.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class ShapeGeometryTest {

    private fun assertVec(expected: Vec2, actual: Vec2, tol: Float = 1e-3f) {
        assertEquals("x", expected.x, actual.x, tol)
        assertEquals("y", expected.y, actual.y, tol)
    }

    private fun lastPoint(op: PathOp): Vec2 = when (op) {
        is PathOp.MoveTo -> op.p
        is PathOp.LineTo -> op.p
        is PathOp.CubicTo -> op.p
        PathOp.Close -> error("close has no point")
    }

    @Test
    fun brushOutlinesAreOneContinuousPath() {
        val a = Vec2(0f, 0f); val b = Vec2(100f, 0f)
        for (heads in ArrowHeads.entries) for (style in ArrowHeadStyle.entries) {
            val path = ShapeGeometry.arrowBrushOutline(a, b, 4f, heads, style, 4f)
            val polys = path.flatten()
            assertEquals("$heads/$style: one sub-path", 1, polys.size)
            val pts = polys[0].points
            // Covers the whole arrow: both tips and the head corners (16 px long, 8 or 9.6 px wide).
            val halfW = if (style == ArrowHeadStyle.FILLED) 8f else 9.6f
            assertTrue(pts.any { it == a } && pts.any { it == b })
            if (heads.end) {
                assertTrue(pts.any { abs(it.x - 84f) < 1e-3f && abs(abs(it.y) - halfW) < 1e-3f && it.y > 0f })
                assertTrue(pts.any { abs(it.x - 84f) < 1e-3f && abs(abs(it.y) - halfW) < 1e-3f && it.y < 0f })
            }
            if (heads.start) assertTrue(pts.any { abs(it.x - 16f) < 1e-3f && abs(abs(it.y) - halfW) < 1e-3f })
            // Consecutive points never jump across the arrow (no hidden pen-up).
            pts.zipWithNext().forEach { (p, q) -> assertTrue(p.distanceTo(q) <= 100f + 1e-3f) }
        }
        val box = ShapeBox(50f, 50f, 60f, 40f, 0f)
        for (type in ShapeType.entries) {
            val outline = ShapeGeometry.brushOutline(type, box, OutlineParams(corner = CornerStyle.ROUND, cornerRadius = 8f), 4f, ArrowHeads.BOTH, ArrowHeadStyle.OPEN, 3f)
            assertEquals("$type", 1, outline.flatten().size)
        }
        assertTrue(ShapeGeometry.arrowBrushOutline(a, a, 4f, ArrowHeads.END, ArrowHeadStyle.FILLED, 4f).isEmpty)
    }

    @Test
    fun polygonVertices() {
        for (n in listOf(3, 5, 6, 64)) {
            val v = ShapeGeometry.unitPolygon(n)
            assertEquals(n, v.size)
            v.forEach { assertEquals(1f, it.length, 1e-5f) }
            assertVec(Vec2(0f, -1f), v[0]) // first vertex on top
            // Clockwise on screen (y down) = positive signed area in this convention.
            assertTrue(Geometry.signedArea(v) > 0f)
        }
        // Sides are clamped to the supported range.
        assertEquals(3, ShapeGeometry.unitPolygon(1).size)
        assertEquals(64, ShapeGeometry.unitPolygon(500).size)
    }

    @Test
    fun starVertices() {
        val v = ShapeGeometry.unitStar(5, 0.4f)
        assertEquals(10, v.size)
        v.forEachIndexed { i, p -> assertEquals(if (i % 2 == 0) 1f else 0.4f, p.length, 1e-5f) }
        // Inner vertices sit exactly between two tips.
        val a0 = Math.toDegrees(v[0].angle.toDouble()); val a1 = Math.toDegrees(v[1].angle.toDouble())
        assertEquals(36.0, a1 - a0, 1e-3)
        assertEquals(128, ShapeGeometry.unitStar(64, 0.5f).size)
    }

    @Test
    fun polygonFitsItsBox() {
        val v = ShapeGeometry.vertices(ShapeType.POLYGON, 300f, 200f, OutlineParams(sides = 5))
        val b = Bounds.of(v)!!
        assertEquals(-150f, b.left, 1e-3f); assertEquals(150f, b.right, 1e-3f)
        assertEquals(-100f, b.top, 1e-3f); assertEquals(100f, b.bottom, 1e-3f)
        // A triangle's natural aspect is wider than tall (equilateral: 2 / sqrt(3) * ... ).
        val tri = ShapeGeometry.naturalAspect(ShapeGeometry.unitPolygon(3))
        assertEquals(sqrt(3f) / 1.5f, tri, 1e-4f)
        assertEquals(1f, ShapeGeometry.naturalAspect(ShapeType.RECTANGLE, OutlineParams()), 0f)
    }

    @Test
    fun roundedRectangleKeepsBoundsAndIsTangent() {
        val v = ShapeGeometry.rectVertices(200f, 100f)
        val path = ShapeGeometry.cornerPath(v, CornerStyle.ROUND, 20f)
        val b = path.bounds()!!
        assertEquals(-100f, b.left, 0.05f); assertEquals(100f, b.right, 0.05f)
        assertEquals(-50f, b.top, 0.05f); assertEquals(50f, b.bottom, 0.05f)
        // 4 edges + 4 quarter arcs (one cubic each).
        assertEquals(4, path.ops.count { it is PathOp.CubicTo })
        assertEquals(1 + 3 + 4 + 1, path.ops.size) // move + 3 lines + 4 arcs + close
        // Every flattened point of the top-left arc lies on the circle of radius 20 around (-80, -30).
        val pts = path.flatten(0.01f).single().points
        val c = Vec2(-80f, -30f)
        val arcPts = pts.filter { it.x < -80f && it.y < -30f }
        assertTrue(arcPts.isNotEmpty())
        arcPts.forEach { assertEquals(20f, it.distanceTo(c), 0.05f) }
    }

    @Test
    fun cornerRadiusIsClampedToHalfTheShorterEdge() {
        val v = ShapeGeometry.rectVertices(100f, 100f)
        val cuts = ShapeGeometry.cornerCuts(v, 500f)
        cuts.forEach { assertEquals(50f, it, 1e-4f) }
        // Fully rounded square = circle of radius 50.
        val pts = ShapeGeometry.cornerPath(v, CornerStyle.ROUND, 500f).flatten(0.01f).single().points
        pts.forEach { assertEquals(50f, it.length, 0.1f) }
        // Uneven edges: clamp per vertex to the shorter adjacent edge.
        val rect = ShapeGeometry.rectVertices(400f, 60f)
        ShapeGeometry.cornerCuts(rect, 100f).forEach { assertEquals(30f, it, 1e-4f) }
    }

    @Test
    fun bevelCutsCornersWithStraightSegments() {
        val v = ShapeGeometry.rectVertices(100f, 80f)
        val path = ShapeGeometry.cornerPath(v, CornerStyle.BEVEL, 10f)
        assertTrue(path.ops.none { it is PathOp.CubicTo })
        val poly = path.flatten().single()
        assertTrue(poly.closed)
        assertEquals(8, poly.points.size) // two cut points per corner
        assertVec(Vec2(-50f, -30f), poly.points[0]) // top-left, cut back along the left edge
        assertVec(Vec2(-40f, -40f), poly.points[1]) // ...and along the top edge
        // Bevel area = rect - 4 triangles of 10x10/2.
        assertEquals(100f * 80f - 4 * 50f, abs(Geometry.signedArea(poly.points)), 0.01f)
    }

    @Test
    fun invertedCornersAreConcave() {
        val v = ShapeGeometry.rectVertices(100f, 100f)
        val path = ShapeGeometry.cornerPath(v, CornerStyle.INVERTED, 20f)
        val pts = path.flatten(0.01f).single().points
        // Points of the top-left notch lie on a circle of radius 20 centered on the old vertex.
        val notch = pts.filter { it.x < -30f && it.y < -30f }
        assertTrue(notch.isNotEmpty())
        notch.forEach { assertEquals(20f, it.distanceTo(Vec2(-50f, -50f)), 0.05f) }
        // The notch bulges inward: its midpoint is inside the square, on the diagonal.
        val d = 20f / sqrt(2f)
        val mid = Vec2(-50f + d, -50f + d)
        val closest = pts.zipWithNext().minOf { (a, b) -> Geometry.distanceToSegment(mid, a, b) }
        assertTrue(closest < 0.05f)
        // Area = square minus four quarter circles.
        val area = abs(Geometry.signedArea(pts))
        assertEquals(10000f - Math.PI.toFloat() * 400f, area, 2f)
    }

    @Test
    fun polygonCornersRoundEveryVertex() {
        val v = ShapeGeometry.vertices(ShapeType.POLYGON, 200f, 200f, OutlineParams(sides = 6))
        val path = ShapeGeometry.cornerPath(v, CornerStyle.ROUND, 15f)
        assertEquals(6, path.ops.count { it is PathOp.CubicTo })
        // Round star: tips (convex) and inner (reflex) vertices both get arcs.
        val star = ShapeGeometry.vertices(ShapeType.STAR, 200f, 200f, OutlineParams(starPoints = 5, innerRatio = 0.5f))
        val starPath = ShapeGeometry.cornerPath(star, CornerStyle.ROUND, 8f)
        assertTrue(starPath.ops.count { it is PathOp.CubicTo } >= 10)
        // The rounded star stays inside its sharp version's bounds.
        val sharp = Bounds.of(star)!!
        val round = starPath.bounds()!!
        assertTrue(round.left >= sharp.left - 1e-3f && round.right <= sharp.right + 1e-3f)
        assertTrue(round.top >= sharp.top - 1e-3f && round.bottom <= sharp.bottom + 1e-3f)
        // Sharp style is the plain polygon.
        val plain = ShapeGeometry.cornerPath(v, CornerStyle.SHARP, 15f)
        assertEquals(6 + 1, plain.ops.size)
    }

    @Test
    fun roundCornerArcTouchesBisector() {
        // Right angle at the origin with edges along +x and +y: fillet center at (d, d).
        val v = listOf(Vec2(0f, 0f), Vec2(100f, 0f), Vec2(0f, 100f))
        val path = ShapeGeometry.cornerPath(v, CornerStyle.ROUND, 10f)
        val first = path.ops[0] as PathOp.MoveTo
        // For the 90 degree vertex at the origin the arc starts 10 px down the previous edge.
        assertVec(Vec2(0f, 10f), first.p)
        val arc = path.ops[1] as PathOp.CubicTo
        assertVec(Vec2(10f, 0f), arc.p)
        val mid = VectorPath.cubicPoint(first.p, arc.c1, arc.c2, arc.p, 0.5f)
        val expected = Vec2(10f, 10f) - Vec2(1f, 1f).normalized() * 10f
        assertVec(expected, mid, 0.01f)
    }

    @Test
    fun ellipseAndOutlineTransform() {
        val e = ShapeGeometry.ellipsePath(200f, 100f)
        val b = e.bounds()!!
        assertEquals(200f, b.width, 0.1f); assertEquals(100f, b.height, 0.1f)
        val pts = e.flatten(0.01f).single().points
        pts.forEach { val v = (it.x / 100f) * (it.x / 100f) + (it.y / 50f) * (it.y / 50f); assertEquals(1f, v, 2e-3f) }
        // Rotated 90 degrees around (500, 400): width and height swap.
        val box = ShapeBox(500f, 400f, 200f, 100f, 90f)
        val rb = ShapeGeometry.outline(ShapeType.ELLIPSE, box, OutlineParams()).bounds()!!
        assertEquals(100f, rb.width, 0.2f); assertEquals(200f, rb.height, 0.2f)
        assertEquals(500f, (rb.left + rb.right) / 2f, 0.2f)
    }

    @Test
    fun arcSplitsIntoQuarters() {
        val ops = ArrayList<PathOp>()
        ShapeGeometry.arcToCubics(Vec2.ZERO, 10f, 10f, 0f, Math.PI.toFloat() * 1.5f, ops)
        assertEquals(3, ops.size)
        assertVec(Vec2(0f, -10f), lastPoint(ops.last()))
    }

    @Test
    fun arrowHeads() {
        val a = Vec2(0f, 0f); val b = Vec2(100f, 0f)
        val g = ShapeGeometry.arrow(a, b, 4f, ArrowHeads.BOTH, ArrowHeadStyle.FILLED, 4f)
        val fill = g.fill.flatten()
        assertEquals(2, fill.size)
        // Each head has its own bounds (not one box spanning the whole arrow).
        val headBounds = g.fill.subpathControlBounds()
        assertEquals(2, headBounds.size)
        assertEquals(84f, headBounds[0].left, 1e-3f); assertEquals(100f, headBounds[0].right, 1e-3f)
        assertEquals(0f, headBounds[1].left, 1e-3f); assertEquals(16f, headBounds[1].right, 1e-3f)
        // Head length = 16 px, half width 8 px, tip at the end point.
        val endHead = fill[0].points
        assertVec(b, endHead[0])
        assertVec(Vec2(84f, 8f), endHead[1], 1e-3f)
        // Shaft is shortened under both heads.
        val shaft = g.stroke.flatten().single().points
        assertTrue(shaft.first().x > 0f && shaft.last().x < 100f)
        // Open heads are chevrons in the stroke, no fill.
        val open = ShapeGeometry.arrow(a, b, 4f, ArrowHeads.END, ArrowHeadStyle.OPEN, 4f)
        assertTrue(open.fill.isEmpty)
        assertEquals(2, open.stroke.flatten().size)
        // Heads never overlap on very short arrows.
        val short = ShapeGeometry.arrow(a, Vec2(10f, 0f), 10f, ArrowHeads.BOTH, ArrowHeadStyle.FILLED, 6f)
        val heads = short.fill.flatten()
        assertTrue(heads[0].points[1].x >= heads[1].points[1].x)
        // Zero-length arrow draws nothing.
        assertTrue(ShapeGeometry.arrow(a, a, 4f, ArrowHeads.END, ArrowHeadStyle.FILLED, 4f).stroke.isEmpty)
    }

    @Test
    fun angleAndGridSnapping() {
        val s = ShapeGeometry.snapAngle(Vec2(0f, 0f), Vec2(100f, 8f))
        assertEquals(0f, s.y, 1e-3f)
        assertEquals(100f, s.x, 1e-3f)
        val diag = ShapeGeometry.snapAngle(Vec2(0f, 0f), Vec2(100f, 95f))
        assertEquals(diag.x, diag.y, 1e-3f)
        assertEquals(30f, ShapeGeometry.snapDegrees(34f), 0f)
        assertEquals(180f, ShapeGeometry.normalizeDegrees(-180f), 0f)
        assertEquals(-90f, ShapeGeometry.normalizeDegrees(270f), 0f)
        assertVec(Vec2(105f, 45f), ShapeGeometry.snapToGrid(Vec2(112f, 38f), 20f, 5f, 5f))
    }

    @Test
    fun dragBoxModes() {
        val corner = ShapeGeometry.dragBox(Vec2(100f, 100f), Vec2(40f, 160f), fromCenter = false, aspect = null)
        assertEquals(ShapeBox(70f, 130f, 60f, 60f, 0f), corner)
        val centered = ShapeGeometry.dragBox(Vec2(100f, 100f), Vec2(130f, 110f), fromCenter = true, aspect = null)
        assertEquals(ShapeBox(100f, 100f, 60f, 20f, 0f), centered)
        val square = ShapeGeometry.dragBox(Vec2(0f, 0f), Vec2(50f, 20f), fromCenter = false, aspect = 1f)
        assertEquals(50f, square.w, 0f); assertEquals(50f, square.h, 0f)
        assertEquals(25f, square.cy, 0f)
        val wide = ShapeGeometry.dragBox(Vec2(0f, 0f), Vec2(-10f, -40f), fromCenter = false, aspect = 2f)
        assertEquals(80f, wide.w, 0f); assertEquals(-40f, wide.cx, 0f); assertEquals(-20f, wide.cy, 0f)
    }

    @Test
    fun resizeKeepsOppositeEdgeEvenWhenRotated() {
        val box = ShapeBox(100f, 100f, 100f, 50f, 30f)
        val fixed = box.toDoc(Vec2(-50f, 0f)) // left edge midpoint
        val target = box.toDoc(Vec2(90f, 0f))
        val r = ShapeGeometry.resize(box, ShapeGeometry.Handle.RIGHT, target, fromCenter = false, aspect = null)
        assertEquals(140f, r.w, 1e-3f); assertEquals(50f, r.h, 1e-3f); assertEquals(30f, r.rotationDeg, 0f)
        assertVec(fixed, r.toDoc(Vec2(-r.w / 2f, 0f)))
        // Proportional corner resize keeps the aspect and the opposite corner.
        val tl = box.toDoc(Vec2(-50f, -25f))
        val p = ShapeGeometry.resize(box, ShapeGeometry.Handle.BOTTOM_RIGHT, box.toDoc(Vec2(150f, 30f)), false, aspect = 2f)
        assertEquals(2f, p.w / p.h, 1e-4f)
        assertVec(tl, p.toDoc(Vec2(-p.w / 2f, -p.h / 2f)))
        // Handles cannot cross the opposite edge.
        val min = ShapeGeometry.resize(box, ShapeGeometry.Handle.LEFT, box.toDoc(Vec2(500f, 0f)), false, null, minSize = 1f)
        assertEquals(1f, min.w, 1e-3f)
    }

    @Test
    fun lineBox() {
        val l = ShapeBox.line(Vec2(10f, 10f), Vec2(10f, 110f))
        assertEquals(100f, l.w, 1e-4f)
        assertEquals(90f, l.rotationDeg, 1e-4f)
        assertVec(Vec2(10f, 10f), l.start)
        assertVec(Vec2(10f, 110f), l.end)
        assertFalse(ShapeGeometry.outline(ShapeType.LINE, l, OutlineParams()).isEmpty)
        assertNotNull(ShapeGeometry.outline(ShapeType.RECTANGLE, l.copy(h = 20f), OutlineParams()).bounds())
    }
}
