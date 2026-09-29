package com.brushwork.paint.tools.frame

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import com.brushwork.paint.ColorModeOps
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.ColorMode
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/** Rasterizes a [FrameModel] into a frame layer (document coordinates). */
object FrameRenderer {
    /** Color outside the panels when [FrameStyle.fillOutside] is on. */
    const val OUTSIDE_COLOR = 0xFFFFFFFF.toInt()

    /** Tile size of [renderChangedTiles]; matches the undo recorder's tiles. */
    private const val TILE = 256

    /** A model with its panel paths built once (outer edge + inner edge of the border ring). */
    class Prepared(val model: FrameModel) {
        internal val outer: List<Path> = model.panels.map { pathOf(it.points) }
        internal val inner: List<Path?> = model.panels.map { p ->
            if (model.style.borderWidth > 0f) FrameMath.inset(p.points, model.style.borderWidth)?.let { pathOf(it) } else null
        }
    }

    /**
     * Replaces the canvas content (inside its current clip) with the frame: outside color,
     * transparent panel interiors and a border ring of exactly `borderWidth` inside each panel
     * edge, with mitered corners.
     */
    fun render(canvas: Canvas, model: FrameModel) = render(canvas, Prepared(model))

    fun render(canvas: Canvas, prepared: Prepared) {
        val style = prepared.model.style
        canvas.drawColor(0, PorterDuff.Mode.CLEAR)
        if (style.fillOutside) canvas.drawColor(OUTSIDE_COLOR, PorterDuff.Mode.SRC)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.FILL
            color = style.borderColor
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
        }
        val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.FILL
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        for (i in prepared.outer.indices) {
            if (style.borderWidth > 0f) {
                canvas.drawPath(prepared.outer[i], border)
                prepared.inner[i]?.let { canvas.drawPath(it, clear) }
            } else {
                canvas.drawPath(prepared.outer[i], clear)
            }
        }
    }

    /**
     * Renders [model] tile by tile over [area] of [target] and writes only the tiles whose pixels
     * actually change (after applying [colorMode]). [beforeWrite] is called with each such tile
     * before it is overwritten, so undo only snapshots what changed. Returns the union of the
     * changed tiles (empty if nothing changed).
     */
    fun renderChangedTiles(target: Bitmap, model: FrameModel, area: Rect, colorMode: ColorMode, beforeWrite: (Rect) -> Unit): Rect {
        val changed = Rect()
        val a = Rect(area)
        if (!a.intersect(0, 0, target.width, target.height)) return changed
        val prepared = Prepared(model)
        val scratch = BitmapUtils.createLayerBitmap(TILE, TILE)
        try {
            val sc = Canvas(scratch)
            val dst = Canvas(target)
            val fresh = IntArray(TILE * TILE)
            val current = IntArray(TILE * TILE)
            for (row in a.top / TILE..(a.bottom - 1) / TILE) {
                for (col in a.left / TILE..(a.right - 1) / TILE) {
                    val x = col * TILE
                    val y = row * TILE
                    val w = min(TILE, target.width - x)
                    val h = min(TILE, target.height - y)
                    val save = sc.save()
                    sc.translate(-x.toFloat(), -y.toFloat())
                    render(sc, prepared)
                    sc.restoreToCount(save)
                    val local = Rect(0, 0, w, h)
                    if (colorMode != ColorMode.RGB) ColorModeOps.constrain(scratch, local, colorMode)
                    scratch.getPixels(fresh, 0, w, 0, 0, w, h)
                    target.getPixels(current, 0, w, x, y, w, h)
                    if (!sameRange(fresh, current, w * h)) {
                        val r = Rect(x, y, x + w, y + h)
                        beforeWrite(r)
                        dst.drawBitmap(scratch, local, r, BitmapUtils.srcPaint)
                        changed.union(r)
                    }
                }
            }
        } finally {
            scratch.recycle()
        }
        return changed
    }

    private fun sameRange(a: IntArray, b: IntArray, n: Int): Boolean {
        for (i in 0 until n) if (a[i] != b[i]) return false
        return true
    }

    fun pathOf(points: List<Vec2>): Path = Path().apply {
        if (points.isEmpty()) return@apply
        moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
        close()
    }

    /** Pixel rect covering [panels] plus an anti-aliasing margin, or null for none. */
    fun dirtyRect(panels: Collection<Panel>, width: Int, height: Int): Rect? {
        if (panels.isEmpty()) return null
        val r = Rect()
        for (p in panels) {
            val b = p.bounds()
            r.union(floor(b.left).toInt() - 2, floor(b.top).toInt() - 2, ceil(b.right).toInt() + 2, ceil(b.bottom).toInt() + 2)
        }
        return if (r.intersect(0, 0, width, height)) r else null
    }
}
