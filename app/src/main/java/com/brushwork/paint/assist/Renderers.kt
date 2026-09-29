package com.brushwork.paint.assist

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.brushwork.paint.core.Geometry
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.GridSettings
import com.brushwork.paint.model.GridType
import com.brushwork.paint.model.RulerSettings
import com.brushwork.paint.model.RulerType
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Reusable float buffer for batched drawLines calls (grows, never shrinks). */
private class LineBuffer(initial: Int = 512) {
    var data = FloatArray(initial)
        private set
    var size = 0
        private set

    val lines: Int get() = size / 4

    fun clear() { size = 0 }

    fun add(x0: Float, y0: Float, x1: Float, y1: Float) {
        if (size + 4 > data.size) data = data.copyOf(data.size * 2)
        data[size] = x0; data[size + 1] = y0; data[size + 2] = x1; data[size + 3] = y1
        size += 4
    }

    fun draw(canvas: Canvas, paint: Paint) { if (size > 0) canvas.drawLines(data, 0, size, paint) }
}

/** Screen-space grid overlay. Main thread only (shares scratch buffers). */
object GridRenderer {
    /** Lines closer than this on screen (px) are skipped (level of detail). */
    const val MIN_SCREEN_SPACING = 6f
    private const val MINOR_ALPHA = 0.5f
    private val SQRT3_2 = (sqrt(3.0) / 2.0).toFloat()

    private val paint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 0f }
    private val values = FloatArray(9)
    private val clip = Rect()
    private val visible = RectF()
    private val buffer = LineBuffer()

    /** Draws the grid overlay in screen space (t maps doc -> screen). */
    fun draw(canvas: Canvas, t: ViewTransform, doc: Document, grid: GridSettings) {
        val w = doc.width.toFloat(); val h = doc.height.toFloat()
        if (w <= 0f || h <= 0f || grid.opacity <= 0f) return
        t.matrix.getValues(values)
        val zoom = hypot(values[Matrix.MSCALE_X], values[Matrix.MSKEW_Y])
        if (zoom < 1e-6f) return
        if (!canvas.getClipBounds(clip)) return
        visible.set(clip)
        t.inverse.mapRect(visible)
        if (!visible.intersect(0f, 0f, w, h)) return
        // Crisp 1px lines when the view is axis-aligned, anti-aliased when rotated.
        paint.isAntiAlias = abs(values[Matrix.MSKEW_X]) > 1e-4f && abs(values[Matrix.MSCALE_X]) > 1e-4f
        val alpha = grid.opacity.coerceIn(0f, 1f)
        val saved = canvas.save()
        canvas.concat(t.matrix)
        canvas.clipRect(0f, 0f, w, h)
        // Hairlines (strokeWidth 0) are exactly 1 screen px whatever the matrix.
        when (grid.type) {
            GridType.SQUARE -> if (grid.spacingPx > 0f) {
                family(canvas, 1f, 0f, grid.spacingPx, grid.offsetXPx, grid, zoom, alpha)
                family(canvas, 0f, 1f, grid.spacingPx, grid.offsetYPx, grid, zoom, alpha)
            }
            GridType.ISOMETRIC -> if (grid.spacingPx > 0f) {
                // Triangles with side = spacing: vertical lines plus lines at +-30 degrees.
                val sp = grid.spacingPx * SQRT3_2
                val ox = grid.offsetXPx; val oy = grid.offsetYPx
                family(canvas, 1f, 0f, sp, ox, grid, zoom, alpha)
                family(canvas, -0.5f, SQRT3_2, sp, -0.5f * ox + SQRT3_2 * oy, grid, zoom, alpha)
                family(canvas, 0.5f, SQRT3_2, sp, 0.5f * ox + SQRT3_2 * oy, grid, zoom, alpha)
            }
            GridType.RULE_OF_THIRDS -> {
                buffer.clear()
                buffer.add(w / 3f, 0f, w / 3f, h)
                buffer.add(w * 2f / 3f, 0f, w * 2f / 3f, h)
                buffer.add(0f, h / 3f, w, h / 3f)
                buffer.add(0f, h * 2f / 3f, w, h * 2f / 3f)
                flush(canvas, grid.color, alpha)
            }
            GridType.DIAGONAL -> {
                buffer.clear()
                buffer.add(0f, 0f, w, h)
                buffer.add(w, 0f, 0f, h)
                buffer.add(w / 2f, 0f, w / 2f, h)
                buffer.add(0f, h / 2f, w, h / 2f)
                flush(canvas, grid.color, alpha)
            }
        }
        canvas.restoreToCount(saved)
    }

    /**
     * Draws the parallel lines n·p = c0 + j * spacing (n = unit normal) that cross the visible
     * part of the canvas. Minor lines are skipped when denser than [MIN_SCREEN_SPACING]; major
     * lines (or all lines without majors) are thinned by powers of two until they are not.
     */
    private fun family(canvas: Canvas, nx: Float, ny: Float, spacing: Float, c0: Float, grid: GridSettings, zoom: Float, alpha: Float) {
        val screen = spacing * zoom
        if (screen <= 0f || screen.isNaN()) return
        val v = visible
        val a = nx * v.left + ny * v.top
        val b = nx * v.right + ny * v.top
        val c = nx * v.left + ny * v.bottom
        val d = nx * v.right + ny * v.bottom
        val lo = min(min(a, b), min(c, d))
        val hi = max(max(a, b), max(c, d))
        val jLo = ceil((lo - c0) / spacing).toLong()
        val jHi = floor((hi - c0) / spacing).toLong()
        if (jHi < jLo) return
        val major = grid.majorEvery
        if (major >= 2) {
            if (screen >= MIN_SCREEN_SPACING) {
                buffer.clear()
                var j = jLo
                while (j <= jHi) {
                    if (Math.floorMod(j, major.toLong()) != 0L) addLine(nx, ny, c0 + j * spacing)
                    j++
                }
                flush(canvas, grid.color, alpha * MINOR_ALPHA)
            }
            addEvery(nx, ny, spacing, c0, jLo, jHi, coarsen(screen, major.toLong()))
            flush(canvas, grid.color, alpha)
        } else {
            addEvery(nx, ny, spacing, c0, jLo, jHi, coarsen(screen, 1L))
            flush(canvas, grid.color, alpha)
        }
    }

    /** Smallest multiple base * 2^k whose on-screen spacing is at least [MIN_SCREEN_SPACING]. */
    private fun coarsen(screen: Float, base: Long): Long {
        var step = base
        while (screen * step < MIN_SCREEN_SPACING && step < (1L shl 40)) step *= 2
        return step
    }

    private fun addEvery(nx: Float, ny: Float, spacing: Float, c0: Float, jLo: Long, jHi: Long, step: Long) {
        buffer.clear()
        var j = -Math.floorDiv(-jLo, step) * step // first multiple of step >= jLo
        while (j <= jHi) {
            addLine(nx, ny, c0 + j * spacing)
            j += step
        }
    }

    /** Adds the segment of the line n·p = [value] that spans the visible rect. */
    private fun addLine(nx: Float, ny: Float, value: Float) {
        val v = visible
        val mx = v.centerX(); val my = v.centerY()
        val off = value - (nx * mx + ny * my)
        val px = mx + nx * off; val py = my + ny * off
        val half = hypot(v.width(), v.height()) * 0.5f + 1f
        val dx = -ny * half; val dy = nx * half
        buffer.add(px - dx, py - dy, px + dx, py + dy)
    }

    private fun flush(canvas: Canvas, color: Int, alpha: Float) {
        if (buffer.size == 0) return
        paint.color = (color and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255f).roundToInt() shl 24)
        buffer.draw(canvas, paint)
        buffer.clear()
    }
}

/** Screen-space ruler guide and (while editing) its handles. Main thread only. */
object RulerRenderer {
    private const val ACCENT = 0xFF4DA3FF.toInt()
    private const val GUIDE = 0xE04DA3FF.toInt()
    private const val GUIDE_FAINT = 0xA04DA3FF.toInt()
    private const val SHADOW = 0x8C000000.toInt()
    private const val SHADOW_FAINT = 0x59000000

    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val values = FloatArray(9)
    private val pts = FloatArray(4)
    private val clip = Rect()
    private val oval = RectF()
    private val buffer = LineBuffer()

    /** Draws the ruler guide in screen space; [editing] = ruler tool active (show handles). */
    fun draw(canvas: Canvas, t: ViewTransform, doc: Document, ruler: RulerSettings, editing: Boolean) {
        t.matrix.getValues(values)
        val zoom = hypot(values[Matrix.MSCALE_X], values[Matrix.MSKEW_Y])
        if (zoom < 1e-6f) return
        if (!canvas.getClipBounds(clip)) return
        val cx = if (ruler.centerX < 0f) doc.width / 2f else ruler.centerX
        val cy = if (ruler.centerY < 0f) doc.height / 2f else ruler.centerY
        val r = if (cx == ruler.centerX && cy == ruler.centerY) ruler else ruler.copy(centerX = cx, centerY = cy)
        mapPoint(t, cx, cy)
        val sx = pts[0]; val sy = pts[1]
        when (r.type) {
            RulerType.STRAIGHT -> drawStraight(canvas, t, r, sx, sy, zoom)
            RulerType.CIRCLE -> drawOval(canvas, t, r, r.radius, r.radius, 0f, zoom)
            RulerType.ELLIPSE -> drawOval(canvas, t, r, r.radiusX, r.radiusY, r.angleDeg, zoom)
            RulerType.RADIAL -> drawRadial(canvas, t, r, sx, sy)
        }
        if (editing) drawHandles(canvas, t, r, zoom) else drawCenterMark(canvas, t, sx, sy)
    }

    private fun mapPoint(t: ViewTransform, x: Float, y: Float) {
        pts[0] = x; pts[1] = y
        t.matrix.mapPoints(pts, 0, pts, 0, 1)
    }

    /** Screen direction (unit, into pts[2..3]) of the document direction at [deg]. */
    private fun mapDirection(t: ViewTransform, deg: Float) {
        val a = deg * Geometry.DEG
        pts[2] = cos(a); pts[3] = sin(a)
        t.matrix.mapVectors(pts, 2, pts, 2, 1)
        val l = hypot(pts[2], pts[3]).coerceAtLeast(1e-9f)
        pts[2] /= l; pts[3] /= l
    }

    /** Distance from (x, y) to the farthest corner of the visible screen area, plus a margin. */
    private fun reach(x: Float, y: Float): Float {
        val dx = max(abs(clip.left - x), abs(clip.right - x))
        val dy = max(abs(clip.top - y), abs(clip.bottom - y))
        return hypot(dx, dy) + 4f
    }

    private fun stroke(canvas: Canvas, t: ViewTransform, x0: Float, y0: Float, x1: Float, y1: Float, faint: Boolean = false) {
        shadow.color = if (faint) SHADOW_FAINT else SHADOW
        shadow.strokeWidth = t.dp(if (faint) 2.5f else 3.5f)
        line.color = if (faint) GUIDE_FAINT else GUIDE
        line.strokeWidth = t.dp(if (faint) 1f else 1.5f)
        canvas.drawLine(x0, y0, x1, y1, shadow)
        canvas.drawLine(x0, y0, x1, y1, line)
    }

    private fun drawStraight(canvas: Canvas, t: ViewTransform, r: RulerSettings, sx: Float, sy: Float, zoom: Float) {
        mapDirection(t, r.angleDeg)
        val vx = pts[2]; val vy = pts[3]
        val ext = reach(sx, sy)
        stroke(canvas, t, sx - vx * ext, sy - vy * ext, sx + vx * ext, sy + vy * ext)

        // Tick marks measured from the center: minor every "nice" step >= 10dp, longer every 5 and 10.
        val stepDoc = niceStep(t.dp(10f) / zoom)
        val stepScreen = stepDoc * zoom
        val kMax = (ext / stepScreen).toInt().coerceAtMost(4000)
        val nx = -vy; val ny = vx
        buffer.clear()
        for (k in -kMax..kMax) {
            val px = sx + vx * k * stepScreen
            val py = sy + vy * k * stepScreen
            if (px < clip.left - 20 || px > clip.right + 20 || py < clip.top - 20 || py > clip.bottom + 20) continue
            val len = t.dp(if (k % 10 == 0) 7f else if (k % 5 == 0) 4.5f else 2.5f)
            buffer.add(px - nx * len, py - ny * len, px + nx * len, py + ny * len)
        }
        shadow.color = SHADOW; shadow.strokeWidth = t.dp(2.5f)
        buffer.draw(canvas, shadow)
        line.color = GUIDE; line.strokeWidth = t.dp(1f)
        buffer.draw(canvas, line)
        buffer.clear()
    }

    private fun drawOval(canvas: Canvas, t: ViewTransform, r: RulerSettings, rx: Float, ry: Float, deg: Float, zoom: Float) {
        if (rx <= 0f || ry <= 0f) return
        val saved = canvas.save()
        canvas.concat(t.matrix)
        canvas.rotate(deg, r.centerX, r.centerY)
        oval.set(r.centerX - rx, r.centerY - ry, r.centerX + rx, r.centerY + ry)
        shadow.color = SHADOW; shadow.strokeWidth = t.dp(3.5f) / zoom
        line.color = GUIDE; line.strokeWidth = t.dp(1.5f) / zoom
        canvas.drawOval(oval, shadow)
        canvas.drawOval(oval, line)
        canvas.restoreToCount(saved)
    }

    private fun drawRadial(canvas: Canvas, t: ViewTransform, r: RulerSettings, sx: Float, sy: Float) {
        val n = r.radialLines.coerceIn(2, 360)
        val ext = reach(sx, sy)
        val inner = t.dp(10f)
        buffer.clear()
        for (k in 0 until n) {
            mapDirection(t, r.angleDeg + k * 360f / n)
            val vx = pts[2]; val vy = pts[3]
            buffer.add(sx + vx * inner, sy + vy * inner, sx + vx * ext, sy + vy * ext)
        }
        shadow.color = SHADOW_FAINT; shadow.strokeWidth = t.dp(2.5f)
        buffer.draw(canvas, shadow)
        line.color = GUIDE_FAINT; line.strokeWidth = t.dp(1f)
        buffer.draw(canvas, line)
        buffer.clear()
    }

    private fun drawCenterMark(canvas: Canvas, t: ViewTransform, sx: Float, sy: Float) {
        val s = t.dp(5f)
        stroke(canvas, t, sx - s, sy, sx + s, sy)
        stroke(canvas, t, sx, sy - s, sx, sy + s)
    }

    private fun drawHandles(canvas: Canvas, t: ViewTransform, r: RulerSettings, zoom: Float) {
        val docPerDp = t.dp(1f) / zoom
        mapPoint(t, r.centerX, r.centerY)
        val cx = pts[0]; val cy = pts[1]
        val size = t.dp(RulerGeometry.HANDLE_DP)
        // Resize handles are squares aligned with the axis they change.
        mapDirection(t, if (r.type == RulerType.CIRCLE) 0f else r.angleDeg)
        val axisDeg = Math.toDegrees(atan2(pts[3], pts[2]).toDouble()).toFloat()
        if (r.type == RulerType.ELLIPSE) {
            // Semi-axes, to show which handle changes which radius.
            for (h in arrayOf(RulerHandle.RADIUS_X, RulerHandle.RADIUS_Y)) {
                val p = RulerGeometry.handlePosition(r, h, docPerDp)
                mapPoint(t, p.x, p.y)
                stroke(canvas, t, cx, cy, pts[0], pts[1], faint = true)
            }
        }
        for (h in RulerGeometry.handlesOf(r.type)) {
            val p = RulerGeometry.handlePosition(r, h, docPerDp)
            mapPoint(t, p.x, p.y)
            val hx = pts[0]; val hy = pts[1]
            when (h) {
                RulerHandle.CENTER -> {
                    val rad = size * 0.65f
                    fill.color = SHADOW; canvas.drawCircle(hx, hy, rad + t.dp(1.5f), fill)
                    fill.color = 0xFFFFFFFF.toInt(); canvas.drawCircle(hx, hy, rad, fill)
                    fill.color = ACCENT; canvas.drawCircle(hx, hy, rad - t.dp(2.5f), fill)
                }
                RulerHandle.ROTATE -> {
                    val base = if (r.type == RulerType.ELLIPSE) RulerGeometry.handlePosition(r, RulerHandle.RADIUS_X, docPerDp) else null
                    val bx: Float; val by: Float
                    if (base != null) { mapPoint(t, base.x, base.y); bx = pts[0]; by = pts[1] } else { bx = cx; by = cy }
                    stroke(canvas, t, bx, by, hx, hy, faint = true)
                    val rad = size / 2f
                    fill.color = SHADOW; canvas.drawCircle(hx, hy, rad + t.dp(1.5f), fill)
                    fill.color = ACCENT; canvas.drawCircle(hx, hy, rad, fill)
                    fill.color = 0xFFFFFFFF.toInt(); canvas.drawCircle(hx, hy, rad * 0.4f, fill)
                }
                RulerHandle.RADIUS, RulerHandle.RADIUS_X, RulerHandle.RADIUS_Y -> {
                    val half = size / 2f
                    val saved = canvas.save()
                    canvas.rotate(axisDeg, hx, hy)
                    val o = t.dp(1.5f)
                    fill.color = SHADOW; canvas.drawRect(hx - half - o, hy - half - o, hx + half + o, hy + half + o, fill)
                    fill.color = 0xFFFFFFFF.toInt(); canvas.drawRect(hx - half, hy - half, hx + half, hy + half, fill)
                    val i = half - t.dp(2.5f)
                    fill.color = ACCENT; canvas.drawRect(hx - i, hy - i, hx + i, hy + i, fill)
                    canvas.restoreToCount(saved)
                }
            }
        }
    }

    /** Smallest value of the 1-2-5 series (at least 1 px) that is >= [min]. */
    fun niceStep(min: Float): Float {
        if (min <= 1f) return 1f
        val base = 10f.pow(floor(log10(min)))
        val m = min / base
        return base * when {
            m <= 1f -> 1f
            m <= 2f -> 2f
            m <= 5f -> 5f
            else -> 10f
        }
    }
}
