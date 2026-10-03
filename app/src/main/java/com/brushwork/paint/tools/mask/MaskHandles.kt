package com.brushwork.paint.tools.mask

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import com.brushwork.paint.core.Geometry
import com.brushwork.paint.core.IncrementMath
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.masks.BrushMask
import com.brushwork.paint.masks.LinearMask
import com.brushwork.paint.masks.MaskComponent
import com.brushwork.paint.masks.MaskGeometry
import com.brushwork.paint.masks.MaskSpec
import com.brushwork.paint.masks.RadialMask
import com.brushwork.paint.model.IncrementKind
import com.brushwork.paint.snap.Increments
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
     *
     * v1.6 increments (§3.4c, [inc] = `controller.increments`; null or off: exactly v1.5): a pin
     * moves by multiples of the Length step from where the drag began ([pinDelta]); a linear
     * ramp's width (p0–p1) and a radial's radii land on multiples of the Length step (absolute);
     * rotation knobs turn the component to multiples of the Angle step (its absolute angle: the
     * radial's rotation, the linear ramp's direction); the feather lands on multiples of the
     * custom step [FEATHER_KEY] (in %, as its slider shows it).
     */
    fun dragged(start: MaskComponent, k: Kind, from: Vec2, to: Vec2, inc: Increments? = null): MaskComponent {
        val delta = to - from
        val stepped = inc?.enabled == true
        return when (start) {
            is LinearMask -> {
                val u = direction(start)
                when (k) {
                    Kind.PIN -> {
                        val d = pinDelta(from, to, inc)
                        MaskGeometry.transformed(start, MaskGeometry.Affine.translate(d.x, d.y)) ?: start
                    }
                    Kind.LINEAR_START -> {
                        if (u == null) {
                            val d = if (stepped) inc.lengthDelta(delta) else delta
                            return start.copy(x0 = start.x0 + d.x, y0 = start.y0 + d.y)
                        }
                        val len = (Vec2(start.x1, start.y1) - Vec2(start.x0, start.y0)).length
                        var along = delta.dot(u).coerceAtMost(len - 1f)
                        // The width (len − along) on the Length step's multiples.
                        if (stepped) along = len - steppedLength(len - along, inc)
                        start.copy(x0 = start.x0 + u.x * along, y0 = start.y0 + u.y * along)
                    }
                    Kind.LINEAR_END -> {
                        if (u == null) {
                            val d = if (stepped) inc.lengthDelta(delta) else delta
                            return start.copy(x1 = start.x1 + d.x, y1 = start.y1 + d.y)
                        }
                        val len = (Vec2(start.x1, start.y1) - Vec2(start.x0, start.y0)).length
                        var along = delta.dot(u).coerceAtLeast(1f - len)
                        // The width (len + along) on the Length step's multiples.
                        if (stepped) along = steppedLength(len + along, inc) - len
                        start.copy(x1 = start.x1 + u.x * along, y1 = start.y1 + u.y * along)
                    }
                    Kind.LINEAR_ROTATE -> {
                        val m = Vec2((start.x0 + start.x1) / 2f, (start.y0 + start.y1) / 2f)
                        var deg = angleDeg(m, from, to)
                        if (stepped && u != null) {
                            // The ramp's direction (p0 → p1) on the Angle step's multiples.
                            val a0 = Math.toDegrees(atan2(u.y, u.x).toDouble()).toFloat()
                            deg = MaskGeometry.normalizeDegrees(inc.angle(a0 + deg) - a0)
                        }
                        MaskGeometry.transformed(start, MaskGeometry.Affine.similarity(m.x, m.y, 1f, deg)) ?: start
                    }
                    else -> start
                }
            }
            is RadialMask -> {
                val (ux, uy) = axes(start)
                val ctr = Vec2(start.cx, start.cy)
                fun radius(r: Float): Float = if (stepped) steppedLength(r, inc) else max(1f, r)
                when (k) {
                    Kind.PIN -> {
                        val d = pinDelta(from, to, inc)
                        start.copy(cx = start.cx + d.x, cy = start.cy + d.y)
                    }
                    Kind.RX_POS -> start.copy(rx = radius(start.rx + delta.dot(ux)))
                    Kind.RX_NEG -> start.copy(rx = radius(start.rx - delta.dot(ux)))
                    Kind.RY_POS -> start.copy(ry = radius(start.ry + delta.dot(uy)))
                    Kind.RY_NEG -> start.copy(ry = radius(start.ry - delta.dot(uy)))
                    Kind.ROTATE -> {
                        val deg = start.rotationDeg + angleDeg(ctr, from, to)
                        start.copy(rotationDeg = if (stepped) MaskGeometry.normalizeDegrees(inc.angle(deg)) else MaskGeometry.normalizeDegrees(deg))
                    }
                    Kind.FEATHER -> {
                        val d0 = ellipseDistance(start, from); val d1 = ellipseDistance(start, to)
                        val f = (start.feather - (d1 - d0)).coerceIn(0f, 1f)
                        start.copy(feather = steppedFeather(f, inc))
                    }
                    else -> start
                }
            }
            is BrushMask -> if (k == Kind.PIN) {
                val d = pinDelta(from, to, inc)
                MaskGeometry.transformed(start, MaskGeometry.Affine.translate(d.x, d.y)) ?: start
            } else {
                start
            }
        }
    }

    /** Custom increment key of the radial feather (its slider and handle; the step is in %). */
    const val FEATHER_KEY = "mask.feather"

    /** How far a pin dragged from [from] to [to] moves its component: on the Length step's multiples when [inc] is on. */
    fun pinDelta(from: Vec2, to: Vec2, inc: Increments?): Vec2 {
        val d = to - from
        return if (inc?.enabled == true) inc.lengthDelta(d) else d
    }

    /** A length (ramp width, radius) on the Length step's multiples, at least 1 px (and one step when the step is that big). */
    private fun steppedLength(v: Float, inc: Increments): Float {
        val s = inc.lengthAbs(v)
        if (s >= 1f) return s
        val step = inc.step(IncrementKind.LENGTH) ?: 1f
        return max(1f, step)
    }

    /** A feather (0..1) on the custom step [FEATHER_KEY] (in %; 0 and 100 % stay reachable); unchanged without one. */
    fun steppedFeather(f: Float, inc: Increments?): Float {
        val step = inc?.customStep(FEATHER_KEY) ?: return f
        return (IncrementMath.snapInRange(f * 100.0, step.toDouble(), 0.0, 100.0) / 100.0).toFloat()
    }

    /**
     * What a stepped drag of handle [k] shows while it runs (`Increments.readout`, the InfoChip
     * slot): the move ("X +30 px  Y −10 px"), the width or radius ("Width 120 px"), the angle
     * ("45°") or the feather ("Feather 40 %"); null when [inc] is off.
     */
    fun readout(start: MaskComponent, k: Kind, result: MaskComponent, from: Vec2, to: Vec2, inc: Increments?): String? {
        if (inc?.enabled != true) return null
        return when (k) {
            Kind.PIN -> pinDelta(from, to, inc).let { "X ${signed(it.x)} px  Y ${signed(it.y)} px" }
            Kind.LINEAR_START, Kind.LINEAR_END -> (result as? LinearMask)?.let { "Width ${number(Vec2(it.x1 - it.x0, it.y1 - it.y0).length)} px" }
            Kind.LINEAR_ROTATE -> (result as? LinearMask)?.let { direction(it) }?.let { "${number(Math.toDegrees(atan2(it.y, it.x).toDouble()).toFloat())}°" }
            Kind.RX_POS, Kind.RX_NEG -> (result as? RadialMask)?.let { "Radius X ${number(it.rx)} px" }
            Kind.RY_POS, Kind.RY_NEG -> (result as? RadialMask)?.let { "Radius Y ${number(it.ry)} px" }
            Kind.ROTATE -> (result as? RadialMask)?.let { "${number(it.rotationDeg)}°" }
            Kind.FEATHER -> (result as? RadialMask)?.let { "Feather ${number(it.feather * 100f)} %" }
        }
    }

    /**
     * Where a creating drag of a linear ramp from [a] to [b] ends with [inc] (null or off: [b]):
     * the ramp's width on the Length step's multiples (at least one step) and its direction on
     * the Angle step's multiples.
     */
    fun steppedLinearEnd(a: Vec2, b: Vec2, inc: Increments?): Vec2 {
        if (inc?.enabled != true) return b
        val d = b - a
        val len = d.length
        if (!(len > 1e-4f) || !len.isFinite()) return b
        val w = steppedLength(len, inc)
        val deg = inc.angle(Math.toDegrees(atan2(d.y, d.x).toDouble()).toFloat())
        val r = Math.toRadians(deg.toDouble())
        return Vec2(a.x + (cos(r) * w).toFloat(), a.y + (sin(r) * w).toFloat())
    }

    /** The radius of a radial being created by a drag ([r] px) with [inc] (null or off: [r]): on the Length step's multiples. */
    fun steppedRadius(r: Float, inc: Increments?): Float = if (inc?.enabled == true) steppedLength(r, inc) else r

    /** The readout of a stepped creating drag ("Width 120 px  30°", "Radius 80 px"); null when [inc] is off. */
    fun createReadout(c: MaskComponent, inc: Increments?): String? {
        if (inc?.enabled != true) return null
        return when (c) {
            is LinearMask -> direction(c)?.let { u ->
                "Width ${number(Vec2(c.x1 - c.x0, c.y1 - c.y0).length)} px  ${number(Math.toDegrees(atan2(u.y, u.x).toDouble()).toFloat())}°"
            }
            is RadialMask -> "Radius ${number(c.rx)} px"
            is BrushMask -> null
        }
    }

    /** A two-finger gesture on a component after the increments: its move, scale factor and rotation (degrees). */
    data class Pinch(val translation: Vec2, val scale: Float, val rotationDeg: Float)

    /**
     * The two-finger gesture ([translation], [scale], [rotationDeg] since it began) on [comp]
     * stepped by [inc] (null or off: unchanged): the move on the Length step, the scale relative
     * to the gesture start on the Scale step, and the component's absolute angle (a radial's
     * rotation, a linear ramp's direction; a brush part's turn) on the Angle step.
     */
    fun steppedPinch(comp: MaskComponent, translation: Vec2, scale: Float, rotationDeg: Float, inc: Increments?): Pinch {
        if (inc?.enabled != true) return Pinch(translation, scale, rotationDeg)
        val a0 = absoluteAngle(comp)
        val rot = when {
            a0 != null -> MaskGeometry.normalizeDegrees(inc.angle(a0 + rotationDeg) - a0)
            else -> inc.step(IncrementKind.ANGLE)?.let { IncrementMath.snapDelta(rotationDeg, it) } ?: rotationDeg
        }
        return Pinch(inc.lengthDelta(translation), inc.factor(scale), rot)
    }

    /** The readout of a stepped two-finger gesture ("120 %  45°"); null when [inc] is off. */
    fun pinchReadout(start: MaskComponent, p: Pinch, inc: Increments?): String? {
        if (inc?.enabled != true) return null
        val a0 = absoluteAngle(start)
        val angle = if (a0 != null) MaskGeometry.normalizeDegrees(a0 + p.rotationDeg) else p.rotationDeg
        return "${number(p.scale * 100f)} %  ${number(angle)}°"
    }

    /** The angle that turns with [c] (degrees): a radial's rotation, a linear ramp's direction; null for a brush part. */
    private fun absoluteAngle(c: MaskComponent): Float? = when (c) {
        is RadialMask -> c.rotationDeg
        is LinearMask -> direction(c)?.let { Math.toDegrees(atan2(it.y, it.x).toDouble()).toFloat() }
        is BrushMask -> null
    }

    /** [v] with at most one decimal (none when whole). */
    internal fun number(v: Float): String {
        val r = Math.round(v * 10f) / 10f
        return if (r == Math.round(r).toFloat()) Math.round(r).toString() else r.toString()
    }

    /** [v] as [number] with its sign ("+30", "−10", "0"). */
    internal fun signed(v: Float): String {
        val n = number(v)
        return when {
            n == "0" || n == "-0" -> "0"
            v > 0f -> "+$n"
            else -> "−" + n.removePrefix("-")
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
