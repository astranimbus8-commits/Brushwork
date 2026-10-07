package com.brushwork.paint.tools.points

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import com.brushwork.paint.core.Affine2
import com.brushwork.paint.core.IncrementMath
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.model.IncrementSettings
import kotlin.math.abs
import kotlin.math.atan2

/**
 * v1.7 (item 1, design §3.1 and §4.5): the group gizmo of a point editor, shown with two or more
 * points selected. A dashed box around the points (their document bounds, never smaller than
 * [MIN_BOX_DP] on screen: it grows about its centre, so two close points stay grabbable), 8 handles
 * ([HANDLE_DP] drawn, [TOUCH_DP] touch) and a rotate knob [ROTATE_OFFSET_DP] beyond the top edge.
 *
 * Pure geometry plus drawing: the tool owns the gesture. It asks [hit] where a finger went down,
 * then for each event applies [dragMap] (the whole gesture's map, from the start) to the points it
 * captured at the start (`PointEditor.setGroupTransform`). The box's sides are the DOCUMENT axes
 * ("N" is its top edge in the document), drawn wherever the view's rotation puts them; the pivot
 * of every scale and rotation is the box centre.
 */
class PointGizmo {
    enum class Part { NONE, MOVE, SCALE_N, SCALE_S, SCALE_E, SCALE_W, SCALE_NE, SCALE_NW, SCALE_SE, SCALE_SW, ROTATE }

    /**
     * Where the gizmo is: [cornersScreen] NW, NE, SE, SW (the document box's corners on screen),
     * [pivotDoc] the box centre in document px, [rotateHandleScreen] the rotate knob.
     */
    class Layout(val cornersScreen: List<Vec2>, val pivotDoc: Vec2, val rotateHandleScreen: Vec2)

    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = HALO }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = BOX }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private var dashDensity = 0f

    /** Null for fewer than 2 selected points. The box is their bounds, grown about its centre to at least MIN_BOX_DP on screen. */
    fun layout(pointsDoc: List<Vec2>, t: ViewTransform): Layout? {
        var l = Float.POSITIVE_INFINITY
        var top = Float.POSITIVE_INFINITY
        var r = Float.NEGATIVE_INFINITY
        var b = Float.NEGATIVE_INFINITY
        var n = 0
        for (p in pointsDoc) {
            if (!p.x.isFinite() || !p.y.isFinite()) continue
            n++
            if (p.x < l) l = p.x
            if (p.x > r) r = p.x
            if (p.y < top) top = p.y
            if (p.y > b) b = p.y
        }
        if (n < 2) return null
        val cx = (l + r) / 2f
        val cy = (top + b) / 2f
        val minDoc = t.screenToDocLength(t.dp(MIN_BOX_DP))
        val halfW = maxOf((r - l) / 2f, minDoc / 2f)
        val halfH = maxOf((b - top) / 2f, minDoc / 2f)
        val corners = listOf(
            Vec2(cx - halfW, cy - halfH), Vec2(cx + halfW, cy - halfH),
            Vec2(cx + halfW, cy + halfH), Vec2(cx - halfW, cy + halfH),
        ).map { t.docToScreen(it) }
        val center = t.docToScreen(Vec2(cx, cy))
        val topMid = (corners[0] + corners[1]) * 0.5f
        val out = (topMid - center).normalized().let { if (it == Vec2.ZERO) Vec2(0f, -1f) else it }
        return Layout(corners, Vec2(cx, cy), topMid + out * t.dp(ROTATE_OFFSET_DP))
    }

    /** The part under [screen]: the nearest handle (or the knob) within half of TOUCH_DP, else MOVE inside the box, else NONE. */
    fun hit(layout: Layout, screen: Vec2, t: ViewTransform): Part {
        val reach = t.dp(TOUCH_DP) / 2f
        var best = Part.NONE
        var bestD = Float.POSITIVE_INFINITY
        for ((part, at) in handles(layout)) {
            val dd = at.distanceTo(screen)
            if (dd <= reach && dd < bestD) { best = part; bestD = dd }
        }
        if (best != Part.NONE) return best
        return if (insideQuad(layout.cornersScreen, screen)) Part.MOVE else Part.NONE
    }

    /** Corners scale proportionally (freely when [keepProportions] is false), edges one axis, MOVE translates, ROTATE turns
     *  about the pivot; with [steps] non-null, the Length, Scale and Angle steps apply. */
    fun dragMap(layout: Layout, part: Part, startDoc: Vec2, nowDoc: Vec2, keepProportions: Boolean, steps: IncrementSettings?): Affine2 {
        if (!startDoc.x.isFinite() || !startDoc.y.isFinite() || !nowDoc.x.isFinite() || !nowDoc.y.isFinite()) return Affine2.IDENTITY
        val stepped = steps?.takeIf { it.enabled }
        val p = layout.pivotDoc
        val s = startDoc - p
        val n = nowDoc - p
        return when (part) {
            Part.NONE -> Affine2.IDENTITY
            Part.MOVE -> {
                var d = nowDoc - startDoc
                if (stepped != null) d = Vec2(IncrementMath.snapDelta(d.x, stepped.lengthPx), IncrementMath.snapDelta(d.y, stepped.lengthPx))
                Affine2.translate(d.x, d.y)
            }
            Part.ROTATE -> {
                var deg = Math.toDegrees(atan2(s.cross(n).toDouble(), s.dot(n).toDouble())).toFloat()
                if (s.lengthSq <= MIN_REACH_SQ || n.lengthSq <= MIN_REACH_SQ) deg = 0f
                if (stepped != null) deg = IncrementMath.snapAngle(deg, stepped.angleDeg)
                if (deg == 0f) Affine2.IDENTITY else Affine2.rotateAbout(p, deg)
            }
            Part.SCALE_N, Part.SCALE_S -> scale(p, 1f, axisFactor(s.y, n.y), stepped)
            Part.SCALE_E, Part.SCALE_W -> scale(p, axisFactor(s.x, n.x), 1f, stepped)
            Part.SCALE_NE, Part.SCALE_NW, Part.SCALE_SE, Part.SCALE_SW -> if (keepProportions) {
                // Proportional: the finger's progress along the line from the pivot through its start.
                val k = if (s.lengthSq <= MIN_REACH_SQ) 1f else n.dot(s) / s.lengthSq
                scale(p, k, k, stepped)
            } else {
                scale(p, axisFactor(s.x, n.x), axisFactor(s.y, n.y), stepped)
            }
        }
    }

    /** Draws the box, its handles and the knob ([active] highlighted) in screen space. */
    fun draw(canvas: Canvas, layout: Layout, t: ViewTransform, active: Part) {
        val c = layout.cornersScreen
        if (c.size != 4) return
        if (dashDensity != t.density) {
            dashDensity = t.density
            dash.pathEffect = DashPathEffect(floatArrayOf(t.dp(6f), t.dp(4f)), 0f)
        }
        path.rewind()
        path.moveTo(c[0].x, c[0].y)
        for (i in 1..3) path.lineTo(c[i].x, c[i].y)
        path.close()
        halo.strokeWidth = t.dp(3f)
        dash.strokeWidth = t.dp(1.5f)
        canvas.drawPath(path, halo)
        canvas.drawPath(path, dash)
        // The knob's stem from the top edge's middle.
        val topMid = (c[0] + c[1]) * 0.5f
        val k = layout.rotateHandleScreen
        canvas.drawLine(topMid.x, topMid.y, k.x, k.y, halo)
        line.strokeWidth = t.dp(1.5f)
        line.color = BOX
        canvas.drawLine(topMid.x, topMid.y, k.x, k.y, line)
        val half = t.dp(HANDLE_DP) / 2f
        for ((part, at) in handles(layout)) {
            val on = part == active
            if (part == Part.ROTATE) {
                fill.color = HALO
                canvas.drawCircle(at.x, at.y, half + t.dp(1.5f), fill)
                fill.color = if (on) ACCENT else BOX
                canvas.drawCircle(at.x, at.y, half, fill)
                // A small circular arrow marks the knob.
                line.color = if (on) BOX else ACCENT
                val ir = half * 0.55f
                canvas.drawArc(at.x - ir, at.y - ir, at.x + ir, at.y + ir, -60f, 270f, false, line)
            } else {
                fill.color = HALO
                canvas.drawRect(at.x - half - t.dp(1f), at.y - half - t.dp(1f), at.x + half + t.dp(1f), at.y + half + t.dp(1f), fill)
                fill.color = if (on) ACCENT else BOX
                canvas.drawRect(at.x - half, at.y - half, at.x + half, at.y + half, fill)
                line.color = ACCENT
                canvas.drawRect(at.x - half, at.y - half, at.x + half, at.y + half, line)
            }
        }
    }

    /** The 8 handles and the knob on screen, knob first (it wins ties: it lies outside the box). */
    private fun handles(layout: Layout): List<Pair<Part, Vec2>> {
        val c = layout.cornersScreen
        if (c.size != 4) return emptyList()
        fun mid(a: Vec2, b: Vec2) = (a + b) * 0.5f
        return listOf(
            Part.ROTATE to layout.rotateHandleScreen,
            Part.SCALE_NW to c[0], Part.SCALE_NE to c[1], Part.SCALE_SE to c[2], Part.SCALE_SW to c[3],
            Part.SCALE_N to mid(c[0], c[1]), Part.SCALE_E to mid(c[1], c[2]),
            Part.SCALE_S to mid(c[2], c[3]), Part.SCALE_W to mid(c[3], c[0]),
        )
    }

    private fun insideQuad(c: List<Vec2>, p: Vec2): Boolean {
        if (c.size != 4) return false
        // Convex quad (any winding): p is on the same side of every edge.
        var sign = 0
        for (i in 0 until 4) {
            val a = c[i]
            val b = c[(i + 1) % 4]
            val cr = (b - a).cross(p - a)
            if (cr == 0f) continue
            val sg = if (cr > 0f) 1 else -1
            if (sign == 0) sign = sg else if (sg != sign) return false
        }
        return true
    }

    /** The factor along one axis: the finger's offset from the pivot now over at the start (1 when it started on the pivot). */
    private fun axisFactor(start: Float, now: Float): Float =
        if (abs(start) <= MIN_REACH) 1f else (now / start).takeIf { it.isFinite() } ?: 1f

    private fun scale(p: Vec2, kx: Float, ky: Float, stepped: IncrementSettings?): Affine2 {
        var sx = kx
        var sy = ky
        if (stepped != null) {
            sx = snapSigned(sx, stepped.scalePercent)
            sy = snapSigned(sy, stepped.scalePercent)
        }
        return if (sx == 1f && sy == 1f) Affine2.IDENTITY else Affine2.scaleAbout(p, sx, sy)
    }

    /** A factor on the Scale step's multiples; a mirrored (negative) factor snaps |k| and keeps its sign. 1 stays 1. */
    private fun snapSigned(k: Float, stepPercent: Float): Float =
        if (k == 1f) 1f else if (k < 0f) -IncrementMath.snapFactor(-k, stepPercent) else IncrementMath.snapFactor(k, stepPercent)

    companion object {
        const val HANDLE_DP = 12f
        const val TOUCH_DP = 44f
        const val ROTATE_OFFSET_DP = 36f
        const val MIN_BOX_DP = 56f

        private const val MIN_REACH = 1e-4f
        private const val MIN_REACH_SQ = MIN_REACH * MIN_REACH
        private const val ACCENT = 0xFF4DA3FF.toInt()
        private const val BOX = 0xFFFFFFFF.toInt()
        private const val HALO = 0x99000000.toInt()
    }
}
