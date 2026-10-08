package com.brushwork.paint.tools.vector

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.points.PointGizmo
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.vector.VectorContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.7 (item 1, design §3.1(a)): the touch gestures on several points of the Curve, Polyline and
 * Path tools. "Select several": a tap toggles a point, a drag on empty canvas box-selects (adds),
 * a tap on empty canvas clears, no point is ever added. Off with two or more selected: a drag on
 * a selected point moves the group (one in-tool step), a drag on another point selects only it
 * and moves it, the gizmo's corner scales about the box centre, the knob rotates, a pinch that
 * starts inside the box scales the group (a cancelled one puts it back).
 */
@RunWith(RobolectricTestRunner::class)
class CurveGroupGestureRobolectricTest {

    private val w = 400
    private val h = 300

    private fun controller(): EditorController {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("brushwork_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val doc = Document("t", "t", w, h)
        doc.layers += Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(w, h))
        doc.layers += Layer(doc.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(w, h)).also { it.vector = VectorContent.EMPTY }
        doc.activeLayerIndex = 1
        return EditorController(ctx, doc, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), AppSettings(ctx)).also {
            it.color = 0xFF203080.toInt()
            it.tools
            it.brush = BrushLibrary.defaultBrush.copy(size = 6f, pressureSize = false, taperStart = 0f, taperEnd = 0f)
            it.snapping.enabled = false
        }
    }

    private val five = listOf(Vec2(40f, 60f), Vec2(100f, 40f), Vec2(160f, 80f), Vec2(220f, 50f), Vec2(280f, 90f))

    private fun withFive(id: ToolId): Pair<EditorController, CurveTool> {
        val c = controller()
        c.selectTool(id)
        val t = c.tools.getValue(id) as CurveTool
        for (p in five) assertTrue(t.addAnchor(p))
        t.deselect()
        return c to t
    }

    private fun EditorController.drag(vararg pts: Vec2) {
        pointerDown(ToolPoint(pts[0].x, pts[0].y))
        for (i in 1 until pts.size) pointerMove(ToolPoint(pts[i].x, pts[i].y))
        pointerUp(ToolPoint(pts.last().x, pts.last().y))
    }

    private fun EditorController.tap(p: Vec2) = drag(p)

    private fun steps(t: CurveTool): Int {
        var n = 0
        while (t.undoStep()) n++
        repeat(n) { t.redoStep() }
        return n
    }

    private val kinds = listOf(ToolId.CURVE, ToolId.POLYLINE, ToolId.PATH)

    @Test
    fun selectSeveralTapsToggleAndAnEmptyTapClearsWithoutAddingPoints() {
        for (id in kinds) {
            val (c, t) = withFive(id)
            t.selectSeveral = true
            assertTrue(t.selectSeveral)
            c.tap(five[1])
            c.tap(five[3])
            c.tap(five[4])
            assertEquals("$id", listOf(1, 3, 4), t.pointSelection.indices)
            c.tap(five[3])
            assertEquals("$id", listOf(1, 4), t.pointSelection.indices)
            // Empty canvas: clears, never adds a point.
            c.tap(Vec2(200f, 250f))
            assertTrue("$id", t.pointSelection.isEmpty)
            assertEquals("$id", 5, t.pointCount)
            assertEquals("$id: no step", five, (0 until 5).map { t.pointAt(it) })
        }
    }

    @Test
    fun aMarqueeOnEmptyCanvasAddsThePointsInsideIt() {
        for (id in kinds) {
            val (c, t) = withFive(id)
            t.selectSeveral = true
            c.tap(five[4])
            c.drag(Vec2(20f, 20f), Vec2(80f, 60f), Vec2(170f, 120f))
            assertEquals("$id", listOf(0, 1, 2, 4), t.pointSelection.indices)
            assertEquals("$id", 5, t.pointCount)
        }
    }

    @Test
    fun selectSeveralTurnsItselfOffWhenThePathEnds() {
        val (_, t) = withFive(ToolId.PATH)
        t.selectSeveral = true
        t.commit()
        assertFalse(t.selectSeveral)
        // Nothing open: it can't be turned on.
        t.selectSeveral = true
        assertFalse(t.selectSeveral)
    }

    @Test
    fun aDragOnASelectedPointMovesTheGroupAsOneStep() {
        for (id in kinds) {
            val (c, t) = withFive(id)
            t.selectPoints(PointSelection.of(5, 1, 3))
            val n = steps(t)
            c.drag(five[1], five[1] + Vec2(10f, 5f), five[1] + Vec2(30f, 20f))
            assertEquals("$id", five[1] + Vec2(30f, 20f), t.pointAt(1))
            assertEquals("$id", five[3] + Vec2(30f, 20f), t.pointAt(3))
            assertEquals("$id", five[0], t.pointAt(0))
            assertEquals("$id", listOf(1, 3), t.pointSelection.indices)
            assertEquals("$id: one step", n + 1, steps(t))
            assertTrue(t.undoStep())
            assertEquals("$id", five[3], t.pointAt(3))
        }
    }

    @Test
    fun aDragOnAnUnselectedPointSelectsOnlyItAndMovesIt() {
        for (id in kinds) {
            val (c, t) = withFive(id)
            t.selectPoints(PointSelection.of(5, 1, 3))
            c.drag(five[0], five[0] + Vec2(0f, 10f), five[0] + Vec2(0f, 30f))
            assertEquals("$id", PointSelection.of(5, 0), t.pointSelection)
            assertEquals("$id", five[0] + Vec2(0f, 30f), t.pointAt(0))
            assertEquals("$id", five[1], t.pointAt(1))
        }
    }

    @Test
    fun aTapOnAGroupPointSelectsOnlyIt() {
        val (c, t) = withFive(ToolId.CURVE)
        t.selectPoints(PointSelection.of(5, 1, 3))
        c.tap(five[3])
        assertEquals(PointSelection.of(5, 3), t.pointSelection)
    }

    @Test
    fun theGizmoCornerScalesTheGroupAboutTheBoxCentre() {
        for (id in kinds) {
            val (c, t) = withFive(id)
            // Points 0, 2 and 4: the box is 40..280 × 60..90, its centre (160, 75).
            t.selectPoints(PointSelection.of(5, 0, 2, 4))
            val layout = PointGizmo().layout(listOf(five[0], five[2], five[4]), c.viewTransform)!!
            val se = c.viewTransform.screenToDoc(layout.cornersScreen[2])
            val centre = layout.pivotDoc
            // Twice as far from the centre: × 2.
            val to = centre + (se - centre) * 2f
            c.drag(se, se + Vec2(5f, 5f), to)
            for (i in listOf(0, 2, 4)) {
                val want = centre + (five[i] - centre) * 2f
                assertEquals("$id point $i x", want.x, t.pointAt(i).x, 0.05f)
                assertEquals("$id point $i y", want.y, t.pointAt(i).y, 0.05f)
            }
            assertEquals("$id", five[1], t.pointAt(1))
            assertTrue(t.undoStep())
            assertEquals("$id", five[4], t.pointAt(4))
        }
    }

    @Test
    fun theKnobRotatesTheGroupAboutTheBoxCentre() {
        val (c, t) = withFive(ToolId.PATH)
        t.selectPoints(PointSelection.of(5, 0, 4))
        val layout = PointGizmo().layout(listOf(five[0], five[4]), c.viewTransform)!!
        val knob = c.viewTransform.screenToDoc(layout.rotateHandleScreen)
        val p = layout.pivotDoc
        // A quarter turn clockwise: the knob above the centre goes to its right.
        val r = knob.distanceTo(p)
        c.drag(knob, knob + Vec2(3f, 0f), p + Vec2(r, 0f))
        val a = t.pointAt(0)
        val want = Vec2(p.x - (five[0].y - p.y), p.y + (five[0].x - p.x))
        assertEquals(want.x, a.x, 0.05f)
        assertEquals(want.y, a.y, 0.05f)
    }

    @Test
    fun aPinchThatStartsInsideTheBoxScalesTheGroupAndACancelledOnePutsItBack() {
        for (id in kinds) {
            val (c, t) = withFive(id)
            t.selectPoints(PointSelection.of(5, 1, 2, 3))
            val n = steps(t)
            val focus = Vec2(160f, 60f)
            assertTrue("$id", c.twoFingerStart(focus, focus - Vec2(20f, 0f), focus + Vec2(20f, 0f)))
            c.twoFingerGesture(Vec2.ZERO, 2f, 0f)
            c.twoFingerEnd(cancelled = false)
            // About the box centre (160, 60).
            val centre = Vec2(160f, 60f)
            for (i in 1..3) {
                val want = centre + (five[i] - centre) * 2f
                assertEquals("$id point $i", want.x, t.pointAt(i).x, 0.05f)
                assertEquals("$id point $i", want.y, t.pointAt(i).y, 0.05f)
            }
            assertEquals("$id: one step", n + 1, steps(t))
            assertTrue(c.twoFingerStart(focus, focus - Vec2(20f, 0f), focus + Vec2(20f, 0f)))
            c.twoFingerGesture(Vec2(10f, 0f), 0.5f, 30f)
            c.twoFingerEnd(cancelled = true)
            for (i in 1..3) {
                val want = centre + (five[i] - centre) * 2f
                assertEquals("$id cancelled $i", want.x, t.pointAt(i).x, 0.05f)
            }
            assertEquals("$id", n + 1, steps(t))
        }
    }

    @Test
    fun aPinchOutsideTheBoxLeavesTheViewToTheCanvas() {
        val (c, t) = withFive(ToolId.PATH)
        t.selectPoints(PointSelection.of(5, 1, 3))
        val far = Vec2(300f, 250f)
        assertFalse(c.twoFingerStart(far, far - Vec2(20f, 0f), far + Vec2(20f, 0f)))
    }
}
