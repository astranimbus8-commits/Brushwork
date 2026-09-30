package com.brushwork.paint.tools.transform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.os.Looper
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Transform tool v1.3 against the real controller, undo stack and Skia: Delete (lifted content,
 * selections, masks, placed pictures), smart-guide snapping while dragging / resizing / nudging,
 * scaling from the center and the reference point of the Numbers sheet.
 */
@RunWith(RobolectricTestRunner::class)
class TransformSnapDeleteRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()

    /** A [w] x [h] document with [layers] empty layers (the top one active), view at zoom 1, density 1. */
    private fun setup(w: Int, h: Int, layers: Int = 1): EditorController {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", w, h)
        repeat(layers) { i -> doc.layers += Layer(doc.newLayerId(), "Layer ${i + 1}", BitmapUtils.createLayerBitmap(w, h)) }
        doc.activeLayerIndex = layers - 1
        return EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun tool(c: EditorController) = c.tools.getValue(ToolId.TRANSFORM) as TransformTool

    private fun activate(c: EditorController): TransformTool {
        c.selectTool(ToolId.TRANSFORM)
        idle()
        return tool(c)
    }

    private fun fill(b: Bitmap, r: Rect, color: Int) = Canvas(b).drawRect(r, Paint().apply { this.color = color })

    private fun drag(c: EditorController, from: Vec2, vararg to: Vec2) {
        c.pointerDown(ToolPoint(from.x, from.y))
        for (p in to) c.pointerMove(ToolPoint(p.x, p.y))
        val last = to.last()
        c.pointerUp(ToolPoint(last.x, last.y))
    }

    private fun rectSelection(w: Int, h: Int, r: Rect): Selection =
        Selection.fromPath(Path().apply { addRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), Path.Direction.CW) }, w, h, antiAlias = false)

    private fun bounds(t: TransformTool) = t.transformState!!.bounds()

    // ================================================================== Delete

    @Test
    fun deleteRemovesTheLiftedLayerContentInOneUndoStep() {
        val c = setup(64, 64)
        val layer = c.activeLayer
        fill(layer.bitmap, Rect(10, 10, 30, 30), RED)
        val t = activate(c)
        t.moveBy(20f, 0f)
        assertTrue(t.deleteContent())
        assertFalse(t.hasPendingWork)
        assertNull(c.renderOverride)
        // The lifted area is cleared and nothing is put back where it was moved to.
        assertEquals(0, layer.bitmap.getPixel(15, 15))
        assertEquals(0, layer.bitmap.getPixel(45, 15))
        assertEquals(TransformTool.DELETE_LABEL, c.undoManager.undoLabel)
        assertEquals(1, c.undoManager.undoCount)
        c.undo()
        assertEquals(RED, layer.bitmap.getPixel(15, 15))
        assertEquals(0, layer.bitmap.getPixel(45, 15))
        assertFalse(c.canUndo)
        // Nothing lifted: nothing to delete.
        assertFalse(t.hasPendingWork)
        assertFalse(t.deleteContent())
    }

    @Test
    fun deleteClearsOnlyTheLiftedSelectionAndKeepsTheSelection() {
        val c = setup(64, 64)
        val layer = c.activeLayer
        fill(layer.bitmap, Rect(0, 0, 40, 40), RED)
        c.setSelection(rectSelection(64, 64, Rect(10, 10, 20, 20)), recordUndo = false)
        val t = activate(c)
        t.moveBy(15f, 0f)
        assertTrue(t.deleteContent())
        val b = layer.bitmap
        assertEquals(0, b.getPixel(15, 15))       // the lifted pixels are gone
        assertEquals(RED, b.getPixel(27, 15))     // the moved copy was not put down
        assertEquals(RED, b.getPixel(5, 5))       // unselected content untouched
        assertEquals(Rect(10, 10, 20, 20), c.selection!!.bounds)
        assertEquals(1, c.undoManager.undoCount)
        c.undo()
        assertEquals(RED, b.getPixel(15, 15))
    }

    @Test
    fun deleteOnAMaskRestoresTheMaskBackground() {
        val c = setup(64, 64)
        val layer = c.activeLayer
        layer.bitmap.eraseColor(RED)
        val mask = BitmapUtils.createMaskBitmap(64, 64)
        fill(mask, Rect(10, 10, 20, 20), BLACK)
        layer.mask = mask
        layer.editingMask = true
        val t = activate(c)
        assertEquals(DocBox(10f, 10f, 20f, 20f), bounds(t))
        assertTrue(t.deleteContent())
        assertEquals(WHITE, mask.getPixel(15, 15))
        assertEquals(RED, layer.bitmap.getPixel(15, 15))
        assertEquals(TransformTool.DELETE_LABEL, c.undoManager.undoLabel)
        c.undo()
        assertEquals(BLACK, mask.getPixel(15, 15))
    }

    @Test
    fun deleteIsRefusedOnALockedLayer() {
        val c = setup(32, 32)
        val layer = c.activeLayer
        fill(layer.bitmap, Rect(0, 0, 8, 8), RED)
        val t = activate(c)
        t.moveBy(4f, 4f)
        layer.locked = true
        assertFalse(t.deleteContent())
        assertTrue(c.message!!.contains("locked"))
        assertEquals(RED, layer.bitmap.getPixel(2, 2))
        assertFalse(c.canUndo)
    }

    @Test
    fun deletingAPlacedPictureRemovesItsLayerUndoably() {
        val c = setup(100, 100)
        val base = c.activeLayer
        val img = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(BLUE) }
        c.importImageAsLayer(img)
        idle()
        val t = tool(c)
        assertTrue(t.isPlacement)
        val placed = c.activeLayer
        t.moveBy(5f, 5f)
        assertTrue(t.deleteContent())
        idle()
        assertFalse(t.hasPendingWork)
        assertEquals(listOf(base), c.doc.layers.toList())
        assertEquals(TransformTool.DELETE_LABEL, c.undoManager.undoLabel)
        // Undo brings the picture back on its layer, where it was...
        c.undo()
        assertEquals(listOf(base, placed), c.doc.layers.toList())
        assertSame(placed, c.activeLayer)
        assertEquals(BLUE, placed.bitmap.getPixel(55, 55))
        assertEquals(TransformTool.IMPORT_LABEL, c.undoManager.undoLabel)
        // ...and the import itself is the step before.
        c.undo()
        assertEquals(listOf(base), c.doc.layers.toList())
        assertFalse(c.canUndo)
        c.redo(); c.redo()
        assertEquals(listOf(base), c.doc.layers.toList())
    }

    @Test
    fun deletingAPastedPictureRemovesThePastedLayer() {
        val c = setup(80, 80)
        val base = c.activeLayer
        fill(base.bitmap, Rect(10, 10, 30, 30), RED)
        c.editWholeLayer(base, "Seed") {}
        assertTrue(c.copySelection())
        c.paste()
        idle()
        val t = tool(c)
        assertTrue(t.isPlacement)
        assertEquals(2, c.doc.layers.size)
        assertTrue(t.deleteContent())
        idle()
        assertEquals(listOf(base), c.doc.layers.toList())
        assertEquals(RED, base.bitmap.getPixel(15, 15)) // the source is untouched
        c.undo()
        assertEquals(2, c.doc.layers.size)
        assertEquals(RED, c.activeLayer.bitmap.getPixel(15, 15))
    }

    // ================================================================== smart guides

    /**
     * 500 x 400 canvas (center 250, 200). "Layer 1" (below) has content at (300, 100)-(400, 160);
     * the active "Layer 2" has a 40 x 20 block at (20, 20).
     */
    private fun twoObjects(): Pair<EditorController, Layer> {
        val c = setup(500, 400, layers = 2)
        fill(c.doc.layers[0].bitmap, Rect(300, 100, 400, 160), BLUE)
        c.doc.layers[0].markChanged()
        val top = c.doc.layers[1]
        fill(top.bitmap, Rect(20, 20, 60, 40), RED)
        top.markChanged()
        return c to top
    }

    @Test
    fun draggingSnapsToTheOtherLayersEdgesAndCenters() {
        val (c, top) = twoObjects()
        val t = activate(c)
        assertTrue(t.snapToObjects) // on by default
        assertEquals(DocBox(20f, 20f, 60f, 40f), bounds(t))

        // Left / top: the box would land at (303, 103): 3 px from the other layer's left and top.
        c.pointerDown(ToolPoint(40f, 30f))
        c.pointerMove(ToolPoint(323f, 113f))
        assertEquals(DocBox(300f, 100f, 340f, 120f), bounds(t))
        val g = t.activeGuides
        assertTrue("guides $g", g.any { it.axis == SnapAxis.X && it.pos == 300f && it.label == "Layer 1 left" })
        assertTrue("guides $g", g.any { it.axis == SnapAxis.Y && it.pos == 100f && it.label == "Layer 1 top" })
        // The guides are drawn across the canvas (magenta line at x = 300).
        val overlay = BitmapUtils.createLayerBitmap(500, 400)
        t.drawOverlay(Canvas(overlay), c.viewTransform)
        assertTrue((overlay.getPixel(300, 300) ushr 24) != 0 || (overlay.getPixel(299, 300) ushr 24) != 0)
        c.pointerUp(ToolPoint(323f, 113f))
        assertTrue("guides go away on release", t.activeGuides.isEmpty())
        assertEquals("release keeps the snapped place", DocBox(300f, 100f, 340f, 120f), bounds(t))

        // Centers: raw (328, 122) -> the box center meets the other layer's center (350, 130).
        drag(c, Vec2(320f, 110f), Vec2(348f, 132f))
        assertEquals(DocBox(330f, 120f, 370f, 140f), bounds(t))

        // Right / bottom: raw (363, 143)-(403, 163) -> (360, 140)-(400, 160).
        c.pointerDown(ToolPoint(350f, 130f))
        c.pointerMove(ToolPoint(383f, 153f))
        assertEquals(DocBox(360f, 140f, 400f, 160f), bounds(t))
        assertTrue(t.activeGuides.any { it.label == "Layer 1 right" })
        assertTrue(t.activeGuides.any { it.label == "Layer 1 bottom" })
        c.pointerUp(ToolPoint(383f, 153f))

        t.commit()
        assertEquals(RED, top.bitmap.getPixel(360, 140))
        assertEquals(RED, top.bitmap.getPixel(399, 159))
        assertEquals(0, top.bitmap.getPixel(359, 140))
        assertEquals(0, top.bitmap.getPixel(400, 159))
    }

    @Test
    fun draggingSnapsToTheCanvasCenter() {
        val (c, _) = twoObjects()
        val t = activate(c)
        // Raw box (233, 187)-(273, 207): its center is 3 px off the canvas center both ways.
        drag(c, Vec2(40f, 30f), Vec2(253f, 197f))
        assertEquals(DocBox(230f, 190f, 270f, 210f), bounds(t))
        c.pointerDown(ToolPoint(250f, 200f))
        c.pointerMove(ToolPoint(250f, 200f))
        assertTrue(t.activeGuides.any { it.axis == SnapAxis.X && it.label == "Canvas center" })
        assertTrue(t.activeGuides.any { it.axis == SnapAxis.Y && it.label == "Canvas center" })
        c.pointerUp(ToolPoint(250f, 200f))
    }

    @Test
    fun movingFartherThanTheSnapDistanceLetsGo() {
        val (c, _) = twoObjects()
        val t = activate(c)
        c.pointerDown(ToolPoint(40f, 30f))
        // Raw left 297 (3 px from 300): snapped...
        c.pointerMove(ToolPoint(317f, 70f))
        assertEquals(300f, bounds(t).left, 0f)
        assertTrue(t.activeGuides.isNotEmpty())
        // ...raw left 291 (9 px away, the snap distance is 8 dp = 8 px here): free again.
        c.pointerMove(ToolPoint(311f, 70f))
        assertEquals(DocBox(291f, 60f, 331f, 80f), bounds(t))
        assertTrue(t.activeGuides.isEmpty())
        c.pointerUp(ToolPoint(311f, 70f))
        assertEquals(DocBox(291f, 60f, 331f, 80f), bounds(t))

        // Zoomed in 4x the same 8 dp are only 2 document px.
        c.viewTransform.set(Matrix().apply { setScale(4f, 4f) })
        drag(c, Vec2(311f, 70f), Vec2(317f, 70f)) // raw left 297: 3 px > 2 px
        assertEquals(297f, bounds(t).left, 0f)
    }

    @Test
    fun snappingCanBeTurnedOffAndIsRemembered() {
        val (c, _) = twoObjects()
        val t = activate(c)
        t.snapToObjects = false
        drag(c, Vec2(40f, 30f), Vec2(323f, 113f))
        assertEquals(DocBox(303f, 103f, 343f, 123f), bounds(t))
        assertTrue(t.activeGuides.isEmpty())
        t.anchor = TransformAnchor.BOTTOM
        t.scaleFromCenter = true
        // Another editor (same app settings) starts with the same options.
        val doc2 = Document("u", "u", 50, 50).apply { layers += Layer(newLayerId(), "L", BitmapUtils.createLayerBitmap(50, 50)) }
        val t2 = tool(EditorController(app, doc2, scope, AppSettings(app)))
        assertFalse(t2.snapToObjects)
        assertEquals(TransformAnchor.BOTTOM, t2.anchor)
        assertTrue(t2.scaleFromCenter)
    }

    @Test
    fun resizeHandlesSnapTheDraggedSides() {
        val (c, _) = twoObjects()
        val t = activate(c)
        t.keepAspect = false
        // Bottom-right corner (60, 40) dragged to (297, 97): 3 px short of the other layer's
        // left (300) and top (100) lines.
        c.pointerDown(ToolPoint(60f, 40f))
        c.pointerMove(ToolPoint(297f, 97f))
        assertBox(DocBox(20f, 20f, 300f, 100f), bounds(t))
        assertTrue(t.activeGuides.any { it.axis == SnapAxis.X && it.pos == 300f })
        assertTrue(t.activeGuides.any { it.axis == SnapAxis.Y && it.pos == 100f })
        c.pointerUp(ToolPoint(297f, 97f))
        assertBox(DocBox(20f, 20f, 300f, 100f), bounds(t))
        assertTrue("guides go away on release", t.activeGuides.isEmpty())
    }

    @Test
    fun keepAspectResizeSnapsTheCloserSide() {
        val (c, _) = twoObjects()
        val t = activate(c)
        // 2:1 box; its bottom-right corner dragged to (348, 184): the right edge is 2 px from the
        // other layer's center line (350), the bottom far from any line.
        c.pointerDown(ToolPoint(60f, 40f))
        c.pointerMove(ToolPoint(348f, 184f))
        val b = bounds(t)
        assertEquals(350f, b.right, 1e-3f)
        assertEquals(2f, b.width / b.height, 1e-3f) // the ratio is kept
        assertEquals(20f, b.left, 1e-3f)
        assertEquals(20f, b.top, 1e-3f)
        assertTrue(t.activeGuides.any { it.axis == SnapAxis.X && it.label == "Layer 1 center" })
        c.pointerUp(ToolPoint(348f, 184f))
    }

    private fun assertBox(expected: DocBox, actual: DocBox, tol: Float = 1e-3f) {
        assertEquals("left of $actual", expected.left, actual.left, tol)
        assertEquals("top of $actual", expected.top, actual.top, tol)
        assertEquals("right of $actual", expected.right, actual.right, tol)
        assertEquals("bottom of $actual", expected.bottom, actual.bottom, tol)
    }

    @Test
    fun handlesCanScaleFromTheCenter() {
        val (c, _) = twoObjects()
        val t = activate(c)
        t.keepAspect = false
        t.scaleFromCenter = true
        drag(c, Vec2(60f, 40f), Vec2(70f, 45f))
        assertEquals(DocBox(10f, 15f, 70f, 45f), bounds(t))
        assertEquals(Vec2(40f, 30f), t.transformState!!.center())
    }

    @Test
    fun nudgesStopOnGuides() {
        val (c, _) = twoObjects()
        val t = activate(c)
        t.moveBy(270f, 40f)
        assertEquals(DocBox(290f, 60f, 330f, 80f), bounds(t))
        t.nudgeStepPx = 20.0
        t.nudge(1, 0)
        assertEquals("stops on the other layer's left edge", DocBox(300f, 60f, 340f, 80f), bounds(t))
        assertTrue(t.activeGuides.any { it.pos == 300f && it.axis == SnapAxis.X })
        // Without snapping it moves by the full step.
        t.snapToObjects = false
        t.nudge(1, 0)
        assertEquals(DocBox(320f, 60f, 360f, 80f), bounds(t))
    }

    @Test
    fun placedPicturesSnapToo() {
        val c = setup(200, 100, layers = 1)
        fill(c.activeLayer.bitmap, Rect(120, 10, 180, 50), BLUE)
        c.activeLayer.markChanged()
        c.importImageAsLayer(Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(RED) })
        idle()
        val t = tool(c)
        assertTrue(t.isPlacement)
        assertEquals(DocBox(90f, 40f, 110f, 60f), bounds(t))
        // Raw top 13: 3 px below the other layer's top (10).
        drag(c, Vec2(100f, 50f), Vec2(100f, 23f))
        assertEquals(10f, bounds(t).top, 0f)
    }

    @Test
    fun largeLayersAreMeasuredInTheBackground() {
        val c = setup(1000, 600, layers = 2) // above the synchronous scan size
        fill(c.doc.layers[0].bitmap, Rect(700, 300, 800, 400), BLUE)
        c.doc.layers[0].markChanged()
        fill(c.activeLayer.bitmap, Rect(20, 20, 60, 40), RED)
        c.activeLayer.markChanged()
        c.selectTool(ToolId.TRANSFORM)
        val t = tool(c)
        val deadline = System.currentTimeMillis() + 10_000
        while ((!t.hasPendingWork || t.isFindingSnapTargets) && System.currentTimeMillis() < deadline) {
            idle()
            Thread.sleep(5)
        }
        assertTrue(t.hasPendingWork)
        assertFalse(t.isFindingSnapTargets)
        // Raw left 704: snaps to the other layer's left edge (700).
        drag(c, Vec2(40f, 30f), Vec2(724f, 230f))
        assertEquals(700f, bounds(t).left, 0f)
    }

    // ================================================================== reference point

    @Test
    fun numbersScaleFromTheChosenReferencePoint() {
        val c = setup(200, 200)
        fill(c.activeLayer.bitmap, Rect(40, 40, 120, 80), RED)
        val t = activate(c)
        assertEquals(TransformAnchor.CENTER, t.anchor) // the default
        t.setScalePercent(50.0)
        t.endNumericEdit()
        assertEquals(DocBox(60f, 50f, 100f, 70f), bounds(t))
        // X / Y are the reference point's position.
        assertEquals(Vec2(80f, 60f), t.anchorPosition)
        t.setAnchorPosition(x = 100.0)
        assertEquals(Vec2(100f, 60f), t.anchorPosition)

        t.anchor = TransformAnchor.TOP_LEFT
        t.setSize(width = 80.0)
        t.endNumericEdit()
        assertEquals(DocBox(80f, 50f, 160f, 90f), bounds(t)) // keep-aspect is on

        // A slider going 10°, 20° and back to 0° around a corner returns to the same place.
        val before = t.transformState!!
        for (deg in listOf(10.0, 20.0, 0.0)) t.setRotation(deg)
        t.endNumericEdit()
        assertTrue(t.transformState!!.sameGeometry(before))
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
        const val BLUE = 0xFF0000FF.toInt()
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}
