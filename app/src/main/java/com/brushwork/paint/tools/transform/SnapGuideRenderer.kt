package com.brushwork.paint.tools.transform

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.ViewTransform

/**
 * Draws smart guides ([SnapGuide]s from [SnapGuides]) in screen space: a faint magenta line
 * across the whole canvas, a strong segment between the aligned objects with small x markers
 * at its ends, and a label naming what was aligned to (one per direction). Main thread only.
 */
object SnapGuideRenderer {
    /** Illustrator-like smart guide magenta. */
    const val COLOR = 0xFFFF2BC2.toInt()

    private val across = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = COLOR; alpha = 0x80 }
    private val strong = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = COLOR; strokeCap = Paint.Cap.ROUND }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x66000000; strokeCap = Paint.Cap.ROUND }
    private val labelFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = COLOR }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = -1; typeface = Typeface.DEFAULT_BOLD }
    private val clip = Rect()
    private val box = RectF()
    /** Where labels may go (screen px): the canvas as shown, within the view. */
    private val labelArea = RectF()

    /**
     * Draws [guides] on a [docW] x [docH] canvas. [moving] (the box being moved, document px)
     * keeps the labels away from it and its handles: each goes at the far end of its guide.
     * Labels stay on the canvas as shown (around a canvas fit to the screen the rest of the view
     * is covered by the editor's bars) and within the view.
     */
    fun draw(canvas: Canvas, t: ViewTransform, guides: List<SnapGuide>, docW: Float, docH: Float, moving: DocBox? = null) {
        if (guides.isEmpty()) return
        across.strokeWidth = t.dp(1f)
        strong.strokeWidth = t.dp(1.5f)
        shadow.strokeWidth = t.dp(3f)
        labelArea.set(0f, 0f, docW, docH)
        t.matrix.mapRect(labelArea)
        if (canvas.getClipBounds(clip) &&
            !labelArea.intersect(clip.left.toFloat(), clip.top.toFloat(), clip.right.toFloat(), clip.bottom.toFloat())
        ) {
            labelArea.set(clip)
        }
        labelArea.inset(t.dp(4f), t.dp(4f))
        val m = t.dp(4f)
        for (g in guides) {
            // Faint line across the canvas.
            val (a, b) = ends(g.axis, g.pos, 0f, if (g.axis == SnapAxis.X) docH else docW, t)
            canvas.drawLine(a.x, a.y, b.x, b.y, across)
            // Strong segment between the objects, with x markers at its ends.
            val (s, e) = ends(g.axis, g.pos, g.start, g.end, t)
            canvas.drawLine(s.x, s.y, e.x, e.y, shadow)
            canvas.drawLine(s.x, s.y, e.x, e.y, strong)
            for (p in arrayOf(s, e)) {
                canvas.drawLine(p.x - m, p.y - m, p.x + m, p.y + m, strong)
                canvas.drawLine(p.x - m, p.y + m, p.x + m, p.y - m, strong)
            }
        }
        // One label per direction (the first guide: the one snapped to).
        guides.firstOrNull { it.axis == SnapAxis.X }?.let { label(canvas, t, it, moving) }
        guides.firstOrNull { it.axis == SnapAxis.Y }?.let { label(canvas, t, it, moving) }
    }

    private fun ends(axis: SnapAxis, pos: Float, from: Float, to: Float, t: ViewTransform): Pair<Vec2, Vec2> =
        if (axis == SnapAxis.X) t.docToScreen(Vec2(pos, from)) to t.docToScreen(Vec2(pos, to))
        else t.docToScreen(Vec2(from, pos)) to t.docToScreen(Vec2(to, pos))

    private fun label(canvas: Canvas, t: ViewTransform, g: SnapGuide, moving: DocBox?) {
        labelText.textSize = t.dp(11f)
        val text = g.label
        val w = labelText.measureText(text) + t.dp(12f)
        val h = t.dp(20f)
        val (s, e) = ends(g.axis, g.pos, g.start, g.end, t)
        // The end of the segment farther from the moving box (its handles sit on the near one).
        val mid = moving?.let {
            if (g.axis == SnapAxis.X) (it.top + it.bottom) / 2f else (it.left + it.right) / 2f
        }
        val atEnd = mid != null && kotlin.math.abs(g.end - mid) > kotlin.math.abs(g.start - mid)
        val anchor = if (atEnd) e else s
        val other = if (atEnd) s else e
        // Just beyond that end, along the guide, kept on screen.
        var away = (anchor - other).normalized()
        if (away.lengthSq < 0.5f) away = if (g.axis == SnapAxis.X) Vec2(0f, -1f) else Vec2(-1f, 0f)
        val cx = fit(anchor.x + away.x * (w / 2f + t.dp(6f)), w / 2f, labelArea.left, labelArea.right)
        val cy = fit(anchor.y + away.y * (h / 2f + t.dp(6f)), h / 2f, labelArea.top, labelArea.bottom)
        box.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        val r = t.dp(6f)
        canvas.drawRoundRect(box, r, r, labelFill)
        val fm = labelText.fontMetrics
        canvas.drawText(text, box.left + t.dp(6f), cy - (fm.ascent + fm.descent) / 2f, labelText)
    }

    /** [c] moved so that [c] ± [half] lies within [lo]..[hi] (centered there when it can't fit). */
    private fun fit(c: Float, half: Float, lo: Float, hi: Float): Float =
        if (!(hi - lo >= 2f * half)) (lo + hi) / 2f else c.coerceIn(lo + half, hi - half)
}
