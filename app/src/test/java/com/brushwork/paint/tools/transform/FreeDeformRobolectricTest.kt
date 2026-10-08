package com.brushwork.paint.tools.transform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.os.Looper
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Affine2
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.model.LayerTree
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.ui.common.TransformLabels17
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * v1.7 (item 16, design §3.16): Free deform on the Transform tool, against the real controller,
 * undo stack and Skia. The lifted pixels are drawn through a mesh whose vertices move like the
 * points of any point editor (tools/points): one vertex, or a selected group together, each
 * gesture one in-tool step (undo restores the vertices and the selection). Applying the mesh is
 * ONE "Free deform" step; an unchanged mesh is the Free transform it showed. A text (or a
 * folder) is refused with its caption.
 *
 * The 128 x 128 document holds a square, red on its left half and blue on its right, so where
 * the colours meet shows where the mesh moved the content.
 */
@RunWith(RobolectricTestRunner::class)
class FreeDeformRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()
    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()

    /** A text map that moves the centre only (area D owns the real one). */
    private object MoveOnlyMaps : DataMaps {
        override fun textCanMap(m: FloatArray): Boolean = true
        override fun text(textData: String, m: FloatArray): String? = TextCodec.decode(textData)?.let { t ->
            TextCodec.encode(t.copy(cx = m[0] * t.cx + m[1] * t.cy + m[2], cy = m[3] * t.cx + m[4] * t.cy + m[5]))
        }
        override fun shape(shapeData: String, m: FloatArray): String? = null
        override fun array(layer: Layer, m: FloatArray): LayerData? = null
    }

    /**
     * A 128 x 128 document with one layer: the square (20, 20)-(100, 100), red left of x = 60, blue
     * right of it. [more] adds to the document before the controller is made.
     */
    private fun setup(more: (Document, Layer) -> Unit = { _, _ -> }): Pair<EditorController, Layer> {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", 128, 128)
        val layer = Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(128, 128))
        Canvas(layer.bitmap).apply {
            drawRect(Rect(20, 20, 60, 100), Paint().apply { color = red })
            drawRect(Rect(60, 20, 100, 100), Paint().apply { color = blue })
        }
        doc.layers += layer
        more(doc, layer)
        val c = EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
        return c to layer
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** The Transform tool with the square lifted (no snapping), in Free deform with [cells] x [cells] cells. */
    private fun freeDeform(c: EditorController, cells: Int = 2): TransformTool {
        c.selectTool(ToolId.TRANSFORM)
        idle()
        val tool = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        tool.snapToObjects = false
        assertTrue(tool.hasPendingWork)
        tool.mode = TransformTool.Mode.MESH
        assertEquals(TransformTool.Mode.MESH, tool.mode)
        assertTrue(tool.isMeshShown)
        tool.setMeshCells(cells, cells)
        assertEquals((cells + 1) * (cells + 1), tool.pointCount)
        assertFalse(tool.isMeshChanged)
        return tool
    }

    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun assertAt(x: Float, y: Float, p: Vec2, what: String = "vertex") {
        assertEquals("$what x", x, p.x, 1e-2f)
        assertEquals("$what y", y, p.y, 1e-2f)
    }

    private fun drag(c: EditorController, from: Vec2, to: Vec2) {
        c.pointerDown(ToolPoint(from.x, from.y))
        c.pointerMove(ToolPoint((from.x + to.x) / 2f, (from.y + to.y) / 2f))
        c.pointerUp(ToolPoint(to.x, to.y))
    }

    private fun tap(c: EditorController, at: Vec2) {
        c.pointerDown(ToolPoint(at.x, at.y))
        c.pointerUp(ToolPoint(at.x, at.y))
    }

    /** Moves the selected vertices by ([dx], [dy]) through the PointEditor group edit (the pill's and the gizmo's way). */
    private fun moveSelected(tool: TransformTool, dx: Float, dy: Float) {
        tool.beginGroupEdit("Move points")
        tool.setGroupTransform(Affine2.translate(dx, dy))
        tool.endGroupEdit()
    }

    @Test
    fun movingTheCentreVertexDeformsThePixelsInOneStep() {
        val (c, layer) = setup()
        val before = pixels(layer.bitmap)
        val tool = freeDeform(c)
        // 2 x 2 cells over the square: vertices at 20, 60 and 100 on each axis; 4 is the centre.
        assertAt(20f, 20f, tool.pointAt(0))
        assertAt(60f, 60f, tool.pointAt(4))
        assertAt(100f, 100f, tool.pointAt(8))
        val steps = c.undoManager.undoCount

        drag(c, Vec2(60f, 60f), Vec2(80f, 60f))
        assertAt(80f, 60f, tool.pointAt(4))
        assertAt(60f, 20f, tool.pointAt(1), "an edge vertex stays")
        assertEquals("the dragged vertex is selected", listOf(4), tool.pointSelection.indices)
        assertTrue(tool.isMeshChanged)
        assertTrue("one in-tool step", tool.canUndoStep)
        assertArrayEquals("the layer waits for the apply", before, pixels(layer.bitmap))
        assertEquals(steps, c.undoManager.undoCount)
        // The preview already draws the deformed square.
        val preview = BitmapUtils.createLayerBitmap(128, 128)
        c.compositor.drawDocument(Canvas(preview), null, target = null)
        assertEquals("previewed red where it was blue", red, preview.getPixel(70, 60))

        tool.commit()
        assertFalse(tool.hasPendingWork)
        assertNull(c.renderOverride)
        assertEquals("ONE step", steps + 1, c.undoManager.undoCount)
        assertEquals(TransformLabels17.FREE_DEFORM, c.undoManager.undoLabel)
        assertEquals(TransformTool.FREE_DEFORM_LABEL, c.undoManager.undoLabel)
        val b = layer.bitmap
        assertEquals("the red half now reaches the moved vertex", red, b.getPixel(70, 60))
        assertEquals(blue, b.getPixel(90, 60))
        assertEquals(red, b.getPixel(25, 25))
        assertEquals(blue, b.getPixel(95, 95))
        assertEquals("the outline stays", 0, b.getPixel(10, 60))
        assertEquals(0, b.getPixel(110, 60))
        assertEquals(0, b.getPixel(60, 10))

        c.undo()
        assertArrayEquals("one undo restores every pixel", before, pixels(b))
    }

    @Test
    fun threeSelectedVerticesMoveTogetherAsOneInToolStep() {
        val (c, layer) = setup()
        val tool = freeDeform(c)
        val start = (0 until tool.pointCount).map { tool.pointAt(it) }

        // "Select several": a tap toggles a vertex; selecting is not a step.
        tool.selectSeveral = true
        tap(c, Vec2(20f, 60f))
        tap(c, Vec2(60f, 60f))
        tap(c, Vec2(100f, 60f))
        assertEquals(listOf(3, 4, 5), tool.pointSelection.indices)
        assertFalse(tool.isMeshChanged)
        assertFalse(tool.canUndoStep)
        tap(c, Vec2(60f, 60f))
        assertEquals("a second tap deselects", listOf(3, 5), tool.pointSelection.indices)
        tap(c, Vec2(60f, 60f))

        // "Select several" off: a drag on a vertex of the selection moves all three.
        tool.selectSeveral = false
        drag(c, Vec2(60f, 60f), Vec2(60f, 75f))
        for (i in 0 until tool.pointCount) {
            val dy = if (i in 3..5) 15f else 0f
            assertAt(start[i].x, start[i].y + dy, tool.pointAt(i), "vertex $i")
        }
        assertEquals("the selection is kept", listOf(3, 4, 5), tool.pointSelection.indices)
        assertTrue(tool.canUndoStep)

        // Undo takes back the drag only (in the tool), with the vertices and the selection.
        val steps = c.undoManager.undoCount
        tool.selectPoints(PointSelection.none(tool.pointCount))
        c.undo()
        for (i in 0 until tool.pointCount) assertAt(start[i].x, start[i].y, tool.pointAt(i), "vertex $i")
        assertEquals(listOf(3, 4, 5), tool.pointSelection.indices)
        assertTrue("still free deforming", tool.isMeshShown)
        assertEquals(steps, c.undoManager.undoCount)
        c.redo()
        assertAt(60f, 75f, tool.pointAt(4))
        assertAt(20f, 75f, tool.pointAt(3))

        tool.commit()
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(TransformTool.FREE_DEFORM_LABEL, c.undoManager.undoLabel)
        // The middle row went down 15 px: the colours' meeting line is unchanged (x = 60), the content below it is pressed.
        assertEquals(red, layer.bitmap.getPixel(40, 70))
        assertEquals(blue, layer.bitmap.getPixel(80, 70))
    }

    @Test
    fun aTextLayerRefusesFreeDeformWithItsCaptionAndRasterizesOnRequest() {
        val item = TextItem("Hi", spec = TextSpec(sizePx = 32f), cx = 64f, cy = 64f)
        var made: Layer? = null
        val (c, _) = setup { doc, _ ->
            val l = Layer(doc.newLayerId(), "Text", BitmapUtils.createLayerBitmap(128, 128))
            l.textData = TextCodec.encode(item)
            TextRenderer.drawItem(Canvas(l.bitmap), item, TextRenderer.prepare(item), null)
            doc.layers += l
            doc.activeLayerIndex = doc.layers.indexOf(l)
            made = l
        }
        val text = made!!
        c.selectTool(ToolId.TRANSFORM)
        val tool = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        tool.dataMaps = { MoveOnlyMaps }
        tool.start()
        assertEquals(TransformTool.Lifted.TEXT, tool.lifted)

        assertEquals(TransformLabels17.RASTERIZE_TO_FREE_DEFORM, tool.modeRefusal(TransformTool.Mode.MESH))
        assertTrue("\"Rasterize and deform\" is offered", tool.canRasterizeFor(TransformTool.Mode.MESH))
        tool.mode = TransformTool.Mode.MESH
        assertEquals("not switched to", TransformTool.Mode.FREE, tool.mode)
        assertFalse(tool.isMeshShown)
        assertEquals(0, tool.pointCount)

        val steps = c.undoManager.undoCount
        assertTrue(tool.rasterizeAndDeform(TransformTool.Mode.MESH))
        assertNull("rasterized", text.textData)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(TransformTool.RASTERIZE_TEXT_LABEL, c.undoManager.undoLabel)
        assertEquals(TransformTool.Lifted.PIXELS, tool.lifted)
        assertEquals(TransformTool.Mode.MESH, tool.mode)
        assertTrue("the mesh is shown on the pixels", tool.isMeshShown)
        assertNull(tool.modeRefusal(TransformTool.Mode.MESH))
    }

    @Test
    fun aFolderRefusesFreeDeform() {
        val (c, _) = setup { doc, layer ->
            val folder = Layer.newFolder(doc.newLayerId(), "Folder")
            layer.parentId = folder.id
            doc.layers += folder
            doc.activeLayerIndex = doc.layers.indexOf(folder)
        }
        assertNull(LayerTree.check(c.doc.layers))
        c.selectTool(ToolId.TRANSFORM)
        val tool = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        tool.start()
        assertEquals(TransformTool.Lifted.FOLDER, tool.lifted)
        assertEquals(TransformLabels17.ONE_LAYER, tool.modeRefusal(TransformTool.Mode.MESH))
        assertFalse("nothing to rasterize", tool.canRasterizeFor(TransformTool.Mode.MESH))
        tool.mode = TransformTool.Mode.MESH
        assertEquals(TransformTool.Mode.FREE, tool.mode)
        assertFalse(tool.isMeshShown)
    }

    @Test
    fun inToolUndoRedoResetAndHistoryRollback() {
        val (c, _) = setup()
        val tool = freeDeform(c)
        val mark = tool.historyMark()
        assertNotNull("a mesh has a history mark", mark)

        tool.selectPoints(PointSelection.of(tool.pointCount, 4))
        moveSelected(tool, 10f, 0f)
        assertAt(70f, 60f, tool.pointAt(4))
        val afterFirst = tool.historyMark()
        tool.selectPoints(PointSelection.of(tool.pointCount, 0, 8))
        moveSelected(tool, 0f, 5f)
        assertAt(20f, 25f, tool.pointAt(0))
        assertAt(100f, 105f, tool.pointAt(8))

        assertTrue(tool.undoStep())
        assertAt(20f, 20f, tool.pointAt(0))
        assertAt(70f, 60f, tool.pointAt(4))
        assertEquals("the selection as it was before the step", listOf(0, 8), tool.pointSelection.indices)
        assertTrue(tool.canRedoStep)
        assertTrue(tool.redoStep())
        assertAt(20f, 25f, tool.pointAt(0))

        // A history tap over the UI rolls back what was done after its mark.
        tool.rollbackHistory(afterFirst)
        assertAt(20f, 20f, tool.pointAt(0))
        assertAt(70f, 60f, tool.pointAt(4))
        tool.rollbackHistory(mark)
        assertAt(60f, 60f, tool.pointAt(4))
        assertFalse(tool.isMeshChanged)
        assertFalse(tool.canUndoStep)

        // "Reset mesh" is one step back to the mesh Free deform started with.
        tool.selectPoints(PointSelection.of(tool.pointCount, 4))
        moveSelected(tool, -12f, 7f)
        assertTrue(tool.resetMesh())
        assertFalse(tool.isMeshChanged)
        assertAt(60f, 60f, tool.pointAt(4))
        assertFalse("nothing to reset", tool.resetMesh())
        assertTrue(tool.undoStep())
        assertAt(48f, 67f, tool.pointAt(4))
    }

    @Test
    fun moreCellsKeepTheShapeAndTheSettingsAreRemembered() {
        val (c, _) = setup()
        val tool = freeDeform(c)
        tool.selectPoints(PointSelection.of(tool.pointCount, 4))
        moveSelected(tool, 20f, 0f)

        // 4 x 4: the old centre is vertex 12 (row 2, column 2) and stays on the moved content.
        tool.setMeshCells(4, 4)
        assertEquals(25, tool.pointCount)
        assertTrue("still deformed", tool.isMeshChanged)
        assertAt(80f, 60f, tool.pointAt(12))
        assertTrue("the selection is cleared", tool.pointSelection.isEmpty)

        tool.smoothMesh = false
        assertEquals(MeshSettings(4, 4, smooth = false), MeshSettings.load(c.settings))
        tool.setMeshCells(20, 0)
        assertEquals(MeshDeform.MAX_CELLS, tool.meshColumns)
        assertEquals(1, tool.meshRows)
        assertEquals(MeshSettings(MeshDeform.MAX_CELLS, 1, smooth = false), MeshSettings.load(c.settings))

        // The next Free deform starts with them.
        tool.commit()
        assertFalse(tool.hasPendingWork)
        tool.start()
        assertTrue(tool.isMeshShown)
        assertEquals(MeshDeform.MAX_CELLS, tool.meshColumns)
        assertEquals(1, tool.meshRows)
        assertFalse(tool.smoothMesh)
    }

    @Test
    fun anUnchangedMeshIsTheFreeTransformAndLeavingAChangedOneAppliesIt() {
        val (c, layer) = setup()
        c.selectTool(ToolId.TRANSFORM)
        idle()
        val tool = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        tool.snapToObjects = false
        tool.moveBy(10f, 0f)
        // The mesh starts over the box as it is shown (the pending move kept).
        tool.mode = TransformTool.Mode.MESH
        tool.setMeshCells(2, 2)
        assertAt(30f, 20f, tool.pointAt(0))
        assertFalse(tool.isMeshChanged)
        tool.mode = TransformTool.Mode.FREE
        assertFalse(tool.isMeshShown)
        val steps = c.undoManager.undoCount
        tool.commit()
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals("an unchanged mesh is a plain transform", TransformTool.TRANSFORM_LABEL, c.undoManager.undoLabel)
        assertEquals(red, layer.bitmap.getPixel(30, 30))
        assertEquals(blue, layer.bitmap.getPixel(109, 99))
        assertEquals(0, layer.bitmap.getPixel(25, 30))

        // Leaving Free deform with a deformed mesh applies it (one step) and lifts the result again.
        tool.start()
        tool.mode = TransformTool.Mode.MESH
        tool.setMeshCells(2, 2)
        tool.selectPoints(PointSelection.of(tool.pointCount, 4))
        moveSelected(tool, 0f, 20f)
        val before = pixels(layer.bitmap)
        tool.mode = TransformTool.Mode.FREE
        assertEquals(TransformTool.Mode.FREE, tool.mode)
        assertFalse(tool.isMeshShown)
        assertEquals(steps + 2, c.undoManager.undoCount)
        assertEquals(TransformTool.FREE_DEFORM_LABEL, c.undoManager.undoLabel)
        assertTrue("the result is lifted again", tool.hasPendingWork)
        tool.commit()
        assertFalse("the pixels moved", before.contentEquals(pixels(layer.bitmap)))
        assertEquals(steps + 2, c.undoManager.undoCount)
    }
}
