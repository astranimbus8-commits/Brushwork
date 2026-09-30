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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** Transform tool against the real controller, undo stack and Skia (Robolectric NATIVE graphics). */
@RunWith(RobolectricTestRunner::class)
class TransformToolRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private fun setup(w: Int, h: Int): Pair<EditorController, Layer> {
        val app = RuntimeEnvironment.getApplication()
        val doc = Document("t", "t", w, h)
        val layer = Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(w, h))
        doc.layers += layer
        return EditorController(app, doc, scope, AppSettings(app)) to layer
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun transformTool(c: EditorController) = c.tools.getValue(ToolId.TRANSFORM) as TransformTool

    /** Selects the tool and lets the deferred activation lift the content. */
    private fun activate(c: EditorController): TransformTool {
        c.selectTool(ToolId.TRANSFORM)
        idle()
        return transformTool(c)
    }

    private fun fill(b: Bitmap, r: Rect, color: Int) = Canvas(b).drawRect(r, Paint().apply { this.color = color })

    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun rectSelection(w: Int, h: Int, r: Rect): Selection =
        Selection.fromPath(Path().apply { addRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), Path.Direction.CW) }, w, h, antiAlias = false)

    @Test
    fun dragMovesPixelsAndUndoRestores() {
        val (c, layer) = setup(128, 128)
        fill(layer.bitmap, Rect(20, 20, 60, 60), RED)
        c.viewTransform.set(Matrix().apply { setScale(2f, 2f) })
        val tool = activate(c)
        assertTrue(tool.hasPendingWork)
        assertEquals(DocBox(20f, 20f, 60f, 60f), tool.transformState!!.bounds())
        assertTrue(c.renderOverride != null)

        c.pointerDown(ToolPoint(40f, 40f))
        c.pointerMove(ToolPoint(55f, 45f))
        c.pointerUp(ToolPoint(60f, 45f))
        // Preview goes through the compositor while the layer itself is untouched.
        val preview = BitmapUtils.createLayerBitmap(128, 128)
        c.compositor.drawDocument(Canvas(preview), null)
        assertEquals(RED, preview.getPixel(70, 50))
        assertEquals(0, preview.getPixel(25, 25))
        assertEquals(RED, layer.bitmap.getPixel(25, 25))

        tool.commit()
        assertFalse(tool.hasPendingWork)
        assertNull(c.renderOverride)
        val b = layer.bitmap
        assertEquals(RED, b.getPixel(40, 25))
        assertEquals(RED, b.getPixel(79, 64))
        assertEquals(0, b.getPixel(39, 25))
        assertEquals(0, b.getPixel(80, 64))
        assertEquals(0, b.getPixel(20, 20))
        assertEquals(TransformTool.TRANSFORM_LABEL, c.undoManager.undoLabel)

        c.undo()
        assertEquals(RED, b.getPixel(20, 20))
        assertEquals(RED, b.getPixel(59, 59))
        assertEquals(0, b.getPixel(79, 64))
        assertFalse(c.canUndo)
    }

    @Test
    fun selectionMovesWithContentInOneUndoStep() {
        val (c, layer) = setup(128, 128)
        fill(layer.bitmap, Rect(10, 10, 50, 50), RED)
        c.setSelection(rectSelection(128, 128, Rect(20, 20, 40, 40)), recordUndo = false)
        val tool = activate(c)
        assertEquals(DocBox(20f, 20f, 40f, 40f), tool.transformState!!.bounds())
        tool.moveBy(40f, 0f)
        tool.commit()

        val b = layer.bitmap
        assertEquals(0, b.getPixel(25, 25))      // lifted area cleared
        assertEquals(RED, b.getPixel(15, 15))    // unselected content stays
        assertEquals(RED, b.getPixel(45, 25))
        assertEquals(RED, b.getPixel(61, 25))    // moved block (60..80, 20..40)
        assertEquals(RED, b.getPixel(79, 39))
        assertEquals(0, b.getPixel(81, 25))
        assertEquals(Rect(60, 20, 80, 40), c.selection!!.bounds)

        c.undo()
        assertEquals(Rect(20, 20, 40, 40), c.selection!!.bounds)
        assertEquals(RED, b.getPixel(25, 25))
        assertEquals(0, b.getPixel(65, 25))
        assertFalse(c.canUndo) // pixels + selection were one step

        c.redo()
        assertEquals(Rect(60, 20, 80, 40), c.selection!!.bounds)
        assertEquals(RED, b.getPixel(65, 25))
    }

    @Test
    fun discardAndGestureCancelLeaveNoTrace() {
        val (c, layer) = setup(64, 64)
        fill(layer.bitmap, Rect(8, 8, 30, 20), BLUE)
        val before = pixels(layer.bitmap)
        c.viewTransform.set(Matrix().apply { setScale(3f, 3f) })
        val tool = activate(c)
        val start = tool.transformState

        c.pointerDown(ToolPoint(19f, 14f))
        c.pointerMove(ToolPoint(40f, 30f))
        c.pointerCancel()
        assertEquals(start, tool.transformState)

        tool.moveBy(10f, 10f)
        tool.rotate90(clockwise = true)
        tool.discard()
        assertFalse(tool.hasPendingWork)
        assertNull(c.renderOverride)
        assertTrue(before.contentEquals(pixels(layer.bitmap)))
        assertFalse(c.canUndo)
    }

    @Test
    fun unchangedCommitRecordsNothing() {
        val (c, layer) = setup(32, 32)
        fill(layer.bitmap, Rect(4, 4, 10, 10), RED)
        val tool = activate(c)
        tool.moveBy(3f, 0f)
        tool.moveBy(-3f, 0f)
        tool.commit()
        assertFalse(c.canUndo)
        assertEquals(RED, layer.bitmap.getPixel(4, 4))
    }

    @Test
    fun switchingToolCommits() {
        val (c, layer) = setup(64, 64)
        fill(layer.bitmap, Rect(0, 0, 10, 10), RED)
        val tool = activate(c)
        tool.moveBy(20f, 0f)
        c.selectTool(ToolId.BRUSH)
        assertFalse(tool.hasPendingWork)
        assertEquals(RED, layer.bitmap.getPixel(25, 5))
        assertEquals(0, layer.bitmap.getPixel(5, 5))
        assertEquals(TransformTool.TRANSFORM_LABEL, c.undoManager.undoLabel)
    }

    @Test
    fun quarterTurnIsPixelExact() {
        for (mode in TransformTool.Interpolation.entries) {
            val (c, layer) = setup(48, 48)
            val colors = intArrayOf(RED, GREEN, BLUE, CYAN, MAGENTA, YELLOW)
            // 3x2 block at (20, 20): R G B / C M Y
            for (i in 0 until 6) layer.bitmap.setPixel(20 + i % 3, 20 + i / 3, colors[i])
            val tool = activate(c)
            tool.interpolation = mode
            tool.rotate90(clockwise = true)
            tool.commit()
            val b = layer.bitmap
            val expected = mapOf(
                (21 to 20) to CYAN, (22 to 20) to RED,
                (21 to 21) to MAGENTA, (22 to 21) to GREEN,
                (21 to 22) to YELLOW, (22 to 22) to BLUE,
            )
            for ((p, col) in expected) assertEquals("$mode at $p", col, b.getPixel(p.first, p.second))
            assertEquals("$mode", 0, b.getPixel(20, 20))
            assertEquals("$mode", 0, b.getPixel(23, 20))
            assertEquals("$mode", 0, b.getPixel(21, 23))
        }
    }

    @Test
    fun distortCornerDragMakesPerspective() {
        val (c, layer) = setup(80, 80)
        fill(layer.bitmap, Rect(20, 20, 60, 60), RED)
        c.viewTransform.set(Matrix().apply { setScale(2f, 2f) })
        val tool = activate(c)
        tool.mode = TransformTool.Mode.DISTORT
        c.pointerDown(ToolPoint(20f, 20f))
        c.pointerMove(ToolPoint(10f, 5f))
        c.pointerUp(ToolPoint(10f, 5f))
        val st = tool.transformState!!
        assertTrue(st.isDistorted)
        assertEquals(10f, st.corner(0).x, 1e-3f)
        assertEquals(5f, st.corner(0).y, 1e-3f)
        assertEquals(60f, st.corner(2).x, 1e-3f)
        assertEquals(0, layer.bitmap.getPixel(14, 10))
        tool.commit()
        assertEquals(RED, layer.bitmap.getPixel(14, 10))
        assertEquals(RED, layer.bitmap.getPixel(55, 55))
        assertEquals(0, layer.bitmap.getPixel(12, 55)) // left of the new left edge (20,60)-(10,5)
    }

    @Test
    fun placementFitsAndCommitsAsImport() {
        val (c, _) = setup(100, 100)
        val img = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(BLUE) }
        c.importImageAsLayer(img)
        idle()
        val tool = transformTool(c)
        assertEquals(ToolId.TRANSFORM, c.activeToolId)
        assertTrue(tool.hasPendingWork)
        assertTrue(tool.isPlacement)
        assertEquals(DocBox(5f, 28f, 95f, 73f), tool.transformState!!.bounds())
        assertEquals(2, c.doc.layers.size)
        val placed = c.doc.activeLayer
        tool.commit()
        assertFalse(tool.isPlacement)
        assertEquals(BLUE, placed.bitmap.getPixel(50, 50))
        assertEquals(BLUE, placed.bitmap.getPixel(6, 29))
        assertEquals(0, placed.bitmap.getPixel(2, 50))
        assertEquals(0, placed.bitmap.getPixel(50, 20))
        assertEquals(TransformTool.IMPORT_LABEL, c.undoManager.undoLabel)
        // Adding the layer and placing the picture are ONE undo step.
        c.undo()
        assertEquals(1, c.doc.layers.size)
        assertFalse(c.canUndo)
        c.redo()
        assertEquals(2, c.doc.layers.size)
        assertEquals(BLUE, c.doc.activeLayer.bitmap.getPixel(50, 50))
    }

    @Test
    fun discardingPlacementRemovesTheLayer() {
        val (c, base) = setup(100, 100)
        val img = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(BLUE) }
        c.importImageAsLayer(img)
        idle()
        val tool = transformTool(c)
        assertEquals(2, c.doc.layers.size)
        tool.discard()
        idle()
        assertFalse(tool.hasPendingWork)
        assertEquals(listOf(base), c.doc.layers.toList())
        assertFalse(c.canUndo)

        // Undo (two-finger tap) while placing does the same.
        c.importImageAsLayer(img)
        idle()
        assertEquals(2, c.doc.layers.size)
        c.undo()
        idle()
        assertFalse(tool.hasPendingWork)
        assertEquals(listOf(base), c.doc.layers.toList())
    }

    @Test
    fun deletingLayersDuringPlacementIsSafe() {
        val (c, base) = setup(50, 50)
        val img = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(BLUE) }
        c.importImageAsLayer(img)
        idle()
        val placed = c.doc.activeLayer
        // deleteLayer() calls discard() and then removes by a precomputed index.
        c.deleteLayer(placed)
        idle()
        assertFalse(transformTool(c).hasPendingWork)
        assertEquals(listOf(base), c.doc.layers.toList())

        val (c2, base2) = setup(50, 50)
        c2.importImageAsLayer(img)
        idle()
        val placed2 = c2.doc.activeLayer
        c2.deleteLayer(base2)
        idle()
        assertEquals(listOf(placed2), c2.doc.layers.toList()) // the other layer went, nothing else
    }

    @Test
    fun strongDownscaleAveragesInsteadOfAliasing() {
        val (c, _) = setup(100, 100)
        // 1-px black/white checkerboard: a plain bilinear 9 % shrink would pick near-pure pixels.
        val n = 1000
        val px = IntArray(n * n) { i -> if ((i % n + i / n) % 2 == 0) BLACK else WHITE }
        val img = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888).apply { setPixels(px, 0, n, 0, 0, n, n) }
        c.importImageAsLayer(img)
        idle()
        val tool = transformTool(c)
        assertEquals(3, tool.transformState!!.minificationLevel())
        val placed = c.doc.activeLayer
        tool.commit()
        for ((x, y) in listOf(50 to 50, 30 to 40, 61 to 57)) {
            val v = placed.bitmap.getPixel(x, y) and 0xFF
            assertTrue("gray expected at ($x,$y), got $v", v in 96..160)
        }
    }

    @Test
    fun maskEditingTransformsTheMask() {
        val (c, layer) = setup(64, 64)
        layer.bitmap.eraseColor(RED)
        val mask = BitmapUtils.createMaskBitmap(64, 64)
        fill(mask, Rect(10, 10, 20, 20), BLACK)
        layer.mask = mask
        layer.editingMask = true
        val tool = activate(c)
        assertEquals(DocBox(10f, 10f, 20f, 20f), tool.transformState!!.bounds())
        tool.moveBy(30f, 0f)

        // Preview composes the moved mask (saveLayer + mask paint).
        val preview = BitmapUtils.createLayerBitmap(64, 64)
        c.compositor.drawDocument(Canvas(preview), null)
        assertEquals(RED, preview.getPixel(15, 15))
        assertEquals(0, preview.getPixel(45, 15))

        tool.commit()
        assertEquals(WHITE, mask.getPixel(15, 15))
        assertEquals(BLACK, mask.getPixel(45, 15))
        assertEquals(RED, layer.bitmap.getPixel(45, 15)) // content untouched
        c.undo()
        assertEquals(BLACK, mask.getPixel(15, 15))
        assertEquals(WHITE, mask.getPixel(45, 15))
    }

    @Test
    fun refusesAlphaLockedEmptyLockedAndHiddenLayers() {
        val (c, layer) = setup(32, 32)
        val tool = activate(c)
        assertFalse(tool.hasPendingWork)
        assertEquals("Nothing to transform on this layer", c.message)

        fill(layer.bitmap, Rect(0, 0, 5, 5), RED)
        layer.alphaLocked = true
        c.message = null
        tool.start()
        assertFalse(tool.hasPendingWork)
        assertTrue(c.message!!.contains("Transparency is locked"))

        layer.alphaLocked = false
        layer.locked = true
        c.message = null
        c.pointerDown(ToolPoint(2f, 2f))
        c.pointerUp(ToolPoint(2f, 2f))
        assertFalse(tool.hasPendingWork)
        assertTrue(c.message!!.contains("locked"))

        layer.locked = false
        layer.visible = false
        c.message = null
        tool.start()
        assertFalse(tool.hasPendingWork)
        assertTrue(c.message!!.contains("hidden"))
    }

    @Test
    fun largeLayerBoundsAreFoundInTheBackground() {
        val (c, layer) = setup(2000, 1100) // above the synchronous scan limit
        fill(layer.bitmap, Rect(1500, 700, 1510, 720), RED)
        c.selectTool(ToolId.TRANSFORM)
        val tool = transformTool(c)
        val deadline = System.currentTimeMillis() + 10_000
        while (!tool.hasPendingWork && System.currentTimeMillis() < deadline) {
            idle()
            Thread.sleep(5)
        }
        assertTrue(tool.hasPendingWork)
        assertFalse(tool.isPreparing)
        assertEquals(DocBox(1500f, 700f, 1510f, 720f), tool.transformState!!.bounds())
        tool.fitToCanvas()
        assertNotEquals(DocBox(1500f, 700f, 1510f, 720f), tool.transformState!!.bounds())
        tool.reset()
        assertEquals(DocBox(1500f, 700f, 1510f, 720f), tool.transformState!!.bounds())
    }

    @Test
    fun selectingDuringTransformAppliesItAndLiftsTheSelection() {
        val (c, layer) = setup(64, 64)
        fill(layer.bitmap, Rect(0, 0, 10, 10), RED)
        val tool = activate(c)
        tool.moveBy(20f, 0f)
        // "Select all"-like menu action while the whole-layer transform is pending.
        c.setSelection(rectSelection(64, 64, Rect(16, 0, 40, 20)), label = "Select")
        assertEquals(RED, layer.bitmap.getPixel(25, 5))   // the move was applied
        assertEquals(0, layer.bitmap.getPixel(5, 5))
        assertEquals(Rect(16, 0, 40, 20), c.selection!!.bounds) // the user's selection is kept as set
        assertTrue(tool.hasPendingWork)                   // lifted again, now with the selection
        assertEquals(DocBox(16f, 0f, 40f, 20f), tool.transformState!!.bounds())

        tool.discard() // an unchanged re-lift leaves nothing behind
        assertEquals(TransformTool.TRANSFORM_LABEL, c.undoManager.undoLabel)
        c.undo()
        assertEquals(RED, layer.bitmap.getPixel(5, 5))
        assertEquals("Select", c.undoManager.undoLabel)
    }

    @Test
    fun deselectDuringSelectionTransformCommitsAtTheNewPlace() {
        val (c, layer) = setup(64, 64)
        fill(layer.bitmap, Rect(0, 0, 40, 40), RED)
        c.setSelection(rectSelection(64, 64, Rect(0, 0, 10, 10)), recordUndo = false)
        val tool = activate(c)
        tool.moveBy(50f, 50f)
        c.deselect()
        assertNull(c.selection)
        val b = layer.bitmap
        assertEquals(RED, b.getPixel(55, 55))
        assertEquals(0, b.getPixel(5, 5))
        assertEquals(RED, b.getPixel(20, 20))
        // Re-lifted without a selection: the whole content (still pending, unchanged).
        assertEquals(DocBox(0f, 0f, 60f, 60f), tool.transformState!!.bounds())
        tool.commit()
        assertEquals(TransformTool.TRANSFORM_LABEL, c.undoManager.undoLabel)
    }

    @Test
    fun ownSelectionMoveDoesNotRetrigger() {
        val (c, layer) = setup(64, 64)
        fill(layer.bitmap, Rect(0, 0, 20, 20), RED)
        c.setSelection(rectSelection(64, 64, Rect(0, 0, 10, 10)), recordUndo = false)
        val tool = activate(c)
        tool.moveBy(30f, 0f)
        tool.commit()
        assertFalse(tool.hasPendingWork) // no re-lift from its own setSelection
        assertEquals(Rect(30, 0, 40, 10), c.selection!!.bounds)
    }

    @Test
    fun movedSelectionBoundsAreTight() {
        val (c, layer) = setup(100, 100)
        fill(layer.bitmap, Rect(0, 0, 100, 100), RED)
        c.setSelection(rectSelection(100, 100, Rect(30, 30, 60, 50)), recordUndo = false)
        val tool = activate(c)
        tool.setRotation(30.0)
        tool.moveBy(7f, -3f)
        tool.commit()
        val sel = c.selection!!
        assertEquals(Selection.computeBounds(sel.mask), sel.bounds)

        // Moved completely off the canvas: nothing stays selected.
        val t2 = transformTool(c)
        t2.start()
        t2.moveBy(500f, 0f)
        t2.commit()
        assertNull(c.selection)
    }

    @Test
    fun contentBoundsScanRegionOfAlpha8() {
        val m = Bitmap.createBitmap(50, 40, Bitmap.Config.ALPHA_8)
        Canvas(m).drawRect(Rect(12, 7, 20, 30), Paint().apply { color = BLACK })
        Canvas(m).drawRect(Rect(40, 35, 45, 38), Paint().apply { color = BLACK })
        assertEquals(Rect(12, 7, 45, 38), ContentBounds.of(m))
        assertEquals(Rect(12, 7, 20, 30), ContentBounds.of(m, region = Rect(0, 0, 30, 40)))
        assertEquals(Rect(14, 10, 20, 30), ContentBounds.of(m, region = Rect(14, 10, 25, 33)))
        assertNull(ContentBounds.of(m, region = Rect(21, 0, 39, 40)))
        assertNull(ContentBounds.of(m, region = Rect(60, 60, 70, 70)))
    }

    @Test
    fun nonFiniteNumbersAreIgnored() {
        val (c, layer) = setup(32, 32)
        fill(layer.bitmap, Rect(4, 4, 12, 12), RED)
        val tool = activate(c)
        val start = tool.transformState
        tool.setRotation(Double.NaN)
        tool.setSize(width = Double.NaN)
        tool.setSize(height = Double.POSITIVE_INFINITY)
        tool.setPosition(left = Double.NaN, top = Double.NEGATIVE_INFINITY)
        tool.setScalePercent(Double.NaN)
        tool.moveBy(Float.NaN, 1f)
        tool.nudgeStepPx = Double.NaN
        tool.nudgeStepPx = -3.0
        assertEquals(1.0, tool.nudgeStepPx, 0.0)
        assertEquals(start, tool.transformState)
        // Valid numbers still work, and the nudge step moves by exact amounts.
        tool.nudgeStepPx = 3.0
        tool.nudge(-1, 0)
        assertEquals(DocBox(1f, 4f, 9f, 12f), tool.transformState!!.bounds())
    }

    @Test
    fun menuFillCommitsThePendingTransformFirst() {
        val (c, layer) = setup(64, 64)
        fill(layer.bitmap, Rect(0, 0, 10, 10), RED)
        val tool = activate(c)
        tool.moveBy(30f, 30f)
        // The controller commits pending tool work before a menu Fill, so the moved pixels are
        // baked in and then filled over; nothing is left floating.
        c.fillLayer(layer, BLUE)
        assertFalse(tool.hasPendingWork)
        assertEquals(BLUE, layer.bitmap.getPixel(50, 5))
        assertEquals(BLUE, layer.bitmap.getPixel(5, 5))
        assertEquals(BLUE, layer.bitmap.getPixel(35, 35))
        val preview = BitmapUtils.createLayerBitmap(64, 64)
        c.compositor.drawDocument(Canvas(preview), null)
        assertTrue(pixels(preview).contentEquals(pixels(layer.bitmap)))
        // Two undo steps: fill, then the transform.
        c.undo()
        assertEquals(RED, layer.bitmap.getPixel(35, 35))
        assertEquals(0, layer.bitmap.getPixel(5, 5))
    }

    @Test
    fun lockedMeanwhileCancelsInsteadOfWriting() {
        val (c, layer) = setup(32, 32)
        fill(layer.bitmap, Rect(0, 0, 8, 8), RED)
        val before = pixels(layer.bitmap)
        val tool = activate(c)
        tool.moveBy(10f, 10f)
        layer.locked = true
        tool.commit()
        assertFalse(tool.hasPendingWork)
        assertNull(c.renderOverride)
        assertTrue(before.contentEquals(pixels(layer.bitmap)))
        assertFalse(c.canUndo)
        assertTrue(c.message!!.contains("locked"))
    }

    @Test
    fun replacedBitmapEndsTheSessionSafely() {
        val (c, layer) = setup(32, 32)
        fill(layer.bitmap, Rect(0, 0, 8, 8), RED)
        val tool = activate(c)
        // E.g. another module replaced the layer bitmap without deactivating the tool.
        layer.bitmap = BitmapUtils.createLayerBitmap(32, 32)
        tool.moveBy(5f, 5f)
        assertFalse(tool.hasPendingWork)
        assertNull(c.renderOverride)
        tool.commit()
        assertFalse(c.canUndo)
    }

    @Test
    fun unusablePictureRemovesTheImportLayer() {
        val (c, base) = setup(40, 40)
        val img = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888).apply { recycle() }
        c.importImageAsLayer(img)
        idle()
        val tool = transformTool(c)
        assertFalse(tool.hasPendingWork)
        assertEquals(listOf(base), c.doc.layers.toList())
        assertFalse(c.canUndo)
        assertEquals("The picture could not be placed", c.message)
    }

    @Test
    fun placementIgnoresSelectionChanges() {
        val (c, _) = setup(60, 60)
        val img = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(BLUE) }
        c.importImageAsLayer(img)
        idle()
        val tool = transformTool(c)
        val st = tool.transformState
        c.selectAll()
        assertTrue(tool.isPlacement)
        assertEquals(st, tool.transformState)
        tool.commit()
        assertEquals(BLUE, c.doc.activeLayer.bitmap.getPixel(30, 30))
    }

    @Test
    fun discardingPlacementAfterNonUndoableChangesStillUndoesTheAdd() {
        val (c, base) = setup(50, 50)
        val img = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(BLUE) }
        c.importImageAsLayer(img)
        idle()
        c.updateGrid(c.grid.copy(enabled = !c.grid.enabled)) // counts as an edit, but no undo step
        transformTool(c).discard()
        idle()
        assertEquals(listOf(base), c.doc.layers.toList())
        assertFalse(c.canUndo)

        // A menu fill during placement commits the placement first (the picture stays).
        c.importImageAsLayer(img)
        idle()
        val placed = c.doc.activeLayer
        c.fillLayer(placed, RED)
        assertFalse(transformTool(c).hasPendingWork)
        assertEquals(listOf(base, placed), c.doc.layers.toList())
        assertEquals("Fill", c.undoManager.undoLabel)
    }

    @Test
    fun movedSelectionOutlineIsDrawnAtTheNewPlace() {
        val (c, layer) = setup(100, 100)
        fill(layer.bitmap, Rect(0, 0, 100, 100), RED)
        val sel = rectSelection(100, 100, Rect(10, 10, 50, 50))
        // Stand-in for the asynchronously computed marching-ants outline (away from the handles).
        sel.outline = Path().apply { addRect(20f, 20f, 40f, 40f, Path.Direction.CW) }
        c.setSelection(sel, recordUndo = false)
        val tool = activate(c)
        val overlay = BitmapUtils.createLayerBitmap(100, 100)
        tool.drawOverlay(Canvas(overlay), c.viewTransform)
        assertEquals(0, overlay.getPixel(20, 30)) // unchanged transform: the ants already show it
        tool.moveBy(20f, 0f)
        overlay.eraseColor(0)
        tool.drawOverlay(Canvas(overlay), c.viewTransform)
        assertTrue(overlay.getPixel(40, 30) ushr 24 != 0)
        assertEquals(0, overlay.getPixel(20, 30))
    }

    @Test
    fun statusTextExplainsRefusals() {
        val (c, layer) = setup(16, 16)
        val tool = transformTool(c)
        assertEquals("Touch the canvas to transform the layer", tool.statusText)
        layer.alphaLocked = true
        assertEquals("Transparency is locked on this layer", tool.statusText)
        layer.visible = false
        assertEquals("The layer is hidden", tool.statusText)
        layer.locked = true
        assertEquals("The layer is locked", tool.statusText)
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
        const val GREEN = 0xFF00FF00.toInt()
        const val BLUE = 0xFF0000FF.toInt()
        const val CYAN = 0xFF00FFFF.toInt()
        const val MAGENTA = 0xFFFF00FF.toInt()
        const val YELLOW = 0xFFFFFF00.toInt()
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}
