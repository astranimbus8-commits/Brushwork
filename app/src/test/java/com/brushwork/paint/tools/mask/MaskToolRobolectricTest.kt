package com.brushwork.paint.tools.mask

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.AdjustmentStage
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.masks.AdjustmentEffects
import com.brushwork.paint.masks.BrushMask
import com.brushwork.paint.masks.LinearMask
import com.brushwork.paint.masks.MaskGeometry
import com.brushwork.paint.masks.MaskSpec
import com.brushwork.paint.masks.MaskSpecs
import com.brushwork.paint.masks.RadialMask
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
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
import kotlin.math.abs

/**
 * v1.5 A5 (§4.3a, §4.3e): the Masks tool — creating components (a new adjustment layer in one
 * step), handle drags, brush strokes painted live, cancel without a trace, pins, the pinch rule,
 * the reduced-resolution preview, the X / Y strip and the Adjust session's single step.
 */
@RunWith(RobolectricTestRunner::class)
class MaskToolRobolectricTest {
    private val w = 200
    private val h = 150
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var c: EditorController

    @After
    fun tearDown() {
        if (::c.isInitialized) c.dispose()
        AdjustmentStage.safeCompositing = false
        scope.cancel()
    }

    private fun setup(): EditorController {
        val app = RuntimeEnvironment.getApplication()
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("m", "m", w, h)
        doc.layers += Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(w, h)).also { it.bitmap.eraseColor(-1) }
        doc.layers += Layer(doc.newLayerId(), "Photo", BitmapUtils.createLayerBitmap(w, h)).also {
            Canvas(it.bitmap).drawRect(20f, 20f, 180f, 130f, Paint().apply { color = 0xFF3366AA.toInt() })
        }
        doc.activeLayerIndex = 1
        c = EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
        c.selectTool(ToolId.MASK)
        return c
    }

    private val tool get() = c.tools.getValue(ToolId.MASK) as MaskTool

    private fun drag(vararg pts: Pair<Float, Float>) {
        c.pointerDown(ToolPoint(pts[0].first, pts[0].second))
        for (p in pts.drop(1)) c.pointerMove(ToolPoint(p.first, p.second))
        c.pointerUp(ToolPoint(pts.last().first, pts.last().second))
    }

    /** A drag in small steps (like a finger). */
    private fun stroke(x0: Float, y0: Float, x1: Float, y1: Float, n: Int = 20) {
        c.pointerDown(ToolPoint(x0, y0))
        for (i in 1..n) c.pointerMove(ToolPoint(x0 + (x1 - x0) * i / n, y0 + (y1 - y0) * i / n))
        c.pointerUp(ToolPoint(x1, y1))
    }

    private fun maskPixels(l: Layer) = IntArray(w * h).also { l.mask!!.getPixels(it, 0, w, 0, 0, w, h) }

    private fun rendered(spec: MaskSpec) = IntArray(w * h).also { MaskSpecs.render(spec, w, h, Rect(0, 0, w, h), it, w) }

    @Test
    fun aDragWithNothingArmedCreatesNothing() {
        setup()
        val layers = c.doc.layers.size
        drag(30f to 30f, 120f to 90f)
        c.pointerDown(ToolPoint(50f, 50f)); c.pointerUp(ToolPoint(50f, 50f))
        assertEquals(0, c.undoManager.undoCount)
        assertEquals(layers, c.doc.layers.size)
        assertNull(c.renderOverride)
        assertFalse(tool.hasPendingWork)
    }

    @Test
    fun anArmedLinearDragAddsATone1LayerWithTheComponentAsOneStep() {
        setup()
        val photo = c.activeLayer
        tool.arm(MaskTool.Kind.LINEAR)
        drag(20f to 75f, 40f to 75f, 60f to 75f, 160f to 75f)
        assertEquals(1, c.undoManager.undoCount)
        assertEquals("Mask: linear", c.undoManager.undoLabel)
        val adj = c.activeLayer
        assertTrue(adj.isAdjustmentLayer)
        assertEquals("Tone 1", adj.name)
        assertEquals(AdjustmentEffects.DEFAULT_ID, adj.adjustment!!.filterId)
        assertEquals(c.doc.indexOf(photo) + 1, c.doc.indexOf(adj))
        val comp = adj.maskSpec!!.components.single() as LinearMask
        assertEquals(20f, comp.x0, 0f); assertEquals(160f, comp.x1, 0f)
        assertArrayEquals(rendered(adj.maskSpec!!), maskPixels(adj))
        assertEquals(comp.id, tool.selectedId)
        assertNull(tool.armed)
        assertNull("no preview left", c.renderOverride)
        assertEquals(1, tool.adjustPulse)
        c.undo()
        assertEquals(-1, c.doc.indexOf(adj))
        assertSame(photo, c.activeLayer)
    }

    @Test
    fun aRadialOnAMasklessAdjustmentLayerCreatesItsMask() {
        setup()
        val adj = c.addAdjustmentLayer(AdjustmentEffects.defaultSpec(FilterRegistry.byId("adjust.invert")), null)!!
        c.selectTool(ToolId.BRUSH); c.selectTool(ToolId.MASK)
        val steps = c.undoManager.undoCount
        tool.arm(MaskTool.Kind.RADIAL)
        drag(100f to 75f, 110f to 75f, 140f to 75f)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals("Mask: radial", c.undoManager.undoLabel)
        val r = adj.maskSpec!!.components.single() as RadialMask
        assertEquals(40f, r.rx, 1e-3f)
        assertNotNull(adj.mask)
        assertArrayEquals(rendered(adj.maskSpec!!), maskPixels(adj))
        // The composite now inverts only inside the ellipse.
        val flat = c.compositor.renderFlattened()
        assertEquals(0xFFCC9955.toInt(), flat.getPixel(100, 75))
        assertEquals(0xFF3366AA.toInt(), flat.getPixel(25, 25))
        c.undo()
        assertNull(adj.mask)
        assertNull(adj.maskSpec)
    }

    private fun radialLayer(): Layer {
        tool.arm(MaskTool.Kind.RADIAL)
        drag(100f to 75f, 110f to 75f, 140f to 75f)
        return c.activeLayer
    }

    @Test
    fun aHandleDragIsOneDataOnlyStepAndUndoReRendersTheMask() {
        setup()
        val adj = radialLayer()
        val before = adj.maskSpec!!
        val steps = c.undoManager.undoCount
        // The right side handle (rx) is at (140, 75): drag it 20 px further.
        drag(140f to 75f, 150f to 75f, 160f to 75f)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals("Edit mask", c.undoManager.undoLabel)
        val after = adj.maskSpec!!
        assertEquals(60f, (after.components.single() as RadialMask).rx, 1e-3f)
        assertArrayEquals(rendered(after), maskPixels(adj))
        assertNull(c.renderOverride)
        c.undo()
        assertEquals(before, adj.maskSpec)
        assertArrayEquals("undo re-renders", rendered(before), maskPixels(adj))
        c.redo()
        assertEquals(after, adj.maskSpec)
        assertArrayEquals(rendered(after), maskPixels(adj))
    }

    @Test
    fun pinsMoveComponentsAndATapOnTheSelectedPinDeselectsIt() {
        setup()
        val adj = radialLayer()
        val id = tool.selectedId!!
        drag(100f to 75f, 90f to 70f, 80f to 65f)
        assertEquals("Move mask", c.undoManager.undoLabel)
        val r = adj.maskSpec!!.components.single() as RadialMask
        assertEquals(80f, r.cx, 1e-3f); assertEquals(65f, r.cy, 1e-3f)
        assertEquals(id, tool.selectedId)
        val steps = c.undoManager.undoCount
        c.pointerDown(ToolPoint(80f, 65f)); c.pointerUp(ToolPoint(80f, 65f))
        assertNull(tool.selectedId)
        assertEquals(steps, c.undoManager.undoCount)
        // A tap on the pin selects it again.
        c.pointerDown(ToolPoint(81f, 66f)); c.pointerUp(ToolPoint(81f, 66f))
        assertEquals(id, tool.selectedId)
    }

    @Test
    fun brushStrokesPaintTheMaskLiveAsOneStepEach() {
        setup()
        val adj = radialLayer()
        c.updatePreset(ToolId.MASK, c.maskBrush.copy(size = 30f, hardness = 0.6f, opacity = 1f))
        tool.arm(MaskTool.Kind.BRUSH)
        val before = adj.maskSpec!!
        val steps = c.undoManager.undoCount
        // While painting, the mask already shows the stroke.
        c.pointerDown(ToolPoint(20f, 20f))
        for (i in 1..20) c.pointerMove(ToolPoint(20f + i * 4f, 20f + i))
        assertNotNull("the layer is painted live", c.renderOverride)
        assertEquals(255, adj.mask!!.getPixel(50, 27) and 0xFF)
        c.pointerUp(ToolPoint(100f, 40f))
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals("Mask: brush", c.undoManager.undoLabel)
        val spec1 = adj.maskSpec!!
        val brush = spec1.components.last() as BrushMask
        assertEquals(1, brush.strokes.size)
        assertEquals(brush.id, tool.selectedId)
        assertArrayEquals("live pixels = the spec's rendering", rendered(spec1), maskPixels(adj))
        assertNull(c.renderOverride)
        // The selected brush component keeps painting: a second stroke, then an erase.
        stroke(30f, 120f, 170f, 120f)
        assertEquals("Brush mask", c.undoManager.undoLabel)
        tool.brushErase = true
        stroke(100f, 100f, 100f, 140f)
        assertEquals("Erase mask", c.undoManager.undoLabel)
        val spec3 = adj.maskSpec!!
        assertEquals(3, (spec3.components.last() as BrushMask).strokes.size)
        assertArrayEquals(rendered(spec3), maskPixels(adj))
        c.undo(); c.undo(); c.undo()
        assertEquals(before, adj.maskSpec)
        assertArrayEquals(rendered(before), maskPixels(adj))
        c.redo()
        assertArrayEquals(rendered(spec1), maskPixels(adj))
    }

    @Test
    fun aSecondFingerCancelsAStrokeWithoutATrace() {
        setup()
        val adj = radialLayer()
        val radialId = tool.selectedId
        val px = maskPixels(adj)
        val spec = adj.maskSpec
        val steps = c.undoManager.undoCount
        tool.arm(MaskTool.Kind.BRUSH)
        c.pointerDown(ToolPoint(20f, 20f))
        for (i in 1..20) c.pointerMove(ToolPoint(20f + i * 5f, 20f + i * 2f))
        c.pointerCancel()
        assertEquals(steps, c.undoManager.undoCount)
        assertEquals(spec, adj.maskSpec)
        assertArrayEquals(px, maskPixels(adj))
        assertNull(c.renderOverride)
        // A cancelled handle drag puts the preview away too.
        tool.select(radialId)
        c.pointerDown(ToolPoint(140f, 75f)); c.pointerMove(ToolPoint(170f, 75f))
        assertNotNull("previewed", c.renderOverride)
        c.pointerCancel()
        assertNull(c.renderOverride)
        assertEquals(spec, adj.maskSpec)
    }

    @Test
    fun aFirstBrushStrokeOnAPixelLayerMakesANewAdjustment() {
        setup()
        tool.arm(MaskTool.Kind.BRUSH)
        stroke(30f, 30f, 150f, 100f)
        assertEquals(1, c.undoManager.undoCount)
        assertEquals("Mask: brush", c.undoManager.undoLabel)
        val adj = c.activeLayer
        assertTrue(adj.isAdjustmentLayer)
        val b = adj.maskSpec!!.components.single() as BrushMask
        assertEquals(1, b.strokes.size)
        assertArrayEquals(rendered(adj.maskSpec!!), maskPixels(adj))
        assertNull(c.renderOverride)
    }

    @Test
    fun twoFingersScaleTheSelectedComponentOnlyWithAFingerOnIt() {
        setup()
        val adj = radialLayer() // centre (100, 75), radius 40
        // Both fingers outside on opposite sides (the midpoint is the centre): the view zooms.
        assertFalse(c.twoFingerStart(Vec2(100f, 75f), Vec2(40f, 75f), Vec2(160f, 75f)))
        // One finger inside, one far away: the component.
        assertTrue(c.twoFingerStart(Vec2(120f, 75f), Vec2(100f, 75f), Vec2(190f, 140f)))
        c.twoFingerEnd(cancelled = true)
        assertNull(c.renderOverride)
        // Both inside: scale x2 and turn 30°, one step.
        val steps = c.undoManager.undoCount
        assertTrue(c.twoFingerStart(Vec2(100f, 75f), Vec2(90f, 75f), Vec2(110f, 75f)))
        c.twoFingerGesture(Vec2(0f, 0f), 2f, 30f)
        assertNotNull("previewed", c.renderOverride)
        c.twoFingerEnd(cancelled = false)
        assertEquals(steps + 1, c.undoManager.undoCount)
        val r = adj.maskSpec!!.components.single() as RadialMask
        assertEquals(80f, r.rx, 1e-2f)
        assertEquals(30f, r.rotationDeg, 1e-2f)
        assertArrayEquals(rendered(adj.maskSpec!!), maskPixels(adj))
        // A small component: one finger on it is enough (44 dp minimum box).
        tool.updateComponent(r.copy(rx = 3f, ry = 3f), "Edit mask")
        assertTrue(c.twoFingerStart(Vec2(130f, 75f), Vec2(110f, 75f), Vec2(150f, 75f)))
        c.twoFingerEnd(cancelled = true)
        // Nothing selected: the view.
        tool.select(null)
        assertFalse(c.twoFingerStart(Vec2(100f, 75f), Vec2(99f, 75f), Vec2(101f, 75f)))
    }

    @Test
    fun theReducedPreviewMatchesTheFinalRenderWithin2() {
        setup()
        val adj = radialLayer()
        val spec = MaskSpec(components = listOf(LinearMask(1, x0 = 10f, y0 = 20f, x1 = 190f, y1 = 120f), RadialMask(2, mode = com.brushwork.paint.masks.MaskMode.SUBTRACT, amount = 0.5f, cx = 90f, cy = 70f, rx = 70f, ry = 50f, feather = 1f)), nextId = 3)
        val preview = MaskPreview(c)
        preview.begin(adj, adj.maskSpec!!, null)
        preview.update(spec)
        assertNotNull(c.renderOverride)
        val bmp = preview.bitmap!!
        assertEquals(0.5f, preview.scale, 0f)
        val up = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(up).drawBitmap(bmp, null, android.graphics.RectF(0f, 0f, bmp.width / preview.scale, bmp.height / preview.scale), Paint(Paint.FILTER_BITMAP_FLAG))
        val want = rendered(spec)
        var worst = 0
        for (y in 2 until h - 2) for (x in 2 until w - 2) worst = maxOf(worst, abs((up.getPixel(x, y) and 0xFF) - (want[y * w + x] and 0xFF)))
        assertTrue("preview differs by $worst / 255", worst <= 2)
        preview.end()
        assertNull(c.renderOverride)
        preview.release()
    }

    @Test
    fun theXYStripMovesTheSelectedComponentAsOneStep() {
        setup()
        assertNull(tool.objectPosition)
        val adj = radialLayer()
        val pos = tool.objectPosition!!
        assertEquals("Radial 1", pos.label)
        assertEquals(Vec2(100f, 75f), pos.position)
        val steps = c.undoManager.undoCount
        pos.setPosition(110f, null)
        pos.setPosition(120f, 60f)
        assertEquals(steps, c.undoManager.undoCount)
        pos.endPositionEdit()
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals("Move mask", c.undoManager.undoLabel)
        val r = adj.maskSpec!!.components.single() as RadialMask
        assertEquals(120f, r.cx, 1e-3f); assertEquals(60f, r.cy, 1e-3f)
        assertNull(c.renderOverride)
    }

    @Test
    fun theAdjustSessionIsOneStepRecordedBeforeAnyOtherStep() {
        setup()
        val adj = radialLayer()
        val start = adj.adjustment
        val invert = AdjustmentEffects.defaultSpec(FilterRegistry.byId("adjust.invert"))
        val edit = tool.adjustmentEdit(adj)
        val steps = c.undoManager.undoCount
        edit.preview(invert, 0.7f)
        edit.preview(invert, 0.5f)
        assertEquals("live, no step yet", steps, c.undoManager.undoCount)
        assertEquals(0.5f, adj.opacity, 0f)
        // An unrelated step: the adjustment is recorded first, as its own step.
        c.addLayer()
        assertEquals(steps + 2, c.undoManager.undoCount)
        c.undo()
        assertEquals("Edit adjustment", c.undoManager.undoLabel)
        assertEquals(invert, adj.adjustment)
        c.undo()
        assertEquals(start, adj.adjustment)
        assertEquals(1f, adj.opacity, 0f)
        // Undo during a session takes the session back.
        c.selectLayer(adj)
        val e2 = tool.adjustmentEdit(adj)
        val n = c.undoManager.undoCount
        e2.preview(invert)
        c.undo()
        assertEquals(n, c.undoManager.undoCount)
        assertEquals(start, adj.adjustment)
        c.redo()
        assertEquals(invert, adj.adjustment)
        // Switching tools records a pending session.
        e2.preview(AdjustmentEffects.defaultSpec())
        val m = c.undoManager.undoCount
        c.selectTool(ToolId.BRUSH)
        assertEquals(m + 1, c.undoManager.undoCount)
    }

    @Test
    fun masksOnAVectorLayerMakeAnAdjustmentAboveIt() {
        setup()
        val v = c.addVectorLayer()!!
        c.selectTool(ToolId.BRUSH); c.selectTool(ToolId.MASK)
        tool.arm(MaskTool.Kind.LINEAR)
        drag(20f to 20f, 120f to 20f)
        assertTrue(c.activeLayer.isAdjustmentLayer)
        assertEquals(c.doc.indexOf(v) + 1, c.doc.indexOf(c.activeLayer))
        assertNotNull(v.vector)
    }

    @Test
    fun thisLayersMaskFadesAPhotoWithAGradient() {
        setup()
        val photo = c.activeLayer
        com.brushwork.paint.masks.MaskLayerOps.addGradientMask(c, photo)
        assertEquals(MaskTool.Target.ThisLayer, tool.target)
        assertEquals(MaskTool.Kind.LINEAR, tool.armed)
        drag(20f to 75f, 100f to 75f, 180f to 75f)
        assertEquals("Mask: linear", c.undoManager.undoLabel)
        assertSame(photo, c.activeLayer)
        assertNotNull(photo.maskSpec)
        assertArrayEquals(rendered(photo.maskSpec!!), maskPixels(photo))
        // A painted mask asks before it is replaced.
        c.undo()
        c.addMask(photo, fromSelection = false)
        c.selectTool(ToolId.BRUSH); c.selectTool(ToolId.MASK)
        tool.chooseTarget(MaskTool.Target.ThisLayer)
        assertSame(photo, tool.replacePrompt)
        tool.answerReplace(true)
        tool.arm(MaskTool.Kind.RADIAL)
        drag(100f to 75f, 140f to 75f)
        assertNotNull(photo.maskSpec)
        c.undo()
        assertNull(photo.maskSpec)
        assertTrue("the painted (white) mask is back", maskPixels(photo).all { it == -1 })
    }

    @Test
    fun deleteAndDuplicateComponents() {
        setup()
        val adj = radialLayer()
        val id = tool.selectedId!!
        tool.duplicateComponent(id)
        assertEquals(2, adj.maskSpec!!.components.size)
        assertEquals("Duplicate mask component", c.undoManager.undoLabel)
        assertTrue(tool.selectedId != id)
        tool.deleteComponent(tool.selectedId!!)
        assertEquals(1, adj.maskSpec!!.components.size)
        assertNull(tool.selectedId)
        // Without a selection the mode chips set the mode of new components (no step).
        val n = c.undoManager.undoCount
        tool.setMode(com.brushwork.paint.masks.MaskMode.INTERSECT)
        assertEquals(n, c.undoManager.undoCount)
        assertEquals(com.brushwork.paint.masks.MaskMode.INTERSECT, tool.newMode)
        tool.select(id)
        tool.setMode(com.brushwork.paint.masks.MaskMode.SUBTRACT)
        assertEquals("one step per change", "Mask mode", c.undoManager.undoLabel)
        tool.toggleInvertSelected()
        assertTrue(adj.maskSpec!!.components.single().invert)
        assertArrayEquals(rendered(adj.maskSpec!!), maskPixels(adj))
        val moved = MaskGeometry.movedTo(adj.maskSpec!!.components.single(), 50f, 50f)
        tool.previewSpec(MaskGeometry.replaced(adj.maskSpec!!, id, moved))
        assertNotNull(c.renderOverride)
        tool.cancelSpecPreview()
        assertNull(c.renderOverride)
    }
}
