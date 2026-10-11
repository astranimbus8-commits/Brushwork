package com.brushwork.paint.array

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import com.brushwork.paint.array.ArrayLookKit.RED_TO_BLUE
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.ArrayInstances
import com.brushwork.paint.model.ArrayLook
import com.brushwork.paint.model.ArrayMode
import com.brushwork.paint.model.ArrayPixels
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.LayerArray
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.model.Selection
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.array.ArrayTool
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextSpec
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.math.min

/**
 * v1.8 (area B, design §3.3 c "T606 budgets", §6.3 Q5 and Q6): what the look costs, measured on
 * the JVM (Robolectric NATIVE, software Skia), locally only (never on CI).
 *
 * The host's software Skia draws through a color filter (the tint's `ColorMatrixColorFilter`)
 * about 60 times slower than a plain bitmap draw (measured here: about 1.4 µs per copy pixel
 * against about 25 ns; 200 tinted copies of 500 × 500 took 54 s), where the phone's GPU (the
 * preview overlay) and its NEON raster pipeline (the worker's cache) pay a small factor. The
 * T606 budgets of the tinted rows (a preview frame of 64 copies ≤ 33 ms, 200 tinted pixel copies
 * of a 4000 × 5000 cache ≤ 800 ms, 32 tinted copies of a 1500 × 400 text ≤ 320 ms) are therefore
 * device checks (§6.3 Q5, Q6); here each row is guarded by what the host CAN tell, timed back to
 * back:
 * - no look costs what v1.7 cost (± 5 %, T606 size): the raster cache (200 copies of 500 × 500
 *   on 4000 × 5000) and the text array (32 copies of a 1600 × 500 text) against the v1.7
 *   drawing ([ArrayLookKit.v17Pixels], [ArrayLookKit.v17Draw]);
 * - a look costs its paints and nothing more: the tinted raster cache and the tinted text array
 *   against a bare loop drawing the same copies through the same paints (within 10 %), at a size
 *   the host's slow filter allows; an opacity-only look (no filter) against no look at T606 size;
 * - the draft rule: above 64 copies a slider drag with a tinted look draws a frame about as fast
 *   as one without a look (the tint waits for the release).
 */
@RunWith(RobolectricTestRunner::class)
@Category(Timing::class)
class ArrayLookPerfTest {
    @Before
    fun notOnCi() = assumeTrue("T606-size probes run locally only", System.getenv("CI") == null)

    @After
    fun tearDown() = ArrayDraw.clearCaches()

    private fun median(xs: List<Double>): Double = xs.sorted()[xs.size / 2]

    /** [runs] timed runs of [block] after one warm-up run (JIT); prints them and returns the median. */
    private fun measure(what: String, runs: Int, block: () -> Unit): Double {
        block()
        val times = List(runs) { PerfBudget.timeMs(block) }
        val med = median(times)
        println("v18 array look probe: $what: median ${"%.1f".format(med)} ms (${times.joinToString { "%.1f".format(it) }})")
        return med
    }

    private fun report(what: String, ab: Pair<Double, Double>, names: Pair<String, String>) =
        println("v18 array look probe: $what: ${names.first} ${"%.1f".format(ab.first)} ms, ${names.second} ${"%.1f".format(ab.second)} ms (${"%.2f".format(ab.first / ab.second)} x)")

    /** A painted, partly transparent source (an opaque disc on a translucent square), as v1.7's probes paint it. */
    private fun source(side: Int): Bitmap = BitmapUtils.createLayerBitmap(side, side).also { b ->
        Canvas(b).apply {
            drawColor(0x803366AA.toInt())
            drawCircle(side / 2f, side / 2f, side / 3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEE8800.toInt() })
        }
    }

    /** What a raster cache with [array] costs at least: its copies through their paints, in a bare loop (no checks). */
    private fun bareCopies(canvas: Canvas, array: LayerArray) {
        val px = array.pixels!!
        val bmp = px.bitmap
        val src = RectF(px.left.toFloat(), px.top.toFloat(), (px.left + bmp.width).toFloat(), (px.top + bmp.height).toFloat())
        val bilinear = Paint(Paint.FILTER_BITMAP_FLAG)
        val m = Matrix()
        for (inst in ArrayInstances.of(array.spec, src)) {
            if (inst.k == 0) continue
            val look = inst.look!!
            m.setValues(inst.matrix)
            canvas.save()
            canvas.concat(m)
            canvas.drawBitmap(bmp, src.left, src.top, ArrayDraw.paintOf(look, bilinear))
            canvas.restore()
        }
        canvas.drawBitmap(bmp, src.left, src.top, null)
    }

    /** What a text or shape array with [array] costs at least: each copy in a layer through its paint, in a bare loop. */
    private fun bareLayers(canvas: Canvas, array: LayerArray, bounds: RectF, draw: (Canvas) -> Unit) {
        val m = Matrix()
        val layer = RectF(bounds).apply { val g = ArrayDraw.layerMargin(bounds); inset(-g, -g) }
        for (inst in ArrayInstances.of(array.spec, bounds)) {
            if (inst.k == 0) continue
            m.setValues(inst.matrix)
            canvas.save()
            canvas.concat(m)
            canvas.saveLayer(layer, ArrayDraw.paintOf(inst.look!!, null))
            draw(canvas)
            canvas.restore()
            canvas.restore()
        }
        draw(canvas)
    }

    private fun text(sizePx: Float, cx: Float, cy: Float) =
        TextItem("Brushwork", spec = TextSpec(sizePx = sizePx, color = 0xFF606060.toInt()), cx = cx, cy = cy)

    @Test
    fun withoutALookTheCopiesCostWhatV17Did() {
        val cache = BitmapUtils.createLayerBitmap(W, H)
        val cv = Canvas(cache)
        val src = source(500)
        try {
            val plain = LayerArray(ArraySpec(mode = ArrayMode.CIRCLE, count = 200), ArrayPixels(src, W / 2 - 250, 1200))
            val raster = PerfBudget.ratioWithin(
                limit = 1.05,
                a = {
                    cv.drawColor(0, PorterDuff.Mode.CLEAR)
                    ArrayDraw.drawPixels(cv, plain)
                },
                b = {
                    cv.drawColor(0, PorterDuff.Mode.CLEAR)
                    ArrayLookKit.v17Pixels(cv, plain)
                },
                slackMs = 2.0,
                what = "raster cache, no look, against v1.7",
            )
            report("raster cache, 200 copies of 500 x 500 on $W x $H, no look", raster, "now" to "v1.7")

            val item = text(300f, 2000f, 1200f)
            val spec = ArraySpec(mode = ArrayMode.TRANSFORM, count = 32, moveY = 90f, turnDeg = 11f, scale = 0.97f)
            val data = LayerData(text = TextCodec.encode(item), array = LayerArray(spec))
            val bounds = ArrayDraw.sourceBounds(data)!!
            val draw = ArraySources.sourceDraw(data, ColorMode.RGB, W, H)!!
            val text = PerfBudget.ratioWithin(
                limit = 1.05,
                a = {
                    cv.drawColor(0, PorterDuff.Mode.CLEAR)
                    ArrayDraw.drawWithArray(cv, data.array, bounds, draw)
                },
                b = {
                    cv.drawColor(0, PorterDuff.Mode.CLEAR)
                    ArrayLookKit.v17Draw(cv, data.array, bounds, draw)
                },
                slackMs = 2.0,
                what = "text array, no look, against v1.7",
            )
            report("text array, 32 copies of ${bounds.width().toInt()} x ${bounds.height().toInt()}, no look", text, "now" to "v1.7")
        } finally {
            cache.recycle()
            src.recycle()
        }
    }

    @Test
    fun aLookCostsItsPaintsAndNothingMore() {
        // The opacity alone (no filter), T606 size: about the plain cost.
        val cache = BitmapUtils.createLayerBitmap(W, H)
        val cv = Canvas(cache)
        val big = source(500)
        try {
            val plain = LayerArray(ArraySpec(mode = ArrayMode.CIRCLE, count = 200), ArrayPixels(big, W / 2 - 250, 1200))
            val faded = LayerArray(plain.spec.copy(look = ArrayLook(opacityEnd = 0.4f, sizeEnd = 0.6f)), plain.pixels)
            val fade = PerfBudget.ratioWithin(
                limit = FADE_LIMIT,
                a = {
                    cv.drawColor(0, PorterDuff.Mode.CLEAR)
                    ArrayDraw.drawPixels(cv, faded)
                },
                b = {
                    cv.drawColor(0, PorterDuff.Mode.CLEAR)
                    ArrayDraw.drawPixels(cv, plain)
                },
                slackMs = 2.0,
                what = "raster cache, a fade and a shrink against no look",
            )
            report("raster cache, 200 copies of 500 x 500 on $W x $H, a fade and a shrink", fade, "look" to "no look")
        } finally {
            cache.recycle()
            big.recycle()
        }

        // The tint: the same copies through the same paints in a bare loop, at a size the host's filter allows.
        val small = BitmapUtils.createLayerBitmap(SMALL, SMALL)
        val sv = Canvas(small)
        val src = source(100)
        try {
            val tinted = LayerArray(ArraySpec(mode = ArrayMode.CIRCLE, count = 64), ArrayPixels(src, SMALL / 2 - 50, 50)).let {
                LayerArray(it.spec.copy(look = LOOK), it.pixels)
            }
            val px = PerfBudget.ratioWithin(
                limit = 1.10,
                a = {
                    sv.drawColor(0, PorterDuff.Mode.CLEAR)
                    ArrayDraw.drawPixels(sv, tinted)
                },
                b = {
                    sv.drawColor(0, PorterDuff.Mode.CLEAR)
                    bareCopies(sv, tinted)
                },
                slackMs = 2.0,
                what = "tinted raster cache against its bare paints",
            )
            report("raster cache, 64 tinted copies of 100 x 100 on $SMALL x $SMALL", px, "ArrayDraw" to "bare")

            val item = text(60f, SMALL / 2f, 120f)
            val spec = ArraySpec(mode = ArrayMode.TRANSFORM, count = 32, moveY = 25f, turnDeg = 11f, scale = 0.97f, look = LOOK)
            val data = LayerData(text = TextCodec.encode(item), array = LayerArray(spec))
            val bounds = ArrayDraw.sourceBounds(data)!!
            val draw = ArraySources.sourceDraw(data, ColorMode.RGB, SMALL, SMALL)!!
            val text = PerfBudget.ratioWithin(
                limit = 1.10,
                a = {
                    sv.drawColor(0, PorterDuff.Mode.CLEAR)
                    ArrayDraw.drawWithArray(sv, data.array, bounds, draw)
                },
                b = {
                    sv.drawColor(0, PorterDuff.Mode.CLEAR)
                    bareLayers(sv, data.array!!, bounds, draw)
                },
                slackMs = 2.0,
                what = "tinted text array against its bare layers",
            )
            report("text array, 32 tinted copies of ${bounds.width().toInt()} x ${bounds.height().toInt()}", text, "ArrayDraw" to "bare")
        } finally {
            small.recycle()
            src.recycle()
        }
    }

    @Test
    fun aDraftFrameCostsAboutAPlainFrame() {
        val c = Smoke.controller(RuntimeEnvironment.getApplication(), Smoke.document(W, H, layers = 2, whiteBottom = true))
        val screen = BitmapUtils.createLayerBitmap(1080, 2400)
        try {
            val src = c.doc.layers[1]
            val proxy = source(1000)
            Canvas(src.bitmap).drawBitmap(proxy, 1500f, 1000f, null)
            proxy.recycle()
            c.selectLayer(src)
            val rect = Path().apply { addRect(1500f, 1000f, 2500f, 2000f, Path.Direction.CW) }
            c.setSelection(Selection.fromPath(rect, W, H, antiAlias = false), recordUndo = false)
            assertTrue(c.arrayFromSelection())
            Smoke.pump(20)
            val tool = c.tools.getValue(ToolId.ARRAY) as ArrayTool
            val base = c.activeLayer.array!!.spec.copy(mode = ArrayMode.CIRCLE, count = DRAFT_COUNT)
            // The phone's canvas with the document fitted.
            val fit = min(1080f / W, 2400f / H)
            c.viewTransform.set(Matrix().apply { setScale(fit, fit) })
            val cv = Canvas(screen)
            var sweep = 360f
            // A slider dragged: each frame a new spec previewed, then the overlay drawn.
            fun frame(look: ArrayLook?) {
                sweep -= 1f
                tool.preview(base.copy(sweepDeg = sweep, look = look))
                cv.drawColor(0, PorterDuff.Mode.CLEAR)
                tool.drawOverlay(cv, c.viewTransform)
            }
            frame(LOOK)
            assertTrue("65 copies, a tinted look, dragging: a draft", tool.previewIsDraft)
            val draft = PerfBudget.ratioWithin(
                limit = DRAFT_LIMIT,
                a = { frame(LOOK) },
                b = { frame(null) },
                slackMs = 3.0,
                what = "a draft frame against a frame without a look",
            )
            report("preview frame (overlay, software canvas), 65 copies of 1000 x 1000, a draft", draft, "draft" to "no look")
            assertFalse("no look: no draft", tool.previewIsDraft)
            frame(LOOK)
            assertTrue(tool.previewIsDraft)
            // Not released here: the host would take minutes to tint the cache (ArrayLookUndoTest checks the release).
        } finally {
            screen.recycle()
            c.dispose()
            for (l in c.doc.layers) if (!l.isFolder && !l.bitmap.isRecycled) l.bitmap.recycle()
        }
    }

    private companion object {
        /** The T606 document. */
        const val W = 4000
        const val H = 5000

        /** One copy more than `ArrayTool.DRAFT_ABOVE` (64): a dragging preview is a draft. */
        const val DRAFT_COUNT = 65

        /** The side of the canvas the tinted rows use (the host's filter is slow, see the class docs). */
        const val SMALL = 800

        /** An opacity-only look against no look (alpha only, no filter). */
        const val FADE_LIMIT = 1.5

        /** A draft frame (opacity, size, no tint) against a frame without a look. */
        const val DRAFT_LIMIT = 1.5

        /** Every part of a look at once: a fade, a shrink, a turn and a full-strength tint. */
        val LOOK = ArrayLook(opacityEnd = 0.4f, sizeEnd = 0.6f, turnEnd = 45f, tint = RED_TO_BLUE, tintStrength = 1f)
    }
}
