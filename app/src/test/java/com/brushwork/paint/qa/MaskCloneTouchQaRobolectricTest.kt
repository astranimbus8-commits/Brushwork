package com.brushwork.paint.qa

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.BrushPresetStore
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.masks.AdjustmentEffects
import com.brushwork.paint.masks.MaskSpec
import com.brushwork.paint.masks.MaskSpecs
import com.brushwork.paint.masks.RadialMask
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.Smoke.P
import com.brushwork.paint.tools.LayerToolRules
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.clone.CloneTool
import com.brushwork.paint.tools.mask.MaskTool
import com.brushwork.paint.ui.editor.CanvasView
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.5 final QA with real fingers (MotionEvents into a real [CanvasView] on a 360 dp phone): the
 * clone stamp's long-press, aligned / non-aligned strokes, a second finger that cancels a stroke
 * without undoing anything, two-finger-tap undo; the Masks tool's creating drag, pinches that
 * scale a component only with a finger on it, long-press on a pin to move it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-hdpi")
class MaskCloneTouchQaRobolectricTest {
    private lateinit var activity: ComponentActivity
    private lateinit var c: EditorController
    private lateinit var view: CanvasView
    private lateinit var touch: Smoke.Touch

    @Before
    fun setUp() {
        ShadowLog.clear()
        Smoke.scopeErrors.clear()
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.getSharedPreferences(BrushPresetStore.PREFS_NAME, android.content.Context.MODE_PRIVATE).edit().clear().commit()
        c = Smoke.controller(activity, Smoke.document(400, 300, 2))
        view = CanvasView(activity, c)
        activity.setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        Smoke.pump(100)
        assertTrue(view.width > 0 && view.height > 0)
        touch = Smoke.Touch(view)
        c.tools
    }

    @After
    fun tearDown() {
        Smoke.pump(50)
        Smoke.assertQuiet(c, "end of test")
        c.dispose()
    }

    private fun screen(x: Float, y: Float): Pair<Float, Float> = c.viewTransform.docToScreen(x, y).let { it.x to it.y }

    private fun strokeDoc(vararg pts: Pair<Float, Float>) = touch.stroke(*pts.map { screen(it.first, it.second) }.toTypedArray())

    private fun longPressDoc(x: Float, y: Float, moveTo: Pair<Float, Float>? = null) {
        val s = screen(x, y)
        touch.send(MotionEvent.ACTION_DOWN, P(0, s.first, s.second))
        touch.idle(600)
        var end = s
        if (moveTo != null) {
            end = screen(moveTo.first, moveTo.second)
            for (k in 1..6) {
                touch.idle(16)
                touch.send(MotionEvent.ACTION_MOVE, P(0, s.first + (end.first - s.first) * k / 6f, s.second + (end.second - s.second) * k / 6f))
            }
        }
        touch.idle(16)
        touch.send(MotionEvent.ACTION_UP, P(0, end.first, end.second))
        touch.idle(50)
    }

    private fun fill(l: Float, t: Float, r: Float, b: Float, color: Int) {
        c.editWholeLayer(c.activeLayer, "Seed") { bmp -> Canvas(bmp).drawRect(l, t, r, b, Paint().apply { this.color = color }) }
    }

    // ------------------------------------------------------------------ clone stamp

    @Test
    fun cloneWithFingersLongPressAlignedOffSecondFingerAndTwoFingerUndo() {
        val layer = c.activeLayer
        fill(40f, 40f, 100f, 100f, RED)
        c.selectTool(ToolId.CLONE)
        val tool = c.tools.getValue(ToolId.CLONE) as CloneTool
        val pen = BrushLibrary.clones.first { it.id == "clone_pen" }
        c.updatePreset(ToolId.CLONE, pen.copy(size = 12f, hardness = 1f, pressureSize = false, minSizeRatio = 1f, opacity = 1f, flow = 1f))
        assertTrue(tool.aligned)
        val steps0 = c.undoManager.undoCount

        // No source yet: a stroke paints nothing and says how to set one.
        strokeDoc(300f to 200f, 320f to 200f)
        assertEquals(steps0, c.undoManager.undoCount)
        assertEquals(CloneTool.HINT, c.message)

        // A long-press sets the source (no paint, no step).
        longPressDoc(60f, 60f)
        val src = tool.anchor.source
        assertNotNull("the long-press set the source", src)
        assertEquals(60f, src!!.x, 1.5f); assertEquals(60f, src.y, 1.5f)
        assertEquals(steps0, c.undoManager.undoCount)
        assertEquals("nothing painted at the source", RED, layer.bitmap.getPixel(60, 60))

        // Stroke 1 copies the red square, 140 px to the right.
        strokeDoc(200f to 60f, 215f to 60f)
        assertEquals(steps0 + 1, c.undoManager.undoCount)
        assertEquals(CloneTool.UNDO_LABEL, c.undoManager.undoLabel)
        assertEquals(RED, layer.bitmap.getPixel(207, 60))
        // Stroke 2 (Aligned): the offset is kept, so far to the right it samples empty pixels.
        strokeDoc(300f to 60f, 310f to 60f)
        assertEquals(0, layer.bitmap.getPixel(305, 60))
        // Aligned off: every stroke starts at the source again.
        tool.setAligned(false)
        strokeDoc(300f to 160f, 310f to 160f)
        assertEquals("non-aligned restarts at the source", RED, layer.bitmap.getPixel(305, 160))
        val steps = c.undoManager.undoCount

        // A second finger during a stroke cancels it without a trace, and undoes nothing.
        val before = IntArray(400 * 300).also { layer.bitmap.getPixels(it, 0, 400, 0, 0, 400, 300) }
        val a = screen(200f, 230f)
        touch.send(MotionEvent.ACTION_DOWN, P(0, a.first, a.second))
        for (k in 1..12) { touch.idle(16); touch.send(MotionEvent.ACTION_MOVE, P(0, a.first + k * 4f, a.second)) }
        val a2 = (a.first + 48f) to a.second
        val b = screen(330f, 280f)
        touch.idle(16)
        touch.send(MotionEvent.ACTION_POINTER_DOWN, P(0, a2.first, a2.second), P(1, b.first, b.second), index = 1)
        touch.idle(40)
        touch.send(MotionEvent.ACTION_POINTER_UP, P(0, a2.first, a2.second), P(1, b.first, b.second), index = 1)
        touch.idle(20)
        touch.send(MotionEvent.ACTION_UP, P(0, a2.first, a2.second))
        touch.idle(50)
        assertEquals("no step, nothing undone", steps, c.undoManager.undoCount)
        assertArrayEquals("no pixels", before, IntArray(400 * 300).also { layer.bitmap.getPixels(it, 0, 400, 0, 0, 400, 300) })
        assertNull(c.renderOverride)

        // A two-finger tap undoes the last clone stroke.
        touch.idle(300)
        touch.twoFingerTap(screen(150f, 250f), screen(250f, 250f))
        assertEquals(steps - 1, c.undoManager.undoCount)
        assertEquals(0, layer.bitmap.getPixel(305, 160))
    }

    @Test
    fun cloneIsRefusedOnAdjustmentAndVectorLayersAndTheSourceSurvivesALayerSwitch() {
        val photo = c.activeLayer
        fill(40f, 40f, 100f, 100f, RED)
        c.selectTool(ToolId.CLONE)
        val tool = c.tools.getValue(ToolId.CLONE) as CloneTool
        longPressDoc(60f, 60f)
        assertNotNull(tool.anchor.source)
        val src = tool.anchor.source
        // An adjustment layer above: refused with the adjustment message, nothing recorded.
        val adj = c.addAdjustmentLayer(AdjustmentEffects.defaultSpec(), null)!!
        val steps = c.undoManager.undoCount
        strokeDoc(200f to 60f, 220f to 60f)
        assertEquals(LayerToolRules.ADJUSTMENT_MESSAGE, c.message)
        assertEquals(steps, c.undoManager.undoCount)
        assertEquals("the source stays set across layer switches", src, tool.anchor.source)
        // A vector layer: refused with the pixels message.
        c.addVectorLayer()
        c.message = null
        strokeDoc(200f to 60f, 220f to 60f)
        assertEquals(LayerToolRules.pixelOnlyMessage(ToolId.CLONE), c.message)
        // Back on the photo it clones again.
        c.selectLayer(photo)
        strokeDoc(200f to 60f, 220f to 60f)
        assertEquals(RED, photo.bitmap.getPixel(210, 60))
        assertNotEquals(-1, c.doc.indexOf(adj))
    }

    // ------------------------------------------------------------------ masks

    @Test
    fun maskPinchesScaleTheComponentOnlyWithAFingerOnItAndALongPressOnAPinMovesIt() {
        c.selectTool(ToolId.MASK)
        val tool = c.tools.getValue(ToolId.MASK) as MaskTool
        tool.arm(MaskTool.Kind.RADIAL)
        strokeDoc(200f to 150f, 230f to 150f, 260f to 150f)
        val adj = c.activeLayer
        assertTrue(adj.isAdjustmentLayer)
        fun radial() = adj.maskSpec!!.components.single() as RadialMask
        assertEquals(60f, radial().rx, 1.5f)
        assertNotNull("the new radial is selected", tool.selectedId)
        val steps = c.undoManager.undoCount

        // Both fingers beside the radial (outside its box, close to it): the view zooms.
        val z0 = c.viewTransform.zoom
        touch.idle(300)
        touch.pinch(screen(128f, 150f), screen(272f, 150f), screen(110f, 150f), screen(290f, 150f))
        assertNotEquals("the view zoomed", z0, c.viewTransform.zoom)
        assertEquals("the radial didn't change", 60f, radial().rx, 1.5f)
        assertEquals(steps, c.undoManager.undoCount)

        // One finger on the radial, the other far away: the radial scales (one step), not the view.
        val z1 = c.viewTransform.zoom
        val r0 = radial().rx
        val inside = screen(210f, 150f)
        val far = screen(380f, 290f)
        touch.idle(300)
        touch.pinch(inside, far, (inside.first - (far.first - inside.first) * 0.25f) to (inside.second - (far.second - inside.second) * 0.25f), far)
        assertEquals("the view stayed", z1, c.viewTransform.zoom, 1e-4f)
        assertTrue("the radial grew: $r0 -> ${radial().rx}", radial().rx > r0 * 1.1f)
        assertEquals("Edit mask", c.undoManager.undoLabel)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertArrayEquals(rendered(adj.maskSpec!!), maskPixels())

        // A two-finger tap takes the pinch back.
        touch.idle(300)
        touch.twoFingerTap(screen(60f, 250f), screen(120f, 250f))
        assertEquals(steps, c.undoManager.undoCount)
        assertEquals(r0, radial().rx, 1e-3f)

        // With + Linear armed, a long-press on the R pin moves the radial (nothing is created).
        tool.arm(MaskTool.Kind.LINEAR)
        val cx = radial().cx; val cy = radial().cy
        longPressDoc(cx, cy, moveTo = (cx + 20f) to (cy + 10f))
        assertEquals(1, adj.maskSpec!!.components.size)
        assertEquals(cx + 20f, radial().cx, 1.5f)
        assertEquals(cy + 10f, radial().cy, 1.5f)
        assertEquals("Move mask", c.undoManager.undoLabel)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertNull(tool.armed)
        assertArrayEquals(rendered(adj.maskSpec!!), maskPixels())
    }

    private fun maskPixels() = IntArray(400 * 300).also { c.activeLayer.mask!!.getPixels(it, 0, 400, 0, 0, 400, 300) }
    private fun rendered(spec: MaskSpec) = IntArray(400 * 300).also { MaskSpecs.render(spec, 400, 300, android.graphics.Rect(0, 0, 400, 300), it, 400) }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
    }
}
