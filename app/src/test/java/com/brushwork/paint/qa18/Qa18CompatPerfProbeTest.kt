package com.brushwork.paint.qa18

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterValues
import com.brushwork.paint.filters.art.ChromaticAberrationZoomingFilter
import com.brushwork.paint.filters.art.FringeColors
import com.brushwork.paint.filters.art.FringeFixtures
import com.brushwork.paint.filters.art.v17.ChromaticAberrationV17
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.select.SelectionMasks
import com.brushwork.paint.tools.select.colorrange.ColorMasks
import com.brushwork.paint.tools.select.colorrange.ColorPreset
import com.brushwork.paint.tools.select.colorrange.ColorRange
import com.brushwork.paint.tools.select.magnetic.EdgeMap
import com.brushwork.paint.tools.select.magnetic.LiveWire
import com.brushwork.paint.tools.select.magnetic.MagneticTestPictures
import com.brushwork.paint.tools.vector.CogParams
import com.brushwork.paint.tools.vector.CogRig
import com.brushwork.paint.tools.vector.gen.CurveGenerators
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.plainLine
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tool
import com.brushwork.paint.vector.CurveGenKind
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.render.VectorLayerRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import org.robolectric.RobolectricTestRunner

/**
 * v1.8 QA (compat cluster), the §6.3 rows no other test measures at the phone's sizes, on the JVM
 * (Robolectric NATIVE, software Skia). The numbers are JVM numbers: each probe prints them, and
 * the phone's own are device checks. Locally only, never on CI (T606-size documents).
 *
 * The other §6.3 rows with a JVM side are the areas' own Timing tests: Q1 and Q10
 * (GeneratorPerfTest.t606SizeProbes), Q2 and Q3 (SymmetryClonePerfTest), Q4's refusal
 * (CloneMirrorTest), Q5 and Q6 (ArrayLookPerfTest: the host's software Skia tints about 60 times
 * slower than a plain draw, so Q6's absolute 800 ms is the phone's), Q7 (MagneticPerfTest, and
 * LoupeChromeFlipTest for the loupe), Q8 (ColorSelectPerfTest, at 4000 × 5000) and Q11 at 512 × 512
 * (CogPerfTest). FringePerfTest measures Q9's ratio on a small picture only. GeneratorPerfTest,
 * MagneticPerfTest and ColorSelectPerfTest guard without printing a number, so Q1, Q7, Q8 and
 * Q10 are timed here again the same way and printed (guarded at twice the phone budget).
 *
 * - Q9, "Chromatic aberration Zooming, a custom color set, 4000 x 5000: ≤ 1.3 x v1.7": the v1.8
 *   filter with three colors that each feed two or three channels (the general path, the worst
 *   case) against the frozen v1.7 copy at the phone's size, both through Parallel as on the
 *   phone. Each side is first compiled by the JIT on a small picture (one call over a large
 *   picture leaves a loop to on-stack replacement, whose code varies between compiles); then
 *   interleaved pairs at 4000 × 5000, the median of the pairs' ratios.
 * - Q11, "Cog, 64 teeth, Number of teeth drag: ≤ 8 ms per frame", at T606 size: CogPerfTest's
 *   frame (the setting changed, the preview rebuilt, the cog drawn on a layer-size bitmap) on a
 *   2048 × 2048 canvas with a 1248 px cog with a hole. Guarded at twice the phone budget.
 */
@RunWith(RobolectricTestRunner::class)
@Category(Timing::class)
class Qa18CompatPerfProbeTest {
    @Before
    fun notOnCi() = assumeTrue("T606-size probes run locally only", System.getenv("CI") == null)

    private fun median(xs: List<Double>): Double = xs.sorted()[xs.size / 2]

    private fun f(v: Double) = "%.1f".format(v)

    @Test
    fun q9ZoomingWithACustomSetAt4000x5000() {
        val z = ChromaticAberrationZoomingFilter()
        val ctx = FilterContext()
        val custom = intArrayOf(0xFFFF8000.toInt(), 0xFF8040C0.toInt(), 0xFF00A0A0.toInt())
        assertTrue("the general path", FringeColors.oneHotFactors(custom) == null)
        val v18 = z.defaultValues().apply {
            set("distance", 12f)
            set("center", floatArrayOf(0.4f, 0.6f))
            set(FringeColors.KEY, custom)
        }
        val v17 = FilterValues(mapOf("distance" to 12f, "angle" to 30f, "order" to 0, "center" to floatArrayOf(0.4f, 0.6f)))
        // The JIT's full compile of both loops on a small picture.
        val small = FringeFixtures.random(192, 144, 42)
        repeat(400) { z.apply(small, v18, ctx); ChromaticAberrationV17.zooming(small, v17, ctx) }
        val big: PixelBuffer = FringeFixtures.random(4000, 5000, 7)
        var sink = 0
        val (a, b) = PerfBudget.measuredRatioWithin(1.3, tries = 3, what = "Q9: Zooming, a custom set, 4000 × 5000, against v1.7") {
            val ta = ArrayList<Double>()
            val tb = ArrayList<Double>()
            for (i in 0 until 5) {
                val runA = { sink += z.apply(big, v18, ctx).pixels[12345] }
                val runB = { sink += ChromaticAberrationV17.zooming(big, v17, ctx).pixels[12345] }
                if (i % 2 == 0) { ta += PerfBudget.timeMs(runA); tb += PerfBudget.timeMs(runB) } else { tb += PerfBudget.timeMs(runB); ta += PerfBudget.timeMs(runA) }
            }
            val ratio = median(ta.indices.map { ta[it] / tb[it] })
            val mb = median(tb)
            println("[qa18 compat perf] Q9 try: v1.8 ${ta.joinToString { f(it) }} ms; v1.7 ${tb.joinToString { f(it) }} ms; median ratio ${"%.3f".format(ratio)}")
            ratio * mb to mb
        }
        println("[qa18 compat perf] Q9 (JVM): Zooming, a custom set, 4000 × 5000: v1.8 ${f(a)} ms, v1.7 ${f(b)} ms (x${"%.3f".format(a / b)}, phone budget x1.3)")
        assertTrue(sink != 1)
        // Both did the work: most pixels moved (the fringes shift every channel away from the center).
        val n = big.pixels.size
        for ((name, out) in listOf("v1.8" to z.apply(big, v18, ctx), "v1.7" to ChromaticAberrationV17.zooming(big, v17, ctx))) {
            val changed = (0 until n).count { out.pixels[it] != big.pixels[it] }
            println("[qa18 compat perf] Q9 $name changed $changed of $n pixels")
            assertTrue("$name: the fringes changed most pixels ($changed of $n)", changed > n / 2)
        }
    }

    /** A photo-like pixel (ColorSelectPerfTest's): three slow gradients and 0..15 of noise. */
    private fun photo(x: Int, y: Int, w: Int, h: Int): Int {
        var n = x * 73856093 xor y * 19349663
        n = (n xor (n ushr 13)) * 0x5bd1e995
        val noise = (n ushr 28) and 15
        val r = (x * 239 / w + noise).coerceAtMost(255)
        val g = (y * 239 / h + noise).coerceAtMost(255)
        val b = ((x + y) * 239 / (w + h) + (15 - noise)).coerceAtMost(255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** [runs] timed runs of [block] after [warm] warm-up runs: the median, printed with every run. */
    private fun medianOf(what: String, runs: Int, warm: Int = 3, block: () -> Unit): Double {
        repeat(warm) { block() }
        val times = List(runs) { PerfBudget.timeMs(block) }
        val m = median(times)
        println("[qa18 compat perf] $what (JVM): median ${f(m)} ms (${times.joinToString { f(it) }})")
        return m
    }

    /**
     * The rows the areas guard without printing a number (GeneratorPerfTest, MagneticPerfTest,
     * ColorSelectPerfTest), timed again the same way and printed: Q1, Q7, Q8 and Q10. Guarded at
     * twice the phone budget.
     */
    @Test
    fun q1Q7Q8Q10TheAreasRowsInNumbers() {
        // Q1: Kaleidoscope 8, a generated curve of about 300 anchors committed on a vector layer, ≤ 50 ms.
        val c = CurveToolTestSupport.controller()
        val t = c.tool(ToolId.CURVE)
        t.plainLine()
        c.updateSymmetry(SymmetrySettings(type = SymmetryType.KALEIDOSCOPE, divisions = 8))
        val commits = ArrayList<Double>()
        repeat(9) {
            assertTrue(t.startGenerator(CurveGenKind.ARCHIMEDEAN, null))
            t.changeGenerator(t.generator!!.copy(turns = 12f, size = 140f, density = 25))
            assertTrue("about 300 anchors: ${t.pointCount}", t.pointCount in 150..CurveGenerators.MAX_ANCHORS)
            assertTrue("copies", t.hasCopies)
            commits += PerfBudget.timeMs { t.commit() }
        }
        val q1 = median(commits.drop(3))
        println("[qa18 compat perf] Q1 (JVM): a Kaleidoscope 8 commit of a ${CurveGenKind.ARCHIMEDEAN} curve: median ${f(q1)} ms (${commits.joinToString { f(it) }}; the first 3 warm up)")
        assertTrue("Q1 $q1 ms (phone budget 50)", q1 <= 100.0)
        c.updateSymmetry(SymmetrySettings(type = SymmetryType.OFF))

        // Q10: a Golden spiral of T606 size (614 px on a 2048 px canvas), "Turns" drag, ≤ 8 ms per frame (the geometry).
        assertTrue(t.startGenerator(CurveGenKind.GOLDEN, null))
        t.changeGenerator(t.generator!!.copy(size = 0.3f * 2048f))
        val start = t.generator!!
        repeat(20) { i -> t.changeGenerator(start.copy(turns = start.turns + (i % 8) * 0.25f)); t.path().flatten(0.25f) }
        t.beginNumericEdit()
        val frames = (1..40).map { i -> PerfBudget.timeMs { t.changeGenerator(start.copy(turns = start.turns + i * 0.25f)); t.path().flatten(0.25f) } }
        t.endNumericEdit()
        t.discard()
        val q10 = median(frames)
        println("[qa18 compat perf] Q10 (JVM): a 614 px Golden spiral's \"Turns\" drag: median frame ${f(q10)} ms (max ${f(frames.max())})")
        assertTrue("Q10 $q10 ms (phone budget 8)", q10 <= 16.0)

        // Q7: the magnetic lasso on a 2048 px proxy: a 300 px segment ≤ 30 ms, a dragged anchor's two 150 px segments ≤ 16 ms.
        val n = EdgeMap.MAX_SIDE
        val cx = 1024f
        val r = 950f
        fun rim(arc: Float): Pair<Int, Int> {
            val a = PI + arc / r
            return (cx + r * cos(a)).roundToInt() to (cx + r * sin(a)).roundToInt()
        }
        val map = requireNotNull(EdgeMap.fromLuminance(MagneticTestPictures.disc(n, n, cx, cx, r), n, n, 1f))
        val wire = LiveWire()
        val (ax, ay) = rim(0f)
        val (mx, my) = rim(150f)
        val (bx, by) = rim(300f)
        val segment = medianOf("Q7 a 300 px segment on a 2048 px proxy", 9) { requireNotNull(wire.path(map, ax, ay, bx, by, floor = 25)) }
        val retrace = medianOf("Q7 a dragged anchor's two 150 px segments", 9) {
            requireNotNull(wire.path(map, ax, ay, mx, my, floor = 25))
            requireNotNull(wire.path(map, mx, my, bx, by, floor = 25))
        }
        assertTrue("Q7 segment $segment ms (phone budget 30)", segment <= 60.0)
        assertTrue("Q7 retrace $retrace ms (phone budget 16)", retrace <= 32.0)

        // Q8: Color select "Reds": the preview on the 1024 × 1280 proxy of a 4000 × 5000 canvas ≤ 120 ms, Select colors at 4000 × 5000 ≤ 1.5 s.
        val pw = 1024
        val ph = 1280
        val proxy = requireNotNull(ColorMasks.proxy(IntArray(pw * ph) { i -> photo(i % pw, i / pw, pw, ph) }, pw, ph))
        val reds = ColorRange.Params.of(ColorPreset.REDS, 40, IntArray(0), invert = false, antiAlias = true)
        val wide = ColorRange.Params.of(ColorPreset.SAMPLED, 200, IntArray(8) { k -> photo(100 + k * 110, 150 + k * 130, pw, ph) }, invert = false, antiAlias = true)
        val preview = medianOf("Q8 the \"Reds\" preview of a 4000 × 5000 canvas (its 1024 × 1280 proxy)", 9) {
            BitmapUtils.bytesToAlpha8(requireNotNull(ColorMasks.previewMask(proxy, reds)), pw, ph).recycle()
        }
        val previewWide = medianOf("Q8 a preview of 8 samples at fuzziness 200 (the proxy)", 9) {
            BitmapUtils.bytesToAlpha8(requireNotNull(ColorMasks.previewMask(proxy, wide)), pw, ph).recycle()
        }
        val w = 4000
        val h = 5000
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val row = IntArray(w)
        for (y in 0 until h) {
            for (x in 0 until w) row[x] = photo(x, y, w, h)
            bmp.setPixels(row, 0, w, 0, y, w, 1)
        }
        val select = medianOf("Q8 Select colors \"Reds\" at 4000 × 5000 (the mask and the selection)", 3, warm = 1) {
            SelectionMasks.fromWindow(requireNotNull(ColorMasks.fullMask(bmp, reds)), 0, 0, w, h, w, h)
        }
        bmp.recycle()
        assertTrue("Q8 preview $preview / $previewWide ms (phone budget 120)", maxOf(preview, previewWide) <= 240.0)
        assertTrue("Q8 select $select ms (phone budget 1500)", select <= 3000.0)
    }

    @Test
    fun q11ACogOf64TeethDragAtT606Size() {
        val side = 2048
        val rig = CogRig(side, side)
        try {
            val c = rig.controller()
            val tool = rig.cogTool(c, CogParams(teeth = 64, hole = 0.3f))
            rig.drag(c, 400f to 400f, 1024f to 1024f, 1648f to 1648f)
            assertTrue("a pending cog", tool.pendingObject() != null)
            val bitmap = BitmapUtils.createLayerBitmap(side, side)
            val canvas = Canvas(bitmap)
            val whole = Rect(0, 0, side, side)
            val tips = TipCache()
            fun frame(teeth: Int) {
                tool.update { it.copy(cogTeeth = teeth) }
                val o = tool.pendingObject()!!
                bitmap.eraseColor(0)
                VectorLayerRenderer.render(canvas, VectorContent.EMPTY.plus(listOf(VShape(0L, shape = o))).first, whole, tips = tips, document = whole)
            }
            for (t in CogParams.MIN_TEETH..CogParams.MAX_TEETH) frame(t)
            val ms = PerfBudget.measuredWithin(16.0, what = "Q11 at T606 size (phone budget 8 ms)") {
                val times = (CogParams.MAX_TEETH downTo CogParams.MIN_TEETH).map { t -> PerfBudget.timeMs { frame(t) } }
                val m = median(times)
                println("[qa18 compat perf] Q11 try: median ${f(m)} ms over ${times.size} frames (max ${f(times.max())})")
                m
            }
            assertEquals(CogParams.MIN_TEETH, tool.pendingObject()!!.cogTeeth)
            println("[qa18 compat perf] Q11 (JVM): a 64-tooth cog's \"Number of teeth\" drag on 2048 × 2048: median frame ${f(ms)} ms (phone budget 8 ms)")
            bitmap.recycle()
            c.dispose()
        } finally {
            rig.close()
        }
    }
}
