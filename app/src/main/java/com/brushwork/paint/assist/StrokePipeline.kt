package com.brushwork.paint.assist

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.RulerSettings
import com.brushwork.paint.model.RulerType
import com.brushwork.paint.model.StabilizerMode
import com.brushwork.paint.tools.ToolPoint
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.hypot

/**
 * Pure (Android-free) core of [StrokeAssist]. Every raw point goes through:
 * ruler constraint -> stabilizer -> re-projection onto the constraint, and output on curved
 * rulers (and rope segments) is subdivided along the curve so the painted stroke never shows
 * chords. All distances are DOCUMENT pixels. Pressure/time of emitted points come from the
 * latest raw point (interpolated across subdivisions from the previously emitted point).
 */
class StrokePipeline {

    /** Per-stroke parameters, already converted to document px by the caller. */
    data class Params(
        /** The enabled ruler, or null when strokes are free. */
        val ruler: RulerSettings? = null,
        val mode: StabilizerMode = StabilizerMode.OFF,
        /** When lifting, finish the stroke at the finger instead of where the brush lags. */
        val catchUp: Boolean = true,
        /** A PARALLEL-mode stroke starting this close to the ruler snaps onto the ruler itself. */
        val snapDistance: Float = 40f,
        /** ROPE: length of the string between finger and brush. */
        val ropeLength: Float = 60f,
        /** SMOOTH: steady-state lag of the brush behind the finger (0 = no smoothing). */
        val smoothLag: Float = 0f,
        /** Spacing of subdivided output points. */
        val step: Float = 2f,
        /** RADIAL: how far from the center a point must be to define the stroke direction. */
        val minRadialDistance: Float = 4f,
    )

    private var params = Params()
    private var mode = StabilizerMode.OFF

    /** The constraint of the current stroke (null = free). */
    var constraint: StrokeConstraint? = null
        private set
    private var radialPending = false

    /** True between [down] and [up]/[cancel]. */
    var isActive = false
        private set

    private var startX = 0f; private var startY = 0f
    // Latest constrained finger position.
    private var fx = 0f; private var fy = 0f
    // Rope: the brush end of the string.
    private var bx = 0f; private var by = 0f
    // Smooth: two cascaded low-pass stages and the previous constrained raw point.
    private var s1x = 0f; private var s1y = 0f
    private var s2x = 0f; private var s2y = 0f
    private var prevX = 0f; private var prevY = 0f
    // Last emitted point.
    private var lx = 0f; private var ly = 0f
    private var lp = 1f; private var lt = 0L

    /** The stabilizer actually in effect for the current stroke. */
    val activeMode: StabilizerMode get() = if (isActive) mode else StabilizerMode.OFF

    /** Brush end of the rope (valid while [activeMode] is ROPE). */
    val brushX: Float get() = bx
    val brushY: Float get() = by

    /** Latest constrained finger position. */
    val fingerX: Float get() = fx
    val fingerY: Float get() = fy

    /** Rope length of the current stroke (document px). */
    val ropeLength: Float get() = params.ropeLength

    /** Starts a stroke; returns the (possibly snapped) first point. */
    fun down(p: ToolPoint, params: Params): ToolPoint {
        this.params = params
        isActive = true
        radialPending = false
        val r = params.ruler
        constraint = if (r != null) RulerSnapping.constraintFor(r, p.x, p.y, params.snapDistance, params.minRadialDistance) else null
        if (r != null && constraint == null && r.type == RulerType.RADIAL) radialPending = true
        mode = when (params.mode) {
            StabilizerMode.OFF -> StabilizerMode.OFF
            StabilizerMode.SMOOTH -> if (params.smoothLag > MIN_EFFECT) StabilizerMode.SMOOTH else StabilizerMode.OFF
            StabilizerMode.ROPE -> if (params.ropeLength > MIN_EFFECT) StabilizerMode.ROPE else StabilizerMode.OFF
        }
        val q = constraint?.project(p.x, p.y) ?: Vec2(p.x, p.y)
        startX = q.x; startY = q.y
        fx = q.x; fy = q.y
        bx = q.x; by = q.y
        s1x = q.x; s1y = q.y; s2x = q.x; s2y = q.y
        prevX = q.x; prevY = q.y
        lx = q.x; ly = q.y; lp = p.pressure; lt = p.time
        return p.copy(x = q.x, y = q.y)
    }

    /** Processes a move; returns zero or more points for the tool. */
    fun move(p: ToolPoint): List<ToolPoint> {
        if (!isActive) return listOf(p)
        val out = ArrayList<ToolPoint>(4)
        advance(p, out)
        return out
    }

    /** Ends the stroke; returns the remaining points, the LAST one being the up point (never empty). */
    fun up(p: ToolPoint): List<ToolPoint> {
        if (!isActive) return listOf(p)
        val out = ArrayList<ToolPoint>(8)
        advance(p, out)
        if (params.catchUp && mode != StabilizerMode.OFF) emitTo(fx, fy, p.pressure, p.time, p, out, subdivide = true)
        if (out.isEmpty()) out += p.copy(x = lx, y = ly, pressure = p.pressure, time = p.time)
        isActive = false
        radialPending = false
        return out
    }

    /** Abandons the stroke. */
    fun cancel() {
        isActive = false
        radialPending = false
        constraint = null
    }

    private fun advance(p: ToolPoint, out: MutableList<ToolPoint>) {
        val q = constrain(p)
        fx = q.x; fy = q.y
        when (mode) {
            StabilizerMode.OFF -> emitTo(fx, fy, p.pressure, p.time, p, out, subdivide = false)
            StabilizerMode.ROPE -> pullRope(p, out)
            StabilizerMode.SMOOTH -> smooth(p, out)
        }
    }

    private fun constrain(p: ToolPoint): Vec2 {
        if (radialPending) {
            val r = params.ruler ?: return Vec2(p.x, p.y)
            val line = RulerSnapping.radialThrough(r.centerX, r.centerY, p.x, p.y, params.minRadialDistance)
                ?: return Vec2(startX, startY) // direction still unknown: hold the brush at the start
            constraint = line
            radialPending = false
        }
        return constraint?.project(p.x, p.y) ?: Vec2(p.x, p.y)
    }

    /** Blender-style lazy mouse: the brush only moves when the string is taut. */
    private fun pullRope(p: ToolPoint, out: MutableList<ToolPoint>) {
        val dx = fx - bx; val dy = fy - by
        val d = hypot(dx, dy)
        val len = params.ropeLength
        if (d <= len) return
        var nx = fx - dx / d * len
        var ny = fy - dy / d * len
        constraint?.let { val q = it.project(nx, ny); nx = q.x; ny = q.y }
        bx = nx; by = ny
        emitTo(nx, ny, p.pressure, p.time, p, out, subdivide = true)
    }

    /**
     * Two cascaded exponential low-pass filters driven by the distance the finger travels
     * (not by time, so the feel doesn't depend on the input event rate). Each stage lags by
     * half of [Params.smoothLag]; the raw segment is sub-stepped so fast strokes stay smooth.
     */
    private fun smooth(p: ToolPoint, out: MutableList<ToolPoint>) {
        val tau = params.smoothLag / 2f
        val sx = prevX; val sy = prevY
        val len = hypot(fx - sx, fy - sy)
        prevX = fx; prevY = fy
        if (len < 1e-4f) return
        val steps = ceil(len / (tau / 4f)).toInt().coerceIn(1, MAX_SMOOTH_STEPS)
        val a = 1f - exp(-(len / steps) / tau)
        val p0 = lp; val t0 = lt
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            val px = sx + (fx - sx) * t
            val py = sy + (fy - sy) * t
            s1x += (px - s1x) * a; s1y += (py - s1y) * a
            s2x += (s1x - s2x) * a; s2y += (s1y - s2y) * a
            if (i == steps || hypot(s2x - lx, s2y - ly) >= params.step) {
                val q = constraint?.project(s2x, s2y) ?: Vec2(s2x, s2y)
                val pressure = p0 + (p.pressure - p0) * t
                val time = t0 + ((p.time - t0) * t.toDouble()).toLong()
                emitTo(q.x, q.y, pressure, time, p, out, subdivide = false)
            }
        }
    }

    /**
     * Emits points from the last emitted point to (tx, ty) — which must lie on the constraint —
     * subdivided along the constraint when it is curved or when [subdivide] is set.
     */
    private fun emitTo(tx: Float, ty: Float, pressure: Float, time: Long, template: ToolPoint, out: MutableList<ToolPoint>, subdivide: Boolean) {
        val c = constraint
        val a = Vec2(lx, ly)
        val b = Vec2(tx, ty)
        val len = c?.pathLength(a, b) ?: a.distanceTo(b)
        if (len < EPS) return
        val step = params.step.coerceAtLeast(0.25f)
        val n = if (subdivide || c?.isCurved == true) ceil(len / step).toInt().coerceIn(1, MAX_SUBDIVISIONS) else 1
        val p0 = lp; val t0 = lt
        for (i in 1..n) {
            val t = i.toFloat() / n
            val q = when {
                i == n -> b
                c != null -> c.between(a, b, t)
                else -> a.lerp(b, t)
            }
            out += template.copy(
                x = q.x,
                y = q.y,
                pressure = p0 + (pressure - p0) * t,
                time = t0 + ((time - t0) * t.toDouble()).toLong(),
            )
        }
        lx = tx; ly = ty; lp = pressure; lt = time
    }

    companion object {
        private const val EPS = 1e-3f
        private const val MIN_EFFECT = 0.05f
        private const val MAX_SUBDIVISIONS = 1024
        private const val MAX_SMOOTH_STEPS = 64
    }
}
