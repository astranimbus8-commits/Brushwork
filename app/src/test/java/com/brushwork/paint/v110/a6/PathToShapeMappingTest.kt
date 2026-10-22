package com.brushwork.paint.v110.a6

import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.core.Geometry
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CornerStyle
import com.brushwork.paint.tools.vector.CurveGeometry
import com.brushwork.paint.tools.vector.JoinStyle
import com.brushwork.paint.tools.vector.LineCapStyle
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeOutlines
import com.brushwork.paint.tools.vector.ShapePoints
import com.brushwork.paint.tools.vector.ShapeStroke
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.vector.CopyMaps
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStrokeKind
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorOps
import com.brushwork.paint.vector.convert.PathToShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 A6 (R11 (a) "Mapping"): a Curve, Polyline or Path-tool path, open or closed, becomes a
 * custom outline of the Shape tool that draws the same segments: a curved segment with the same
 * Bézier control points, a straight one as a straight edge (no handles) between the same ends.
 * Smooth points stay smooth, sharp ones sharp, Polyline points are all sharp without handles. A
 * closed path is a closed outline, an open one a line. The line's color, width, cap and join (as
 * the corner), a solid fill, a brush line's tool, brush and grain, the id, opacity and copies are
 * copied.
 */
@RunWith(RobolectricTestRunner::class)
class PathToShapeMappingTest {

    private fun shapeOf(p: VPath): ShapeObject {
        assertNull("convertible", PathToShape.refusal(p))
        return PathToShape.convert(p).shape.shape
    }

    private fun assertNear(what: String, a: Vec2, b: Vec2) =
        assertTrue("$what: $a vs $b", a.distanceTo(b) < TOL)

    private fun onChord(c: Vec2, a: Vec2, b: Vec2) = Geometry.distanceToSegment(c, a, b) < 1e-3f

    /** Each segment of [p] is drawn by [o] too (see the class docs). */
    private fun assertSameSegments(what: String, p: VPath, o: ShapeObject) {
        val sub = p.subpaths.single()
        val closed = sub.closed && sub.anchors.size > 2
        val ca = VectorOps.curveAnchors(sub)
        val sa = ShapePoints.docAnchors(o.box, o.points!!)
        assertEquals("$what: one point per anchor", ca.size, sa.size)
        assertEquals("$what: closed", closed, o.closed)
        assertTrue("$what: a custom outline", ShapeOutlines.isCustom(o))
        val n = CurveGeometry.segmentCount(ca.size, closed)
        assertEquals(n, ShapePoints.segmentCount(sa.size, closed))
        for (s in 0 until n) {
            val ps = CurveGeometry.segment(ca, s, closed, p.tension, p.polyline)
            val ss = ShapePoints.segment(sa, s, closed)
            assertNear("$what segment $s start", ps[0], ss[0])
            assertNear("$what segment $s end", ps[3], ss[3])
            if (onChord(ps[1], ps[0], ps[3]) && onChord(ps[2], ps[0], ps[3])) {
                assertTrue("$what segment $s: straight, no handles", ShapePoints.isStraight(ss))
            } else {
                assertNear("$what segment $s first handle", ps[1], ss[1])
                assertNear("$what segment $s second handle", ps[2], ss[2])
            }
        }
    }

    @Test
    fun aCurveOpenAndClosed() {
        val open = A6Paths.curve(closed = false)
        val o = shapeOf(open)
        assertEquals("open: a line with points", ShapeType.LINE, o.type)
        assertTrue("smooth points stay smooth", o.points!!.all { it.smooth })
        assertSameSegments("open curve", open, o)

        val loop = A6Paths.curve(closed = true)
        val c = shapeOf(loop)
        assertEquals("closed: a closed outline", ShapeType.RECTANGLE, c.type)
        assertEquals(0f, c.rotation)
        assertSameSegments("closed curve", loop, c)
    }

    @Test
    fun aPolylineIsAllSharpWithoutHandles() {
        for (closed in listOf(false, true)) {
            val p = A6Paths.polyline(closed)
            val o = shapeOf(p)
            assertTrue("sharp", o.points!!.none { it.smooth })
            assertTrue("no handles", o.points!!.all { it.handleIn == null && it.handleOut == null })
            assertSameSegments("polyline closed=$closed", p, o)
        }
        // Smooth flags on a polyline's anchors don't curve it.
        val smoothFlags = A6Paths.polyline(true).let { p -> p.copy(subpaths = listOf(p.subpaths[0].copy(anchors = p.subpaths[0].anchors.map { it.copy(sharp = false) }))) }
        assertTrue(shapeOf(smoothFlags).points!!.none { it.smooth })
    }

    @Test
    fun aPathToolSplineOpenAndCyclic() {
        for (cyclic in listOf(false, true)) {
            val p = A6Paths.spline(cyclic)
            val o = shapeOf(p)
            assertEquals(if (cyclic) ShapeType.RECTANGLE else ShapeType.LINE, o.type)
            assertSameSegments("spline cyclic=$cyclic", p, o)
        }
    }

    @Test
    fun sharpPointsStaySharpAndTensionIsKept() {
        val p = VPath(
            0,
            subpaths = listOf(VSubpath(listOf(VAnchor(80f, 80f), VAnchor(200f, 60f, sharp = true), VAnchor(320f, 120f), VAnchor(260f, 240f, sharp = true), VAnchor(100f, 220f)), closed = true)),
            tension = 0.4f,
            stroke = VStrokeStyle(color = BLUE, width = 4f),
        )
        val o = shapeOf(p)
        assertEquals(listOf(true, false, true, false, true), o.points!!.map { it.smooth })
        assertSameSegments("mixed, tension 0.4", p, o)
        // Custom handles of a smooth anchor (an imported cubic) are kept.
        val custom = p.copy(subpaths = listOf(VSubpath(listOf(VAnchor(60f, 150f), VAnchor(200f, 80f, inX = -50f, inY = 30f, outX = 70f, outY = 10f), VAnchor(340f, 160f)))))
        assertSameSegments("custom handles", custom, shapeOf(custom))
    }

    @Test
    fun theLineAndFillAreCopied() {
        val copies = CopyMaps.of(listOf(floatArrayOf(-1f, 0f, 400f, 0f, 1f, 0f, 0f, 0f, 1f)))
        val p = A6Paths.polyline(closed = true).copy(
            id = 42L,
            opacity = 0.5f,
            fill = VPaint.Solid(RED),
            stroke = VStrokeStyle(color = BLUE, width = 6f, cap = LineCapStyle.BUTT, join = JoinStyle.BEVEL),
            copies = copies,
        )
        val r = PathToShape.convert(p)
        assertTrue("nothing lost", r.notes.isEmpty())
        assertEquals("the same id", 42L, r.shape.id)
        assertEquals(0.5f, r.shape.opacity)
        val o = r.shape.shape
        assertEquals("the copies as they are", copies, o.copies)
        assertEquals(ShapeStyle.STROKE_FILL, o.style)
        assertEquals(ShapeStroke.PLAIN, o.strokeWith)
        assertEquals(BLUE, o.strokeColor)
        assertEquals(6f, o.strokeWidth)
        assertEquals(LineCapStyle.BUTT, o.lineCap)
        assertEquals("bevel join, bevel corner", CornerStyle.BEVEL, o.corner)
        assertEquals("radius 0: the outline is unchanged", 0f, o.cornerRadius)
        assertEquals(RED, o.fillColor)
        assertFalse("the fill keeps its own color", o.fillFollowsColor)
        assertEquals(JoinStyle.BEVEL, ShapeOutlines.join(o))

        assertEquals(CornerStyle.SHARP, shapeOf(p.copy(stroke = p.stroke!!.copy(join = JoinStyle.MITER))).corner)
        assertEquals(CornerStyle.ROUND, shapeOf(p.copy(stroke = p.stroke!!.copy(join = JoinStyle.ROUND))).corner)

        // A fill alone fills; a line alone strokes.
        assertEquals(ShapeStyle.FILL, shapeOf(p.copy(stroke = null)).style)
        assertEquals(ShapeStyle.STROKE, shapeOf(p.copy(fill = null)).style)
        // Thickness factors of 2 at every anchor: twice the width, drawn round (no note).
        val doubled = p.copy(subpaths = listOf(p.subpaths[0].copy(anchors = p.subpaths[0].anchors.map { it.copy(width = 2f) })))
        val d = PathToShape.convert(doubled)
        assertTrue(d.notes.isEmpty())
        assertEquals(12f, d.shape.shape.strokeWidth)
        assertEquals(CornerStyle.ROUND, d.shape.shape.corner)
        assertEquals(LineCapStyle.ROUND, d.shape.shape.lineCap)
    }

    @Test
    fun aBrushLineKeepsItsToolBrushAndGrain() {
        val preset = BrushLibrary.byId("calligraphy")!!
        val p = A6Paths.curve().copy(stroke = VStrokeStyle(kind = VStrokeKind.BRUSH, color = BLUE, width = preset.size, brushTool = ToolId.BRUSH, brush = preset, seed = 77L))
        val r = PathToShape.convert(p)
        val o = r.shape.shape
        assertEquals(ShapeStroke.BRUSH, o.strokeWith)
        assertEquals(ToolId.BRUSH.name, o.brushTool)
        assertEquals(preset, o.brushPreset)
        assertEquals("the grain", 77L, r.shape.seed)
        assertEquals(BLUE, o.strokeColor)
    }

    private companion object {
        const val TOL = 0.01f
        const val BLUE = 0xFF203080.toInt()
        const val RED = 0xFFE04020.toInt()
    }
}
