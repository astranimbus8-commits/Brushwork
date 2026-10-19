package com.brushwork.paint.v19.cross

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Layer
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.tools.ErasableTool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeStroke
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.tools.vector.VectorPreview
import com.brushwork.paint.ui.common.EraseLabels19
import com.brushwork.paint.ui.vector.ErasePixels
import com.brushwork.paint.v19.a9.A9Rig
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorOps
import com.brushwork.paint.vector.draw.DoomPreview
import com.brushwork.paint.vector.draw.VectorEraseMode
import com.brushwork.paint.vector.draw.VectorEraserModes
import com.brushwork.paint.vector.render.VectorLayerRenderer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * v1.9 Gate 2 cross test X1 (design 3.4; A5 x A1 x A9; R11, I1, I20): a layer of joined shapes
 * (S1: a rectangle on a layer of its own, then an ellipse drawn while that layer is selected,
 * one vector layer of two shape objects) erased with the outline of each of the four tools
 * (Curve, Polyline, Path, Shape), in each mode the eraser chooser offers:
 * - Object: the rectangle the outline crosses goes whole;
 * - Partial: the rectangle is cut into filled paths in its place, editable objects still;
 * - To intersection: shapes are only boundaries (v1.8's mode, unchanged): over the shapes alone
 *   nothing goes and no step is made; a line across them loses the piece the outline touches, up
 *   to the rectangle's outline;
 * - "Turn into pixels", confirmed after its warning: v1.8's rasterize as a step of its own (with
 *   its toast), then the outline erases the layer's pixels.
 *
 * Each erase is ONE step whose undo and redo give back exactly the layer's objects and pixels;
 * the layer stays a fresh rendering of its objects (I1) and is never turned into pixels by itself
 * (I20); the result saves and opens again as it was. The ellipse, never under the outline, is
 * untouched throughout. A piece cut with Shape opens in a curve tool once erasing is off (A1's
 * reopen), so the pieces are editable lines, not only objects.
 */
@RunWith(RobolectricTestRunner::class)
class CrossObjectsEraseJoinedShapesTest {
    private val rig = A9Rig()
    private val app get() = RuntimeEnvironment.getApplication()
    private val repo by lazy { ProjectRepository(app) }

    @Before
    fun clean() {
        File(app.filesDir, "projects").deleteRecursively()
    }

    @After
    fun close() = rig.close()

    private val blue = 0xFF3070C0.toInt()
    private val green = 0xFF20A040.toInt()
    private val tools = listOf(ToolId.CURVE, ToolId.POLYLINE, ToolId.PATH, ToolId.SHAPE)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Lets the layer's renders and the state observers land. */
    private fun settle(c: EditorController) {
        Snapshot.sendApplyNotifications()
        c.vectors.flushPending()
        repeat(400) {
            idle()
            if (!c.vectors.isRendering) return
            Thread.sleep(5)
        }
        throw AssertionError("the layer is still rendering")
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** A fresh rendering of [content] over the whole document. */
    private fun rendered(c: EditorController, content: VectorContent): IntArray {
        val b = BitmapUtils.createLayerBitmap(c.doc.width, c.doc.height)
        val doc = Rect(0, 0, c.doc.width, c.doc.height)
        VectorLayerRenderer.render(Canvas(b), content, doc, tips = TipCache(), document = doc)
        return pixels(b).also { b.recycle() }
    }

    /**
     * "Layer 1" (raster), and above it the joined layer, active: the rectangle (30, 40) - (110, 100)
     * then the ellipse (170, 40) - (250, 100), both filled blue with a 4 px red outline, drawn with
     * the Shape tool as the user draws them.
     */
    private fun joined(): Pair<EditorController, Layer> {
        val c = rig.controller()
        c.snapping.enabled = false
        val shape = rig.shapeTool(c)
        shape.update {
            it.copy(
                type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE_FILL, strokeWith = ShapeStroke.PLAIN, useBrushSize = false,
                strokeWidth = 4f, fillColor = blue, editable = true, newLayerEachTime = false,
            )
        }
        rig.drag(c, 30f to 40f, 70f to 70f, 110f to 100f)
        shape.commit()
        val layer = c.activeLayer
        assertTrue("the first shape: a shape layer of its own", layer.isShapeLayer && !layer.isVectorLayer)
        shape.update { it.copy(type = ShapeType.ELLIPSE) }
        val steps = c.undoManager.undoCount
        rig.drag(c, 170f to 40f, 210f to 70f, 250f to 100f)
        shape.commit()
        assertEquals("the join is ONE step", steps + 1, c.undoManager.undoCount)
        assertSame(layer, c.activeLayer)
        val objects = layer.vector?.objects ?: throw AssertionError("S1: the shapes joined into a vector layer")
        assertEquals("two shape objects", listOf(true, true), objects.map { it is VShape })
        settle(c)
        return c to layer
    }

    /** Erase mode's step label of tool [id]. */
    private fun label(id: ToolId): String = when (id) {
        ToolId.PATH -> EraseLabels19.STEP_PATH
        ToolId.SHAPE -> EraseLabels19.STEP_SHAPE
        else -> EraseLabels19.STEP_LINE
    }

    /**
     * Tool [id] erasing, its outline pending: a plain vertical line 10 px wide at x = 70 from
     * y = 10 to 200 (dragged with Shape, three points tapped with the curve tools), across the
     * rectangle only. Returns the tool's commit (✓).
     */
    private fun outline(c: EditorController, id: ToolId): () -> Unit {
        c.selectTool(id)
        val t = c.tools.getValue(id)
        if (t is ShapeTool) {
            t.update { it.copy(type = ShapeType.LINE, strokeWith = ShapeStroke.PLAIN, useBrushSize = false, strokeWidth = 10f) }
            t.setErase(true)
            rig.drag(c, 70f to 10f, 70f to 105f, 70f to 200f)
            assertTrue("$id: the outline is pending", t.hasPendingWork && t.eraseOn)
            return { t.commit() }
        }
        val curve = t as CurveTool
        curve.update { it.copy(stroke = CurveStroke.PLAIN, plainWidth = 10f, useBrushSize = false, fill = false, closed = false) }
        curve.setErase(true)
        for (y in listOf(10f, 105f, 200f)) {
            val n = curve.pointCount
            rig.tap(c, 70f, y)
            idle()
            assertEquals("$id: the tap at y = $y adds a point", n + 1, curve.pointCount)
        }
        curve.deselect()
        assertTrue("$id: the outline is pending", curve.hasPendingWork && curve.eraseOn)
        return { curve.commit() }
    }

    /** What [layer] is: its pixels, its objects, its shape and text data. */
    private data class State(val pixels: List<Int>, val vector: VectorContent?, val shape: String?, val text: String?)

    private fun state(c: EditorController, layer: Layer): State {
        settle(c)
        return State(pixels(layer.bitmap).toList(), layer.vector, layer.shapeData, layer.textData)
    }

    /**
     * [commit] makes ONE step [label] on [layer]; undo brings back exactly what was there, redo
     * exactly what the step made, each once, and again.
     */
    private fun oneStep(c: EditorController, layer: Layer, where: String, label: String, commit: () -> Unit) {
        val before = state(c, layer)
        val steps = c.undoManager.undoCount
        commit()
        assertNull("$where: the erase's preview went", c.renderOverride)
        assertEquals("$where: ONE step", steps + 1, c.undoManager.undoCount)
        assertEquals(where, label, c.undoManager.undoLabel)
        val after = state(c, layer)
        assertTrue("$where: the step changed the layer", after != before)
        repeat(2) { round ->
            c.undo()
            assertEquals("$where: undo (round $round) restores exactly", before, state(c, layer))
            assertEquals(steps, c.undoManager.undoCount)
            c.redo()
            assertEquals("$where: redo (round $round) restores exactly", after, state(c, layer))
            assertEquals(steps + 1, c.undoManager.undoCount)
        }
    }

    /** Saves [c]'s artwork, opens it again and checks that [layer] came back as it is now. */
    private fun assertReopens(where: String, c: EditorController, layer: Layer) {
        settle(c)
        runBlocking { repo.save(c.doc, null) }
        try {
            val back = runBlocking { repo.load(c.doc.id) }
            assertTrue("$where: no load warnings: ${back.loadWarnings}", back.loadWarnings.isEmpty())
            val l = back.layers.single { it.id == layer.id }
            assertEquals("$where: the same kind of layer", layer.isVectorLayer, l.isVectorLayer)
            assertNull("$where: no shape data", l.shapeData)
            assertEquals("$where: the objects as saved", layer.vector?.objects, l.vector?.objects)
            assertArrayEquals("$where: the pixels as saved", pixels(layer.bitmap), pixels(l.bitmap))
        } finally {
            runBlocking { repo.delete(c.doc.id) }
        }
    }

    private fun minX(p: VPath): Float = p.subpaths.minOf { s -> s.anchors.minOf { it.x } }

    private fun maxX(p: VPath): Float = p.subpaths.maxOf { s -> s.anchors.maxOf { it.x } }

    /**
     * [piece] of [layer] is an editable line: erase mode off (slot 1) and the Curve tool picked, a
     * tap on its line opens it in a curve tool (the one of its kind, R14); ✕ then leaves the layer
     * exactly as it was, with no step.
     */
    private fun assertOpensInACurveTool(where: String, c: EditorController, layer: Layer, piece: VPath) {
        (c.currentTool as? ErasableTool)?.setErase(false)
        c.selectTool(ToolId.CURVE)
        val before = state(c, layer)
        val steps = c.undoManager.undoCount
        val pts = VectorOps.toVectorPath(piece).flatten(0.25f).single().points
        val p = pts[pts.size / 3]
        rig.tap(c, p.x, p.y)
        idle()
        val open = c.currentTool as? CurveTool
        assertTrue("$where: a tap on the piece's line opens it (${c.activeToolId})", open != null && open.isReopened)
        assertEquals("$where: opening it is no step", steps, c.undoManager.undoCount)
        open!!.discard()
        assertEquals("$where: ✕ is no step", steps, c.undoManager.undoCount)
        assertEquals("$where: ✕ leaves the layer as it was", before, state(c, layer))
    }

    @Test
    fun eachToolErasesTheJoinedShapesInEachModeInOneStepThatUndoesAndReopensExactly() {
        for (id in tools) for (mode in listOf(VectorEraseMode.OBJECT, VectorEraseMode.PARTIAL, VectorEraseMode.TO_INTERSECTION)) {
            val where = "$id in $mode"
            val (c, layer) = joined()
            val (rect, ellipse) = layer.vector!!.objects
            VectorEraserModes.setMode(c, mode)
            val commit = outline(c, id)
            assertTrue("$where: live, through the layer's render override", c.renderOverride is DoomPreview)
            when (mode) {
                VectorEraseMode.OBJECT -> {
                    oneStep(c, layer, where, label(id), commit)
                    assertEquals("$where: the rectangle went whole", listOf(ellipse), layer.vector!!.objects)
                    assertEquals("$where: its pixels too", 0, layer.bitmap.getPixel(45, 70) ushr 24)
                }
                VectorEraseMode.PARTIAL -> {
                    oneStep(c, layer, where, label(id), commit)
                    val after = layer.vector!!.objects
                    assertEquals("$where: two pieces and the ellipse", 3, after.size)
                    assertEquals("$where: the ellipse untouched, on top", ellipse, after[2])
                    val pieces = after.take(2).map { it as? VPath ?: throw AssertionError("$where: a piece is an editable path, not $it") }
                    assertEquals("$where: the first piece keeps the rectangle's id", rect.id, pieces[0].id)
                    assertTrue("$where: the pieces keep the fill", pieces.all { it.fill != null && it.stroke != null })
                    val (left, right) = pieces.sortedBy { minX(it) }
                    assertTrue("$where: the left piece ends at the outline (${maxX(left)})", maxX(left) <= 65.1f)
                    assertTrue("$where: the right piece starts after it (${minX(right)})", minX(right) >= 74.9f)
                    assertEquals("$where: the cut is a gap", 0, layer.bitmap.getPixel(70, 70) ushr 24)
                    assertEquals("$where: beside it the fill stays", blue, layer.bitmap.getPixel(45, 70))
                    // (Once: what the user does next, after cutting with a shape.)
                    if (id == ToolId.SHAPE) assertOpensInACurveTool(where, c, layer, left)
                }
                else -> {
                    // Over shapes alone nothing goes: they are only boundaries (v1.8). No step.
                    val before = state(c, layer)
                    val steps = c.undoManager.undoCount
                    commit()
                    assertNull("$where: the preview went", c.renderOverride)
                    assertEquals("$where: no step", steps, c.undoManager.undoCount)
                    assertEquals("$where: the layer as it was", before, state(c, layer))
                    assertEquals("$where: no shape added", listOf(rect, ellipse), layer.vector!!.objects)
                    // A line across both shapes: the piece the outline touches goes, up to the rectangle's outline.
                    val line = VPath(
                        0, subpaths = listOf(VSubpath(listOf(VAnchor(10f, 70f, true), VAnchor(290f, 70f, true)), closed = false)),
                        stroke = VStrokeStyle(color = green, width = 4f),
                    )
                    c.vectors.addObjects(layer, listOf(line), "Add")
                    settle(c)
                    assertEquals(green, layer.bitmap.getPixel(70, 70))
                    oneStep(c, layer, where, label(id), outline(c, id))
                    val after = layer.vector!!.objects
                    assertEquals("$where: the shapes untouched", listOf(rect, ellipse), after.take(2))
                    val pieces = after.drop(2).map { it as? VPath ?: throw AssertionError("$where: a piece of the line is a path, not $it") }
                    assertEquals("$where: the line in two", 2, pieces.size)
                    val (left, right) = pieces.sortedBy { minX(it) }
                    assertTrue("$where: the left piece ends at the rectangle's left side (${maxX(left)})", maxX(left) <= 31f)
                    assertTrue("$where: the right piece starts at its right side (${minX(right)})", minX(right) >= 109f)
                    assertEquals("$where: the right piece goes on to the line's end", 290f, maxX(right), 0.01f)
                    assertEquals("$where: under the piece that went, the rectangle's fill", blue, layer.bitmap.getPixel(70, 70))
                    assertEquals("$where: past the rectangle the line stays", green, layer.bitmap.getPixel(140, 70))
                }
            }
            // I20: never turned into pixels by itself; I1: its pixels are its objects'.
            assertTrue("$where: still a vector layer", layer.isVectorLayer)
            assertFalse("$where: no rasterize: ${c.message}", c.message.orEmpty().contains("regular layer"))
            assertTrue("$where: every object stays an object", layer.vector!!.objects.all { it is VShape || it is VPath })
            assertArrayEquals("$where: the layer is a fresh rendering of its objects", rendered(c, layer.vector!!), pixels(layer.bitmap))
            assertEquals("$where: the ellipse's pixels stay", blue, layer.bitmap.getPixel(210, 85))
            assertReopens(where, c, layer)
        }
    }

    @Test
    fun turnIntoPixelsAfterItsWarningIsAStepOfItsOwnThenEachToolErasesThePixels() {
        for (id in tools) {
            val where = "$id, turned into pixels"
            val (c, layer) = joined()
            val objects = layer.vector!!
            VectorEraserModes.setMode(c, VectorEraseMode.OBJECT)
            val commit = outline(c, id)
            assertTrue("$where: erasing objects", c.renderOverride is DoomPreview)
            val drawn = state(c, layer)
            val steps = c.undoManager.undoCount
            // The mode cell's "Turn into pixels", confirmed in its dialog (the Shape strip's dialog
            // refreshes the tool as it closes, then the confirm turns the layer into pixels).
            (c.tools.getValue(id) as? ShapeTool)?.refreshPreview()
            assertTrue("$where: confirmed", ErasePixels.turnIntoPixels(c, layer))
            settle(c)
            assertEquals("$where: v1.8's rasterize, ONE step of its own", steps + 1, c.undoManager.undoCount)
            assertEquals("Rasterize vector layer", c.undoManager.undoLabel)
            assertTrue("$where: never silent: ${c.message}", c.message.orEmpty().contains("is now a regular layer"))
            assertFalse("$where: pixels now", layer.isVectorLayer)
            assertEquals("$where: the pixels as they were", drawn.pixels, state(c, layer).pixels)
            assertTrue("$where: the outline still pending", c.currentTool.hasPendingWork)
            assertTrue("$where: the erase follows the layer onto its pixels: ${c.renderOverride}", c.renderOverride is VectorPreview)

            oneStep(c, layer, where, label(id), commit)
            assertFalse(layer.isVectorLayer)
            assertEquals("$where: under the outline the pixels went", 0, layer.bitmap.getPixel(70, 70) ushr 24)
            assertEquals("$where: beside it the rectangle's pixels stay", blue, layer.bitmap.getPixel(45, 70))
            assertEquals("$where: the ellipse's pixels stay", blue, layer.bitmap.getPixel(210, 85))
            val erased = state(c, layer)

            // Undo twice: the joined shapes exactly; redo twice: the erased pixels exactly.
            c.undo()
            c.undo()
            assertEquals("$where: the objects back", objects, layer.vector)
            assertEquals("$where: the layer as it was", drawn, state(c, layer))
            c.redo()
            c.redo()
            assertEquals("$where: the erased pixels again", erased, state(c, layer))
            assertEquals(steps + 2, c.undoManager.undoCount)
            assertReopens(where, c, layer)
        }
    }
}
