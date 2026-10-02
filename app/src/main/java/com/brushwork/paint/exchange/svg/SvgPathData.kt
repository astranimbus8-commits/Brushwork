package com.brushwork.paint.exchange.svg

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.vector.PathOp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * SVG path data (`d`, v1.5 §4.11b): the full grammar `M L H V C S Q T A Z` in absolute and
 * relative forms, implicit repeats (an implicit `L` after `M`), numbers like `-.5.5` and `1e-5`,
 * arc flags packed without separators (`a1 1 0 0110 10`). Quadratics and arcs become cubics
 * (arcs in pieces small enough to stay within [tolerance] user units of the true ellipse). As SVG
 * requires, an error stops parsing and keeps what was read up to it. Pure Kotlin.
 */
object SvgPathData {
    /**
     * The commands of [d] as absolute lines and cubics in user units; [tolerance] is the largest
     * deviation allowed for arcs. At most [maxPoints] points are produced (the rest is dropped).
     */
    fun parse(d: String?, tolerance: Float = 0.05f, maxPoints: Int = Int.MAX_VALUE): List<PathOp> {
        if (d.isNullOrBlank()) return emptyList()
        val p = Parser(d, tolerance.coerceAtLeast(1e-4f), maxPoints)
        p.run()
        return p.ops
    }

    private class Parser(val s: String, val tol: Float, val maxPoints: Int) {
        val ops = ArrayList<PathOp>()
        var i = 0
        var cx = 0f
        var cy = 0f
        var startX = 0f
        var startY = 0f
        // Last control point (for S and T) and the kind of the previous command.
        var lastCx = 0f
        var lastCy = 0f
        var prev = ' '
        var points = 0
        var open = false

        fun run() {
            var cmd = ' '
            while (true) {
                skipSep()
                if (i >= s.length) return
                val ch = s[i]
                if (ch.isLetter()) {
                    cmd = ch
                    i++
                    if (cmd == 'Z' || cmd == 'z') {
                        close()
                        continue
                    }
                } else if (cmd == ' ' || cmd == 'Z' || cmd == 'z') {
                    return // numbers without a command: an error
                }
                if (!command(cmd)) return
                if (points > maxPoints) return
                // After M / m the implicit repeats are L / l.
                if (cmd == 'M') cmd = 'L' else if (cmd == 'm') cmd = 'l'
            }
        }

        fun close() {
            if (open) ops += PathOp.Close
            open = false
            cx = startX; cy = startY
            prev = 'Z'
        }

        /** One segment of [cmd]; false on a syntax error. */
        fun command(cmd: Char): Boolean {
            val rel = cmd.isLowerCase()
            val ox = if (rel) cx else 0f
            val oy = if (rel) cy else 0f
            when (cmd.uppercaseChar()) {
                'M' -> {
                    val x = num() ?: return false
                    val y = num() ?: return false
                    moveTo(ox + x, oy + y)
                    prev = 'M'
                }
                'L' -> {
                    val x = num() ?: return false
                    val y = num() ?: return false
                    lineTo(ox + x, oy + y)
                    prev = 'L'
                }
                'H' -> {
                    val x = num() ?: return false
                    lineTo(ox + x, cy)
                    prev = 'L'
                }
                'V' -> {
                    val y = num() ?: return false
                    lineTo(cx, oy + y)
                    prev = 'L'
                }
                'C' -> {
                    val v = nums(6) ?: return false
                    cubic(ox + v[0], oy + v[1], ox + v[2], oy + v[3], ox + v[4], oy + v[5])
                    prev = 'C'
                }
                'S' -> {
                    val v = nums(4) ?: return false
                    val (x1, y1) = if (prev == 'C') (2 * cx - lastCx) to (2 * cy - lastCy) else cx to cy
                    cubic(x1, y1, ox + v[0], oy + v[1], ox + v[2], oy + v[3])
                    prev = 'C'
                }
                'Q' -> {
                    val v = nums(4) ?: return false
                    quad(ox + v[0], oy + v[1], ox + v[2], oy + v[3])
                    prev = 'Q'
                }
                'T' -> {
                    val v = nums(2) ?: return false
                    val (qx, qy) = if (prev == 'Q') (2 * cx - lastCx) to (2 * cy - lastCy) else cx to cy
                    quad(qx, qy, ox + v[0], oy + v[1])
                    prev = 'Q'
                }
                'A' -> {
                    val rx = num() ?: return false
                    val ry = num() ?: return false
                    val rot = num() ?: return false
                    val large = flag() ?: return false
                    val sweep = flag() ?: return false
                    val x = num() ?: return false
                    val y = num() ?: return false
                    arc(abs(rx), abs(ry), rot, large, sweep, ox + x, oy + y)
                    prev = 'A'
                }
                else -> return false
            }
            return true
        }

        fun ensureOpen() {
            if (!open) {
                ops += PathOp.MoveTo(Vec2(cx, cy))
                startX = cx; startY = cy
                open = true
                points++
            }
        }

        fun moveTo(x: Float, y: Float) {
            ops += PathOp.MoveTo(Vec2(x, y))
            cx = x; cy = y
            startX = x; startY = y
            open = true
            points++
        }

        fun lineTo(x: Float, y: Float) {
            ensureOpen()
            ops += PathOp.LineTo(Vec2(x, y))
            cx = x; cy = y
            points++
        }

        fun cubic(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) {
            ensureOpen()
            ops += PathOp.CubicTo(Vec2(x1, y1), Vec2(x2, y2), Vec2(x, y))
            lastCx = x2; lastCy = y2
            cx = x; cy = y
            points += 3
        }

        fun quad(qx: Float, qy: Float, x: Float, y: Float) {
            ensureOpen()
            val x0 = cx
            val y0 = cy
            ops += PathOp.CubicTo(
                Vec2(x0 + 2f / 3f * (qx - x0), y0 + 2f / 3f * (qy - y0)),
                Vec2(x + 2f / 3f * (qx - x), y + 2f / 3f * (qy - y)),
                Vec2(x, y),
            )
            lastCx = qx; lastCy = qy
            cx = x; cy = y
            points += 3
        }

        /** Endpoint arc (SVG implementation notes F.6) as cubics. */
        fun arc(rx0: Float, ry0: Float, rotDeg: Float, large: Boolean, sweep: Boolean, x: Float, y: Float) {
            ensureOpen()
            val x0 = cx
            val y0 = cy
            if (x0 == x && y0 == y) return
            if (rx0 == 0f || ry0 == 0f) { lineTo(x, y); return }
            val phi = rotDeg * PI / 180.0
            val cosP = cos(phi)
            val sinP = sin(phi)
            val dx2 = (x0 - x) / 2.0
            val dy2 = (y0 - y) / 2.0
            val x1p = cosP * dx2 + sinP * dy2
            val y1p = -sinP * dx2 + cosP * dy2
            var rx = rx0.toDouble()
            var ry = ry0.toDouble()
            // Radii too small for the endpoints: scaled up (F.6.6).
            val lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
            if (lambda > 1.0) { val k = sqrt(lambda); rx *= k; ry *= k }
            val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
            val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
            var coef = if (den == 0.0) 0.0 else sqrt(max(0.0, num / den))
            if (large == sweep) coef = -coef
            val cxp = coef * (rx * y1p / ry)
            val cyp = coef * (-ry * x1p / rx)
            val ccx = cosP * cxp - sinP * cyp + (x0 + x) / 2.0
            val ccy = sinP * cxp + cosP * cyp + (y0 + y) / 2.0
            val theta1 = angle(1.0, 0.0, (x1p - cxp) / rx, (y1p - cyp) / ry)
            var dTheta = angle((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
            if (!sweep && dTheta > 0) dTheta -= 2 * PI else if (sweep && dTheta < 0) dTheta += 2 * PI
            // Pieces of at most 90°, fewer degrees for large radii so the error stays within tol:
            // a cubic's error over an arc of angle a is about r * 2.7e-4 * (a / 90°)^6.
            val rMax = max(rx, ry)
            var n = ceil(abs(dTheta) / (PI / 2)).toInt().coerceAtLeast(1)
            val maxAngle = (PI / 2) * (tol / (rMax * 2.7e-4)).coerceAtMost(1.0).pow(1.0 / 6.0)
            n = max(n, ceil(abs(dTheta) / maxAngle).toInt()).coerceIn(1, 4096)
            val step = dTheta / n
            val k = 4.0 / 3.0 * tan(step / 4.0)
            var t = theta1
            for (seg in 0 until n) {
                val t2 = t + step
                val c1 = cos(t); val s1 = sin(t)
                val c2 = cos(t2); val s2 = sin(t2)
                // Unit-circle control points, then the ellipse (rx, ry), rotation and centre.
                val p1x = c1 - k * s1; val p1y = s1 + k * c1
                val p2x = c2 + k * s2; val p2y = s2 - k * c2
                fun mx(ux: Double, uy: Double) = (cosP * rx * ux - sinP * ry * uy + ccx).toFloat()
                fun my(ux: Double, uy: Double) = (sinP * rx * ux + cosP * ry * uy + ccy).toFloat()
                val last = seg == n - 1
                ops += PathOp.CubicTo(
                    Vec2(mx(p1x, p1y), my(p1x, p1y)),
                    Vec2(mx(p2x, p2y), my(p2x, p2y)),
                    if (last) Vec2(x, y) else Vec2(mx(c2, s2), my(c2, s2)),
                )
                points += 3
                t = t2
            }
            cx = x; cy = y
        }

        fun angle(ux: Double, uy: Double, vx: Double, vy: Double): Double {
            val dot = ux * vx + uy * vy
            val len = sqrt(ux * ux + uy * uy) * sqrt(vx * vx + vy * vy)
            if (len == 0.0) return 0.0
            var a = acos((dot / len).coerceIn(-1.0, 1.0))
            if (ux * vy - uy * vx < 0) a = -a
            return a
        }

        fun skipSep() {
            while (i < s.length && (s[i] == ' ' || s[i] == ',' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t' || s[i] == '\u000C')) i++
        }

        fun num(): Float? {
            skipSep()
            val (v, end) = SvgUnits.numberAt(s, i) ?: return null
            i = end
            return v
        }

        fun nums(n: Int): FloatArray? {
            val out = FloatArray(n)
            for (k in 0 until n) out[k] = num() ?: return null
            return out
        }

        /** An arc flag: a single 0 or 1, which may be followed directly by the next number. */
        fun flag(): Boolean? {
            skipSep()
            if (i >= s.length) return null
            return when (s[i]) {
                '0' -> { i++; false }
                '1' -> { i++; true }
                else -> null
            }
        }
    }
}
