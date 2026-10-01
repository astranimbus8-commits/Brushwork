package com.brushwork.paint

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.CanvasOps
import com.brushwork.paint.engine.CanvasSnapshot
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.masks.AdjustmentSpec
import com.brushwork.paint.masks.MaskSpec
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.model.Selection
import com.brushwork.paint.tools.LayerToolRules
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.ui.layers.LayerOps
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VectorContent
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.5 foundation (§5.10 items 2, 6, 7): the LayerData rules of every pixel path (I1), the
 * Vector button, the pointer-down gate, adjustment-layer rules and editable-mask helpers.
 */
@RunWith(RobolectricTestRunner::class)
class FoundationControllerRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()
    private val w = 120
    private val h = 90

    private fun setup(layers: Int = 2): EditorController {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", w, h)
        repeat(layers) { i -> doc.layers += Layer(doc.newLayerId(), "Layer ${i + 1}", BitmapUtils.createLayerBitmap(w, h)) }
        doc.activeLayerIndex = layers - 1
        return EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
    }

    /** What a new canvas looks like: a white Background and an empty "Layer 1" (active). */
    private fun newCanvas(): EditorController {
        val c = setup(2)
        c.doc.layers[0].name = "Background"
        c.doc.layers[0].bitmap.eraseColor(-1)
        c.doc.layers[1].name = "Layer 1"
        return c
    }

    private fun paint(layer: Layer, r: Rect = Rect(10, 10, 40, 30), color: Int = 0xFFCC2200.toInt()) {
        Canvas(layer.bitmap).drawRect(r, Paint().apply { this.color = color })
        layer.markChanged()
    }

    private fun pixels(layer: Layer): IntArray = IntArray(w * h).also { layer.bitmap.getPixels(it, 0, w, 0, 0, w, h) }

    private fun stroke(id: Long = 0) = VStroke(id, preset = BrushLibrary.defaultBrush, color = -16777216, seed = 1, stylus = false,
        points = PackedPoints(floatArrayOf(1f, 20f), floatArrayOf(1f, 20f), floatArrayOf(1f, 1f)))

    private fun content(n: Int = 2) = VectorContent.EMPTY.plus(List(n) { stroke() }).first

    // ------------------------------------------------------------------ rasterize rules (I1)

    @Test
    fun contentEditClearsContentDataAndKeepsMaskSpecAndAdjustment() {
        val c = setup()
        val l = c.activeLayer
        l.mask = BitmapUtils.createMaskBitmap(w, h)
        val before = LayerData(text = "t", vector = content(), maskSpec = MaskSpec(startFull = true), adjustment = AdjustmentSpec())
        l.restoreData(before)
        val rec = c.beginEdit(l, EditTarget.CONTENT)
        rec.touch(Rect(0, 0, 10, 10))
        Canvas(l.bitmap).drawRect(0f, 0f, 10f, 10f, Paint().apply { color = -16777216 })
        assertTrue(c.commitEdit(rec, "Paint"))
        assertEquals(before.rasterizedContent(), l.dataSnapshot())
        assertTrue(c.message!!.contains("can no longer be edited"))
        assertEquals("one step", 1, c.undoManager.undoCount)
        c.undo()
        assertEquals(before, l.dataSnapshot())
        c.redo()
        assertEquals(before.rasterizedContent(), l.dataSnapshot())
    }

    @Test
    fun maskEditClearsOnlyTheMaskSpec() {
        val c = setup()
        val l = c.activeLayer
        l.mask = BitmapUtils.createMaskBitmap(w, h)
        val before = LayerData(vector = content(), maskSpec = MaskSpec(startFull = true))
        l.restoreData(before)
        val rec = c.beginEdit(l, EditTarget.MASK)
        rec.touch(Rect(0, 0, 10, 10))
        Canvas(l.mask!!).drawRect(0f, 0f, 10f, 10f, Paint().apply { color = -16777216 })
        assertTrue(c.commitEdit(rec, "Paint mask"))
        assertEquals(before.rasterizedMask(), l.dataSnapshot())
        assertTrue(c.message!!.contains("painted mask"))
        c.undo()
        assertEquals(before, l.dataSnapshot())
    }

    @Test
    fun preservedAndKeptDataSurvivePixelEdits() {
        val c = setup()
        val l = c.activeLayer
        l.vector = content()
        val rec = c.beginEdit(l, EditTarget.CONTENT).also { it.preserveData = true }
        rec.touch(Rect(0, 0, 4, 4))
        c.commitEdit(rec, "Re-render")
        assertNotNull(l.vector)
        c.keepLayerData(l) {
            val r2 = c.beginEdit(l, EditTarget.CONTENT)
            r2.touch(Rect(0, 0, 4, 4))
            c.commitEdit(r2, "Brush")
        }
        assertNotNull("keepLayerData keeps the vector content", l.vector)
    }

    @Test
    fun layerDataActionBumpsContentVersionAndRestores() {
        val c = setup()
        val l = c.activeLayer
        val v0 = l.contentVersion
        val after = l.dataSnapshot().copy(vector = content(3))
        c.setLayerData(l, after, "Add objects")
        assertTrue(l.contentVersion > v0)
        assertEquals(after, l.dataSnapshot())
        assertEquals("Add objects", c.undoManager.undoLabel)
        val v1 = l.contentVersion
        c.undo()
        assertTrue("undo marks the layer changed (saving picks it up)", l.contentVersion > v1)
        assertNull(l.vector)
        c.setLayerData(l, l.dataSnapshot(), "Nothing")
        assertEquals("an unchanged data set records nothing", 0, c.undoManager.undoCount)
    }

    @Test
    fun updateLayerDataIsOneStepOfPixelsAndData() {
        val c = setup()
        val l = c.activeLayer
        val p0 = pixels(l)
        val after = LayerData(vector = content(1))
        assertTrue(c.updateLayerData(l, after, "Edit path", Rect(0, 0, 50, 50)) { cv -> cv.drawRect(5f, 5f, 20f, 20f, Paint().apply { color = 0xFF00AA00.toInt() }) })
        assertEquals(1, c.undoManager.undoCount)
        assertEquals("Edit path", c.undoManager.undoLabel)
        assertSame(after.vector, l.vector)
        assertEquals(0xFF00AA00.toInt(), l.bitmap.getPixel(10, 10))
        c.undo()
        assertArrayEquals(p0, pixels(l))
        assertNull(l.vector)
        c.redo()
        assertSame(after.vector, l.vector)
        assertEquals(0xFF00AA00.toInt(), l.bitmap.getPixel(10, 10))
        // Text and shape updates are wrappers with the same behaviour.
        assertTrue(c.updateTextLayer(l, "{}", "Edit text") { })
        assertEquals("{}", l.textData)
        assertSame("the rest of the data is kept", after.vector, l.vector)
        // Locked layers refuse; mask-less adjustment layers accept (A5 stores specs on them).
        l.locked = true
        assertFalse(c.updateLayerData(l, LayerData.NONE, "x", null, draw = null))
        l.locked = false
        l.adjustment = AdjustmentSpec()
        assertTrue(c.updateLayerData(l, l.dataSnapshot().copy(adjustment = AdjustmentSpec(filterId = "adjust.invert")), "Edit adjustment", null, draw = null))
        assertEquals("adjust.invert", l.adjustment!!.filterId)
    }

    @Test
    fun duplicateMergeAndFlipKeepOrRestoreTheData() {
        val c = setup()
        val lower = c.doc.layers[0]
        val upper = c.doc.layers[1]
        lower.vector = content()
        lower.mask = BitmapUtils.createMaskBitmap(w, h)
        lower.maskSpec = MaskSpec(startFull = true)
        paint(upper)
        // Duplicate: a whole copy shares the (immutable) data.
        val copy = c.duplicateLayer(lower)!!
        assertSame(lower.vector, copy.vector)
        assertEquals(lower.maskSpec, copy.maskSpec)
        c.undo()
        assertEquals(-1, c.doc.indexOf(copy))
        // ... a partial copy keeps the mask spec but is no longer the vector object.
        c.selectLayer(lower)
        c.setSelection(Selection.all(w, h))
        val part = c.duplicateLayer(lower)!!
        assertNull(part.vector)
        assertEquals(lower.maskSpec, part.maskSpec)
        c.undo(); c.undo()
        c.selectLayer(upper)
        // Merge down: the merged layer is plain pixels; undo restores the data.
        val before = lower.dataSnapshot()
        c.mergeDown(upper)
        assertTrue(lower.dataSnapshot().isEmpty)
        c.undo()
        assertEquals(before, lower.dataSnapshot())
        // Flip: what can't be mirrored (foundation stubs) is cleared; undo restores it.
        c.flipLayer(lower, horizontal = true)
        assertNull(lower.vector)
        assertNull(lower.maskSpec)
        assertNotNull("the mask pixels flip along", lower.mask)
        c.undo()
        assertEquals(before, lower.dataSnapshot())
    }

    @Test
    fun canvasOperationsCarryTheDataAndUndoRestoresIt() {
        val c = setup()
        val l = c.doc.layers[0]
        l.mask = BitmapUtils.createMaskBitmap(w, h)
        val before = LayerData(vector = content(), maskSpec = MaskSpec(startFull = true), adjustment = null)
        l.restoreData(before)
        val snap = CanvasSnapshot.of(c.doc)
        CanvasOps.commit(c, "Flip canvas", snap, CanvasOps.flip(snap, horizontal = true))
        assertNull("vector content can't be mapped yet (A1): raster", l.vector)
        assertNull("a non-identity map drops the spec when it can't be mapped (A5)", l.maskSpec)
        c.undo()
        assertEquals(before, l.dataSnapshot())
        // A color mode change keeps geometry: the mask spec stays.
        val snap2 = CanvasSnapshot.of(c.doc)
        CanvasOps.commit(c, "Grayscale", snap2, CanvasOps.convertColorMode(snap2, ColorMode.GRAYSCALE))
        assertNull(l.vector)
        assertEquals(before.maskSpec, l.maskSpec)
        c.undo()
        assertEquals(before, l.dataSnapshot())
    }

    // ------------------------------------------------------------------ Vector button (§4.9)

    @Test
    fun aNewCanvasConvertsLayer1InPlace() {
        val c = newCanvas()
        assertFalse(c.isVectorMode)
        c.toggleVectorMode()
        val l1 = c.doc.layers[1]
        assertEquals(2, c.doc.layers.size)
        assertTrue(l1.isVectorLayer)
        assertTrue(c.isVectorMode)
        assertEquals("Vector 1", l1.name)
        assertEquals(1, c.undoManager.undoCount)
        assertEquals("Convert to vector layer", c.undoManager.undoLabel)
        // Back: the remembered layer is a vector layer now, so the nearest raster layer below.
        c.toggleVectorMode()
        assertFalse(c.isVectorMode)
        assertSame(c.doc.layers[0], c.activeLayer)
        assertEquals(1, c.undoManager.undoCount)
        // Undo of the conversion: back to a raster "Layer 1".
        c.selectLayer(l1)
        assertTrue(c.isVectorMode)
        c.undo()
        assertFalse(l1.isVectorLayer)
        assertEquals("Layer 1", l1.name)
        assertFalse(c.isVectorMode)
    }

    @Test
    fun aPaintedLayerAddsAVectorLayerAndComesBack() {
        val c = setup(2)
        val l = c.doc.layers[1]
        paint(l)
        val p0 = pixels(l)
        c.toggleVectorMode()
        assertEquals("a painted layer is not converted", 3, c.doc.layers.size)
        assertFalse(l.isVectorLayer)
        assertArrayEquals(p0, pixels(l))
        val v = c.activeLayer
        assertTrue(v.isVectorLayer)
        assertEquals(2, c.doc.indexOf(v))
        assertEquals("Vector 1", v.name)
        assertEquals("Add vector layer", c.undoManager.undoLabel)
        c.toggleVectorMode()
        assertSame("back to the remembered layer", l, c.activeLayer)
        // Reuse: the visible, unlocked vector layer right above is selected (no step).
        val steps = c.undoManager.undoCount
        c.toggleVectorMode()
        assertSame(v, c.activeLayer)
        assertEquals(steps, c.undoManager.undoCount)
        // Undo of "Add vector layer" drops back to raster mode.
        c.selectLayer(l)
        c.undo()
        assertEquals(2, c.doc.layers.size)
        assertFalse(c.isVectorMode)
    }

    @Test
    fun aLockedOrHiddenVectorLayerAboveIsNotReused() {
        val c = setup(2)
        paint(c.doc.layers[1])
        c.selectLayer(c.doc.layers[0])
        paint(c.doc.layers[0])
        c.doc.layers[1].vector = VectorContent.EMPTY
        c.doc.layers[1].locked = true
        c.toggleVectorMode()
        assertEquals(3, c.doc.layers.size)
        assertSame(c.doc.layers[1], c.activeLayer)
        assertTrue(c.activeLayer.isVectorLayer)
        assertFalse(c.activeLayer.locked)
    }

    @Test
    fun addVectorAndConvertShapeLayers() {
        val c = setup(1)
        val v = c.addVectorLayer()!!
        assertSame(VectorContent.EMPTY, v.vector)
        assertEquals("Add vector layer", c.undoManager.undoLabel)
        val shapeLayer = c.addLayer()!!
        shapeLayer.shapeData = com.brushwork.paint.tools.vector.ShapeCodec.encode(com.brushwork.paint.tools.vector.ShapeObject(cx = 30f, cy = 30f, w = 20f, h = 10f))
        paint(shapeLayer)
        val p0 = pixels(shapeLayer)
        assertTrue(c.convertToVectorLayer(shapeLayer))
        assertNull(shapeLayer.shapeData)
        assertEquals(1, shapeLayer.vector!!.objects.size)
        assertTrue(shapeLayer.vector!!.objects[0] is com.brushwork.paint.vector.VShape)
        assertArrayEquals("the pixels stay (they are the cache)", p0, pixels(shapeLayer))
        c.undo()
        assertNotNull(shapeLayer.shapeData)
        assertNull(shapeLayer.vector)
        val painted = c.addLayer()!!
        paint(painted)
        assertFalse("a painted raster layer can't be converted", c.convertToVectorLayer(painted))
    }

    // ------------------------------------------------------------------ pointer-down gate (§5.10 item 7)

    @Test
    fun smudgeOnAVectorLayerIsRefusedWithoutAStrokeOrAStep() {
        val c = setup()
        val l = c.activeLayer
        paint(l)
        l.vector = VectorContent.EMPTY
        val p0 = pixels(l)
        c.selectTool(ToolId.SMUDGE)
        c.pointerDown(ToolPoint(15f, 15f))
        c.pointerMove(ToolPoint(30f, 20f))
        c.pointerMove(ToolPoint(60f, 40f))
        c.pointerUp(ToolPoint(70f, 50f))
        assertEquals(LayerToolRules.pixelOnlyMessage(ToolId.SMUDGE), c.message)
        assertEquals(0, c.undoManager.undoCount)
        assertFalse(c.isInteracting)
        assertNull(c.renderOverride)
        assertArrayEquals(p0, pixels(l))
        assertNotNull(l.vector)
    }

    @Test
    fun adjustmentLayersRefusePixelToolsUntilTheyHaveAMask() {
        val c = setup()
        val adj = c.addAdjustmentLayer(AdjustmentSpec(), null)!!
        assertEquals("Tone 1", adj.name)
        assertEquals("New adjustment layer", c.undoManager.undoLabel)
        assertSame(adj, c.activeLayer)
        assertEquals(LayerToolRules.ADJUSTMENT_MESSAGE, LayerToolRules.refusal(ToolId.BRUSH, adj))
        assertEquals(LayerToolRules.ADJUSTMENT_MESSAGE, LayerToolRules.refusal(ToolId.FILL, adj))
        assertEquals(LayerToolRules.ADJUSTMENT_MESSAGE, LayerToolRules.refusal(ToolId.SMUDGE, adj))
        assertNull(LayerToolRules.refusal(ToolId.MASK, adj))
        assertNull(LayerToolRules.refusal(ToolId.LASSO, adj))
        assertFalse(c.checkEditable(adj))
        assertEquals(EditTarget.CONTENT, c.editTargetOf(adj))
        c.selectTool(ToolId.BRUSH)
        c.pointerDown(ToolPoint(10f, 10f)); c.pointerUp(ToolPoint(20f, 20f))
        assertEquals(LayerToolRules.ADJUSTMENT_MESSAGE, c.message)
        assertEquals(1, c.undoManager.undoCount)
        // With a mask the brush paints the mask.
        val masked = c.addAdjustmentLayer(AdjustmentSpec(), MaskSpec(startFull = true))!!
        assertEquals("Tone 2", masked.name)
        assertEquals(-1, masked.mask!!.getPixel(5, 5))
        assertEquals(MaskSpec(startFull = true), masked.maskSpec)
        assertEquals(EditTarget.MASK, c.editTargetOf(masked))
        assertTrue(c.checkEditable(masked))
        assertNull(LayerToolRules.refusal(ToolId.BRUSH, masked))
        assertEquals(LayerToolRules.ADJUSTMENT_MESSAGE, LayerToolRules.refusal(ToolId.CLONE, masked))
        // Filters are refused on adjustment layers.
        c.startFilter(com.brushwork.paint.filters.FilterRegistry.byId("adjust.invert")!!)
        assertNull(c.filterSession)
        assertEquals(EditorController.ADJUSTMENT_FILTER_MESSAGE, c.message)
        // Undo removes the layers.
        c.undo(); c.undo()
        assertEquals(2, c.doc.layers.size)
    }

    @Test
    fun adjustmentLayersAreNeverClipped() {
        val c = setup()
        val adj = c.addAdjustmentLayer(AdjustmentSpec(), null)!!
        c.toggleClipping(adj)
        assertFalse(adj.clipping)
        val above = c.addLayer()!!
        c.toggleClipping(above)
        assertFalse("a layer right above an adjustment layer can't clip to it", above.clipping)
        // Unclipping is always allowed.
        above.clipping = true
        c.toggleClipping(above)
        assertFalse(above.clipping)
        // Applying an adjustment layer to the layer below merges it (no "no pixels" dead end).
        val below = c.doc.layers[1]
        paint(below)
        c.selectLayer(adj)
        LayerOps.mergeDown(c, adj)
        assertEquals(-1, c.doc.indexOf(adj))
        assertTrue(below.dataSnapshot().isEmpty)
    }

    @Test
    fun maskToSelectionAndPixelMask() {
        val c = setup()
        val l = c.activeLayer
        l.mask = BitmapUtils.createMaskBitmap(w, h, 0xFF000000.toInt())
        Canvas(l.mask!!).drawRect(0f, 0f, 60f, 90f, Paint().apply { color = -1 })
        l.maskSpec = MaskSpec()
        c.selectionFromMask(l)
        assertEquals(Rect(0, 0, 60, 90), c.selection!!.bounds)
        assertEquals("Mask to selection", c.undoManager.undoLabel)
        com.brushwork.paint.masks.MaskLayerOps.toPixelMask(c, l)
        assertNull(l.maskSpec)
        assertNotNull(l.mask)
        assertEquals("Convert to pixel mask", c.undoManager.undoLabel)
        c.undo()
        assertEquals(MaskSpec(), l.maskSpec)
    }

    @Test
    fun clonePresetsAreKeptApartFromTheBrush() {
        val c = setup()
        assertEquals(BrushLibrary.defaultClone, c.presetFor(ToolId.CLONE))
        assertEquals(BrushLibrary.defaultMaskBrush, c.presetFor(ToolId.MASK))
        c.updatePreset(ToolId.CLONE, BrushLibrary.defaultClone.copy(size = 33f))
        assertEquals(33f, c.cloneBrush.size, 0f)
        assertEquals(BrushLibrary.defaultBrush, c.presetFor(ToolId.BRUSH))
        assertTrue(ToolId.CLONE in EditorController.PAINT_TOOLS && ToolId.MASK in EditorController.PAINT_TOOLS)
        c.selectTool(ToolId.CLONE)
        assertEquals("CLONE never becomes the last paint tool", ToolId.BRUSH, c.lastPaintTool)
        assertEquals(ToolId.CLONE, c.sliderToolId)
        // The preset store keeps a clone preset per its own id.
        val store = com.brushwork.paint.brush.BrushPresetStore.get(app)
        store.remember(ToolId.CLONE, BrushLibrary.clones[1].copy(size = 50f))
        assertEquals(50f, store.current(ToolId.CLONE).size, 0f)
        assertEquals(BrushLibrary.defaultMaskBrush, store.current(ToolId.MASK))
        assertEquals(com.brushwork.paint.brush.StrokeKind.PAINT, com.brushwork.paint.brush.StrokeKind.of(ToolId.CLONE, BrushLibrary.byId("watercolor")!!))
    }
}
