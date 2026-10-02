package com.brushwork.paint.tools.vector

import com.brushwork.paint.brush.PathStrokeInput
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VSubpath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * v1.5 §4.5 (A4, JVM): per-point thickness. Factors blend with smoothstep along the arc length
 * between neighbouring anchors (exact at the anchors and halfway), for plain lines
 * ([CurveWidths.line]) and brush samples ([CurveWidths.atSamples]), open, closed and polylines;
 * anchors convert to vector path anchors and back without loss.
 */
class CurveWidthsTest {

    private val line3 = listOf(CurveAnchor(0f, 0f, sharp = true), CurveAnchor(100f, 0f, sharp = true, width = 3f), CurveAnchor(200f, 0f, sharp = true))

    @Test
    fun widthAtBlendsWithSmoothstep() {
        assertEquals(1f, CurveGeometry.widthAt(line3, 0, 0f), 0f)
        assertEquals(3f, CurveGeometry.widthAt(line3, 0, 1f), 0f)
        assertEquals(2f, CurveGeometry.widthAt(line3, 0, 0.5f), 1e-6f)
        assertEquals(1f + 2f * 0.25f * 0.25f * (3f - 0.5f), CurveGeometry.widthAt(line3, 0, 0.25f), 1e-6f)
        assertEquals(3f, CurveGeometry.widthAt(line3, 1, 0f), 0f)
        // A closed path's last segment blends back into the first anchor.
        assertEquals(1f, CurveGeometry.widthAt(line3, 2, 1f), 0f)
        assertEquals(1f, CurveGeometry.widthAt(emptyList(), 0, 0.5f), 0f)
        // NaN or negative factors are drawn as 1 and 0.
        val odd = listOf(CurveAnchor(0f, 0f, width = Float.NaN), CurveAnchor(10f, 0f, width = -2f))
        assertEquals(1f, CurveGeometry.widthAt(odd, 0, 0f), 0f)
        assertEquals(0f, CurveGeometry.widthAt(odd, 0, 1f), 0f)
        assertEquals(0f, CurveGeometry.smoothstep(-1f), 0f)
        assertEquals(1f, CurveGeometry.smoothstep(2f), 0f)
        assertTrue(CurveGeometry.isUniformWidth(listOf(CurveAnchor(1f, 1f))))
        assertTrue(!CurveGeometry.isUniformWidth(line3))
    }

    @Test
    fun aPlainLineFollowsTheFactorsAlongTheArc() {
        val l = CurveWidths.line(line3, closed = false, tension = 0f, polyline = true, width = 10f)!!
        assertEquals(10f, l.ws[0], 0f)
        assertEquals(10f, l.ws[l.n - 1], 1e-5f)
        // Points every 2 px or less where the width changes; the width at x is 10 · widthAt.
        for (i in 1 until l.n) assertTrue(hypot(l.xs[i] - l.xs[i - 1], l.ys[i] - l.ys[i - 1]) <= CurveWidths.LINE_STEP + 1e-3f)
        for (i in 0 until l.n) {
            val x = l.xs[i]
            val expected = if (x <= 100f) 10f * CurveGeometry.widthAt(line3, 0, x / 100f) else 10f * CurveGeometry.widthAt(line3, 1, (x - 100f) / 100f)
            assertEquals("x $x", expected, l.ws[i], 1e-3f)
        }
        assertTrue(l.ws.take(l.n).any { abs(it - 30f) < 1e-4f })
        // Uniform: the flattened path only (no extra points on straight pieces).
        val flat = CurveWidths.line(line3.map { it.copy(width = 1f) }, false, 0f, true, 10f)!!
        assertEquals(3, flat.n)
        assertNull(CurveWidths.line(emptyList(), false, 0f, false, 4f))
        assertEquals(1, CurveWidths.line(listOf(CurveAnchor(3f, 4f, width = 2f)), false, 0f, false, 4f)!!.n)
        // Closed: ends on the first point again.
        val loop = listOf(CurveAnchor(0f, 0f), CurveAnchor(100f, 0f, width = 0f), CurveAnchor(100f, 100f), CurveAnchor(0f, 100f, width = 2f))
        val ll = CurveWidths.line(loop, closed = true, tension = 0.2f, polyline = false, width = 8f)!!
        assertEquals(0f, ll.xs[ll.n - 1], 1e-4f)
        assertEquals(0f, ll.ys[ll.n - 1], 1e-4f)
        assertEquals(8f, ll.ws[ll.n - 1], 1e-4f)
        assertTrue(ll.ws.take(ll.n).any { it < 0.01f })
    }

    @Test
    fun brushSamplesGetTheFactorAtTheirArcPosition() {
        val path = CurveGeometry.toPath(line3, false, 0f, polyline = true)
        val input = brushStrokeInput(path, 0f, PathStrokeInput())
        val n = input.size
        val w = CurveWidths.atSamples(line3, closed = false, tension = 0f, polyline = true, count = n)
        assertEquals(n, w.size)
        assertEquals(1f, w[0], 0f)
        assertEquals(1f, w[n - 1], 1e-5f)
        for (i in 0 until n) {
            val x = input.x[i]
            val expected = if (x <= 100f) CurveGeometry.widthAt(line3, 0, x / 100f) else CurveGeometry.widthAt(line3, 1, (x - 100f) / 100f)
            assertEquals("sample $i at $x", expected, w[i], 2e-3f)
        }
        // The sample points stay exactly where they were; the pressures scale by w / max.
        val xs = input.x.copyOf(n); val ps = input.pressure.copyOf(n)
        val profiled = brushStrokeInput(path, 0f, PathStrokeInput(), WidthProfile(w))
        val wMax = profileMax(WidthProfile(w))
        for (i in 0 until n) {
            assertEquals(xs[i].toRawBits(), profiled.x[i].toRawBits())
            assertEquals(ps[i] * w[i] / wMax, profiled.pressure[i], 1e-6f)
        }
        // A curve: factors at the anchors' arc positions.
        val curve = listOf(CurveAnchor(20f, 200f, width = 0.5f), CurveAnchor(120f, 40f, width = 2.5f), CurveAnchor(260f, 180f, width = 1f))
        val cp = CurveGeometry.toPath(curve, false, 0.1f, false)
        val ci = brushStrokeInput(cp, 0.2f, PathStrokeInput())
        val cw = CurveWidths.atSamples(curve, false, 0.1f, false, ci.size)
        // The sample nearest the middle anchor carries (almost) its factor.
        var best = 0
        for (i in 0 until ci.size) if (hypot(ci.x[i] - 120f, ci.y[i] - 40f) < hypot(ci.x[best] - 120f, ci.y[best] - 40f)) best = i
        assertEquals(2.5f, cw[best], 0.01f)
        assertEquals(0.5f, cw[0], 0f)
        // Closed paths go back to the first anchor's factor; empty / single anchors give 1.
        val loop = curve + CurveAnchor(40f, 260f, width = 3f)
        val lp = CurveGeometry.toPath(loop, true, 0f, false)
        val li = brushStrokeInput(lp, 0f, PathStrokeInput())
        val lw = CurveWidths.atSamples(loop, true, 0f, false, li.size)
        assertEquals(0.5f, lw[li.size - 1], 1e-4f)
        assertTrue(lw.max() > 2.9f)
        assertEquals(1f, CurveWidths.atSamples(listOf(CurveAnchor(1f, 1f)), false, 0f, false, 3)[1], 0f)
    }

    @Test
    fun anchorsConvertToVectorAnchorsAndBack() {
        val anchors = listOf(
            CurveAnchor(10f, 20f),
            CurveAnchor(50f, 60f, sharp = true, handleIn = Vec2(-5f, 3f), handleOut = null, width = 2.5f),
            CurveAnchor(90f, 20f, handleIn = Vec2(-10f, 0f), handleOut = Vec2(10f, 0f), width = 0f),
        )
        val back = anchors.map { it.toVAnchor() }.map { it.toCurveAnchor() }
        assertEquals(anchors, back)
        assertEquals(VAnchor(50f, 60f, true, -5f, 3f, null, null, 2.5f), anchors[1].toVAnchor())
        // A handle needs both coordinates; widths out of range are clamped.
        assertNull(VAnchor(0f, 0f, inX = 1f).toCurveAnchor().handleIn)
        assertEquals(3f, VAnchor(0f, 0f, width = 9f).toCurveAnchor().width, 0f)
        assertEquals(1f, VAnchor(0f, 0f, width = Float.NaN).toCurveAnchor().width, 0f)
        // An SVG-style cubic (sharp anchors with explicit handles) gives the same path back.
        val svg = VPath(1, subpaths = listOf(VSubpath(listOf(
            VAnchor(10f, 10f, true, outX = 40f, outY = -30f),
            VAnchor(120f, 80f, true, inX = -20f, inY = -50f, outX = 15f, outY = 40f),
            VAnchor(200f, 30f, true, inX = 0f, inY = 30f),
        ))))
        val reopened = svg.subpaths[0].anchors.map { it.toCurveAnchor() }
        val again = CurveGeometry.toPath(reopened, false, 0f, false).ops
        val original = com.brushwork.paint.vector.VectorOps.toVectorPath(svg).ops
        assertEquals(original.size, again.size)
        for ((a, b) in original.zip(again)) assertOpsClose(a, b)
    }

    private fun assertOpsClose(a: PathOp, b: PathOp) {
        fun pts(o: PathOp): List<Vec2> = when (o) {
            is PathOp.MoveTo -> listOf(o.p)
            is PathOp.LineTo -> listOf(o.p)
            is PathOp.CubicTo -> listOf(o.c1, o.c2, o.p)
            PathOp.Close -> emptyList()
        }
        assertEquals(a::class, b::class)
        for ((p, q) in pts(a).zip(pts(b))) assertTrue("$p vs $q", p.distanceTo(q) < 1e-3f)
    }
}
