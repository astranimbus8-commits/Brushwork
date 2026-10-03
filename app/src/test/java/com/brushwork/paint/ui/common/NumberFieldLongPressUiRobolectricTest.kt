package com.brushwork.paint.ui.common

import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.brushwork.paint.AppSettings
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.snap.Increments
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.time.Duration

/**
 * v1.6 §3.4 (G): a finger held on a NumberField opens the Step popup and nothing else. The text
 * field's own long-press (focus, select a word, keyboard) would otherwise fire on the same touch
 * behind the popup: the field is never focused while the finger is held, nor after the popup
 * closes, and nothing is committed. A tap afterwards still types into it.
 * Own sandbox, one test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.common.fieldlongpresssandbox"])
class NumberFieldLongPressUiRobolectricTest {

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun hold(e: RobolectricUi.Element, holdMs: Long) {
        val x = e.bounds.center.x
        val y = e.bounds.center.y
        val t0 = SystemClock.uptimeMillis()
        fun send(action: Int) {
            val ev = MotionEvent.obtain(t0, SystemClock.uptimeMillis(), action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            e.window.dispatchTouchEvent(ev)
            ev.recycle()
        }
        send(MotionEvent.ACTION_DOWN)
        val end = System.currentTimeMillis() + holdMs
        var focusedWhileHeld = false
        while (System.currentTimeMillis() < end) {
            Thread.sleep(10)
            idle(10)
            if (RobolectricUi.textFields().any { it.window === e.window && it.focused }) focusedWhileHeld = true
        }
        send(MotionEvent.ACTION_UP)
        SmokeUi.settle()
        assertFalse("the field behind the popup took focus while the finger was held", focusedWhileHeld)
    }

    @Test
    fun aHeldFieldOpensOnlyThePopup() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val settings = AppSettings(RuntimeEnvironment.getApplication()).also { it.prefs.edit().clear().commit() }
        val inc = Increments(settings)
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        var angle by mutableDoubleStateOf(30.0)
        var finished = 0
        var focusManager: FocusManager? = null
        activity.setContent {
            focusManager = LocalFocusManager.current
            BrushworkTheme {
                CompositionLocalProvider(LocalIncrements provides inc) {
                    Surface {
                        Column(Modifier.padding(16.dp)) {
                            NumberField(
                                "Rotation", angle, { angle = it }, decimals = 1, suffix = "°", min = -360.0, max = 360.0,
                                adjust = NumberAdjust.NONE, onValueChangeFinished = { finished++ },
                            )
                        }
                    }
                }
            }
        }
        SmokeUi.settle()
        // Robolectric is not in touch mode: the window may hand the field focus on its own (a
        // finger on a phone never does). Start from no focus, as on the phone.
        focusManager!!.clearFocus(force = true)
        SmokeUi.settle()
        finished = 0
        val field = RobolectricUi.textFields().last()
        assertFalse(field.focused)
        hold(field, holdMs = 900)
        assertTrue("held: the popup", SmokeUi.has("Step for angles", exact = true))
        val behind = RobolectricUi.textFields().filter { it.window === field.window }
        assertTrue("held: the field behind is not focused", behind.none { it.focused })
        SmokeUi.click("Cancel", exact = true)
        assertFalse(SmokeUi.has("Step for angles", exact = true))
        SmokeUi.settle()
        assertTrue("after the popup: the field is not focused", RobolectricUi.textFields().none { it.focused })
        assertEquals(30.0, angle, 0.0)
        assertEquals("nothing was committed", 0, finished)

        // A tap afterwards still types into the field (the hold no longer refuses focus).
        val again = RobolectricUi.textFields().last()
        again.tap()
        SmokeUi.settle()
        assertTrue("a tap focuses the field", RobolectricUi.textFields().last().focused)
        assertFalse(SmokeUi.has("Step for angles", exact = true))
    }
}
