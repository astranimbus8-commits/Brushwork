package com.brushwork.paint.snap

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.os.Looper
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.GridSettings
import com.brushwork.paint.model.GridType
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.transform.SnapAxis
import com.brushwork.paint.tools.transform.SnapLine
import com.brushwork.paint.tools.transform.SnapSource
import com.brushwork.paint.tools.transform.TransformTool
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
 * v1.4 foundation: the app-wide snapping service / session, and editable shape layers
 * ([Layer.shapeData]) following the same rules as text layers.
 */
@RunWith(RobolectricTestRunner::class)
class SnapFoundationRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()

    private fun setup(w: Int = 200, h: Int = 100, layers: Int = 2, clearPrefs: Boolean = true): EditorController {
        val settings = AppSettings(app)
        if (clearPrefs) settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", w, h)
        repeat(layers) { i -> doc.layers += Layer(doc.newLayerId(), "Layer ${i + 1}", BitmapUtils.createLayerBitmap(w, h)) }
        doc.activeLayerIndex = layers - 1
        return EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun fill(layer: Layer, r: Rect) {
        Canvas(layer.bitmap).drawRect(r, Paint().apply { color = 0xFF000000.toInt() })
        layer.markChanged()
    }

    // ------------------------------------------------------------------ snapping service

    @Test
    fun targetsHoldCanvasLayerBoundsExtraAndFeatures() {
        val c = setup()
        val other = c.doc.layers[0]
        fill(other, Rect(30, 20, 70, 60))
        c.snapping.addLayerFeatures { l -> if (l === other) SnapLine.point(Vec2(111f, 77f), "Vertex") else emptyList() }
        c.snapping.prepare()
        idle()
        assertEquals(Rect(30, 20, 70, 60), c.snapping.bounds(other))
        val t = c.snapping.targets(extra = SnapLine.point(Vec2(5f, 6f), "Point"))
        val xs = t.xs.map { it.pos }
        assertTrue("canvas edges and center", xs.containsAll(listOf(0f, 100f, 200f)))
        assertTrue("layer bounds", xs.containsAll(listOf(30f, 50f, 70f)))
        assertTrue("extra point", t.xs.any { it.pos == 5f && it.source == SnapSource.POINT })
        assertTrue("layer feature", t.xs.any { it.pos == 111f && it.source == SnapSource.POINT && it.label == "Vertex" })
        // Excluded layers give nothing (e.g. the shape layer being edited).
        val ex = c.snapping.targets(exclude = listOf(other))
        assertFalse(ex.xs.any { it.pos == 30f })
        assertFalse(ex.xs.any { it.pos == 111f })
    }

    @Test
    fun sessionSnapsPointsWithinReachAndShowsGuides() {
        val c = setup()
        fill(c.doc.layers[0], Rect(30, 20, 70, 60))
        val s = c.newSnapSession()
        s.begin()
        idle()
        // Threshold is 8 dp = 8 doc px at zoom 1, density 1.
        assertEquals(Vec2(30f, 60f), s.snapPoint(Vec2(33f, 57f)))
        assertTrue(s.guides.any { it.axis == SnapAxis.X && it.pos == 30f })
        assertTrue(s.guides.any { it.axis == SnapAxis.Y && it.pos == 60f })
        // Out of reach on both axes: unchanged, no guides.
        assertEquals(Vec2(15f, 30f), s.snapPoint(Vec2(15f, 30f)))
        assertTrue(s.guides.isEmpty())
        s.end()
        assertTrue(s.guides.isEmpty())
    }

    @Test
    fun snappingOffStillFollowsTheGrid() {
        val c = setup()
        c.snapping.enabled = false
        c.updateGrid(GridSettings(enabled = true, type = GridType.SQUARE, spacingPx = 10f, snap = true))
        val s = c.newSnapSession()
        s.begin()
        assertEquals(Vec2(30f, 60f), s.snapPoint(Vec2(33f, 57f)))
        assertTrue(s.guides.isEmpty())
    }

    @Test
    fun objectSnapWinsOverTheGridPerAxis() {
        val c = setup()
        fill(c.doc.layers[0], Rect(33, 20, 70, 60))
        c.updateGrid(GridSettings(enabled = true, type = GridType.SQUARE, spacingPx = 10f, snap = true))
        val s = c.newSnapSession()
        s.begin()
        idle()
        // x snaps to the layer's left edge (33); y (out of reach of any object) follows the grid.
        assertEquals(Vec2(33f, 40f), s.snapPoint(Vec2(35f, 41f)))
    }

    @Test
    fun oneSettingForAllToolsRemembered() {
        val c = setup()
        val transform = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        assertTrue(c.snapping.enabled)
        transform.snapToObjects = false
        assertFalse(c.snapping.enabled)
        c.snapping.enabled = true
        assertTrue(transform.snapToObjects)
        c.snapping.enabled = false
        val again = setup(clearPrefs = false)
        assertFalse(again.snapping.enabled)
        assertFalse((again.tools.getValue(ToolId.TRANSFORM) as TransformTool).snapToObjects)
    }

    // ------------------------------------------------------------------ shape layers

    @Test
    fun paintingAShapeLayerRasterizesItUndoably() {
        val c = setup()
        val layer = c.activeLayer
        layer.shapeData = "{\"shape\":1}"
        val rec = c.beginEdit(layer)
        rec.touch(Rect(0, 0, 10, 10))
        layer.bitmap.eraseColor(0xFF00FF00.toInt())
        c.commitEdit(rec, "Brush")
        assertNull(layer.shapeData)
        c.undo()
        assertEquals("{\"shape\":1}", layer.shapeData)
        c.redo()
        assertNull(layer.shapeData)
    }

    @Test
    fun keepLayerDataLetsAToolPaintItsOwnShapeLayer() {
        val c = setup()
        val layer = c.activeLayer
        layer.shapeData = "S"
        c.keepLayerData(layer) {
            val rec = c.beginEdit(layer)
            rec.touch(Rect(0, 0, 10, 10))
            c.commitEdit(rec, "Brush")
        }
        assertEquals("S", layer.shapeData)
        // Only inside the block.
        val rec = c.beginEdit(layer)
        rec.touch(Rect(0, 0, 10, 10))
        c.commitEdit(rec, "Brush")
        assertNull(layer.shapeData)
    }

    @Test
    fun updateShapeLayerIsOneUndoStepForPixelsAndData() {
        val c = setup()
        val layer = c.addLayerWithContent("Rectangle", "Shape", shapeData = "A") { it.drawRect(10f, 10f, 20f, 20f, Paint().apply { color = -1 }) }!!
        assertTrue(layer.isShapeLayer)
        assertTrue(layer.hasEditableData)
        val undos = c.undoManager.undoCount
        assertTrue(c.updateShapeLayer(layer, "B", "Edit shape") { it.drawRect(50f, 50f, 60f, 60f, Paint().apply { color = -1 }) })
        assertEquals("B", layer.shapeData)
        assertEquals(undos + 1, c.undoManager.undoCount)
        assertEquals(0, layer.bitmap.getPixel(15, 15))
        assertEquals(-1, layer.bitmap.getPixel(55, 55))
        c.undo()
        assertEquals("A", layer.shapeData)
        assertEquals(-1, layer.bitmap.getPixel(15, 15))
        c.redo()
        assertEquals("B", layer.shapeData)
    }

    @Test
    fun duplicateAndMergeFollowTheTextRules() {
        val c = setup()
        val layer = c.activeLayer
        layer.shapeData = "S"
        val copy = c.duplicateLayer(layer)
        assertNotNull(copy)
        assertEquals("S", copy!!.shapeData)
        // Merging the copy down onto the shape layer makes it a raster layer; undo restores.
        c.mergeDown(copy)
        idle()
        assertNull(layer.shapeData)
        c.undo()
        assertEquals("S", layer.shapeData)
    }
}
