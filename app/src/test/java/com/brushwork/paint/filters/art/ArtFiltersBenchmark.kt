package com.brushwork.paint.filters.art

import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterMath
import org.junit.Ignore
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.hypot

/**
 * Manual checks, run on demand by removing @Ignore: timing of every art filter at 3 megapixels,
 * and PNG renders of every filter (opaque scene and line art on a checkerboard) written to
 * app/build/art-previews for visual inspection.
 */
@Ignore("manual benchmark / visual dump")
class ArtFiltersBenchmark {
    @Test
    fun timeAllArtFilters() {
        val src = scene(2000, 1500)
        for (f in artFilters) {
            val v = f.defaultValues()
            f.apply(src, v, FilterContext()) // warm-up
            val t0 = System.nanoTime()
            f.apply(src, v, FilterContext())
            println("BENCH ${f.id}: ${(System.nanoTime() - t0) / 1_000_000} ms")
        }
    }

    @Test
    fun timeWorstCaseParameters() {
        val src = scene(2000, 1500)
        val white = PixelBuffer.filled(2000, 1500, -1)
        val cases = listOf(
            Triple(SheerFilter(SheerShape.SQUARE), src, mapOf("size" to 1f, "amount" to 100f)),
            Triple(SheerFilter(SheerShape.LINE), src, mapOf("size" to 100f, "amount" to 100f)),
            Triple(SheerFilter(SheerShape.CROSS), src, mapOf("size" to 1f, "amount" to 100f)),
            Triple(CrossFilter(), white, mapOf("count" to 16f, "length" to 1000f, "thickness" to 10f, "area" to 100f, "brightness" to 200f)),
            Triple(CrossFilter(), src, mapOf("count" to 16f, "length" to 5f, "area" to 100f)),
            Triple(NoiseFilter(), src, mapOf("size" to 1.5f, "mode" to 1, "distribution" to 1)),
            Triple(OilPaintFilter(), src, mapOf("size" to 30f, "smoothness" to 100f, "detail" to 100f, "texture" to 100f)),
            Triple(RetroGameFilter(), src, mapOf("dotSize" to 1f, "palette" to 2)),
            Triple(GlitchFilter(), src, mapOf("height" to 1f, "strength" to 100f, "noise" to 100f, "blocks" to 100f)),
            Triple(BloomFilter(), src, mapOf("radius" to 200f, "area" to 100f)),
            Triple(BloomFilter(), src, mapOf("radius" to 1f, "area" to 100f)),
            Triple(ChromeFilter(), lineArt(2000, 1500), mapOf("smoothness" to 10f)),
        )
        for ((f, img, params) in cases) {
            val v = f.defaultValues()
            for ((k, value) in params) v.set(k, value)
            val t0 = System.nanoTime()
            f.apply(img, v, FilterContext())
            println("WORST ${f.id} $params: ${(System.nanoTime() - t0) / 1_000_000} ms")
        }
    }

    /**
     * Single-threaded cost per megapixel (Parallel runs inline on its own worker threads), a rough
     * proxy for phones: divide by ~3 for an 8-core phone and multiply by 12 for 12 megapixels.
     */
    @Test
    fun timeSingleThreadPerMegapixel() {
        val src = scene(1000, 1000)
        var error: Throwable? = null
        val t = Thread({
            try {
                for (f in artFilters) {
                    val v = f.defaultValues()
                    f.apply(src, v, FilterContext())
                    val t0 = System.nanoTime()
                    f.apply(src, v, FilterContext())
                    println("SINGLE ${f.id}: ${(System.nanoTime() - t0) / 1_000_000} ms/MP")
                }
                val oil = OilPaintFilter()
                for (texture in listOf(0f, 40f)) {
                    val v = oil.defaultValues().set("texture", texture)
                    oil.apply(src, v, FilterContext())
                    val t0 = System.nanoTime()
                    oil.apply(src, v, FilterContext())
                    println("SINGLE oil texture=$texture: ${(System.nanoTime() - t0) / 1_000_000} ms/MP")
                }
            } catch (e: Throwable) { error = e }
        }, "bw-parallel-bench")
        t.start(); t.join()
        error?.let { throw it }
    }

    @Test
    fun dumpPreviews() {
        val dir = File("build/art-previews").apply { mkdirs() }
        val scene = scene(480, 320)
        val art = lineArt(480, 320)
        save(scene, File(dir, "_scene.png"))
        save(art, File(dir, "_lineart.png"))
        for (f in artFilters) {
            val v = f.defaultValues()
            save(f.apply(scene, v, FilterContext()), File(dir, "${f.id}.png"))
            save(f.apply(art, v, FilterContext()), File(dir, "${f.id}.lineart.png"))
        }
    }

    private fun scene(w: Int, h: Int): PixelBuffer {
        val p = PixelBuffer(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val t = y.toFloat() / h
            val n = FilterMath.fbm(x / 40f, y / 40f, 3, 5)
            var c = ColorUtils.argb(255, (20 + 200 * t + 30 * n).toInt(), (30 + 90 * t).toInt(), (120 - 60 * t + 30 * n).toInt())
            if (hypot(x - w * 0.3, y - h * 0.55) < h * 0.2) c = 0xFF2E8B57.toInt()
            if (x > w * 0.6 && x < w * 0.85 && y > h * 0.3 && y < h * 0.7) c = 0xFFE0C040.toInt()
            if (kotlin.math.abs((x - y * 1.2f) - w * 0.15f) < 4) c = 0xFF101010.toInt()
            p[x, y] = c
        }
        for (i in 0 until 12) {
            val sx = (FilterMath.hash01(i, 1, 9) * w).toInt(); val sy = (FilterMath.hash01(i, 2, 9) * h * 0.4f).toInt()
            for (dy in -1..1) for (dx in -1..1) if (p.inBounds(sx + dx, sy + dy)) p[sx + dx, sy + dy] = -1
        }
        return p
    }

    private fun lineArt(w: Int, h: Int): PixelBuffer {
        val p = PixelBuffer(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val d = hypot(x - w / 2.0, y - h / 2.0)
            if (kotlin.math.abs(d - h * 0.3) < 3) p[x, y] = 0xFF101010.toInt()
            if (kotlin.math.abs(y - h * 0.2 - (x - w * 0.1) * 0.3) < 5 && x in 40..440) p[x, y] = 0xFFFFFFFF.toInt()
            if (d < h * 0.1) p[x, y] = 0xFFFF4060.toInt()
        }
        return p
    }

    /** Writes [p] composited over a light checkerboard so transparency is visible. */
    private fun save(p: PixelBuffer, file: File) {
        val img = BufferedImage(p.width, p.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until p.height) for (x in 0 until p.width) {
            val bg = if ((x / 8 + y / 8) % 2 == 0) 0xFFC8C8C8.toInt() else 0xFF989898.toInt()
            img.setRGB(x, y, ColorUtils.over(p[x, y], bg) and 0xFFFFFF)
        }
        ImageIO.write(img, "png", file)
    }
}
