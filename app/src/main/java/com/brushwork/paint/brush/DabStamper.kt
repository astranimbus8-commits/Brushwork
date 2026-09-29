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
 * Reuses its Paint/Matrix: not thread-safe.
 */
class DabStamper(private val tips: TipCache) {
    private val matrix = Matrix()
    private val paint = Paint()

    /** Computes the document-space bounds of [dab] into its left/top/right/bottom. */
    fun measure(preset: BrushPreset, dab: Dab): Tip {
        val tip = tips.get(preset, dab.diameter, dab.variant)
        if (preset.antiAlias) {
            val s = dab.diameter / tip.diameter
            val rad = Math.toRadians(dab.rotation.toDouble())
            val e = tip.size / 2f * s * (abs(cos(rad)) + abs(sin(rad))).toFloat() + 1f
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

    /** Measures and draws [dab] (already resolved) into [canvas]. */
    fun stamp(canvas: Canvas, preset: BrushPreset, dab: Dab) {
        val tip = measure(preset, dab)
        val alpha = (dab.alpha * 255f + 0.5f).toInt()
        if (alpha <= 0) return
        paint.alpha = alpha.coerceAtMost(255)
        if (preset.antiAlias) {
            paint.isFilterBitmap = true
            val s = dab.diameter / tip.diameter
            val half = tip.size / 2f
            matrix.setTranslate(-half, -half)
            matrix.postScale(s, s)
            if (dab.rotation != 0f) matrix.postRotate(dab.rotation)
            matrix.postTranslate(dab.cx, dab.cy)
            canvas.drawBitmap(tip.bitmap, matrix, paint)
        } else {
            paint.isFilterBitmap = false
            canvas.drawBitmap(tip.bitmap, dab.left.toFloat(), dab.top.toFloat(), paint)
        }
    }
}
