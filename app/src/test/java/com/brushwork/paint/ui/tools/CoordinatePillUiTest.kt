package com.brushwork.paint.ui.tools

import android.graphics.Matrix
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.brushwork.paint.masks.RadialMask
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.mask.MaskTool
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.LocalIncrements
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.time.Duration
import kotlin.math.abs
import kotlin.math.round

/**
 * v1.6 §3.7.8 / §5.1 G Accept: CoordinatePillUiTest. The X / Y pill on the user's phone (392 dp,
 * xxhdpi) over the Masks tool's radial component (each finished edit is one undo step "Move
 * mask"), the view zoomed 2×:
 * - two beveled cells "X" / "Y" with finger-sized (40 dp) touch targets, at least 72 dp wide, the
 *   value as the state ("250 px") and its range for screen readers;
 * - a drag of the number moves the value by the finger's travel in document px (dx / zoom), a
 *   tenth of it ("Fine") while the finger is more than 48 dp above or below; one step per drag;
 * - with snapping on, a detent at the canvas centre; with "#" (Increments) on, a drag lands on
 *   multiples of the Length step and a toast says the steps;
 * - a long-press opens the Step popup for lengths; a tap types the value, kept exactly;
 * - folding leaves a lone ✥ (remembered), unfolding brings the cells back.
 * One test (Compose's frame clock serves the first test of a sandbox only), own sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.tools.pillsandbox"])
class CoordinatePillUiTest {

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun cell(axis: String): RobolectricUi.Element = RobolectricUi.elements().last { e ->
        e.node.layoutInfo.isPlaced && e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("$axis slider") == true
    }

    /**
     * A real finger: down at ([x], [y]), then through [points] (window px), 8 ms of main-looper
     * time between events (as on a device), and up at the last one.
     */
    private fun finger(window: View, x: Float, y: Float, vararg points: Pair<Float, Float>) {
        val t0 = SystemClock.uptimeMillis()
        var t = t0
        fun send(action: Int, px: Float, py: Float) {
            val ev = MotionEvent.obtain(t0, t, action, px, py, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            window.dispatchTouchEvent(ev)
            ev.recycle()
            idle(8)
            t += 8
        }
        send(MotionEvent.ACTION_DOWN, x, y)
        for ((px, py) in points) send(MotionEvent.ACTION_MOVE, px, py)
        val (ux, uy) = points.last()
        send(MotionEvent.ACTION_UP, ux, uy)
        settle(4)
    }

    /** [n] evenly spaced points from ([x0], [y0]) to ([x1], [y1]), the start excluded. */
    private fun path(x0: Float, y0: Float, x1: Float, y1: Float, n: Int = 8): Array<Pair<Float, Float>> =
        Array(n) { i -> (x0 + (x1 - x0) * (i + 1) / n) to (y0 + (y1 - y0) * (i + 1) / n) }

    @Test
    fun thePill() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val ctl = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val activity = ctl.get()
            val density = activity.resources.displayMetrics.density
            val c = Smoke.controller(activity, Smoke.document(600, 400, layers = 2))
            c.viewTransform.set(Matrix())
            c.selectTool(ToolId.MASK)
            val tool = c.tools.getValue(ToolId.MASK) as MaskTool
            tool.arm(MaskTool.Kind.RADIAL)
            c.pointerDown(ToolPoint(250f, 200f)); c.pointerMove(ToolPoint(270f, 200f)); c.pointerMove(ToolPoint(300f, 200f)); c.pointerUp(ToolPoint(300f, 200f))
            val adj = c.activeLayer
            assertTrue(adj.isAdjustmentLayer)
            fun cx() = (adj.maskSpec!!.components.single() as RadialMask).cx
            // Mid-drag the component moves in the tool's live preview (committed when the drag ends).
            fun live() = tool.objectPosition!!.position!!.x
            assertEquals(250f, cx(), 1e-3f)
            // Zoomed in 2×: one screen px is half a document px.
            c.viewTransform.set(Matrix().apply { setScale(2f, 2f) })
            c.snapping.enabled = false
            activity.setContent {
                BrushworkTheme {
                    CompositionLocalProvider(LocalIncrements provides c.increments) {
                        // Where the editor puts it: 8 dp from the left, under the options strip.
                        Box(Modifier.fillMaxSize()) { CoordinatePill(c, Modifier.offset(x = 8.dp, y = 135.dp)) }
                    }
                }
            }
            settle()

            // ---------------------------------------------------------------- the cells
            val x = cell("X")
            // The pill is 32 dp tall; each cell's touch target is 40 dp, reaching 4 dp above and
            // below it (Compose hit-tests children outside their parent).
            assertEquals("a finger-sized target", 40f * density, x.node.size.height.toFloat(), 1f)
            assertTrue("at least 72 dp wide: ${x.bounds.width / density}", x.bounds.width >= 72f * density - 1f)
            // A real finger 2 dp above the 32 dp pill (18 dp above the cell's middle) still lands
            // on the cell: a tap types the value.
            RobolectricUi.tap(x.window, x.bounds.center.x, x.bounds.center.y - 18f * density)
            settle()
            assertTrue("the tap above the pill opened \"Type X\": ${SmokeUi.shown().take(40)}", SmokeUi.has("X position", exact = true))
            SmokeUi.clickIn("X position", "Cancel")
            assertFalse(SmokeUi.has("X position", exact = true))
            assertEquals("nothing moved", 250f, cx(), 1e-3f)
            assertEquals("250 px", x.stateDescription)
            assertEquals("200 px", cell("Y").stateDescription)
            val range = x.node.config[SemanticsProperties.ProgressBarRangeInfo].range
            assertEquals("a quarter of the canvas before it", -150f, range.start, 1e-3f)
            assertEquals("a quarter of the canvas after it", 750f, range.endInclusive, 1e-3f)
            assertTrue("X and Y prefixes", SmokeUi.has("X", exact = true) && SmokeUi.has("Y", exact = true))
            assertTrue(SmokeUi.has("250", exact = true))
            assertTrue(SmokeUi.has("Fold the X / Y strip", exact = true))
            val hash = RobolectricUi.elements().last { it.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Increments") == true }
            assertEquals(ToggleableState.Off, hash.node.config[SemanticsProperties.ToggleableState])
            assertTrue("the # cell is finger-sized", hash.node.size.height >= 40f * density - 1f && hash.node.size.width >= 40f * density - 1f)

            // ---------------------------------------------------------------- drag gain = dx / zoom
            var steps = c.undoManager.undoCount
            val b = x.bounds
            finger(x.window, b.center.x, b.center.y, *path(b.center.x, b.center.y, b.center.x + 80f, b.center.y))
            assertEquals("80 screen px at 2× are 40 document px", 290f, cx(), 0.01f)
            assertEquals("one step per drag", steps + 1, c.undoManager.undoCount)
            assertEquals("Move mask", c.undoManager.undoLabel)
            steps = c.undoManager.undoCount

            // ---------------------------------------------------------------- fine
            val far = 60f * density
            val x0 = b.center.x
            val y0 = b.center.y
            val t0 = SystemClock.uptimeMillis()
            var t = t0
            fun send(action: Int, px: Float, py: Float) {
                val ev = MotionEvent.obtain(t0, t, action, px, py, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                x.window.dispatchTouchEvent(ev)
                ev.recycle()
                idle(8)
                t += 8
            }
            send(MotionEvent.ACTION_DOWN, x0, y0)
            for ((px, py) in path(x0, y0, x0 + 60f, y0)) send(MotionEvent.ACTION_MOVE, px, py)
            val coarse = live()
            assertEquals(320f, coarse, 0.01f)
            for ((px, py) in path(x0 + 60f, y0, x0 + 60f, y0 + far)) send(MotionEvent.ACTION_MOVE, px, py)
            settle(2)
            assertTrue("\"Fine\" while the finger is far below", SmokeUi.has("Fine", exact = true))
            assertEquals("no jump when fine starts", coarse, live(), 0.01f)
            for ((px, py) in path(x0 + 60f, y0 + far, x0 + 120f, y0 + far)) send(MotionEvent.ACTION_MOVE, px, py)
            assertEquals("a tenth of 60 px at 2×: 3 document px", 323f, live(), 0.01f)
            send(MotionEvent.ACTION_UP, x0 + 120f, y0 + far)
            settle(4)
            assertFalse(SmokeUi.has("Fine", exact = true))
            assertEquals(323f, cx(), 0.01f)
            assertEquals("one step", steps + 1, c.undoManager.undoCount)
            c.undo()
            c.undo()
            assertEquals(250f, cx(), 1e-3f)
            settle()
            steps = c.undoManager.undoCount

            // ---------------------------------------------------------------- detent at the centre (snapping on)
            c.snapping.enabled = true
            settle()
            // +92 screen px = +46 document px: raw 296, 4 px from the canvas centre (300); the pull is 8 dp = 12 px here.
            val bx = cell("X").bounds
            finger(cell("X").window, bx.center.x, bx.center.y, *path(bx.center.x, bx.center.y, bx.center.x + 92f, bx.center.y))
            assertEquals("pulled onto the centre", 300f, cx(), 1e-3f)
            c.snapping.enabled = false
            settle()

            // ---------------------------------------------------------------- "#": increments
            SmokeUi.click("Increments", exact = true)
            assertTrue(c.increments.enabled)
            assertEquals("Increments on: 10 px · 10 % · 15°", c.message)
            val on = RobolectricUi.elements().last { it.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Increments") == true }
            assertEquals(ToggleableState.On, on.node.config[SemanticsProperties.ToggleableState])
            // +67 screen px (past the touch slop) = +33.5 document px from 300: 333.5 → 330.
            val bs = cell("X").bounds

            finger(cell("X").window, bs.center.x, bs.center.y, *path(bs.center.x, bs.center.y, bs.center.x + 67f, bs.center.y))

            assertEquals("on a multiple of 10 px", 330f, cx(), 1e-3f)
            // The screen reader's increase: the next multiple.
            cell("X").node.config[SemanticsActions.CustomActions].single { it.label == "Increase X" }.action.invoke()
            settle()
            assertEquals(340f, cx(), 1e-3f)
            // Long-press: the Step popup for lengths.
            requireNotNull(cell("X").node.config[SemanticsActions.OnLongClick].action).invoke()
            settle()
            assertTrue("the Step popup: ${SmokeUi.shown()}", SmokeUi.has("Step for lengths", exact = true))
            SmokeUi.click("Cancel", exact = true)
            assertFalse(SmokeUi.has("Step for lengths", exact = true))
            SmokeUi.click("Increments", exact = true)
            assertFalse(c.increments.enabled)
            assertEquals("Increments off", c.message)

            // ---------------------------------------------------------------- typing: exact
            steps = c.undoManager.undoCount
            SmokeUi.click("Type X")
            SmokeUi.typeAndDone("X", "123.4")
            assertEquals(123.4f, cx(), 1e-3f)
            assertEquals("typed: one step", steps + 1, c.undoManager.undoCount)
            assertEquals("123.4 px", cell("X").stateDescription)

            // ---------------------------------------------------------------- folded
            SmokeUi.click("Fold the X / Y strip", exact = true)
            assertTrue(c.settings.coordinateStripFolded)
            assertTrue(SmokeUi.has("Unfold the X / Y strip", exact = true))
            assertFalse(SmokeUi.has("X slider"))
            assertFalse(SmokeUi.has("Increments", exact = true))
            SmokeUi.click("Unfold the X / Y strip", exact = true)
            assertFalse(c.settings.coordinateStripFolded)
            assertTrue(SmokeUi.has("X slider"))
            assertTrue(abs(cx() - round(cx() * 10f) / 10f) < 1e-3f)
        } finally {
            runCatching { ctl.pause().stop().destroy() }
        }
    }
}
