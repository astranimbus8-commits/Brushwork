package com.brushwork.paint.vector.draw

import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.tools.vector.CurveGeometry
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.tools.vector.VectorPath
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * v1.5 A3: the vector eraser's geometry (JVM): intervals, capsules, crossings, the three modes on
 * crossing strokes (counts and remaining geometry), cut ends losing their taper, open paths split
 * exactly by de Casteljau, closed objects erased whole.
 */
class EraseMathTest {

    // ------------------------------------------------------------------ intervals

    @Test
    fun intervalsMergeAndComplement() {
        val s = Intervals()
        s.add(5f, 6f)
        s.add(1f, 2f)
        s.add(8f, 9f)
        assertEquals(3, s.size)
        s.add(1.5f, 5.5f) // joins the first two
        assertEquals(2, s.size)
        assertEquals(1f, s.start(0)); assertEquals(6f, s.end(0))
        s.add(9f, 8.5f) // inside, reversed
        assertEquals(2, s.size)
        s.add(6f, 8f) // touching: one interval
        assertEquals(1, s.size)
        assertEquals(1f, s.start(0)); assertEquals(9f, s.end(0))
        val kept = s.complement(0f, 12f)
        assertEquals(2, kept.size)
        assertEquals(0f, kept[0][0]); assertEquals(1f, kept[0][1])
        assertEquals(9f, kept[1][0]); assertEquals(12f, kept[1][1])
        assertTrue(s.overlaps(0f, 1f))
        assertFalse(s.overlaps(9.5f, 10f))
        assertTrue(Intervals().complement(0f, 3f).single().let { it[0] == 0f && it[1] == 3f })
    }

    // ------------------------------------------------------------------ capsules

    @Test
    fun aSegmentCrossingACapsuleGetsTheExactInterval() {
        val out = FloatArray(2)
        // Segment (0,0)->(100,0) against a vertical capsule x = 50, y in -20..20, radius 10.
        assertTrue(EraseMath.segmentInCapsule(0f, 0f, 100f, 0f, 50f, -20f, 50f, 20f, 10f, out))
        assertEquals(0.4f, out[0], 1e-5f)
        assertEquals(0.6f, out[1], 1e-5f)
        // Past the capsule's end disc: through the disc only.
        assertTrue(EraseMath.segmentInCapsule(0f, 26f, 100f, 26f, 50f, -20f, 50f, 20f, 10f, out))
        val half = kotlin.math.sqrt(100f - 36f) / 100f
        assertEquals(0.5f - half, out[0], 1e-5f)
        assertEquals(0.5f + half, out[1], 1e-5f)
        // Too far.
        assertFalse(EraseMath.segmentInCapsule(0f, 31f, 100f, 31f, 50f, -20f, 50f, 20f, 10f, out))
        // A capsule that is a disc (one eraser point), the segment ends inside it.
        assertTrue(EraseMath.segmentInCapsule(0f, 0f, 10f, 0f, 12f, 0f, 12f, 0f, 5f, out))
        assertEquals(0.7f, out[0], 1e-5f)
        assertEquals(1f, out[1], 1e-5f)
        // A point segment.
        assertTrue(EraseMath.segmentInCapsule(3f, 3f, 3f, 3f, 0f, 0f, 10f, 0f, 4f, out))
        assertFalse(EraseMath.segmentInCapsule(3f, 5f, 3f, 5f, 0f, 0f, 10f, 0f, 4f, out))
    }

    @Test
    fun capsuleIntervalsAlongAPolyline() {
        // An L-shaped line: (0,0) -> (100,0) -> (100,100).
        val line = FlatLine(floatArrayOf(0f, 100f, 100f), floatArrayOf(0f, 0f, 100f))
        val s = Intervals()
        EraseMath.capsuleIntervals(line, 100f, 50f, 100f, 50f, 10f, s)
        assertEquals(1, s.size)
        assertEquals(1.4f, s.start(0), 1e-5f)
        assertEquals(1.6f, s.end(0), 1e-5f)
        // Across the corner: one merged interval over both segments.
        val t = Intervals()
        EraseMath.capsuleIntervals(line, 80f, -30f, 130f, 20f, 15f, t)
        assertEquals(1, t.size)
        assertTrue(t.start(0) < 1f && t.end(0) > 1f)
        assertTrue(EraseMath.touches(line, 50f, 5f, 60f, 5f, 6f))
        assertFalse(EraseMath.touches(line, 50f, 50f, 60f, 50f, 6f))
    }

    @Test
    fun crossingsAndInsideTests() {
        val a = FlatLine(floatArrayOf(0f, 100f), floatArrayOf(0f, 0f))
        val b = FlatLine(floatArrayOf(25f, 25f), floatArrayOf(-10f, 10f))
        val out = ArrayList<Float>()
        EraseMath.crossings(a, b, out)
        assertEquals(listOf(0.25f), out)
        // A closed square crossed twice.
        val sq = FlatLine(floatArrayOf(40f, 60f, 60f, 40f), floatArrayOf(-10f, -10f, 10f, 10f), 4, closed = true)
        out.clear()
        EraseMath.crossings(a, sq, out)
        out.sort()
        assertEquals(2, out.size)
        assertEquals(0.4f, out[0], 1e-5f)
        assertEquals(0.6f, out[1], 1e-5f)
        assertTrue(EraseMath.inside(listOf(sq), 50f, 0f, false))
        assertFalse(EraseMath.inside(listOf(sq), 70f, 0f, false))
        // A long wavy line: the segment grid finds the same crossings as brute force.
        val n = 400
        val wx = FloatArray(n) { it * 1f }
        val wy = FloatArray(n) { 30f * kotlin.math.sin(it / 20f) }
        val wave = FlatLine(wx, wy)
        val axis = FlatLine(floatArrayOf(-5f, 405f), floatArrayOf(0.5f, 0.5f))
        out.clear()
        EraseMath.crossings(wave, axis, out)
        val brute = ArrayList<Float>()
        val tmp = FloatArray(2)
        for (i in 0 until n - 1) if (EraseMath.segmentIntersection(wx[i], wy[i], wx[i + 1], wy[i + 1], -5f, 0.5f, 405f, 0.5f, tmp)) brute += i + tmp[0]
        out.sort()
        assertEquals(brute, out)
        assertTrue(out.size >= 6)
    }

    @Test
    fun toIntersectionKeepsPiecesTheEraserOnlySpillsInto() {
        // A 100 px line crossed at 30 and 70; pieces [0,30], [30,70], [70,100].
        val line = FlatLine(floatArrayOf(0f, 100f), floatArrayOf(0f, 0f))
        val cr = listOf(0.3f, 0.7f)
        fun removed(touchFrom: Float, touchTo: Float, spill: Float): Intervals {
            val t = Intervals().also { it.add(touchFrom / 100f, touchTo / 100f) }
            return Intervals().also { EraseMath.piecesToIntersection(line, cr, t, spill, it) }
        }
        // Touching the middle piece: exactly it.
        val mid = removed(45f, 55f, 5f)
        assertEquals(1, mid.size)
        assertEquals(0.3f, mid.start(0), 1e-6f); assertEquals(0.7f, mid.end(0), 1e-6f)
        // Touching across the first crossing but only spilling 4 px into the middle: the first only.
        val first = removed(10f, 34f, 8f)
        assertEquals(1, first.size)
        assertEquals(0f, first.start(0), 1e-6f); assertEquals(0.3f, first.end(0), 1e-6f)
        // A wide swipe over both crossings: everything it covers.
        val all = removed(10f, 90f, 5f)
        assertEquals(0f, all.start(0), 1e-6f); assertEquals(1f, all.end(0), 1e-6f)
    }

    @Test
    fun subCubicsAreThePartsOfTheCurve() {
        val c = floatArrayOf(0f, 0f, 30f, 80f, 90f, -40f, 120f, 20f)
        val part = EraseMath.subCubic(c, 0.2f, 0.7f)
        val p = FloatArray(2)
        val q = FloatArray(2)
        for (k in 0..10) {
            val s = k / 10f
            EraseMath.cubicPoint(part, s, p)
            EraseMath.cubicPoint(c, 0.2f + 0.5f * s, q)
            assertEquals(q[0], p[0], 1e-3f)
            assertEquals(q[1], p[1], 1e-3f)
        }
    }

    // ------------------------------------------------------------------ sessions

    private val pen = BrushLibrary.defaultBrush.copy(size = 8f, scatter = 0f, taperStart = 30f, taperEnd = 30f)

    private fun stroke(x0: Float, y0: Float, x1: Float, y1: Float, n: Int = 21, id: Long = 0): VStroke {
        val xs = FloatArray(n) { x0 + (x1 - x0) * it / (n - 1) }
        val ys = FloatArray(n) { y0 + (y1 - y0) * it / (n - 1) }
        return VStroke(id, preset = pen, color = 0xFF000000.toInt(), seed = 1L, stylus = false, points = PackedPoints(xs, ys, FloatArray(n) { 1f }))
    }

    /** A horizontal stroke 0..200 at y 50 and a vertical one at x 100 crossing it. */
    private fun cross(): VectorContent = VectorContent.EMPTY.plus(listOf(stroke(0f, 50f, 200f, 50f), stroke(100f, 0f, 100f, 100f))).first

    private fun erase(content: VectorContent, mode: VectorEraseMode, r: Float, vararg pts: Pair<Float, Float>): VectorContent? {
        val s = EraseSession(content, mode)
        for ((x, y) in pts) s.add(x, y, r)
        return s.result()
    }

    @Test
    fun objectModeRemovesEveryTouchedObject() {
        val content = cross()
        // Touches only the horizontal stroke.
        val a = erase(content, VectorEraseMode.OBJECT, 5f, 20f to 45f, 30f to 45f)!!
        assertEquals(listOf(2L), a.objects.map { it.id })
        // Through the crossing: both.
        val b = erase(content, VectorEraseMode.OBJECT, 5f, 100f to 50f)!!
        assertTrue(b.objects.isEmpty())
        // Nothing touched.
        assertNull(erase(content, VectorEraseMode.OBJECT, 5f, 30f to 80f, 60f to 90f))
    }

    @Test
    fun partialModeCutsStrokesAndTheCutEndsLoseTheirTaper() {
        val content = cross()
        // An eraser of radius 6 across the horizontal stroke at x = 40 (pen radius 4).
        val after = erase(content, VectorEraseMode.PARTIAL, 6f, 40f to 30f, 40f to 70f)!!
        assertEquals(3, after.objects.size)
        val left = after.objects[0] as VStroke
        val right = after.objects[1] as VStroke
        assertEquals(1L, left.id) // the first piece keeps the id and the place
        assertEquals(3L, right.id) // the next one gets a new id right after it
        assertEquals(2L, after.objects[2].id)
        assertEquals(4L, after.nextId)
        // The cut lies 6 + 4 px from the eraser's centerline: the remaining dabs end at its edge.
        val lp = left.points
        assertEquals(0f, lp.x[0], 1e-4f)
        assertEquals(30f, lp.x[lp.size - 1], 1e-3f)
        assertEquals(50f, right.points.x[0], 1e-3f)
        assertEquals(200f, right.points.x[right.points.size - 1], 1e-4f)
        // The original ends keep their taper, the cut ends lose it.
        assertTrue(left.taperIn); assertFalse(left.taperOut)
        assertFalse(right.taperIn); assertTrue(right.taperOut)
        // Original points are kept exactly in between.
        for (k in 1 until lp.size - 1) assertEquals(0f, (lp.x[k] / 10f) - kotlin.math.round(lp.x[k] / 10f), 1e-5f)
    }

    @Test
    fun partialModeAcrossAFastStrokeWithFewPoints() {
        // Two points 200 px apart: the eraser passing between them still cuts the stroke.
        val content = VectorContent.EMPTY.plus(listOf(stroke(0f, 0f, 200f, 0f, n = 2))).first
        val after = erase(content, VectorEraseMode.PARTIAL, 10f, 100f to -20f, 100f to 20f)!!
        assertEquals(2, after.objects.size)
        val a = after.objects[0] as VStroke
        val b = after.objects[1] as VStroke
        assertEquals(2, a.points.size)
        assertEquals(86f, a.points.x[1], 1e-3f)
        assertEquals(114f, b.points.x[0], 1e-3f)
    }

    @Test
    fun partialModeErasesClosedAndFilledObjectsWhole() {
        val square = VPath(
            0, subpaths = listOf(VSubpath(listOf(VAnchor(10f, 10f, true), VAnchor(60f, 10f, true), VAnchor(60f, 60f, true), VAnchor(10f, 60f, true)), closed = true)),
            stroke = VStrokeStyle(color = 0xFF000000.toInt(), width = 4f),
        )
        val ellipse = VShape(0, shape = ShapeObject(ShapeType.ELLIPSE, cx = 150f, cy = 40f, w = 60f, h = 40f, style = ShapeStyle.STROKE_FILL, strokeWidth = 3f))
        val content = VectorContent.EMPTY.plus(listOf(square, ellipse)).first
        val s = EraseSession(content, VectorEraseMode.PARTIAL)
        s.add(60f, 30f, 3f)
        assertTrue(s.removedWholeInPartial)
        assertEquals(listOf(2L), s.result()!!.objects.map { it.id })
        // Inside a filled shape, far from its outline: it goes too.
        val t = EraseSession(content, VectorEraseMode.PARTIAL)
        t.add(150f, 40f, 3f)
        assertEquals(listOf(1L), t.result()!!.objects.map { it.id })
        // Inside an unfilled outline: nothing is touched.
        assertNull(EraseSession(content, VectorEraseMode.PARTIAL).also { it.add(35f, 35f, 3f) }.result())
    }

    @Test
    fun toIntersectionRemovesTheTouchedPieceBetweenCrossings() {
        // Horizontal line crossed by two vertical ones at x = 60 and x = 140.
        val content = VectorContent.EMPTY.plus(
            listOf(stroke(0f, 50f, 200f, 50f), stroke(60f, 0f, 60f, 100f), stroke(140f, 0f, 140f, 100f)),
        ).first
        // The overhang on the left (x < 60) is swiped.
        val a = erase(content, VectorEraseMode.TO_INTERSECTION, 6f, 20f to 30f, 25f to 70f)!!
        val h = a.objects.first { it.id == 1L } as VStroke
        assertEquals(60f, h.points.x[0], 1e-3f)
        assertEquals(200f, h.points.x[h.points.size - 1], 1e-3f)
        assertFalse(h.taperIn)
        assertTrue(h.taperOut)
        // The vertical lines are untouched.
        assertEquals(content.byId(2), a.byId(2))
        assertEquals(content.byId(3), a.byId(3))
        // The middle piece: the line splits into its two outer pieces.
        val b = erase(content, VectorEraseMode.TO_INTERSECTION, 6f, 100f to 40f, 100f to 60f)!!
        val pieces = b.objects.filter { it.id == 1L || it.id == 4L }.map { it as VStroke }
        assertEquals(2, pieces.size)
        assertEquals(60f, pieces[0].points.x[pieces[0].points.size - 1], 1e-3f)
        assertEquals(140f, pieces[1].points.x[0], 1e-3f)
        // A line with no crossing goes whole once touched.
        val single = VectorContent.EMPTY.plus(listOf(stroke(0f, 0f, 100f, 0f))).first
        assertTrue(erase(single, VectorEraseMode.TO_INTERSECTION, 5f, 50f to 0f)!!.objects.isEmpty())
    }

    @Test
    fun toIntersectionLeavesClosedObjectsAlone() {
        val ellipse = VShape(0, shape = ShapeObject(ShapeType.ELLIPSE, cx = 100f, cy = 50f, w = 80f, h = 60f, style = ShapeStyle.STROKE_FILL, strokeWidth = 3f))
        val content = VectorContent.EMPTY.plus(listOf(ellipse, stroke(0f, 50f, 200f, 50f))).first
        // The stroke crosses the ellipse at x = 60 and x = 140; the overhang left of it goes.
        val a = erase(content, VectorEraseMode.TO_INTERSECTION, 5f, 20f to 45f, 20f to 55f)!!
        assertEquals(ellipse.copy(id = 1L), a.byId(1))
        val s = a.byId(2) as VStroke
        assertEquals(60f, s.points.x[0], 0.5f)
    }

    @Test
    fun partialModeSplitsAnOpenCurveExactly() {
        // A smooth open curve through 4 anchors with a varying width.
        val anchors = listOf(VAnchor(0f, 100f, width = 1f), VAnchor(80f, 20f, width = 2f), VAnchor(160f, 120f, width = 1f), VAnchor(240f, 40f, width = 0.5f))
        val curve = VPath(0, subpaths = listOf(VSubpath(anchors)), tension = 0.1f, stroke = VStrokeStyle(color = 0xFF000000.toInt(), width = 6f))
        val content = VectorContent.EMPTY.plus(listOf(curve)).first
        val original = EraseTarget.subpathGeometry(content.objects[0] as VPath, (content.objects[0] as VPath).subpaths[0])
        val session = EraseSession(content, VectorEraseMode.PARTIAL)
        // A vertical eraser line at x = 120 crossing the middle segment.
        session.add(120f, -50f, 4f)
        session.add(120f, 200f, 4f)
        val after = session.result()!!
        assertEquals(2, after.objects.size)
        val left = after.objects[0] as VPath
        val right = after.objects[1] as VPath
        assertTrue(left.subpaths.single().anchors.all { it.sharp })
        assertEquals(0f, left.subpaths[0].anchors.first().x, 1e-4f)
        assertEquals(240f, right.subpaths[0].anchors.last().x, 1e-4f)
        // Untouched anchors keep their width; the pieces follow the original curve exactly.
        assertEquals(2f, left.subpaths[0].anchors[1].width, 1e-6f)
        assertEquals(0.5f, right.subpaths[0].anchors.last().width, 1e-6f)
        val orig = original.flatten(0.05f).single().points
        for (piece in listOf(left, right)) {
            for (p in ErasePieces.geometryOf(piece).flatten(0.1f).single().points) {
                val d = orig.zipWithNext().minOf { (a, b) -> EraseMath.pointSegmentDistance(p.x, p.y, a.x, a.y, b.x, b.y) }
                assertTrue("piece point $p is ${d}px off the curve", d < 0.2f)
            }
        }
        // The gap: no piece point within the eraser radius + half the (widest) line of x = 120.
        for (piece in listOf(left, right)) for (p in ErasePieces.geometryOf(piece).flatten(0.1f).single().points) {
            assertTrue("${p.x} is inside the erased band", abs(p.x - 120f) >= 9.5f)
        }
        // The left piece ends at the cut; the right one starts at it.
        val le = left.subpaths[0].anchors.last()
        val rs = right.subpaths[0].anchors.first()
        assertTrue(le.x < 120f && rs.x > 120f)
    }

    @Test
    fun dotsGoWholeInEveryMode() {
        // A tap with the brush: its down and up points at the same place.
        val p = PackedPoints(floatArrayOf(50f, 50f), floatArrayOf(40f, 40f), floatArrayOf(1f, 1f))
        val dot = VStroke(0, preset = pen, color = 0xFF000000.toInt(), seed = 1L, stylus = false, points = p)
        val content = VectorContent.EMPTY.plus(listOf(dot, stroke(0f, 100f, 100f, 100f))).first
        assertEquals(CutKind.WHOLE, EraseTarget.of(content.objects[0]).cut)
        assertTrue(EraseTarget.of(content.objects[0]).isLine)
        for (mode in VectorEraseMode.entries) {
            val s = EraseSession(content, mode)
            s.add(45f, 30f, 6f)
            s.add(55f, 50f, 6f)
            assertFalse("$mode: a dot is no closed shape", s.removedWholeInPartial)
            assertEquals("$mode removes the dot", listOf(2L), s.result()!!.objects.map { it.id })
        }
        // A path whose anchors are all at one place is a dot too.
        val flat = VPath(0, subpaths = listOf(VSubpath(listOf(VAnchor(10f, 10f), VAnchor(10f, 10f)))), stroke = VStrokeStyle(color = 0xFF000000.toInt(), width = 6f))
        val t = EraseTarget.of(flat)
        assertEquals(CutKind.WHOLE, t.cut)
        assertTrue(t.isLine)
        val c2 = VectorContent.EMPTY.plus(listOf(flat)).first
        assertTrue(EraseSession(c2, VectorEraseMode.TO_INTERSECTION).also { it.add(10f, 12f, 3f) }.result()!!.objects.isEmpty())
    }

    @Test
    fun aGrazeDoesNotSplit() {
        // The eraser passes exactly at its reach from the line: nothing is cut.
        val content = VectorContent.EMPTY.plus(listOf(stroke(0f, 0f, 100f, 0f))).first
        val r = 6f
        val reach = 4f // pen radius
        assertNull(erase(content, VectorEraseMode.PARTIAL, r, 50f to (r + reach + 0.001f)))
    }

    @Test
    fun theLengthOfAPolylinePart() {
        val line = FlatLine(floatArrayOf(0f, 3f, 3f), floatArrayOf(0f, 0f, 4f))
        assertEquals(7f, EraseMath.lengthBetween(line, 0f, 2f), 1e-5f)
        assertEquals(1.5f + 2f, EraseMath.lengthBetween(line, 0.5f, 1.5f), 1e-5f)
        val cum = line.cumulative()
        assertEquals(1.25f, line.uAtArc(4f, cum), 1e-5f)
        assertEquals(4f, line.arcAt(1.25f, cum), 1e-5f)
        assertNotNull(VectorPath.EMPTY)
        assertEquals(hypot(3f, 4f), 5f, 0f)
        assertEquals(2, CurveGeometry.segmentCount(3, false))
    }
}
