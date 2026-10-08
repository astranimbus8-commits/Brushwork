package com.brushwork.paint.tools.vector

import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.core.Geometry
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.spline.NurbsGeometry
import com.brushwork.paint.tools.vector.spline.SplineBezier
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VSpline
import com.brushwork.paint.vector.VStrokeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.tan

/**
 * v1.7 item 6 (design §3.6, area C): [ShapeToSpline.convert] turns a shape into a Path-tool
 * path whose spline draws the shape's outline exactly (sampled with de Boor, within 0.01 px)
 * except at the selected corners, which become editable NURBS arcs at a quarter of their shorter
 * side; the look carries over; arrows are refused. The spline is sanitize-stable and its path
 * holds its Bézier form (I9).
 */
class ShapeToSplineTest {

    @Test
    fun aSquareGivesFourSharpPointsAndTheSameOutline() {
        val o = ShapeObject(type = ShapeType.RECTANGLE, cx = 200f, cy = 150f, w = 100f, h = 100f)
        val r = convert(o)
        val s = r.path.spline!!
        assertEquals(4, s.points.size)
        assertTrue(s.points.all { it.sharp && it.weight == 1f && it.width == 1f })
        assertTrue(s.cyclic)
        assertEquals(VSpline.DEFAULT_ORDER, s.order)
        assertTrue(s.endpoint)
        assertEquals(0, r.selectedSplineIndices.size)
        assertSameOutline(o, s)
    }

    @Test
    fun aRoundedRectangleGivesExactArcs() {
        val o = ShapeObject(type = ShapeType.RECTANGLE, cx = 120f, cy = 90f, w = 160f, h = 100f, corner = CornerStyle.ROUND, cornerRadius = 20f)
        val s = convert(o).path.spline!!
        // Four arcs (a, c, b) joined by sides: 12 points, the four c weighted cos(45°).
        assertEquals(12, s.points.size)
        val arcs = s.points.filter { !it.sharp }
        assertEquals(4, arcs.size)
        for (c in arcs) assertEquals(cos(PI / 4).toFloat(), c.weight, 1e-5f)
        // Every arc piece lies on its circle of radius 20 (corner centres 20 px inside the box).
        val centres = listOf(Vec2(60f, 60f), Vec2(180f, 60f), Vec2(180f, 120f), Vec2(60f, 120f))
        for (piece in NurbsGeometry.pieces(s).filter { it.points.size == 3 }) {
            val pts = samples(piece, 100)
            val centre = centres.minBy { it.distanceTo(pts[50]) }
            for (p in pts) assertEquals(20f, p.distanceTo(centre), 0.01f)
        }
        assertSameOutline(o, s)
    }

    @Test
    fun aSelectedTriangleCornerBecomesAnEditableArcAtAQuarterOfItsShorterSide() {
        val o = ShapeObject(type = ShapeType.POLYGON, sides = 3, cx = 150f, cy = 150f, w = 120f, h = 100f)
        val r = convert(o, 0)
        val s = r.path.spline!!
        val v = ShapePoints.docAnchors(o.box, ShapePoints.fromRegular(o.type, o.outlineParams)).map { it.pos }
        // The corner's c is the vertex itself, a SMOOTH point weighted cos(φ / 2), φ = π − θ.
        assertEquals(1, r.selectedSplineIndices.size)
        val c = s.points[r.selectedSplineIndices[0]]
        assertFalse(c.sharp)
        assertEquals(v[0].x, c.x, 1e-4f)
        assertEquals(v[0].y, c.y, 1e-4f)
        val uA = (v[2] - v[0]).normalized()
        val uB = (v[1] - v[0]).normalized()
        val theta = acos(uA.dot(uB).toDouble())
        assertEquals(cos((PI - theta) / 2).toFloat(), c.weight, 1e-5f)
        // The arc: cut back by a quarter of the shorter side, tangent to both sides.
        val d = 0.25f * minOf(v[0].distanceTo(v[1]), v[0].distanceTo(v[2]))
        val centre = v[0] + (uA + uB).normalized() * (d / cos(theta / 2).toFloat())
        val radius = d * tan(theta / 2).toFloat()
        val pieces = NurbsGeometry.pieces(s)
        val arc = pieces.single { p -> p.points.any { it === c } }
        val pts = samples(arc, 100)
        for (p in pts) assertEquals(radius, p.distanceTo(centre), 0.01f)
        // It differs visibly from the triangle near the corner ...
        val triangle = ShapeOutlines.outline(o).flatten(0.001f)
        assertTrue(distanceTo(pts[50], triangle) > 1f)
        // ... and nowhere else.
        for (piece in pieces.filter { it !== arc }) for (p in samples(piece, 60)) assertTrue("$p", distanceTo(p, triangle) <= 0.01f)
        assertI9(r.path)
    }

    @Test
    fun anEllipseIsExact() {
        assertSameOutline(ShapeObject(type = ShapeType.ELLIPSE, cx = 100f, cy = 120f, w = 180f, h = 90f))
        assertSameOutline(ShapeObject(type = ShapeType.ELLIPSE, cx = 100f, cy = 120f, w = 60f, h = 140f, rotation = 33f))
    }

    @Test
    fun aBrushStrokedRectangleKeepsItsToolBrushAndCap() {
        val pencil = BrushLibrary.byId("pencil")!!
        val o = ShapeObject(
            type = ShapeType.RECTANGLE, cx = 80f, cy = 80f, w = 100f, h = 60f, strokeWith = ShapeStroke.BRUSH, strokeColor = 0xFF336699.toInt(),
            strokeWidth = 12f, lineCap = LineCapStyle.SQUARE, brushTool = ToolId.BRUSH.name, brushPreset = pencil,
        )
        val st = convert(o).path.stroke!!
        assertEquals(VStrokeKind.BRUSH, st.kind)
        assertEquals(ToolId.BRUSH, st.brushTool)
        assertEquals(pencil, st.brush)
        assertEquals(LineCapStyle.SQUARE, st.cap)
        assertEquals(0xFF336699.toInt(), st.color)
        assertEquals(12f, st.width)
    }

    @Test
    fun plainStrokeFillAndBothCarryOver() {
        val base = ShapeObject(
            type = ShapeType.RECTANGLE, cx = 80f, cy = 80f, w = 100f, h = 60f, strokeColor = 0xFF102030.toInt(), fillColor = 0xFF405060.toInt(),
            strokeWidth = 7f, lineCap = LineCapStyle.BUTT, corner = CornerStyle.BEVEL, cornerRadius = 10f,
        )
        val stroke = convert(base.copy(style = ShapeStyle.STROKE)).path
        assertNull(stroke.fill)
        val st = stroke.stroke!!
        assertEquals(VStrokeKind.PLAIN, st.kind)
        assertEquals(0xFF102030.toInt(), st.color)
        assertEquals(7f, st.width)
        assertEquals(LineCapStyle.BUTT, st.cap)
        assertEquals(JoinStyle.BEVEL, st.join)
        val fill = convert(base.copy(style = ShapeStyle.FILL)).path
        assertNull(fill.stroke)
        assertEquals(VPaint.Solid(0xFF405060.toInt()), fill.fill)
        val both = convert(base.copy(style = ShapeStyle.STROKE_FILL)).path
        assertNotNull(both.stroke)
        assertEquals(VPaint.Solid(0xFF405060.toInt()), both.fill)
        // A bevel is a, b: 8 sharp points, the same outline.
        assertTrue(stroke.spline!!.points.all { it.sharp })
        assertEquals(8, stroke.spline!!.points.size)
        assertSameOutline(base)
    }

    @Test
    fun anArrowIsRefused() {
        assertNull(ShapeToSpline.convert(ShapeObject(type = ShapeType.ARROW, cx = 50f, cy = 50f, w = 80f, h = 0f), intArrayOf()))
        assertNull(ShapeToSpline.convert(ShapeObject(type = ShapeType.ARROW, cx = 50f, cy = 50f, w = 80f, h = 0f), intArrayOf(0)))
    }

    @Test
    fun cornerStylesStarsRotationAndOwnRadiiStayExact() {
        for (corner in CornerStyle.entries) {
            assertSameOutline(ShapeObject(type = ShapeType.STAR, starPoints = 5, innerRatio = 0.45f, cx = 150f, cy = 150f, w = 200f, h = 190f, corner = corner, cornerRadius = 9f, rotation = 17f))
            assertSameOutline(ShapeObject(type = ShapeType.POLYGON, sides = 6, cx = 150f, cy = 150f, w = 200f, h = 170f, corner = corner, cornerRadius = 25f, rotation = -40f))
        }
        // A corner's own radius on a sharp shape.
        val square = ShapePoints.fromRegular(ShapeType.RECTANGLE, OutlineParams(5, 5, 0.45f, CornerStyle.SHARP, 0f))
        val own = square.mapIndexed { i, p -> if (i == 2) p.copy(radius = 15f) else p }
        assertSameOutline(ShapeObject(type = ShapeType.RECTANGLE, cx = 100f, cy = 100f, w = 120f, h = 80f, points = own))
    }

    @Test
    fun aVeryPointedRoundTipIsSplitAndNeverClamped() {
        val o = ShapeObject(type = ShapeType.STAR, starPoints = 5, innerRatio = 0.1f, cx = 200f, cy = 200f, w = 300f, h = 300f, corner = CornerStyle.ROUND, cornerRadius = 12f)
        val s = convert(o).path.spline!!
        assertTrue(s.points.all { it.weight >= ShapeToSpline.MIN_ARC_WEIGHT.toFloat() - 1e-6f })
        assertSame(s, s.sanitized())
        assertSameOutline(o)
    }

    @Test
    fun pointsInTheMiddleOfASideStayOnItOrGoUnderACut() {
        // A square with a point 10 px along its top side (radii up to 30: the outline's cubic arcs are within 0.01 px of circles).
        fun square(radius: Float) = ShapeObject(
            type = ShapeType.RECTANGLE, cx = 100f, cy = 100f, w = 100f, h = 100f, corner = CornerStyle.ROUND, cornerRadius = radius,
            points = listOf(ShapePoint(-0.5f, -0.5f), ShapePoint(-0.4f, -0.5f), ShapePoint(0.5f, -0.5f), ShapePoint(0.5f, 0.5f), ShapePoint(-0.5f, 0.5f)),
        )
        // Outside the cut: kept as a sharp point on the side.
        val small = convert(square(5f)).path.spline!!
        assertTrue(small.points.any { abs(it.x - 60f) < 1e-3f && abs(it.y - 50f) < 1e-3f && it.sharp })
        assertSameOutline(square(5f))
        // Under the cut: left out.
        val big = convert(square(20f)).path.spline!!
        assertFalse(big.points.any { abs(it.x - 60f) < 1e-3f && abs(it.y - 50f) < 1e-3f })
        assertSameOutline(square(20f))
        // And without corner treatment the outline runs through it as it is.
        assertSameOutline(square(0f))
    }

    @Test
    fun aSelectedRoundedCornerKeepsItsArc() {
        val o = ShapeObject(type = ShapeType.RECTANGLE, cx = 120f, cy = 90f, w = 160f, h = 100f, corner = CornerStyle.ROUND, cornerRadius = 20f)
        val r = convert(o, 1, 3)
        assertEquals(2, r.selectedSplineIndices.size)
        for (i in r.selectedSplineIndices) {
            assertFalse(r.path.spline!!.points[i].sharp)
            assertEquals(cos(PI / 4).toFloat(), r.path.spline!!.points[i].weight, 1e-5f)
        }
        assertSameOutline(o, r.path.spline!!)
    }

    @Test
    fun aSelectedPointNextToACurveBecomesSmooth() {
        val o = ShapeObject(type = ShapeType.ELLIPSE, cx = 100f, cy = 100f, w = 120f, h = 80f)
        val r = convert(o, 2)
        val s = r.path.spline!!
        assertEquals(1, r.selectedSplineIndices.size)
        val p = s.points[r.selectedSplineIndices[0]]
        assertFalse(p.sharp)
        assertEquals(100f, p.x, 1e-3f)
        assertEquals(140f, p.y, 1e-3f)
        // The three other points stay sharp.
        assertEquals(3, s.points.count { it.sharp })
        assertI9(r.path)
    }

    @Test
    fun aLineIsAnOpenTwoPointPath() {
        val o = ShapeObject(type = ShapeType.LINE, cx = 100f, cy = 100f, w = 120f, h = 0f, rotation = 30f, lineCap = LineCapStyle.SQUARE, style = ShapeStyle.STROKE_FILL)
        val path = convert(o).path
        val s = path.spline!!
        assertFalse(s.cyclic)
        assertEquals(2, s.points.size)
        assertNull(path.fill)
        assertEquals(LineCapStyle.SQUARE, path.stroke!!.cap)
        assertSameOutline(o)
    }

    @Test
    fun everySelectedCornerOfASquareTurnsIntoAnArc() {
        val o = ShapeObject(type = ShapeType.RECTANGLE, cx = 100f, cy = 100f, w = 100f, h = 60f)
        val r = convert(o, 0, 1, 2, 3)
        val s = r.path.spline!!
        assertEquals(4, r.selectedSplineIndices.size)
        assertEquals(12, s.points.size)
        // Cut back by a quarter of the shorter side, 15 px: corner 0's arc starts 15 px below it.
        assertEquals(50f, s.points[0].x, 1e-3f)
        assertEquals(70f + 15f, s.points[0].y, 1e-3f)
        assertI9(r.path)
    }

    // ------------------------------------------------------------------ helpers

    private fun convert(o: ShapeObject, vararg selected: Int): ConvertResult {
        val r = ShapeToSpline.convert(o, selected)
        assertNotNull("${o.type} converts", r)
        assertI9(r!!.path)
        return r
    }

    /** The spline is sanitize-stable and the path holds exactly its Bézier form. */
    private fun assertI9(p: VPath) {
        val s = p.spline!!
        assertSame(s, s.sanitized())
        assertEquals(1, p.subpaths.size)
        assertTrue(SplineBezier.structurallyEqual(p.subpaths[0], SplineBezier.toSubpath(s), SplineBezier.MATCH_TOLERANCE))
        assertTrue(SplineBezier.matches(p))
    }

    /** The spline of [o]'s conversion (or [s]) runs along [o]'s outline and covers it, within 0.01 px both ways. */
    private fun assertSameOutline(o: ShapeObject, s: VSpline = convert(o).path.spline!!) {
        val outline = ShapeOutlines.outline(o).flatten(0.001f)
        val pieces = NurbsGeometry.pieces(s)
        val curve = ArrayList<Vec2>()
        for (piece in pieces) curve += samples(piece, 200)
        for (p in curve) assertTrue("${o.type} ${o.corner}: $p is ${distanceTo(p, outline)} from the outline", distanceTo(p, outline) <= 0.01f)
        val poly = listOf(Polyline(curve, NurbsGeometry.isClosed(s)))
        for (line in outline) for (q in line.points) assertTrue("${o.type} ${o.corner}: $q is ${distanceTo(q, poly)} from the path", distanceTo(q, poly) <= 0.01f)
    }

    private fun samples(s: VSpline, count: Int): List<Vec2> {
        val d = NurbsGeometry.domain(s)
        return List(count + 1) { k ->
            val p = NurbsGeometry.pointAt(s, d.start + (d.endInclusive - d.start) * k / count)
            Vec2(p[0].toFloat(), p[1].toFloat())
        }
    }

    private fun distanceTo(p: Vec2, polys: List<Polyline>): Float {
        var best = Float.MAX_VALUE
        for (poly in polys) {
            val pts = poly.points
            if (pts.size == 1) best = minOf(best, p.distanceTo(pts[0]))
            for (i in 1 until pts.size) best = minOf(best, Geometry.distanceToSegment(p, pts[i - 1], pts[i]))
            if (poly.closed && pts.size > 2) best = minOf(best, Geometry.distanceToSegment(p, pts.last(), pts[0]))
        }
        return best
    }
}
