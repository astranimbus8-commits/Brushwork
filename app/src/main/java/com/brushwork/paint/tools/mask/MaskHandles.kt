package com.brushwork.paint.tools.mask

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import com.brushwork.paint.core.Geometry
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.masks.BrushMask
import com.brushwork.paint.masks.LinearMask
import com.brushwork.paint.masks.MaskComponent
import com.brushwork.paint.masks.MaskGeometry
import com.brushwork.paint.masks.MaskSpec
import com.brushwork.paint.masks.RadialMask
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The on-canvas handles of mask components (v1.5 §4.3a; owned by A5): their positions, what a
 * finger grabs, how a drag changes the component, and how they are drawn (screen space).
 *
 * - Linear: a solid centre line with a pin (move), dashed 100 % and 0 % lines (drag along the
 *   ramp to change its width) and a knob on the centre line (rotate).
 * - Radial: the ellipse, a dashed inner feather ellipse with a feather handle, four side handles
 *   (rx, ry), a rotation stem and a centre pin.
 * - Brush: a pin (move).
 *
 * Every component shows a pin with its letter (L, R, B); the selected one is filled.
 */
internal object MaskHandles {
    enum class Kind { PIN, LINEAR_START, LINEAR_END, LINEAR_ROTATE, RX_POS, RX_NEG, RY_POS, RY_NEG, ROTATE, FEATHER }

    /** Grab radius of handles and pins (dp). */
    const val GRAB_DP = 24f
    /** Distance of rotation knobs from what they turn (dp). */
    const val KNOB_DP = 56f
    /** Pin radius (dp). */
    const val PIN_DP = 12f
    /** Half the length the 100 % / 0 % / centre lines of a linear component are drawn (dp). */
    private const val LINE_DP = 2400f

    /** Unit direction of a linear component (p0 → p1), or null when it has none. */
    fun direction(c: LinearMask): Vec2? {
        val dx = c.x1 - c.x0; val dy = c.y1 - c.y0
        val len = sqrt(dx * dx + dy * dy)
        return if (len > 1e-4f && len.isFinite()) Vec2(dx / len, dy / len) else null
    }

    private fun axes(c: RadialMask): Pair<Vec2, Vec2> {
        val r = Math.toRadians(c.rotationDeg.toDouble())
        val ux = Vec2(cos(r).toFloat(), sin(r).toFloat())
        return ux to Vec2(-ux.y, ux.x)
    }

    /** Document position of handle [k] of [c] (null when [c] has no such handle). */
    fun position(c: MaskComponent, k: Kind, t: ViewTransform): Vec2? {
        val knob = t.screenToDocLength(t.dp(KNOB_DP))
        return when (c) {
            is LinearMask -> {
                val u = direction(c)
                val m = Vec2((c.x0 + c.x1) / 2f, (c.y0 + c.y1) / 2f)
                when (k) {
                    Kind.PIN -> m
                    Kind.LINEAR_ROTATE -> u?.let { m + it.perpendicular() * knob }
                    Kind.LINEAR_START -> Vec2(c.x0, c.y0)
                    Kind.LINEAR_END -> Vec2(c.x1, c.y1)
                    else -> null
                }
            }
            is RadialMask -> {
                val (ux, uy) = axes(c)
                val ctr = Vec2(c.cx, c.cy)
                when (k) {
                    Kind.PIN -> ctr
                    Kind.RX_POS -> ctr + ux * c.rx
                    Kind.RX_NEG -> ctr - ux * c.rx
                    Kind.RY_POS -> ctr + uy * c.ry
                    Kind.RY_NEG -> ctr - uy * c.ry
                    Kind.ROTATE -> ctr - uy * (c.ry + knob)
                    Kind.FEATHER -> {
                        val k2 = featherHandleScale(c)
                        val a = FEATHER_ANGLE
                        ctr + ux * (c.rx * k2 * cos(a)) + uy * (c.ry * k2 * sin(a))
                    }
                    else -> null
                }
            }
            is BrushMask -> if (k == Kind.PIN) MaskGeometry.pin(c)?.let { Vec2(it.first, it.second) } else null
        }
    }

    /** Where on the inner ellipse the feather handle sits (kept off the centre pin). */
    private fun featherHandleScale(c: RadialMask): Float = (1f - c.feather.coerceIn(0f, 1f)).coerceAtLeast(0.2f)

    private val FEATHER_ANGLE = (-Math.PI / 4).toFloat()

    /**
     * The handle of the SELECTED component [c] under document point [p], or null. Knobs and
     * point handles come first, then the dashed lines of a linear component.
     */
    fun hit(c: MaskComponent, p: Vec2, t: ViewTransform): Kind? {
        val sp = t.docToScreen(p)
        val grab = t.dp(GRAB_DP)
        val order = when (c) {
            is LinearMask -> listOf(Kind.LINEAR_ROTATE, Kind.PIN)
            is RadialMask -> listOf(Kind.ROTATE, Kind.FEATHER, Kind.RX_POS, Kind.RX_NEG, Kind.RY_POS, Kind.RY_NEG, Kind.PIN)
            is BrushMask -> listOf(Kind.PIN)
        }
        var best: Kind? = null
        var bestD = Float.MAX_VALUE
        for (k in order) {
            val q = position(c, k, t) ?: continue
            val d = t.docToScreen(q).distanceTo(sp)
            if (d <= grab && d < bestD) { best = k; bestD = d }
        }
        if (best != null) return best
        if (c is LinearMask) {
            val u = direction(c) ?: return null
            // The dashed lines are perpendicular to the ramp through p0 and p1.
            val n = u.perpendicular()
            val d0 = Geometry.distanceToSegment(sp, t.docToScreen(Vec2(c.x0, c.y0) - n * 1e5f), t.docToScreen(Vec2(c.x0, c.y0) + n * 1e5f))
            val d1 = Geometry.distanceToSegment(sp, t.docToScreen(Vec2(c.x1, c.y1) - n * 1e5f), t.docToScreen(Vec2(c.x1, c.y1) + n * 1e5f))
            val tol = t.dp(GRAB_DP * 0.8f)
            if (d0 <= tol && d0 <= d1) return Kind.LINEAR_START
            if (d1 <= tol) return Kind.LINEAR_END
        }
        return null
    }

    /** The component of [spec] whose pin is under [p] (the closest; the selected one wins ties), or null. */
    fun hitPin(spec: MaskSpec, p: Vec2, t: ViewTransform, selectedId: Long?): MaskComponent? {
        val sp = t.docToScreen(p)
        val grab = t.dp(GRAB_DP)
        var best: MaskComponent? = null
        var bestD = Float.MAX_VALUE
        for (c in spec.components) {
            val q = position(c, Kind.PIN, t) ?: continue
            var d = t.docToScreen(q).distanceTo(sp)
            if (c.id == selectedId) d -= 0.5f
            if (d <= grab && d < bestD) { best = c; bestD = d }
        }
        return best
    }

    /**
     * [start] changed by dragging handle [k] from document point [from] to [to] (relative: the
     * component never jumps under the finger). Linear ramps keep at least 1 px, radii at least
     * 1 px; the feather stays in 0..1.
     */
    fun dragged(start: MaskComponent, k: Kind, from: Vec2, to: Vec2): MaskComponent {
        val delta = to - from
        return when (start) {
            is LinearMask -> {
                val u = direction(start)
                when (k) {
                    Kind.PIN -> MaskGeometry.transformed(start, MaskGeometry.Affine.translate(delta.x, delta.y)) ?: start
                    Kind.LINEAR_START -> {
                        if (u == null) return start.copy(x0 = start.x0 + delta.x, y0 = start.y0 + delta.y)
                        val len = (Vec2(start.x1, start.y1) - Vec2(start.x0, start.y0)).length
                        val along = delta.dot(u).coerceAtMost(len - 1f)
                        start.copy(x0 = start.x0 + u.x * along, y0 = start.y0 + u.y * along)
                    }
                    Kind.LINEAR_END -> {
                        if (u == null) return start.copy(x1 = start.x1 + delta.x, y1 = start.y1 + delta.y)
                        val len = (Vec2(start.x1, start.y1) - Vec2(start.x0, start.y0)).length
                        val along = delta.dot(u).coerceAtLeast(1f - len)
                        start.copy(x1 = start.x1 + u.x * along, y1 = start.y1 + u.y * along)
                    }
                    Kind.LINEAR_ROTATE -> {
                        val m = Vec2((start.x0 + start.x1) / 2f, (start.y0 + start.y1) / 2f)
                        val deg = angleDeg(m, from, to)
                        MaskGeometry.transformed(start, MaskGeometry.Affine.similarity(m.x, m.y, 1f, deg)) ?: start
                    }
                    else -> start
                }
            }
            is RadialMask -> {
                val (ux, uy) = axes(start)
                val ctr = Vec2(start.cx, start.cy)
                when (k) {
                    Kind.PIN -> start.copy(cx = start.cx + delta.x, cy = start.cy + delta.y)
                    Kind.RX_POS -> start.copy(rx = max(1f, start.rx + delta.dot(ux)))
                    Kind.RX_NEG -> start.copy(rx = max(1f, start.rx - delta.dot(ux)))
                    Kind.RY_POS -> start.copy(ry = max(1f, start.ry + delta.dot(uy)))
                    Kind.RY_NEG -> start.copy(ry = max(1f, start.ry - delta.dot(uy)))
                    Kind.ROTATE -> start.copy(rotationDeg = MaskGeometry.normalizeDegrees(start.rotationDeg + angleDeg(ctr, from, to)))
                    Kind.FEATHER -> {
                        val d0 = ellipseDistance(start, from); val d1 = ellipseDistance(start, to)
                        start.copy(feather = (start.feather - (d1 - d0)).coerceIn(0f, 1f))
                    }
                    else -> start
                }
            }
            is BrushMask -> if (k == Kind.PIN) MaskGeometry.transformed(start, MaskGeometry.Affine.translate(delta.x, delta.y)) ?: start else start
        }
    }

    /** Normalized elliptical distance of [p] from [c]'s centre (1 on the ellipse). */
    fun ellipseDistance(c: RadialMask, p: Vec2): Float {
        val (ux, uy) = axes(c)
        val d = p - Vec2(c.cx, c.cy)
        val nx = d.dot(ux) / max(1e-3f, c.rx); val ny = d.dot(uy) / max(1e-3f, c.ry)
        return sqrt(nx * nx + ny * ny)
    }

    /** Signed angle (degrees, clockwise on screen) from [from] to [to] around [center]. */
    private fun angleDeg(center: Vec2, from: Vec2, to: Vec2): Float {
        val a0 = atan2(from.y - center.y, from.x - center.x)
        val a1 = atan2(to.y - center.y, to.x - center.x)
        return MaskGeometry.normalizeDegrees(Math.toDegrees((a1 - a0).toDouble()).toFloat())
    }

    // ------------------------------------------------------------------ drawing (screen space)

    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x99000000.toInt(); strokeCap = Paint.Cap.ROUND }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = -1; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val path = Path()

    private const val ACCENT = 0xFF4DA3FF.toInt()
    private const val DARK = 0xE0202226.toInt()

    /** Pins of every component of [spec] (the selected one filled). */
    fun drawPins(canvas: Canvas, spec: MaskSpec, selectedId: Long?, t: ViewTransform) {
        for (c in spec.components) {
            val p = position(c, Kind.PIN, t) ?: continue
            drawPin(canvas, t.docToScreen(p), letter(c), c.id == selectedId, c.visible, t)
        }
    }

    fun letter(c: MaskComponent): String = when (c) {
        is LinearMask -> "L"
        is RadialMask -> "R"
        is BrushMask -> "B"
    }

    private fun drawPin(canvas: Canvas, s: Vec2, letter: String, selected: Boolean, visible: Boolean, t: ViewTransform) {
        val r = t.dp(PIN_DP)
        fill.color = if (selected) ACCENT else DARK
        canvas.drawCircle(s.x, s.y, r, fill)
        line.strokeWidth = t.dp(1.75f)
        line.color = if (visible) -1 else 0xFF9AA0A6.toInt()
        canvas.drawCircle(s.x, s.y, r, line)
        line.color = -1
        text.textSize = t.dp(12f)
        text.color = if (selected) 0xFF002B55.toInt() else -1
        canvas.drawText(letter, s.x, s.y + t.dp(4.2f), text)
    }

    /** The handles of the selected component [c]. */
    fun drawHandles(canvas: Canvas, c: MaskComponent, t: ViewTransform) {
        shadow.strokeWidth = t.dp(4f)
        line.strokeWidth = t.dp(1.75f)
        when (c) {
            is LinearMask -> drawLinear(canvas, c, t)
            is RadialMask -> drawRadial(canvas, c, t)
            is BrushMask -> {}
        }
    }

    private fun stroke(canvas: Canvas, a: Vec2, b: Vec2, dashed: Boolean, t: ViewTransform) {
        val effect = if (dashed) DashPathEffect(floatArrayOf(t.dp(8f), t.dp(6f)), 0f) else null
        shadow.pathEffect = effect
        line.pathEffect = effect
        canvas.drawLine(a.x, a.y, b.x, b.y, shadow)
        canvas.drawLine(a.x, a.y, b.x, b.y, line)
        shadow.pathEffect = null
        line.pathEffect = null
    }

    private fun drawLinear(canvas: Canvas, c: LinearMask, t: ViewTransform) {
        val p0 = t.docToScreen(Vec2(c.x0, c.y0))
        val p1 = t.docToScreen(Vec2(c.x1, c.y1))
        val d = p1 - p0
        val len = d.length
        val n = if (len > 1e-3f) d.perpendicular() / len else Vec2(0f, 1f)
        val half = t.dp(LINE_DP)
        val m = (p0 + p1) / 2f
        stroke(canvas, p0 - n * half, p0 + n * half, dashed = true, t)
        stroke(canvas, p1 - n * half, p1 + n * half, dashed = true, t)
        stroke(canvas, m - n * half, m + n * half, dashed = false, t)
        // Rotation knob on the centre line.
        position(c, Kind.LINEAR_ROTATE, t)?.let { drawKnob(canvas, t.docToScreen(it), t) }
    }

    private fun drawRadial(canvas: Canvas, c: RadialMask, t: ViewTransform) {
        val (ux, uy) = axes(c)
        val ctr = Vec2(c.cx, c.cy)
        ellipsePath(ctr, ux, uy, c.rx, c.ry, t)
        canvas.drawPath(path, shadow)
        canvas.drawPath(path, line)
        val k = 1f - c.feather.coerceIn(0f, 1f)
        if (k > 0.01f) {
            ellipsePath(ctr, ux, uy, c.rx * k, c.ry * k, t)
            val effect = DashPathEffect(floatArrayOf(t.dp(8f), t.dp(6f)), 0f)
            shadow.pathEffect = effect; line.pathEffect = effect
            canvas.drawPath(path, shadow)
            canvas.drawPath(path, line)
            shadow.pathEffect = null; line.pathEffect = null
        }
        // Rotation stem from the top side handle.
        val top = t.docToScreen(ctr - uy * c.ry)
        val knob = t.docToScreen(position(c, Kind.ROTATE, t)!!)
        stroke(canvas, top, knob, dashed = false, t)
        drawKnob(canvas, knob, t)
        for (h in listOf(Kind.RX_POS, Kind.RX_NEG, Kind.RY_POS, Kind.RY_NEG)) drawSquare(canvas, t.docToScreen(position(c, h, t)!!), t)
        drawDiamond(canvas, t.docToScreen(position(c, Kind.FEATHER, t)!!), t)
    }

    private fun ellipsePath(ctr: Vec2, ux: Vec2, uy: Vec2, rx: Float, ry: Float, t: ViewTransform) {
        path.rewind()
        val n = 72
        for (i in 0..n) {
            val a = (i * 2.0 * Math.PI / n).toFloat()
            val q = t.docToScreen(ctr + ux * (rx * cos(a)) + uy * (ry * sin(a)))
            if (i == 0) path.moveTo(q.x, q.y) else path.lineTo(q.x, q.y)
        }
        path.close()
    }

    private fun drawKnob(canvas: Canvas, s: Vec2, t: ViewTransform) {
        fill.color = -1
        canvas.drawCircle(s.x, s.y, t.dp(7f), fill)
        line.color = DARK
        line.strokeWidth = t.dp(1.5f)
        canvas.drawCircle(s.x, s.y, t.dp(7f), line)
        line.color = -1
        line.strokeWidth = t.dp(1.75f)
    }

    private fun drawSquare(canvas: Canvas, s: Vec2, t: ViewTransform) {
        val r = t.dp(6f)
        fill.color = -1
        canvas.drawRect(s.x - r, s.y - r, s.x + r, s.y + r, fill)
        line.color = DARK
        line.strokeWidth = t.dp(1.5f)
        canvas.drawRect(s.x - r, s.y - r, s.x + r, s.y + r, line)
        line.color = -1
        line.strokeWidth = t.dp(1.75f)
    }

    private fun drawDiamond(canvas: Canvas, s: Vec2, t: ViewTransform) {
        val r = t.dp(7f)
        path.rewind()
        path.moveTo(s.x, s.y - r); path.lineTo(s.x + r, s.y); path.lineTo(s.x, s.y + r); path.lineTo(s.x - r, s.y); path.close()
        fill.color = ACCENT
        canvas.drawPath(path, fill)
        line.color = -1
        line.strokeWidth = t.dp(1.5f)
        canvas.drawPath(path, line)
        line.strokeWidth = t.dp(1.75f)
    }
}
