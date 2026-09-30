package com.brushwork.paint.smoke

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.BrushworkApp
import com.brushwork.paint.EditorController
import com.brushwork.paint.EditorSession
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.CanvasOps
import com.brushwork.paint.segmentation.SegTestImages
import com.brushwork.paint.segmentation.SmartTarget
import com.brushwork.paint.storage.NewCanvasSpec
import com.brushwork.paint.tools.select.SelectionEdits
import kotlinx.coroutines.runBlocking
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
        // Brush: holding still turns the gesture into the eyedropper: the dot is taken back, no
        // undo step is recorded and the brush stays the current tool.
        val (bx, by) = screen(60f, 60f)
        val undoBefore = c.undoManager.undoCount
        touch.idle(200)
        touch.send(MotionEvent.ACTION_DOWN, P(0, bx, by))
        touch.idle(700)
        assertTrue("long press started color picking", c.holdPicking)
        touch.send(MotionEvent.ACTION_UP, P(0, bx, by))
        touch.idle(50)
        assertFalse(c.holdPicking)
        assertEquals(undoBefore, c.undoManager.undoCount)
        assertEquals(0, alpha(c.activeLayer, 60, 60))
        assertEquals(ToolId.BRUSH, c.activeToolId)

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

    // ================================================================== every tool, every layer state

    private fun allPixels(): List<IntArray> = c.doc.layers.flatMap { l -> listOfNotNull(pixels(l.bitmap), l.mask?.let { pixels(it) }) }

    private fun settleBusy(where: String) {
        assertTrue("$where: busy operation finished", Smoke.pumpUntil(20_000) { c.busyMessage == null })
        Smoke.pump(60)
    }

    /** Taps, drags and a long press with [id] on the canvas, then applies any pending work. */
    private fun exercise(id: ToolId, state: String) {
        val where = "$id / $state"
        Smoke.step(where)
        c.selectTool(id)
        Smoke.pump(60)
        touch.idle(200); touch.tap(screen(200f, 150f).first, screen(200f, 150f).second)
        settleBusy("$where tap")
        touch.idle(200); touch.tap(screen(90f, 80f).first, screen(90f, 80f).second)
        settleBusy("$where tap 2")
        touch.idle(200); touch.stroke(screen(60f, 60f), screen(340f, 240f))
        settleBusy("$where drag")
        touch.idle(200); touch.stroke(screen(200f, 150f), screen(230f, 160f))
        settleBusy("$where drag 2")
        val (lx, ly) = screen(300f, 70f)
        touch.idle(200)
        touch.send(MotionEvent.ACTION_DOWN, P(0, lx, ly))
        touch.idle(700)
        touch.send(MotionEvent.ACTION_UP, P(0, lx, ly))
        settleBusy("$where long press")
        val tool = c.currentTool
        if (tool.hasPendingWork) {
            tool.commit()
            settleBusy("$where commit")
        }
        Smoke.assertQuiet(c, where)
    }

    @Test
    fun everyToolSurvivesTouchInEveryLayerState() {
        val top = c.doc.layers[1]
        val bottom = c.doc.layers[0]
        bottom.bitmap.eraseColor(-1)
        seed(top)
        c.undoManager.clear()
        c.selectTool(ToolId.BRUSH)
        val original = allPixels()
        val states = listOf<Pair<String, () -> Unit>>(
            "plain" to {},
            "selection" to { c.setSelection(com.brushwork.paint.model.Selection.fromBytes(ByteArray(400 * 300) { i -> if (i % 400 in 100..299 && i / 400 in 50..249) -1 else 0 }, 400, 300)) },
            "mask" to { c.deselect(); c.addMask(top, fromSelection = false) },
            "alpha locked" to { c.setEditingMask(top, false); c.toggleAlphaLock(top) },
            "hidden" to { c.toggleAlphaLock(top); c.toggleVisibility(top) },
            "locked" to { c.toggleVisibility(top); c.toggleLock(top) },
            "grayscale" to { c.toggleLock(top); c.doc.colorMode = com.brushwork.paint.model.ColorMode.GRAYSCALE; c.onDocumentGeometryChanged() },
            "bottom layer" to { c.doc.colorMode = com.brushwork.paint.model.ColorMode.RGB; c.onDocumentGeometryChanged(); c.selectLayer(bottom) },
        )
        val leaks = mutableListOf<String>()
        for ((state, setUp) in states) {
            c.selectTool(ToolId.BRUSH)
            setUp()
            Smoke.pump(60)
            for (id in ToolId.entries) {
                // Whatever the tool did must be undoable back to exactly these pixels. (History
                // is capped at 150 steps, so start each tool from an empty one.)
                c.undoManager.clear()
                val n0 = 0
                val p0 = allPixels()
                val layers0 = c.doc.layers.size
                exercise(id, state)
                c.selectTool(ToolId.BRUSH)
                var g = 100
                while (c.undoManager.undoCount > n0 && g-- > 0) { c.undo(); settleBusy("undo $id / $state") }
                assertEquals("$id / $state: layers after undo", layers0, c.doc.layers.size)
                val p1 = allPixels()
                for (k in p0.indices) {
                    val a = p0[k]; val b = p1.getOrNull(k)
                    if (b == null || !a.contentEquals(b)) {
                        val i = a.indices.firstOrNull { b == null || a[it] != b[it] } ?: -1
                        leaks += "$id / $state: bitmap $k differs after undo, first at (${i % 400}, ${i / 400}): " +
                            "${Integer.toHexString(a.getOrElse(i) { 0 })} -> ${Integer.toHexString(b?.getOrElse(i) { 0 } ?: 0)}, " +
                            "${a.indices.count { b == null || a[it] != b[it] }} px"
                    }
                }
                // Redo it so the next tools work on real content again.
                while (c.canRedo && g-- > 0) { c.redo(); settleBusy("redo $id / $state") }
            }
        }
        assertTrue("pixels changed outside undo:\n" + leaks.joinToString("\n"), leaks.isEmpty())
        assertEquals(2, c.doc.layers.size)
        assertTrue("the tools did paint", original.indices.any { !original[it].contentEquals(allPixels().getOrNull(it) ?: IntArray(0)) })
        // Failures that are only logged (swallowed exceptions) count as failures here.
        val errors = Smoke.errorLogs()
        assertTrue("error logs:\n" + errors.joinToString("\n"), errors.isEmpty())
    }

    // ================================================================== canvas operations

    /** Leaves [kind] of pending tool work (or none) on the active layer; returns its undo label. */
    private fun pending(kind: String): String? {
        when (kind) {
            "shape" -> {
                c.selectTool(ToolId.SHAPE)
                assertTrue((c.tools.getValue(ToolId.SHAPE) as ShapeTool).ensurePending())
                return "Shape"
            }
            "moved transform" -> {
                c.selectTool(ToolId.TRANSFORM)
                Smoke.pump(100)
                val tr = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
                assertTrue("lifted", tr.hasPendingWork)
                tr.moveBy(15f, 10f)
                return "Transform"
            }
            "untouched transform" -> {
                c.selectTool(ToolId.TRANSFORM)
                Smoke.pump(100)
                assertTrue("lifted", c.currentTool.hasPendingWork)
                return null
            }
            "text" -> {
                c.selectTool(ToolId.TEXT)
                val text = c.tools.getValue(ToolId.TEXT) as TextTool
                text.startTextAt(150f, 120f)
                text.setText("Canvas")
                text.confirmEditor()
                return "Add text"
            }
            "curve" -> {
                c.selectTool(ToolId.CURVE)
                val curve = c.tools.getValue(ToolId.CURVE) as CurveTool
                curve.update { it.copy(stroke = com.brushwork.paint.tools.vector.CurveStroke.PLAIN) }
                for (p in listOf(Vec2(30f, 30f), Vec2(200f, 200f), Vec2(350f, 40f))) curve.addAnchor(p)
                return "Curve"
            }
        }
        c.selectTool(ToolId.BRUSH)
        return null
    }

    @Test
    fun canvasOperationsCommitPendingWorkFirstAndUndoCleanly() {
        val top = c.doc.layers[1]
        val bottom = c.doc.layers[0]
        bottom.bitmap.eraseColor(-1)
        seed(top)
        c.addMask(top, fromSelection = false)
        c.setEditingMask(top, false)
        c.toggleAlphaLock(bottom)
        c.updateRuler(c.ruler.copy(enabled = true))
        c.updateGrid(c.grid.copy(enabled = true))
        c.undoManager.clear()
        val probe = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val ops = listOf<Triple<String, String, () -> Boolean>>(
            Triple("Resize image", "Resize image") { CanvasOps.applyResizeImage(c, 200, 150, 150f, com.brushwork.paint.engine.Resample.entries.first()) },
            Triple("Canvas size", "Canvas size") { CanvasOps.applyResizeCanvas(c, 500, 360, 1, 1, null) },
            Triple("Crop", "Crop") { CanvasOps.applyCrop(c, android.graphics.Rect(20, 10, 300, 250)) },
            Triple("Crop to selection", "Crop to selection") { CanvasOps.applyCropToSelection(c) },
            Triple("Trim", "Trim") { CanvasOps.applyTrim(c) },
            Triple("Rotate CW", com.brushwork.paint.engine.CanvasRotation.CW_90.label) { CanvasOps.applyRotate(c, com.brushwork.paint.engine.CanvasRotation.CW_90) },
            Triple("Rotate 180", com.brushwork.paint.engine.CanvasRotation.R_180.label) { CanvasOps.applyRotate(c, com.brushwork.paint.engine.CanvasRotation.R_180) },
            Triple("Flip", "Flip canvas horizontally") { CanvasOps.applyFlip(c, horizontal = true) },
            Triple("Resolution", "Resolution") { CanvasOps.applyDpi(c, 72f) },
            Triple("Grayscale", "Color mode: Grayscale") { CanvasOps.applyColorMode(c, com.brushwork.paint.model.ColorMode.GRAYSCALE, 128, false) },
            Triple("Monochrome", "Color mode: Monochrome (1-bit)") { CanvasOps.applyColorMode(c, com.brushwork.paint.model.ColorMode.MONOCHROME, 128, true) },
        )
        val problems = mutableListOf<String>()
        for ((name, label, op) in ops) {
            for (kind in listOf("none", "shape", "moved transform", "untouched transform", "text", "curve")) {
                val where = "$name with $kind"
                Smoke.step(where)
                c.deselect()
                when (name) {
                    // A selection to crop to, made before the pending work like a user would.
                    "Crop to selection" -> c.setSelection(com.brushwork.paint.model.Selection.fromBytes(
                        ByteArray(c.doc.width * c.doc.height) { i -> if (i % c.doc.width in 50..249 && i / c.doc.width in 40..199) -1 else 0 },
                        c.doc.width, c.doc.height,
                    ))
                    // Transparent edges to trim: clear the white background.
                    "Trim" -> if (bottom.bitmap.getPixel(0, 0) != 0) c.editWholeLayer(bottom, "Clear") { it.eraseColor(0) }
                }
                c.undoManager.clear()
                val w0 = c.doc.width
                val h0 = c.doc.height
                val pixels0 = allPixels()
                val layers0 = c.doc.layers.size
                val pendingLabel = pending(kind)
                c.message = null
                assertTrue("$where started", op())
                settleBusy(where)
                Smoke.assertQuiet(c, where)
                if (c.message != null) problems += "$where: message \"${c.message}\""
                val got = labels()
                // Resolution only changes metadata: pending work stays editable (and is harmless).
                val want = listOfNotNull(if (name == "Resolution") null else pendingLabel, label)
                if (got != want) problems += "$where: history $got, expected $want"
                view.draw(Canvas(probe)) // the view refits and draws the new geometry
                // Undo everything: the original canvas comes back exactly.
                var g = 20
                while (c.canUndo && g-- > 0) { c.undo(); settleBusy("$where undo") }
                Smoke.assertQuiet(c, "$where undone")
                if (c.currentTool.hasPendingWork) { c.currentTool.discard(); Smoke.pump(60) }
                if (c.doc.width != w0 || c.doc.height != h0) problems += "$where: size after undo ${c.doc.width}x${c.doc.height}"
                if (c.doc.layers.size != layers0) problems += "$where: ${c.doc.layers.size} layers after undo"
                val back = allPixels()
                if (back.size != pixels0.size || pixels0.indices.any { !pixels0[it].contentEquals(back[it]) }) problems += "$where: pixels not restored by undo"
                // And redo brings the operation back without errors.
                while (c.canRedo && g-- > 0) { c.redo(); settleBusy("$where redo") }
                Smoke.assertQuiet(c, "$where redone")
                view.draw(Canvas(probe))
                while (c.canUndo && g-- > 0) { c.undo(); settleBusy("$where undo 2") }
                if (c.currentTool.hasPendingWork) { c.currentTool.discard(); Smoke.pump(60) }
                c.selectTool(ToolId.BRUSH)
                if (c.doc.width != w0 || c.doc.height != h0) problems += "$where: size after the second undo ${c.doc.width}x${c.doc.height}"
            }
        }
        assertTrue("canvas operation problems:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    // ================================================================== smart select

    @Test
    fun smartSelectFallsBackToTheHeuristicsAndSelectionEditsRunInTheBackground() {
        val doc = Smoke.document(160, 120, layers = 1)
        BitmapUtils.writePixelBuffer(doc.layers[0].bitmap, SegTestImages.skyOverFoliage(160, 120))
        val sc = Smoke.controller(activity, doc)
        try {
            SelectionEdits.smartSelect(sc, SmartTarget.SKY, SelectionMode.REPLACE)
            assertEquals("Selecting sky…", sc.busyMessage)
            assertTrue("smart select finished", Smoke.pumpUntil(90_000) { sc.busyMessage == null })
            assertNull("no failure message", sc.message)
            val sky = sc.selection ?: throw AssertionError("nothing selected; message: ${sc.message}")
            assertEquals("Select sky", sc.undoManager.undoLabel)
            assertTrue("the sky (top) is selected", sky.mask.getPixel(80, 15) ushr 24 > 200)
            assertTrue("the foliage (bottom) is not", sky.mask.getPixel(80, 105) ushr 24 < 50)
            Smoke.assertQuiet(sc, "smart select sky")

            SelectionEdits.smartSelect(sc, SmartTarget.SUBJECT, SelectionMode.ADD)
            assertTrue(Smoke.pumpUntil(90_000) { sc.busyMessage == null })
            assertTrue("subject: a result or a clear message, never a failure: ${sc.message}", sc.message == null || !sc.message!!.contains("failed"))
            Smoke.assertQuiet(sc, "smart select subject")

            // Grow / feather run on a background thread and come back as one undo step each.
            val before = sc.undoManager.undoCount
            SelectionEdits.growOrShrink(sc, 3)
            assertTrue(Smoke.pumpUntil { sc.busyMessage == null })
            SelectionEdits.feather(sc, 2f)
            assertTrue(Smoke.pumpUntil { sc.busyMessage == null })
            assertEquals(before + 2, sc.undoManager.undoCount)
            assertEquals("Feather selection", sc.undoManager.undoLabel)
            assertNull(sc.message)
            Smoke.assertQuiet(sc, "grow + feather")
        } finally {
            sc.dispose()
        }
    }

    // ================================================================== brush engine extremes

    @Test
    fun everyBrushPresetAtExtremeSettingsPaintsAndUndoes() {
        val layer = c.activeLayer
        c.doc.layers[0].bitmap.eraseColor(-1)
        c.editWholeLayer(layer, "Seed") { b ->
            Canvas(b).drawRect(150f, 0f, 250f, 300f, Paint().apply { color = blue })
        }
        c.undoManager.clear()
        val problems = mutableListOf<String>()
        fun variants(p: com.brushwork.paint.brush.BrushPreset) = listOf(
            "as is" to p,
            "min" to p.copy(size = 0.5f, minSizeRatio = 0f, opacity = 0.01f, flow = 0.01f, hardness = 0f, spacing = 0.01f, roundness = 0.05f, mixing = 0f, grain = 1f),
            "max" to p.copy(size = 1000f, minSizeRatio = 1f, hardness = 1f, spacing = 2f, scatter = 3f, taperStart = 2000f, taperEnd = 2000f, angle = 359f, mixing = 1f, pressureOpacity = true),
            "huge dense" to p.copy(size = 600f, spacing = 0.01f, hardness = 0f, scatter = 3f),
            "NaN" to p.copy(size = Float.NaN, opacity = Float.NaN, spacing = Float.NaN, hardness = Float.NaN, angle = Float.NaN),
        )
        for (tool in EditorController.PAINT_TOOLS) {
            c.selectTool(tool)
            for (preset in com.brushwork.paint.brush.BrushLibrary.presetsFor(tool)) {
                for ((vName, v) in variants(preset)) {
                    val where = "$tool / ${preset.name} / $vName"
                    Smoke.step(where)
                    c.updatePreset(tool, v)
                    val before = pixels(layer.bitmap)
                    val n = c.undoManager.undoCount
                    val t0 = System.currentTimeMillis()
                    try {
                        strokeDoc(40f to 60f, 360f to 240f)
                        // A pen stroke with changing pressure and tilt, straight to the controller.
                        c.pointerDown(ToolPoint(60f, 250f, 0.1f, 0L, isStylus = true, tilt = 0.8f, orientation = 1f))
                        for (i in 1..20) c.pointerMove(ToolPoint(60f + i * 14f, 250f - i * 9f, (i % 7) / 6f, i * 8L, isStylus = true, tilt = 0.3f * (i % 3), orientation = i * 0.3f))
                        c.pointerUp(ToolPoint(340f, 70f, 0f, 200L, isStylus = true))
                        Smoke.pump(30)
                    } catch (t: Throwable) {
                        problems += "$where: ${t.javaClass.simpleName}: ${t.message}"
                        continue
                    }
                    val ms = System.currentTimeMillis() - t0
                    if (ms > 6_000) problems += "$where: two strokes took $ms ms"
                    if (c.isInteracting || c.renderOverride != null) problems += "$where: stroke left open"
                    if (c.undoManager.undoCount - n !in 0..2) problems += "$where: ${c.undoManager.undoCount - n} undo steps"
                    while (c.undoManager.undoCount > n) c.undo()
                    if (!before.contentEquals(pixels(layer.bitmap))) problems += "$where: undo did not restore the pixels"
                }
                c.updatePreset(tool, preset)
            }
        }
        assertTrue("brush problems:\n" + problems.joinToString("\n"), problems.isEmpty())
        c.selectTool(ToolId.BRUSH)
    }

    @Test
    fun overlaysDrawForEveryGridRulerAndToolAtExtremeViews() {
        seed()
        val probe = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(probe)
        val cx = view.width / 2f
        val cy = view.height / 2f
        // Selection outline + pending shape/curve/text/transform overlays at once where possible.
        c.selectAll()
        Smoke.pump(100)
        for (zoomSteps in listOf(0, 3, -3)) {
            // Pinch in / out repeatedly, with a rotation.
            repeat(kotlin.math.abs(zoomSteps)) {
                if (zoomSteps > 0) pinch(cx - 30f to cy, cx + 30f to cy, cx - 150f to cy + 20f, cx + 150f to cy - 20f)
                else pinch(cx - 150f to cy, cx + 150f to cy, cx - 30f to cy - 10f, cx + 30f to cy + 10f)
            }
            for (type in com.brushwork.paint.model.GridType.entries) {
                c.updateGrid(c.grid.copy(enabled = true, type = type, spacingPx = 1f))
                view.draw(canvas)
                c.updateGrid(c.grid.copy(enabled = true, type = type, spacingPx = 5000f))
                view.draw(canvas)
            }
            c.updateGrid(c.grid.copy(enabled = false))
            for (type in com.brushwork.paint.model.RulerType.entries) {
                c.updateRuler(c.ruler.copy(enabled = true, type = type, radius = 1f, radiusX = 1f, radiusY = 5000f, radialLines = 360))
                view.draw(canvas)
            }
            c.updateRuler(c.ruler.copy(enabled = false))
            for (id in ToolId.entries) {
                c.selectTool(id)
                Smoke.pump(40)
                when (val t = c.currentTool) {
                    is ShapeTool -> t.ensurePending()
                    is CurveTool -> { t.addAnchor(Vec2(10f, 10f)); t.addAnchor(Vec2(390f, 290f)); t.select(1) }
                    is TextTool -> { t.startTextAt(200f, 150f); t.setText("Overlay"); t.confirmEditor() }
                    else -> {}
                }
                view.draw(canvas)
                if (c.currentTool.hasPendingWork) c.currentTool.discard()
                Smoke.pump(40)
            }
            c.selectTool(ToolId.BRUSH)
        }
        c.deselect()
        view.setMirrored(true)
        view.draw(canvas)
        view.setMirrored(false)
        Smoke.assertQuiet(c, "overlays")
    }

    // ================================================================== assists, frames, import

    @Test
    fun rulerAndStabilizerStrokesLandWhereExpected() {
        val layer = c.activeLayer
        // Straight ruler along y = 150, strokes pulled onto it: a slanted stroke paints the line.
        c.updateRuler(com.brushwork.paint.model.RulerSettings(enabled = true, centerX = 200f, centerY = 150f, angleDeg = 0f, snap = com.brushwork.paint.model.RulerSnap.ON_RULER))
        strokeDoc(80f to 120f, 320f to 180f)
        assertEquals(1, c.undoManager.undoCount)
        assertEquals("on the ruler", 255, alpha(layer, 200, 150))
        assertEquals("not where the finger was", 0, alpha(layer, 80, 120))
        c.updateRuler(c.ruler.copy(enabled = false))
        // Every stabilizer mode still paints one step, near the finger's path.
        for (mode in com.brushwork.paint.model.StabilizerMode.entries) {
            c.updateStabilizer(com.brushwork.paint.model.StabilizerSettings(mode = mode, strength = 0.8f, ropeLengthDp = 30f))
            val n = c.undoManager.undoCount
            strokeDoc(60f to 240f, 340f to 240f)
            assertEquals("$mode: one step", n + 1, c.undoManager.undoCount)
            assertEquals("$mode: the path is painted", 255, alpha(layer, 200, 240))
            Smoke.assertQuiet(c, "stabilizer $mode")
        }
        c.updateStabilizer(com.brushwork.paint.model.StabilizerSettings())
        // The ruler tool drags the ruler on the canvas (not an undo step).
        c.selectTool(ToolId.RULER)
        c.updateRuler(c.ruler.copy(enabled = true))
        val before = c.ruler
        val n = c.undoManager.undoCount
        strokeDoc(200f to 150f, 240f to 190f)
        assertNotEquals("the ruler moved", before, c.ruler)
        assertEquals(n, c.undoManager.undoCount)
        c.selectTool(ToolId.BRUSH)
    }

    @Test
    fun frameDividerCutsWithTouchAndUndoes() {
        c.selectTool(ToolId.FRAME_DIVIDER)
        val frame = c.tools.getValue(ToolId.FRAME_DIVIDER) as com.brushwork.paint.tools.frame.FrameDividerTool
        frame.settings = frame.settings.copy(rows = 1, cols = 1)
        assertTrue(frame.createFrameLayer())
        Smoke.pump(50)
        val panels0 = frame.model!!.panels.size
        val n = c.undoManager.undoCount
        strokeDoc(200f to 10f, 200f to 290f)
        assertEquals("a vertical cut splits the panel", panels0 + 1, frame.model!!.panels.size)
        assertEquals(n + 1, c.undoManager.undoCount)
        frame.removeMode = true
        touch.idle(200); screen(100f, 150f).let { touch.tap(it.first, it.second) }
        assertEquals("tapping removes a panel", panels0, frame.model!!.panels.size)
        frame.removeMode = false
        c.undo(); c.undo()
        Smoke.pump(50)
        assertEquals(panels0, frame.model!!.panels.size)
        assertEquals(com.brushwork.paint.tools.frame.FrameDividerTool.Status.READY, frame.status())
        c.selectTool(ToolId.BRUSH)
    }

    @Test
    fun importedPictureIsPlacedAndCommitted() {
        val file = java.io.File(activity.cacheDir, "picture.png")
        Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF00AA00.toInt()) }
            .compress(Bitmap.CompressFormat.PNG, 100, java.io.FileOutputStream(file))
        val actions = com.brushwork.paint.ui.editor.EditorActions(c, activity)
        actions.importPicture(android.net.Uri.fromFile(file))
        assertTrue("imported", Smoke.pumpUntil { c.busyMessage == null && c.doc.layers.size == 3 })
        Smoke.pump(100)
        val tr = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        assertEquals(ToolId.TRANSFORM, c.activeToolId)
        assertTrue("placing", tr.isPlacement && tr.hasPendingWork)
        assertNull(c.message)
        tr.commit()
        Smoke.pump(50)
        assertEquals("Import picture", c.undoManager.undoLabel)
        assertEquals(0xFF00AA00.toInt(), c.activeLayer.bitmap.getPixel(200, 150))
        // The same picture as a new project from the gallery.
        val app = ApplicationProvider.getApplicationContext<BrushworkApp>()
        val id = runBlocking { app.repository.createFromImage(android.net.Uri.fromFile(file)) }
        val doc = runBlocking { app.repository.load(id) }
        assertEquals(120, doc.width)
        assertEquals(80, doc.height)
        c.selectTool(ToolId.BRUSH)
    }

    // ================================================================== export / share

    @Test
    fun exportAndShareFilesAreWritten() {
        val app = ApplicationProvider.getApplicationContext<BrushworkApp>()
        val bmp = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(red) }
        // The returned Uri isn't checked: FileProvider matches its roots with '/' (as on Android),
        // and on a Windows test host the cache path has backslashes, so no Uri can be made there.
        runBlocking { app.repository.exportForShare(bmp, "Share me", com.brushwork.paint.storage.ExportFormat.PNG) }
        val shareFile = java.io.File(app.cacheDir, "exports/Share me.png")
        assertTrue("the share PNG was encoded", shareFile.length() > 0)
        val gallery = runBlocking { app.repository.exportToGallery(bmp, "Gallery me", com.brushwork.paint.storage.ExportFormat.JPEG) }
        println("[smoke] gallery export: $gallery")
        Smoke.pump(50)
    }

    // ================================================================== autosave

    @Test
    fun editorSessionSavesAndReloadsWhatWasEdited() {
        val app = ApplicationProvider.getApplicationContext<BrushworkApp>()
        app.settings.autosaveSeconds = 45
        val id = runBlocking { app.repository.create(NewCanvasSpec("Round trip", 320, 240, 300f)) }
        val session = app.openEditor(id)
        assertTrue("loaded", Smoke.pumpUntil { session.state is EditorSession.State.Ready })
        val ec = (session.state as EditorSession.State.Ready).controller
        assertEquals(2, ec.doc.layers.size)
        assertEquals(-1, ec.doc.layers[0].bitmap.getPixel(10, 10)) // white background layer

        // Edit through a real canvas view: strokes on the top layer, then layer structure/props.
        val v = CanvasView(activity, ec)
        activity.setContentView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        Smoke.pump(100)
        ec.tools
        ec.brush = ec.brush.copy(size = 8f, opacity = 1f, hardness = 1f, taperStart = 0f, taperEnd = 0f)
        ec.color = blue
        val t = Smoke.Touch(v)
        fun s(x: Float, y: Float) = ec.viewTransform.docToScreen(x, y).let { it.x to it.y }
        t.stroke(s(20f, 120f), s(300f, 120f))
        val top = ec.activeLayer
        assertEquals(blue, top.bitmap.getPixel(160, 120))
        val added = ec.addLayer("Extra")!!
        ec.fillLayer(added, red)
        ec.setLayerProps(added, added.props().copy(opacity = 0.5f, blendMode = com.brushwork.paint.model.LayerBlendMode.MULTIPLY))
        ec.selectAll()
        ec.addMask(added, fromSelection = false)
        ec.updateGrid(ec.grid.copy(enabled = true, spacingPx = 24f))
        val expected = ec.doc.layers.map { it.props() to pixels(it.bitmap) }
        val expectedMask = pixels(added.mask!!)

        // The periodic autosave picks the edits up by itself.
        Smoke.pump(46_000, stepMs = 1_000)
        assertTrue("autosaved", Smoke.pumpUntil { ec.doc.layers.all { it.savedVersion == it.contentVersion } })
        val loaded = runBlocking { app.repository.load(id) }
        assertEquals(expected.map { it.first }, loaded.layers.map { it.props() })
        for ((i, l) in loaded.layers.withIndex()) assertTrue("layer $i pixels", expected[i].second.contentEquals(pixels(l.bitmap)))
        assertTrue("mask pixels", expectedMask.contentEquals(pixels(loaded.layers[2].mask!!)))
        assertEquals(ec.doc.activeLayerIndex, loaded.activeLayerIndex)
        assertEquals(ec.grid, loaded.grid)

        // One more edit (the mask is being edited now), then close: saved on the way out.
        assertTrue(added.editingMask)
        t.stroke(s(160f, 20f), s(160f, 220f))
        val lastContent = ec.doc.layers.map { pixels(it.bitmap) }
        val lastMask = pixels(added.mask!!)
        assertFalse("the stroke changed the mask", lastMask.contentEquals(expectedMask))
        var closed = false
        app.closeEditor { closed = true }
        assertTrue("closed", Smoke.pumpUntil { closed })
        assertNull(app.editorSession)
        assertTrue(session.closed)

        // Reopening shows exactly what was there.
        val again = app.openEditor(id)
        assertTrue(Smoke.pumpUntil { again.state is EditorSession.State.Ready })
        val rc = (again.state as EditorSession.State.Ready).controller
        assertEquals(3, rc.doc.layers.size)
        for ((i, l) in rc.doc.layers.withIndex()) assertTrue("reopened layer $i pixels", lastContent[i].contentEquals(pixels(l.bitmap)))
        assertTrue("the stroke drawn right before closing is in the mask", lastMask.contentEquals(pixels(rc.doc.layers[2].mask!!)))
        var closed2 = false
        app.closeEditor { closed2 = true }
        assertTrue(Smoke.pumpUntil { closed2 })

        // A project that doesn't exist fails cleanly.
        val missing = app.openEditor("00000000-0000-0000-0000-000000000000")
        assertTrue(Smoke.pumpUntil { missing.state !is EditorSession.State.Loading })
        assertTrue(missing.state is EditorSession.State.Failed)
        app.closeEditor {}
        Smoke.pump(100)
    }

    // ================================================================== helpers

    private fun labels(): List<String> {
        // The undo stack is private; read it through repeated undo/redo would change state, so use reflection.
        val f = c.undoManager.javaClass.getDeclaredField("undoStack").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return (f.get(c.undoManager) as kotlin.collections.ArrayDeque<com.brushwork.paint.engine.UndoAction>).map { it.label }
    }
}
