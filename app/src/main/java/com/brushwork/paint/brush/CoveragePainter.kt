package com.brushwork.paint.brush

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.roundToInt

/** How a stroke's coverage buffer is composited onto its target. */
data class CoverageStyle(
    /** SRC_OVER (paint / mask), DST_OUT (erase) or SRC_ATOP (alpha-locked layer). */
    val mode: PorterDuff.Mode,
    /** Opaque tint color. */
    val color: Int,
    /** Stroke opacity 0..1 (the cap for overlapping dabs within the stroke). */
    val opacity: Float,
    /** Paper grain strength 0..1 (document-anchored texture). */
    val grain: Float,
    /**
     * Paints the coverage with this shader instead of [color] (clone stamp, v1.5): document
     * anchored, its local matrix places the source. Null = the tint color, exactly as before.
     */
    val shader: Shader? = null,
)

/** Tileable ALPHA_8 paper texture used for pencil/chalk grain (document-anchored). */
object PaperGrain {
    const val SIZE = 256
    private val texture: FloatArray by lazy { TipShapes.paperTexture(SIZE) }
    private val tiles = HashMap<Int, Bitmap>()

    /** Tile whose alpha is 1 on paper peaks and 1 - [amount] in the valleys. */
    @Synchronized
    fun tile(amount: Float): Bitmap {
        val key = (amount.coerceIn(0f, 1f) * 20f).roundToInt()
        return tiles.getOrPut(key) {
            val g = key / 20f
            val t = texture
            val bytes = ByteArray(SIZE * SIZE) { i -> ((1f - g * (1f - t[i])) * 255f + 0.5f).toInt().toByte() }
            com.brushwork.paint.engine.BitmapUtils.bytesToAlpha8(bytes, SIZE, SIZE)
        }
    }
}

/**
 * Composites an ALPHA_8 coverage buffer, tinted, onto a canvas in document coordinates:
 * used for the live preview (through the layer render override), for committing the stroke,
 * and for thumbnails. With a selection or grain the tinted buffer is built in an isolated
 * layer, masked with DST_IN, then composited with the stroke mode. Not thread-safe.
 */
class CoveragePainter {
    private val tint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val layerPaint = Paint()
    private val dstIn = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val grainPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private var grainAmount = -1f
    private val boundsF = RectF()
    private val modes = HashMap<PorterDuff.Mode, PorterDuffXfermode>()

    private fun xfer(mode: PorterDuff.Mode): PorterDuffXfermode? =
        if (mode == PorterDuff.Mode.SRC_OVER) null else modes.getOrPut(mode) { PorterDuffXfermode(mode) }

    private fun alphaOf(style: CoverageStyle): Int = (style.opacity.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

    /** False when [style] is so transparent that [draw] changes nothing. */
    fun isVisible(style: CoverageStyle): Boolean = alphaOf(style) > 0

    /**
     * Draws [coverage] limited to [bounds] with [style]. [selectionMask] (ALPHA_8, document
     * sized) limits the effect to the selection.
     */
    fun draw(canvas: Canvas, coverage: Bitmap, bounds: Rect, style: CoverageStyle, selectionMask: Bitmap?) {
        if (bounds.isEmpty) return
        val alpha = alphaOf(style)
        if (alpha <= 0) return
        canvas.save()
        canvas.clipRect(bounds)
        if (selectionMask == null && style.grain <= 0f) {
            tint.shader = style.shader
            tint.xfermode = xfer(style.mode)
            tint.color = style.color
            tint.alpha = alpha
            canvas.drawBitmap(coverage, 0f, 0f, tint)
        } else {
            boundsF.set(bounds)
            layerPaint.xfermode = xfer(style.mode)
            layerPaint.alpha = alpha
            canvas.saveLayer(boundsF, layerPaint)
            tint.shader = style.shader
            tint.xfermode = null
            tint.color = style.color
            tint.alpha = 255
            canvas.drawBitmap(coverage, 0f, 0f, tint)
            if (style.grain > 0f) {
                if (grainAmount != style.grain) {
                    grainPaint.shader = BitmapShader(PaperGrain.tile(style.grain), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                    grainAmount = style.grain
                }
                canvas.drawRect(boundsF, grainPaint)
            }
            // (drawBitmap(A8, DST_IN) would be a no-op: Skia treats A8 bitmaps as coverage.)
            if (selectionMask != null) com.brushwork.paint.engine.BitmapUtils.maskWith(canvas, selectionMask)
            canvas.restore()
        }
        // Don't keep a clone source alive through the shared paint.
        if (style.shader != null) tint.shader = null
        canvas.restore()
    }
}
