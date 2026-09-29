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

    /** A model with its panel paths and paints built once (outer edge + inner edge of the border ring). */
    class Prepared(val model: FrameModel) {
        internal val outer: List<Path> = model.panels.map { pathOf(it.points) }
        internal val inner: List<Path?> = model.panels.map { p ->
            if (model.style.borderWidth > 0f) FrameMath.inset(p.points, model.style.borderWidth)?.let { pathOf(it) } else null
        }
        internal val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = model.style.borderColor
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
        }
        internal val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
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
        for (i in prepared.outer.indices) {
            if (style.borderWidth > 0f) {
                canvas.drawPath(prepared.outer[i], prepared.border)
                prepared.inner[i]?.let { canvas.drawPath(it, prepared.clear) }
            } else {
                canvas.drawPath(prepared.outer[i], prepared.clear)
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
        val dst = Canvas(target)
        forEachChangedTile(target, model, area, colorMode) { fresh, local, tile ->
            beforeWrite(tile)
            dst.drawBitmap(fresh, local, tile, BitmapUtils.srcPaint)
            changed.union(tile)
            true
        }
        return changed
    }

    /** True when [target] already holds exactly the rendering of [model] (read-only check). */
    fun matches(target: Bitmap, model: FrameModel, colorMode: ColorMode): Boolean {
        var same = true
        forEachChangedTile(target, model, Rect(0, 0, target.width, target.height), colorMode) { _, _, _ ->
            same = false
            false
        }
        return same
    }

    /**
     * Renders [model] into a scratch tile for every tile of [target] overlapping [area], compares it
     * with the current pixels (native memcmp via [Bitmap.sameAs]) and calls [onChanged] with the
     * fresh tile, its valid sub-rect and the tile rect in [target] when they differ. Stops when
     * [onChanged] returns false. Two 256² scratch bitmaps are the only allocations.
     */
    private fun forEachChangedTile(
        target: Bitmap,
        model: FrameModel,
        area: Rect,
        colorMode: ColorMode,
        onChanged: (fresh: Bitmap, local: Rect, tile: Rect) -> Boolean,
    ) {
        val a = Rect(area)
        if (!a.intersect(0, 0, target.width, target.height)) return
        val prepared = Prepared(model)
        val fresh = BitmapUtils.createLayerBitmap(TILE, TILE)
        val current = BitmapUtils.createLayerBitmap(TILE, TILE)
        try {
            val fc = Canvas(fresh)
            val cc = Canvas(current)
            val local = Rect()
            val tile = Rect()
            for (row in a.top / TILE..(a.bottom - 1) / TILE) {
                for (col in a.left / TILE..(a.right - 1) / TILE) {
                    val x = col * TILE
                    val y = row * TILE
                    val w = min(TILE, target.width - x)
                    val h = min(TILE, target.height - y)
                    // Edge tiles only use part of the scratch: keep the rest identical (transparent).
                    if (w < TILE || h < TILE) { fresh.eraseColor(0); current.eraseColor(0) }
                    local.set(0, 0, w, h)
                    tile.set(x, y, x + w, y + h)
                    val save = fc.save()
                    fc.clipRect(local)
                    fc.translate(-x.toFloat(), -y.toFloat())
                    render(fc, prepared)
                    fc.restoreToCount(save)
                    if (colorMode != ColorMode.RGB) ColorModeOps.constrain(fresh, local, colorMode)
                    cc.drawBitmap(target, tile, local, BitmapUtils.srcPaint)
                    if (!fresh.sameAs(current) && !onChanged(fresh, local, Rect(tile))) return
                }
            }
        } finally {
            fresh.recycle()
            current.recycle()
        }
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
