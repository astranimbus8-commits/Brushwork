package com.brushwork.paint.v19.a1

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.array.ArraySources
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.LayerRenderOverride
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.vector.CurvePaint
import com.brushwork.paint.tools.vector.CurveSettings
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.tools.vector.VectorPreview
import com.brushwork.paint.ui.common.CurveLabels19
import com.brushwork.paint.ui.common.EraseLabels19
import com.brushwork.paint.ui.vector.ErasePixels
import com.brushwork.paint.ui.vector.curveFacts
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.draw.DoomPreview
import com.brushwork.paint.vector.draw.VectorEraseMode
import com.brushwork.paint.vector.draw.VectorEraserModes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import org.robolectric.Shadows.shadowOf

/**
 * v1.9 A1 (R11 a, R14; design 3.2 A1 steps 6 to 10), the independent review's round trips: every
 * erase step the curve tools make (raster "Erase line", "Erase fill", the Path tool's
 * "Erase path", a text layer, a vector layer in Object and Partial, a legacy shape layer) is ONE
 * step that undo and redo restore exactly; an erased vector layer and a "Curves N" layer save and
 * reopen as they were; "Turn into pixels" while an outline is pending reroutes the erase to the
 * layer's pixels; "To Bézier" and its hand-back keep erase mode; taking points back while erasing
 * leaves no trace. Points are placed with taps (pointer down and up), as a finger does.
 */
@RunWith(RobolectricTestRunner::class)
class CurveEraseRoundTripTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val w = 300
    private val h = 220
    private val red = 0xFFD02020.toInt()
    private val blue = 0xFF3070C0.toInt()

    /** "Background" and "Layer 2" (active): "raster" (red), "text" (red pixels of a text), "vector" (empty), "pixel" (transparent, a red square at 20..60). */
    private fun setup(kind: String = "raster", id: String = "t"): EditorController {
        val app = RuntimeEnvironment.getApplication()
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document(id, "t", w, h)
        doc.layers += Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(w, h))
        doc.layers += Layer(doc.newLayerId(), "Layer 2", BitmapUtils.createLayerBitmap(w, h)).also { l ->
            when (kind) {
                "raster" -> l.bitmap.eraseColor(red)
                "text" -> { l.textData = "{}"; l.bitmap.eraseColor(red) }
                "vector" -> { l.vector = VectorContent.EMPTY; l.name = "Vector 1" }
                "pixel" -> Canvas(l.bitmap).drawRect(20f, 20f, 60f, 60f, Paint().apply { color = red })
            }
        }
        doc.activeLayerIndex = 1
        return EditorController(app, doc, scope, settings).also {
            it.viewTransform.set(Matrix())
            it.tools
            it.color = 0xFF203080.toInt()
            it.snapping.enabled = false
        }
    }

    private val EditorController.layer: Layer get() = doc.activeLayer

    /** A plain line 10 px wide, no fill, open. */
    private val plain: (CurveSettings) -> CurveSettings = { it.copy(stroke = CurveStroke.PLAIN, plainWidth = 10f, useBrushSize = false, fill = false, closed = false) }

    /** Tool [id], erasing (or painting when [erase] is false), with points tapped at [pts]. */
    private fun EditorController.tapped(id: ToolId, vararg pts: Vec2, erase: Boolean = true, look: (CurveSettings) -> CurveSettings = plain): CurveTool {
        selectTool(id)
        val t = tools.getValue(id) as CurveTool
        t.update(look)
        if (erase) t.setErase(true)
        for (p in pts) {
            val n = t.pointCount
            tap(p)
            idle()
            assertEquals("$id: the tap at $p adds a point", n + 1, t.pointCount)
        }
        t.deselect()
        return t
    }

    private fun EditorController.tap(p: Vec2) {
        pointerDown(ToolPoint(p.x, p.y))
        pointerUp(ToolPoint(p.x, p.y))
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** What the render override shows the active layer as, now. */
    private fun shown(o: LayerRenderOverride): IntArray {
        val b = BitmapUtils.createLayerBitmap(w, h)
        assertTrue(o.drawContent(Canvas(b)))
        return pixels(b)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun settle() {
        Snapshot.sendApplyNotifications()
        idle()
    }

    private fun box(l: Float, t: Float, r: Float, b: Float) = VPath(
        0, subpaths = listOf(VSubpath(listOf(VAnchor(l, t, true), VAnchor(r, t, true), VAnchor(r, b, true), VAnchor(l, b, true)), closed = true)),
        fill = VPaint.Solid(blue), stroke = VStrokeStyle(color = 0xFF802000.toInt(), width = 3f),
    )

    /** What the active layer is: its pixels, its objects, its shape and text data. */
    private data class State(val pixels: List<Int>, val vector: VectorContent?, val shape: String?, val text: String?)

    private fun EditorController.state(): State {
        settle()
        val l = layer
        return State(pixels(l.bitmap).toList(), l.vector, l.shapeData, l.textData)
    }

    /**
     * [commit] makes ONE step [label]; undo brings back exactly what was there, redo exactly what
     * the step made, each once, and again.
     */
    private fun EditorController.oneStep(where: String, label: String, commit: () -> Unit) {
        val before = state()
        val steps = undoManager.undoCount
        commit()
        assertNull("$where: the erase's preview went", renderOverride)
        assertEquals("$where: ONE step", steps + 1, undoManager.undoCount)
        assertEquals(where, label, undoManager.undoLabel)
        val after = state()
        assertTrue("$where: the step changed the layer", after != before)
        repeat(2) { round ->
            undo()
            assertEquals("$where: undo (round $round) restores exactly", before, state())
            assertEquals(steps, undoManager.undoCount)
            redo()
            assertEquals("$where: redo (round $round) restores exactly", after, state())
            assertEquals(steps + 1, undoManager.undoCount)
        }
    }

    // ------------------------------------------------------------------ undo and redo

    @Test
    fun everyEraseStepOfTheCurveToolsUndoesAndRedoesExactly() {
        // Pixels: a Polyline line, a fill alone, the Path tool's path, a Curve on a text layer.
        run {
            val c = setup()
            val t = c.tapped(ToolId.POLYLINE, Vec2(100f, 30f), Vec2(100f, 200f), Vec2(200f, 200f))
            c.oneStep("raster line", EraseLabels19.STEP_LINE) { t.commit() }
            assertTrue("erase stays on", t.eraseOn)
        }
        run {
            val c = setup()
            val t = c.tapped(ToolId.POLYLINE, Vec2(60f, 60f), Vec2(240f, 60f), Vec2(240f, 160f), Vec2(60f, 160f)) { plain(it).copy(closed = true) }
            t.setPaintMode(CurvePaint.FILL)
            c.oneStep("raster fill", EraseLabels19.STEP_FILL) { t.commit() }
        }
        run {
            val c = setup()
            val t = c.tapped(ToolId.PATH, Vec2(40f, 110f), Vec2(150f, 60f), Vec2(260f, 110f))
            c.oneStep("raster path", EraseLabels19.STEP_PATH) { t.commit() }
        }
        run {
            val c = setup("text")
            val t = c.tapped(ToolId.CURVE, Vec2(100f, 30f), Vec2(100f, 200f))
            c.oneStep("text layer", EraseLabels19.STEP_LINE) { t.commit() }
            assertNull(c.layer.textData)
        }
        // Objects: Object and Partial on a vector layer, Partial on a legacy shape layer.
        for (mode in listOf(VectorEraseMode.OBJECT, VectorEraseMode.PARTIAL)) {
            val c = setup("vector")
            c.vectors.addObjects(c.layer, listOf(box(40f, 60f, 160f, 140f), box(200f, 30f, 260f, 90f)), "Add")
            VectorEraserModes.setMode(c, mode)
            // (A tap on an object while erasing starts the outline: no reopening.)
            val t = c.tapped(ToolId.CURVE, Vec2(100f, 20f), Vec2(100f, 200f))
            assertTrue("$mode: live", c.renderOverride is DoomPreview)
            c.oneStep("vector $mode", EraseLabels19.STEP_LINE) { t.commit() }
            assertEquals("$mode: what is left", if (mode == VectorEraseMode.OBJECT) 1 else 3, c.layer.vector!!.objects.size)
        }
        run {
            val c = setup()
            val o = ShapeObject(ShapeType.RECTANGLE, cx = 100f, cy = 100f, w = 120f, h = 80f, style = ShapeStyle.STROKE_FILL, strokeWidth = 3f, fillColor = blue)
            val shape = c.addLayerWithContent("Shape", "Add shape", shapeData = ShapeCodec.encode(o), draw = ArraySources.shapeDraw(o, ColorMode.RGB, w, h))!!
            c.selectLayer(shape)
            VectorEraserModes.setMode(c, VectorEraseMode.PARTIAL)
            val t = c.tapped(ToolId.POLYLINE, Vec2(100f, 30f), Vec2(100f, 200f))
            c.oneStep("shape layer", EraseLabels19.STEP_LINE) { t.commit() }
            assertTrue(shape.isVectorLayer)
            // Undo turns it back into the shape layer, redo into the cut objects (in oneStep); once more:
            c.undo()
            assertTrue(shape.isShapeLayer)
        }
    }

    // ------------------------------------------------------------------ save and reopen

    @Test
    fun anErasedVectorLayerAndACurvesLayerSaveAndReopenAsTheyWere() {
        val repo = ProjectRepository(RuntimeEnvironment.getApplication())
        // A Partial erase cuts the box in two: the pieces are v1.8 paths, saved and read back as they are.
        run {
            val c = setup("vector", id = "a1-erase-roundtrip")
            c.vectors.addObjects(c.layer, listOf(box(40f, 60f, 160f, 140f)), "Add")
            VectorEraserModes.setMode(c, VectorEraseMode.PARTIAL)
            c.tapped(ToolId.CURVE, Vec2(100f, 20f), Vec2(100f, 200f)).commit()
            settle()
            val content = c.layer.vector!!
            assertEquals(2, content.objects.size)
            val drawn = pixels(c.layer.bitmap)
            try {
                runBlocking { repo.save(c.doc, null) }
                val back = runBlocking { repo.load(c.doc.id) }
                assertTrue("no warnings: ${back.loadWarnings}", back.loadWarnings.isEmpty())
                val layer = back.layers[1]
                assertEquals("the cut pieces as saved", content.objects, layer.vector!!.objects)
                assertArrayEquals("and the layer's pixels", drawn, pixels(layer.bitmap))
            } finally {
                runBlocking { repo.delete(c.doc.id) }
            }
        }
        // "Editable (own layer)": the "Curves 1" layer is a v1.8 vector layer in the file.
        run {
            val c = setup("pixel", id = "a1-own-roundtrip")
            val raster = pixels(c.layer.bitmap)
            c.tapped(ToolId.POLYLINE, Vec2(80f, 100f), Vec2(160f, 140f), Vec2(240f, 100f), erase = false) { plain(it).copy(ownLayer = true) }.commit()
            settle()
            val v = c.layer
            assertEquals("${CurveLabels19.LAYER_BASE} 1", v.name)
            val objects = v.vector!!.objects
            val drawn = pixels(v.bitmap)
            try {
                runBlocking { repo.save(c.doc, null) }
                val back = runBlocking { repo.load(c.doc.id) }
                assertTrue("no warnings: ${back.loadWarnings}", back.loadWarnings.isEmpty())
                assertEquals(3, back.layers.size)
                assertEquals("the active layer", 2, back.activeLayerIndex)
                val layer = back.layers[2]
                assertEquals(v.name, layer.name)
                assertEquals(objects, layer.vector!!.objects)
                assertArrayEquals(drawn, pixels(layer.bitmap))
                assertArrayEquals("the pixel layer as it was", raster, pixels(back.layers[1].bitmap))
            } finally {
                runBlocking { repo.delete(c.doc.id) }
            }
        }
    }

    // ------------------------------------------------------------------ while the outline is pending

    @Test
    fun turnIntoPixelsWhileAnOutlineIsPendingErasesThePixelsInstead() {
        val c = setup("vector")
        c.vectors.addObjects(c.layer, listOf(box(40f, 60f, 160f, 140f)), "Add")
        settle()
        val objects = c.layer.vector!!
        VectorEraserModes.setMode(c, VectorEraseMode.OBJECT)
        val t = c.tapped(ToolId.CURVE, Vec2(100f, 20f), Vec2(100f, 200f))
        assertTrue(c.renderOverride is DoomPreview)
        assertNotNull("the mode cell shows", curveFacts(t).eraseCell)
        val steps = c.undoManager.undoCount
        // The mode cell's "Turn into pixels", confirmed: the layer's own step.
        assertTrue(ErasePixels.turnIntoPixels(c, c.layer))
        settle()
        assertFalse(c.layer.isVectorLayer)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertTrue("the outline still pending", t.hasPendingWork && t.eraseOn)
        assertNull("no mode cell on pixels", curveFacts(t).eraseCell)
        val rasterized = pixels(c.layer.bitmap)
        assertEquals(blue, c.layer.bitmap.getPixel(60, 100))
        assertEquals(blue, c.layer.bitmap.getPixel(100, 100))
        // The erase follows: the pixels under the line go (no longer the whole object).
        val o = c.renderOverride
        assertTrue("$o", o is VectorPreview)
        c.oneStep("turned into pixels", EraseLabels19.STEP_LINE) { t.commit() }
        assertEquals(0, c.layer.bitmap.getPixel(100, 100) ushr 24)
        assertEquals("the rest of the box stays", blue, c.layer.bitmap.getPixel(60, 100))
        c.undo()
        assertArrayEquals(rasterized, pixels(c.layer.bitmap))
        c.undo()
        assertSame("the objects back", objects, c.layer.vector)
    }

    @Test
    fun toBezierAndItsHandBackKeepEraseMode() {
        val c = setup()
        val drawn = pixels(c.layer.bitmap)
        val path = c.tapped(ToolId.PATH, Vec2(40f, 110f), Vec2(150f, 60f), Vec2(260f, 110f))
        assertTrue(c.renderOverride is VectorPreview)
        val steps = c.undoManager.undoCount
        assertTrue(path.toBezier())
        val curve = c.tools.getValue(ToolId.CURVE) as CurveTool
        assertSame(curve, c.currentTool)
        assertTrue("the Curve tool erases the handed-over path", curve.eraseOn)
        assertTrue(curve.hasPendingWork)
        assertTrue(c.renderOverride is VectorPreview)
        // Undo hands it back to the Path tool, erasing still; redo converts it again.
        c.undo()
        assertSame(path, c.currentTool)
        assertTrue(path.eraseOn)
        assertTrue(path.hasPendingWork)
        assertTrue(c.renderOverride is VectorPreview)
        c.redo()
        assertSame(curve, c.currentTool)
        assertTrue(curve.eraseOn)
        assertArrayEquals("nothing erased before ✓", drawn, pixels(c.layer.bitmap))
        assertEquals(steps, c.undoManager.undoCount)
        val preview = shown(c.renderOverride!!)
        assertTrue("the preview erases", preview.any { it ushr 24 == 0 })
        curve.commit()
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(EraseLabels19.STEP_LINE, c.undoManager.undoLabel)
        // The commit is what the preview showed. (The spline's end cap is anti-aliased a few levels
        // apart by the preview and the commit, as when the Path tool paints it: v1.8's
        // VectorPreview and VectorCommit, at most 16 of 255 on a few edge pixels.)
        val done = pixels(c.layer.bitmap)
        val apart = preview.indices.filter { preview[it] != done[it] }
        assertTrue("${apart.size} pixels apart", apart.size <= 40)
        for (i in apart) assertTrue("pixel $i: ${preview[i].toUInt().toString(16)} / ${done[i].toUInt().toString(16)}", kotlin.math.abs((preview[i] ushr 24) - (done[i] ushr 24)) <= 16)
    }

    @Test
    fun takingPointsBackWhileErasingLeavesNoTrace() {
        val c = setup()
        val drawn = pixels(c.layer.bitmap)
        val steps = c.undoManager.undoCount
        val t = c.tapped(ToolId.POLYLINE, Vec2(100f, 30f), Vec2(100f, 200f), Vec2(200f, 200f))
        assertTrue(c.renderOverride is VectorPreview)
        // The controller's undo takes the points back one by one, the erase following them.
        c.undo()
        assertEquals(2, t.pointCount)
        assertTrue(c.renderOverride is VectorPreview)
        c.undo()
        assertEquals(1, t.pointCount)
        assertNull("one point erases nothing", c.renderOverride)
        c.redo()
        assertEquals(2, t.pointCount)
        assertTrue(c.renderOverride is VectorPreview)
        c.undo()
        c.undo()
        assertEquals(0, t.pointCount)
        assertNull(c.renderOverride)
        assertFalse(t.hasPendingWork)
        assertEquals(steps, c.undoManager.undoCount)
        assertArrayEquals(drawn, pixels(c.layer.bitmap))
        assertTrue("erase mode stays on", t.eraseOn)
    }
}
