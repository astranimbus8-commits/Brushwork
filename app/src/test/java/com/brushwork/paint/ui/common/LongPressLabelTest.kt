package com.brushwork.paint.ui.common

import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
 * v1.8 (item 9, design §3.9, §4.6): `Modifier.longPressLabel`. A long press shows the label in a
 * bubble (tag [V18Tags.LONG_PRESS_BUBBLE]) under the element for 1.5 s and never fires the
 * element's click; a tap still acts and shows no bubble; a drag shows none. Whether the modifier
 * comes before or after `clickable` makes no difference. Outside a [LongPressLabelHost] a long
 * press shows nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.common.longpresssandbox"])
class LongPressLabelTest {

    private fun send(v: View, downTime: Long, time: Long, action: Int, x: Float, y: Float) {
        val e = MotionEvent.obtain(downTime, time, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        v.dispatchTouchEvent(e)
        e.recycle()
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /**
     * Holds for [ms] of REAL time while running the looper: Compose times the long press and the
     * bubble with coroutine delays on the wall clock that resume on the main looper.
     */
    private fun holdRealTime(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            Thread.sleep(10)
            idle(10)
        }
    }

    private fun bubble(): String? = StripTestKit.tagged(V18Tags.LONG_PRESS_BUBBLE)?.let { b ->
        generateSequence(listOf(b)) { level -> level.flatMap { it.children }.ifEmpty { null } }.flatten()
            .firstNotNullOfOrNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text }
    }

    private fun element(description: String): RobolectricUi.Element = RobolectricUi.byDescription(description)

    /** Down on [description]'s middle, held [holdMs], then up (moved [dx] px on the way when given). */
    private fun press(description: String, holdMs: Long, dx: Float = 0f, check: () -> Unit = {}) {
        val e = element(description)
        val x = e.bounds.center.x
        val y = e.bounds.center.y
        val t0 = SystemClock.uptimeMillis()
        send(e.window, t0, t0, MotionEvent.ACTION_DOWN, x, y)
        if (dx != 0f) {
            for (s in 1..6) {
                idle(16)
                send(e.window, t0, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x + dx * s / 6f, y)
            }
        }
        holdRealTime(holdMs)
        check()
        send(e.window, t0, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x + dx, y)
        idle(50)
    }

    @Test
    fun aLongPressShowsTheLabelAndATapStillActs() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val clicks = IntArray(3)
        activity.setContent {
            BrushworkTheme {
                Column(Modifier.fillMaxWidth()) {
                    LongPressLabelHost(Modifier.fillMaxWidth().height(300.dp)) {
                        Column {
                            Spacer(Modifier.height(40.dp))
                            // The label before the click, then after it.
                            Box(Modifier.size(48.dp).longPressLabel("Probe before").clickable { clicks[0]++ }.semantics { contentDescription = "Probe A" })
                            Spacer(Modifier.height(40.dp))
                            Box(Modifier.size(48.dp).clickable { clicks[1]++ }.longPressLabel("Probe after").semantics { contentDescription = "Probe B" })
                        }
                    }
                    // Outside any host.
                    Box(Modifier.size(48.dp).longPressLabel("Probe no host").clickable { clicks[2]++ }.semantics { contentDescription = "Probe C" })
                }
            }
        }
        RobolectricUi.settle(10, 50)

        for ((i, pair) in listOf("Probe A" to "Probe before", "Probe B" to "Probe after").withIndex()) {
            val (target, label) = pair
            // A tap acts and shows nothing.
            element(target).tap()
            assertEquals("$target: a tap clicks", 1, clicks[i])
            assertNull("$target: no bubble on a tap", bubble())
            // A long press: the bubble with the label, under the element; no click.
            press(target, holdMs = 800) {
                assertEquals("$target: the bubble shows while held", label, bubble())
            }
            RobolectricUi.settle(4, 50)
            assertEquals("$target: the long press never clicks", 1, clicks[i])
            assertEquals("$target: the bubble stays after the finger lifts", label, bubble())
            val b = StripTestKit.tagged(V18Tags.LONG_PRESS_BUBBLE)!!
            val el = element(target).node
            assertTrue("$target: the bubble is under the element", b.boundsInRoot.top >= el.boundsInRoot.bottom)
            // Gone after 1.5 s.
            holdRealTime(LONG_PRESS_BUBBLE_MS + 300)
            RobolectricUi.settle(2, 50)
            assertNull("$target: the bubble goes after 1.5 s", bubble())
            // A drag past the touch slop shows none.
            press(target, holdMs = 800, dx = 120f)
            assertNull("$target: no bubble on a drag", bubble())
            // And a tap still acts after all that.
            element(target).tap()
            assertEquals("$target: a tap still clicks", 2, clicks[i])
            assertNull(bubble())
        }

        // Outside a host: no bubble, the tap still acts.
        press("Probe C", holdMs = 800) { assertNull("no host: no bubble", bubble()) }
        RobolectricUi.settle(4, 50)
        assertNull(bubble())
        val before = clicks[2]
        element("Probe C").tap()
        assertEquals("no host: a tap clicks", before + 1, clicks[2])
    }
}
