package com.brushwork.paint.tools.vector.gen

import com.brushwork.paint.tools.vector.CurveKind
import com.brushwork.paint.tools.vector.spline.NurbsGeometry
import com.brushwork.paint.vector.CurveGenKind
import com.brushwork.paint.vector.CurveGenerator
import com.brushwork.paint.vector.VSubpath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * v1.8 (item 6, design §3.7c): the geometry of every generated curve.
 * - Curve (Bézier): an adaptive Hermite fit within 0.25 px of the analytic curve (checked at 32
 *   samples per span, four times the fit's own check), smooth anchors with both handles, at most
 *   400 anchors (the cap holds where the tolerance cannot).
 * - The Fermat spiral passes through its centre; the rose is closed and ends where it started.
 * - Path (NURBS): the spline interpolates its samples EXACTLY (the Path tool's own evaluator at
 *   the knots), order 4 with endpoint on, cyclic for the rose, at most 400 samples.
 */
class CurveGeneratorsTest {
    /** A spread of every kind, mid-size, both directions, the edges of the ranges. */
    private val cases: List<CurveGenerator> = buildList {
        for (cw in listOf(false, true)) {
            add(CurveGenerator(CurveGenKind.ARCHIMEDEAN, cx = 250f, cy = 240f, size = 180f, angleDeg = 20f, turns = 3f, clockwise = cw))
            add(CurveGenerator(CurveGenKind.ARCHIMEDEAN, cx = 250f, cy = 240f, size = 180f, turns = 7.5f, inner = 0.4f, clockwise = cw))
            add(CurveGenerator(CurveGenKind.LOGARITHMIC, cx = 200f, cy = 200f, size = 150f, angleDeg = -40f, turns = 4f, growth = 2f, clockwise = cw))
            add(CurveGenerator(CurveGenKind.LOGARITHMIC, cx = 200f, cy = 200f, size = 300f, turns = 2f, growth = 10f, clockwise = cw))
            add(CurveGenerator(CurveGenKind.GOLDEN, cx = 256f, cy = 256f, size = 200f, angleDeg = 90f, turns = 3f, clockwise = cw))
            add(CurveGenerator(CurveGenKind.FERMAT, cx = 256f, cy = 256f, size = 160f, turns = 3f, clockwise = cw))
            add(CurveGenerator(CurveGenKind.SINE, cx = 256f, cy = 128f, size = 400f, angleDeg = 10f, amplitude = 40f, waves = 3f, clockwise = cw))
            add(CurveGenerator(CurveGenKind.SINE, cx = 256f, cy = 128f, size = 300f, amplitude = 80f, waves = 0.5f, clockwise = cw))
            add(CurveGenerator(CurveGenKind.ROSE, cx = 256f, cy = 256f, size = 160f, angleDeg = 30f, petalsN = 5, petalsD = 1, clockwise = cw))
            add(CurveGenerator(CurveGenKind.ROSE, cx = 256f, cy = 256f, size = 160f, petalsN = 3, petalsD = 4, clockwise = cw))
        }
    }

    /** The parametric distance of [sub]'s cubics from the curve at [perSpan] samples of each span. */
    private fun worstError(g: CurveGenerator, sub: VSubpath, perSpan: Int): Double {
        val a = CurveGenerators.Analytic(g.sanitized()!!)
        val ts = CurveGenerators.parameters(a, sub, 0.01) ?: throw AssertionError("$g: the fit is not a Hermite fit of its curve")
        val n = sub.anchors.size
        val spans = if (sub.closed) n else n - 1
        val v = DoubleArray(4)
        var worst = 0.0
        for (s in 0 until spans) {
            val p = sub.anchors[s]
            val q = sub.anchors[(s + 1) % n]
            val t0 = ts[s]
            val t1 = if (s + 1 < n) ts[s + 1] else a.end
            val c1x = p.x + p.outX!!; val c1y = p.y + p.outY!!
            val c2x = q.x + q.inX!!; val c2y = q.y + q.inY!!
            for (j in 0..perSpan) {
                val u = j.toDouble() / perSpan
                val w = 1 - u
                val bx = w * w * w * p.x + 3 * w * w * u * c1x + 3 * w * u * u * c2x + u * u * u * q.x
                val by = w * w * w * p.y + 3 * w * w * u * c1y + 3 * w * u * u * c2y + u * u * u * q.y
                a.eval(t0 + (t1 - t0) * u, v)
                worst = maxOf(worst, hypot(bx - v[0], by - v[1]))
            }
        }
        return worst
    }

    @Test
    fun everyKindFitsWithinAQuarterPixel() {
        for (g in cases) {
            val b = CurveGenerators.build(g, CurveKind.CURVE)
            assertNull(b.spline)
            val sub = b.subpath
            assertTrue("$g: at most 400 anchors (${sub.anchors.size})", sub.anchors.size in 2..CurveGenerators.MAX_ANCHORS)
            assertTrue("$g: smooth anchors with both handles", sub.anchors.all { !it.sharp && it.inX != null && it.outX != null })
            val err = worstError(g, sub, 32)
            assertTrue("$g: ${sub.anchors.size} anchors, error $err px", err <= CurveGenerators.TOLERANCE_PX)
        }
    }

    @Test
    fun theFitStartsAndEndsOnTheCurve() {
        val v = DoubleArray(4)
        for (g in cases) {
            val a = CurveGenerators.Analytic(g)
            val sub = CurveGenerators.build(g, CurveKind.CURVE).subpath
            a.eval(a.start, v)
            assertEquals("$g: the start", 0.0, hypot(sub.anchors.first().x - v[0], sub.anchors.first().y - v[1]), 1e-3)
            if (!a.closed) {
                a.eval(a.end, v)
                assertEquals("$g: the end", 0.0, hypot(sub.anchors.last().x - v[0], sub.anchors.last().y - v[1]), 1e-3)
            }
        }
    }

    @Test
    fun spiralsEndAtTheSizeHandle() {
        for (g in cases.filter { it.kind != CurveGenKind.SINE && it.kind != CurveGenKind.ROSE }) {
            val sub = CurveGenerators.build(g, CurveKind.CURVE).subpath
            val h = CurveGenerators.sizeHandle(g)
            assertEquals("$g: the outer end is the size handle", 0.0, hypot((sub.anchors.last().x - h.x).toDouble(), (sub.anchors.last().y - h.y).toDouble()), 1e-3)
        }
    }

    @Test
    fun theAnchorCapHoldsWhereTheToleranceCannot() {
        val huge = listOf(
            CurveGenerator(CurveGenKind.ARCHIMEDEAN, cx = 0f, cy = 0f, size = 50_000f, turns = CurveGenerator.MAX_TURNS),
            CurveGenerator(CurveGenKind.FERMAT, cx = 0f, cy = 0f, size = 50_000f, turns = CurveGenerator.MAX_TURNS),
            CurveGenerator(CurveGenKind.SINE, cx = 0f, cy = 0f, size = 90_000f, amplitude = 20_000f, waves = 50f),
            CurveGenerator(CurveGenKind.ARCHIMEDEAN, cx = 0f, cy = 0f, size = 100f, turns = CurveGenerator.MAX_TURNS, density = CurveGenerator.MAX_DENSITY),
            CurveGenerator(CurveGenKind.ROSE, cx = 0f, cy = 0f, size = 50_000f, petalsN = 24, petalsD = 11),
        )
        for (g in huge) {
            val sub = CurveGenerators.build(g, CurveKind.CURVE).subpath
            assertEquals("$g: capped at 400 anchors", CurveGenerators.MAX_ANCHORS, sub.anchors.size)
            assertTrue("$g: still a Hermite fit", CurveGenerators.matchesHermite(CurveGenerators.Analytic(g), sub, 0.05))
            val s = CurveGenerators.build(g, CurveKind.PATH).spline!!
            assertTrue("$g: at most 400 samples (${s.points.size} points)", s.points.size <= CurveGenerators.MAX_ANCHORS + 2)
        }
    }

    @Test
    fun theFermatSpiralPassesThroughItsCentre() {
        for (cw in listOf(false, true)) {
            val g = CurveGenerator(CurveGenKind.FERMAT, cx = 200f, cy = 150f, size = 120f, angleDeg = 33f, turns = 2.5f, clockwise = cw)
            val sub = CurveGenerators.build(g, CurveKind.CURVE).subpath
            var nearest = Double.MAX_VALUE
            for (s in 0 until sub.anchors.size - 1) {
                val p = sub.anchors[s]; val q = sub.anchors[s + 1]
                for (j in 0..64) {
                    val u = j / 64.0; val w = 1 - u
                    val bx = w * w * w * p.x + 3 * w * w * u * (p.x + p.outX!!) + 3 * w * u * u * (q.x + q.inX!!) + u * u * u * q.x
                    val by = w * w * w * p.y + 3 * w * w * u * (p.y + p.outY!!) + 3 * w * u * u * (q.y + q.inY!!) + u * u * u * q.y
                    nearest = minOf(nearest, hypot(bx - 200.0, by - 150.0))
                }
            }
            assertTrue("through the centre ($nearest px)", nearest <= CurveGenerators.TOLERANCE_PX)
            // Both arms: the two ends are opposite, at the size from the centre.
            val first = sub.anchors.first(); val last = sub.anchors.last()
            assertEquals(120.0, hypot(first.x - 200.0, first.y - 150.0), 1e-2)
            assertEquals(120.0, hypot(last.x - 200.0, last.y - 150.0), 1e-2)
            assertEquals("opposite ends", 240.0, hypot((first.x - last.x).toDouble(), (first.y - last.y).toDouble()), 1e-2)
        }
    }

    @Test
    fun everyRoseIsClosedAndEndsWhereItStarted() {
        val v0 = DoubleArray(4)
        val v1 = DoubleArray(4)
        for (n in 1..7) for (d in 1..5) {
            val g = CurveGenerator(CurveGenKind.ROSE, cx = 256f, cy = 256f, size = 150f, angleDeg = 12f, petalsN = n, petalsD = d)
            val a = CurveGenerators.Analytic(g)
            a.eval(a.start, v0)
            a.eval(a.end, v1)
            assertEquals("$n/$d: the period ends where it started", 0.0, hypot(v1[0] - v0[0], v1[1] - v0[1]), 1e-6)
            assertEquals("$n/$d: and in the same direction", 0.0, hypot(v1[2] - v0[2], v1[3] - v0[3]), 1e-6)
            val sub = CurveGenerators.build(g, CurveKind.CURVE).subpath
            assertTrue("$n/$d: a closed subpath", sub.closed)
            assertTrue("$n/$d: within the tolerance", worstError(g, sub, 16) <= CurveGenerators.TOLERANCE_PX)
            val s = CurveGenerators.build(g, CurveKind.PATH).spline!!
            assertTrue("$n/$d: a cyclic spline", s.cyclic && NurbsGeometry.isClosed(s))
        }
    }

    @Test
    fun thePathSplineIsExactAtItsSamples() {
        val v = DoubleArray(4)
        for (g in cases + listOf(CurveGenerator(CurveGenKind.ARCHIMEDEAN, cx = 9f, cy = 9f, size = 5f, turns = 0.25f, density = 1))) {
            val a = CurveGenerators.Analytic(g)
            val b = CurveGenerators.build(g, CurveKind.PATH)
            val s = assertNotNullAnd(b.spline, "$g: a spline")
            assertEquals(4, s.order)
            assertEquals("$g: cyclic only for the rose", a.closed, s.cyclic)
            assertTrue(s.endpoint)
            val m = if (a.closed) s.points.size else s.points.size - 2
            val first = NurbsGeometry.domain(s).start
            val h = (a.end - a.start) / (if (a.closed) m else m - 1)
            var worst = 0.0
            for (i in 0 until m) {
                a.eval(a.start + i * h, v)
                val p = NurbsGeometry.pointAt(s, first + i)
                worst = maxOf(worst, hypot(p[0] - v[0], p[1] - v[1]))
            }
            assertTrue("$g: the samples are on the spline ($worst px)", worst <= 2e-3)
            assertFalse("$g: the Bézier form has anchors", b.subpath.anchors.isEmpty())
        }
    }

    @Test
    fun pointsPerTurnSetsTheSampleCount() {
        val g = CurveGenerator(CurveGenKind.ARCHIMEDEAN, cx = 100f, cy = 100f, size = 80f, turns = 3f)
        assertEquals("the default: 8 per turn, + 1, + the two tangent points", 3 * 8 + 1 + 2, CurveGenerators.build(g, CurveKind.PATH).spline!!.points.size)
        assertEquals(3 * 12 + 1 + 2, CurveGenerators.build(g.copy(density = 12), CurveKind.PATH).spline!!.points.size)
        val rose = CurveGenerator(CurveGenKind.ROSE, cx = 100f, cy = 100f, size = 80f, petalsN = 2, petalsD = 1)
        assertEquals("4 petals × 8", 32, CurveGenerators.build(rose, CurveKind.PATH).spline!!.points.size)
        // The Bézier fit starts from Points per turn and refines from there.
        assertTrue(CurveGenerators.build(g.copy(density = 40), CurveKind.CURVE).subpath.anchors.size >= 3 * 40 + 1)
    }

    @Test
    fun thePolylineBuildIsSharpAndHasNoSpline() {
        for (g in cases) {
            val b = CurveGenerators.build(g, CurveKind.POLYLINE)
            assertNull(b.spline)
            assertTrue(b.subpath.anchors.size >= 2 && b.subpath.anchors.all { it.sharp && it.outX == null })
        }
    }

    @Test
    fun theSizeHandleScalesAndTurns() {
        val g = CurveGenerator(CurveGenKind.SINE, cx = 100f, cy = 100f, size = 200f, amplitude = 30f, angleDeg = 0f)
        assertEquals(200f, CurveGenerators.sizeHandle(g).x, 1e-3f)
        val out = CurveGenerators.withSizeHandle(g, com.brushwork.paint.core.Vec2(100f, 300f))
        assertEquals("the length doubles", 400f, out.size, 1e-2f)
        assertEquals("the height with it", 60f, out.amplitude, 1e-2f)
        assertEquals("turned to the handle", 90f, out.angleDeg, 1e-3f)
        assertEquals("on the centre: unchanged", g, CurveGenerators.withSizeHandle(g, com.brushwork.paint.core.Vec2(100f, 100f)))
    }

    private fun <T : Any> assertNotNullAnd(v: T?, what: String): T {
        assertNotNull(what, v)
        return v!!
    }
}
