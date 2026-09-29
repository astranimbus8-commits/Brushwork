package com.brushwork.paint.filters.pixelate

import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.FilterContext
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sanity check that full-resolution applies stay fast, including the worst-case (smallest) cell
 * and dot sizes, which produce the most cells / dots.
 */
class PixelatePerformanceTest {
    @Test
    fun largeImagesStayFast() {
        val w = System.getProperty("pixelate.perfWidth")?.toIntOrNull() ?: 1600
        val h = w * 3 / 4
        val src = PixelBuffer(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            src[x, y] = ColorUtils.argb(if ((x / 64 + y / 64) % 7 == 0) 0 else 255, x * 255 / w, y * 255 / h, (x xor y) and 0xFF)
        }
        val ctx = FilterContext()
        val report = StringBuilder()
        var worst = 0L
        for (f in pixelateFilters) {
            val cases = listOf(
                "defaults" to f.defaultValues(),
                "smallest" to f.defaultValues().apply {
                    set("size", 1f); set("radius", 1f); set("density", 100f); set("angle", 33f); set("sizing", 1)
                },
            )
            for ((label, v) in cases) {
                // Clamp to the declared minimums (the sliders never go below them).
                for (p in f.params) if (p is com.brushwork.paint.filters.FilterParam.Slider) {
                    val cur = v.float(p.key)
                    v.set(p.key, cur.coerceIn(p.min, p.max))
                }
                val t0 = System.nanoTime()
                val out = f.apply(src, v, ctx)
                val ms = (System.nanoTime() - t0) / 1_000_000
                assertTrue(out.width == w && out.height == h)
                worst = maxOf(worst, ms)
                report.append("${f.id} [$label]: $ms ms\n")
            }
        }
        println("Pixelate timings for ${w}x$h:\n$report")
        assertTrue("slowest pixelate filter took $worst ms\n$report", worst < 20_000)
    }
}
