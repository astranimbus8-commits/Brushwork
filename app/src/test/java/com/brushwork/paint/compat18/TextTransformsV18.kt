package com.brushwork.paint.compat18

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * v1.9 F0: a FROZEN copy of v1.8's Transform data lift of a text layer (`tools/text/
 * TextTransforms.kt`, with the parts of `TextPathGeometry.kt` (`sanitized`, `transformed`,
 * `normalizeDegrees`) and `frames/FrameGeometry.kt` (`storyLook`) it calls, at 600582d9), in test
 * sources only. It is what v1.8's Transform tool does to a v1.9 text layer: it decodes the layer
 * with [TextCodecV18] (the first text only, `more` dropped), maps that text and encodes it again
 * (design 5.2, the Transform row). Never change it.
 */
object TextTransformsV18 {
    /** v1.8's `TextTransforms.canMap`. */
    fun canMap(m: FloatArray): Boolean = similarity(m) != null

    /**
     * v1.8's `TextTransforms.mapped(textData, m)`: the text layer data mapped by [m] as v1.8
     * writes it, or null when v1.8 can't map it; [textData] itself for the identity.
     */
    fun mapped(textData: String, m: FloatArray): String? {
        val sim = similarity(m) ?: return null
        val item = TextCodecV18.decode(textData) ?: return null
        if (sim.isIdentity) return textData
        val next = mapped(item, sim) ?: return null
        return TextCodecV18.encode(next)
    }

    /** v1.8's `TextTransforms.mapped(item, m)`. */
    fun mapped(item: TextItemV18, m: FloatArray): TextItemV18? = similarity(m)?.let { mapped(item, it) }

    private class Similarity(
        val a: Double, val b: Double, val c: Double,
        val d: Double, val e: Double, val f: Double,
        val scale: Double, val degrees: Double,
    ) {
        val isIdentity: Boolean get() = a == 1.0 && b == 0.0 && c == 0.0 && d == 0.0 && e == 1.0 && f == 0.0

        fun x(px: Double, py: Double): Double = a * px + b * py + c
        fun y(px: Double, py: Double): Double = d * px + e * py + f
    }

    private fun similarity(m: FloatArray): Similarity? {
        if (m.size != 9 || m.any { !it.isFinite() }) return null
        if (abs(m[6]) > EPS || abs(m[7]) > EPS || abs(m[8] - 1f) > EPS) return null
        val a = m[0].toDouble(); val b = m[1].toDouble(); val c = m[2].toDouble()
        val d = m[3].toDouble(); val e = m[4].toDouble(); val f = m[5].toDouble()
        val sx = sqrt(a * a + d * d)
        val sy = sqrt(b * b + e * e)
        if (!(sx > MIN_SCALE) || !(sy > MIN_SCALE)) return null
        if (abs(sx - sy) > TOLERANCE * sx) return null
        if (abs(a * b + d * e) > TOLERANCE * sx * sy) return null
        if (a * e - b * d <= 0.0) return null
        val scale = ((sx + sy) / 2.0).let { if (abs(it - 1.0) <= UNIT_SCALE_EPS) 1.0 else it }
        return Similarity(a, b, c, d, e, f, scale, Math.toDegrees(atan2(d, a)))
    }

    private fun mapped(item: TextItemV18, m: Similarity): TextItemV18? {
        val k = m.scale.toFloat()
        val turn = m.degrees.toFloat()
        if (item.thread.isOn && abs(TextItemV18.normalizeDegrees(turn)) > FRAME_TURN_EPS) return null
        val spec = item.spec.scaled(k)
        if (!(spec.sizePx >= TextSpecV18.MIN_SIZE_PX && spec.sizePx <= TextSpecV18.MAX_SIZE_PX)) return null
        val b = spec.box
        if (maxOf(spec.strokeWidthPx, b.width, b.height, b.padding, b.borderWidth, maxOf(b.minHeight, b.minWidth)) > TextSpecV18.MAX_LENGTH_PX) return null
        val cx = m.x(item.cx.toDouble(), item.cy.toDouble()).toFloat()
        val cy = m.y(item.cx.toDouble(), item.cy.toDouble()).toFloat()
        if (!cx.isFinite() || !cy.isFinite()) return null
        val path = if (item.path.isActive) {
            transformedPath(item.path, cx - item.cx, cy - item.cy, k, turn, item.cx, item.cy)
        } else item.path
        val wrap = if (item.wrap.gapPx == 0f) item.wrap else item.wrap.copy(gapPx = (item.wrap.gapPx * k).coerceAtMost(TextWrapSpecV18.MAX_GAP_PX))
        val thread = if (item.thread.isOn && storyLook(spec) != storyLook(item.spec)) {
            item.thread.copy(rev = item.thread.rev + 1)
        } else item.thread
        return item.copy(
            spec = spec,
            cx = cx,
            cy = cy,
            rotationDeg = if (item.thread.isOn) item.rotationDeg else TextItemV18.normalizeDegrees(item.rotationDeg + turn),
            path = path,
            wrap = wrap,
            thread = thread,
        )
    }

    /** v1.8's `FrameGeometry.storyLook`. */
    private fun storyLook(spec: TextSpecV18): TextSpecV18 =
        spec.copy(box = spec.box.copy(width = 0f, minHeight = 0f, height = 0f, minWidth = 0f), vertical = false)

    /**
     * v1.8's `TextPathGeometry.transformed` (through `TextOnPath.transformed`): [spec] moved by
     * ([tx0], [ty0]), scaled by [scale] and rotated by [rotationDeg] around ([pivotX], [pivotY]).
     */
    private fun transformedPath(
        spec: TextPathSpecV18, tx0: Float, ty0: Float, scale: Float, rotationDeg: Float, pivotX: Float, pivotY: Float,
    ): TextPathSpecV18 {
        val s = sanitizedPath(spec)
        val pivotOk = pivotX.isFinite() && pivotY.isFinite()
        val k = if (pivotOk && scale.isFinite() && scale > 0f) scale.toDouble() else 1.0
        val deg = if (pivotOk && rotationDeg.isFinite()) rotationDeg else 0f
        val tx = if (tx0.isFinite()) tx0.toDouble() else 0.0
        val ty = if (ty0.isFinite()) ty0.toDouble() else 0.0
        val px = if (pivotOk) pivotX.toDouble() else 0.0
        val py = if (pivotOk) pivotY.toDouble() else 0.0
        val rad = Math.toRadians(deg.toDouble())
        val cos = cos(rad)
        val sin = sin(rad)
        fun lim(v: Double): Float = v.coerceIn(-MAX_COORD.toDouble(), MAX_COORD.toDouble()).toFloat()
        fun mapX(x: Float, y: Float): Float = lim(px + ((x - px) * cos - (y - py) * sin) * k + tx)
        fun mapY(x: Float, y: Float): Float = lim(py + ((x - px) * sin + (y - py) * cos) * k + ty)
        return sanitizedPath(
            s.copy(
                x1 = mapX(s.x1, s.y1), y1 = mapY(s.x1, s.y1), x2 = mapX(s.x2, s.y2), y2 = mapY(s.x2, s.y2),
                cx1 = mapX(s.cx1, s.cy1), cy1 = mapY(s.cx1, s.cy1), cx2 = mapX(s.cx2, s.cy2), cy2 = mapY(s.cx2, s.cy2),
                cx = mapX(s.cx, s.cy), cy = mapY(s.cx, s.cy),
                radius = lim(s.radius * k),
                width = lim(s.width * k),
                height = lim(s.height * k),
                cornerRadius = lim(s.cornerRadius * k),
                rotationDeg = normalizePathDegrees(s.rotationDeg + deg),
                startAngleDeg = if (s.type == TextPathTypeV18.RECT) s.startAngleDeg else normalizePathDegrees(s.startAngleDeg + deg),
                offset = lim(s.offset * k),
                baselineShift = lim(s.baselineShift * k),
            )
        )
    }

    /** v1.8's `TextPathGeometry.sanitized`. */
    private fun sanitizedPath(spec: TextPathSpecV18): TextPathSpecV18 {
        if (isClean(spec)) return spec
        val d = PATH_DEFAULTS
        return spec.copy(
            x1 = coord(spec.x1, d.x1), y1 = coord(spec.y1, d.y1), x2 = coord(spec.x2, d.x2), y2 = coord(spec.y2, d.y2),
            cx1 = coord(spec.cx1, d.cx1), cy1 = coord(spec.cy1, d.cy1), cx2 = coord(spec.cx2, d.cx2), cy2 = coord(spec.cy2, d.cy2),
            cx = coord(spec.cx, d.cx), cy = coord(spec.cy, d.cy),
            radius = coord(spec.radius, d.radius),
            width = coord(spec.width, d.width), height = coord(spec.height, d.height),
            cornerRadius = coord(spec.cornerRadius, d.cornerRadius),
            startAngleDeg = angle(spec.startAngleDeg, d.startAngleDeg),
            rotationDeg = angle(spec.rotationDeg, d.rotationDeg),
            offset = coord(spec.offset, d.offset),
            baselineShift = coord(spec.baselineShift, d.baselineShift),
        )
    }

    private fun coordOk(v: Float) = v >= -MAX_COORD && v <= MAX_COORD
    private fun angleOk(v: Float) = v >= -360f && v <= 360f
    private fun coord(v: Float, default: Float) = if (v.isFinite()) v.coerceIn(-MAX_COORD, MAX_COORD) else default
    private fun angle(v: Float, default: Float) = if (!v.isFinite()) default else if (angleOk(v)) v else normalizePathDegrees(v)

    private fun isClean(s: TextPathSpecV18): Boolean =
        coordOk(s.x1) && coordOk(s.y1) && coordOk(s.x2) && coordOk(s.y2) &&
            coordOk(s.cx1) && coordOk(s.cy1) && coordOk(s.cx2) && coordOk(s.cy2) &&
            coordOk(s.cx) && coordOk(s.cy) && coordOk(s.radius) && coordOk(s.width) && coordOk(s.height) &&
            coordOk(s.cornerRadius) && coordOk(s.offset) && coordOk(s.baselineShift) &&
            angleOk(s.startAngleDeg) && angleOk(s.rotationDeg)

    /** v1.8's `TextPathGeometry.normalizeDegrees`: (-180, 180], 0 for a non-finite angle. */
    private fun normalizePathDegrees(deg: Float): Float {
        if (!deg.isFinite()) return 0f
        var d = deg % 360f
        if (d <= -180f) d += 360f
        if (d > 180f) d -= 360f
        return d
    }

    /** v1.8's `TextPathGeometry.MAX_COORD`. */
    private const val MAX_COORD = 1e6f

    private val PATH_DEFAULTS = TextPathSpecV18()

    private const val EPS = 1e-6f
    private const val TOLERANCE = 1e-4
    private const val MIN_SCALE = 1e-6
    private const val UNIT_SCALE_EPS = 1e-6
    private const val FRAME_TURN_EPS = 0.01f
}
