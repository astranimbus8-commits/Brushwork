package com.brushwork.paint.storage

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Canvas size and memory rules shared by the new canvas dialog and picture import. Uses the same
 * layer budget as `EditorController.maxLayers` (55% of the heap, minus 3 layer-sized buffers
 * for undo and compositing). Pure Kotlin, so it is unit-tested on the JVM.
 */
object CanvasLimits {
    /** Longest allowed canvas side in pixels. */
    const val MAX_SIDE = 10_000
    /** A new canvas must leave room for at least this many layers. */
    const val MIN_LAYERS = 3
    /** An imported picture is scaled down (never up) until at least this many layers fit. */
    const val IMPORT_MIN_LAYERS = 5
    /** Longest side of an imported picture. */
    const val IMPORT_MAX_SIDE = 4096

    /** Bytes of one ARGB_8888 layer of this size. */
    fun layerBytes(width: Int, height: Int): Long = width.toLong() * height * 4

    /** Layers that fit in [maxHeapBytes] (can be negative or above the editor's clamp of 2..100). */
    fun rawMaxLayers(width: Int, height: Int, maxHeapBytes: Long): Long {
        val per = max(1L, layerBytes(width, height))
        return (maxHeapBytes * 0.55).toLong() / per - 3
    }

    /**
     * Longest side (at most [IMPORT_MAX_SIDE]) that a [width] x [height] picture may keep so that
     * at least [minLayers] layers of the resulting canvas fit in [maxHeapBytes]. Returns the
     * picture's own longest side when it already fits.
     */
    fun importMaxSide(width: Int, height: Int, maxHeapBytes: Long, minLayers: Int = IMPORT_MIN_LAYERS): Int {
        if (width <= 0 || height <= 0) return IMPORT_MAX_SIDE
        val long = max(width, height)
        val short = min(width, height)
        var cap = min(long, IMPORT_MAX_SIDE)
        if (fits(cap, long, short, maxHeapBytes, minLayers)) return cap
        // rawMaxLayers >= n  <=>  layerBytes <= budget / (n + 3); solve for the longest side.
        val maxPixels = (maxHeapBytes * 0.55).toLong() / (minLayers + 3) / 4
        val ratio = short.toDouble() / long
        cap = floor(sqrt(maxPixels / ratio)).toInt().coerceIn(1, cap)
        while (cap > 1 && !fits(cap, long, short, maxHeapBytes, minLayers)) cap--
        return cap
    }

    /** True when a [long] x [short] picture scaled to longest side [side] leaves room for [minLayers] layers. */
    private fun fits(side: Int, long: Int, short: Int, maxHeapBytes: Long, minLayers: Int): Boolean {
        val other = max(1L, (short.toDouble() * side / long).roundToLong()).toInt()
        return rawMaxLayers(side, other, maxHeapBytes) >= minLayers
    }
}
