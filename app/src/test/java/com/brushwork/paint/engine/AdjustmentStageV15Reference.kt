package com.brushwork.paint.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.brushwork.paint.ColorModeOps
import com.brushwork.paint.core.Parallel
import com.brushwork.paint.filters.PixelMapper
import com.brushwork.paint.masks.MaskSpec
import com.brushwork.paint.masks.MaskSpecs
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/**
 * Test-only frozen copy of the v1.5 [AdjustmentStage.draw] (the Skia path: a mapped scratch chunk
 * drawn through saveLayer / DST_IN / DST_OUT / PLUS), taken at the v1.6 foundation (e8a7e2a)
 * before the fused NORMAL path (§3.1 C1) replaced it for direct-write targets. The fused path must
 * stay within one level of it (`AdjustmentFusedPathRobolectricTest`).
 *
 * The only changes from the original are the name and that the scratch state lives here.
 */
object AdjustmentStageV15Reference {
    private const val CHUNK = 512
    private const val PARALLEL_MIN = 16_384

    private val maskPaint = BitmapUtils.newMaskApplyPaint()
    private val plainPaint = Paint()
    private val whitePaint = Paint().apply { color = -1 }
    private val dstOutPaint = Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT) }
    private val plusPaint = Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.ADD) }

    /**
     * Applies adjustment [layer] onto [canvas] like v1.5 (document px, clipped to [bounds]);
     * [override] as in the compositor; [target] the bitmap [canvas] draws into.
     */
    fun draw(canvas: Canvas, layer: Layer, bounds: RectF, override: LayerRenderOverride?, target: CompositeTarget?, colorMode: ColorMode = ColorMode.RGB) {
        if (target == null || (AdjustmentStage.safeCompositing && target.display)) return
        val spec = layer.adjustment ?: return
        val mapper = com.brushwork.paint.masks.AdjustmentEffects.mapperOf(spec) ?: return
        val bmp = target.bitmap
        if (bmp.isRecycled || bmp.config != Bitmap.Config.ARGB_8888) return
        val ov = if (override != null && override.layer === layer) override else null
        val mask = if (layer.maskEnabled) layer.mask else null
        val masked = mask != null || (layer.mask == null && ov is MaskCoverageHint)
        val region = RectF(bounds)
        if (masked) {
            val cov: Rect? = when {
                ov == null -> coverage(layer, mask!!) ?: return
                ov is MaskCoverageHint -> ov.maskCoverage() ?: return
                else -> null
            }
            if (cov != null && !region.intersect(cov.left.toFloat(), cov.top.toFloat(), cov.right.toFloat(), cov.bottom.toFloat())) return
        }
        val inverse = Matrix()
        if (!target.docToTarget.invert(inverse)) return
        val mapped = RectF(region)
        target.docToTarget.mapRect(mapped)
        val t = Rect(floorInt(mapped.left), floorInt(mapped.top), ceilInt(mapped.right), ceilInt(mapped.bottom))
        if (!t.intersect(0, 0, bmp.width, bmp.height) || t.isEmpty) return
        val o = layer.opacity.coerceIn(0f, 1f)
        val alpha = (o * 255f + 0.5f).toInt()
        if (alpha <= 0) return
        val normal = layer.blendMode == LayerBlendMode.NORMAL
        val blendPaint = BlendModes.paint(layer.blendMode, o).apply { isFilterBitmap = false }
        val cw0 = min(CHUNK, t.width()); val ch0 = min(CHUNK, t.height())
        val s = Bitmap.createBitmap(cw0, ch0, Bitmap.Config.ARGB_8888)
        val px = IntArray(cw0 * ch0)
        val src = Rect()
        val dst = Rect()
        var y = t.top
        while (y < t.bottom) {
            val ch = min(CHUNK, t.bottom - y)
            var x = t.left
            while (x < t.right) {
                val cw = min(CHUNK, t.right - x)
                bmp.getPixels(px, 0, cw, x, y, cw, ch)
                val n = cw * ch
                var opaque = true
                for (i in 0 until n) if (px[i] ushr 24 != 0xFF) { opaque = false; break }
                val below = if (normal && !opaque) px.copyOf(n) else null
                if (n < PARALLEL_MIN) mapRange(mapper, colorMode, px, below, 0, n)
                else Parallel.forRange(ch, 8) { r0, r1 -> mapRange(mapper, colorMode, px, below, r0 * cw, r1 * cw) }
                s.setPixels(px, 0, cw, 0, 0, cw, ch)
                src.set(0, 0, cw, ch)
                dst.set(x, y, x + cw, y + ch)
                val save = canvas.save()
                canvas.concat(inverse)
                canvas.clipRect(dst)
                canvas.concat(target.docToTarget)
                if (normal && !opaque) {
                    dstOutPaint.alpha = alpha
                    val s1 = canvas.saveLayer(bounds, dstOutPaint)
                    canvas.drawPaint(whitePaint)
                    if (masked) drawMask(canvas, mask, ov)
                    canvas.restoreToCount(s1)
                    plusPaint.alpha = alpha
                    val s2 = canvas.saveLayer(bounds, plusPaint)
                    drawScratch(canvas, s, src, dst, inverse, plainPaint)
                    if (masked) drawMask(canvas, mask, ov)
                    canvas.restoreToCount(s2)
                } else if (!masked) {
                    drawScratch(canvas, s, src, dst, inverse, blendPaint)
                } else {
                    val sl = canvas.saveLayer(bounds, blendPaint)
                    drawScratch(canvas, s, src, dst, inverse, plainPaint)
                    drawMask(canvas, mask, ov)
                    canvas.restoreToCount(sl)
                }
                canvas.restoreToCount(save)
                x += cw
            }
            y += ch
        }
        s.recycle()
    }

    private fun coverage(layer: Layer, mask: Bitmap): Rect? {
        layer.maskSpec?.let { return MaskSpecs.coverageBounds(it as MaskSpec, mask.width, mask.height) }
        return Rect(0, 0, mask.width, mask.height)
    }

    private fun mapRange(mapper: PixelMapper, mode: ColorMode, px: IntArray, below: IntArray?, from: Int, until: Int) {
        mapper.map(px, from, until)
        if (mode != ColorMode.RGB) {
            for (i in from until until) {
                val c = px[i]
                val a = c and 0xFF000000.toInt()
                if (a == 0) continue
                px[i] = a or (ColorModeOps.constrainPixel(c or 0xFF000000.toInt(), mode) and 0xFFFFFF)
            }
        }
        if (below != null) for (i in from until until) if (px[i] ushr 24 != below[i] ushr 24) px[i] = AdjustmentStage.revealed(below[i], px[i])
    }

    private fun drawScratch(canvas: Canvas, s: Bitmap, src: Rect, dst: Rect, inverse: Matrix, paint: Paint) {
        val save = canvas.save()
        canvas.concat(inverse)
        canvas.drawBitmap(s, src, dst, paint)
        canvas.restoreToCount(save)
    }

    private fun drawMask(canvas: Canvas, mask: Bitmap?, ov: LayerRenderOverride?) {
        if (ov != null && ov.drawMask(canvas, maskPaint)) return
        if (mask != null) canvas.drawBitmap(mask, 0f, 0f, maskPaint)
    }

    private fun floorInt(v: Float): Int = if (v.isNaN()) 0 else floor(v.toDouble()).coerceIn(-1e9, 1e9).toInt()
    private fun ceilInt(v: Float): Int = if (v.isNaN()) 0 else ceil(v.toDouble()).coerceIn(-1e9, 1e9).toInt()
}
