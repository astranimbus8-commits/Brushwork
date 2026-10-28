package com.brushwork.paint.v110.a5

import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.qa19.Qa19Ui
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.points.GroupSliderGeometry
import com.brushwork.paint.ui.points.ThicknessRowLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * v1.10 (A5, R12) in the editor on the user's 392 dp phone: one Curve or Path point at 100 %, its
 * thickness slider dragged up on the More row with a frame after each move, as a phone runs them.
 * The drag's first change brings "All points 100 %" in (v1.10 Gate 2: after the slider, on its
 * line); the slider stays where it is (under the finger), the rest of the drag counts and one Undo
 * gives 100 % back. Had the slider moved to another line or along its line at that change, the
 * rest of the drag was lost ([ThicknessKeepsItsLineTest] is the pure rule).
 */
// One test per class (the test recomposer policy is global): Curve and Path are its sections.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q392, instrumentedPackages = ["com.brushwork.paint.v110.a5.thicknessdragsandbox"])
class ThicknessDragKeepsItsPlaceUiTest {

    @Test
    fun theSliderStaysUnderTheFinger() = Qa19Ui.uiTest { h ->
        for (id in listOf(ToolId.CURVE, ToolId.PATH)) h.section("$id: one point dragged up from 100 %") {
            val s = h.editor()
            s.c.selectTool(id)
            val t = s.c.tools.getValue(id) as CurveTool
            for (p in listOf(Vec2(60f, 200f), Vec2(130f, 90f), Vec2(200f, 160f), Vec2(270f, 80f), Vec2(340f, 200f))) assertTrue(t.addAnchor(p))
            t.select(2)
            Qa19Ui.settle()
            assertEquals("$id: the point starts at 100 %", 1f, t.widthOf(2), 0f)
            assertTrue("$id: the More row opens", Qa19Ui.openMoreRow())
            frames(4)
            assertFalse("$id: every point at 100 %: no \"$ALL_100\"", Qa19Ui.has(ALL_100, exact = true))
            val el = slider(s)
            val at = el.node.positionInWindow
            val d = s.density
            // The slider's layout starts SLIDER_REACH_DP inside its semantics node; the thumb at
            // 100 % of the 0..300 % range sits a third of the way along its track.
            val left = at.x + SLIDER_REACH_DP * d
            val width = el.node.size.width - 2f * SLIDER_REACH_DP * d
            val inset = GroupSliderGeometry.THUMB_INSET.value * d
            val x0 = left + GroupSliderGeometry.thumbX(1f / 3f, width, inset)
            val dx = DRAG_DP * d
            // The width when "All points 100 %" first shows, during the drag (the first change).
            var whenItCame = Float.NaN
            drag(el, (0..MOVES).map { x0 + dx * it / MOVES }) {
                if (whenItCame.isNaN() && Qa19Ui.has(ALL_100, exact = true)) whenItCame = t.widthOf(2)
            }
            assertFalse("$id: \"$ALL_100\" came during the drag", whenItCame.isNaN())
            val now = slider(s).node.positionInWindow
            assertEquals("$id: the slider stayed where it was (x)", at.x, now.x, 0.5f)
            assertEquals("$id: the slider stayed where it was (y)", at.y, now.y, 0.5f)
            // The rest of the drag counted: the value went on rising after that first change, by
            // at least a third of the travel left (the slider's touch slop takes the drag's start
            // and the value snaps to the 5 % Percent step).
            val left0 = dx / (width - 2f * inset) * 3f - (whenItCame - 1f)
            assertTrue(
                "$id: the drag went on after \"$ALL_100\" came: ${t.widthOf(2)} from $whenItCame",
                t.widthOf(2) - whenItCame >= left0 / 3f,
            )
            assertTrue("$id", t.undoStep())
            assertEquals("$id: one Undo gives 100 % back", 1f, t.widthOf(2), 0f)
        }
    }

    private companion object {
        /** "All points 100 %" (CurveStrips.kt's item, a literal there). */
        const val ALL_100 = "All points 100 %"

        /** How far the material3 slider's semantics node reaches past its layout at each end (dp), as GroupThicknessControlTest. */
        const val SLIDER_REACH_DP = 10f

        /** The drag: this far to the right (dp), in this many moves. */
        const val DRAG_DP = 36f
        const val MOVES = 9
    }

    /** [n] frames as a phone runs them: each one also lays the screen out (Robolectric otherwise waits for input). */
    private fun frames(n: Int) = repeat(n) {
        Qa19Ui.settle(1)
        RobolectricUi.elements()
    }

    /** The thickness slider of [s]'s window. */
    private fun slider(s: ChromeScreen): RobolectricUi.Element = RobolectricUi.elements().last { e ->
        e.window === s.activity.window.decorView &&
            e.node.config.contains(SemanticsActions.SetProgress) &&
            e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(ThicknessRowLabels.SLIDER) == true
    }

    /** One finger on [el]'s window at the x positions [xs] in turn (down at the first, up at the last), a frame and [afterMove] after each move. */
    private fun drag(el: RobolectricUi.Element, xs: List<Float>, afterMove: () -> Unit) {
        val y = el.node.positionInWindow.y + el.node.size.height / 2f
        val d0 = SystemClock.uptimeMillis()
        var at = d0
        fun send(action: Int, x: Float) {
            val ev = MotionEvent.obtain(d0, at, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            el.window.dispatchTouchEvent(ev)
            ev.recycle()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            at += 16
            frames(1)
        }
        send(MotionEvent.ACTION_DOWN, xs.first())
        for (x in xs.drop(1)) {
            send(MotionEvent.ACTION_MOVE, x)
            afterMove()
        }
        send(MotionEvent.ACTION_UP, xs.last())
        frames(4)
    }
}
