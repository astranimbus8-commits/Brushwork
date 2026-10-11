package com.brushwork.paint.qa18

import android.graphics.Bitmap
import java.util.Random
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * v1.8 final QA (the selection tools): photo-like 512 × 512 pictures, the same on every run (fixed
 * seeds). Grain is added to the three channels alike, so it changes a color's lightness and never
 * its hue or saturation (a gray stays a gray for Color select's hue chips).
 */
internal object Qa18SelectPictures {
    const val SIZE = 512

    /** The orange (a fruit): centre and radius (document px). */
    const val FRUIT_X = 256f
    const val FRUIT_Y = 290f
    const val FRUIT_R = 140f

    /** A lemon near the top of the canvas (for "Add to selection" and the loupe's flip). */
    const val LEMON_X = 92f
    const val LEMON_Y = 88f
    const val LEMON_R = 56f

    /** A dark bruise inside the orange (for "Subtract from selection"). */
    const val SPOT_X = 262f
    const val SPOT_Y = 318f
    const val SPOT_R = 44f

    private fun clamp(v: Int) = v.coerceIn(0, 255)

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (clamp(r) shl 16) or (clamp(g) shl 8) or clamp(b)

    /** Smooth value noise in -1..1 with cells of [cell] px (bilinear between random corners). */
    private class ValueNoise(seed: Long, private val cell: Int) {
        private val n = SIZE / cell + 2
        private val v = Random(seed).let { r -> FloatArray(n * n) { r.nextFloat() * 2f - 1f } }
        fun at(x: Float, y: Float): Float {
            val fx = x / cell; val fy = y / cell
            val ix = floor(fx).toInt(); val iy = floor(fy).toInt()
            val tx = fx - ix; val ty = fy - iy
            fun g(i: Int, j: Int) = v[Math.floorMod(j, n) * n + Math.floorMod(i, n)]
            val a = g(ix, iy) + (g(ix + 1, iy) - g(ix, iy)) * tx
            val b = g(ix, iy + 1) + (g(ix + 1, iy + 1) - g(ix, iy + 1)) * tx
            return a + (b - a) * ty
        }
    }

    /** Coverage 0..1 of the disc ([cx], [cy], [r]) over pixel ([x], [y]) (4 × 4 samples near the rim). */
    private fun cover(x: Int, y: Int, cx: Float, cy: Float, r: Float): Float {
        val d = hypot(x + 0.5f - cx, y + 0.5f - cy)
        if (d <= r - 1f) return 1f
        if (d >= r + 1f) return 0f
        var inside = 0
        for (sy in 0 until 4) for (sx in 0 until 4) {
            if (hypot(x + (sx + 0.5f) / 4f - cx, y + (sy + 0.5f) / 4f - cy) <= r) inside++
        }
        return inside / 16f
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = (((a shr s) and 0xFF) * (1f - t) + ((b shr s) and 0xFF) * t).roundToInt()
        return argb(ch(16), ch(8), ch(0))
    }

    /**
     * An orange on a green-gray cloth (woven texture, soft blotches and grain), lit from the top
     * left; a lemon near the top left; a dark bruise on the orange. Edges are anti-aliased.
     */
    fun fruit(bmp: Bitmap) {
        require(bmp.width == SIZE && bmp.height == SIZE)
        val blotch = ValueNoise(11, 24)
        val fine = ValueNoise(12, 5)
        val grain = Random(13)
        val px = IntArray(SIZE * SIZE)
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            // The cloth: weave (a fine diagonal pattern), blotches and grain, about lum 85 ± 18.
            val weave = 6f * sin((x + y) * 0.9f) * sin((x - y) * 0.9f)
            val l = 9f * blotch.at(x.toFloat(), y.toFloat()) + 5f * fine.at(x.toFloat(), y.toFloat()) + weave + (grain.nextFloat() * 8f - 4f)
            val cloth = argb((78 + l).roundToInt(), (96 + l).roundToInt(), (70 + l).roundToInt())
            var c = cloth
            // The orange, shaded from 1.05 (top left) to 0.85 (bottom right), with a dimpled peel.
            val fc = cover(x, y, FRUIT_X, FRUIT_Y, FRUIT_R)
            if (fc > 0f) {
                val t = ((x - FRUIT_X) * 0.6f + (y - FRUIT_Y) * 0.8f) / FRUIT_R
                val shade = 1.05f - 0.1f * (t + 1f)
                val dimple = 4f * fine.at(x * 1.7f, y * 1.7f) + (grain.nextFloat() * 6f - 3f)
                var orange = argb((236 * shade + dimple).roundToInt(), (138 * shade + dimple).roundToInt(), (34 * shade + dimple).roundToInt())
                val sc = cover(x, y, SPOT_X, SPOT_Y, SPOT_R)
                if (sc > 0f) orange = mix(orange, argb((92 + dimple).roundToInt(), (52 + dimple).roundToInt(), (22 + dimple).roundToInt()), sc)
                c = mix(cloth, orange, fc)
            }
            val lc = cover(x, y, LEMON_X, LEMON_Y, LEMON_R)
            if (lc > 0f) {
                val g = grain.nextFloat() * 6f - 3f
                c = mix(c, argb((242 + g).roundToInt(), (218 + g).roundToInt(), (64 + g).roundToInt()), lc)
            }
            px[y * SIZE + x] = c
        }
        bmp.setPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
    }

    /** Distance (px) from ([x], [y]) to the orange's rim. */
    fun fromFruitRim(x: Float, y: Float): Float = kotlin.math.abs(hypot(x - FRUIT_X, y - FRUIT_Y) - FRUIT_R)

    /** Distance (px) from ([x], [y]) to the lemon's rim. */
    fun fromLemonRim(x: Float, y: Float): Float = kotlin.math.abs(hypot(x - LEMON_X, y - LEMON_Y) - LEMON_R)

    /** Distance (px) from ([x], [y]) to the bruise's rim. */
    fun fromSpotRim(x: Float, y: Float): Float = kotlin.math.abs(hypot(x - SPOT_X, y - SPOT_Y) - SPOT_R)
}
