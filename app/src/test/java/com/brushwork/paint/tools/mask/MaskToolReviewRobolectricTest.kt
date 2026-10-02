package com.brushwork.paint.tools.mask

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.AdjustmentStage
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.CompositeTarget
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.masks.AdjustmentEffects
import com.brushwork.paint.masks.BrushMask
import com.brushwork.paint.masks.MaskEdits
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

/**
 * v1.5 A5 review: the Adjust session's step after an undo, pins that don't steal creating or
 * painting drags (tap selects, long press moves), the reduced overlay copy, the live stroke's
 * coverage hint and the guarded "Delete mask".
 */
@RunWith(RobolectricTestRunner::class)
class MaskToolReviewRobolectricTest {
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
        val doc = Document("r", "r", w, h)
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

    private fun stroke(x0: Float, y0: Float, x1: Float, y1: Float, n: Int = 20) {
        c.pointerDown(ToolPoint(x0, y0))
        for (i in 1..n) c.pointerMove(ToolPoint(x0 + (x1 - x0) * i / n, y0 + (y1 - y0) * i / n))
        c.pointerUp(ToolPoint(x1, y1))
    }

    private fun tap(x: Float, y: Float) {
        c.pointerDown(ToolPoint(x, y)); c.pointerUp(ToolPoint(x, y))
    }

    private fun maskPixels(l: Layer) = IntArray(w * h).also { l.mask!!.getPixels(it, 0, w, 0, 0, w, h) }

    private fun rendered(spec: MaskSpec) = IntArray(w * h).also { MaskSpecs.render(spec, w, h, Rect(0, 0, w, h), it, w) }

    /** A radial component centred at (100, 75) with radius 40 on a new "Tone 1" (selected). */
    private fun radialLayer(): Layer {
        tool.arm(MaskTool.Kind.RADIAL)
        drag(100f to 75f, 110f to 75f, 140f to 75f)
        return c.activeLayer
    }

    private val invert get() = AdjustmentEffects.defaultSpec(FilterRegistry.byId("adjust.invert"))

    @Test
    fun anAdjustSessionAfterAnUndoRecordsNoStepOfItsOwnAndStartsFromTheLayer() {
        setup()
        val adj = radialLayer()
        val start = adj.adjustment
        val edit = tool.adjustmentEdit(adj)
        val n = c.undoManager.undoCount
        edit.preview(invert, 0.6f)
        // Undo records the pending change and takes it back.
        c.undo()
        assertEquals(n, c.undoManager.undoCount)
        assertEquals(start, adj.adjustment)
        assertTrue(c.undoManager.canRedo)
        // The sheet closing (or the tool going) afterwards must not record anything: the redo stays.
        edit.flush()
        tool.flushAdjustment()
        assertEquals(n, c.undoManager.undoCount)
        assertTrue("redo is still available", c.undoManager.canRedo)
        c.redo()
        assertEquals(invert, adj.adjustment)
        assertEquals(0.6f, adj.opacity, 0f)
        c.undo()
        // A new change starts from what the layer shows now (the undone values are gone).
        edit.preview(invert, 0.4f)
        edit.flush()
        assertEquals(n + 1, c.undoManager.undoCount)
        c.undo()
        assertEquals(start, adj.adjustment)
        assertEquals(1f, adj.opacity, 0f)
        // Undoing the new adjustment layer itself, then closing the sheet: nothing is recorded.
        c.undo()
        assertEquals(-1, c.doc.indexOf(adj))
        val m = c.undoManager.undoCount
        edit.flush()
        assertEquals(m, c.undoManager.undoCount)
        assertTrue(c.undoManager.canRedo)
    }

    @Test
    fun anArmedBrushDragThatStartsOnAPinPaintsAndLeavesThatComponentAlone() {
        setup()
        val adj = radialLayer()
        val radial = adj.maskSpec!!.components.single() as RadialMask
        c.updatePreset(ToolId.MASK, c.maskBrush.copy(size = 20f, hardness = 0.5f, opacity = 1f))
        tool.arm(MaskTool.Kind.BRUSH)
        val steps = c.undoManager.undoCount
        // The stroke starts right on the radial's centre pin.
        stroke(100f, 75f, 170f, 120f)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals("Mask: brush", c.undoManager.undoLabel)
        val spec = adj.maskSpec!!
        assertEquals("the radial didn't move", radial, spec.components.first())
        val brush = spec.components.last() as BrushMask
        assertEquals(100f, brush.strokes.single().points.x[0], 0f)
        assertEquals(75f, brush.strokes.single().points.y[0], 0f)
        assertArrayEquals(rendered(spec), maskPixels(adj))
        assertNull(c.renderOverride)
    }

    @Test
    fun anArmedLinearDragThatStartsOnAPinCreatesALinearComponent() {
        setup()
        val adj = radialLayer()
        val radial = adj.maskSpec!!.components.single()
        tool.arm(MaskTool.Kind.LINEAR)
        drag(100f to 75f, 120f to 75f, 160f to 75f)
        val spec = adj.maskSpec!!
        assertEquals(2, spec.components.size)
        assertEquals(radial, spec.components.first())
        assertEquals("Mask: linear", c.undoManager.undoLabel)
    }

    @Test
    fun aTapOnAPinWhileArmedSelectsItsComponent() {
        setup()
        val adj = radialLayer()
        val id = adj.maskSpec!!.components.single().id
        tool.select(null)
        tool.arm(MaskTool.Kind.LINEAR)
        val steps = c.undoManager.undoCount
        tap(101f, 76f)
        assertEquals(id, tool.selectedId)
        assertNull("the armed kind is put away", tool.armed)
        assertEquals(steps, c.undoManager.undoCount)
        assertEquals(1, adj.maskSpec!!.components.size)
    }

    @Test
    fun paintingASelectedBrushComponentFromItsOwnPinPaintsAndALongPressMovesIt() {
        setup()
        val adj = radialLayer()
        c.updatePreset(ToolId.MASK, c.maskBrush.copy(size = 16f, hardness = 0.5f, opacity = 1f))
        tool.arm(MaskTool.Kind.BRUSH)
        stroke(20f, 120f, 60f, 120f)
        val first = adj.maskSpec!!.components.last() as BrushMask
        assertEquals(first.id, tool.selectedId)
        val pin = MaskGeometry.pin(first)!!
        // A stroke from the brush component's own pin adds to it (it doesn't move it).
        stroke(pin.first, pin.second, pin.first + 30f, pin.second - 30f)
        assertEquals("Brush mask", c.undoManager.undoLabel)
        val painted = adj.maskSpec!!.components.last() as BrushMask
        assertEquals(2, painted.strokes.size)
        assertSame("the first stroke stays where it was", first.strokes[0], painted.strokes[0])
        // Held still on the pin: the finger moves the component.
        val pin2 = MaskGeometry.pin(painted)!!
        val steps = c.undoManager.undoCount
        c.pointerDown(ToolPoint(pin2.first, pin2.second))
        assertTrue(c.pointerLongPress(ToolPoint(pin2.first, pin2.second)))
        c.pointerMove(ToolPoint(pin2.first + 10f, pin2.second + 5f))
        c.pointerMove(ToolPoint(pin2.first + 20f, pin2.second + 10f))
        c.pointerUp(ToolPoint(pin2.first + 20f, pin2.second + 10f))
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals("Move mask", c.undoManager.undoLabel)
        val moved = adj.maskSpec!!.components.last() as BrushMask
        assertEquals(2, moved.strokes.size)
        assertEquals(painted.strokes[0].points.x[0] + 20f, moved.strokes[0].points.x[0], 1e-3f)
        assertEquals(painted.strokes[0].points.y[0] + 10f, moved.strokes[0].points.y[0], 1e-3f)
        assertArrayEquals(rendered(adj.maskSpec!!), maskPixels(adj))
        // A tap on the selected brush component's pin ends painting it.
        val pin3 = MaskGeometry.pin(moved)!!
        tap(pin3.first, pin3.second)
        assertNull(tool.selectedId)
    }

    @Test
    fun aLiveStrokeOnAnAdjustmentMaskShowsTheEffectWhereItPaints() {
        setup()
        val adj = radialLayer()
        // An Invert effect, so the stroke's effect shows.
        tool.adjustmentEdit(adj).preview(invert)
        tool.flushAdjustment()
        c.updatePreset(ToolId.MASK, c.maskBrush.copy(size = 24f, hardness = 1f, opacity = 1f))
        tool.arm(MaskTool.Kind.BRUSH)
        // (30, 40) is outside the radial: not inverted yet.
        val target = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        fun composite(): Bitmap {
            target.eraseColor(0)
            c.compositor.drawDocument(Canvas(target), null, useOverrides = true, target = CompositeTarget.identity(target))
            return target
        }
        assertEquals(0xFF3366AA.toInt(), composite().getPixel(30, 40))
        c.pointerDown(ToolPoint(24f, 40f))
        for (i in 1..10) c.pointerMove(ToolPoint(24f + i * 2f, 40f))
        assertNotNull("painted live through the override", c.renderOverride)
        // Mid-stroke, the composite already inverts under the stroke (the override's coverage
        // includes the stroke so far) and still leaves far-away pixels alone.
        assertEquals(0xFFCC9955.toInt(), composite().getPixel(30, 40))
        assertEquals(0xFF3366AA.toInt(), composite().getPixel(170, 125))
        c.pointerUp(ToolPoint(44f, 40f))
        assertEquals(0xFFCC9955.toInt(), composite().getPixel(30, 40))
        assertArrayEquals(rendered(adj.maskSpec!!), maskPixels(adj))
    }

    @Test
    fun deleteMaskFromTheSheetIsRefusedOnALockedLayer() {
        setup()
        val adj = radialLayer()
        c.setLayerProps(adj, adj.props().copy(locked = true))
        val steps = c.undoManager.undoCount
        assertFalse(MaskEdits.deleteMask(c, adj))
        assertNotNull(adj.mask)
        assertNotNull(adj.maskSpec)
        assertEquals(steps, c.undoManager.undoCount)
        c.setLayerProps(adj, adj.props().copy(locked = false))
        val n = c.undoManager.undoCount
        assertTrue(MaskEdits.deleteMask(c, adj))
        assertNull(adj.mask)
        assertNull(adj.maskSpec)
        assertEquals(n + 1, c.undoManager.undoCount)
        c.undo()
        assertNotNull(adj.mask)
        assertNotNull(adj.maskSpec)
    }

    @Test
    fun theOverlayCopyIsSmallAndFollowsLiveStrokes() {
        // The user's canvas: the copy stays near 1.2 MP (not the 2.6 MP mask).
        val s = MaskTint.scaleFor(1080, 2408)
        assertTrue(s < 1f)
        assertTrue(1080L * 2408 * s * s <= MaskTint.MAX_PIXELS * 1.01)
        assertEquals(1f, MaskTint.scaleFor(1000, 1000), 0f)
        // A mask just above the limit gets a reduced copy; a patched area equals a rebuild.
        val mw = 1200; val mh = 1100
        val doc = Document("t", "t", mw, mh)
        val layer = Layer(doc.newLayerId(), "L", BitmapUtils.createLayerBitmap(mw, mh))
        val mask = BitmapUtils.createMaskBitmap(mw, mh, 0xFF000000.toInt())
        layer.mask = mask
        val tint = MaskTint()
        val copy = tint.of(layer, mask)!!
        assertTrue(copy !== mask)
        assertTrue(copy.width < mw)
        assertSame("cached while the version stays", copy, tint.of(layer, mask))
        // A live stroke paints a region: the copy is patched there.
        val region = Rect(300, 200, 420, 330)
        Canvas(mask).drawCircle(360f, 265f, 50f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = -1 })
        tint.refresh(mask, region)
        val fresh = MaskTint().of(layer, mask)!!
        fun px(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        assertArrayEquals("patched = rebuilt", px(fresh), px(tint.of(layer, mask)!!))
        // Committed: the version moves on and the copy is adopted without a rebuild.
        val before = layer.contentVersion
        layer.markChanged()
        tint.adopt(layer, mask, before)
        assertSame(copy, tint.of(layer, mask))
        // Another change (an undo) makes it rebuild.
        mask.eraseColor(0xFF000000.toInt())
        layer.markChanged()
        val again = tint.of(layer, mask)!!
        assertTrue(px(again).all { it and 0xFFFFFF == 0 })
        tint.release()
    }
}
