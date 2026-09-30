package com.brushwork.paint.inpaint

import com.brushwork.paint.core.PixelBuffer
import java.io.File
import kotlin.math.abs
import kotlin.math.sin

/** Synthetic images and helpers for the content-aware fill tests. */
internal object InpaintTestImages {

    fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    /** Vertical black / white stripes, [period] px wide in total (half black, half white). */
    fun stripes(w: Int, h: Int, period: Int = 8): PixelBuffer =
        PixelBuffer(w, h, IntArray(w * h) { i -> stripeColor(i % w, period) })

    fun stripeColor(x: Int, period: Int = 8): Int = if (x % period < period / 2) 0xFF000000.toInt() else -1

    /** Horizontal color ramp: red grows with x, blue falls, green constant. */
    fun gradient(w: Int, h: Int): PixelBuffer = PixelBuffer(w, h, IntArray(w * h) { i -> gradientColor(i % w, w) })

    fun gradientColor(x: Int, w: Int): Int {
        val t = x * 255 / (w - 1)
        return argb(255, t, 90, 255 - t)
    }

    /** Deterministic colorful noise texture over a soft gradient (like foliage / gravel). */
    fun texture(w: Int, h: Int, seed: Int = 3): PixelBuffer {
        val px = IntArray(w * h)
        var s = seed * 0x9E3779B1.toInt() + 1
        for (y in 0 until h) for (x in 0 until w) {
            s = s xor (s shl 13); s = s xor (s ushr 17); s = s xor (s shl 5)
            val n = (s ushr 24) and 0x3F
            val base = 60 + (x * 100 / w)
            val wave = (20 * sin(x * 0.35) * sin(y * 0.21)).toInt()
            px[y * w + x] = argb(255, (base + n + wave).coerceIn(0, 255), (120 + n / 2 - wave).coerceIn(0, 255), (40 + y * 80 / h + n).coerceIn(0, 255))
        }
        return PixelBuffer(w, h, px)
    }

    /**
     * A landscape: a sky gradient above the row [horizon], a noisy grass texture below it (a
     * straight structure the fill must carry across a hole).
     */
    fun landscape(w: Int, h: Int, horizon: Int, seed: Int = 4): PixelBuffer {
        val px = IntArray(w * h)
        var s = seed * 0x9E3779B1.toInt() + 7
        for (y in 0 until h) for (x in 0 until w) {
            s = s xor (s shl 13); s = s xor (s ushr 17); s = s xor (s shl 5)
            px[y * w + x] = if (y < horizon) skyColor(y, horizon) else {
                val n = (s ushr 24) and 0x1F
                argb(255, 40 + n, 110 + n * 2, 30 + n / 2)
            }
        }
        return PixelBuffer(w, h, px)
    }

    fun skyColor(y: Int, horizon: Int): Int {
        val t = y * 255 / maxOf(1, horizon - 1)
        return argb(255, 90 + t * 100 / 255, 150 + t * 80 / 255, 235)
    }

    /** True if [c] looks like the grass of [landscape] (green dominates, darker than the sky). */
    fun isGrass(c: Int): Boolean {
        val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
        return g > r + 30 && g > b + 30 && b < 120
    }

    /** A 0 / 255 mask with [rect] set. */
    fun rectMask(w: Int, h: Int, rect: IRect): ByteArray {
        val m = ByteArray(w * h)
        for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) m[y * w + x] = -1
        return m
    }

    /** [image] with [rect] painted [color] (content that must disappear). */
    fun paint(image: PixelBuffer, rect: IRect, color: Int): PixelBuffer {
        val out = image.copy()
        for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) out[x, y] = color
        return out
    }

    fun channelDiff(a: Int, b: Int): Int {
        var m = 0
        for (sh in intArrayOf(0, 8, 16, 24)) m = maxOf(m, abs(((a ushr sh) and 0xFF) - ((b ushr sh) and 0xFF)))
        return m
    }

    /** Writes [img] as a PNG when BW_CAF_DUMP names a folder (manual visual checks). */
    fun dump(name: String, img: PixelBuffer) {
        val dir = System.getenv("BW_CAF_DUMP") ?: return
        val bi = java.awt.image.BufferedImage(img.width, img.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        bi.setRGB(0, 0, img.width, img.height, img.pixels, 0, img.width)
        File(dir).mkdirs()
        javax.imageio.ImageIO.write(bi, "png", File(dir, "$name.png"))
    }
}
