package com.brushwork.paint.tools.array

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.model.ArrayLayout
import com.brushwork.paint.model.ArrayMode
import com.brushwork.paint.model.ArraySpec
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Array tool's canvas handles (v1.7 item 3, §3.3 a; area E): where they are, what a finger
 * grabs (a 44 dp target), how a drag changes the spec, and how they are drawn (screen space).
 *
 * - LINE: an arrow from the source's centre to copy 1's; dragging its tip edits the constant
 *   offset (the relative one stays).
 * - CIRCLE: the centre (dragging it fixes it; until then it follows the source).
 * - CURVE: the guide's anchors.
 * - TRANSFORM: the pivot, and an arrow from the source's centre to where copy 1 takes it;
 *   dragging that arrow's tip edits "Move X" / "Move Y" (turn and scale stay).
 *
 * Only the dragged field changes: a Circle centre or Transform pivot left to follow the source
 * is not fixed by another handle's drag. With count 1 the arrows still show (as for count 2).
 */
internal object ArrayHandles {
    enum class Kind { LINE_ARROW, CIRCLE_CENTER, GUIDE_POINT, PIVOT, STEP_ARROW }

    /** A handle at [pos] (document px); [index] is the guide anchor of a [Kind.GUIDE_POINT]. */
    data class Handle(val kind: Kind, val pos: Vec2, val index: Int = -1)

    /** Half the 44 dp touch target (dp). */
    const val GRAB_DP = 22f

    /** The handles of [spec] for a source with bounds [source], in grab priority order. */
    fun handles(spec: ArraySpec, source: RectF): List<Handle> {
        val c = Vec2(source.centerX(), source.centerY())
        return when (spec.mode) {
            ArrayMode.LINE -> listOf(Handle(Kind.LINE_ARROW, firstCopyOf(spec, source, c)))
            ArrayMode.CIRCLE -> ArrayLayout.center(spec, source).let { (x, y) -> listOf(Handle(Kind.CIRCLE_CENTER, Vec2(x, y))) }
            ArrayMode.CURVE -> spec.guide?.anchors?.mapIndexed { i, a -> Handle(Kind.GUIDE_POINT, Vec2(a.x, a.y), i) } ?: emptyList()
            ArrayMode.TRANSFORM -> {
                val (px, py) = ArrayLayout.pivot(spec, source)
                // The step arrow first: with the pivot on the source's centre (the default) a
                // short arrow's tip and the pivot are both within reach; the pivot still moves
                // by grabbing it where the arrow isn't.
                listOf(Handle(Kind.STEP_ARROW, firstCopyOf(spec, source, c)), Handle(Kind.PIVOT, Vec2(px, py)))
            }
        }
    }

    /** The nearest of [handles] to [p] within [radius] (document px; earlier ones win ties), or null. */
    fun hit(handles: List<Handle>, p: Vec2, radius: Float): Handle? {
        var best: Handle? = null
        var bestD = radius
        for (h in handles) {
            val d = h.pos.distanceTo(p)
            if (d <= bestD && (best == null || d < bestD)) { best = h; bestD = d }
        }
        return best
    }

    /**
     * [spec] with handle [h] dragged to [p] (document px), for a source with bounds [source]:
     * only the field that handle shows changes.
     */
    fun dragged(spec: ArraySpec, source: RectF, h: Handle, p: Vec2): ArraySpec {
        if (!p.x.isFinite() || !p.y.isFinite()) return spec
        val c = Vec2(source.centerX(), source.centerY())
        return when (h.kind) {
            Kind.LINE_ARROW -> spec.copy(
                constantX = p.x - c.x - spec.relativeX * source.width(),
                constantY = p.y - c.y - spec.relativeY * source.height(),
            )
            Kind.CIRCLE_CENTER -> spec.copy(centerX = p.x, centerY = p.y)
            Kind.GUIDE_POINT -> spec.guide?.let { spec.copy(guide = GuideEditor.moved(it, h.index, p)) } ?: spec
            Kind.PIVOT -> spec.copy(pivotX = p.x, pivotY = p.y)
            Kind.STEP_ARROW -> {
                // M·c = pivot + move + R·S·(c − pivot): the move that takes c to p.
                val (px, py) = ArrayLayout.pivot(spec, source)
                val t = Math.toRadians(spec.turnDeg.toDouble())
                val vx = (c.x - px).toDouble() * spec.scale
                val vy = (c.y - py).toDouble() * spec.scale
                val rx = cos(t) * vx - sin(t) * vy
                val ry = sin(t) * vx + cos(t) * vy
                spec.copy(moveX = (p.x - px - rx).toFloat(), moveY = (p.y - py - ry).toFloat())
            }
        }.sanitized()
    }

    /** Where copy 1 takes the source's centre [c] (as with count 2 when the count is 1). */
    private fun firstCopyOf(spec: ArraySpec, source: RectF, c: Vec2): Vec2 {
        val ms = ArrayLayout.matrices(if (spec.count < 2) spec.copy(count = 2) else spec, source)
        val m = ms.getOrNull(1) ?: return c
        return Vec2(m[0] * c.x + m[1] * c.y + m[2], m[3] * c.x + m[4] * c.y + m[5])
    }

    // ------------------------------------------------------------------ drawing (screen space)

    private const val ACCENT = 0xFF4DA3FF.toInt()
    private const val DARK = 0xE0202226.toInt()

    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x99000000.toInt(); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = -1; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()

    /**
     * Draws [spec]'s handles for a source with bounds [source] (the guide and the circle as
     * guides; [active] = the handle being dragged, filled).
     */
    fun draw(canvas: Canvas, spec: ArraySpec, source: RectF, t: ViewTransform, active: Handle?) {
        shadow.strokeWidth = t.dp(4f)
        line.strokeWidth = t.dp(1.75f)
        val c = Vec2(source.centerX(), source.centerY())
        val hs = handles(spec, source)
        when (spec.mode) {
            ArrayMode.LINE, ArrayMode.TRANSFORM -> {
                val tip = hs.first { it.kind == Kind.LINE_ARROW || it.kind == Kind.STEP_ARROW }.pos
                drawArrow(canvas, t.docToScreen(c), t.docToScreen(tip), t)
            }
            ArrayMode.CIRCLE -> {
                val ctr = t.docToScreen(hs[0].pos)
                val r = ctr.distanceTo(t.docToScreen(c))
                if (r > 0f && r.isFinite()) dashed { canvas.drawCircle(ctr.x, ctr.y, r, it) }
            }
            ArrayMode.CURVE -> spec.guide?.let { g ->
                val poly = GuideEditor.polyline(g)
                if (poly.size >= 2) {
                    path.rewind()
                    poly.forEachIndexed { i, p -> val s = t.docToScreen(p); if (i == 0) path.moveTo(s.x, s.y) else path.lineTo(s.x, s.y) }
                    canvas.drawPath(path, shadow)
                    canvas.drawPath(path, line)
                }
            }
        }
        for (h in hs) {
            val s = t.docToScreen(h.pos)
            val on = active != null && active.kind == h.kind && active.index == h.index
            when (h.kind) {
                Kind.CIRCLE_CENTER, Kind.PIVOT -> drawCross(canvas, s, on, t)
                else -> drawDot(canvas, s, on, t)
            }
        }
    }

    /** A stroke [points] being drawn as a guide (document px). */
    fun drawStroke(canvas: Canvas, points: List<Vec2>, t: ViewTransform) {
        if (points.size < 2) return
        shadow.strokeWidth = t.dp(4f)
        line.strokeWidth = t.dp(2f)
        path.rewind()
        points.forEachIndexed { i, p -> val s = t.docToScreen(p); if (i == 0) path.moveTo(s.x, s.y) else path.lineTo(s.x, s.y) }
        canvas.drawPath(path, shadow)
        line.color = ACCENT
        canvas.drawPath(path, line)
        line.color = -1
    }

    private inline fun dashed(draw: (Paint) -> Unit) {
        val e = DashPathEffect(floatArrayOf(10f, 8f), 0f)
        shadow.pathEffect = e; line.pathEffect = e
        draw(shadow); draw(line)
        shadow.pathEffect = null; line.pathEffect = null
    }

    private fun drawArrow(canvas: Canvas, a: Vec2, b: Vec2, t: ViewTransform) {
        val len = a.distanceTo(b)
        if (!(len > t.dp(2f))) return
        canvas.drawLine(a.x, a.y, b.x, b.y, shadow)
        canvas.drawLine(a.x, a.y, b.x, b.y, line)
        // The head, short of the tip's dot.
        val ang = atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())
        val back = t.dp(9f)
        val hx = b.x - (cos(ang) * back).toFloat()
        val hy = b.y - (sin(ang) * back).toFloat()
        val wing = t.dp(8f)
        path.rewind()
        for (s in intArrayOf(-1, 1)) {
            val wa = ang + s * Math.toRadians(150.0)
            path.moveTo(hx, hy)
            path.lineTo(hx + (cos(wa) * wing).toFloat(), hy + (sin(wa) * wing).toFloat())
        }
        canvas.drawPath(path, shadow)
        canvas.drawPath(path, line)
    }

    private fun drawDot(canvas: Canvas, s: Vec2, active: Boolean, t: ViewTransform) {
        val r = t.dp(8f)
        fill.color = if (active) ACCENT else DARK
        canvas.drawCircle(s.x, s.y, r, fill)
        line.strokeWidth = t.dp(1.75f)
        canvas.drawCircle(s.x, s.y, r, line)
    }

    private fun drawCross(canvas: Canvas, s: Vec2, active: Boolean, t: ViewTransform) {
        val r = t.dp(10f)
        fill.color = if (active) ACCENT else DARK
        canvas.drawCircle(s.x, s.y, r, fill)
        line.strokeWidth = t.dp(1.75f)
        canvas.drawCircle(s.x, s.y, r, line)
        canvas.drawLine(s.x - r, s.y, s.x + r, s.y, line)
        canvas.drawLine(s.x, s.y - r, s.x, s.y + r, line)
    }
}
