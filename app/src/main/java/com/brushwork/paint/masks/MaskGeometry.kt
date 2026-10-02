package com.brushwork.paint.masks

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geometry of editable mask components (pure Kotlin, v1.5 §4.3; owned by A5): affine maps of
 * components and specs, pins (the point a component is moved by) and boxes.
 *
 * An affine map is `x' = a·x + b·y + tx`, `y' = c·x + d·y + ty`, given as [Affine].
 */
object MaskGeometry {

    /** An affine map of document points. */
    data class Affine(val a: Float, val b: Float, val tx: Float, val c: Float, val d: Float, val ty: Float) {
        val det: Float get() = a * d - b * c

        fun mapX(x: Float, y: Float): Float = a * x + b * y + tx
        fun mapY(x: Float, y: Float): Float = c * x + d * y + ty

        val isFinite: Boolean get() = a.isFinite() && b.isFinite() && tx.isFinite() && c.isFinite() && d.isFinite() && ty.isFinite()

        /** This map as a 3x3 row-major matrix (for `PackedPoints.mapped`). */
        fun toArray(): FloatArray = floatArrayOf(a, b, tx, c, d, ty, 0f, 0f, 1f)

        /** The inverse map, or null when it doesn't exist. */
        fun inverse(): Affine? {
            val dt = det
            if (!(abs(dt) > 1e-12f) || !dt.isFinite()) return null
            val ia = d / dt; val ib = -b / dt; val ic = -c / dt; val id = a / dt
            return Affine(ia, ib, -(ia * tx + ib * ty), ic, id, -(ic * tx + id * ty))
        }

        /** This map after [first] (x → this(first(x))). */
        fun after(first: Affine): Affine = Affine(
            a * first.a + b * first.c, a * first.b + b * first.d, a * first.tx + b * first.ty + tx,
            c * first.a + d * first.c, c * first.b + d * first.d, c * first.tx + d * first.ty + ty,
        )

        companion object {
            val IDENTITY = Affine(1f, 0f, 0f, 0f, 1f, 0f)

            fun translate(dx: Float, dy: Float) = Affine(1f, 0f, dx, 0f, 1f, dy)

            /** Scale [s] and rotation [degrees] (clockwise on screen) around ([px], [py]), then a move by ([dx], [dy]). */
            fun similarity(px: Float, py: Float, s: Float, degrees: Float, dx: Float = 0f, dy: Float = 0f): Affine {
                val r = Math.toRadians(degrees.toDouble())
                val cs = (cos(r) * s).toFloat(); val sn = (sin(r) * s).toFloat()
                // x' = R·s·(x − p) + p + d
                return Affine(cs, -sn, px - cs * px + sn * py + dx, sn, cs, py - sn * px - cs * py + dy)
            }
        }
    }

    /**
     * [c] mapped by [m], exactly for linear and radial components (their iso-lines map onto
     * iso-lines for any affine map); brush strokes map their points and scale their size by
     * √|det|. Null when [m] can't be inverted or isn't finite.
     */
    fun transformed(c: MaskComponent, m: Affine): MaskComponent? {
        if (!m.isFinite) return null
        val det = m.det
        if (!(abs(det) > 1e-12f)) return null
        return when (c) {
            is LinearMask -> {
                val x0 = m.mapX(c.x0, c.y0); val y0 = m.mapY(c.x0, c.y0)
                val dx = c.x1 - c.x0; val dy = c.y1 - c.y0
                val len2 = dx * dx + dy * dy
                if (!(len2 > 0f)) {
                    c.copy(x0 = x0, y0 = y0, x1 = x0, y1 = y0)
                } else {
                    // t(x) = (x − p0)·d / |d|² is affine; after the map its gradient is A^-T d / |d|².
                    val gx = (m.d * dx - m.c * dy) / det / len2
                    val gy = (-m.b * dx + m.a * dy) / det / len2
                    val g2 = gx * gx + gy * gy
                    if (!(g2 > 0f) || !g2.isFinite()) return null
                    c.copy(x0 = x0, y0 = y0, x1 = x0 + gx / g2, y1 = y0 + gy / g2)
                }
            }
            is RadialMask -> {
                val cx = m.mapX(c.cx, c.cy); val cy = m.mapY(c.cx, c.cy)
                // A similarity (with or without a reflection) turns and scales the ellipse as it is,
                // so its handles follow the turn (also for a circle, whose angle is otherwise free).
                val s = sqrt(abs(det))
                val tol = 1e-4f * max(1f, s)
                if (abs(m.a - m.d) <= tol && abs(m.b + m.c) <= tol) {
                    val phi = Math.toDegrees(atan2(m.c, m.a).toDouble()).toFloat()
                    return c.copy(cx = cx, cy = cy, rx = c.rx * s, ry = c.ry * s, rotationDeg = normalizeDegrees(c.rotationDeg + phi))
                }
                if (abs(m.a + m.d) <= tol && abs(m.b - m.c) <= tol) {
                    val phi = Math.toDegrees(atan2(m.c, m.a).toDouble()).toFloat()
                    return c.copy(cx = cx, cy = cy, rx = c.rx * s, ry = c.ry * s, rotationDeg = normalizeDegrees(phi - c.rotationDeg))
                }
                // The ellipse is c + R(θ)·diag(rx, ry)·u, |u| <= 1; after the map, M = A·R(θ)·diag(rx, ry).
                val t = Math.toRadians(c.rotationDeg.toDouble())
                val ct = cos(t).toFloat(); val st = sin(t).toFloat()
                val m00 = (m.a * ct + m.b * st) * c.rx
                val m01 = (-m.a * st + m.b * ct) * c.ry
                val m10 = (m.c * ct + m.d * st) * c.rx
                val m11 = (-m.c * st + m.d * ct) * c.ry
                // 2x2 SVD: M = R(θ')·diag(sx, sy)·R(φ); a negative sy is a reflection on the right (norm-free).
                val e = (m00 + m11) / 2f; val f = (m00 - m11) / 2f
                val g = (m10 + m01) / 2f; val h = (m10 - m01) / 2f
                val q = sqrt(e * e + h * h); val r = sqrt(f * f + g * g)
                val sx = q + r; val sy = abs(q - r)
                val a1 = atan2(g, f); val a2 = atan2(h, e)
                val theta = (a2 + a1) / 2f
                if (!sx.isFinite() || !theta.isFinite()) return null
                c.copy(cx = cx, cy = cy, rx = sx, ry = sy, rotationDeg = normalizeDegrees(Math.toDegrees(theta.toDouble()).toFloat()))
            }
            is BrushMask -> {
                val k = sqrt(abs(det))
                val arr = m.toArray()
                c.copy(strokes = c.strokes.map { s -> s.copy(points = s.points.mapped(arr), size = s.size * k) })
            }
        }
    }

    /** [spec] with every component mapped by [m] (null when one can't be). */
    fun transformed(spec: MaskSpec, m: Affine): MaskSpec? {
        val comps = spec.components.map { transformed(it, m) ?: return null }
        return spec.copy(components = comps)
    }

    /** Degrees in (−180, 180]. */
    fun normalizeDegrees(d: Float): Float {
        var v = d % 360f
        if (v <= -180f) v += 360f
        if (v > 180f) v -= 360f
        return v
    }

    /**
     * The pin of [c] (document px): the point it is moved by and that the X / Y strip shows —
     * the middle of the ramp of a linear component, the centre of a radial one, the middle of a
     * brush component's painted area. Null for an empty brush component.
     */
    fun pin(c: MaskComponent): Pair<Float, Float>? = when (c) {
        is LinearMask -> (c.x0 + c.x1) / 2f to (c.y0 + c.y1) / 2f
        is RadialMask -> c.cx to c.cy
        is BrushMask -> brushBox(c)?.let { (it[0] + it[2]) / 2f to (it[1] + it[3]) / 2f }
    }

    /** [c] moved so its pin is at ([x], [y]). */
    fun movedTo(c: MaskComponent, x: Float, y: Float): MaskComponent {
        val p = pin(c) ?: return c
        if (!x.isFinite() || !y.isFinite()) return c
        return transformed(c, Affine.translate(x - p.first, y - p.second)) ?: c
    }

    /** Bounds (left, top, right, bottom) of the points of a brush component's strokes (no radius), or null. */
    fun brushBox(c: BrushMask): FloatArray? {
        var l = Float.POSITIVE_INFINITY; var t = Float.POSITIVE_INFINITY
        var r = Float.NEGATIVE_INFINITY; var b = Float.NEGATIVE_INFINITY
        for (s in c.strokes) {
            val p = s.points
            for (i in 0 until p.size) {
                val x = p.x[i]; val y = p.y[i]
                if (!x.isFinite() || !y.isFinite()) continue
                l = min(l, x); r = max(r, x); t = min(t, y); b = max(b, y)
            }
        }
        return if (l > r) null else floatArrayOf(l, t, r, b)
    }

    /**
     * The box of [c] as drawn by the Masks tool (four corners, document px, clockwise): the
     * rotated rectangle around a radial ellipse, the painted area of a brush component (with the
     * brush radius), and for a linear component the band between its 100 % and 0 % lines, as long
     * as it is wide (a square around the ramp).
     */
    fun boxCorners(c: MaskComponent): List<Pair<Float, Float>>? = when (c) {
        is RadialMask -> {
            val t = Math.toRadians(c.rotationDeg.toDouble())
            val ux = cos(t).toFloat(); val uy = sin(t).toFloat()
            val rx = abs(c.rx); val ry = abs(c.ry)
            listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f).map { (sx, sy) ->
                (c.cx + ux * rx * sx - uy * ry * sy) to (c.cy + uy * rx * sx + ux * ry * sy)
            }
        }
        is LinearMask -> {
            val dx = c.x1 - c.x0; val dy = c.y1 - c.y0
            val len = sqrt(dx * dx + dy * dy)
            if (!(len > 0f)) null else {
                val nx = -dy / len * len / 2f; val ny = dx / len * len / 2f
                listOf(
                    (c.x0 + nx) to (c.y0 + ny), (c.x1 + nx) to (c.y1 + ny),
                    (c.x1 - nx) to (c.y1 - ny), (c.x0 - nx) to (c.y0 - ny),
                )
            }
        }
        is BrushMask -> {
            val box = brushBox(c)
            if (box == null) null else {
                val r = (c.strokes.maxOfOrNull { if (it.size.isFinite()) it.size else 0f } ?: 0f) / 2f
                listOf((box[0] - r) to (box[1] - r), (box[2] + r) to (box[1] - r), (box[2] + r) to (box[3] + r), (box[0] - r) to (box[3] + r))
            }
        }
    }

    /** Short name of a component kind. */
    fun kindName(c: MaskComponent): String = when (c) {
        is LinearMask -> "Linear"
        is RadialMask -> "Radial"
        is BrushMask -> "Brush"
    }

    /** "Linear 1", "Radial 2": the kind and the component's number among those of its kind in [spec]. */
    fun displayName(spec: MaskSpec, c: MaskComponent): String {
        val same = spec.components.filter { it::class == c::class }
        val n = same.indexOfFirst { it.id == c.id }.let { if (it < 0) same.size + 1 else it + 1 }
        return "${kindName(c)} $n"
    }

    /** [spec] with the component of [id] replaced by [c] (same position). */
    fun replaced(spec: MaskSpec, id: Long, c: MaskComponent): MaskSpec =
        spec.copy(components = spec.components.map { if (it.id == id) c else it })

    /** [spec] with [c] appended on top, given the next free id. */
    fun added(spec: MaskSpec, c: MaskComponent): MaskSpec {
        val id = max(spec.nextId, (spec.components.maxOfOrNull { it.id } ?: 0L) + 1)
        val withId = withId(c, id)
        return spec.copy(components = spec.components + withId, nextId = id + 1)
    }

    /** The id the next added component gets. */
    fun nextId(spec: MaskSpec): Long = max(spec.nextId, (spec.components.maxOfOrNull { it.id } ?: 0L) + 1)

    /** [c] with id [id]. */
    fun withId(c: MaskComponent, id: Long): MaskComponent = when (c) {
        is LinearMask -> c.copy(id = id)
        is RadialMask -> c.copy(id = id)
        is BrushMask -> c.copy(id = id)
    }

    /** [c] with its common fields changed. */
    fun withCommon(c: MaskComponent, mode: MaskMode = c.mode, invert: Boolean = c.invert, amount: Float = c.amount, visible: Boolean = c.visible): MaskComponent = when (c) {
        is LinearMask -> c.copy(mode = mode, invert = invert, amount = amount, visible = visible)
        is RadialMask -> c.copy(mode = mode, invert = invert, amount = amount, visible = visible)
        is BrushMask -> c.copy(mode = mode, invert = invert, amount = amount, visible = visible)
    }
}
