package com.brushwork.paint.tools.select

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import kotlin.math.floor

/**
 * Eyedropper: touch or drag to sample a color (from the canvas composite or the active layer,
 * 1x1 / 3x3 / 5x5 average ignoring transparent pixels). A loupe ring shows the new color (top)
 * against the current one (bottom); releasing sets the drawing color and, optionally, returns
 * to the last painting tool.
 */
class EyedropperTool(controller: EditorController) : Tool(controller) {
    override val id = ToolId.EYEDROPPER

    var settings: EyedropperSettings by PersistedOption(controller.settings, "select.eyedropper", EyedropperSettings.serializer(), EyedropperSettings())

    private var active = false
    private var sampled: Int? = null
    private var previous = 0
    private var posX = 0f
    private var posY = 0f

    private val probe: Bitmap = Bitmap.createBitmap(MAX_SAMPLE, MAX_SAMPLE, Bitmap.Config.ARGB_8888)
    private val probeCanvas = Canvas(probe)
    private val pixels = IntArray(MAX_SAMPLE * MAX_SAMPLE)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val oval = RectF()

    override fun onDown(p: ToolPoint) {
        active = true
        previous = controller.color
        sampled = null
        sampleAt(p)
    }

    override fun onMove(p: ToolPoint) {
        if (active) sampleAt(p)
    }

    override fun onUp(p: ToolPoint) {
        if (!active) return
        sampleAt(p)
        active = false
        controller.invalidateOverlay()
        val c = sampled ?: return
        controller.color = c or 0xFF000000.toInt()
        if (settings.returnToBrush) controller.selectTool(controller.lastPaintTool)
    }

    override fun onCancel() {
        active = false
        sampled = null
        controller.invalidateOverlay()
    }

    private fun sampleAt(p: ToolPoint) {
        posX = p.x; posY = p.y
        sample(p.x, p.y)?.let { sampled = it }
        controller.invalidateOverlay()
    }

    /**
     * Averaged color around document position (x, y) as opaque ARGB, or null when every sampled
     * pixel is transparent (or the point is outside the canvas).
     */
    fun sample(x: Float, y: Float): Int? {
        val doc = controller.doc
        val ix = floor(x).toInt(); val iy = floor(y).toInt()
        if (ix !in 0 until doc.width || iy !in 0 until doc.height) return null
        val half = (settings.sampleSize.coerceIn(1, MAX_SAMPLE) - 1) / 2
        val r = Rect(ix - half, iy - half, ix + half + 1, iy + half + 1)
        if (!r.intersect(0, 0, doc.width, doc.height)) return null
        val w = r.width(); val h = r.height()
        if (settings.source == SampleSource.LAYER) {
            controller.activeLayer.bitmap.getPixels(pixels, 0, w, r.left, r.top, w, h)
        } else {
            probe.eraseColor(0)
            probeCanvas.save()
            probeCanvas.clipRect(0, 0, w, h)
            probeCanvas.translate(-r.left.toFloat(), -r.top.toFloat())
            controller.compositor.drawDocument(probeCanvas, r, useOverrides = false)
            probeCanvas.restore()
            probe.getPixels(pixels, 0, w, 0, 0, w, h)
        }
        return averageOpaque(pixels, w * h)
    }

    override fun drawOverlay(canvas: Canvas, t: ViewTransform) {
        if (!active) return
        val c = t.docToScreen(posX, posY)
        val radius = t.dp(46f)
        val thick = t.dp(14f)
        oval.set(c.x - radius, c.y - radius, c.x + radius, c.y + radius)
        ring.strokeWidth = thick
        ring.color = (sampled ?: previous) or 0xFF000000.toInt()
        canvas.drawArc(oval, 180f, 180f, false, ring)
        ring.color = previous or 0xFF000000.toInt()
        canvas.drawArc(oval, 0f, 180f, false, ring)
        // Thin light/dark rims keep the ring visible on any background.
        ring.strokeWidth = t.dp(1.5f)
        ring.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(c.x, c.y, radius + thick / 2, ring)
        canvas.drawCircle(c.x, c.y, radius - thick / 2, ring)
        ring.color = 0x99000000.toInt()
        canvas.drawCircle(c.x, c.y, radius + thick / 2 + t.dp(1.5f), ring)
        canvas.drawCircle(c.x, c.y, radius - thick / 2 - t.dp(1.5f), ring)
        // Crosshair marking the sampled pixel.
        val arm = t.dp(6f)
        ring.color = 0xFFFFFFFF.toInt()
        canvas.drawLine(c.x - arm, c.y, c.x + arm, c.y, ring)
        canvas.drawLine(c.x, c.y - arm, c.x, c.y + arm, ring)
    }

    companion object {
        const val MAX_SAMPLE = 5

        /**
         * Alpha-weighted average of the first [count] NON-premultiplied pixels, ignoring fully
         * transparent ones. Returns an opaque color or null if all are transparent.
         */
        fun averageOpaque(px: IntArray, count: Int): Int? {
            var sa = 0L; var sr = 0L; var sg = 0L; var sb = 0L
            for (i in 0 until count) {
                val c = px[i]
                val a = c ushr 24
                if (a == 0) continue
                sa += a
                sr += ((c shr 16) and 0xFF).toLong() * a
                sg += ((c shr 8) and 0xFF).toLong() * a
                sb += (c and 0xFF).toLong() * a
            }
            if (sa == 0L) return null
            val r = ((sr + sa / 2) / sa).toInt(); val g = ((sg + sa / 2) / sa).toInt(); val b = ((sb + sa / 2) / sa).toInt()
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}
