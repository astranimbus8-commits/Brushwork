package com.brushwork.paint.tools.vector

import com.brushwork.paint.core.Geometry
import com.brushwork.paint.core.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.acos

class CurveGeometryTest {

    private val zigzag = listOf(
        CurveAnchor(0f, 0f), CurveAnchor(100f, 80f), CurveAnchor(200f, 0f), CurveAnchor(300f, 80f),
    )

    private fun angleBetween(a: Vec2, b: Vec2): Float =
        Math.toDegrees(acos(a.normalized().dot(b.normalized()).coerceIn(-1f, 1f).toDouble())).toFloat()

    private fun pointOf(op: PathOp): Vec2 = when (op) {
        is PathOp.MoveTo -> op.p
        is PathOp.LineTo -> op.p
        is PathOp.CubicTo -> op.p
        PathOp.Close -> error("no point")
    }

    @Test
    fun catmullRomPassesThroughAnchors() {
        val path = CurveGeometry.toPath(zigzag, closed = false, tension = 0f, polyline = false)
        val ends = path.ops.map { pointOf(it) }
        assertEquals(zigzag.map { it.pos }, ends)
        assertEquals(3, path.ops.count { it is PathOp.CubicTo })
        // The flattened curve also passes (within tolerance) through every anchor.
        val pts = path.flatten(0.05f).single().points
        zigzag.forEach { a -> assertTrue(pts.any { it.distanceTo(a.pos) < 1e-3f }) }
        // Inner anchors are smooth: in/out handles are opposite and parallel to next - prev.
        val (hIn, hOut) = CurveGeometry.handles(zigzag, 1, false, 0f)
        assertEquals(180f, angleBetween(hIn, hOut), 0.1f)
        val chord = zigzag[2].pos - zigzag[0].pos
        assertEquals(0f, angleBetween(hOut, chord), 0.1f)
        assertEquals(chord.length / 6f, hOut.length, 1e-3f) // (next - prev) / 2 / 3
    }

    @Test
    fun sharpAnchorMakesACorner() {
        val sharp = zigzag.toMutableList().also { it[1] = it[1].copy(sharp = true) }
        val (hIn, hOut) = CurveGeometry.handles(sharp, 1, false, 0f)
        // Handles follow their own chords, so the tangents meet at the zigzag angle.
        val corner = angleBetween(-hIn, hOut)
        val chordAngle = angleBetween(zigzag[1].pos - zigzag[0].pos, zigzag[2].pos - zigzag[1].pos)
        assertEquals(chordAngle, corner, 0.1f)
        assertTrue(corner > 60f)
        // Along the flattened curve the direction jumps at the sharp anchor.
        val pts = CurveGeometry.toPath(sharp, false, 0f, false).flatten(0.02f).single().points
        val k = pts.indexOfFirst { it.distanceTo(zigzag[1].pos) < 1e-3f }
        val before = pts[k] - pts[k - 1]; val after = pts[k + 1] - pts[k]
        assertTrue(angleBetween(before, after) > 60f)
        // A smooth anchor has (nearly) no turn at the same place.
        val smoothPts = CurveGeometry.toPath(zigzag, false, 0f, false).flatten(0.02f).single().points
        val j = smoothPts.indexOfFirst { it.distanceTo(zigzag[1].pos) < 1e-3f }
        assertTrue(angleBetween(smoothPts[j] - smoothPts[j - 1], smoothPts[j + 1] - smoothPts[j]) < 15f)
    }

    @Test
    fun allSharpAndPolylineAreStraight() {
        val sharp = zigzag.map { it.copy(sharp = true) }
        val a = CurveGeometry.toPath(sharp, false, 0f, false)
        assertTrue(a.ops.drop(1).all { it is PathOp.LineTo })
        val b = CurveGeometry.toPath(zigzag, false, 0f, polyline = true)
        assertTrue(b.ops.drop(1).all { it is PathOp.LineTo })
        // Full tension collapses the handles, i.e. straight segments.
        val c = CurveGeometry.toPath(zigzag, false, tension = 1f, polyline = false)
        assertTrue(c.ops.drop(1).all { it is PathOp.LineTo })
    }

    @Test
    fun closedPathWrapsAround() {
        val square = listOf(CurveAnchor(0f, 0f), CurveAnchor(100f, 0f), CurveAnchor(100f, 100f), CurveAnchor(0f, 100f))
        val path = CurveGeometry.toPath(square, closed = true, tension = 0f, polyline = false)
        assertEquals(4, path.ops.count { it is PathOp.CubicTo })
        assertTrue(path.ops.last() is PathOp.Close)
        assertEquals(square[0].pos, pointOf(path.ops[path.ops.size - 2]))
        // First anchor is smooth too when closed: its tangent uses the last anchor.
        val (hIn, hOut) = CurveGeometry.handles(square, 0, true, 0f)
        assertEquals(180f, angleBetween(hIn, hOut), 0.1f)
        assertEquals(0f, angleBetween(hOut, square[1].pos - square[3].pos), 0.1f)
        // Symmetric closed Catmull-Rom through a square is a round-ish loop around its center.
        val pts = path.flatten(0.05f).single().points
        val c = Vec2(50f, 50f)
        pts.forEach { assertTrue(it.distanceTo(c) in 49f..75f) }
        // Two anchors cannot be closed.
        assertEquals(1, CurveGeometry.segmentCount(2, closed = true))
    }

    @Test
    fun customTangentOverridesAutomatic() {
        val custom = zigzag.toMutableList().also { it[1] = it[1].copy(handleIn = Vec2(-10f, 0f), handleOut = Vec2(10f, 0f)) }
        val (hIn, hOut) = CurveGeometry.handles(custom, 1, false, 0f)
        assertEquals(Vec2(-10f, 0f), hIn)
        assertEquals(Vec2(10f, 0f), hOut)
        val seg = CurveGeometry.segment(custom, 1, false, 0f, false)
        assertEquals(Vec2(110f, 80f), seg[1])
        assertEquals(zigzag[1].pos, custom[1].withAutoTangent().pos)
        assertTrue(!custom[1].withAutoTangent().hasCustomTangent)
    }

    @Test
    fun evenSampling() {
        val path = CurveGeometry.toPath(zigzag, false, 0f, false)
        val total = VectorPath.length(path.flatten(0.05f).single().points)
        for (spacing in listOf(0.5f, 1f, 7f)) {
            val s = CurveGeometry.sample(path, spacing)
            assertEquals(zigzag.first().pos, s.first())
            assertEquals(zigzag.last().pos, s.last())
            val gaps = s.zipWithNext { a, b -> a.distanceTo(b) }
            // Every gap but the last equals the spacing (chord of a gently curved arc).
            gaps.dropLast(1).forEach { assertEquals(spacing, it, spacing * 0.02f) }
            assertTrue(gaps.last() <= spacing + 2e-3f)
            assertEquals(total / spacing, (s.size - 1).toFloat(), 1.5f)
        }
        // A straight line of 10 px at spacing 1 gives exactly 11 samples.
        val line = VectorPath.polyline(listOf(Vec2(0f, 0f), Vec2(10f, 0f)))
        val ls = CurveGeometry.sample(line, 1f)
        assertEquals(11, ls.size)
        ls.forEachIndexed { i, p -> assertEquals(i.toFloat(), p.x, 1e-4f) }
    }

    @Test
    fun closedSamplingReturnsToStart() {
        val square = listOf(CurveAnchor(0f, 0f), CurveAnchor(100f, 0f), CurveAnchor(100f, 100f), CurveAnchor(0f, 100f))
        val path = CurveGeometry.toPath(square, true, 0f, polyline = true)
        val s = CurveGeometry.sample(path, 1f)
        assertEquals(401, s.size)
        assertEquals(s.first(), s.last())
    }

    @Test
    fun nearestPointOnCurve() {
        val hit = CurveGeometry.nearest(zigzag, Vec2(150f, 60f), false, 0f, false)
        assertNotNull(hit)
        assertEquals(1, hit!!.segment)
        assertTrue(hit.t in 0.2f..0.8f)
        // The reported point lies on the curve.
        val seg = CurveGeometry.segment(zigzag, 1, false, 0f, false)
        val onCurve = VectorPath.cubicPoint(seg[0], seg[1], seg[2], seg[3], hit.t)
        assertTrue(onCurve.distanceTo(hit.point) < 1f)
        // Polyline distance to a straight segment is exact.
        val poly = CurveGeometry.nearest(zigzag, Vec2(50f, 0f), false, 0f, polyline = true)!!
        assertEquals(Geometry.distanceToSegment(Vec2(50f, 0f), zigzag[0].pos, zigzag[1].pos), poly.distance, 1e-3f)
    }

    @Test
    fun taperRamp() {
        assertEquals(0.08f, CurveGeometry.taperPressure(0f, 100f, 20f), 1e-5f)
        assertEquals(1f, CurveGeometry.taperPressure(50f, 100f, 20f), 1e-5f)
        assertEquals(0.08f, CurveGeometry.taperPressure(100f, 100f, 20f), 1e-5f)
        val a = CurveGeometry.taperPressure(5f, 100f, 20f); val b = CurveGeometry.taperPressure(10f, 100f, 20f)
        assertTrue(a < b && b < 1f)
        assertEquals(1f, CurveGeometry.taperPressure(10f, 100f, 0f), 0f)
        // Taper longer than half the stroke is limited so the middle still reaches full pressure.
        assertEquals(1f, CurveGeometry.taperPressure(50f, 100f, 500f), 1e-5f)
        assertTrue(abs(CurveGeometry.taperPressure(25f, 100f, 500f) - CurveGeometry.taperPressure(75f, 100f, 500f)) < 1e-5f)
    }
}
