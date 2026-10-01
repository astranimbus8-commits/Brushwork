package com.brushwork.paint.brush

import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import com.brushwork.paint.core.PackedPoints

/**
 * Offline, thread-safe replay of a recorded brush stroke (`VStroke`): the same sampler,
 * dynamics, dab stamper, coverage painter and [StrokeCost] spacing as the live BufferStroke, so a
 * vector stroke re-renders like it was drawn (v1.5 §5.4; API frozen, owned by A1 after F2). Use
 * one instance per thread.
 *
 * Foundation (F1): draws nothing; F2 writes the reference replay.
 */
class StrokeRaster(private val tips: TipCache = TipCache(8L shl 20)) {
    /**
     * Replays the stroke into [canvas] (document px) clipped to [clip]: [points] with their RAW
     * pressures (Stroke.pressureOf is applied here), [seed] for the random values, tapers at
     * the ends unless [taperIn] / [taperOut] are false. Returns what was painted.
     */
    fun render(
        canvas: Canvas,
        clip: Rect,
        preset: BrushPreset,
        color: Int,
        seed: Long,
        stylus: Boolean,
        points: PackedPoints,
        sizeScale: Float = 1f,
        opacity: Float = 1f,
        taperIn: Boolean = true,
        taperOut: Boolean = true,
    ): Rect = Rect()

    /** Everything the stroke can paint (document px). */
    fun bounds(preset: BrushPreset, sizeScale: Float, points: PackedPoints): RectF = RectF()
}
