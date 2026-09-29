package com.brushwork.paint.smoke

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.SelectionMode
import com.brushwork.paint.smoke.Smoke.P
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.select.LassoTool
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.ui.editor.CanvasView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * The editor without Compose: a real [CanvasView] attached to an activity window and driven with
 * MotionEvents (strokes, erasing, multi-finger taps, pinch, cancelled strokes, long press), the
 * interplay of tools that hold pending work with layer / fill / undo operations, and smart select
 * through the real segmentation service. Controller coroutines run on a real main-thread scope
 * whose uncaught exceptions fail the test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-hdpi")
class EditorRuntimeSmokeTest {
    private lateinit var activity: ComponentActivity
    private lateinit var c: EditorController
    private lateinit var view: CanvasView
    private lateinit var touch: Smoke.Touch

    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()

    @Before
    fun setUp() {
        ShadowLog.clear()
        Smoke.scopeErrors.clear()
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        c = Smoke.controller(activity, Smoke.document(400, 300, 2))
        view = CanvasView(activity, c)
        activity.setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        Smoke.pump(100)
        assertTrue("canvas view laid out: ${view.width}x${view.height}", view.width > 0 && view.height > 0)
        touch = Smoke.Touch(view)
        c.tools // builds the tools, which load the stored presets: set test presets after this
        c.brush = c.brush.copy(size = 8f, opacity = 1f, flow = 1f, hardness = 1f, spacing = 0.1f, taperStart = 0f, taperEnd = 0f, scatter = 0f, grain = 0f)
        c.color = red
    }

    @After
    fun tearDown() {
        Smoke.pump(50)
        Smoke.assertQuiet(c, "end of test")
        c.dispose()
    }

    // ------------------------------------------------------------------ helpers

    private fun screen(x: Float, y: Float): Pair<Float, Float> = c.viewTransform.docToScreen(x, y).let { it.x to it.y }

    private fun strokeDoc(vararg pts: Pair<Float, Float>) {
        touch.idle(200) // never part of the previous gesture
        touch.stroke(*pts.map { screen(it.first, it.second) }.toTypedArray())
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun painted(layer: Layer): Int = pixels(layer.bitmap).count { it ushr 24 != 0 }

    private fun alpha(layer: Layer, x: Int, y: Int) = layer.bitmap.getPixel(x, y) ushr 24

    private fun twoFingerTap(a: Pair<Float, Float>, b: Pair<Float, Float>) { touch.idle(200); touch.twoFingerTap(a, b) }

    private fun threeFingerTap(a: Pair<Float, Float>, b: Pair<Float, Float>, d: Pair<Float, Float>) { touch.idle(200); touch.threeFingerTap(a, b, d) }

    private fun pinch(a0: Pair<Float, Float>, b0: Pair<Float, Float>, a1: Pair<Float, Float>, b1: Pair<Float, Float>) { touch.idle(200); touch.pinch(a0, b0, a1, b1) }

    private fun seed(layer: Layer = c.activeLayer) {
        c.editWholeLayer(layer, "Seed") { b ->
            Canvas(b).drawRect(120f, 90f, 260f, 200f, Paint().apply { color = blue })
        }
    }

    // ================================================================== touch

    @Test
    fun brushStrokeLandsUnderTheFingerAsOneUndoStep() {
        val layer = c.activeLayer
        val other = c.doc.layers[0]
        strokeDoc(100f to 150f, 300f to 150f)
        assertEquals(1, c.undoManager.undoCount)
        assertEquals("Brush", c.undoManager.undoLabel)
        for (x in listOf(100, 150, 200, 250, 300)) {
            assertEquals("stroke at ($x, 150)", 255, alpha(layer, x, 150))
            assertEquals(red, layer.bitmap.getPixel(x, 150))
        }
        assertEquals("nothing above the stroke", 0, alpha(layer, 200, 140))
        assertEquals("nothing below the stroke", 0, alpha(layer, 200, 160))
        assertEquals("nothing before its start", 0, alpha(layer, 90, 150))
        assertEquals("nothing after its end", 0, alpha(layer, 310, 150))
        assertEquals("the inactive layer is untouched", 0, painted(other))
        assertNull(c.renderOverride)

        // Screen <-> document mapping round trip and a dot where the finger taps.
        for ((x, y) in listOf(0f to 0f, 400f to 300f, 37f to 211f)) {
            val s = c.viewTransform.docToScreen(x, y)
            val d = c.viewTransform.screenToDoc(s.x, s.y)
            assertEquals(x, d.x, 0.01f)
            assertEquals(y, d.y, 0.01f)
        }
        val (tx, ty) = screen(37f, 211f)
        touch.idle(200)
        touch.tap(tx, ty)
        assertEquals("a tap paints a dot under the finger", 255, alpha(layer, 37, 211))
        assertEquals(2, c.undoManager.undoCount)
    }

    @Test
    fun strokesStayUnderTheFingerAfterZoomAndRotation() {
        val zoom0 = c.viewTransform.zoom
        val cx = view.width / 2f
        val cy = view.height / 2f
        // Zoom 1.5x, then rotate 90 degrees.
        pinch(cx - 100f to cy, cx + 100f to cy, cx - 150f to cy, cx + 150f to cy)
        assertEquals(zoom0 * 1.5f, c.viewTransform.zoom, 0.01f)
        pinch(cx - 100f to cy, cx + 100f to cy, cx to cy - 100f, cx to cy + 100f)
        assertEquals(90f, Math.abs(c.viewTransform.rotationDeg), 0.5f)
        assertEquals("pinches never paint", 0, painted(c.activeLayer))
        assertEquals("pinches never record undo", 0, c.undoManager.undoCount)
        // A document-horizontal stroke is now vertical on screen; it must still land on y = 150.
        strokeDoc(150f to 150f, 250f to 150f)
        assertEquals(1, c.undoManager.undoCount)
        for (x in listOf(150, 200, 250)) assertEquals("($x, 150)", 255, alpha(c.activeLayer, x, 150))
        assertEquals(0, alpha(c.activeLayer, 200, 165))
    }

    @Test
    fun eraserStrokeErasesAlongTheFinger() {
        val layer = c.activeLayer
        c.fillLayer(layer, red)
        assertEquals(1, c.undoManager.undoCount)
        c.toggleEraser()
        assertEquals(ToolId.ERASER, c.activeToolId)
        c.eraser = c.eraser.copy(size = 10f, opacity = 1f)
        strokeDoc(100f to 150f, 300f to 150f)
        assertEquals(2, c.undoManager.undoCount)
        assertEquals("Eraser", c.undoManager.undoLabel)
        assertEquals(0, alpha(layer, 200, 150))
        assertEquals(red, layer.bitmap.getPixel(200, 100))
        c.toggleEraser()
        assertEquals(ToolId.BRUSH, c.activeToolId)
    }

    @Test
    fun twoFingerTapUndoesAndThreeFingerTapRedoes() {
        val layer = c.activeLayer
        strokeDoc(100f to 150f, 300f to 150f)
        val after = pixels(layer.bitmap)
        twoFingerTap(screen(150f, 100f), screen(250f, 100f))
        assertEquals("undone", 0, painted(layer))
        assertEquals(0, c.undoManager.undoCount)
        assertTrue(c.canRedo)
        threeFingerTap(screen(100f, 100f), screen(200f, 100f), screen(300f, 100f))
        assertTrue("redone", after.contentEquals(pixels(layer.bitmap)))
        assertEquals(1, c.undoManager.undoCount)
        assertFalse(c.canRedo)
    }

    @Test
    fun pinchZoomsAndPansWithoutPainting() {
        val z0 = c.viewTransform.zoom
        val cx = view.width / 2f
        val cy = view.height / 2f
        pinch(cx - 60f to cy, cx + 60f to cy, cx - 120f to cy + 30f, cx + 120f to cy + 30f)
        assertEquals(z0 * 2f, c.viewTransform.zoom, 0.02f)
        assertEquals(0, painted(c.activeLayer))
        assertEquals(0, painted(c.doc.layers[0]))
        assertEquals(0, c.undoManager.undoCount)
        assertFalse(c.canRedo)
    }

    @Test
    fun secondFingerMidStrokeLeavesNoTrace() {
        val layer = c.activeLayer
        // Paint, eraser (on content) and smudge (edits pixels directly) all cancel cleanly.
        c.editWholeLayer(layer, "Seed") { b ->
            Canvas(b).drawRect(0f, 0f, 200f, 300f, Paint().apply { color = red })
            Canvas(b).drawRect(200f, 0f, 400f, 300f, Paint().apply { color = blue })
        }
        val before = pixels(layer.bitmap)
        val undo0 = c.undoManager.undoCount
        for (tool in listOf(ToolId.BRUSH, ToolId.ERASER, ToolId.SMUDGE, ToolId.BLUR)) {
            c.selectTool(tool)
            c.color = 0xFF00FF00.toInt()
            val a = screen(100f, 150f)
            val b = screen(300f, 150f)
            touch.idle(200)
            touch.send(MotionEvent.ACTION_DOWN, P(0, a.first, a.second))
            for (s in 1..8) {
                touch.idle(16)
                touch.send(MotionEvent.ACTION_MOVE, P(0, a.first + (b.first - a.first) * s / 8f, a.second))
            }
            assertTrue("$tool stroke in progress", c.isInteracting)
            val f2 = screen(200f, 250f)
            touch.idle(16)
            touch.send(MotionEvent.ACTION_POINTER_DOWN, P(0, b.first, b.second), P(1, f2.first, f2.second), index = 1)
            assertFalse("$tool stroke cancelled by the second finger", c.isInteracting)
            touch.idle(400)
            touch.send(MotionEvent.ACTION_POINTER_UP, P(0, b.first, b.second), P(1, f2.first, f2.second), index = 1)
            touch.idle(20)
            touch.send(MotionEvent.ACTION_UP, P(0, b.first, b.second))
            touch.idle(50)
            assertTrue("$tool left no pixels behind", before.contentEquals(pixels(layer.bitmap)))
            assertEquals("$tool left no undo step", undo0, c.undoManager.undoCount)
            assertNull("$tool left no preview", c.renderOverride)
            Smoke.assertQuiet(c, "$tool cancelled")
        }
    }

    @Test
    fun longPressSelectsACurvePointAndIsHarmlessForTheBrush() {
        // Brush: holding still paints one dot and nothing else happens.
        val (bx, by) = screen(60f, 60f)
        touch.idle(200)
        touch.send(MotionEvent.ACTION_DOWN, P(0, bx, by))
        touch.idle(700)
        touch.send(MotionEvent.ACTION_UP, P(0, bx, by))
        touch.idle(50)
        assertEquals(1, c.undoManager.undoCount)
        assertEquals(255, alpha(c.activeLayer, 60, 60))

        // Curve: long-pressing an anchor selects it (and the finger then does nothing else).
        c.selectTool(ToolId.CURVE)
        val curve = c.tools.getValue(ToolId.CURVE) as CurveTool
        for (p in listOf(Vec2(100f, 200f), Vec2(200f, 120f), Vec2(300f, 200f))) curve.addAnchor(p)
        curve.deselect()
        assertEquals(-1, curve.selected)
        val (ax, ay) = screen(200f, 120f)
        touch.idle(200)
        touch.send(MotionEvent.ACTION_DOWN, P(0, ax, ay))
        touch.idle(700)
        assertEquals("long press selected the anchor", 1, curve.selected)
        touch.send(MotionEvent.ACTION_MOVE, P(0, ax + 40f, ay + 40f))
        touch.idle(20)
        touch.send(MotionEvent.ACTION_UP, P(0, ax + 40f, ay + 40f))
        touch.idle(50)
        assertEquals("the rest of the gesture did not drag the point", Vec2(200f, 120f), curve.anchors[1].pos)
        curve.discard()
    }

    // ================================================================== tool interplay

    @Test
    fun pendingShapeIsCommittedBeforeFillAndUndoesInOrder() {
        val layer = c.activeLayer
        c.selectTool(ToolId.SHAPE)
        val shape = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        assertTrue(shape.ensurePending())
        assertNotNull("pending shape preview", c.renderOverride)
        c.fillLayer(layer, blue)
        Smoke.pump(50)
        assertFalse("fill committed the shape first", shape.hasPendingWork)
        assertEquals(listOf("Shape", "Fill"), labels())
        Smoke.assertQuiet(c, "shape + fill")
        c.undo()
        assertEquals("undo reverts the fill only", listOf("Shape"), labels())
        assertTrue("the shape stays", painted(layer) > 0)
        c.undo()
        assertEquals(0, painted(layer))
        Smoke.assertQuiet(c, "shape undone")
    }

    @Test
    fun pendingShapeIsCommittedOnItsLayerWhenAnotherLayerIsSelected() {
        val top = c.doc.layers[1]
        val bottom = c.doc.layers[0]
        c.selectTool(ToolId.SHAPE)
        val shape = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        assertTrue(shape.ensurePending())
        c.selectLayer(bottom)
        assertSame(bottom, c.activeLayer)
        assertFalse(shape.hasPendingWork)
        assertTrue("shape landed on the layer it was drawn on", painted(top) > 0)
        assertEquals(0, painted(bottom))
        Smoke.assertQuiet(c, "shape + select layer")
    }

    @Test
    fun undoDiscardsPendingWorkBeforeTouchingHistory() {
        strokeDoc(100f to 50f, 300f to 50f)
        c.selectTool(ToolId.SHAPE)
        val shape = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        assertTrue(shape.ensurePending())
        c.undo()
        assertFalse(shape.hasPendingWork)
        assertEquals("the stroke is still there", listOf("Brush"), labels())
        assertNull(c.renderOverride)
        // Redo is refused while work is pending.
        c.undo()
        assertTrue(c.canRedo)
        assertTrue(shape.ensurePending())
        c.redo()
        assertTrue("redo waits for the pending shape", c.canRedo)
        c.selectTool(ToolId.BRUSH)
        assertEquals(listOf("Shape"), labels())
        assertFalse("committing new work clears the redo stack", c.canRedo)
    }

    @Test
    fun pendingCurveAndPolylineCommitOnToolSwitchAndFill() {
        for (id in listOf(ToolId.CURVE, ToolId.POLYLINE)) {
            c.selectTool(id)
            val curve = c.tools.getValue(id) as CurveTool
            curve.update { it.copy(stroke = com.brushwork.paint.tools.vector.CurveStroke.PLAIN) }
            for (p in listOf(Vec2(50f, 50f), Vec2(200f, 250f), Vec2(350f, 50f))) curve.addAnchor(p)
            assertTrue(curve.hasPendingWork)
            val n = c.undoManager.undoCount
            c.selectTool(ToolId.BRUSH)
            assertFalse("$id committed on tool switch", curve.hasPendingWork)
            assertEquals(n + 1, c.undoManager.undoCount)
            Smoke.assertQuiet(c, "$id + tool switch")

            c.selectTool(id)
            for (p in listOf(Vec2(60f, 60f), Vec2(210f, 240f))) curve.addAnchor(p)
            c.fillLayer(c.activeLayer, blue)
            assertFalse(curve.hasPendingWork)
            assertEquals("Fill", c.undoManager.undoLabel)
            assertEquals(n + 3, c.undoManager.undoCount)
            Smoke.assertQuiet(c, "$id + fill")
            c.selectTool(ToolId.BRUSH)
        }
    }

    @Test
    fun pendingTextBecomesALayerAndTheRightLayerIsSelected() {
        val bottom = c.doc.layers[0]
        c.selectTool(ToolId.TEXT)
        val text = c.tools.getValue(ToolId.TEXT) as TextTool
        text.startTextAt(200f, 150f)
        text.setText("Hello")
        text.confirmEditor()
        assertTrue(text.hasPendingWork)
        c.selectLayer(bottom)
        assertEquals("text committed as a new layer", 3, c.doc.layers.size)
        assertSame("the layer the user picked is active", bottom, c.activeLayer)
        assertFalse(text.hasPendingWork)
        Smoke.assertQuiet(c, "text + select layer")
        // Text pending, then fill: the text layer is added first, the fill targets the chosen layer.
        text.startTextAt(100f, 100f)
        text.setText("World")
        text.confirmEditor()
        c.fillLayer(bottom, red)
        assertEquals(4, c.doc.layers.size)
        assertEquals(red, bottom.bitmap.getPixel(5, 5))
        Smoke.assertQuiet(c, "text + fill")
    }

    @Test
    fun movedTransformIsCommittedBeforeFillAsTwoUndoSteps() {
        val layer = c.activeLayer
        seed(layer)
        val seeded = c.undoManager.undoCount
        c.selectTool(ToolId.TRANSFORM)
        Smoke.pump(100)
        val tr = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        assertTrue("transform lifted the content", tr.hasPendingWork)
        tr.moveBy(40f, 20f)
        c.fillLayer(layer, red)
        Smoke.pump(100)
        assertEquals(listOf("Seed", "Transform", "Fill"), labels().takeLast(3))
        assertEquals(seeded + 2, c.undoManager.undoCount)
        Smoke.assertQuiet(c, "transform + fill")

        c.undo()
        Smoke.pump(100)
        assertEquals("first undo reverts the fill", "Transform", c.undoManager.undoLabel)
        assertEquals(blue, layer.bitmap.getPixel(250, 205))
        assertEquals("moved content: the old corner is empty", 0, layer.bitmap.getPixel(125, 95))
        c.undo()
        Smoke.pump(100)
        assertEquals("second undo reverts the move", "Seed", c.undoManager.undoLabel)
        assertEquals(blue, layer.bitmap.getPixel(125, 95))
        Smoke.assertQuiet(c, "transform undone")
    }

    @Test
    fun pendingTransformSurvivesLayerSwitchAndDeleteAndPlacementDiscardLeavesNoLayer() {
        val top = c.doc.layers[1]
        val bottom = c.doc.layers[0]
        seed(top)
        seed(bottom)
        c.selectTool(ToolId.TRANSFORM)
        Smoke.pump(100)
        val tr = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        tr.moveBy(10f, 0f)
        c.selectLayer(bottom)
        Smoke.pump(100)
        assertEquals("the move was baked into the top layer", blue, top.bitmap.getPixel(265, 150))
        assertTrue("the bottom layer is lifted now", tr.hasPendingWork)
        tr.moveBy(0f, 30f)
        c.deleteLayer(bottom)
        Smoke.pump(100)
        assertEquals(1, c.doc.layers.size)
        assertSame(top, c.activeLayer)
        Smoke.assertQuiet(c, "transform + delete layer")

        // An imported picture being placed, then undo: the placement and its layer disappear.
        val labelsBefore = labels()
        val pic = Bitmap.createBitmap(50, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF00FF00.toInt()) }
        c.importImageAsLayer(pic, "Picture")
        Smoke.pump(100)
        assertEquals(2, c.doc.layers.size)
        assertTrue(tr.isPlacement)
        c.undo()
        Smoke.pump(100)
        assertEquals("the empty placement layer is gone", 1, c.doc.layers.size)
        assertEquals(labelsBefore, labels())
        Smoke.assertQuiet(c, "placement discarded")
    }

    @Test
    fun lassoPolygonInterplay() {
        c.selectTool(ToolId.LASSO)
        val lasso = c.tools.getValue(ToolId.LASSO) as LassoTool
        lasso.setPolygonMode(true)
        fun vertex(x: Float, y: Float) { c.pointerDown(ToolPoint(x, y)); c.pointerUp(ToolPoint(x, y)) }
        vertex(50f, 50f); vertex(250f, 60f)
        assertTrue(lasso.hasPendingWork)
        // Two points can't make a polygon: switching tools just drops them.
        c.selectTool(ToolId.BRUSH)
        Smoke.pump(100)
        assertFalse(lasso.hasPendingWork)
        assertNull(c.selection)
        c.selectTool(ToolId.LASSO)
        vertex(50f, 50f); vertex(250f, 60f); vertex(200f, 220f)
        c.fillLayer(c.activeLayer, red)
        assertTrue("the polygon became the selection before the fill", Smoke.pumpUntil { c.selection != null })
        Smoke.pump(100)
        Smoke.assertQuiet(c, "lasso + fill")
        c.selectTool(ToolId.LASSO)
        vertex(10f, 10f)
        c.undo()
        assertFalse("undo drops the unfinished polygon", lasso.hasPendingWork)
        lasso.setPolygonMode(false)
    }

    // ================================================================== helpers

    private fun labels(): List<String> {
        // The undo stack is private; read it through repeated undo/redo would change state, so use reflection.
        val f = c.undoManager.javaClass.getDeclaredField("undoStack").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return (f.get(c.undoManager) as kotlin.collections.ArrayDeque<com.brushwork.paint.engine.UndoAction>).map { it.label }
    }
}
