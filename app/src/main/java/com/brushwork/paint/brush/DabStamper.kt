package com.brushwork.paint.brush

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Draws resolved dabs into an ALPHA_8 coverage canvas. Anti-aliased tips are drawn through a
 * matrix with bilinear filtering (sub-pixel positions, scale and rotation); aliased tips are
 * copied at integer positions with no filtering, so pixel brushes stay exact.
 *
 * A stroke draws thousands of small dabs, so the work around each draw is kept small: the tip of
 * the previous dab is reused when nothing changed, paint state is only set when it changes, and
 * an unscaled, unrotated dab gets a plain translation (the same matrix the general chain
 * produces, since scaling by 1 leaves a matrix unchanged). Reuses its Paint/Matrix: not
 * thread-safe.
 */
class DabStamper(private val tips: TipCache) {
    private val matrix = Matrix()
    // (Paint() is anti-aliased: every dab's box gets an anti-aliased edge.)
    private val paint = Paint()
    /** Alpha / filtering last set on [paint] (-1 / null: not set yet). */
    private var paintAlpha = -1
    private var paintFilter: Boolean? = null

    /**
     * Draft dabs (see [stamp]) skip the anti-aliasing of the dab's box, which costs several
     * times the drawing itself; the tip's own soft edge and transparent margin make the box
     * edge all but invisible.
     */
    private val draftPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var draftAlpha = -1

    // The tip of the previous dab: the next one usually needs the same one.
    private var lastPreset: BrushPreset? = null
    private var lastDiameter = Float.NaN
    private var lastVariant = -1
    private var lastTip: Tip? = null

    /** Number of dabs drawn by [stamp] so far (performance tests). */
    var stampCount = 0L
        private set

    private fun tipFor(preset: BrushPreset, diameter: Float, variant: Int): Tip {
        val t = lastTip
        if (t != null && preset === lastPreset && diameter == lastDiameter && variant == lastVariant && !t.bitmap.isRecycled) return t
        val tip = tips.get(preset, diameter, variant)
        lastPreset = preset
        lastDiameter = diameter
        lastVariant = variant
        lastTip = tip
        return tip
    }

    /** Computes the document-space bounds of [dab] into its left/top/right/bottom. */
    fun measure(preset: BrushPreset, dab: Dab): Tip {
        val tip = tipFor(preset, dab.diameter, dab.variant)
        if (preset.antiAlias) {
            val s = dab.diameter / tip.diameter
            // |cos| + |sin| of the rotation (exactly 1 without one).
            val spread = if (dab.rotation == 0f) 1f else {
                val rad = Math.toRadians(dab.rotation.toDouble())
                (abs(cos(rad)) + abs(sin(rad))).toFloat()
            }
            val e = tip.size / 2f * s * spread + 1f
            dab.left = floor(dab.cx - e).toInt()
            dab.top = floor(dab.cy - e).toInt()
            dab.right = ceil(dab.cx + e).toInt()
            dab.bottom = ceil(dab.cy + e).toInt()
        } else {
            dab.left = floor(dab.cx - tip.size / 2f + 0.5f).toInt()
            dab.top = floor(dab.cy - tip.size / 2f + 0.5f).toInt()
            dab.right = dab.left + tip.size
            dab.bottom = dab.top + tip.size
        }
        return tip
    }

    /**
     * Measures and draws [dab] (already resolved) into [canvas]. A [draft] dab (the quick
     * preview of a path while it is dragged) is drawn much faster and nearly identically, but
     * not pixel for pixel like a stroke's dabs.
     */
    fun stamp(canvas: Canvas, preset: BrushPreset, dab: Dab, draft: Boolean = false) {
        val tip = measure(preset, dab)
        val alpha = (dab.alpha * 255f + 0.5f).toInt()
        if (alpha <= 0) return
        stampCount++
        val a = alpha.coerceAtMost(255)
        if (draft && preset.antiAlias) {
            if (a != draftAlpha) {
                draftPaint.alpha = a
                draftAlpha = a
            }
            matrix.setTranslate(-tip.size / 2f, -tip.size / 2f)
            val s = dab.diameter / tip.diameter
            if (s != 1f) matrix.postScale(s, s)
            if (dab.rotation != 0f) matrix.postRotate(dab.rotation)
            matrix.postTranslate(dab.cx, dab.cy)
            canvas.drawBitmap(tip.bitmap, matrix, draftPaint)
            return
        }
        if (a != paintAlpha) {
            paint.alpha = a
            paintAlpha = a
        }
        if (preset.antiAlias) {
            if (paintFilter != true) {
                paint.isFilterBitmap = true
                paintFilter = true
            }
            val s = dab.diameter / tip.diameter
            val half = tip.size / 2f
            if (s == 1f && dab.rotation == 0f) {
                // translate(-half) . scale(1) . translate(c) is this translation.
                matrix.setTranslate(dab.cx - half, dab.cy - half)
            } else {
                matrix.setTranslate(-half, -half)
                matrix.postScale(s, s)
                if (dab.rotation != 0f) matrix.postRotate(dab.rotation)
                matrix.postTranslate(dab.cx, dab.cy)
            }
            canvas.drawBitmap(tip.bitmap, matrix, paint)
        } else {
            if (paintFilter != false) {
                paint.isFilterBitmap = false
                paintFilter = false
            }
            canvas.drawBitmap(tip.bitmap, dab.left.toFloat(), dab.top.toFloat(), paint)
        }
    }
}
