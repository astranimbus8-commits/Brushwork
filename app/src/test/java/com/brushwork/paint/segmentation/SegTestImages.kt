package com.brushwork.paint.segmentation

import com.brushwork.paint.core.PixelBuffer
import kotlin.math.roundToInt
import kotlin.random.Random

/** Deterministic synthetic scenes for segmentation tests. */
object SegTestImages {
    const val SKY_BLUE = 0xFF6EA0E6.toInt()      // (110,160,230), luma ~0.60
    const val LIGHT_SKY = 0xFF87B4EB.toInt()     // (135,180,235)
    const val FOLIAGE = 0xFF326E28.toInt()       // (50,110,40), luma ~0.33
    const val SEA = 0xFF1E5096.toInt()           // (30,80,150)
    const val FACADE = 0xFF969696.toInt()        // (150,150,150)
    const val WINDOW = 0xFF3C3C46.toInt()        // (60,60,70)
    const val SKIN = 0xFFE0AC90.toInt()          // (224,172,144)
    const val GRAY_BG = 0xFF808080.toInt()
    const val RED = 0xFFDC2828.toInt()

    fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** [base] with a random luminance offset in +-[amount] plus +-[amount]/4 per channel. */
    fun noisy(base: Int, amount: Int, rnd: Random): Int {
        if (amount == 0) return base
        val o = rnd.nextInt(-amount, amount + 1)
        val q = amount / 4 + 1
        return rgb(
            ((base shr 16) and 0xFF) + o + rnd.nextInt(-q, q + 1),
            ((base shr 8) and 0xFF) + o + rnd.nextInt(-q, q + 1),
            (base and 0xFF) + o + rnd.nextInt(-q, q + 1),
        )
    }

    /** Smooth blue sky above [horizon] (fraction of the height), textured foliage below. */
    fun skyOverFoliage(w: Int, h: Int, horizon: Float = 0.5f, seed: Int = 1): PixelBuffer {
        val rnd = Random(seed)
        val split = (h * horizon).roundToInt()
        return PixelBuffer(w, h).also { img ->
            for (y in 0 until h) for (x in 0 until w) {
                img[x, y] = if (y < split) noisy(SKY_BLUE, 2, rnd) else noisy(FOLIAGE, 28, rnd)
            }
        }
    }

    /** Sky (top 40%), a textured land strip, then rippled sea (bottom 45%). */
    fun seascape(w: Int, h: Int, seed: Int = 2): PixelBuffer {
        val rnd = Random(seed)
        return PixelBuffer(w, h).also { img ->
            for (y in 0 until h) for (x in 0 until w) {
                val fy = y.toFloat() / h
                img[x, y] = when {
                    fy < 0.40f -> noisy(LIGHT_SKY, 2, rnd)
                    fy < 0.55f -> noisy(FOLIAGE, 28, rnd)
                    else -> noisy(SEA, 5, rnd)
                }
            }
        }
    }

    /** Sky above [horizon], a gray facade with a grid of dark windows below. */
    fun facade(w: Int, h: Int, horizon: Float = 0.45f): PixelBuffer {
        val split = (h * horizon).toInt()
        val cell = maxOf(8, w / 16)
        return PixelBuffer(w, h).also { img ->
            for (y in 0 until h) for (x in 0 until w) {
                img[x, y] = when {
                    y < split -> SKY_BLUE
                    (x % cell) in (cell / 4) until (3 * cell / 4) && ((y - split) % cell) in (cell / 4) until (3 * cell / 4) -> WINDOW
                    else -> FACADE
                }
            }
        }
    }

    /** A filled disc of [fg] (radius = [radiusFrac] of the short side) centered on [bg]. */
    fun disc(w: Int, h: Int, bg: Int, fg: Int, radiusFrac: Float = 0.25f, cx: Float = 0.5f, cy: Float = 0.5f): PixelBuffer {
        val r = minOf(w, h) * radiusFrac
        val ccx = w * cx; val ccy = h * cy
        return PixelBuffer(w, h).also { img ->
            for (y in 0 until h) for (x in 0 until w) {
                val dx = x + 0.5f - ccx; val dy = y + 0.5f - ccy
                img[x, y] = if (dx * dx + dy * dy <= r * r) fg else bg
            }
        }
    }

    /** Mean of [m] over the rectangle [x0, x1) x [y0, y1) of a [w]-wide plane. */
    fun mean(m: FloatArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int): Float {
        var s = 0.0
        for (y in y0 until y1) for (x in x0 until x1) s += m[y * w + x]
        return (s / ((x1 - x0) * (y1 - y0))).toFloat()
    }
}
