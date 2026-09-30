package com.brushwork.paint.tools.vector

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.Smoke.P
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.select.LassoTool
import com.brushwork.paint.ui.editor.CanvasView
import com.brushwork.paint.ui.editor.Hotbar
import com.brushwork.paint.ui.theme.BrushworkTheme
import com.brushwork.paint.ui.tools.ToolOptionsBar
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
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
 * Point editing as the user does it on the phone (~392 dp wide): real MotionEvents through the
 * canvas view for tapping points / polygon corners, the two-finger tap (undo), the three-finger
 * tap (redo) and a pinch while a curve is being edited; the Redo button of the bottom bar and the
 * in-tool undo / redo buttons.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi")
class PointEditingTouchTest {
    private lateinit var activity: ComponentActivity
    private lateinit var c: EditorController
    private lateinit var view: CanvasView
    private lateinit var touch: Smoke.Touch

    private val red = 0xFFFF0000.toInt()

    @Before
    fun setUp() {
        ShadowLog.clear()
        Smoke.scopeErrors.clear()
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        c = Smoke.controller(activity, Smoke.document(400, 300, 2))
        view = CanvasView(activity, c)
        activity.setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        Smoke.pump(100)
        assertTrue("canvas view laid out", view.width > 0 && view.height > 0)
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

    private fun tapDoc(x: Float, y: Float) {
        touch.idle(200) // never part of the previous gesture
        val (sx, sy) = screen(x, y)
        touch.tap(sx, sy)
    }

    /** Two-finger tap on empty canvas (the first finger lands where no point is). */
    private fun twoFingerTap() {
        touch.idle(200)
        touch.twoFingerTap(screen(150f, 280f), screen(250f, 280f))
    }

    private fun threeFingerTap() {
        touch.idle(200)
        touch.threeFingerTap(screen(100f, 280f), screen(200f, 280f), screen(300f, 280f))
    }

    private fun composite(): Bitmap {
        val out = BitmapUtils.createLayerBitmap(c.doc.width, c.doc.height)
        c.compositor.drawDocument(Canvas(out), null)
        return out
    }

    private fun painted(layer: Layer): Int {
        val px = IntArray(layer.bitmap.width * layer.bitmap.height)
        layer.bitmap.getPixels(px, 0, layer.bitmap.width, 0, 0, layer.bitmap.width, layer.bitmap.height)
        return px.count { it ushr 24 != 0 }
    }

    private fun alphaAt(b: Bitmap, x: Int, y: Int) = b.getPixel(x, y) ushr 24

    private fun assertNear(expected: Vec2, actual: Vec2) {
        assertEquals("x of $actual", expected.x, actual.x, 0.5f)
        assertEquals("y of $actual", expected.y, actual.y, 0.5f)
    }

    // ------------------------------------------------------------------ curve / polyline

    @Test
    fun twoFingerTapTakesBackOneCurvePointAndThreeFingersBringItBack() {
        c.selectTool(ToolId.POLYLINE)
        val tool = c.tools.getValue(ToolId.POLYLINE) as CurveTool
        tool.update { it.copy(stroke = CurveStroke.BRUSH, fill = false, closed = false, taper = false) }
        tapDoc(60f, 100f); tapDoc(200f, 60f); tapDoc(340f, 100f)
        assertEquals(3, tool.anchors.size)
        touch.idle(300)
        // The real brush stroke shows while the points are edited; nothing is baked yet.
        assertNotNull(c.renderOverride)
        assertEquals(0, painted(c.activeLayer))
        assertTrue("second segment visible", alphaAt(composite(), 270, 80) > 200)

        twoFingerTap()
        assertEquals("only the last point is gone", 2, tool.anchors.size)
        assertNear(Vec2(200f, 60f), tool.anchors[1].pos)
        touch.idle(300)
        val shot = composite()
        assertEquals("its segment is gone from the preview", 0, alphaAt(shot, 270, 80))
        assertTrue("the first segment stays", alphaAt(shot, 130, 80) > 200)
        assertEquals("the document history was not touched", 0, c.undoManager.undoCount)

        twoFingerTap()
        assertEquals(1, tool.anchors.size)
        threeFingerTap()
        threeFingerTap()
        assertEquals("both points are back", 3, tool.anchors.size)
        assertNear(Vec2(340f, 100f), tool.anchors[2].pos)
        touch.idle(300)
        assertTrue(alphaAt(composite(), 270, 80) > 200)

        // An old point is dragged by touching near it (not exactly on it).
        touch.idle(200)
        touch.stroke(screen(203f, 63f), screen(200f, 150f))
        assertEquals("dragging added no point", 3, tool.anchors.size)
        assertEquals(150f, tool.anchors[1].y, 4f)
        twoFingerTap()
        assertNear(Vec2(200f, 60f), tool.anchors[1].pos)

        tool.commit()
        assertEquals(1, c.undoManager.undoCount)
        assertEquals("Polyline", c.undoManager.undoLabel)
        assertTrue(alphaAt(c.activeLayer.bitmap, 270, 80) > 200)
    }

    @Test
    fun pinchWhileEditingACurveNeverFlashesTheBrushStroke() {
        c.selectTool(ToolId.CURVE)
        val tool = c.tools.getValue(ToolId.CURVE) as CurveTool
        tool.update { it.copy(stroke = CurveStroke.BRUSH, fill = false, closed = false, taper = false) }
        tapDoc(60f, 100f); tapDoc(340f, 100f)
        touch.idle(300)
        val shown = c.renderOverride
        assertNotNull(shown)
        val z0 = c.viewTransform.zoom

        // The first finger of a pinch lands on empty canvas and adds a point under it...
        touch.idle(200)
        val (ax, ay) = screen(160f, 220f)
        val (bx, by) = screen(240f, 220f)
        touch.send(MotionEvent.ACTION_DOWN, P(0, ax, ay))
        touch.idle(60)
        assertEquals(3, tool.anchors.size)
        assertSame("the stroke does not jump to that point yet", shown, c.renderOverride)
        // ...which the second finger takes back.
        touch.send(MotionEvent.ACTION_POINTER_DOWN, P(0, ax, ay), P(1, bx, by), index = 1)
        assertEquals(2, tool.anchors.size)
        for (s in 1..10) {
            touch.idle(40)
            touch.send(MotionEvent.ACTION_MOVE, P(0, ax - 8f * s, ay), P(1, bx + 8f * s, by))
        }
        touch.idle(20)
        touch.send(MotionEvent.ACTION_POINTER_UP, P(0, ax - 80f, ay), P(1, bx + 80f, by), index = 0)
        touch.idle(20)
        touch.send(MotionEvent.ACTION_UP, P(1, bx + 80f, by))
        touch.idle(300)
        assertTrue("the view zoomed", c.viewTransform.zoom > z0 * 1.2f)
        assertEquals(2, tool.anchors.size)
        assertSame("the brush stroke was never replayed", shown, c.renderOverride)

        // A plain tap keeps its point, and the stroke follows right away.
        tapDoc(200f, 200f)
        assertEquals(3, tool.anchors.size)
        assertNotSame("replayed promptly after the tap", shown, c.renderOverride)
        tool.discard()
        assertEquals(0, painted(c.activeLayer))
    }

    // ------------------------------------------------------------------ polygon lasso

    @Test
    fun tapCornersUndoOneAtATimeAndOldCornersCanBeDragged() {
        c.selectTool(ToolId.LASSO)
        val lasso = c.tools.getValue(ToolId.LASSO) as LassoTool
        lasso.setPolygonMode(true)
        for ((x, y) in listOf(60f to 60f, 340f to 60f, 340f to 240f, 60f to 240f)) tapDoc(x, y)
        assertEquals(4, lasso.vertexCount)

        twoFingerTap()
        assertEquals("only the last corner is gone", 3, lasso.vertexCount)
        assertTrue(lasso.hasPendingWork)
        threeFingerTap()
        assertEquals(4, lasso.vertexCount)

        // Drag the second corner somewhere else.
        touch.idle(200)
        touch.stroke(screen(340f, 60f), screen(300f, 110f))
        assertEquals("dragging added no corner", 4, lasso.vertexCount)
        lasso.corner(1).let { (x, y) -> assertNear(Vec2(300f, 110f), Vec2(x, y)) }
        twoFingerTap()
        lasso.corner(1).let { (x, y) -> assertNear(Vec2(340f, 60f), Vec2(x, y)) }
        assertEquals(4, lasso.vertexCount)

        // A tap on the first corner closes the polygon into a selection.
        tapDoc(60f, 60f)
        assertFalse(lasso.hasPendingWork)
        assertTrue(Smoke.pumpUntil { c.selection != null })
        assertTrue(c.selection!!.mask.getPixel(200, 150) ushr 24 > 0)
        c.deselect()
        lasso.setPolygonMode(false)
    }

    // ------------------------------------------------------------------ buttons

    @Test
    fun bottomBarRedoAndInToolButtonsFollowThePointSteps() {
        // Only the windows shown from here on are searched (the canvas activity has no Compose).
        SmokeUi.markBaseline()
        val ui = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        ui.setContent {
            BrushworkTheme {
                Column {
                    ToolOptionsBar(c)
                    Hotbar(c, onToolPicker = {}, onBrushPanel = {}, onColorPanel = {}, onLayersPanel = {})
                }
            }
        }
        SmokeUi.settle()
        fun tap(x: Float, y: Float) { c.pointerDown(ToolPoint(x, y)); c.pointerUp(ToolPoint(x, y)) }

        c.selectTool(ToolId.POLYLINE)
        val tool = c.tools.getValue(ToolId.POLYLINE) as CurveTool
        tool.update { it.copy(stroke = CurveStroke.PLAIN) }
        tap(60f, 100f); tap(200f, 60f); tap(340f, 100f)
        SmokeUi.settle()
        assertFalse("nothing to redo yet", SmokeUi.isEnabled("Redo"))
        SmokeUi.click("Undo", exact = true)
        assertEquals("the bottom bar's undo takes back one point", 2, tool.anchors.size)
        assertTrue("the bottom bar's redo brings a point back", SmokeUi.isEnabled("Redo"))
        assertTrue(SmokeUi.isEnabled("Redo point"))
        SmokeUi.click("Redo", exact = true)
        assertEquals(3, tool.anchors.size)
        assertFalse(SmokeUi.isEnabled("Redo"))
        SmokeUi.click("Undo last point")
        assertEquals(2, tool.anchors.size)
        SmokeUi.click("Redo point")
        assertEquals(3, tool.anchors.size)
        tool.discard()
        SmokeUi.settle()

        c.selectTool(ToolId.LASSO)
        val lasso = c.tools.getValue(ToolId.LASSO) as LassoTool
        lasso.setPolygonMode(true)
        tap(60f, 60f); tap(340f, 60f); tap(340f, 240f)
        SmokeUi.settle()
        assertTrue(SmokeUi.has("3 pt"))
        SmokeUi.click("Undo last corner")
        assertEquals(2, lasso.vertexCount)
        assertTrue(SmokeUi.has("2 pt"))
        assertTrue(SmokeUi.isEnabled("Redo"))
        SmokeUi.click("Redo corner")
        assertEquals(3, lasso.vertexCount)
        SmokeUi.click("Undo", exact = true)
        assertEquals(2, lasso.vertexCount)
        SmokeUi.click("Redo", exact = true)
        assertEquals(3, lasso.vertexCount)
        lasso.discard()
        lasso.setPolygonMode(false)
        SmokeUi.settle()
    }
}
