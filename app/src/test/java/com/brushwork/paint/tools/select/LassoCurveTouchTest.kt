package com.brushwork.paint.tools.select

import android.view.MotionEvent
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.Smoke.P
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.editor.CanvasView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * The curve lasso as the user drives it on the phone (~392 dp wide, fingers): real MotionEvents
 * through the canvas view for tapping points, the two-finger tap (undo one point), the
 * three-finger tap (redo), a long press on a point (selects it, the finger then drags it), and
 * closing by tapping the first point.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi")
class LassoCurveTouchTest {
    private lateinit var activity: ComponentActivity
    private lateinit var c: EditorController
    private lateinit var view: CanvasView
    private lateinit var touch: Smoke.Touch
    private lateinit var lasso: LassoTool

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
        c.selectTool(ToolId.LASSO)
        lasso = c.tools.getValue(ToolId.LASSO) as LassoTool
        lasso.setKind(LassoKind.CURVE)
    }

    @After
    fun tearDown() {
        Smoke.pump(50)
        Smoke.assertQuiet(c, "end of test")
        c.dispose()
    }

    private fun screen(x: Float, y: Float): Pair<Float, Float> = c.viewTransform.docToScreen(x, y).let { it.x to it.y }

    private fun tapDoc(x: Float, y: Float) {
        touch.idle(200) // never part of the previous gesture
        val (sx, sy) = screen(x, y)
        touch.tap(sx, sy)
    }

    /** Two-finger tap on empty canvas, well away from the outline. */
    private fun twoFingerTap() {
        touch.idle(200)
        touch.twoFingerTap(screen(150f, 285f), screen(250f, 285f))
    }

    private fun threeFingerTap() {
        touch.idle(200)
        touch.threeFingerTap(screen(100f, 285f), screen(200f, 285f), screen(300f, 285f))
    }

    private fun assertNear(expected: Vec2, actual: Vec2, tol: Float = 0.75f) {
        assertEquals("x of $actual", expected.x, actual.x, tol)
        assertEquals("y of $actual", expected.y, actual.y, tol)
    }

    @Test
    fun tapPointsUndoRedoWithFingersLongPressToEditAndCloseOnTheFirstPoint() {
        for ((x, y) in listOf(80f to 50f, 320f to 50f, 320f to 190f, 80f to 190f)) tapDoc(x, y)
        assertEquals(4, lasso.curve.count)
        assertNear(Vec2(320f, 190f), lasso.curve.anchors[2].pos)

        twoFingerTap()
        assertEquals("only the last point is gone", 3, lasso.curve.count)
        assertTrue(lasso.hasPendingWork)
        threeFingerTap()
        assertEquals(4, lasso.curve.count)

        // Long-press the third point: it is selected (sharp / smooth / delete in the strip) and
        // the same finger then drags it.
        val (ax, ay) = screen(320f, 190f)
        touch.idle(200)
        touch.send(MotionEvent.ACTION_DOWN, P(0, ax, ay))
        touch.idle(700)
        assertEquals("long press selected the point", 2, lasso.curve.selected)
        val (bx, by) = screen(340f, 230f)
        for (s in 1..6) {
            touch.idle(16)
            touch.send(MotionEvent.ACTION_MOVE, P(0, ax + (bx - ax) * s / 6f, ay + (by - ay) * s / 6f))
        }
        touch.send(MotionEvent.ACTION_UP, P(0, bx, by))
        touch.idle(50)
        assertFalse("no color picking over a point", c.holdPicking)
        assertEquals(2, lasso.curve.selected)
        assertEquals(4, lasso.curve.count)
        assertNear(Vec2(340f, 230f), lasso.curve.anchors[2].pos)
        lasso.curve.setSharp(2, true)
        assertTrue(lasso.curve.anchors[2].sharp)
        // Undo with two fingers: first the corner, then the drag.
        twoFingerTap()
        assertFalse(lasso.curve.anchors[2].sharp)
        twoFingerTap()
        assertNear(Vec2(320f, 190f), lasso.curve.anchors[2].pos)
        assertEquals(4, lasso.curve.count)

        // A drag from empty canvas places a new point where the finger lifts.
        touch.idle(200)
        touch.stroke(screen(200f, 150f), screen(210f, 160f), screen(215f, 170f))
        assertEquals(5, lasso.curve.count)
        assertNear(Vec2(215f, 170f), lasso.curve.anchors[4].pos)
        twoFingerTap()
        assertEquals(4, lasso.curve.count)

        // Tapping the first point closes the outline into a selection.
        tapDoc(80f, 50f)
        assertFalse(lasso.hasPendingWork)
        assertTrue(Smoke.pumpUntil { c.selection != null && !lasso.busy })
        val sel = c.selection!!
        assertEquals(255, sel.alphaAt(200, 120))
        // The smooth outline bulges past the chord between the first two points.
        assertEquals(255, sel.alphaAt(200, 44))
        assertEquals(0, sel.alphaAt(200, 10))
        c.deselect()
    }
}
