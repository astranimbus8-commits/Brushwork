package com.brushwork.paint.assist

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The symmetry rulers' guides and handles (v1.7 item 18, §3.18; area H): thin dashed lines in
 * the theme accent over the canvas while symmetry is on, with the [SymmetryHandles.HANDLE_DP]
 * handles while the Symmetry tool is active ([editing]). The controller's overlay pass calls
 * [draw] right after the ruler (`EditorController.drawOverlays`); there is no global hook (V30).
 * The guides show under every tool while symmetry is on (the overlay call doesn't say which tool
 * is active), as ibisPaint shows its rulers.
 *
 * - Mirror: the axis. Kaleidoscope n: the n mirror axes. Rotation n: the n spokes.
 * - Array: the grid's lines (every k-th one when they would crowd closer than
 *   [MIN_LINE_GAP_DP] on screen), the origin cell solid while editing.
 * - Perspective array: the edges of the cells the copies land in (as for a stroke starting in
 *   the quad), the quad solid while editing.
 *
 * Lines are clipped to the canvas, then to the visible screen, so their cost follows the screen,
 * not the zoom. Main thread only (shared scratch objects).
 */
object SymmetryGuides {
    private const val ACCENT = 0xFF4DA3FF.toInt()
    private const val GUIDE = 0xE04DA3FF.toInt()
    private const val SHADOW = 0x8C000000.toInt()
    private const val SHADOW_FAINT = 0x59000000
    private const val WHITE = 0xFFFFFFFF.toInt()

    /** Array lines closer than this on screen are thinned out (every k-th line drawn). */
    const val MIN_LINE_GAP_DP = 10f

    /** Safety cap on the array lines drawn per direction. */
    private const val MAX_LINES = 400

    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
    private val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dashed = Path()
    private val solidPath = Path()
    private val values = FloatArray(9)
    private val pts = FloatArray(4)
    private val seg = FloatArray(4)
    private val clip = Rect()
    private var dashDensity = -1f

    /** The perspective guide edges of the last settings drawn (document px; x0 y0 x1 y1 each). */
    private var perspectiveKey: Triple<SymmetrySettings, Int, Int>? = null
    private var perspectiveEdges = FloatArray(0)

    /** Draws `doc.symmetry`'s guides in screen space; [t] maps document -> screen. */
    fun draw(canvas: Canvas, t: ViewTransform, doc: Document, editing: Boolean) {
        val s = doc.symmetry.sanitized()
        if (s.type == SymmetryType.OFF) return
        t.matrix.getValues(values)
        val zoom = hypot(values[Matrix.MSCALE_X], values[Matrix.MSKEW_Y])
        if (!(zoom > 1e-6f) || !canvas.getClipBounds(clip)) return
        val w = doc.width
        val h = doc.height
        if (w <= 0 || h <= 0) return
        setUpPaints(t)
        dashed.rewind()
        solidPath.rewind()
        val c = SymmetryMaps.center(s, w, h)
        val far = farthest(c.x, c.y, w, h) + 1f
        when (s.type) {
            SymmetryType.MIRROR -> axis(t, c.x, c.y, s.angleDeg.toDouble(), far, w, h, both = true)
            SymmetryType.KALEIDOSCOPE -> for (k in 0 until s.divisions) axis(t, c.x, c.y, s.angleDeg + 180.0 * k / s.divisions, far, w, h, both = true)
            SymmetryType.ROTATION -> for (k in 0 until s.divisions) axis(t, c.x, c.y, s.angleDeg + 360.0 * k / s.divisions, far, w, h, both = false)
            SymmetryType.ARRAY -> grid(t, s, c.x, c.y, zoom, w, h, editing)
            SymmetryType.PERSPECTIVE_ARRAY -> perspective(t, s, w, h, editing)
            SymmetryType.OFF -> Unit
        }
        canvas.drawPath(dashed, shadow)
        canvas.drawPath(dashed, line)
        if (!solidPath.isEmpty) {
            solid.color = SHADOW; solid.strokeWidth = t.dp(3f)
            canvas.drawPath(solidPath, solid)
            solid.color = GUIDE; solid.strokeWidth = t.dp(1.5f)
            canvas.drawPath(solidPath, solid)
        }
        if (editing) drawHandles(canvas, t, s, w, h, zoom) else if (s.type != SymmetryType.PERSPECTIVE_ARRAY) centerMark(canvas, t, c.x, c.y)
    }

    private fun setUpPaints(t: ViewTransform) {
        shadow.color = SHADOW_FAINT
        shadow.strokeWidth = t.dp(2.75f)
        line.color = GUIDE
        line.strokeWidth = t.dp(1.25f)
        if (t.density != dashDensity) {
            dashDensity = t.density
            val effect = DashPathEffect(floatArrayOf(t.dp(6f), t.dp(4f)), 0f)
            shadow.pathEffect = effect
            line.pathEffect = effect
        }
    }

    /** Distance from ([x], [y]) to the farthest canvas corner. */
    private fun farthest(x: Float, y: Float, w: Int, h: Int): Float =
        hypot(max(abs(x), abs(w - x)), max(abs(y), abs(h - y)))

    /** The line through ([cx], [cy]) at [deg] ([both] ways, else the half-line), [len] long each way. */
    private fun axis(t: ViewTransform, cx: Float, cy: Float, deg: Double, len: Float, w: Int, h: Int, both: Boolean) {
        val a = Math.toRadians(deg)
        val dx = (cos(a) * len).toFloat()
        val dy = (sin(a) * len).toFloat()
        if (both) segment(t, dashed, cx - dx, cy - dy, cx + dx, cy + dy, w, h)
        else segment(t, dashed, cx, cy, cx + dx, cy + dy, w, h)
    }

    /** The array's grid lines over the canvas: through the origin + i·u (along v) and + j·v (along u). */
    private fun grid(t: ViewTransform, s: SymmetrySettings, ox: Float, oy: Float, zoom: Float, w: Int, h: Int, editing: Boolean) {
        val b = SymmetryMaps.arrayBasis(s)
        val ux = b[0]; val uy = b[1]; val vx = b[2]; val vy = b[3]
        val det = ux * vy - uy * vx
        if (!det.isFinite() || abs(det) < 1e-9) return
        // The canvas corners in grid coordinates (α along u, β along v).
        var aLo = Double.MAX_VALUE; var aHi = -Double.MAX_VALUE; var bLo = Double.MAX_VALUE; var bHi = -Double.MAX_VALUE
        for (k in 0 until 4) {
            val x = (if (k == 1 || k == 2) w else 0) - ox.toDouble()
            val y = (if (k >= 2) h else 0) - oy.toDouble()
            val al = (x * vy - y * vx) / det
            val be = (ux * y - uy * x) / det
            aLo = min(aLo, al); aHi = max(aHi, al); bLo = min(bLo, be); bHi = max(bHi, be)
        }
        // On screen the lines along v are |det| / |v| apart (and those along u |det| / |u|).
        val gapI = abs(det) / hypot(vx, vy) * zoom
        val gapJ = abs(det) / hypot(ux, uy) * zoom
        val minGap = t.dp(MIN_LINE_GAP_DP).toDouble()
        val stepI = step(gapI, minGap, aHi - aLo)
        val stepJ = step(gapJ, minGap, bHi - bLo)
        if (stepI > 0) {
            var i = ceil(aLo / stepI).toLong() * stepI
            while (i <= aHi) {
                val x0 = ox + i * ux + bLo * vx; val y0 = oy + i * uy + bLo * vy
                val x1 = ox + i * ux + bHi * vx; val y1 = oy + i * uy + bHi * vy
                segment(t, dashed, x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat(), w, h)
                i += stepI
            }
        }
        if (stepJ > 0) {
            var j = ceil(bLo / stepJ).toLong() * stepJ
            while (j <= bHi) {
                val x0 = ox + aLo * ux + j * vx; val y0 = oy + aLo * uy + j * vy
                val x1 = ox + aHi * ux + j * vx; val y1 = oy + aHi * uy + j * vy
                segment(t, dashed, x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat(), w, h)
                j += stepJ
            }
        }
        if (editing) {
            // The origin cell, whose far corners are the spacing handles.
            val p = floatArrayOf(ox, oy, (ox + ux).toFloat(), (oy + uy).toFloat(), (ox + ux + vx).toFloat(), (oy + uy + vy).toFloat(), (ox + vx).toFloat(), (oy + vy).toFloat())
            outline(t, p)
        }
    }

    /** Every how many lines one is drawn: 1 when they are [minGap] apart or more; 0 for none at all. */
    private fun step(gap: Double, minGap: Double, span: Double): Long {
        if (!(gap > 0.0) || !span.isFinite()) return 0
        var k = 1L
        while (gap * k < minGap) k *= 2
        while (span / k > MAX_LINES) k *= 2
        return k
    }

    private fun perspective(t: ViewTransform, s: SymmetrySettings, w: Int, h: Int, editing: Boolean) {
        val key = Triple(s, w, h)
        if (key != perspectiveKey) {
            perspectiveKey = key
            perspectiveEdges = perspectiveEdges(s, w, h)
        }
        val e = perspectiveEdges
        var k = 0
        while (k + 3 < e.size) {
            segment(t, dashed, e[k], e[k + 1], e[k + 2], e[k + 3], w, h)
            k += 4
        }
        if (editing) outline(t, SymmetryMaps.quad(s, w, h).toFloatArray())
    }

    /**
     * The edges (each once) of the perspective cells a stroke starting in the quad is copied
     * to, document px.
     */
    internal fun perspectiveEdges(s: SymmetrySettings, w: Int, h: Int): FloatArray {
        val q = SymmetryMaps.quad(s, w, h)
        val hm = SymmetryMaps.squareToQuad(q) ?: return FloatArray(0)
        val mx = (q[0] + q[2] + q[4] + q[6]) / 4f
        val my = (q[1] + q[3] + q[5] + q[7]) / 4f
        val cells = SymmetryMaps.perspectiveCells(s, w, h, mx, my)
        val seen = HashSet<Long>()
        val out = ArrayList<Float>(cells.size * 8)
        fun edge(i: Int, j: Int, horizontal: Boolean) {
            val key = ((i.toLong() + 0x8000) shl 33) or ((j.toLong() + 0x8000) shl 1) or (if (horizontal) 1L else 0L)
            if (!seen.add(key)) return
            val a = SymmetryMaps.apply(hm, i.toDouble(), j.toDouble()) ?: return
            val b = SymmetryMaps.apply(hm, (if (horizontal) i + 1 else i).toDouble(), (if (horizontal) j else j + 1).toDouble()) ?: return
            out += a[0].toFloat(); out += a[1].toFloat(); out += b[0].toFloat(); out += b[1].toFloat()
        }
        for (cell in cells) {
            val i = cell[0]; val j = cell[1]
            edge(i, j, true); edge(i, j + 1, true)
            edge(i, j, false); edge(i + 1, j, false)
        }
        return out.toFloatArray()
    }

    /** A closed solid outline through document points [p] (x, y pairs), unclipped. */
    private fun outline(t: ViewTransform, p: FloatArray) {
        val n = p.size / 2
        for (k in 0 until n) {
            pts[0] = p[2 * k]; pts[1] = p[2 * k + 1]
            pts[2] = p[2 * ((k + 1) % n)]; pts[3] = p[2 * ((k + 1) % n) + 1]
            t.matrix.mapPoints(pts)
            if (clipTo(pts, clip.left - 8f, clip.top - 8f, clip.right + 8f, clip.bottom + 8f)) {
                solidPath.moveTo(pts[0], pts[1]); solidPath.lineTo(pts[2], pts[3])
            }
        }
    }

    /** Adds the part of document segment ([x0], [y0])–([x1], [y1]) on the canvas and on screen to [path]. */
    private fun segment(t: ViewTransform, path: Path, x0: Float, y0: Float, x1: Float, y1: Float, w: Int, h: Int) {
        seg[0] = x0; seg[1] = y0; seg[2] = x1; seg[3] = y1
        if (!clipTo(seg, 0f, 0f, w.toFloat(), h.toFloat())) return
        t.matrix.mapPoints(seg)
        if (!clipTo(seg, clip.left - 4f, clip.top - 4f, clip.right + 4f, clip.bottom + 4f)) return
        path.moveTo(seg[0], seg[1])
        path.lineTo(seg[2], seg[3])
    }

    /** Clips segment [p] (x0 y0 x1 y1) to the box in place (Liang–Barsky); false when nothing is left. */
    internal fun clipTo(p: FloatArray, l: Float, t: Float, r: Float, b: Float): Boolean {
        val x0 = p[0]; val y0 = p[1]
        val dx = p[2] - x0; val dy = p[3] - y0
        if (!x0.isFinite() || !y0.isFinite() || !dx.isFinite() || !dy.isFinite()) return false
        var lo = 0f
        var hi = 1f
        for (k in 0 until 4) {
            val q = when (k) { 0 -> -dx; 1 -> dx; 2 -> -dy; else -> dy }
            val d = when (k) { 0 -> x0 - l; 1 -> r - x0; 2 -> y0 - t; else -> b - y0 }
            if (q == 0f) {
                if (d < 0f) return false
                continue
            }
            val u = d / q
            if (q < 0f) { if (u > hi) return false; if (u > lo) lo = u } else { if (u < lo) return false; if (u < hi) hi = u }
        }
        if (hi < lo) return false
        p[0] = x0 + lo * dx; p[1] = y0 + lo * dy
        p[2] = x0 + hi * dx; p[3] = y0 + hi * dy
        return true
    }

    private fun centerMark(canvas: Canvas, t: ViewTransform, x: Float, y: Float) {
        pts[0] = x; pts[1] = y
        t.matrix.mapPoints(pts, 0, pts, 0, 1)
        val sx = pts[0]; val sy = pts[1]
        val r = t.dp(4f)
        fill.color = SHADOW; canvas.drawCircle(sx, sy, r + t.dp(1.5f), fill)
        fill.color = ACCENT; canvas.drawCircle(sx, sy, r, fill)
    }

    private fun drawHandles(canvas: Canvas, t: ViewTransform, s: SymmetrySettings, w: Int, h: Int, zoom: Float) {
        val docPerDp = t.dp(1f) / zoom
        val r = t.dp(SymmetryHandles.DRAWN_RADIUS_DP)
        val o = t.dp(1.5f)
        val c = SymmetryMaps.center(s, w, h)
        pts[0] = c.x; pts[1] = c.y
        t.matrix.mapPoints(pts, 0, pts, 0, 1)
        val cx = pts[0]; val cy = pts[1]
        for (handle in SymmetryHandles.of(s.type).asReversed()) {
            val p = SymmetryHandles.position(s, w, h, handle, docPerDp)
            pts[0] = p.x; pts[1] = p.y
            t.matrix.mapPoints(pts, 0, pts, 0, 1)
            val hx = pts[0]; val hy = pts[1]
            when (handle) {
                SymmetryHandle.CENTER -> {
                    fill.color = SHADOW; canvas.drawCircle(hx, hy, r + o, fill)
                    fill.color = WHITE; canvas.drawCircle(hx, hy, r, fill)
                    fill.color = ACCENT; canvas.drawCircle(hx, hy, r - t.dp(3f), fill)
                }
                SymmetryHandle.ANGLE -> {
                    solid.color = SHADOW; solid.strokeWidth = t.dp(3f); canvas.drawLine(cx, cy, hx, hy, solid)
                    solid.color = GUIDE; solid.strokeWidth = t.dp(1.5f); canvas.drawLine(cx, cy, hx, hy, solid)
                    fill.color = SHADOW; canvas.drawCircle(hx, hy, r * 0.8f + o, fill)
                    fill.color = ACCENT; canvas.drawCircle(hx, hy, r * 0.8f, fill)
                    fill.color = WHITE; canvas.drawCircle(hx, hy, r * 0.32f, fill)
                }
                SymmetryHandle.SPACING_X, SymmetryHandle.SPACING_Y -> {
                    val half = r * 0.8f
                    fill.color = SHADOW; canvas.drawRect(hx - half - o, hy - half - o, hx + half + o, hy + half + o, fill)
                    fill.color = WHITE; canvas.drawRect(hx - half, hy - half, hx + half, hy + half, fill)
                    val i = half - t.dp(2.5f)
                    fill.color = ACCENT; canvas.drawRect(hx - i, hy - i, hx + i, hy + i, fill)
                }
                else -> {
                    fill.color = SHADOW; canvas.drawCircle(hx, hy, r * 0.85f + o, fill)
                    fill.color = WHITE; canvas.drawCircle(hx, hy, r * 0.85f, fill)
                    fill.color = ACCENT; canvas.drawCircle(hx, hy, r * 0.85f - t.dp(3f), fill)
                }
            }
        }
    }
}
