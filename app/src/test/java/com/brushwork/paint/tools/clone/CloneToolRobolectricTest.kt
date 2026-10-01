package com.brushwork.paint.tools.clone

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.BrushPresetStore
import com.brushwork.paint.brush.StrokeHook
import com.brushwork.paint.brush.StrokeRecorder
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.CompositeTarget
import com.brushwork.paint.masks.AdjustmentSpec
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.tools.LayerToolRules
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** v1.5 §4.2: the clone stamp end to end (real brush engine, real Skia). */
@RunWith(RobolectricTestRunner::class)
class CloneToolRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()
    private var w = 200
    private var h = 140

    private fun setup(width: Int = 200, height: Int = 140): EditorController {
        w = width; h = height
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        app.getSharedPreferences(BrushPresetStore.PREFS_NAME, android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val doc = Document("t", "t", w, h)
        repeat(2) { i -> doc.layers += Layer(doc.newLayerId(), "Layer ${i + 1}", BitmapUtils.createLayerBitmap(w, h)) }
        doc.activeLayerIndex = 1
        val c = EditorController(app, doc, scope, settings)
        c.viewTransform.set(Matrix())
        c.selectTool(ToolId.CLONE)
        // A hard brush: its middle copies the source exactly.
        val pen = BrushLibrary.clones.first { it.id == "clone_pen" }
        c.updatePreset(ToolId.CLONE, pen.copy(size = 20f, hardness = 1f, pressureSize = false, minSizeRatio = 1f, opacity = 1f, flow = 1f))
        return c
    }

    private fun tool(c: EditorController) = c.tools.getValue(ToolId.CLONE) as CloneTool

    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun fill(b: Bitmap, l: Int, t: Int, r: Int, bottom: Int, color: Int) {
        Canvas(b).drawRect(l.toFloat(), t.toFloat(), r.toFloat(), bottom.toFloat(), Paint().apply { this.color = color })
    }

    /** A horizontal stroke through the controller (pointer gate, stroke assist). */
    private fun stroke(c: EditorController, x0: Float, y: Float, x1: Float, steps: Int = 8) {
        c.pointerDown(ToolPoint(x0, y))
        for (i in 1 until steps) c.pointerMove(ToolPoint(x0 + (x1 - x0) * i / steps, y))
        c.pointerUp(ToolPoint(x1, y))
    }

    /** The document as shown (with the live stroke). */
    private fun composite(c: EditorController): IntArray {
        val out = BitmapUtils.createLayerBitmap(w, h)
        c.compositor.drawDocument(Canvas(out), null, target = CompositeTarget.identity(out))
        return pixels(out)
    }

    /**
     * Replaces the clone's commit gate with one that records the document as shown right before
     * the commit (the live stroke with all its dabs); returns a reader of that capture.
     */
    private fun captureLiveAtCommit(c: EditorController): () -> IntArray? {
        var live: IntArray? = null
        tool(c).brush.strokeHook = {
            StrokeHook.Record(object : StrokeRecorder {
                override val replacesStroke: Boolean get() = false
                override val ignoresSelection: Boolean get() = false
                override fun point(x: Float, y: Float, rawPressure: Float) {}
                override fun commit(label: String, bounds: Rect, commitPixels: () -> Boolean): Boolean {
                    live = composite(c)
                    return commitPixels()
                }
                override fun cancel() {}
            })
        }
        return { live }
    }

    @Test
    fun aRedSquareAtTheSourceLandsExactlyAtTheOffset() {
        val c = setup()
        val layer = c.activeLayer
        fill(layer.bitmap, 20, 40, 50, 70, RED)
        val t = tool(c)
        t.setSource(Vec2(35f, 55f))
        stroke(c, 135f, 55f, 145f)
        for (x in 135..145) assertEquals("x=$x", RED, layer.bitmap.getPixel(x, 55))
        assertEquals(RED, layer.bitmap.getPixel(140, 50))
        assertEquals("nothing outside the stroke", 0, layer.bitmap.getPixel(100, 55))
        assertEquals("the source is untouched", RED, layer.bitmap.getPixel(35, 55))
        assertEquals(1, c.undoManager.undoCount)
        assertEquals(CloneTool.UNDO_LABEL, c.undoManager.undoLabel)
        c.undo()
        assertEquals(0, layer.bitmap.getPixel(140, 55))
        c.redo()
        assertEquals(RED, layer.bitmap.getPixel(140, 55))
        assertNull(c.renderOverride)
    }

    private fun twoBlocks(c: EditorController): Layer {
        val layer = c.activeLayer
        fill(layer.bitmap, 20, 20, 50, 50, RED)
        fill(layer.bitmap, 20, 70, 50, 100, BLUE)
        tool(c).setSource(Vec2(35f, 35f))
        return layer
    }

    @Test
    fun alignedKeepsTheOffsetAcrossStrokes() {
        val c = setup()
        val layer = twoBlocks(c)
        assertTrue("Aligned is on by default", tool(c).aligned)
        stroke(c, 135f, 35f, 140f)
        assertEquals(RED, layer.bitmap.getPixel(137, 35))
        assertEquals("the source travelled with the stroke", Vec2(40f, 35f), tool(c).anchor.source)
        // The second stroke keeps the offset (100, 0): below the red block is the blue one.
        stroke(c, 135f, 85f, 140f)
        assertEquals(BLUE, layer.bitmap.getPixel(137, 85))
        assertEquals(CloneOffset(100, 0), tool(c).anchor.fixed)
        assertEquals(2, c.undoManager.undoCount)
    }

    @Test
    fun nonAlignedStartsEveryStrokeAtTheSource() {
        val c = setup()
        val layer = twoBlocks(c)
        tool(c).setAligned(false)
        assertFalse(AppSettings(app).cloneAligned)
        stroke(c, 135f, 35f, 140f)
        stroke(c, 135f, 85f, 140f)
        assertEquals("the second stroke samples the source again", RED, layer.bitmap.getPixel(137, 85))
    }

    @Test
    fun aSourceOffTheCanvasCopiesNothing() {
        val c = setup()
        val layer = c.activeLayer
        layer.bitmap.eraseColor(GREEN)
        val t = tool(c)
        t.setSource(Vec2(10f, 10f))
        stroke(c, 150f, 100f, 152f)
        val before = pixels(layer.bitmap)
        val steps = c.undoManager.undoCount
        // Offset (140, 90): this stroke samples around (-80, -50), outside the document.
        stroke(c, 60f, 40f, 70f)
        assertArrayEquals(before, pixels(layer.bitmap))
        assertEquals("no step for a stroke that copies nothing", steps, c.undoManager.undoCount)
        assertNull(c.renderOverride)
    }

    @Test
    fun theOffCanvasPartOfASourceIsTransparentLiveAndCommitted() {
        val c = setup()
        val layer = c.activeLayer
        // A red column at the left edge: a clamped sample would smear it beyond the edge.
        fill(layer.bitmap, 0, 0, 6, h, RED)
        val t = tool(c)
        val live = captureLiveAtCommit(c)
        t.setSource(Vec2(3f, 60f))
        // Offset (100, 0): x 95..105 samples -5..5; the part left of 0 must copy nothing.
        stroke(c, 103f, 60f, 105f, steps = 2)
        assertArrayEquals("the live stroke equals the result", composite(c), live()!!)
        assertEquals(RED, layer.bitmap.getPixel(102, 60))
        assertEquals("its source (-2) is outside: nothing copied", 0, layer.bitmap.getPixel(98, 60))
        assertTrue(t.source.regionCopies > 0)
    }

    @Test
    fun theLiveStrokeEqualsTheCommittedResult() {
        val c = setup()
        val layer = c.activeLayer
        gradient(layer.bitmap)
        val t = tool(c)
        val live = captureLiveAtCommit(c)
        t.setSource(Vec2(60f, 50f))
        c.pointerDown(ToolPoint(140f, 70f))
        for (i in 1..6) c.pointerMove(ToolPoint(140f + i * 5, 70f + i * 3))
        c.pointerUp(ToolPoint(170f, 88f))
        assertArrayEquals(composite(c), live()!!)
        assertEquals(1, c.undoManager.undoCount)
    }

    private fun gradient(b: Bitmap) {
        val px = IntArray(w * h) { i -> val x = i % w; val y = i / w; 0xFF000000.toInt() or ((x * 255 / w) shl 16) or ((y * 255 / h) shl 8) or ((x * 7 + y * 3) and 0xFF) }
        b.setPixels(px, 0, w, 0, 0, w, h)
    }

    @Test
    fun theCommitCopyPreventsFeedbackOnAnOverlappingClone() {
        val c = setup()
        val layer = c.activeLayer
        gradient(layer.bitmap)
        val pre = pixels(layer.bitmap)
        val t = tool(c)
        val live = captureLiveAtCommit(c)
        t.setSource(Vec2(40f, 50f))
        // Offset (30, 0): the stroke reads 30 px behind where it paints, inside its own area.
        c.pointerDown(ToolPoint(70f, 50f))
        for (i in 1..20) c.pointerMove(ToolPoint(70f + i * 5, 50f))
        c.pointerUp(ToolPoint(170f, 50f))
        assertTrue("it painted", t.source.regionCopies > 0 && c.undoManager.undoCount == 1)
        // Where the brush covers fully, every pixel is the pre-stroke pixel 30 px to the left.
        for (y in 46..54) for (x in 74..166) {
            assertEquals("($x, $y)", pre[y * w + x - 30], layer.bitmap.getPixel(x, y))
        }
        assertArrayEquals("the live stroke read the same pixels", composite(c), live()!!)
    }

    @Test
    fun allLayersSamplesTheLayersBelowAndAnAdjustmentLayer() {
        val c = setup()
        val bottom = c.doc.layers[0]
        val top = c.doc.layers[1]
        fill(bottom.bitmap, 20, 40, 50, 70, BLUE)
        fill(top.bitmap, 20, 40, 35, 70, RED)
        val t = tool(c)
        t.setSource(Vec2(35f, 55f))
        // This layer: only the top layer's red half is copied.
        stroke(c, 135f, 55f, 140f)
        assertEquals(RED, top.bitmap.getPixel(130, 55))
        assertEquals("this layer has nothing right of x=35", 0, top.bitmap.getPixel(140, 55))
        c.undo()
        // All layers: the composite (red over blue) is copied onto the top layer.
        t.setSampleAllLayers(true)
        assertTrue(AppSettings(app).cloneSampleAllLayers)
        stroke(c, 135f, 55f, 140f)
        assertEquals(RED, top.bitmap.getPixel(130, 55))
        assertEquals(BLUE, top.bitmap.getPixel(140, 55))
        assertEquals(CloneSource.Sample.ALL_LAYERS, t.source.sample)
        c.undo()
        // An adjustment layer is part of what "all layers" means: the snapshot is the composite
        // the compositor draws for its target (A5 makes the effect live; F2 passes it through).
        c.selectLayer(bottom)
        val adj = c.addAdjustmentLayer(AdjustmentSpec(filterId = "adjust.invert"), null)
        assertNotNull(adj)
        c.selectLayer(top)
        val reference = BitmapUtils.createLayerBitmap(w, h)
        c.compositor.drawDocument(Canvas(reference), null, useOverrides = false, target = CompositeTarget.identity(reference))
        stroke(c, 135f, 55f, 140f)
        for (x in 128..142) assertEquals("x=$x", reference.getPixel(x - 100, 55), top.bitmap.getPixel(x, 55))
    }

    @Test
    fun theAllLayersSnapshotIsFilledLazilyAndFreedWhenPutAway() {
        val c = setup(800, 600)
        val t = tool(c)
        t.setSampleAllLayers(true)
        fill(c.doc.layers[0].bitmap, 20, 20, 60, 60, BLUE)
        t.setSource(Vec2(40f, 40f))
        stroke(c, 140f, 40f, 150f)
        assertEquals(BLUE, c.activeLayer.bitmap.getPixel(145, 40))
        assertTrue(t.source.hasSnapshot)
        // 4 x 3 tiles of 256 px exist; a small stroke near one corner needs one of them.
        assertTrue("tiles rendered: ${t.source.tilesRendered}", t.source.tilesRendered in 1..2)
        c.selectTool(ToolId.BRUSH)
        assertFalse("freed when the tool is put away", t.source.hasSnapshot)
    }

    @Test
    fun aLongAllLayersStrokeCompositesOnlyTheTilesItsPathSamples() {
        val c = setup(800, 600)
        val bottom = c.doc.layers[0]
        gradient(bottom.bitmap)
        val t = tool(c)
        t.setSampleAllLayers(true)
        t.setSource(Vec2(20f, 20f))
        // A diagonal stroke with the offset (40, 40): its bounding box covers 9 of the 12 tiles.
        c.pointerDown(ToolPoint(60f, 60f))
        for (i in 1..20) c.pointerMove(ToolPoint(60f + i * 35f, 60f + i * 26f))
        c.pointerUp(ToolPoint(760f, 580f))
        val top = c.activeLayer
        for (i in 0..20) {
            val x = 60 + i * 35
            val y = 60 + i * 26
            assertEquals("($x, $y)", bottom.bitmap.getPixel(x - 40, y - 40), top.bitmap.getPixel(x, y))
        }
        assertTrue("tiles composited: ${t.source.tilesRendered}", t.source.tilesRendered in 1..7)
    }

    @Test
    fun withoutMemoryForTheSnapshotItSamplesThisLayer() {
        val c = setup()
        val t = tool(c)
        t.setSampleAllLayers(true)
        t.source.snapshotBudgetBytes = 0
        fill(c.doc.layers[0].bitmap, 20, 40, 50, 70, BLUE)
        fill(c.activeLayer.bitmap, 20, 40, 50, 70, RED)
        t.setSource(Vec2(35f, 55f))
        stroke(c, 135f, 55f, 140f)
        assertEquals(CloneTool.FALLBACK_MESSAGE, c.message)
        assertEquals(RED, c.activeLayer.bitmap.getPixel(137, 55))
        assertFalse(t.source.hasSnapshot)
        // Said once, not at every stroke.
        c.message = null
        stroke(c, 135f, 60f, 140f)
        assertNull(c.message)
    }

    @Test
    fun theSelectionClipsTheClone() {
        val c = setup()
        val layer = c.activeLayer
        fill(layer.bitmap, 20, 40, 60, 70, RED)
        val a8 = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        Canvas(a8).drawRect(0f, 0f, 140f, h.toFloat(), Paint().apply { color = 0xFF000000.toInt() })
        c.setSelection(Selection.wrap(a8, Rect(0, 0, 140, h)), recordUndo = false)
        tool(c).setSource(Vec2(40f, 55f))
        stroke(c, 130f, 55f, 150f)
        assertEquals(RED, layer.bitmap.getPixel(135, 55))
        assertEquals("outside the selection", 0, layer.bitmap.getPixel(145, 55))
    }

    @Test
    fun alphaLockKeepsTheShapeOfTheLayer() {
        val c = setup()
        val layer = c.activeLayer
        fill(layer.bitmap, 20, 40, 60, 70, RED)
        fill(layer.bitmap, 120, 40, 140, 70, GREEN)
        layer.alphaLocked = true
        tool(c).setSource(Vec2(40f, 55f))
        stroke(c, 130f, 55f, 150f)
        assertEquals("painted where the layer had pixels", RED, layer.bitmap.getPixel(135, 55))
        assertEquals("transparency is kept", 0, layer.bitmap.getPixel(145, 55))
    }

    @Test
    fun editingTheMaskClonesTheMask() {
        val c = setup()
        val layer = c.activeLayer
        fill(layer.bitmap, 0, 0, w, h, GREEN)
        val mask = BitmapUtils.createMaskBitmap(w, h)
        fill(mask, 20, 40, 60, 70, 0xFF000000.toInt())
        layer.mask = mask
        layer.editingMask = true
        val t = tool(c)
        t.setSampleAllLayers(true) // a mask always clones its own values
        t.setSource(Vec2(40f, 55f))
        stroke(c, 130f, 55f, 140f)
        assertEquals(0xFF000000.toInt(), mask.getPixel(135, 55))
        assertEquals(-1, mask.getPixel(135, 30))
        assertEquals("the pixels are untouched", GREEN, layer.bitmap.getPixel(135, 55))
        assertEquals(CloneSource.Sample.THIS_LAYER, t.source.sample)
        assertNull("no fallback message for a mask", c.message)
    }

    @Test
    fun aSecondFingerLeavesNoPixelsAndNoStep() {
        val c = setup()
        val layer = c.activeLayer
        fill(layer.bitmap, 20, 40, 50, 70, RED)
        val before = pixels(layer.bitmap)
        tool(c).setSource(Vec2(35f, 55f))
        c.pointerDown(ToolPoint(135f, 55f))
        c.pointerMove(ToolPoint(145f, 55f))
        assertTrue(tool(c).isPainting)
        assertNotNull(tool(c).sampledPoint)
        c.pointerCancel()
        assertArrayEquals(before, pixels(layer.bitmap))
        assertEquals(0, c.undoManager.undoCount)
        assertNull(c.renderOverride)
        assertFalse(tool(c).isPainting)
        assertNull("a cancelled first stroke fixes no offset", tool(c).anchor.fixed)
    }

    @Test
    fun aLongPressSetsTheSourceWithoutPainting() {
        val c = setup()
        val layer = c.activeLayer
        fill(layer.bitmap, 20, 40, 50, 70, RED)
        val before = pixels(layer.bitmap)
        val t = tool(c)
        // Without a source a tap only explains what to do.
        c.pointerDown(ToolPoint(60f, 60f)); c.pointerUp(ToolPoint(60f, 60f))
        assertEquals(CloneTool.HINT, c.message)
        assertNull(t.objectPosition)
        // Long-press: the source is set where the finger is; the finger can still move it.
        c.pointerDown(ToolPoint(30f, 50f))
        assertTrue(c.pointerLongPress(ToolPoint(30f, 50f)))
        assertEquals(Vec2(30f, 50f), t.anchor.source)
        c.pointerMove(ToolPoint(35f, 55f))
        c.pointerUp(ToolPoint(35f, 55f))
        assertEquals(Vec2(35f, 55f), t.anchor.source)
        // A long-press in the middle of a stroke start drops that stroke and moves the source.
        c.pointerDown(ToolPoint(150f, 60f))
        assertTrue(t.isPainting)
        assertTrue(c.pointerLongPress(ToolPoint(150f, 60f)))
        assertFalse(t.isPainting)
        // The lift point went through the ruler / stabilizer: without a move the held point stays.
        c.pointerUp(ToolPoint(153f, 64f))
        assertEquals(Vec2(150f, 60f), t.anchor.source)
        assertArrayEquals(before, pixels(layer.bitmap))
        assertEquals(0, c.undoManager.undoCount)
        assertNull(c.renderOverride)
    }

    @Test
    fun setSourceArmsTheNextTapAndTheCrosshairDrags() {
        val c = setup()
        val layer = c.activeLayer
        val t = tool(c)
        t.arm(true)
        assertFalse("placing the source skips the stabilizer and ruler", t.usesStrokeAssist)
        c.pointerDown(ToolPoint(30f, 30f))
        c.pointerUp(ToolPoint(32f, 31f))
        assertEquals(Vec2(32f, 31f), t.anchor.source)
        assertFalse(t.armed)
        assertTrue(t.usesStrokeAssist)
        // Dragging the ⊕ (within 28 dp) moves the source, keeping where it was grabbed.
        c.pointerDown(ToolPoint(40f, 31f))
        c.pointerMove(ToolPoint(60f, 51f))
        c.pointerUp(ToolPoint(70f, 61f))
        assertEquals(Vec2(62f, 61f), t.anchor.source)
        assertTrue("nothing was painted", pixels(layer.bitmap).all { it == 0 })
        assertEquals(0, c.undoManager.undoCount)
        // Hidden, the crosshair can't be grabbed: the touch paints.
        t.setShowSource(false)
        assertFalse(AppSettings(app).cloneShowSource)
        c.pointerDown(ToolPoint(62f, 61f))
        assertTrue(t.isPainting)
        c.pointerCancel()
        // A second finger while placing puts the source back.
        t.arm(true)
        c.pointerDown(ToolPoint(100f, 100f))
        assertEquals(Vec2(100f, 100f), t.anchor.source)
        c.pointerCancel()
        assertEquals(Vec2(62f, 61f), t.anchor.source)
        assertTrue("still armed", t.armed)
    }

    @Test
    fun theXYStripMovesTheSource() {
        val c = setup()
        val t = tool(c)
        assertNull(t.objectPosition)
        t.setSource(Vec2(10f, 20f))
        t.anchor.strokeCompleted(CloneOffset(5, 5), aligned = true, end = Vec2(15f, 25f))
        val pos = t.objectPosition!!
        assertEquals("Source", pos.label)
        assertEquals(Vec2(10f, 20f), pos.position)
        pos.setPosition(40f, null)
        assertEquals(Vec2(40f, 20f), t.anchor.source)
        pos.setPosition(null, 70f)
        pos.endPositionEdit()
        assertEquals(Vec2(40f, 70f), pos.position)
        assertNull("a moved source starts a new alignment", t.anchor.fixed)
        assertEquals(0, c.undoManager.undoCount)
    }

    @Test
    fun refusedOnAVectorLayer() {
        val c = setup()
        val layer = c.activeLayer
        layer.vector = VectorContent.EMPTY
        tool(c).setSource(Vec2(35f, 55f))
        c.pointerDown(ToolPoint(135f, 55f))
        assertEquals(LayerToolRules.pixelOnlyMessage(ToolId.CLONE), c.message)
        assertTrue(c.message!!.startsWith("Clone stamp works on pixels"))
        c.pointerMove(ToolPoint(145f, 55f))
        c.pointerUp(ToolPoint(145f, 55f))
        assertFalse(tool(c).isPainting)
        assertEquals(0, c.undoManager.undoCount)
        assertTrue(pixels(layer.bitmap).all { it == 0 })
        assertNotNull(layer.vector)
    }

    @Test
    fun aLockedLayerSaysSoAndPaintsNothing() {
        val c = setup()
        val layer = c.activeLayer
        layer.locked = true
        tool(c).setSource(Vec2(35f, 55f))
        stroke(c, 135f, 55f, 145f)
        assertEquals("Layer \"${layer.name}\" is locked", c.message)
        assertEquals(0, c.undoManager.undoCount)
        assertFalse(tool(c).source.isActive)
    }

    @Test
    fun thePresetPersists() {
        val c = setup()
        val store = BrushPresetStore.get(app)
        store.edit(c, ToolId.CLONE, persist = true) { it.copy(size = 55f) }
        assertEquals(55f, c.cloneBrush.size, 0f)
        // Another editor (the tools are created anew) starts with it.
        val c2 = EditorController(app, Document("u", "u", 64, 64).also { d ->
            d.layers += Layer(d.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(64, 64))
        }, scope, AppSettings(app))
        c2.tools // creates the clone tool, which restores its preset
        assertEquals(55f, c2.cloneBrush.size, 0f)
        assertEquals(55f, c2.presetFor(ToolId.CLONE)!!.size, 0f)
        assertEquals(BrushLibrary.defaultBrush, c2.presetFor(ToolId.BRUSH))
        c2.dispose()
    }

    @Test
    fun theOverlayDrawsInEveryState() {
        val c = setup()
        val t = tool(c)
        val screen = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(screen)
        t.drawOverlay(canvas, c.viewTransform) // no source: nothing
        assertTrue(pixels(screen).all { it == 0 })
        t.setSource(Vec2(50f, 50f))
        t.drawOverlay(canvas, c.viewTransform)
        assertTrue("the ⊕ is drawn", screen.getPixel(50, 50 - 9) != 0 || screen.getPixel(50 + 9, 50) != 0)
        c.pointerDown(ToolPoint(120f, 60f))
        c.pointerMove(ToolPoint(130f, 60f))
        screen.eraseColor(0)
        t.drawOverlay(canvas, c.viewTransform)
        assertEquals(Vec2(60f, 50f), t.sampledPoint)
        assertTrue("the sampled point's ring", pixels(screen).count { it != 0 } > 50)
        c.pointerUp(ToolPoint(130f, 60f))
        t.setShowSource(false)
        screen.eraseColor(0)
        t.drawOverlay(canvas, c.viewTransform)
        assertTrue(pixels(screen).all { it == 0 })
    }

    @Test
    fun aStrokeContinuingFromTheLastEndPaintsInsteadOfGrabbingTheSource() {
        val c = setup()
        val layer = c.activeLayer
        gradient(layer.bitmap)
        val t = tool(c)
        // Retouching zoomed in (×2): a 20 px offset is 40 px on screen, the first stroke paints.
        c.viewTransform.set(Matrix().apply { setScale(2f, 2f) })
        t.setSource(Vec2(100f, 70f))
        stroke(c, 120f, 70f, 130f)
        assertEquals(CloneOffset(20, 0), t.anchor.fixed)
        assertEquals("the ⊕ travelled to what the end sampled", Vec2(110f, 70f), t.anchor.source)
        // Zoomed out again, the offset is 20 px on screen, closer than the 28 dp grab radius.
        // The next stroke starts where the last one ended, 20 px from the ⊕: it paints.
        c.viewTransform.set(Matrix())
        val steps = c.undoManager.undoCount
        c.pointerDown(ToolPoint(130f, 70f))
        assertTrue("continuing the stroke paints", t.isPainting)
        c.pointerMove(ToolPoint(136f, 72f))
        c.pointerUp(ToolPoint(140f, 74f))
        assertEquals(steps + 1, c.undoManager.undoCount)
        // A touch right on the ⊕ (well within half the offset) still grabs it.
        val before = t.anchor.source!!
        c.pointerDown(ToolPoint(before.x + 2f, before.y))
        assertFalse(t.isPainting)
        // Holding the ⊕ still is no long-press command (no haptic tick for nothing).
        assertFalse(c.pointerLongPress(ToolPoint(before.x + 2f, before.y)))
        c.pointerMove(ToolPoint(before.x + 12f, before.y + 5f))
        c.pointerUp(ToolPoint(before.x + 12f, before.y + 5f))
        assertEquals(Vec2(before.x + 10f, before.y + 5f), t.anchor.source)
        assertEquals(steps + 1, c.undoManager.undoCount)
        // Without Aligned the ⊕ keeps the full grab radius.
        t.setAligned(false)
        assertTrue(t.isOnCrosshair(Vec2(t.anchor.source!!.x + 20f, t.anchor.source!!.y), t.anchor.source!!))
    }

    @Test
    fun nonFiniteOrHugeSourcePositionsAreSafe() {
        val c = setup()
        val layer = c.activeLayer
        gradient(layer.bitmap)
        val t = tool(c)
        t.setSource(Vec2(10f, 10f))
        val pos = t.objectPosition!!
        pos.setPosition(Float.NaN, null)
        pos.setPosition(null, Float.POSITIVE_INFINITY)
        t.setSource(Vec2(Float.NaN, 3f))
        assertEquals("non-finite values are ignored", Vec2(10f, 10f), t.anchor.source)
        // A typed value far off the canvas is kept within range: painting copies nothing, no crash.
        pos.setPosition(5e9f, -5e9f)
        assertEquals(Vec2(CloneAnchor.MAX_COORD, -CloneAnchor.MAX_COORD), t.anchor.source)
        val before = pixels(layer.bitmap)
        stroke(c, 100f, 70f, 110f)
        assertArrayEquals(before, pixels(layer.bitmap))
        assertEquals(0, c.undoManager.undoCount)
        assertNull(c.renderOverride)
        // Aligned moved the far source along by the stroke; it stays finite and in range.
        val s = t.anchor.source!!
        assertTrue(s.x.isFinite() && s.y.isFinite() && kotlin.math.abs(s.x) <= CloneAnchor.MAX_COORD)
    }

    @Test
    fun aLongPressOnALockedLayerStillSetsTheSource() {
        val c = setup()
        val layer = c.activeLayer
        layer.locked = true
        val t = tool(c)
        t.setSource(Vec2(10f, 10f))
        c.pointerDown(ToolPoint(50f, 60f))
        assertEquals("Layer \"${layer.name}\" is locked", c.message)
        assertTrue(c.pointerLongPress(ToolPoint(50f, 60f)))
        c.pointerUp(ToolPoint(50f, 60f))
        assertEquals(Vec2(50f, 60f), t.anchor.source)
        assertEquals(0, c.undoManager.undoCount)
    }

    @Test
    fun aStillFingerAfterALongPressKeepsTheHeldPoint() {
        val c = setup()
        val t = tool(c)
        c.pointerDown(ToolPoint(30f, 50f))
        assertTrue(c.pointerLongPress(ToolPoint(30f, 50f)))
        // Jitter of a still finger (within 6 dp) leaves the source where it was held.
        c.pointerMove(ToolPoint(32f, 51f))
        c.pointerMove(ToolPoint(29f, 52f))
        assertEquals(Vec2(30f, 50f), t.anchor.source)
        // Moving on drags it along.
        c.pointerMove(ToolPoint(40f, 60f))
        assertEquals(Vec2(40f, 60f), t.anchor.source)
        c.pointerUp(ToolPoint(44f, 62f))
        assertEquals(Vec2(44f, 62f), t.anchor.source)
    }

    @Test
    fun allLayersLiveStrokeEqualsTheCommittedResult() {
        val c = setup(600, 400)
        val bottom = c.doc.layers[0]
        gradient(bottom.bitmap)
        fill(c.activeLayer.bitmap, 300, 0, 600, 120, 0x80FF00FF.toInt())
        val t = tool(c)
        t.setSampleAllLayers(true)
        val live = captureLiveAtCommit(c)
        t.setSource(Vec2(60f, 50f))
        // A diagonal stroke over three snapshot tiles, its source crossing tile borders.
        c.pointerDown(ToolPoint(240f, 150f))
        for (i in 1..12) c.pointerMove(ToolPoint(240f + i * 25f, 150f + i * 18f))
        c.pointerUp(ToolPoint(560f, 380f))
        assertEquals(CloneSource.Sample.ALL_LAYERS, t.source.sample)
        assertArrayEquals("the live stroke equals the result", composite(c), live()!!)
        assertEquals(1, c.undoManager.undoCount)
        // And it copied the composite: the start pixel is the source pixel (180, 100 away).
        val reference = BitmapUtils.createLayerBitmap(w, h)
        c.undo()
        c.compositor.drawDocument(Canvas(reference), null, useOverrides = false, target = CompositeTarget.identity(reference))
        c.redo()
        assertEquals(reference.getPixel(60, 50), c.activeLayer.bitmap.getPixel(240, 150))
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
        const val BLUE = 0xFF0000FF.toInt()
        const val GREEN = 0xFF00FF00.toInt()
    }
}
