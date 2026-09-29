package com.brushwork.paint.engine

import android.graphics.BlendMode
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Build
import com.brushwork.paint.model.LayerBlendMode

/** Maps layer blend modes onto Paint (BlendMode on API 29+, PorterDuff subset before that). */
object BlendModes {
    fun apply(paint: Paint, mode: LayerBlendMode) {
        if (Build.VERSION.SDK_INT >= 29) {
            paint.blendMode = when (mode) {
                LayerBlendMode.NORMAL -> BlendMode.SRC_OVER
                LayerBlendMode.MULTIPLY -> BlendMode.MULTIPLY
                LayerBlendMode.SCREEN -> BlendMode.SCREEN
                LayerBlendMode.OVERLAY -> BlendMode.OVERLAY
                LayerBlendMode.DARKEN -> BlendMode.DARKEN
                LayerBlendMode.LIGHTEN -> BlendMode.LIGHTEN
                LayerBlendMode.COLOR_DODGE -> BlendMode.COLOR_DODGE
                LayerBlendMode.COLOR_BURN -> BlendMode.COLOR_BURN
                LayerBlendMode.HARD_LIGHT -> BlendMode.HARD_LIGHT
                LayerBlendMode.SOFT_LIGHT -> BlendMode.SOFT_LIGHT
                LayerBlendMode.DIFFERENCE -> BlendMode.DIFFERENCE
                LayerBlendMode.EXCLUSION -> BlendMode.EXCLUSION
                LayerBlendMode.HUE -> BlendMode.HUE
                LayerBlendMode.SATURATION -> BlendMode.SATURATION
                LayerBlendMode.COLOR -> BlendMode.COLOR
                LayerBlendMode.LUMINOSITY -> BlendMode.LUMINOSITY
                LayerBlendMode.ADD -> BlendMode.PLUS
            }
        } else {
            val pd = when (mode) {
                LayerBlendMode.MULTIPLY -> PorterDuff.Mode.MULTIPLY
                LayerBlendMode.SCREEN -> PorterDuff.Mode.SCREEN
                LayerBlendMode.OVERLAY -> PorterDuff.Mode.OVERLAY
                LayerBlendMode.DARKEN -> PorterDuff.Mode.DARKEN
                LayerBlendMode.LIGHTEN -> PorterDuff.Mode.LIGHTEN
                LayerBlendMode.ADD -> PorterDuff.Mode.ADD
                else -> PorterDuff.Mode.SRC_OVER
            }
            paint.xfermode = PorterDuffXfermode(pd)
        }
    }

    /** New paint configured with [mode] and [opacity] (0..1). */
    fun paint(mode: LayerBlendMode, opacity: Float): Paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        alpha = (opacity.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        apply(this, mode)
    }
}
