package com.brushwork.paint.brush

import android.graphics.Bitmap
import com.brushwork.paint.engine.BitmapUtils
import kotlin.math.ceil
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A rasterized brush tip: [bitmap] is an ALPHA_8 [size] x [size] image holding a shape of
 * [diameter] px centered at size / 2.
 */
class Tip(val bitmap: Bitmap, val diameter: Float, val size: Int)

/**
 * LRU cache of ALPHA_8 tip bitmaps. Anti-aliased tips are rendered at geometric size buckets
 * (4 per octave) and scaled down slightly when drawn; aliased tips are rendered at their exact
 * integer size with the rotation baked in. Not thread-safe: one instance per thread/owner.
 */
class TipCache(private val maxBytes: Long = 32L shl 20) {
    private data class Key(
        val tip: BrushTip,
        val bucket: Int,
        val hardness: Int,
        val roundness: Int,
        val angle: Int,
        val variant: Int,
        val antiAlias: Boolean,
    )

    private val map = LinkedHashMap<Key, Tip>(64, 0.75f, true)
    private var bytes = 0L

    /** The tip to draw a dab of [diameter] px (>= 1) of [preset] with texture [variant]. */
    fun get(preset: BrushPreset, diameter: Float, variant: Int): Tip {
        val aa = preset.antiAlias
        val bucket: Int
        val tipDiameter: Float
        if (aa) {
            bucket = bucketOf(diameter)
            tipDiameter = bucketDiameter(bucket)
        } else {
            bucket = max(1, diameter.roundToInt())
            tipDiameter = bucket.toFloat()
        }
        val hq = (preset.hardness.coerceIn(0f, 1f) * 20f).roundToInt()
        val rq = (preset.roundness.coerceIn(BrushLimits.MIN_ROUNDNESS, 1f) * 40f).roundToInt()
        val aq = if (aa) 0 else Math.floorMod(preset.angle.roundToInt(), 360)
        val v = variant.coerceIn(0, TipShapes.variants(preset.tip) - 1)
        val key = Key(preset.tip, bucket, hq, rq, aq, v, aa)
        map[key]?.let { return it }

        val size = if (aa) ceil(tipDiameter).toInt() + 2 else aliasedSize(preset.tip, bucket, aq)
        val data = TipShapes.rasterize(preset.tip, tipDiameter, size, hq / 20f, rq / 40f, aq.toFloat(), v, aa)
        val tip = Tip(BitmapUtils.bytesToAlpha8(data, size, size), tipDiameter, size)
        map[key] = tip
        bytes += tip.bitmap.allocationByteCount
        trim()
        return tip
    }

    private fun trim() {
        val it = map.entries.iterator()
        while (bytes > maxBytes && map.size > 1 && it.hasNext()) {
            val e = it.next()
            bytes -= e.value.bitmap.allocationByteCount
            e.value.bitmap.recycle()
            it.remove()
        }
    }

    fun clear() {
        map.values.forEach { it.bitmap.recycle() }
        map.clear()
        bytes = 0
    }

    companion object {
        /** Size bucket (4 per octave) whose diameter is >= [d]. */
        fun bucketOf(d: Float): Int = if (d <= 1f) 0 else ceil(log2(d) * 4f - 1e-3f).toInt()

        fun bucketDiameter(bucket: Int): Float = 2f.pow(bucket / 4f)

        /** Bitmap size for an aliased tip of integer diameter [n] (rotated squares need room). */
        private fun aliasedSize(tip: BrushTip, n: Int, angle: Int): Int {
            val boxy = tip == BrushTip.SQUARE || tip == BrushTip.MARKER
            return if (boxy && angle % 90 != 0) n + 2 * ceil(n * 0.21f).toInt() else n
        }
    }
}
