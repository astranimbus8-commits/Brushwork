package com.brushwork.paint.tools.frame

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import com.brushwork.paint.core.Vec2
import kotlin.math.ceil
import kotlin.math.floor

/** Rasterizes a [FrameModel] into a frame layer (document coordinates). */
object FrameRenderer {
    /** Color outside the panels when [FrameStyle.fillOutside] is on. */
    const val OUTSIDE_COLOR = 0xFFFFFFFF.toInt()

    /**
     * Replaces the canvas content (inside its current clip) with the frame: outside color,
     * transparent panel interiors and a border ring of exactly `borderWidth` inside each panel
     * edge, with mitered corners.
     */
    fun render(canvas: Canvas, model: FrameModel) {
        canvas.drawColor(0, PorterDuff.Mode.CLEAR)
        if (model.style.fillOutside) canvas.drawColor(OUTSIDE_COLOR, PorterDuff.Mode.SRC)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = model.style.borderColor
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
        }
        val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        val bw = model.style.borderWidth
        for (panel in model.panels) {
            if (bw > 0f) {
                canvas.drawPath(pathOf(panel.points), border)
                FrameMath.inset(panel.points, bw)?.let { canvas.drawPath(pathOf(it), clear) }
            } else {
                canvas.drawPath(pathOf(panel.points), clear)
            }
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
