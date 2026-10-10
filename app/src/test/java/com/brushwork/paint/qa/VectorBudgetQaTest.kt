package com.brushwork.paint.qa

import android.graphics.Canvas
import android.graphics.Rect
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.UndoAction
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorCodec
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.draw.VectorEraseMode
import com.brushwork.paint.vector.draw.VectorEraserModes
import com.brushwork.paint.vector.render.VectorLayerRenderer
import com.brushwork.paint.vector.select.ObjectActions
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * QA (§4.9c budgets), the user's real canvas: 1080 x 2408 with ~300 strokes (EXPLICIT large-canvas
 * test: 10 MB per layer). The budgets are for the T606; on the JVM they are checked RELATIVELY so
 * they hold on any machine: a new stroke costs what it costs on a raster layer (data append, no
 * render), a local erase and a deletion re-render only their tiles (a small part of a full
 * render), a whole-pixel move of everything shifts the cache, hit tests don't grow with the
 * object count, the undo history holds tiles of the touched area only, saving encodes quickly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h857dp-xxhdpi")
@Category(Timing::class)
class VectorBudgetQaTest {
    private lateinit var r: VectorQaRig
    private val c get() = r.c

    @After
    fun tearDown() { if (this::r.isInitialized) r.close() }

    private val w = 1080
    private val h = 2408

    /** ~70 points along a wavy line of about 260 px. */
    private fun stroke(rnd: Random): VStroke {
        val x0 = 300f + rnd.nextFloat() * (w - 600f)
        val y0 = 320f + rnd.nextFloat() * (h - 640f)
        val a = rnd.nextFloat() * 6.28f
        val n = 70
        val xs = FloatArray(n) { k -> x0 + cos(a) * 3.7f * k + 8f * sin(k * 0.3f) }
        val ys = FloatArray(n) { k -> y0 + sin(a) * 3.7f * k + 8f * cos(k * 0.3f) }
        val preset = BrushLibrary.byId(listOf("pen", "softround")[rnd.nextInt(2)])!!.copy(size = 6f + rnd.nextFloat() * 14f)
        return VStroke(0, preset = preset, color = 0xFF000000.toInt() or rnd.nextInt(0xFFFFFF), seed = rnd.nextLong(), stylus = rnd.nextBoolean(), points = PackedPoints(xs, ys, FloatArray(n) { 0.4f + 0.6f * rnd.nextFloat() }))
    }

    private fun lastStepBytes(): Long {
        val um = c.undoManager
        val a: UndoAction = um.takeSince(um.undoCount - 1).single()
        um.pushRaw(a)
        return a.byteSize
    }

    private fun drawWith(pts: List<ToolPoint>) {
        c.pointerDown(pts.first())
        for (p in pts.subList(1, pts.size - 1)) c.pointerMove(p)
        c.pointerUp(pts.last())
    }

    private fun wave(x0: Float, y0: Float, n: Int = 60) = List(n) { k -> ToolPoint(x0 + 4f * k, y0 + 10f * sin(k * 0.25f), 1f, k * 8L) }

    @Test
    fun threeHundredStrokesOnThePhonesCanvasStayWithinTheBudgets() {
        r = VectorQaRig(w, h)
        c.toggleVectorMode()
        val vec = c.activeLayer
        val rnd = Random(1505)
        repeat(6) { c.vectors.addObjects(vec, List(50) { stroke(rnd) }, "Add") }
        Smoke.pump(40)
        assertEquals(300, vec.vector!!.objects.size)
        r.assertCacheFresh("300 strokes", vec)

        // The reference: one full render of the layer. Each guard below times its step and then,
        // back to back, a full render of the layer as it is then (PerfBudget.measuredRatioWithin):
        // a try that a busy machine slowed down is taken again with a fresh step.
        val full = BitmapUtils.createLayerBitmap(w, h)
        val docRect = Rect(0, 0, w, h)
        val tips = TipCache()
        fun fullRenderMs() = PerfBudget.timeMs { full.eraseColor(0); VectorLayerRenderer.render(Canvas(full), vec.vector!!, docRect, tips = tips, document = docRect) }
        VectorLayerRenderer.render(Canvas(full), vec.vector!!, docRect, tips = tips, document = docRect)
        val fullMs = (0 until 3).minOf { fullRenderMs() }
        println("[budget] full render of 300 strokes at ${w}x$h: ${"%.1f".format(fullMs)} ms")

        // 1. A new stroke: what it costs on a raster layer (the live pixels are the cache; data append).
        r.tool(ToolId.BRUSH)
        c.brush = BrushLibrary.defaultBrush.copy(size = 12f)
        repeat(2) { drawWith(wave(100f, 300f + it * 40f)) } // warm up
        val raster = c.addLayer()!!
        repeat(2) { drawWith(wave(100f, 500f + it * 40f)) }
        var strokeTries = 0
        val (vectorMs, rasterMs) = PerfBudget.measuredRatioWithin(1.6, slackMs = 8.0, what = "a vector stroke costs like a raster one") {
            strokeTries++
            c.selectLayer(raster)
            val onRaster = (0 until 5).map { k -> PerfBudget.timeMs { drawWith(wave(100f, 700f + k * 40f)) } }.sorted()[2]
            c.selectLayer(vec)
            val onVector = (0 until 5).map { k -> PerfBudget.timeMs { drawWith(wave(100f, 1000f + k * 40f)) } }.sorted()[2]
            onVector to onRaster
        }
        println("[budget] stroke: raster ${"%.1f".format(rasterMs)} ms, vector ${"%.1f".format(vectorMs)} ms ($strokeTries tries)")
        assertEquals("300 strokes, 2 warm-up strokes and 5 per try", 302 + 5 * strokeTries, vec.vector!!.objects.size)
        val strokeBytes = lastStepBytes()
        assertTrue("the stroke's step holds its tiles only: $strokeBytes bytes", strokeBytes < 2L * 1024 * 1024)
        c.deleteLayer(raster)
        c.selectLayer(vec)

        // 2. A local erase (Object): only its tiles are drawn again (another stroke each try).
        c.toggleEraser()
        VectorEraserModes.setMode(c, VectorEraseMode.OBJECT)
        c.eraser = c.eraser.copy(size = 20f)
        val (eraseMs, eraseFullMs) = PerfBudget.measuredRatioWithin(0.35, slackMs = 15.0, what = "a local erase is a fraction of a full render") { k ->
            val target = vec.vector!!.objects[150 + k] as VStroke
            val tx = target.points.x[35]
            val ty = target.points.y[35]
            val before = vec.vector!!.objects.size
            val ms = PerfBudget.timeMs { drawWith(List(6) { j -> ToolPoint(tx - 10f + 4f * j, ty, 1f, j * 8L) }) }
            Smoke.pump(20)
            println("[budget] local erase: ${"%.1f".format(ms)} ms (${before - vec.vector!!.objects.size} objects)")
            assertTrue("erased", vec.vector!!.objects.size < before)
            ms to fullRenderMs()
        }
        println("[budget] local erase ${"%.1f".format(eraseMs)} ms vs a full render ${"%.1f".format(eraseFullMs)} ms")
        assertTrue("the erase step holds tiles only: ${lastStepBytes()}", lastStepBytes() < 4L * 1024 * 1024)
        c.toggleEraser()

        // 3. Delete one long stroke from the Object bar (another one each try).
        val (deleteMs, deleteFullMs) = PerfBudget.measuredRatioWithin(0.35, slackMs = 15.0, what = "delete is a fraction of a full render") { k ->
            val long = vec.vector!!.objects[100 + k]
            c.vectors.setSelection(vec, setOf(long.id))
            val ms = PerfBudget.timeMs { ObjectActions.delete(c) }
            assertTrue("deleted", vec.vector!!.byId(long.id) == null)
            ms to fullRenderMs()
        }
        println("[budget] delete one stroke: ${"%.1f".format(deleteMs)} ms vs a full render ${"%.1f".format(deleteFullMs)} ms")

        // 4. Everything moved by whole pixels: the cache is shifted, not drawn again (lifted again each try).
        val t = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        val (moveMs, moveFullMs) = PerfBudget.measuredRatioWithin(0.5, slackMs = 20.0, what = "a move of everything is a fraction of a full render") { k ->
            if (k > 0) r.tool(ToolId.BRUSH)
            r.tool(ToolId.TRANSFORM)
            assertTrue(Smoke.pumpUntil(20_000) { t.transformState != null })
            t.moveBy(24f, -16f)
            val shifts = c.vectors.shiftCount
            val ms = PerfBudget.timeMs { t.commit() }
            Smoke.pump(20)
            println("[budget] move everything: ${"%.1f".format(ms)} ms (shifted: ${c.vectors.shiftCount > shifts})")
            assertTrue("the move shifted the cache", c.vectors.shiftCount > shifts)
            ms to fullRenderMs()
        }
        println("[budget] move everything ${"%.1f".format(moveMs)} ms vs a full render ${"%.1f".format(moveFullMs)} ms")
        full.recycle()
        r.assertCacheFresh("after the move", vec, tolerance = 32, maxOffPermille = 20)
        r.tool(ToolId.BRUSH)

        // 5. Hit tests don't grow with the number of objects (2000 boxes vs 20).
        fun boxes(n: Int) = VectorContent(objects = List(n) { i ->
            val x = (i % 40) * 26f; val y = (i / 40) * 46f
            VPath(i + 1L, subpaths = listOf(VSubpath(listOf(VAnchor(x, y, true), VAnchor(x + 20f, y, true), VAnchor(x + 20f, y + 40f, true), VAnchor(x, y + 40f, true)), closed = true)), fill = VPaint.Solid(-16777216))
        }, nextId = n + 1L)
        val small = c.addVectorLayer()!!
        small.restoreData(small.dataSnapshot().copy(vector = boxes(20)))
        val big = c.addVectorLayer()!!
        big.restoreData(big.dataSnapshot().copy(vector = boxes(2000)))
        fun hits(layer: com.brushwork.paint.model.Layer, n: Int): Double {
            repeat(50) { c.vectors.hitTest(layer, Vec2(10f + it, 20f), 4f) }
            return PerfBudget.timeMs { for (k in 0 until n) c.vectors.hitTest(layer, Vec2((k * 37 % 1000).toFloat(), (k * 53 % 900).toFloat()), 4f) } / n
        }
        val (hitBig, hitSmall) = PerfBudget.measuredRatioWithin(10.0, slackMs = 0.2, what = "hit tests stay flat") { hits(big, 400) to hits(small, 400) }
        println("[budget] hit test: 20 objects ${"%.4f".format(hitSmall)} ms, 2000 objects ${"%.4f".format(hitBig)} ms")
        PerfBudget.measuredWithin(1.0, what = "a hit test among 2000 objects stays under 1 ms here") { k -> if (k == 0) hitBig else hits(big, 400) }

        // 6. Saving: the vector data encodes in a few ms per hundred strokes.
        val content = vec.vector!!
        val encodeMs = PerfBudget.within(300.0, what = "encode") { VectorCodec.encode(content) }
        println("[budget] encode ${content.objects.size} objects: ${"%.1f".format(encodeMs)} ms")
        val saveMs = PerfBudget.timeMs { runBlocking { r.repo.save(c.doc, null) } }
        println("[budget] save: ${"%.1f".format(saveMs)} ms")
        val loaded = runBlocking { r.repo.load(r.projectId) }
        assertEquals(content, loaded.layers.first { it.name == vec.name }.vector)
    }
}
