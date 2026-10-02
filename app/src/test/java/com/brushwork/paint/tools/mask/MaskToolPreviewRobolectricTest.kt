package com.brushwork.paint.tools.mask

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.AdjustmentStage
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.CompositeTarget
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.masks.AdjustmentEffects
import com.brushwork.paint.masks.BrushMask
import com.brushwork.paint.masks.MaskMode
import com.brushwork.paint.masks.MaskSpecRenderer
import com.brushwork.paint.masks.RadialMask
import com.brushwork.paint.masks.SampleGrid
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.math.cos
import kotlin.math.sin

/**
 * v1.5 A5 review (second pass): the growing preview of a new brush part equals a full render of
 * the stroke so far while only its new dabs are re-rendered, an erase stroke with no brush part
 * to erase from is refused, an adjustment layer without a mask shows its effect only inside the
 * mask being dragged, and a pending X / Y strip move is recorded before a canvas gesture.
 */
@RunWith(RobolectricTestRunner::class)
class MaskToolPreviewRobolectricTest {
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
        val doc = Document("p", "p", w, h)
        doc.layers += Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(w, h)).also { it.bitmap.eraseColor(-1) }
        doc.layers += Layer(doc.newLayerId(), "Photo", BitmapUtils.createLayerBitmap(w, h)).also {
            Canvas(it.bitmap).drawRect(20f, 20f, 180f, 130f, Paint().apply { color = PHOTO })
        }
        doc.activeLayerIndex = 1
        c = EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
        c.selectTool(ToolId.MASK)
        return c
    }

    private val tool get() = c.tools.getValue(ToolId.MASK) as MaskTool

    private val invert get() = AdjustmentEffects.defaultSpec(FilterRegistry.byId("adjust.invert"))

    private fun px(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** The preview bitmap must equal a full render of the shown spec on the preview's sample grid. */
    private fun assertPreviewIsAFullRender(what: String) {
        val pv = tool.preview
        val bmp = pv.bitmap!!
        val spec = tool.displaySpec!!
        val grid = SampleGrid(0f, 0f, 1f / pv.scale, bmp.width, bmp.height)
        val expected = IntArray(grid.size)
        MaskSpecRenderer.renderGrid(spec, grid, expected, grid.cols)
        assertArrayEquals(what, expected, px(bmp))
    }

    private fun composite(): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        c.compositor.drawDocument(Canvas(out), null, useOverrides = true, target = CompositeTarget.identity(out))
        return out
    }

    /** A wavy stroke (not up yet) of [n] moves from ([x0], [y0]). */
    private fun wavy(x0: Float, y0: Float, n: Int, check: (Int) -> Unit = {}) {
        c.pointerDown(ToolPoint(x0, y0))
        for (i in 1..n) {
            val t = i / n.toFloat()
            c.pointerMove(ToolPoint(x0 + 140f * t, y0 + 30f * sin(t * 9f) + 10f * cos(t * 23f)))
            check(i)
        }
    }

    @Test
    fun theGrowingPreviewOfANewBrushPartEqualsAFullRenderOfTheStrokeSoFar() {
        setup()
        c.updatePreset(ToolId.MASK, c.maskBrush.copy(size = 18f, hardness = 0.3f, opacity = 0.6f))
        // The first brush stroke on a pixel layer: a new adjustment layer is made on release, so
        // the stroke shows in the preview until then.
        tool.arm(MaskTool.Kind.BRUSH)
        val layers = c.doc.layers.size
        wavy(30f, 70f, 60) { i -> if (i % 15 == 0) assertPreviewIsAFullRender("move $i") }
        assertTrue(tool.preview.isActive)
        assertPreviewIsAFullRender("before release")
        c.pointerUp(ToolPoint(170f, 70f))
        assertEquals(layers + 1, c.doc.layers.size)
        assertEquals("Mask: brush", c.undoManager.undoLabel)
        assertFalse(tool.preview.isActive)
    }

    @Test
    fun anIntersectingBrushPartPreviewsTheWholeMaskThenOnlyItsNewDabs() {
        setup()
        // Tone 1 with a radial; then a new INTERSECT brush part (it changes the whole mask).
        tool.arm(MaskTool.Kind.RADIAL)
        c.pointerDown(ToolPoint(100f, 75f)); c.pointerMove(ToolPoint(120f, 75f)); c.pointerMove(ToolPoint(150f, 75f)); c.pointerUp(ToolPoint(150f, 75f))
        val adj = c.activeLayer
        assertTrue(adj.isAdjustmentLayer)
        c.updatePreset(ToolId.MASK, c.maskBrush.copy(size = 30f, hardness = 0.5f, opacity = 1f))
        tool.newMode = MaskMode.INTERSECT
        tool.arm(MaskTool.Kind.BRUSH)
        wavy(40f, 60f, 40) { i -> if (i == 1 || i % 10 == 0) assertPreviewIsAFullRender("move $i") }
        c.pointerUp(ToolPoint(180f, 60f))
        val spec = adj.maskSpec!!
        assertEquals(2, spec.components.size)
        assertEquals(MaskMode.INTERSECT, (spec.components.last() as BrushMask).mode)
    }

    @Test
    fun anEraseStrokeWithNoBrushPartToEraseFromIsRefused() {
        setup()
        tool.arm(MaskTool.Kind.BRUSH)
        assertFalse("arming a new brush part starts by painting", tool.brushErase)
        tool.brushErase = true
        val layers = c.doc.layers.size
        val steps = c.undoManager.undoCount
        wavy(30f, 70f, 10)
        c.pointerUp(ToolPoint(170f, 70f))
        assertEquals("no invisible adjustment layer", layers, c.doc.layers.size)
        assertEquals("no step", steps, c.undoManager.undoCount)
        assertEquals(MaskTool.ERASE_HINT, c.message)
        assertNull(c.renderOverride)
        assertFalse(tool.preview.isActive)
        // With a brush part selected, the same stroke erases from it.
        tool.brushErase = false
        wavy(30f, 70f, 10)
        c.pointerUp(ToolPoint(170f, 70f))
        val adj = c.activeLayer
        val painted = adj.maskSpec!!.components.single() as BrushMask
        assertEquals(painted.id, tool.selectedId)
        tool.brushErase = true
        wavy(60f, 40f, 10)
        c.pointerUp(ToolPoint(200f, 40f))
        assertEquals("Erase mask", c.undoManager.undoLabel)
        val erased = adj.maskSpec!!.components.single() as BrushMask
        assertEquals(2, erased.strokes.size)
        assertTrue(erased.strokes.last().erase)
    }

    @Test
    fun anAdjustmentLayerWithoutAMaskShowsItsEffectOnlyInsideTheMaskBeingDragged() {
        setup()
        val adj = c.addAdjustmentLayer(invert, null)!!
        assertNull(adj.mask)
        // Everywhere at first.
        assertEquals(INVERTED, composite().getPixel(30, 40))
        assertEquals(INVERTED, composite().getPixel(100, 75))
        tool.arm(MaskTool.Kind.RADIAL)
        c.pointerDown(ToolPoint(100f, 75f))
        c.pointerMove(ToolPoint(115f, 75f))
        c.pointerMove(ToolPoint(140f, 75f))
        // While dragging: only inside the radial (what release will give).
        assertNotNull(c.renderOverride)
        assertEquals("outside, mid-drag", PHOTO, composite().getPixel(30, 40))
        assertEquals("inside, mid-drag", INVERTED, composite().getPixel(100, 75))
        // A second finger cancels the drag: the effect is everywhere again.
        c.pointerCancel()
        assertNull(c.renderOverride)
        assertNull(adj.mask)
        assertEquals(INVERTED, composite().getPixel(30, 40))
        // Dragged to the end: the mask is made and agrees with the preview.
        val steps = c.undoManager.undoCount
        c.pointerDown(ToolPoint(100f, 75f)); c.pointerMove(ToolPoint(115f, 75f)); c.pointerMove(ToolPoint(140f, 75f)); c.pointerUp(ToolPoint(140f, 75f))
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertNotNull(adj.mask)
        assertTrue(adj.maskSpec!!.components.single() is RadialMask)
        assertEquals(PHOTO, composite().getPixel(30, 40))
        assertEquals(INVERTED, composite().getPixel(100, 75))
        c.undo()
        assertNull(adj.mask)
        assertEquals(INVERTED, composite().getPixel(30, 40))
    }

    @Test
    fun aPendingStripMoveIsRecordedBeforeACanvasGesture() {
        setup()
        tool.arm(MaskTool.Kind.RADIAL)
        c.pointerDown(ToolPoint(100f, 75f)); c.pointerMove(ToolPoint(115f, 75f)); c.pointerMove(ToolPoint(140f, 75f)); c.pointerUp(ToolPoint(140f, 75f))
        val adj = c.activeLayer
        val pos = tool.objectPosition!!
        val steps = c.undoManager.undoCount
        // The strip moves the radial (not ended yet).
        pos.setPosition(60f, null)
        assertEquals(60f, (tool.displaySpec!!.components.single() as RadialMask).cx, 1e-3f)
        assertEquals("live: no step yet", steps, c.undoManager.undoCount)
        // A tap on the canvas: the move is recorded first (one step), then the tap deselects.
        c.pointerDown(ToolPoint(10f, 10f)); c.pointerUp(ToolPoint(10f, 10f))
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals("Move mask", c.undoManager.undoLabel)
        assertEquals(60f, (adj.maskSpec!!.components.single() as RadialMask).cx, 1e-3f)
        assertFalse(tool.preview.isActive)
        // Ending it later records nothing more.
        pos.endPositionEdit()
        assertEquals(steps + 1, c.undoManager.undoCount)
        c.undo()
        assertEquals(100f, (adj.maskSpec!!.components.single() as RadialMask).cx, 1e-3f)
    }

    @Test
    fun anEditOfATurnedOffMaskSaysSoOnce() {
        setup()
        tool.arm(MaskTool.Kind.RADIAL)
        c.pointerDown(ToolPoint(100f, 75f)); c.pointerMove(ToolPoint(115f, 75f)); c.pointerMove(ToolPoint(140f, 75f)); c.pointerUp(ToolPoint(140f, 75f))
        val adj = c.activeLayer
        c.message = null
        c.setLayerProps(adj, adj.props().copy(maskEnabled = false))
        // Move the radial by its pin: recorded, and the turned-off mask is pointed out.
        val steps = c.undoManager.undoCount
        c.pointerDown(ToolPoint(100f, 75f)); c.pointerMove(ToolPoint(110f, 75f)); c.pointerMove(ToolPoint(120f, 80f)); c.pointerUp(ToolPoint(120f, 80f))
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals("Move mask", c.undoManager.undoLabel)
        assertTrue("${c.message}", c.message?.contains("turned off") == true)
        // Once.
        c.message = null
        c.pointerDown(ToolPoint(120f, 80f)); c.pointerMove(ToolPoint(130f, 80f)); c.pointerMove(ToolPoint(140f, 85f)); c.pointerUp(ToolPoint(140f, 85f))
        assertEquals(steps + 2, c.undoManager.undoCount)
        assertNull(c.message)
    }

    private companion object {
        const val PHOTO = 0xFF3366AA.toInt()
        const val INVERTED = 0xFFCC9955.toInt()
    }
}
