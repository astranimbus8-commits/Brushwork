package com.brushwork.paint.tools.select

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.CompositeTarget
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Eyedropper: touch or drag to sample a color (from the canvas composite or the active layer,
 * 1x1 / 3x3 / 5x5 average ignoring transparent pixels). A preview square above the finger shows
 * the new color (top) against the current one (bottom) and a small ring marks the sampled pixel;
 * releasing sets the drawing color and, optionally, returns to the last painting tool.
 *
 * It also serves the long-press color pick of the painting tools (controller.holdPicking): then
 * releasing only sets the color, the tool stays, and a transparent spot keeps the color quietly.
 */
class EyedropperTool(controller: EditorController) : Tool(controller) {
    override val id = ToolId.EYEDROPPER

    var settings: EyedropperSettings by PersistedOption(controller.settings, "select.eyedropper", EyedropperSettings.serializer(), EyedropperSettings())

    private var active = false
    /** This gesture is another tool's long-press pick (see class docs). */
    private var holding = false
    private var sampled: Int? = null
    private var previous = 0
    private var posX = 0f
    private var posY = 0f

    private val pixels = IntArray(MAX_SAMPLE * MAX_SAMPLE)

    // Canvas sampling reads a flattened patch of the document around the finger, rendered once
    // and reused while the finger stays inside it (a drag re-renders only every so often).
    private val patch: Bitmap = Bitmap.createBitmap(PATCH_SIZE, PATCH_SIZE, Bitmap.Config.ARGB_8888)
    private val patchCanvas = Canvas(patch)
    private val patchRect = Rect()
    private var patchValid = false

    override fun onDown(p: ToolPoint) {
        active = true
        holding = controller.holdPicking
        previous = controller.color
        sampled = null
        // The document may have changed since the last pick.
        patchValid = false
        sampleAt(p)
    }

    override fun onMove(p: ToolPoint) {
        if (active) sampleAt(p)
    }

    override fun onUp(p: ToolPoint) {
        if (!active) return
        sampleAt(p)
        active = false
        val hold = holding || controller.holdPicking
        holding = false
        patchValid = false
        controller.invalidateOverlay()
        val c = sampled
        if (c == null) {
            // A held brush that rests on an empty spot just keeps its color (the preview showed it).
            if (!hold) {
                val where = if (layerSource) "on this layer" else "on the canvas"
                controller.toast("Nothing to pick here: the area is transparent $where")
            }
            return
        }
        controller.color = c or 0xFF000000.toInt()
        if (!hold && settings.returnToBrush) controller.selectTool(controller.lastPaintTool)
    }

    override fun onCancel() {
        active = false
        holding = false
        sampled = null
        patchValid = false
        controller.invalidateOverlay()
    }

    private fun sampleAt(p: ToolPoint) {
        posX = p.x; posY = p.y
        // Transparent spots keep the last color found in this gesture (what releasing picks).
        sample(p.x, p.y)?.let { sampled = it }
        controller.invalidateOverlay()
    }

    /**
     * Averaged color around document position (x, y) as opaque ARGB, or null when every sampled
     * pixel is transparent (or the point is outside the canvas).
     */
    /**
     * Samples the active layer's own pixels ("This layer"). v1.7 (rule C): a folder has none, so
     * with a folder active the eyedropper reads the composite, as "Sample all layers" does.
     */
    private val layerSource: Boolean get() = settings.source == SampleSource.LAYER && !controller.activeLayer.isFolder

    fun sample(x: Float, y: Float): Int? {
        val doc = controller.doc
        if (!x.isFinite() || !y.isFinite()) return null
        val ix = floor(x).toInt(); val iy = floor(y).toInt()
        if (ix !in 0 until doc.width || iy !in 0 until doc.height) return null
        val half = (settings.sampleSize.coerceIn(1, MAX_SAMPLE) - 1) / 2
        val r = Rect(ix - half, iy - half, ix + half + 1, iy + half + 1)
        if (!r.intersect(0, 0, doc.width, doc.height)) return null
        val w = r.width(); val h = r.height()
        if (layerSource) {
            controller.activeLayer.bitmap.getPixels(pixels, 0, w, r.left, r.top, w, h)
        } else {
            // Outside a gesture nothing guarantees the cached patch is current.
            if (!active) patchValid = false
            if (!patchValid || !patchRect.contains(r)) renderPatch(ix, iy)
            if (!patchRect.contains(r)) return null
            patch.getPixels(pixels, 0, w, r.left - patchRect.left, r.top - patchRect.top, w, h)
        }
        return averageOpaque(pixels, w * h)
    }

    /** Flattens the document area around ([cx], [cy]) into [patch] (no tool previews). */
    private fun renderPatch(cx: Int, cy: Int) {
        val doc = controller.doc
        val left = (cx - PATCH_SIZE / 2).coerceIn(0, max(0, doc.width - PATCH_SIZE))
        val top = (cy - PATCH_SIZE / 2).coerceIn(0, max(0, doc.height - PATCH_SIZE))
        patchRect.set(left, top, min(doc.width, left + PATCH_SIZE), min(doc.height, top + PATCH_SIZE))
        patch.eraseColor(0)
        patchCanvas.save()
        patchCanvas.clipRect(0, 0, patchRect.width(), patchRect.height())
        patchCanvas.translate(-left.toFloat(), -top.toFloat())
        controller.compositor.drawDocument(patchCanvas, patchRect, useOverrides = false, target = CompositeTarget.translate(patch, left, top))
        patchCanvas.restore()
        patchValid = true
    }

    // ------------------------------------------------------------------ overlay

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val box = RectF()
    private val area = RectF()
    private val half = RectF()
    private val topPath = Path()
    private val radii = FloatArray(8)
    private var checkerPaint: Paint? = null
    private var checkerCell = 0f

    /** Screen px of the canvas view hidden by the editor chrome at each edge (left, top, right, bottom). */
    private val chromeInsets = RectF()

    /**
     * The editor's bars cover these edges of the canvas view (screen px, e.g. the top bar with the
     * tool options and the bottom hotbar): the preview square stays in the area between them.
     */
    fun setChromeInsets(left: Float, top: Float, right: Float, bottom: Float) {
        chromeInsets.set(left.coerceAtLeast(0f), top.coerceAtLeast(0f), right.coerceAtLeast(0f), bottom.coerceAtLeast(0f))
    }

    override fun drawOverlay(canvas: Canvas, t: ViewTransform) {
        if (!active) return
        val at = t.docToScreen(posX, posY)
        val fx = at.x
        val fy = at.y
        if (!fx.isFinite() || !fy.isFinite()) return
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        area.set(chromeInsets.left, chromeInsets.top, w - chromeInsets.right, h - chromeInsets.bottom)
        // Chrome so tall that the square can't fit between it (tiny window): use the whole view.
        val need = (PREVIEW_SIZE_DP + 2 * PREVIEW_MARGIN_DP) * t.density
        if (area.width() < need || area.height() < need) area.set(0f, 0f, w, h)
        placePreview(fx, fy, area, t.density, box)
        drawPreview(canvas, t)
        drawMarker(canvas, t, fx, fy)
    }

    /**
     * The preview square (new color on top, current color below) with a two-tone border so it
     * reads on any background. A checkerboard stands for "nothing picked yet" (transparent).
     */
    private fun drawPreview(canvas: Canvas, t: ViewTransform) {
        val corner = t.dp(PREVIEW_CORNER_DP)
        // Soft dark rim first (visible on light canvases).
        stroke.color = 0x80000000.toInt()
        stroke.strokeWidth = t.dp(4f)
        canvas.drawRoundRect(box, corner, corner, stroke)

        // Current color: the whole square, then the new color over its top half.
        fill.color = previous or 0xFF000000.toInt()
        canvas.drawRoundRect(box, corner, corner, fill)
        half.set(box.left, box.top, box.right, box.centerY())
        radii.fill(0f)
        for (i in 0..3) radii[i] = corner // top-left and top-right corners (x, y each)
        topPath.rewind()
        topPath.addRoundRect(half, radii, Path.Direction.CW)
        val c = sampled
        if (c != null) {
            fill.color = c or 0xFF000000.toInt()
            canvas.drawPath(topPath, fill)
        } else {
            canvas.drawPath(topPath, checker(t.dp(6f)))
        }

        // Light inner rim and a hairline between the two halves.
        stroke.color = 0xFFFFFFFF.toInt()
        stroke.strokeWidth = t.dp(2f)
        canvas.drawRoundRect(box, corner, corner, stroke)
        stroke.strokeWidth = t.dp(1f)
        canvas.drawLine(box.left, box.centerY(), box.right, box.centerY(), stroke)
    }

    /** Ring with a center dot on the exact sampled pixel. */
    private fun drawMarker(canvas: Canvas, t: ViewTransform, x: Float, y: Float) {
        val r = t.dp(MARKER_RADIUS_DP)
        stroke.color = 0x99000000.toInt()
        stroke.strokeWidth = t.dp(3.5f)
        canvas.drawCircle(x, y, r, stroke)
        stroke.color = 0xFFFFFFFF.toInt()
        stroke.strokeWidth = t.dp(1.5f)
        canvas.drawCircle(x, y, r, stroke)
        fill.color = 0x99000000.toInt()
        canvas.drawCircle(x, y, t.dp(2.25f), fill)
        fill.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(x, y, t.dp(1.25f), fill)
    }

    private fun checker(cell: Float): Paint {
        checkerPaint?.let { if (checkerCell == cell) return it }
        val bmp = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        bmp.setPixel(0, 0, 0xFFFFFFFF.toInt()); bmp.setPixel(1, 1, 0xFFFFFFFF.toInt())
        bmp.setPixel(1, 0, 0xFFBDBDBD.toInt()); bmp.setPixel(0, 1, 0xFFBDBDBD.toInt())
        val shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        shader.setLocalMatrix(Matrix().apply { setScale(cell, cell) })
        return Paint().apply { this.shader = shader }.also { checkerPaint = it; checkerCell = cell }
    }

    companion object {
        const val MAX_SAMPLE = 5

        /** Side of the cached composite patch (document px). */
        private const val PATCH_SIZE = 128
        const val PREVIEW_SIZE_DP = 64f
        /** Distance from the finger to the preview's center (screen dp). */
        const val PREVIEW_OFFSET_DP = 80f
        private const val PREVIEW_CORNER_DP = 12f
        private const val PREVIEW_MARGIN_DP = 8f
        private const val MARKER_RADIUS_DP = 9f

        /** [placePreview] in a whole [viewW] x [viewH] view (nothing covers its edges). */
        fun placePreview(fx: Float, fy: Float, viewW: Float, viewH: Float, density: Float, out: RectF) =
            placePreview(fx, fy, RectF(0f, 0f, max(0f, viewW), max(0f, viewH)), density, out)

        /**
         * Where the preview square goes for a finger at ([fx], [fy]) (screen px; [density] px per
         * dp) when it must stay inside [area] (the part of the view not covered by the editor's
         * bars; an empty extent is not clamped): centered [PREVIEW_OFFSET_DP] above the finger so
         * the finger doesn't hide it; beside it (towards the middle of the area) when there is no
         * room above, never below (the hand is there). Written into [out].
         */
        fun placePreview(fx: Float, fy: Float, area: RectF, density: Float, out: RectF) {
            val size = PREVIEW_SIZE_DP * density
            val halfSize = size / 2f
            val offset = PREVIEW_OFFSET_DP * density
            val margin = PREVIEW_MARGIN_DP * density
            val hasW = area.width() > 0f
            val hasH = area.height() > 0f
            var cx = fx
            var cy = fy - offset
            if (cy - halfSize < (if (hasH) area.top else 0f) + margin) {
                cy = fy
                cx = if (!hasW || fx < area.centerX()) fx + offset else fx - offset
            }
            if (hasW) cx = cx.coerceIn(area.left + margin + halfSize, max(area.left + margin + halfSize, area.right - margin - halfSize))
            if (hasH) cy = cy.coerceIn(area.top + margin + halfSize, max(area.top + margin + halfSize, area.bottom - margin - halfSize))
            out.set(cx - halfSize, cy - halfSize, cx + halfSize, cy + halfSize)
        }

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
