package com.brushwork.paint.tools.transform

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
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.ui.common.TransformLabels17
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
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
 * v1.7 (item 16, design §3.16, §3.10) review: the Free deform mesh's in-tool steps survive a
 * change of "Mesh columns" / "Mesh rows" (undo, redo and a history tap bring back earlier shapes
 * on the NEW cells, never a mesh of the old size), and a vector layer asks to be rasterized
 * before Free deform, then deforms as pixels (each its own step; undo brings the objects back).
 */
@RunWith(RobolectricTestRunner::class)
class FreeDeformStepsRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()
    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()

    /** A 128 x 128 document: the square (20, 20)-(100, 100), red left of x = 60, blue right of it. */
    private fun setup(more: (Document) -> Unit = {}): Pair<EditorController, Layer> {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", 128, 128)
        val layer = Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(128, 128))
        Canvas(layer.bitmap).apply {
            drawRect(Rect(20, 20, 60, 100), Paint().apply { color = red })
            drawRect(Rect(60, 20, 100, 100), Paint().apply { color = blue })
        }
        doc.layers += layer
        more(doc)
        val c = EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
        return c to layer
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun tool(c: EditorController): TransformTool {
        c.selectTool(ToolId.TRANSFORM)
        idle()
        return (c.tools.getValue(ToolId.TRANSFORM) as TransformTool).also { it.snapToObjects = false }
    }

    private fun assertAt(x: Float, y: Float, p: Vec2, what: String = "vertex") {
        assertEquals("$what x", x, p.x, 1e-2f)
        assertEquals("$what y", y, p.y, 1e-2f)
    }

    private fun moveSelected(tool: TransformTool, dx: Float, dy: Float) {
        tool.beginGroupEdit("Move points")
        tool.setGroupTransform(Affine2.translate(dx, dy))
        tool.endGroupEdit()
    }

    @Test
    fun changingTheCellsKeepsTheInToolStepsOnTheNewCells() {
        val (c, layer) = setup()
        val tool = tool(c)
        tool.mode = TransformTool.Mode.MESH
        tool.setMeshCells(2, 2)
        val mark = tool.historyMark()
        assertNotNull(mark)
        tool.selectPoints(PointSelection.of(tool.pointCount, 4))
        moveSelected(tool, 20f, 0f)
        assertAt(80f, 60f, tool.pointAt(4))

        // 4 x 4: the old centre is vertex 12, still on the moved content.
        tool.setMeshCells(4, 4)
        assertEquals(25, tool.pointCount)
        assertAt(80f, 60f, tool.pointAt(12))
        assertTrue(tool.canUndoStep)

        // Undo: the shape before the move, on the 4 x 4 cells.
        assertTrue(tool.undoStep())
        assertEquals("undo keeps the new cells", 25, tool.pointCount)
        assertEquals(4, tool.meshColumns)
        assertEquals(4, tool.meshRows)
        assertFalse(tool.isMeshChanged)
        assertAt(60f, 60f, tool.pointAt(12))
        assertAt(20f, 20f, tool.pointAt(0))
        // Redo: the move again, on the 4 x 4 cells.
        assertTrue(tool.redoStep())
        assertEquals(25, tool.pointCount)
        assertTrue(tool.isMeshChanged)
        assertAt(80f, 60f, tool.pointAt(12))

        // A step on the new cells, then a history tap back to the mark taken on the old ones.
        tool.selectPoints(PointSelection.of(tool.pointCount, 0))
        moveSelected(tool, 0f, 4f)
        tool.rollbackHistory(mark)
        assertEquals(25, tool.pointCount)
        assertFalse(tool.isMeshChanged)
        assertFalse(tool.canUndoStep)
        assertAt(60f, 60f, tool.pointAt(12))

        // Back to fewer cells with steps on the stack: still consistent.
        tool.selectPoints(PointSelection.of(tool.pointCount, 12))
        moveSelected(tool, 20f, 0f)
        tool.setMeshCells(3, 2)
        assertEquals(12, tool.pointCount)
        assertTrue(tool.undoStep())
        assertEquals(12, tool.pointCount)
        assertFalse(tool.isMeshChanged)
        assertTrue(tool.redoStep())
        assertTrue(tool.isMeshChanged)

        // Applied as ONE step, drawn through the moved surface.
        val steps = c.undoManager.undoCount
        tool.commit()
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(TransformTool.FREE_DEFORM_LABEL, c.undoManager.undoLabel)
        assertEquals("the red half reaches the moved centre", red, layer.bitmap.getPixel(70, 60))
        assertEquals(blue, layer.bitmap.getPixel(95, 60))
    }

    @Test
    fun aVectorLayerIsRasterizedForFreeDeformAndUndoBringsItsObjectsBack() {
        var made: Layer? = null
        val (c, _) = setup { doc ->
            val v = Layer(doc.newLayerId(), "Vector", BitmapUtils.createLayerBitmap(128, 128)).also { it.vector = VectorContent.EMPTY }
            doc.layers += v
            doc.activeLayerIndex = doc.layers.indexOf(v)
            made = v
        }
        val vector = made!!
        val box = VPath(
            0,
            subpaths = listOf(VSubpath(listOf(VAnchor(30f, 30f, true), VAnchor(90f, 30f, true), VAnchor(90f, 90f, true), VAnchor(30f, 90f, true)), closed = true)),
            fill = VPaint.Solid(0xFF20A040.toInt()),
        )
        c.vectors.addObjects(vector, listOf(box), "Add")
        idle()
        val objects = vector.vector
        val tool = tool(c)
        assertEquals(TransformTool.Lifted.VECTOR, tool.lifted)
        assertEquals(TransformLabels17.RASTERIZE_TO_FREE_DEFORM, tool.modeRefusal(TransformTool.Mode.MESH))
        assertTrue(tool.canRasterizeFor(TransformTool.Mode.MESH))
        tool.mode = TransformTool.Mode.MESH
        assertEquals("not switched to", TransformTool.Mode.FREE, tool.mode)

        val steps = c.undoManager.undoCount
        assertTrue(tool.rasterizeAndDeform(TransformTool.Mode.MESH))
        idle()
        assertNull("rasterized", vector.vector)
        assertEquals("the rasterize is its own step", steps + 1, c.undoManager.undoCount)
        assertEquals(TransformTool.Lifted.PIXELS, tool.lifted)
        assertEquals(TransformTool.Mode.MESH, tool.mode)
        assertTrue(tool.isMeshShown)

        tool.setMeshCells(2, 2)
        assertEquals(9, tool.pointCount)
        assertEquals("the centre vertex over the box's centre", 60f, tool.pointAt(4).x, 1.5f)
        tool.selectPoints(PointSelection.of(tool.pointCount, 4))
        moveSelected(tool, 15f, 0f)
        tool.commit()
        assertEquals(steps + 2, c.undoManager.undoCount)
        assertEquals(TransformTool.FREE_DEFORM_LABEL, c.undoManager.undoLabel)
        c.undo()
        c.undo()
        idle()
        assertEquals("undo brings the objects back", objects, vector.vector)
    }
}
