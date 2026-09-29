package com.brushwork.paint.assist

import com.brushwork.paint.core.Geometry
import com.brushwork.paint.model.RulerSettings
import com.brushwork.paint.model.RulerSnap
import com.brushwork.paint.model.RulerType
import com.brushwork.paint.model.StabilizerMode
import com.brushwork.paint.tools.ToolPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Ruler snapping through the real [StrokePipeline]: every output must lie on the chosen curve. */
class RulerSnappingTest {
    private val center = 500f
    private val snap = 40f

    private fun ruler(type: RulerType, snapMode: RulerSnap = RulerSnap.PARALLEL, angle: Float = 0f) = RulerSettings(
        enabled = true, type = type, centerX = center, centerY = center, angleDeg = angle,
        radius = 300f, radiusX = 400f, radiusY = 200f, snap = snapMode,
    )

    /** A wobbly freehand path starting exactly at (sx, sy). */
    private fun wobble(sx: Float, sy: Float, n: Int = 120): List<ToolPoint> = (0 until n).map { i ->
        val t = i.toFloat()
        ToolPoint(sx + t * 3f + 9f * sin(t * 0.37f), sy + t * 1.7f + 11f * (cos(t * 0.23f) - 1f), pressure = 0.3f + (i % 7) / 10f, time = i * 8L)
    }

    /** Runs a stroke and returns every point the tool would receive (down, moves, up). */
    private fun run(r: RulerSettings, path: List<ToolPoint>, mode: StabilizerMode = StabilizerMode.OFF): List<ToolPoint> {
        val p = StrokePipeline()
        val params = StrokePipeline.Params(ruler = r, mode = mode, snapDistance = snap, ropeLength = 25f, smoothLag = 30f, step = 2f, minRadialDistance = 4f)
        val out = ArrayList<ToolPoint>()
        out += p.down(path.first(), params)
        for (q in path.subList(1, path.size - 1)) out += p.move(q)
        out += p.up(path.last())
        return out
    }

    private fun distToLine(p: ToolPoint, ox: Float, oy: Float, deg: Float): Float {
        val a = deg * Geometry.DEG
        return abs((p.x - ox) * sin(a) - (p.y - oy) * cos(a))
    }

    @Test
    fun straightParallelFollowsLineThroughStart() {
        val r = ruler(RulerType.STRAIGHT, angle = 30f)
        val path = wobble(100f, 900f)
        val out = run(r, path)
        val s = path.first()
        assertTrue(distToLine(s, center, center, 30f) > snap)
        for (p in out) assertEquals(0f, distToLine(p, s.x, s.y, 30f), 1e-2f)
        // Parallel, not the ruler itself.
        assertEquals(distToLine(s, center, center, 30f), distToLine(out.last(), center, center, 30f), 1e-2f)
    }

    @Test
    fun straightStartNearRulerSnapsOntoRuler() {
        val r = ruler(RulerType.STRAIGHT, angle = -60f)
        val a = -60f * Geometry.DEG
        // 25 px off the ruler line (inside the 40 px snap distance).
        val sx = center + 200f * cos(a) - 25f * sin(a)
        val sy = center + 200f * sin(a) + 25f * cos(a)
        val out = run(r, wobble(sx, sy))
        for (p in out) assertEquals(0f, distToLine(p, center, center, -60f), 1e-2f)
    }

    @Test
    fun straightOnRulerModeAlwaysSnapsOntoRuler() {
        val r = ruler(RulerType.STRAIGHT, RulerSnap.ON_RULER, angle = 90f)
        val out = run(r, wobble(50f, 60f))
        for (p in out) assertEquals(0f, distToLine(p, center, center, 90f), 1e-2f)
        for (p in out) assertEquals(center, p.x, 1e-2f)
    }

    @Test
    fun circleParallelIsConcentricThroughStart() {
        val r = ruler(RulerType.CIRCLE)
        val path = wobble(center + 200f, center)
        for (mode in StabilizerMode.entries) {
            val out = run(r, path, mode)
            for (p in out) assertEquals("mode $mode", 200f, hypot(p.x - center, p.y - center), 0.05f)
        }
    }

    @Test
    fun circleStartNearRulerOrOnRulerModeUsesRulerRadius() {
        val near = run(ruler(RulerType.CIRCLE), wobble(center, center + 325f))
        for (p in near) assertEquals(300f, hypot(p.x - center, p.y - center), 0.05f)
        val forced = run(ruler(RulerType.CIRCLE, RulerSnap.ON_RULER), wobble(center + 20f, center + 90f))
        for (p in forced) assertEquals(300f, hypot(p.x - center, p.y - center), 0.05f)
    }

    @Test
    fun circleOutputIsSubdividedAlongTheArc() {
        // Sparse input far apart: output must still hug the arc (no long chords).
        val r = ruler(RulerType.CIRCLE)
        val path = (0..8).map { i -> val a = i * 0.5f; ToolPoint(center + 250f * cos(a), center + 250f * sin(a)) }
        val out = run(r, path)
        for (i in 1 until out.size) {
            val d = hypot(out[i].x - out[i - 1].x, out[i].y - out[i - 1].y)
            assertTrue("gap $d", d <= 2.5f)
        }
    }

    private fun ellipseValue(p: ToolPoint, rx: Float, ry: Float, deg: Float): Float {
        val a = deg * Geometry.DEG
        val vx = p.x - center; val vy = p.y - center
        val lx = vx * cos(a) + vy * sin(a)
        val ly = -vx * sin(a) + vy * cos(a)
        return sqrt((lx / rx) * (lx / rx) + (ly / ry) * (ly / ry))
    }

    @Test
    fun ellipseParallelIsScaledEllipseThroughStart() {
        val r = ruler(RulerType.ELLIPSE, angle = 25f)
        val path = wobble(center + 120f, center + 40f)
        val k = RulerSnapping.ellipseScale(r, path.first().x, path.first().y)
        assertTrue(k < 0.9f)
        for (mode in StabilizerMode.entries) {
            val out = run(r, path, mode)
            assertEquals(1f, ellipseValue(out.first(), 400f * k, 200f * k, 25f), 1e-3f)
            for (p in out) assertEquals("mode $mode", 1f, ellipseValue(p, 400f * k, 200f * k, 25f), 2e-3f)
        }
    }

    @Test
    fun ellipseNearRulerOrOnRulerModeUsesRuler() {
        val r = ruler(RulerType.ELLIPSE, angle = 0f)
        val near = run(r, wobble(center + 410f, center))
        for (p in near) assertEquals(1f, ellipseValue(p, 400f, 200f, 0f), 2e-3f)
        val forced = run(ruler(RulerType.ELLIPSE, RulerSnap.ON_RULER), wobble(center + 30f, center + 30f))
        for (p in forced) assertEquals(1f, ellipseValue(p, 400f, 200f, 0f), 2e-3f)
    }

    @Test
    fun radialFollowsLineThroughCenterAndStart() {
        val r = ruler(RulerType.RADIAL, angle = 10f)
        val path = wobble(center + 150f, center - 80f)
        val s = path.first()
        val dirDeg = Math.toDegrees(kotlin.math.atan2((s.y - center).toDouble(), (s.x - center).toDouble())).toFloat()
        for (mode in StabilizerMode.entries) {
            val out = run(r, path, mode)
            for (p in out) assertEquals("mode $mode", 0f, distToLine(p, center, center, dirDeg), 1e-2f)
        }
    }

    @Test
    fun radialStartingOnCenterTakesDirectionFromFirstDistantPoint() {
        val r = ruler(RulerType.RADIAL)
        val p = StrokePipeline()
        val params = StrokePipeline.Params(ruler = r, snapDistance = snap, minRadialDistance = 4f)
        val first = p.down(ToolPoint(center + 0.5f, center), params)
        assertEquals(center + 0.5f, first.x, 1e-4f)
        assertTrue(p.move(ToolPoint(center + 1f, center + 1f)).isEmpty()) // direction still unknown
        p.move(ToolPoint(center, center + 10f)) // straight down: defines the direction
        val out = p.move(ToolPoint(center + 30f, center + 100f))
        for (q in out) assertEquals(center, q.x, 1e-3f)
        assertEquals(center + 100f, out.last().y, 1e-3f)
    }

    @Test
    fun degenerateCircleThroughCenterIsFree() {
        val r = ruler(RulerType.CIRCLE)
        assertNull(RulerSnapping.constraintFor(r, center, center, snap))
        val out = run(r, wobble(center, center))
        val path = wobble(center, center)
        assertEquals(path.last().x, out.last().x, 1e-3f)
        assertEquals(path.last().y, out.last().y, 1e-3f)
    }

    @Test
    fun noRulerMeansNoConstraint() {
        val p = StrokePipeline()
        p.down(ToolPoint(1f, 2f), StrokePipeline.Params())
        assertNull(p.constraint)
        val out = p.move(ToolPoint(10f, 20f, pressure = 0.25f, time = 99L))
        assertEquals(listOf(ToolPoint(10f, 20f, pressure = 0.25f, time = 99L)), out)
        assertNotNull(p.up(ToolPoint(10f, 20f)).lastOrNull())
    }
}
