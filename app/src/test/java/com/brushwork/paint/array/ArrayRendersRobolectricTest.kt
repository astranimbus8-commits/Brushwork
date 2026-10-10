package com.brushwork.paint.array

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import com.brushwork.paint.BrushworkApp
import com.brushwork.paint.EditorController
import com.brushwork.paint.EditorSession
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.model.ArrayMode
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.storage.NewCanvasSpec
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.array.ArrayTool
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.vector.VectorLayers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * v1.7 integration pass "arrayrender" (§6.3; area E): a text, shape or raster array's cache
 * rendered off the main thread ([ArrayRenders]). An "Edit array" whose cache renders in the
 * background changes nothing until it lands, then lands as ONE step with exactly the pixels a
 * synchronous render gives (I1, I2); undo and redo take it back and bring it again exactly, also
 * while it still renders; a newer edit drops it; saving meanwhile writes the layer as it was
 * (data and pixels agreeing) and closing lands it first; "Rendering array…" shows past 300 ms
 * only; a patch the memory can't take, and a small array, render synchronously; another tool, a
 * pixel edit on the layer and any other step land it first, in order.
 */
@RunWith(RobolectricTestRunner::class)
class ArrayRendersRobolectricTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() = ArrayDraw.clearCaches()

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun tool(c: EditorController) = c.tools.getValue(ToolId.ARRAY) as ArrayTool

    /** A 48 × 40 source at (20, 30): a colour ramp, semi-transparent unless [opaque] (resampled copies show any difference). */
    private fun paintSource(b: Bitmap, opaque: Boolean) {
        val w = 48
        val h = 40
        val px = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val a = if (opaque) 255 else 120 + (x * 3 + y * 2) % 136
            Color.argb(a, 40 + x * 4, 30 + y * 5, 200 - x - y)
        }
        b.setPixels(px, 0, w, 20, 30, w, h)
    }

    /** A raster array of [paintSource] (count 3 side by side) on [c]; the Array tool is current. */
    private fun rasterArrayOn(c: EditorController, opaque: Boolean = false): Layer {
        val src = c.activeLayer
        paintSource(src.bitmap, opaque)
        c.setSelection(Selection.fromPath(Path().apply { addRect(10f, 20f, 80f, 80f, Path.Direction.CW) }, c.doc.width, c.doc.height, antiAlias = false), recordUndo = false)
        assertTrue(c.arrayFromSelection())
        Smoke.pump(20)
        assertEquals(ToolId.ARRAY, c.activeToolId)
        val layer = c.activeLayer
        assertNotNull(layer.array?.pixels)
        return layer
    }

    private fun rasterArray(opaque: Boolean = false): Pair<EditorController, Layer> {
        val c = Smoke.controller(app)
        c.selectLayer(c.doc.layers[1])
        return c to rasterArrayOn(c, opaque)
    }

    /** A text layer ("Hi", 40 px) arrayed as a whole; the Array tool is current. */
    private fun textArray(): Pair<EditorController, Layer> {
        val c = Smoke.controller(app)
        val item = TextItem("Hi", spec = TextSpec(sizePx = 40f), cx = 80f, cy = 60f)
        val text = c.addLayerWithContent("Text", "Add text", textData = TextCodec.encode(item)) { cv ->
            TextRenderer.drawItem(cv, item, TextRenderer.prepare(item), null)
        }!!
        assertTrue(c.arrayWholeLayer(text))
        Smoke.pump(20)
        assertEquals(ToolId.ARRAY, c.activeToolId)
        assertSame(text, tool(c).target)
        return c to text
    }

    /** Waits for the background render to land. */
    private fun land(c: EditorController) {
        assertTrue("the render lands", Smoke.pumpUntil { !c.arrayRenders.isPending })
        Smoke.pump(20)
    }

    /**
     * [actual] holds what [expected] holds, rendered synchronously. The worker draws its patch
     * with the matrix moved to the patch's corner, so a turned or scaled copy's resampling may
     * round differently: by at most 2 levels in a stored (premultiplied) channel, at few pixels
     * (whole-pixel copies are identical).
     */
    private fun assertSameRender(what: String, expected: Bitmap, actual: Bitmap) {
        fun raw(b: Bitmap): ByteArray = ByteBuffer.allocate(b.byteCount).also { b.copyPixelsToBuffer(it) }.array()
        val e = raw(expected)
        val a = raw(actual)
        assertEquals(e.size, a.size)
        var differ = 0
        for (i in e.indices) {
            if (e[i] == a[i]) continue
            differ++
            val d = abs((e[i].toInt() and 0xFF) - (a[i].toInt() and 0xFF))
            val px = i / 4
            assertTrue("$what: pixel (${px % actual.width}, ${px / actual.width}) differs by $d", d <= 2)
        }
        assertTrue("$what: $differ of ${e.size} channels round differently", differ <= e.size / 100)
    }

    /** [layer]'s whole bitmap is what its data draws (a raster array's cache: [ArrayDraw.drawPixels]). */
    private fun assertConsistent(what: String, layer: Layer, w: Int, h: Int) {
        val expected = BitmapUtils.createLayerBitmap(w, h)
        ArrayDraw.drawPixels(Canvas(expected), layer.array!!)
        assertArrayEquals("$what: the pixels are what the data draws", pixels(expected), pixels(layer.bitmap))
        expected.recycle()
    }

    /**
     * The one-step rule and undo exactness: for a raster and a text array, an edit rendering in
     * the background changes nothing (data, pixels, history, edit count) until it lands, keeps
     * its preview up meanwhile, then lands as ONE "Edit array" step with the same pixels as the
     * same edit rendered synchronously; undo and redo restore both states exactly.
     */
    @Test
    fun aBackgroundRenderLandsWithItsDataAsOneStepWithTheSynchronousPixels() {
        for (kind in listOf("raster", "text")) {
            val (c, layer) = if (kind == "raster") rasterArray() else textArray()
            val (twin, twinLayer) = if (kind == "raster") rasterArray() else textArray()
            c.arrayRenders.policy = VectorLayers.Policy.ASYNC
            val t = tool(c)
            val base = layer.array!!.spec
            val specs = listOf(
                base.copy(mode = ArrayMode.CIRCLE, count = 9, centerX = 200f, centerY = 150f),
                base.copy(mode = ArrayMode.TRANSFORM, count = 5, moveX = 60f, moveY = 25f, turnDeg = 25f, scale = 0.85f),
                base.copy(mode = ArrayMode.LINE, count = 4, constantX = 7f, constantY = 9f),
            )
            for (spec in specs) {
                val what = "$kind ${spec.mode}"
                val old = layer.array!!.spec
                val before = pixels(layer.bitmap)
                val steps = c.undoManager.undoCount
                val edits = c.editCount
                t.commit(spec)
                assertTrue("$what: rendering in the background", c.arrayRenders.isPending)
                assertNotNull("$what: the preview stays up", c.renderOverride)
                assertEquals("$what: the data is not changed yet", old, layer.array!!.spec)
                assertArrayEquals("$what: nor the pixels", before, pixels(layer.bitmap))
                assertEquals("$what: no step yet", steps, c.undoManager.undoCount)
                assertEquals("$what: no edit yet", edits, c.editCount)
                land(c)
                assertEquals("$what: ONE step", steps + 1, c.undoManager.undoCount)
                assertEquals(ArrayLabels.EDIT, c.undoManager.undoLabel)
                assertNull("$what: the preview goes with the swap", c.renderOverride)
                assertEquals(spec.sanitized(), layer.array!!.spec)
                tool(twin).commit(spec)
                assertFalse(twin.arrayRenders.isPending)
                assertEquals(spec.sanitized(), twinLayer.array!!.spec)
                assertFalse("$what: the copies changed", before.contentEquals(pixels(twinLayer.bitmap)))
                assertSameRender("$what: the synchronous render's pixels", twinLayer.bitmap, layer.bitmap)
                val landed = pixels(layer.bitmap)
                c.undo()
                assertEquals("$what: undo", old, layer.array!!.spec)
                assertArrayEquals("$what: undo restores every pixel", before, pixels(layer.bitmap))
                c.redo()
                assertEquals("$what: redo", spec.sanitized(), layer.array!!.spec)
                assertArrayEquals("$what: redo brings every pixel back", landed, pixels(layer.bitmap))
            }
            Smoke.assertQuiet(c, kind)
        }
    }

    /**
     * Undo pressed while the render still runs on the worker: it lands first (undo waits for the
     * worker), then undo takes the whole edit back; the landing posted meanwhile adds nothing.
     * Redo brings it back exactly. Redo pressed while another render runs lands that one (a new
     * step, which ends the redo history as any edit does).
     */
    @Test
    fun undoAndRedoWhileARenderRunsAreExact() {
        val (c, layer) = rasterArray()
        val (twin, twinLayer) = rasterArray()
        c.arrayRenders.policy = VectorLayers.Policy.ASYNC
        c.arrayRenders.workerHook = { Thread.sleep(150) }
        val t = tool(c)
        val old = layer.array!!.spec
        val before = pixels(layer.bitmap)
        val steps = c.undoManager.undoCount
        val spec = old.copy(mode = ArrayMode.CIRCLE, count = 7, centerX = 200f, centerY = 150f)
        t.commit(spec)
        assertTrue(c.arrayRenders.isPending)
        c.undo()
        assertFalse("undo landed the render first", c.arrayRenders.isPending)
        assertEquals(old, layer.array!!.spec)
        assertArrayEquals("undo took the whole edit back", before, pixels(layer.bitmap))
        assertEquals(steps, c.undoManager.undoCount)
        assertTrue(c.canRedo)
        assertNull(c.renderOverride)
        Smoke.pump(400)
        assertEquals("nothing lands later", steps, c.undoManager.undoCount)
        assertArrayEquals(before, pixels(layer.bitmap))
        assertFalse(t.rendering)
        tool(twin).commit(spec)
        c.redo()
        assertEquals(spec.sanitized(), layer.array!!.spec)
        assertSameRender("redo", twinLayer.bitmap, layer.bitmap)
        val landed = pixels(layer.bitmap)
        c.undo()
        assertArrayEquals("undo is exact", before, pixels(layer.bitmap))
        c.redo()
        assertArrayEquals("redo is exact", landed, pixels(layer.bitmap))

        // Undo, then an edit rendering while redo is pressed: redo lands it (it ends the redo history).
        c.undo()
        assertTrue(c.canRedo)
        val spec2 = old.copy(count = 5)
        t.commit(spec2)
        assertTrue(c.arrayRenders.isPending)
        c.redo()
        assertFalse(c.arrayRenders.isPending)
        assertEquals(spec2.sanitized(), layer.array!!.spec)
        assertEquals(ArrayLabels.EDIT, c.undoManager.undoLabel)
        assertFalse("the landed edit ended the redo history", c.canRedo)
        tool(twin).commit(old)
        tool(twin).commit(spec2)
        assertSameRender("as rendered synchronously", twinLayer.bitmap, layer.bitmap)
        Smoke.assertQuiet(c, "undo while rendering")
    }

    /**
     * A newer "Edit array" of the same layer drops the render still running: the dropped one
     * hears false at once and records nothing, the preview shows the newer spec, and ONE step
     * lands with the newer spec's synchronous pixels.
     */
    @Test
    fun aNewerEditSupersedesARenderStillRunning() {
        val (c, layer) = rasterArray()
        val (twin, twinLayer) = rasterArray()
        c.arrayRenders.policy = VectorLayers.Policy.ASYNC
        val gate = CountDownLatch(1)
        c.arrayRenders.workerHook = { gate.await(20, TimeUnit.SECONDS) }
        val old = layer.array!!.spec
        val steps = c.undoManager.undoCount
        val before = pixels(layer.bitmap)
        val spec1 = old.copy(mode = ArrayMode.CIRCLE, count = 12, centerX = 200f, centerY = 150f)
        val spec2 = old.copy(mode = ArrayMode.TRANSFORM, count = 4, moveX = 70f, moveY = 30f)
        var done1: Boolean? = null
        var done2: Boolean? = null
        try {
            assertTrue(ArrayOps.edit(c, layer, spec1, ArrayLabels.EDIT) { done1 = it })
            assertTrue(c.arrayRenders.isPending)
            assertNull(done1)
            assertTrue(ArrayOps.edit(c, layer, spec2, ArrayLabels.EDIT) { done2 = it })
            assertEquals("the dropped render hears false at once", false, done1)
            assertTrue("the newer one renders", c.arrayRenders.isPending)
            assertNull(done2)
            assertEquals(old, layer.array!!.spec)
            assertArrayEquals(before, pixels(layer.bitmap))
            assertEquals(steps, c.undoManager.undoCount)
        } finally {
            gate.countDown()
        }
        land(c)
        assertEquals(true, done2)
        assertEquals(false, done1)
        assertEquals("ONE step", steps + 1, c.undoManager.undoCount)
        assertEquals(spec2.sanitized(), layer.array!!.spec)
        assertTrue(ArrayOps.edit(twin, twinLayer, spec2))
        assertSameRender("the newer spec's pixels", twinLayer.bitmap, layer.bitmap)
        c.undo()
        assertEquals(old, layer.array!!.spec)
        assertArrayEquals(before, pixels(layer.bitmap))
        c.redo()

        // Through the tool (a slider released twice): the preview follows the newer spec.
        val t = tool(c)
        val gate2 = CountDownLatch(1)
        c.arrayRenders.workerHook = { gate2.await(20, TimeUnit.SECONDS) }
        val spec3 = spec2.copy(count = 6)
        val spec4 = spec2.copy(count = 3, turnDeg = 45f)
        val steps2 = c.undoManager.undoCount
        try {
            t.commit(spec3)
            assertTrue(c.arrayRenders.isPending)
            t.commit(spec4)
            assertTrue(c.arrayRenders.isPending)
            assertNotNull(c.renderOverride)
            assertEquals(spec4.sanitized(), t.previewSpec)
        } finally {
            gate2.countDown()
        }
        land(c)
        assertEquals(steps2 + 1, c.undoManager.undoCount)
        assertEquals(spec4.sanitized(), layer.array!!.spec)
        assertNull(c.renderOverride)
        assertTrue(ArrayOps.edit(twin, twinLayer, spec4))
        assertSameRender("as rendered synchronously", twinLayer.bitmap, layer.bitmap)
        Smoke.assertQuiet(c, "superseded")
    }

    /**
     * Saving while a render runs writes the layer as it was (its data and its pixels agreeing),
     * and the landed edit is saved by the next save; closing the editor lands a render still
     * running before it saves.
     */
    @Test
    fun savingWhileARenderRunsWritesAConsistentLayerAndClosingLandsItFirst() {
        val bw = app as BrushworkApp
        val id = runBlocking { bw.repository.create(NewCanvasSpec("Array render save", 400, 300, 72f, background = null)) }
        val gate = CountDownLatch(1)
        try {
            val session = EditorSession(bw, id)
            assertTrue(Smoke.pumpUntil { session.state is EditorSession.State.Ready })
            val c = (session.state as EditorSession.State.Ready).controller
            val layer = rasterArrayOn(c, opaque = true)
            val index = c.doc.indexOf(layer)
            val w = c.doc.width
            val h = c.doc.height
            val old = layer.array!!.spec
            c.arrayRenders.policy = VectorLayers.Policy.ASYNC
            c.arrayRenders.workerHook = { gate.await(20, TimeUnit.SECONDS) }
            val spec = old.copy(count = 5, constantX = 4f, constantY = 6f)
            tool(c).commit(spec)
            assertTrue(c.arrayRenders.isPending)
            val save1 = session.saveNow()
            assertTrue("saved", Smoke.pumpUntil { save1.isCompleted })
            assertTrue("still rendering", c.arrayRenders.isPending)
            val saved1 = runBlocking { bw.repository.load(id) }.layers[index]
            assertEquals("saved as it was", old, saved1.array!!.spec)
            assertConsistent("saved while rendering", saved1, w, h)

            gate.countDown()
            land(c)
            assertEquals(spec.sanitized(), layer.array!!.spec)
            val save2 = session.saveNow()
            assertTrue(Smoke.pumpUntil { save2.isCompleted })
            val saved2 = runBlocking { bw.repository.load(id) }.layers[index]
            assertEquals("the landed edit is saved next", spec.sanitized(), saved2.array!!.spec)
            assertConsistent("saved after landing", saved2, w, h)

            c.arrayRenders.workerHook = null
            val spec3 = spec.copy(count = 6, constantX = 10f)
            tool(c).commit(spec3)
            assertTrue(c.arrayRenders.isPending)
            var closed = false
            session.close { closed = true }
            assertTrue("closed", Smoke.pumpUntil { closed })
            val saved3 = runBlocking { bw.repository.load(id) }.layers[index]
            assertEquals("closing landed the render, then saved", spec3.sanitized(), saved3.array!!.spec)
            assertConsistent("saved on close", saved3, w, h)
        } finally {
            gate.countDown()
            runBlocking { bw.repository.delete(id) }
        }
    }

    /**
     * "Rendering array…": not before 300 ms (a render that lands sooner never shows it), shown
     * past 300 ms while the render is still running (an injected slow renderer), gone when it lands.
     */
    @Test
    fun renderingArrayShowsOnlyPast300Ms() {
        val (c, layer) = rasterArray()
        val t = tool(c)
        c.arrayRenders.policy = VectorLayers.Policy.ASYNC
        // A quick render: lands without the chip.
        t.commit(layer.array!!.spec.copy(count = 4))
        assertFalse(t.rendering)
        land(c)
        assertFalse(t.rendering)
        assertFalse(c.arrayRenders.isSlow)

        val gate = CountDownLatch(1)
        c.arrayRenders.workerHook = { gate.await(20, TimeUnit.SECONDS) }
        try {
            t.commit(layer.array!!.spec.copy(mode = ArrayMode.CIRCLE, count = 10, centerX = 200f, centerY = 150f))
            assertTrue(c.arrayRenders.isPending)
            assertFalse("not at once", t.rendering)
            Smoke.pump(100)
            assertFalse("not at 100 ms", t.rendering)
            Smoke.pump(180)
            assertFalse("not at 280 ms", t.rendering)
            Smoke.pump(60)
            assertTrue("past 300 ms", t.rendering)
            assertTrue(c.arrayRenders.isSlow)
            assertEquals("Rendering array…", ArrayLabels.RENDERING)
        } finally {
            gate.countDown()
        }
        land(c)
        assertFalse("gone when it lands", t.rendering)
        assertFalse(c.arrayRenders.isSlow)
        assertEquals(10, layer.array!!.spec.count)
        Smoke.assertQuiet(c, "chip")
    }

    /**
     * Memory: a patch larger than the budget renders synchronously (no background render, the
     * step at once). Small arrays stay synchronous under the automatic policy; a large one goes to
     * the worker. Off the Array tool nothing renders in the background.
     */
    @Test
    fun aPatchTheMemoryCannotTakeAndASmallArrayRenderSynchronously() {
        val (c, layer) = rasterArray()
        val t = tool(c)
        c.arrayRenders.policy = VectorLayers.Policy.ASYNC
        c.arrayRenders.patchBudget = { 0L }
        val steps = c.undoManager.undoCount
        t.commit(layer.array!!.spec.copy(mode = ArrayMode.CIRCLE, count = 8, centerX = 200f, centerY = 150f))
        assertFalse("synchronous when memory is short", c.arrayRenders.isPending)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(8, layer.array!!.spec.count)
        assertNull(c.renderOverride)
        c.arrayRenders.patchBudget = { Runtime.getRuntime().maxMemory() / 8 }

        // AUTO: the 3 copies of a 48 × 40 source cost far below the budget; 200 copies of 500²
        // (the §6.3 row) far above it.
        c.arrayRenders.policy = VectorLayers.Policy.AUTO
        t.commit(layer.array!!.spec.copy(count = 4))
        assertFalse("a small array stays synchronous", c.arrayRenders.isPending)
        assertEquals(steps + 2, c.undoManager.undoCount)
        assertTrue(c.arrayRenders.estimateMs(ArrayRenders.KIND_PIXELS, 200.0 * 60 + 4 * 48 * 40) < 1.0)
        assertTrue(c.arrayRenders.estimateMs(ArrayRenders.KIND_PIXELS, 200.0 * 500 * 500) > ArrayRenders.SYNC_BUDGET_MS)
        assertTrue(c.arrayRenders.estimateMs(ArrayRenders.KIND_SOURCE, 32.0 * 1500 * 400) > ArrayRenders.SYNC_BUDGET_MS)

        // Off the Array tool (an edit made from the layer menu), always synchronous.
        c.arrayRenders.policy = VectorLayers.Policy.ASYNC
        c.selectTool(ToolId.BRUSH)
        assertTrue(ArrayOps.edit(c, layer, layer.array!!.spec.copy(count = 6)))
        assertFalse(c.arrayRenders.isPending)
        assertEquals(steps + 3, c.undoManager.undoCount)
        assertEquals(6, layer.array!!.spec.count)
        Smoke.assertQuiet(c, "synchronous")
    }

    /**
     * Anything else lands a render first, in order: the Array tool stopping being current, a
     * pixel edit starting on the layer, another step (a new layer).
     */
    @Test
    fun anotherToolAPixelEditOrAnotherStepLandTheRenderFirst() {
        val (c, layer) = rasterArray()
        val (twin, twinLayer) = rasterArray()
        val t = tool(c)
        c.arrayRenders.policy = VectorLayers.Policy.ASYNC
        c.arrayRenders.workerHook = { Thread.sleep(120) }
        val steps = c.undoManager.undoCount

        val spec1 = layer.array!!.spec.copy(mode = ArrayMode.CIRCLE, count = 6, centerX = 200f, centerY = 150f)
        t.commit(spec1)
        assertTrue(c.arrayRenders.isPending)
        c.selectTool(ToolId.BRUSH)
        assertFalse("another tool lands it", c.arrayRenders.isPending)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(spec1.sanitized(), layer.array!!.spec)
        assertNull(c.renderOverride)
        assertTrue(ArrayOps.edit(twin, twinLayer, spec1))
        assertSameRender("as rendered synchronously", twinLayer.bitmap, layer.bitmap)

        c.selectTool(ToolId.ARRAY)
        assertSame(layer, t.target)
        val spec2 = spec1.copy(count = 9)
        t.commit(spec2)
        assertTrue(c.arrayRenders.isPending)
        val rec = c.beginEdit(layer, EditTarget.CONTENT)
        assertFalse("a pixel edit on the layer lands it", c.arrayRenders.isPending)
        assertEquals(steps + 2, c.undoManager.undoCount)
        assertEquals(spec2.sanitized(), layer.array!!.spec)
        rec.abort()
        assertTrue(ArrayOps.edit(twin, twinLayer, spec2))
        assertSameRender("as rendered synchronously", twinLayer.bitmap, layer.bitmap)

        val spec3 = spec2.copy(count = 4)
        t.commit(spec3)
        assertTrue(c.arrayRenders.isPending)
        assertNotNull(c.addLayer())
        assertFalse(c.arrayRenders.isPending)
        assertEquals(steps + 4, c.undoManager.undoCount)
        assertEquals("the layer is added after the edit", "Add layer", c.undoManager.undoLabel)
        c.undo()
        assertEquals(ArrayLabels.EDIT, c.undoManager.undoLabel)
        assertEquals(spec3.sanitized(), layer.array!!.spec)
        c.undo()
        assertEquals(spec2.sanitized(), layer.array!!.spec)
        assertSameRender("as rendered synchronously", twinLayer.bitmap, layer.bitmap)
        Smoke.pump(400)
        assertEquals(steps + 2, c.undoManager.undoCount)
        Smoke.assertQuiet(c, "lands first")
    }

    /**
     * Hiding the layer lands the render first (a step); a properties preview that hides it
     * without a step meanwhile does not lose the edit: it lands on the hidden layer.
     */
    @Test
    fun hidingTheLayerDoesNotLoseTheEdit() {
        val (c, layer) = rasterArray()
        val t = tool(c)
        c.arrayRenders.policy = VectorLayers.Policy.ASYNC
        val steps = c.undoManager.undoCount
        t.commit(layer.array!!.spec.copy(count = 5))
        assertTrue(c.arrayRenders.isPending)
        c.toggleVisibility(layer)
        assertFalse(c.arrayRenders.isPending)
        assertEquals("the edit, then the visibility", steps + 2, c.undoManager.undoCount)
        assertEquals(5, layer.array!!.spec.count)
        c.toggleVisibility(layer)
        assertTrue(layer.visible)

        val gate = CountDownLatch(1)
        c.arrayRenders.workerHook = { gate.await(20, TimeUnit.SECONDS) }
        val props = layer.props()
        try {
            t.commit(layer.array!!.spec.copy(count = 7))
            assertTrue(c.arrayRenders.isPending)
            c.previewLayerProps(layer, props.copy(visible = false))
        } finally {
            gate.countDown()
        }
        land(c)
        assertEquals("landed on the hidden layer", 7, layer.array!!.spec.count)
        assertEquals(steps + 4, c.undoManager.undoCount)
        c.previewLayerProps(layer, props)
        Smoke.assertQuiet(c, "hidden")
    }
}
