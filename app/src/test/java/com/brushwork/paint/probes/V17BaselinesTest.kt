package com.brushwork.paint.probes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.StrokeRaster
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.engine.DisplayTiles
import com.brushwork.paint.qa16.QaDocs
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.tools.vector.spline.SplineBezier
import com.brushwork.paint.vector.VSpline
import com.brushwork.paint.vector.VSplinePoint
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * v1.7 F0 baselines (§6.3): the v1.6 cost of the work v1.7 budgets against, measured on the JVM
 * (Robolectric NATIVE) so later steps can compare like with like; T606 times are device-only.
 * Each prints its numbers and guards only against order-of-magnitude regressions. The 20 MP
 * document runs locally and on the device, never on CI (§6.2).
 *
 * - a fresh conversion of a 2 000-point order-6 spline with random widths (item 5's "≤ 2.0 × the
 *   F0 baseline");
 * - a 4000 × 5000 document fitted to the phone: every display tile rendered, then the tiles
 *   drawn onto the screen (item 8's "no-folder baseline");
 * - one stroke replayed 64 times (item 18's 64-copy symmetry, before copies exist).
 */
@RunWith(RobolectricTestRunner::class)
class V17BaselinesTest {
    @Before
    fun notOnCi() = assumeTrue("baselines run locally only", System.getenv("CI") == null)

    private fun median(xs: List<Double>): Double = xs.sorted()[xs.size / 2]

    private inline fun timeMs(block: () -> Unit): Double {
        val t0 = System.nanoTime()
        block()
        return (System.nanoTime() - t0) / 1e6
    }

    @Test
    fun aFreshConversionOf2000PointsAtOrder6() {
        val rnd = Random(17)
        var x = 2000f
        var y = 2500f
        var heading = 0f
        val pts = List(VSpline.MAX_POINTS) {
            heading += (rnd.nextFloat() - 0.5f) * 1.2f
            x = (x + 18f * cos(heading)).coerceIn(0f, 4000f)
            y = (y + 18f * sin(heading)).coerceIn(0f, 5000f)
            VSplinePoint(x, y, width = rnd.nextFloat() * VSpline.MAX_WIDTH)
        }
        val spline = VSpline(pts, order = 6)
        repeat(3) { SplineBezier.toSubpath(spline) } // warm up (JIT)
        var anchors = 0
        val times = List(9) { timeMs { anchors = SplineBezier.toSubpath(spline).anchors.size } }
        val med = median(times)
        println("v17 F0 baseline: fresh conversion, 2000 points, order 6, random widths: median ${"%.1f".format(med)} ms (${times.joinToString { "%.1f".format(it) }}), $anchors anchors")
        assertTrue("conversion $med ms", med <= PerfBudget.ms(2_000.0))
    }

    @Test
    fun a4000x5000DocumentFittedToThePhone() {
        val w = 4000
        val h = 5000
        val doc = QaDocs.stack(w, h)
        val compositor = Compositor(doc) { null }
        val tiles = DisplayTiles(w, h)
        // The phone's canvas (1080 x 2408 px) with the document fitted to its width.
        val screen = Bitmap.createBitmap(1080, 2408, Bitmap.Config.ARGB_8888)
        val fit = Matrix().apply { setScale(1080f / w, 1080f / w) }
        val all = Rect(0, 0, w, h)
        fun redraw(): Pair<Double, Double> {
            tiles.invalidate(null)
            val render = timeMs { tiles.update(compositor, all) }
            val show = timeMs {
                val cv = Canvas(screen)
                cv.drawColor(0xFF808080.toInt())
                cv.concat(fit)
                tiles.draw(cv, all)
            }
            return render to show
        }
        redraw() // warm up (tile bitmaps, JIT)
        val runs = List(3) { redraw() }
        val render = median(runs.map { it.first })
        val show = median(runs.map { it.second })
        println("v17 F0 baseline: 4000 x 5000, 5 layers (1 Multiply), fitted to 1080 x 2408: full tile render median ${"%.0f".format(render)} ms, tiles to screen median ${"%.1f".format(show)} ms (${runs.joinToString { "%.0f+%.1f".format(it.first, it.second) }})")
        tiles.release()
        for (l in doc.layers) l.bitmap.recycle()
        assertTrue("full render $render ms", render <= PerfBudget.ms(60_000.0))
        assertTrue("tiles to screen $show ms", show <= PerfBudget.ms(5_000.0))
    }

    @Test
    fun oneStrokeReplayed64Times() {
        val w = 2000
        val h = 2000
        val n = 400
        val pts = PackedPoints(
            FloatArray(n) { 200f + 1600f * it / (n - 1) },
            FloatArray(n) { 1000f + 600f * sin(it * 0.03f) },
            FloatArray(n) { 0.5f + 0.5f * sin(it * 0.07f) },
        )
        val preset = BrushLibrary.byId("softround")!!.copy(size = 24f)
        val raster = StrokeRaster(TipCache())
        val out = BitmapUtils.createLayerBitmap(w, h)
        val doc = Rect(0, 0, w, h)
        fun once() = raster.render(Canvas(out), doc, preset, -16777216, 3L, false, pts, cut = doc)
        repeat(5) { once() } // warm up (tips, JIT)
        val single = median(List(5) { timeMs { once() } })
        val copies = median(List(3) { timeMs { repeat(64) { once() } } })
        println("v17 F0 baseline: 400-point softround 24 px stroke on 2000 x 2000: one replay median ${"%.2f".format(single)} ms, 64 replays median ${"%.0f".format(copies)} ms (${"%.2f".format(copies / 64)} ms per copy)")
        out.recycle()
        assertTrue("64 replays $copies ms", copies <= PerfBudget.ms(60_000.0))
    }
}
