package com.brushwork.paint.probes

import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Slider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.RepeatIconButton
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * v1.7 F0 touch-down audit (item 10, §3.10), the two widget kinds the audit could not settle by
 * reading alone: a Material3 `Slider` changes nothing while the finger is only down (it moves on
 * the tap's release or after the drag slop), and [RepeatIconButton] steps on touch-down (then
 * repeats after 400 ms), so a history tap whose first finger lands on a ± button must be covered
 * by `restoreUiMark`. The full list is in the F0 commit and `.wt/_tools/v17-touchdown-audit.md`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.probes.touchdownsandbox"])
class TouchDownProbeUiTest {
    private fun send(v: View, downTime: Long, time: Long, action: Int, x: Float, y: Float) {
        val e = MotionEvent.obtain(downTime, time, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        v.dispatchTouchEvent(e)
        e.recycle()
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test
    fun aSliderWaitsForTheReleaseAndARepeatButtonStepsOnDown() {
        SmokeUi.installTestRecomposer()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        var value = 0f
        var sliderChanges = 0
        var steps = 0
        activity.setContent {
            BrushworkTheme {
                Column(Modifier.fillMaxWidth()) {
                    Slider(
                        value = value,
                        onValueChange = { value = it; sliderChanges++ },
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "probe slider" },
                    )
                    RepeatIconButton(Icons.Filled.Add, "probe step") { steps++ }
                }
            }
        }
        RobolectricUi.settle()

        // The slider: down far from the thumb, held 200 ms (inside the 300 ms tap window).
        val slider = RobolectricUi.byDescription("probe slider")
        val sx = slider.bounds.left + slider.bounds.width * 0.8f
        val sy = slider.bounds.center.y
        val t0 = SystemClock.uptimeMillis()
        send(slider.window, t0, t0, MotionEvent.ACTION_DOWN, sx, sy)
        idle(200)
        assertEquals("a Material3 slider changes nothing on touch-down", 0, sliderChanges)
        send(slider.window, t0, t0 + 200, MotionEvent.ACTION_UP, sx, sy)
        RobolectricUi.settle()
        assertEquals("the tap's release moves it", true, sliderChanges > 0 && value > 0.5f)

        // The repeat button: one step on touch-down, before any release.
        val button = RobolectricUi.byDescription("probe step")
        val bx = button.bounds.center.x
        val by = button.bounds.center.y
        val t1 = SystemClock.uptimeMillis()
        send(button.window, t1, t1, MotionEvent.ACTION_DOWN, bx, by)
        idle(100)
        assertEquals("RepeatIconButton steps on touch-down", 1, steps)
        send(button.window, t1, t1 + 100, MotionEvent.ACTION_UP, bx, by)
        RobolectricUi.settle()
        assertEquals("and not again on the release", 1, steps)
    }
}
