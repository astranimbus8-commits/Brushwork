package com.brushwork.paint.tools.vector

import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.core.Geometry
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.ToolId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Custom shape points ([ShapePoints]), the shape object codec and the shape outlines (pure Kotlin). */
class ShapePointsTest {

    private fun assertVec(expected: Vec2, actual: Vec2, eps: Float = 1e-3f) {
        assertEquals("x", expected.x, actual.x, eps)
        assertEquals("y", expected.y, actual.y, eps)
    }

    /** Largest distance from the points of [a] to the polyline(s) of [b] (both flattened finely). */
    private fun deviation(a: VectorPath, b: VectorPath): Float {
        val pa = a.flatten(0.02f).flatMap { it.points }
        val polys = b.flatten(0.02f)
        var worst = 0f
        for (p in pa) {
            var best = Float.MAX_VALUE
            for (poly in polys) {
                val pts = if (poly.closed) poly.points + poly.points.first() else poly.points
                for (i in 1 until pts.size) best = minOf(best, Geometry.distanceToSegment(p, pts[i - 1], pts[i]))
            }
            worst = maxOf(worst, best)
        }
        return worst
    }

    private fun sameOutline(a: VectorPath, b: VectorPath, eps: Float = 0.05f) {
        val d1 = deviation(a, b)
        val d2 = deviation(b, a)
        assertTrue("outlines differ by $d1 / $d2 px", d1 <= eps && d2 <= eps)
    }

    private fun custom(type: ShapeType, box: ShapeBox, params: OutlineParams): VectorPath {
        val pts = ShapePoints.fromRegular(type, params)
        val o = ShapeObject(type = type, cx = box.cx, cy = box.cy, w = box.w, h = box.h, rotation = box.rotationDeg,
            corner = params.corner, cornerRadius = params.cornerRadius, sides = params.sides, starPoints = params.starPoints,
            innerRatio = params.innerRatio, points = pts)
        return ShapeOutlines.outline(o)
    }

    // ------------------------------------------------------------------ conversion

    @Test
    fun convertingKeepsTheOutline() {
        val box = ShapeBox(120f, 90f, 160f, 100f, 20f)
        for (corner in CornerStyle.entries) {
            val params = OutlineParams(sides = 6, starPoints = 5, innerRatio = 0.4f, corner = corner, cornerRadius = 14f)
            for (type in listOf(ShapeType.RECTANGLE, ShapeType.POLYGON, ShapeType.STAR)) {
                sameOutline(ShapeGeometry.outline(type, box, params), custom(type, box, params), eps = 1e-3f)
            }
        }
        sameOutline(ShapeGeometry.outline(ShapeType.ELLIPSE, box, OutlineParams()), custom(ShapeType.ELLIPSE, box, OutlineParams()), eps = 1e-3f)
    }

    @Test
    fun ellipsePointsHaveTheExactArcHandles() {
        val pts = ShapePoints.fromRegular(ShapeType.ELLIPSE, OutlineParams())
        assertEquals(4, pts.size)
        assertTrue(pts.all { it.smooth && it.handleIn != null && it.handleOut != null })
        val local = ShapePoints.localAnchors(pts, 200f, 100f)
        val ours = ShapePoints.outline(local, closed = true, corner = CornerStyle.SHARP, radius = 0f).ops
        val theirs = ShapeGeometry.ellipsePath(200f, 100f).ops
        assertEquals(theirs.size, ours.size)
        for (i in ours.indices) {
            val a = ours[i]; val b = theirs[i]
            when (a) {
                is PathOp.MoveTo -> assertVec((b as PathOp.MoveTo).p, a.p)
                is PathOp.CubicTo -> { b as PathOp.CubicTo; assertVec(b.c1, a.c1); assertVec(b.c2, a.c2); assertVec(b.p, a.p) }
                else -> assertEquals(b, a)
            }
        }
    }

    @Test
    fun linesBecomeTwoEndPoints() {
        val pts = ShapePoints.fromRegular(ShapeType.ARROW, OutlineParams())
        val box = ShapeBox.line(Vec2(10f, 20f), Vec2(110f, 70f))
        val a = ShapePoints.docAnchors(box, pts)
        assertVec(Vec2(10f, 20f), a[0].pos)
        assertVec(Vec2(110f, 70f), a[1].pos)
    }

    // ------------------------------------------------------------------ editing

    @Test
    fun insertingOnAStraightEdgeKeepsTheOutline() {
        val sq = listOf(Vec2(0f, 0f), Vec2(100f, 0f), Vec2(100f, 100f), Vec2(0f, 100f)).map { ShapeAnchor(it) }
        val (list, at) = ShapePoints.insert(sq, closed = true, s = 1, t = 0.25f)
        assertEquals(2, at)
        assertEquals(5, list.size)
        assertVec(Vec2(100f, 25f), list[at].pos)
        assertFalse(list[at].smooth)
        sameOutline(ShapePoints.path(sq, true), ShapePoints.path(list, true), eps = 1e-3f)
    }

    @Test
    fun insertingOnACurveKeepsTheOutline() {
        // Smooth points with automatic tangents: the neighbors' tangents are kept explicitly.
        val a = listOf(Vec2(0f, 0f), Vec2(100f, -30f), Vec2(200f, 40f), Vec2(80f, 120f)).map { ShapeAnchor(it, smooth = true) }
        val before = ShapePoints.path(a, true)
        for (s in 0 until 4) {
            val (list, at) = ShapePoints.insert(a, closed = true, s = s, t = 0.37f)
            assertTrue(list[at].smooth)
            sameOutline(before, ShapePoints.path(list, true))
        }
        // An open path too, at its ends.
        val open = a.take(3)
        val (list, _) = ShapePoints.insert(open, closed = false, s = 1, t = 0.8f)
        sameOutline(ShapePoints.path(open, false), ShapePoints.path(list, false))
    }

    @Test
    fun insertingOnAnEdgeWithCornerStylesKeepsTheOutline() {
        // A big radius is limited by half the shorter edge: a point inserted on an edge must not
        // make the neighboring corners smaller (it is not a corner while it lies on the edge).
        val sq = listOf(Vec2(0f, 0f), Vec2(100f, 0f), Vec2(100f, 60f), Vec2(0f, 60f)).map { ShapeAnchor(it) }
        for (corner in listOf(CornerStyle.ROUND, CornerStyle.BEVEL, CornerStyle.INVERTED)) {
            val before = ShapePoints.outline(sq, true, corner, 40f)
            for ((s, t) in listOf(1 to 0.5f, 0 to 0.1f, 3 to 0.85f, 2 to 0.3f)) {
                val (list, _) = ShapePoints.insert(sq, closed = true, s = s, t = t)
                sameOutline(before, ShapePoints.outline(list, true, corner, 40f), eps = 1e-2f)
                // Two points inserted on the same edge, and one on the closing edge before point 0.
                val (twice, _) = ShapePoints.insert(list, closed = true, s = s, t = 0.5f)
                sameOutline(before, ShapePoints.outline(twice, true, corner, 40f), eps = 1e-2f)
            }
            // With a curved segment elsewhere (corners only between straight edges).
            val mixed = sq.toMutableList().also { it.add(2, ShapeAnchor(Vec2(130f, 30f), smooth = true)) }
            val m0 = ShapePoints.outline(mixed, true, corner, 40f)
            val (m1, _) = ShapePoints.insert(mixed, closed = true, s = 4, t = 0.5f)
            sameOutline(m0, ShapePoints.outline(m1, true, corner, 40f), eps = 1e-2f)
            // Moved off the edge it is a corner like the others.
            val (moved, at) = ShapePoints.insert(sq, closed = true, s = 1, t = 0.5f)
            val bent = moved.mapIndexed { i, a -> if (i == at) a.moved(Vec2(130f, 30f)) else a }
            val bentPath = ShapePoints.outline(bent, true, corner, 40f)
            assertTrue("$corner: the moved point is treated", bentPath.ops.size > ShapePoints.path(bent, true).ops.size + 3)
        }
    }

    @Test
    fun nearestFindsTheEdgeAndItsParameter() {
        val sq = listOf(Vec2(0f, 0f), Vec2(100f, 0f), Vec2(100f, 100f), Vec2(0f, 100f)).map { ShapeAnchor(it) }
        val hit = ShapePoints.nearest(sq, true, Vec2(103f, 60f))!!
        assertEquals(1, hit.segment)
        assertEquals(0.6f, hit.t, 1e-3f)
        assertEquals(3f, hit.distance, 1e-3f)
        // The closing edge of a closed shape counts.
        assertEquals(3, ShapePoints.nearest(sq, true, Vec2(-2f, 50f))!!.segment)
    }

    @Test
    fun smoothSharpAndHandles() {
        val sq = listOf(Vec2(0f, 0f), Vec2(100f, 0f), Vec2(100f, 100f), Vec2(0f, 100f)).map { ShapeAnchor(it) }
        val smooth = ShapePoints.setSmooth(sq, 1, true)
        val (hIn, hOut) = ShapePoints.handles(smooth, 1, true)
        // Catmull-Rom: a third of half the vector from the previous to the next point.
        assertVec(Vec2(100f, 100f) / 6f, hOut)
        assertVec(-(Vec2(100f, 100f) / 6f), hIn)
        // Dragging a smooth point's handle keeps the other collinear with its own length.
        val dragged = ShapePoints.dragHandle(smooth, 1, true, out = true, v = Vec2(0f, 40f))
        val (dIn, dOut) = ShapePoints.handles(dragged, 1, true)
        assertVec(Vec2(0f, 40f), dOut)
        assertVec(Vec2(0f, -hIn.length), dIn)
        assertTrue(ShapePoints.autoTangent(dragged, 1)[1].handleOut == null)
        // Sharp again: straight edges.
        val sharp = ShapePoints.setSmooth(dragged, 1, false)
        assertTrue(ShapePoints.isStraight(ShapePoints.segment(sharp, 0, true)))
        assertTrue(ShapePoints.isStraight(ShapePoints.segment(sharp, 1, true)))
    }

    @Test
    fun fitWrapsTheOutlineAndKeepsDocumentPositions() {
        val anchors = listOf(Vec2(10f, 10f), Vec2(90f, 30f), Vec2(50f, 120f)).map { ShapeAnchor(it) }
        val (box, pts) = ShapePoints.fit(0f, anchors, closed = true)
        assertEquals(50f, box.cx, 1e-3f)
        assertEquals(65f, box.cy, 1e-3f)
        assertEquals(80f, box.w, 1e-3f)
        assertEquals(110f, box.h, 1e-3f)
        val back = ShapePoints.docAnchors(box, pts)
        for (i in anchors.indices) assertVec(anchors[i].pos, back[i].pos)
        // Turned: the box keeps the rotation and still holds the points.
        val (tb, tp) = ShapePoints.fit(30f, anchors, closed = true)
        assertEquals(30f, tb.rotationDeg, 0f)
        val tback = ShapePoints.docAnchors(tb, tp)
        for (i in anchors.indices) assertVec(anchors[i].pos, tback[i].pos)
        // A straight horizontal line has no height: y is stored as 0.
        val (lb, lp) = ShapePoints.fit(0f, listOf(ShapeAnchor(Vec2(0f, 5f)), ShapeAnchor(Vec2(40f, 5f))), closed = false)
        assertEquals(0f, lb.h, 0f)
        assertTrue(lp.all { it.y == 0f })
    }

    @Test
    fun resizingTheBoxScalesCustomPoints() {
        val pts = ShapePoints.fromRegular(ShapeType.STAR, OutlineParams(starPoints = 5))
        val small = ShapePoints.docAnchors(ShapeBox(0f, 0f, 100f, 100f), pts)
        val wide = ShapePoints.docAnchors(ShapeBox(0f, 0f, 200f, 100f), pts)
        for (i in pts.indices) {
            assertEquals(small[i].pos.x * 2f, wide[i].pos.x, 1e-3f)
            assertEquals(small[i].pos.y, wide[i].pos.y, 1e-3f)
        }
    }

    @Test
    fun cornersOnlyBetweenStraightEdgesOfMixedShapes() {
        val sq = listOf(Vec2(0f, 0f), Vec2(100f, 0f), Vec2(100f, 100f), Vec2(0f, 100f)).map { ShapeAnchor(it) }
        // Point 1 smooth: its corner is a curve; corners 2 and 3 are between straight edges... only 3 is.
        val mixed = ShapePoints.setSmooth(sq, 1, true)
        val round = ShapePoints.outline(mixed, true, CornerStyle.ROUND, 10f)
        val sharp = ShapePoints.outline(mixed, true, CornerStyle.SHARP, 10f)
        // The bottom-left corner (3: both edges straight) is rounded: (0, 100) is no longer on it.
        val polys = round.flatten(0.05f).flatMap { it.points }
        assertTrue(polys.none { it.distanceTo(Vec2(0f, 100f)) < 2f })
        assertTrue(sharp.flatten(0.05f).flatMap { it.points }.any { it.distanceTo(Vec2(0f, 100f)) < 1e-3f })
        // All straight: exactly the polygon corner path.
        val allStraight = ShapePoints.outline(sq, true, CornerStyle.BEVEL, 10f)
        assertEquals(ShapeGeometry.cornerPath(sq.map { it.pos }, CornerStyle.BEVEL, 10f).ops, allStraight.ops)
    }

    // ------------------------------------------------------------------ arrows along a path

    @Test
    fun arrowAlongATwoPointPathMatchesTheStraightArrow() {
        val a = Vec2(10f, 50f); val b = Vec2(150f, 80f)
        for (style in ArrowHeadStyle.entries) for (heads in ArrowHeads.entries) {
            val straight = ShapeGeometry.arrow(a, b, 6f, heads, style, 4f)
            val along = ShapeGeometry.arrowAlong(listOf(a, b), 6f, heads, style, 4f)
            sameOutline(straight.stroke, along.stroke, eps = 1e-3f)
            if (!straight.fill.isEmpty) sameOutline(straight.fill, along.fill, eps = 1e-3f)
        }
        // A bent arrow's end head points along its last segment.
        val bent = ShapeGeometry.arrowAlong(listOf(Vec2(0f, 0f), Vec2(100f, 0f), Vec2(100f, 100f)), 4f, ArrowHeads.END, ArrowHeadStyle.FILLED, 4f)
        val tip = (bent.fill.ops[0] as PathOp.MoveTo).p
        assertVec(Vec2(100f, 100f), tip)
        val l = (bent.fill.ops[1] as PathOp.LineTo).p
        assertTrue("head base above the tip", l.y < 100f && abs(l.y - (100f - 16f)) < 1e-3f)
    }

    // ------------------------------------------------------------------ codec

    private fun sample() = ShapeObject(
        type = ShapeType.STAR, cx = 120f, cy = 80f, w = 90f, h = 70f, rotation = 15f,
        style = ShapeStyle.STROKE_FILL, strokeWidth = 7f, strokeWith = ShapeStroke.BRUSH,
        strokeColor = 0xFF102030.toInt(), fillColor = 0x80405060.toInt(), fillFollowsColor = false,
        corner = CornerStyle.ROUND, cornerRadius = 6f, starPoints = 7, innerRatio = 0.3f,
        brushTool = ToolId.BRUSH.name, brushPreset = BrushLibrary.defaultBrush.copy(size = 23f),
        points = ShapePoints.fromRegular(ShapeType.STAR, OutlineParams(starPoints = 7, innerRatio = 0.3f)).mapIndexed { i, p ->
            if (i == 2) p.copy(smooth = true, handleIn = ShapeHandle(-0.1f, 0f), handleOut = ShapeHandle(0.1f, 0f)) else p
        },
    )

    @Test
    fun codecRoundTrip() {
        val o = sample()
        val json = ShapeCodec.encode(o)
        assertTrue(json.contains("\"version\""))
        val back = ShapeCodec.decode(json)
        assertEquals(o, back)
        assertEquals(ToolId.BRUSH, back!!.brushToolId)
        assertEquals(23f, back.brushPreset!!.size, 0f)
    }

    @Test
    fun codecToleratesUnknownKeysAndRejectsGarbage() {
        val json = ShapeCodec.encode(sample())
        val future = json.replaceFirst("{", "{\"futureField\":[1,2,3],").replace("\"type\":\"STAR\"", "\"type\":\"STAR\",\"glow\":{\"r\":3}")
        assertEquals(sample(), ShapeCodec.decode(future))
        // A type written by a newer version falls back to the default.
        assertNotNull(ShapeCodec.decode(json.replace("\"STAR\"", "\"BLOB\"")))
        assertNull(ShapeCodec.decode(null))
        assertNull(ShapeCodec.decode(""))
        assertNull(ShapeCodec.decode("not json"))
        assertNull(ShapeCodec.decode("{}"))
        assertNull(ShapeCodec.decode("{\"shape\":1}"))
        assertNull(ShapeCodec.decode("[1,2]"))
        // Unusable placement.
        assertNull(ShapeCodec.decode(json.replace("\"cx\":120.0", "\"cx\":NaN")))
    }

    /**
     * Review (I13): a shape layer's points keep their own roundness through the codec, and a point
     * without one writes no "radius" key (a v1.6 shape's points read and write as before).
     */
    @Test
    fun codecKeepsAPointsOwnRoundnessAndWritesNoneWithout() {
        val plain = sample()
        assertFalse(ShapeCodec.encode(plain).contains("\"radius\""))
        val rounded = plain.copy(points = plain.points!!.mapIndexed { i, p -> if (i == 3) p.copy(radius = 12.5f) else p })
        val json = ShapeCodec.encode(rounded)
        assertTrue(json.contains("\"radius\":12.5"))
        val back = ShapeCodec.decode(json)!!
        assertEquals(rounded, back)
        assertEquals(listOf(null, null, null, 12.5f), back.points!!.take(4).map { it.radius })
    }

    // ------------------------------------------------------------------ hit testing and features

    @Test
    fun hitTestingOutlineFillAndArrowheads() {
        val rect = ShapeObject(type = ShapeType.RECTANGLE, cx = 100f, cy = 100f, w = 100f, h = 60f, style = ShapeStyle.STROKE, strokeWidth = 4f)
        assertTrue(ShapeOutlines.hits(rect, Vec2(152f, 100f), 5f))
        assertFalse("hollow inside", ShapeOutlines.hits(rect, Vec2(100f, 100f), 5f))
        assertTrue("filled inside", ShapeOutlines.hits(rect.copy(style = ShapeStyle.FILL), Vec2(100f, 100f), 5f))
        assertFalse(ShapeOutlines.hits(rect, Vec2(170f, 100f), 5f))
        val arrow = ShapeObject(type = ShapeType.ARROW, cx = 100f, cy = 100f, w = 160f, h = 0f, strokeWidth = 4f, arrowHeadScale = 8f)
        assertTrue(ShapeOutlines.hits(arrow, Vec2(60f, 101f), 3f))
        // Inside the filled head, off the shaft.
        assertTrue(ShapeOutlines.hits(arrow, Vec2(160f, 108f), 1f))
    }

    @Test
    fun featurePointsAreVerticesCornersAndCenter() {
        val rect = ShapeObject(type = ShapeType.RECTANGLE, cx = 100f, cy = 100f, w = 100f, h = 60f)
        val f = ShapeOutlines.featurePoints(rect)
        assertTrue(f.any { it.distanceTo(Vec2(50f, 70f)) < 1e-3f })
        assertTrue(f.any { it.distanceTo(Vec2(100f, 100f)) < 1e-3f })
        val line = ShapeObject(type = ShapeType.LINE, cx = 100f, cy = 100f, w = 100f, h = 0f)
        assertTrue(ShapeOutlines.featurePoints(line).any { it.distanceTo(Vec2(150f, 100f)) < 1e-3f })
        val custom = rect.copy(points = listOf(ShapePoint(-0.5f, -0.5f), ShapePoint(0.5f, 0f), ShapePoint(-0.2f, 0.5f)))
        assertTrue(ShapeOutlines.featurePoints(custom).any { it.distanceTo(Vec2(150f, 100f)) < 1e-3f })
    }
}
