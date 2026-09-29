package com.brushwork.paint.filters.style

import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.FilterContext
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.hypot
import kotlin.math.max

/**
 * Developer tool, not a regression test: renders every Style filter (defaults plus a few variants)
 * on synthetic art as PNGs over a checkerboard and prints full-resolution (12 MP) timings, for
 * eyeballing results while tuning. Does nothing unless the `BW_STYLE_DUMP` environment variable
 * names an output folder, e.g. (PowerShell)
 * `$env:BW_STYLE_DUMP = 'C:\tmp\style'; gradlew testDebugUnitTest --tests "*StyleVisualDump" --rerun --info`.
 */
class StyleVisualDump {

    private fun shapes(w: Int, h: Int): PixelBuffer {
        val b = PixelBuffer(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            var cov = 0f; var col = 0
            // 4x4 supersampling
            var cCircle = 0; var cRect = 0; var cRing = 0; var cLine = 0
            for (sy in 0 until 4) for (sx in 0 until 4) {
                val px = x + (sx + 0.5f) / 4f; val py = y + (sy + 0.5f) / 4f
                if (hypot(px - 90f, py - 110f) < 60f) cCircle++
                if (px in 180f..300f && py in 50f..170f && hypot(max(0f, max(200f - px, px - 280f)), max(0f, max(70f - py, py - 150f))) < 20f) cRect++
                val r = hypot(px - 390f, py - 110f); if (r in 35f..55f) cRing++
                // diagonal thin line
                val d = kotlin.math.abs((py - 200f) - 0.3f * (px - 20f)) / 1.044f
                if (d < 2f && px in 20f..460f) cLine++
            }
            when {
                cLine > 0 -> { cov = cLine / 16f; col = 0xFF111111.toInt() }
                cCircle > 0 -> { cov = cCircle / 16f; col = 0xFFFF8A00.toInt() }
                cRect > 0 -> { cov = cRect / 16f; col = 0xFF2E7DFF.toInt() }
                cRing > 0 -> { cov = cRing / 16f; col = 0xFF22AA55.toInt() }
            }
            if (cov > 0f) b[x, y] = ColorUtils.withAlpha(col, (cov * 255f + 0.5f).toInt())
        }
        return b
    }

    private fun dots(w: Int, h: Int): PixelBuffer {
        val b = PixelBuffer(w, h)
        val centers = listOf(Triple(80f, 80f, 40f), Triple(200f, 130f, 25f), Triple(320f, 90f, 55f), Triple(420f, 180f, 18f), Triple(120f, 190f, 30f))
        for (y in 0 until h) for (x in 0 until w) {
            var cnt = 0
            for (sy in 0 until 4) for (sx in 0 until 4) {
                val px = x + (sx + 0.5f) / 4f; val py = y + (sy + 0.5f) / 4f
                if (centers.any { (cx, cy, r) -> hypot(px - cx, (py - cy) * 1.1f) < r }) cnt++
            }
            if (cnt > 0) b[x, y] = ColorUtils.withAlpha(0xFF9AD0FF.toInt(), cnt * 255 / 16)
        }
        return b
    }

    private fun painting(w: Int, h: Int): PixelBuffer {
        val b = PixelBuffer(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val sky = ColorUtils.lerp(0xFF203060.toInt(), 0xFFFFD090.toInt(), y / h.toFloat())
            var c = sky
            if (hypot(x - 240f, y - 60f) < 30f) c = 0xFFFFFFE0.toInt()
            if (y > 150 + 20 * kotlin.math.sin(x / 40f)) c = 0xFF2F6030.toInt()
            if (x in 60..140 && y in 90..200) c = 0xFF101010.toInt()
            if (x in 330..360 && y in 40..200) c = 0xFF151515.toInt()
            b[x, y] = c
        }
        return b
    }

    private fun save(img: PixelBuffer, file: File) {
        val bi = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val bg = if (((x / 8) + (y / 8)) % 2 == 0) 0xFFE8E8E8.toInt() else 0xFFFFFFFF.toInt()
            bi.setRGB(x, y, ColorUtils.over(img[x, y], bg))
        }
        ImageIO.write(bi, "png", file)
    }

    @Test
    fun dump() {
        val dir = System.getenv("BW_STYLE_DUMP") ?: return
        val out = File(dir).apply { mkdirs() }
        val w = 480; val h = 240
        val inputs = mapOf("shapes" to shapes(w, h), "dots" to dots(w, h), "painting" to painting(w, h))
        inputs.forEach { (n, img) -> save(img, File(out, "_$n.png")) }
        for (f in styleFilters) {
            val src = when (f.id) {
                "style.waterdrop" -> inputs.getValue("dots")
                "style.stained_glass_cells" -> inputs.getValue("painting")
                else -> inputs.getValue("shapes")
            }
            val t0 = System.nanoTime()
            val res = f.apply(src, f.defaultValues(), FilterContext())
            val ms = (System.nanoTime() - t0) / 1_000_000
            println("${f.id}: ${ms}ms")
            save(res, File(out, f.id.removePrefix("style.") + ".png"))
        }
        // Extra variants
        val painting = inputs.getValue("painting")
        save(StainedGlassFilter().apply(painting, StainedGlassFilter().defaultValues(), FilterContext()), File(out, "stained_glass_painting.png"))
        save(ReliefFilter().let { it.apply(painting, it.defaultValues().set("source", 1), FilterContext()) }, File(out, "relief_brightness.png"))
        save(ReliefHQFilter().let { it.apply(painting, it.defaultValues().set("source", 1), FilterContext()) }, File(out, "relief_hq_brightness.png"))
        save(ReliefHQFilter().let { it.apply(inputs.getValue("shapes"), it.defaultValues().set("metallic", 100f).set("roughness", 25f), FilterContext()) }, File(out, "relief_hq_metal.png"))
        save(GodRaysFilter().let { it.apply(painting, it.defaultValues().set("source", 1).set("light", floatArrayOf(0.5f, 0.25f)), FilterContext()) }, File(out, "god_rays_bright.png"))
        save(GlowOuterFilter().let { it.apply(inputs.getValue("shapes"), it.defaultValues().set("crystal", true), FilterContext()) }, File(out, "glow_outer_crystal.png"))
        save(GlowInnerFilter().let { it.apply(inputs.getValue("shapes"), it.defaultValues().set("crystal", true).set("color", 0xFFFFEE00.toInt()), FilterContext()) }, File(out, "glow_inner_crystal.png"))
        save(ExtrudeParallelFilter().let { it.apply(inputs.getValue("shapes"), it.defaultValues().set("angle", 250f).set("depth", 60f).set("side_color", 1), FilterContext()) }, File(out, "extrude_layer_colors.png"))
        save(ReliefFilter().let { it.apply(inputs.getValue("dots"), it.defaultValues(), FilterContext()) }, File(out, "relief_dots.png"))
        // Preview-scale consistency
        val half = PixelBuffer(w / 2, h / 2).also { hb -> for (y in 0 until h / 2) for (x in 0 until w / 2) hb[x, y] = inputs.getValue("shapes")[x * 2, y * 2] }
        save(StrokeOuterFilter().apply(half, StrokeOuterFilter().defaultValues(), FilterContext(scale = 0.5f)), File(out, "stroke_outer_half.png"))
        // Timing on a large canvas
        val big = PixelBuffer(3000, 4000)
        val sh = shapes(w, h)
        for (y in 0 until 4000) for (x in 0 until 3000) big[x, y] = sh[(x * w) / 3000, (y * h) / 4000]
        for (f in styleFilters) {
            val t0 = System.nanoTime()
            f.apply(big, f.defaultValues().also { v -> if (f.id == "style.extrude_parallel") v.set("depth", 400f) }, FilterContext())
            println("BIG ${f.id}: ${(System.nanoTime() - t0) / 1_000_000}ms")
        }
    }
}
