package com.brushwork.paint.qa3

import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.editor.EditorScreen
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.time.Duration

/**
 * Final QA (v1.5 §4.5): double-tapping the point thickness slider puts the point back to 100 %
 * as ONE undo step. The first tap of the double tap moves the value to where the finger is; undo
 * must then go back to the value before the double tap, not to that accidental one.
 *
 * Own sandbox; all UI work in ONE test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa3.thicknesssandbox"])
class Qa3CurveThicknessUiRobolectricTest {

    @Test
    fun doubleTapResetIsOneStep() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val ctl = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val activity = ctl.get()
            val c = Smoke.controller(activity, Smoke.document(400, 300, layers = 2))
            activity.setContent { BrushworkTheme { EditorScreen(c, onExit = {}, onSaveNow = {}) } }
            settle()
            c.selectTool(ToolId.CURVE)
            val tool = c.tools.getValue(ToolId.CURVE) as CurveTool
            tool.update { it.copy(stroke = CurveStroke.PLAIN) }
            for (p in listOf(Vec2(60f, 200f), Vec2(200f, 80f), Vec2(340f, 200f))) tool.addAnchor(p)
            tool.select(1)
            tool.setWidth(1, 2f)
            tool.endNumericEdit()
            repeat(10) { settle(2); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
            val slider = RobolectricUi.elements().last { e ->
                e.node.layoutInfo.isPlaced && e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Point thickness slider") == true
            }
            val b = slider.bounds
            assertTrue("the slider is on the screen: $b", b.left >= 0f && b.right <= activity.window.decorView.width)
            // A quick double tap at a fifth of the track (about 60 %), away from the thumb (200 %).
            val x = b.left + b.width * 0.2f
            val y = b.center.y
            val t0 = SystemClock.uptimeMillis()
            fun send(down: Long, at: Long, action: Int) {
                val ev = MotionEvent.obtain(down, at, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                slider.window.dispatchTouchEvent(ev)
                ev.recycle()
            }
            fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
            send(t0, t0, MotionEvent.ACTION_DOWN); idle(40)
            send(t0, t0 + 40, MotionEvent.ACTION_UP); idle(80)
            send(t0 + 120, t0 + 120, MotionEvent.ACTION_DOWN); idle(40)
            send(t0 + 120, t0 + 160, MotionEvent.ACTION_UP); idle(40)
            settle(4)
            assertEquals("double tap: 100 %", 1f, tool.anchors[1].width, 1e-4f)
            // One undo goes back to 200 %, the value before the double tap.
            c.undo()
            settle(2)
            assertEquals("undo after the double tap", 2f, tool.anchors[1].width, 1e-4f)
            c.undo()
            assertEquals("the step before", 1f, tool.anchors[1].width, 1e-4f)

            // Another point selected: the double tap resets THAT point.
            tool.setWidth(1, 2f)
            tool.endNumericEdit()
            tool.select(2)
            tool.setWidth(2, 2.5f)
            tool.endNumericEdit()
            repeat(10) { settle(2); idle(16) }
            val t1 = SystemClock.uptimeMillis()
            fun send2(down: Long, at: Long, action: Int) {
                val s2 = RobolectricUi.elements().last { e ->
                    e.node.layoutInfo.isPlaced && e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Point thickness slider") == true
                }
                val ev = MotionEvent.obtain(down, at, action, s2.bounds.left + s2.bounds.width * 0.2f, s2.bounds.center.y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                s2.window.dispatchTouchEvent(ev)
                ev.recycle()
            }
            send2(t1, t1, MotionEvent.ACTION_DOWN); idle(40)
            send2(t1, t1 + 40, MotionEvent.ACTION_UP); idle(80)
            send2(t1 + 120, t1 + 120, MotionEvent.ACTION_DOWN); idle(40)
            send2(t1 + 120, t1 + 160, MotionEvent.ACTION_UP); idle(40)
            settle(4)
            assertEquals("the selected point is reset", 1f, tool.anchors[2].width, 1e-4f)
            assertEquals("the point selected before is left alone", 2f, tool.anchors[1].width, 1e-4f)
            c.undo()
            assertEquals(2.5f, tool.anchors[2].width, 1e-4f)
        } finally {
            runCatching { ctl.pause().stop().destroy() }
        }
    }
}
