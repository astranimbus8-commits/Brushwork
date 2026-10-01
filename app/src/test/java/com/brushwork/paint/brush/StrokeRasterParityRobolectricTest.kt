package com.brushwork.paint.brush

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * v1.5 F2 (§5.10 item 10): [StrokeRaster] replays a recorded stroke like the live BrushTool drew
 * it — same seed, same points — for Pen, Soft, Pencil, Chalk and Airbrush, with a finger and a
 * stylus (≥ 99 % of pixels within ±2; A1 tightens the bar to §4.9's).
 */
@RunWith(RobolectricTestRunner::class)
class StrokeRasterParityRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()
    private val w = 420
    private val h = 300

    private fun setup(): EditorController {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", w, h)
        repeat(2) { i -> doc.layers += Layer(doc.newLayerId(), "Layer ${i + 1}", BitmapUtils.createLayerBitmap(w, h)) }
        doc.activeLayerIndex = 1
        return EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
    }

    private fun pixels(b: Bitmap) = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }

    /** Records what the live stroke was fed and commits its pixels normally. */
    private class Capture : StrokeRecorder {
        override val replacesStroke = false
        override val ignoresSelection = false
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        val ps = ArrayList<Float>()
        override fun point(x: Float, y: Float, rawPressure: Float) { xs += x; ys += y; ps += rawPressure }
        override fun commit(label: String, bounds: Rect, commitPixels: () -> Boolean): Boolean = commitPixels()
        override fun cancel() {}
        fun points() = PackedPoints(xs.toFloatArray(), ys.toFloatArray(), ps.toFloatArray())
    }

    /** A wavy stroke across the 256 px grain tile border, with a pressure swell for the stylus. */
    private fun strokePoints(stylus: Boolean): List<ToolPoint> {
        val n = 36
        return List(n + 1) { i ->
            val t = i.toFloat() / n
            val x = 70f + 290f * t
            val y = 150f + 70f * sin(t * 2f * PI.toFloat())
            val p = if (stylus) 0.2f + 0.8f * sin(t * PI.toFloat()) else 0.5f
            ToolPoint(x, y, p, i.toLong(), isStylus = stylus)
        }
    }

    private class Result(val live: IntArray, val replay: IntArray, val within: Double, val painted: Int, val maxDiff: Int)

    private fun drawAndReplay(preset: BrushPreset, stylus: Boolean, color: Int = 0xFF2050C0.toInt()): Result {
        val c = setup()
        c.selectTool(ToolId.BRUSH)
        val b = c.tools.getValue(ToolId.BRUSH) as BrushTool
        c.brush = preset
        c.color = color
        val cap = Capture()
        var info: StrokeInfo? = null
        b.strokeHook = { i -> info = i; StrokeHook.Record(cap) }
        val pts = strokePoints(stylus)
        b.onDown(pts.first())
        for (p in pts.subList(1, pts.size - 1)) b.onMove(p)
        b.onUp(pts.last())
        val i = info!!
        assertEquals(1, c.undoManager.undoCount)
        val out = BitmapUtils.createLayerBitmap(w, h)
        StrokeRaster().render(Canvas(out), Rect(0, 0, w, h), i.preset, i.color, i.seed, i.isStylus, cap.points())
        val live = pixels(c.activeLayer.bitmap)
        val replay = pixels(out)
        var ok = 0
        var painted = 0
        var maxDiff = 0
        for (k in live.indices) {
            val d = channelDiff(live[k], replay[k])
            if (d <= 2) ok++
            maxDiff = max(maxDiff, d)
            if (live[k] != 0) painted++
        }
        return Result(live, replay, ok.toDouble() / live.size, painted, maxDiff)
    }

    private fun channelDiff(a: Int, b: Int): Int {
        var m = 0
        for (s in intArrayOf(24, 16, 8, 0)) m = max(m, abs(((a ushr s) and 0xFF) - ((b ushr s) and 0xFF)))
        return m
    }

    private val presets: List<Pair<String, BrushPreset>> = listOf(
        "Pen" to BrushLibrary.defaultBrush,
        "Soft" to BrushLibrary.byId("softround")!!,
        "Pencil" to BrushLibrary.byId("pencil")!!,
        "Chalk" to BrushLibrary.byId("chalk")!!,
        "Airbrush" to BrushLibrary.byId("airbrush")!!,
        // A finger taper (end taper re-resolved with the final length).
        "G-pen" to BrushLibrary.byId("gpen")!!,
    )

    @Test
    fun replayMatchesTheLiveStrokeForEveryPresetWithFingerAndStylus() {
        for ((name, preset) in presets) {
            for (stylus in listOf(false, true)) {
                val r = drawAndReplay(preset, stylus)
                val what = "$name ${if (stylus) "stylus" else "finger"}"
                println("parity $what: ${"%.4f".format(r.within * 100)} % within ±2, max diff ${r.maxDiff}, painted ${r.painted}")
                assertTrue("$what painted something", r.painted > 50)
                assertTrue("$what: ${"%.4f".format(r.within * 100)} % within ±2 (max diff ${r.maxDiff})", r.within >= 0.99)
            }
        }
    }

    @Test
    fun everyCoveragePresetMeetsTheVectorLayerBar() {
        // §4.9e's bar (A1 owns it): every non-direct brush preset, finger with tapers on and off,
        // and stylus: ≥ 99.5 % within ±2 and no pixel off by more than 12.
        val coverage = BrushLibrary.all.filter { !StrokeKind.of(ToolId.BRUSH, it).isDirect }
        assertTrue(coverage.size >= 10)
        for (preset in coverage) {
            for ((label, p, stylus) in listOf(
                Triple("finger", preset, false),
                Triple("finger untapered", preset.copy(taperStart = 0f, taperEnd = 0f), false),
                Triple("stylus", preset, true),
            )) {
                val r = drawAndReplay(p, stylus)
                val what = "${preset.name} $label"
                assertTrue("$what: ${"%.4f".format(r.within * 100)} % within ±2", r.within >= 0.995)
                assertTrue("$what: max diff ${r.maxDiff}", r.maxDiff <= 12)
            }
        }
    }

    @Test
    fun aHardPenReplayIsBitExact() {
        // Without grain or soft edges in play the replay is the very same composite.
        val r = drawAndReplay(BrushLibrary.defaultBrush, stylus = true)
        assertArrayEquals(r.live, r.replay)
    }

    @Test
    fun clippedRendersPieceTogetherTheWholeStroke() {
        val preset = BrushLibrary.byId("chalk")!!
        val pts = strokePoints(true)
        val packed = PackedPoints(pts.map { it.x }.toFloatArray(), pts.map { it.y }.toFloatArray(), pts.map { it.pressure }.toFloatArray())
        val whole = BitmapUtils.createLayerBitmap(w, h)
        val raster = StrokeRaster()
        val all = raster.render(Canvas(whole), Rect(0, 0, w, h), preset, 0xFF000000.toInt(), 99L, true, packed)
        assertTrue(!all.isEmpty)
        // Tiles of a different grid than the grain's: every piece is drawn with the whole stroke's coverage.
        val tiled = BitmapUtils.createLayerBitmap(w, h)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val clip = Rect(x, y, minOf(w, x + 100), minOf(h, y + 70))
                val cv = Canvas(tiled)
                cv.save(); cv.clipRect(clip)
                raster.render(cv, clip, preset, 0xFF000000.toInt(), 99L, true, packed)
                cv.restore()
                x += 100
            }
            y += 70
        }
        assertArrayEquals(pixels(whole), pixels(tiled))
        // Everything painted lies inside the conservative bounds.
        val bounds = raster.bounds(preset, 1f, packed)
        val px = pixels(whole)
        for (yy in 0 until h) for (xx in 0 until w) {
            if (px[yy * w + xx] != 0) assertTrue("($xx, $yy) inside $bounds", bounds.contains(xx + 0.5f, yy + 0.5f))
        }
        assertTrue(bounds.contains(all.left.toFloat(), all.top.toFloat()))
    }

    @Test
    fun opacityScaleAndCutEndsChangeTheReplay() {
        val preset = BrushLibrary.byId("gpen")!!.copy(size = 12f)
        val pts = strokePoints(false)
        val packed = PackedPoints(pts.map { it.x }.toFloatArray(), pts.map { it.y }.toFloatArray(), pts.map { it.pressure }.toFloatArray())
        fun render(scale: Float = 1f, opacity: Float = 1f, taperIn: Boolean = true): IntArray {
            val b = BitmapUtils.createLayerBitmap(w, h)
            StrokeRaster().render(Canvas(b), Rect(0, 0, w, h), preset, 0xFF000000.toInt(), 5L, false, packed, scale, opacity, taperIn)
            return pixels(b)
        }
        val base = render()
        val half = render(opacity = 0.5f)
        // Where the stroke is solid, half opacity gives about half the alpha.
        var solid = 0
        for (k in base.indices) if (base[k] ushr 24 == 255) {
            solid++
            assertTrue(abs((half[k] ushr 24) - 128) <= 2)
        }
        assertTrue(solid > 100)
        val wide = render(scale = 2f)
        assertTrue(wide.count { it != 0 } > base.count { it != 0 } * 3 / 2)
        // Without the start taper the first point gets a full-size dab.
        val cut = render(taperIn = false)
        val startCol = 72
        val startY = 150
        fun alphaNear(px: IntArray) = (startY - 8..startY + 8).sumOf { yy -> px[yy * w + startCol] ushr 24 }
        assertTrue(alphaNear(cut) > alphaNear(base))
        // Nothing to draw.
        val empty = BitmapUtils.createLayerBitmap(w, h)
        assertTrue(StrokeRaster().render(Canvas(empty), Rect(0, 0, w, h), preset, -1, 1L, false, PackedPoints.EMPTY).isEmpty)
        assertTrue(StrokeRaster().render(Canvas(empty), Rect(0, 0, 10, 10), preset, -1, 1L, false, packed).isEmpty)
    }
}
