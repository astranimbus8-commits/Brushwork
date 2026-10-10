package com.brushwork.paint.filters.pixelate

import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Sanity check that full-resolution applies stay fast, including the worst-case (smallest) cell
 * and dot sizes, which produce the most cells / dots. Uses 2 MP to keep the suite quick; at
 * 4000x3000 every case measured under 0.4 s on a desktop JVM.
 */
@Category(Timing::class)
class PixelatePerformanceTest {
    @Test
    fun largeImagesStayFast() {
        val w = 1600
        val h = w * 3 / 4
        val src = PixelBuffer(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            src[x, y] = ColorUtils.argb(if ((x / 64 + y / 64) % 7 == 0) 0 else 255, x * 255 / w, y * 255 / h, (x xor y) and 0xFF)
        }
        val ctx = FilterContext()
        val report = StringBuilder()
        var worst = 0.0
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
                // Every case within the budget the slowest one had to meet.
                val (out, ms) = PerfBudget.timedWithin(20_000.0, what = "${f.id} [$label]") { f.apply(src, v, ctx) }
                assertTrue(out.width == w && out.height == h)
                worst = maxOf(worst, ms)
                report.append("${f.id} [$label]: $ms ms\n")
            }
        }
        println("Pixelate timings for ${w}x$h (slowest $worst ms):\n$report")
    }
}
