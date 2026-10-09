package com.brushwork.paint.tools.vector

import android.content.Context
import android.graphics.Matrix
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.ui.common.FolderLabels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.7 (§3.8, area C; forwarded from area A): the Shape tool with a folder active. A new shape
 * with "Editable (own layer)" on goes into a shape layer of its own at the folder's insertion
 * point (inside an open folder), as one step; with it off the shape would paint the folder itself
 * and is refused with "Choose a layer inside the folder to paint" (on drag, on ✓-less placing,
 * and when the option is turned off while a shape is pending). The preview decisions never treat
 * a folder as a plain layer that takes the preview or a live brush stroke. The tool's decisions
 * and the layer structure are asserted, never folder pixels (area A's compositor).
 */
@RunWith(RobolectricTestRunner::class)
class ShapeFolderRobolectricTest {
    private val scopes = ArrayList<CoroutineScope>()

    @After
    fun releaseEditors() {
        for (s in scopes) s.cancel()
        scopes.clear()
    }

    private val w = 200
    private val h = 160

    /** "Layer 1" (raster) and an empty, open, active folder above it; identity view. */
    private fun controller(): Pair<EditorController, Layer> {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("brushwork_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val d = Document("folder", "folder", w, h)
        d.layers += Layer(d.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(w, h))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { scopes += it }
        val c = EditorController(ctx, d, scope, AppSettings(ctx)).also {
            it.viewTransform.set(Matrix())
            it.snapping.enabled = false
            it.tools
            it.color = 0xFF2040C0.toInt()
            it.brush = BrushLibrary.defaultBrush.copy(size = 6f, pressureSize = false, taperStart = 0f, taperEnd = 0f)
        }
        val folder = c.addFolder()!!
        assertTrue(folder.isFolder)
        assertTrue(folder.folderOpen)
        assertSame(folder, c.doc.activeLayer)
        return c to folder
    }

    private fun EditorController.drag(vararg pts: Pair<Float, Float>) {
        pointerDown(ToolPoint(pts[0].first, pts[0].second))
        for (i in 1 until pts.size) pointerMove(ToolPoint(pts[i].first, pts[i].second))
        pointerUp(ToolPoint(pts.last().first, pts.last().second))
    }

    private fun shapeTool(c: EditorController, editable: Boolean): ShapeTool {
        c.selectTool(ToolId.SHAPE)
        val tool = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        tool.update {
            it.copy(
                type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE_FILL, useBrushSize = false, strokeWidth = 4f,
                fillColor = 0xFF40C0E0.toInt(), keepProportions = false, fromCenter = false, snapAngle = false, editable = editable,
            )
        }
        assertEquals(editable, tool.settings.editable)
        return tool
    }

    @Test
    fun aNewShapeWithAnOpenFolderActiveGoesIntoTheFolderAsOneStep() {
        val (c, folder) = controller()
        val tool = shapeTool(c, editable = true)
        val layers = c.doc.layers.size
        val steps = c.undoManager.undoCount
        c.drag(40f to 40f, 90f to 80f, 140f to 120f)
        assertNotNull("pending", tool.box)
        assertNotEquals(FolderLabels.PAINT_REFUSAL, c.message)
        tool.commit()
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(layers + 1, c.doc.layers.size)
        val shape = c.doc.activeLayer
        assertTrue(shape.isShapeLayer)
        assertEquals("the folder's top child", folder.id, shape.parentId)
        assertEquals(c.doc.indexOf(folder) - 1, c.doc.indexOf(shape))
        c.undo()
        assertEquals(layers, c.doc.layers.size)
        assertTrue(c.doc.indexOf(shape) < 0)
    }

    @Test
    fun withEditableOffAShapeOnAFolderIsRefused() {
        val (c, folder) = controller()
        val tool = shapeTool(c, editable = false)
        val layers = c.doc.layers.size
        val steps = c.undoManager.undoCount
        c.drag(40f to 40f, 90f to 80f, 140f to 120f)
        assertEquals(FolderLabels.PAINT_REFUSAL, c.message)
        assertNull("nothing pending", tool.box)
        tool.commit()
        assertEquals(steps, c.undoManager.undoCount)
        assertEquals(layers, c.doc.layers.size)
        assertSame(folder, c.doc.activeLayer)
        // Placing one from the strip (no drag) is refused too.
        c.message = null
        assertFalse(tool.ensurePending())
        assertEquals(FolderLabels.PAINT_REFUSAL, c.message)
        assertNull(tool.box)
    }

    @Test
    fun turningEditableOffWhileAShapeIsPendingOverAFolderIsRefused() {
        val (c, folder) = controller()
        val tool = shapeTool(c, editable = true)
        c.drag(40f to 40f, 90f to 80f, 140f to 120f)
        val pending = tool.box
        assertNotNull(pending)
        tool.update { it.copy(editable = false) }
        assertEquals(FolderLabels.PAINT_REFUSAL, c.message)
        assertTrue("the option is kept", tool.settings.editable)
        assertEquals("the shape stays pending", pending, tool.box)
        // Other options still change.
        tool.update { it.copy(strokeWidth = 8f) }
        assertEquals(8f, tool.settings.strokeWidth, 0f)
        tool.commit()
        val shape = c.doc.activeLayer
        assertTrue(shape.isShapeLayer)
        assertEquals(folder.id, shape.parentId)
    }

    @Test
    fun aFolderIsNeverPreviewedIntoOrStrokedLive() {
        val (c, folder) = controller()
        val tool = shapeTool(c, editable = true)
        // An open folder: the new layer becomes its top child, previewed inside it.
        assertFalse(tool.isPlain(folder))
        assertFalse(tool.newLayerPreviewInOverlay(folder))
        assertFalse(tool.liveBrushForNewLayer(folder))
        folder.folderOpen = false
        assertFalse(tool.isPlain(folder))
        assertFalse(tool.liveBrushForNewLayer(folder))
        // A plain raster layer still takes both.
        val raster = c.doc.layers.first { !it.isFolder }
        assertTrue(tool.isPlain(raster))
        assertTrue(tool.liveBrushForNewLayer(raster))
        assertFalse(tool.newLayerPreviewInOverlay(raster))
    }
}
