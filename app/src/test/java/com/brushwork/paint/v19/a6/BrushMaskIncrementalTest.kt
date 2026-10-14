package com.brushwork.paint.v19.a6

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.live.LiveAdjust
import com.brushwork.paint.masks.BrushMask
import com.brushwork.paint.masks.MaskBrushRaster
import com.brushwork.paint.masks.MaskStroke
import com.brushwork.paint.masks.SampleGrid
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.mask.MaskTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * v1.9 A6 step 5 (R13): a brush stroke painted into an existing brush part of a mask applies
 * each new dab once ([MaskBrushRaster.DabTrail], [MaskBrushRaster.StrokeTiles]) instead of the
 * whole stroke every frame, and gives exactly the bytes of the full render after every point:
 * the canonical arithmetic (per sample, the same Float steps in the same order). The Masks tool
 * uses it under the live policy only (an explicit opt-in here; Robolectric's EXACT policy keeps
 * v1.8's path, I8), falls back to v1.8's path past its tile cap, and its frames and release
 * equal a twin editor that runs v1.8's path.
 */
@RunWith(RobolectricTestRunner::class)
class BrushMaskIncrementalTest {
    private val w = 300
    private val h = 200
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val made = ArrayList<EditorController>()

    @Before
    fun clearSettings() {
        AppSettings(RuntimeEnvironment.getApplication()).prefs.edit().clear().commit()
    }

    @After
    fun tearDown() {
        made.forEach { it.dispose() }
        scope.cancel()
    }

    // ------------------------------------------------------------------ the raster

    /** A wavy stroke of [n] points with repeats and a point that is not finite, partly off the document. */
    private fun points(rnd: Random, n: Int): PackedPoints {
        val xs = FloatArray(n); val ys = FloatArray(n); val ps = FloatArray(n) { 1f }
        var x = rnd.nextFloat() * w - 20f; var y = rnd.nextFloat() * h
        for (i in 0 until n) {
            if (i % 9 != 4) {
                x += 3f + rnd.nextFloat() * 14f * cos(i * 0.4f)
                y += 11f * sin(i * 0.7f) + rnd.nextFloat() * 4f - 2f
            }
            xs[i] = x; ys[i] = y
        }
        if (n > 12) xs[12] = Float.NaN
        return PackedPoints(xs, ys, ps)
    }

    private fun prefix(p: PackedPoints, n: Int) = PackedPoints(p.x.copyOf(n), p.y.copyOf(n), p.p.copyOf(n))

    private fun stroke(erase: Boolean, size: Float, hardness: Float, flow: Float, p: PackedPoints) = MaskStroke(erase, size, hardness, flow, p)

    @Test
    fun theTrailMakesTheDabsOfTheWholeStrokeAfterEveryPoint() {
        val rnd = Random(19)
        for (case in 0 until 12) {
            val size = listOf(1f, 3f, 9f, 24f, 61f, 140f)[case % 6]
            val s = stroke(case % 2 == 1, size, rnd.nextFloat(), 0.05f + 0.95f * rnd.nextFloat(), points(rnd, 40))
            val trail = MaskBrushRaster.DabTrail(s.size, s.hardness, s.flow, s.erase)
            for (n in 1..s.points.size) {
                trail.add(s.points.x[n - 1], s.points.y[n - 1])
                val want = MaskBrushRaster.dabsOf(s.copy(points = prefix(s.points, n)))
                val got = trail.dabs()
                val what = "case $case (size $size), $n points"
                assertEquals("$what: count", want.count, got.count)
                assertArrayEquals("$what: x", want.x, got.x, 0f)
                assertArrayEquals("$what: y", want.y, got.y, 0f)
                assertEquals("$what: radius", want.radius, got.radius, 0f)
                assertEquals("$what: inner", want.inner, got.inner, 0f)
                assertEquals("$what: strength", want.strength, got.strength, 0f)
            }
        }
    }

    @Test
    fun strokeTilesGiveTheBytesOfTheFullRenderAfterEveryPoint() {
        val rnd = Random(7)
        val full = SampleGrid.pixels(0, 0, w, h)
        val before = listOf(
            stroke(false, 30f, 0.6f, 0.8f, points(rnd, 24)),
            stroke(false, 12f, 0.2f, 0.5f, points(rnd, 30)),
        )
        val prior = MaskBrushRaster.rasterize(before, full)
        for ((case, erase) in listOf(false, true, false).withIndex()) {
            val s = stroke(erase, listOf(18f, 40f, 5f)[case], 0.35f, listOf(0.7f, 0.6f, 1f)[case], points(rnd, 36))
            val trail = MaskBrushRaster.DabTrail(s.size, s.hardness, s.flow, s.erase)
            val tiles = MaskBrushRaster.StrokeTiles(w, h, trail, { l, t, cw, ch ->
                ByteArray(cw * ch).also { out -> for (r in 0 until ch) System.arraycopy(prior, (t + r) * w + l, out, r * cw, cw) }
            })
            for (n in 1..s.points.size) {
                assertTrue(tiles.add(s.points.x[n - 1], s.points.y[n - 1]))
                if (n % 5 != 0 && n != s.points.size) continue
                val want = MaskBrushRaster.rasterize(before + s.copy(points = prefix(s.points, n)), full)
                assertArrayEquals("case $case (erase $erase), $n points: the whole document", want, tiles.coverage(full))
                // A region (as a frame renders the new dabs' box).
                val region = SampleGrid.pixels(37, 21, 150, 90)
                val sub = ByteArray(region.size).also { o -> for (r in 0 until region.rows) System.arraycopy(want, (21 + r) * w + 37, o, r * region.cols, region.cols) }
                assertArrayEquals("case $case, $n points: a region", sub, tiles.coverage(region))
            }
            assertTrue("only the tiles under the stroke are made", tiles.tileCount < ((w + 63) / 64) * ((h + 63) / 64))
        }
        // A new part (no prior coverage).
        val s = stroke(false, 22f, 0.5f, 0.9f, points(rnd, 20))
        val tiles = MaskBrushRaster.StrokeTiles(w, h, MaskBrushRaster.DabTrail(s.size, s.hardness, s.flow, s.erase), { _, _, _, _ -> null })
        for (i in 0 until s.points.size) tiles.add(s.points.x[i], s.points.y[i])
        assertArrayEquals("a new part", MaskBrushRaster.rasterize(listOf(s), full), tiles.coverage(full))
        // Grids it can't serve.
        assertEquals(null, tiles.coverage(SampleGrid(0f, 0f, 2f, w / 2, h / 2)))
        assertEquals(null, tiles.coverage(SampleGrid.pixels(w - 10, 0, 20, 10)))
    }

    @Test
    fun aStrokeBeyondTheTileCapSaysSo() {
        val trail = MaskBrushRaster.DabTrail(40f, 0.5f, 1f, false)
        val tiles = MaskBrushRaster.StrokeTiles(w, h, trail, { _, _, _, _ -> null }, maxTiles = 2)
        assertTrue("the first dab fits in two tiles", tiles.add(30f, 30f))
        var ok = true
        var x = 30f
        while (ok && x < w) { x += 10f; ok = tiles.add(x, 30f) }
        assertFalse("a stroke across the document needs more than two tiles", ok)
        assertEquals(2, tiles.tileCount)
    }

    // ------------------------------------------------------------------ the Masks tool

    /** An editor on a fresh document (white, a blue rectangle on the active layer) on the Masks tool, at view zoom 1. */
    private fun controller(): EditorController {
        val app = RuntimeEnvironment.getApplication()
        val doc = Document("m", "m", w, h)
        doc.layers += Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(w, h)).also { it.bitmap.eraseColor(-1) }
        doc.layers += Layer(doc.newLayerId(), "Photo", BitmapUtils.createLayerBitmap(w, h)).also {
            Canvas(it.bitmap).drawRect(30f, 30f, 270f, 170f, Paint().apply { color = 0xFF3080C0.toInt() })
        }
        doc.activeLayerIndex = 1
        return EditorController(app, doc, scope, AppSettings(app)).also {
            it.viewTransform.set(Matrix())
            it.selectTool(ToolId.MASK)
            made += it
        }
    }

    private val EditorController.maskTool get() = tools.getValue(ToolId.MASK) as MaskTool

    private fun px(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun p(i: Int, y0: Float) = ToolPoint(20f + 9f * i, y0 + 30f * sin(i * 0.35f))

    /** One stroke of [n] moves, the same on both editors; [frame] after every 4th move (on [c] only). */
    private fun stroke(twin: EditorController, c: EditorController, y0: Float, n: Int, frame: (Int) -> Unit) {
        twin.pointerDown(p(0, y0)); c.pointerDown(p(0, y0))
        for (i in 1..n) {
            twin.pointerMove(p(i, y0)); c.pointerMove(p(i, y0))
            if (i % 4 == 0) frame(i)
        }
        twin.pointerUp(p(n, y0)); c.pointerUp(p(n, y0))
    }

    @Test
    fun aLiveStrokeIntoABrushPartPaintsFromItsTilesAndEqualsV18EveryFrame() {
        val twin = controller()
        val c = controller()
        for (x in listOf(twin, c)) {
            x.updatePreset(ToolId.MASK, x.maskBrush.copy(size = 26f, hardness = 0.3f, opacity = 0.75f))
            x.maskTool.arm(MaskTool.Kind.BRUSH)
        }
        // A new brush adjustment (one stroke), then its part is selected.
        stroke(twin, c, 70f, 18) { }
        assertTrue(c.activeLayer.isAdjustmentLayer)
        assertTrue("the new part is selected", c.maskTool.selected is BrushMask)
        assertArrayEquals(px(twin.activeLayer.mask!!), px(c.activeLayer.mask!!))

        c.liveAdjust.clock = { 0L }
        c.liveAdjust.policy = LiveAdjust.Policy.LIVE
        // Painting more into the part, then erasing from it.
        for ((k, erase) in listOf(false, true).withIndex()) {
            twin.maskTool.brushErase = erase
            c.maskTool.brushErase = erase
            val y0 = if (erase) 80f else 130f
            stroke(twin, c, y0, 24) { i ->
                assertEquals("EXACT (I8): the twin paints v1.8's way", -1, twin.maskTool.strokeTiles)
                assertTrue("live: the stroke paints from its tiles", c.maskTool.strokeTiles > 0)
                c.maskTool.beforeFrame()
                assertArrayEquals("stroke $k, the frame after move $i equals v1.8", px(twin.activeLayer.mask!!), px(c.activeLayer.mask!!))
            }
            assertEquals(twin.undoManager.undoLabel, c.undoManager.undoLabel)
            assertEquals("stroke $k: the spec", twin.activeLayer.maskSpec, c.activeLayer.maskSpec)
            assertArrayEquals("stroke $k released: the mask", px(twin.activeLayer.mask!!), px(c.activeLayer.mask!!))
        }
        assertEquals("Erase mask", c.undoManager.undoLabel)
        c.undo(); twin.undo()
        assertArrayEquals("undone", px(twin.activeLayer.mask!!), px(c.activeLayer.mask!!))
    }

    @Test
    fun pastTheTileCapTheStrokeGoesOnV18sWay() {
        val twin = controller()
        val c = controller()
        for (x in listOf(twin, c)) {
            x.updatePreset(ToolId.MASK, x.maskBrush.copy(size = 30f, hardness = 0.5f, opacity = 0.9f))
            x.maskTool.arm(MaskTool.Kind.BRUSH)
        }
        stroke(twin, c, 60f, 10) { }
        c.liveAdjust.clock = { 0L }
        c.liveAdjust.policy = LiveAdjust.Policy.LIVE
        MaskTool.strokeTileCap = 3
        try {
            var fellBack = false
            stroke(twin, c, 120f, 28) { i ->
                if (c.maskTool.strokeTiles < 0) fellBack = true
                c.maskTool.beforeFrame()
                assertArrayEquals("the frame after move $i equals v1.8", px(twin.activeLayer.mask!!), px(c.activeLayer.mask!!))
            }
            assertTrue("the stroke outgrew three tiles", fellBack)
            assertArrayEquals("released", px(twin.activeLayer.mask!!), px(c.activeLayer.mask!!))
            assertEquals(twin.activeLayer.maskSpec, c.activeLayer.maskSpec)
        } finally {
            MaskTool.strokeTileCap = MaskBrushRaster.MAX_TILES
        }
    }
}
