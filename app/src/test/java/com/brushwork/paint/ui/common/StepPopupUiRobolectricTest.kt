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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import com.brushwork.paint.AppSettings
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.snap.Increments
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import kotlin.math.abs
import kotlin.math.round

/**
 * v1.6 §3.4 (d) StepPopupUiRobolectricTest (area G): a long-press on a slider's value opens the
 * Step popup in a window of its own. "Layer opacity" (a 0..1 percentage slider) gets a Percent
 * step of 10 and its slider then lands on multiples of 10 %; "Exposure" (EV, no kind) gets a step
 * of its own, 0.25 EV, under the key "Exposure|EV", and its slider lands on multiples of 0.25. A
 * real finger held on the value opens the popup without opening the value editor; a typed value
 * stays exactly as typed. A slider that reads "None" at 0 keeps the unit it showed last (a Size
 * stays a Size). (The layer window's own "Layer opacity" control is area F's: it hooks
 * the same popup through `Modifier.stepOnLongPress`.)
 *
 * One test (Compose's frame clock only serves the first test of a sandbox), own sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.common.steppopupsandbox"])
class StepPopupUiRobolectricTest {

    private fun settle(n: Int = 12) = SmokeUi.settle(n)

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** The value of the slider labelled [label] (its typing button carries "Type a value for $label"). */
    private fun valueButton(label: String): RobolectricUi.Element = RobolectricUi.elements().last { e ->
        e.node.config.getOrNull(SemanticsActions.OnClick)?.label == "Type a value for $label"
    }

    /** The slider under the value of [label] (the harness shows "Layer opacity" above "Exposure"). */
    private fun slider(label: String): RobolectricUi.Element {
        val all = RobolectricUi.elements()
            .filter { it.node.layoutInfo.isPlaced && it.node.config.getOrNull(SemanticsActions.SetProgress) != null }
            .sortedBy { it.bounds.top }
        return all[if (label == "Layer opacity") 0 else 1]
    }

    private fun longClick(e: RobolectricUi.Element) {
        val action = e.node.config.getOrNull(SemanticsActions.OnLongClick)
        assertNotNull("a long-press action on ${e.node.config}", action)
        requireNotNull(action!!.action).invoke()
        settle()
    }

    /** A real finger resting on [e] for [holdMs] of wall-clock time (pointer timeouts run on real delays). */
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
        while (System.currentTimeMillis() < end) { Thread.sleep(10); idle(10) }
        send(MotionEvent.ACTION_UP)
        settle()
    }

    private fun onMultiple(v: Float, step: Float): Boolean {
        val k = v / step
        return abs(k - round(k)) < 1e-3f
    }

    @Test
    fun stepPopup() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val settings = AppSettings(RuntimeEnvironment.getApplication()).also { it.prefs.edit().clear().commit() }
        val inc = Increments(settings)
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        var opacity by mutableFloatStateOf(0.5f)
        var exposure by mutableFloatStateOf(0f)
        var stroke by mutableFloatStateOf(0f)
        activity.setContent {
            BrushworkTheme {
                CompositionLocalProvider(LocalIncrements provides inc) {
                    Surface {
                        Column(Modifier.padding(16.dp)) {
                            LabeledSlider(
                                "Layer opacity", opacity, { opacity = it }, 0f..1f,
                                valueText = "${(opacity * 100f).toInt()}%", typing = SliderTyping.Percent,
                            )
                            LabeledSlider(
                                "Exposure", exposure, { exposure = it }, -5f..5f,
                                valueText = "%+.2f EV".format(exposure), typing = SliderTyping(decimals = 2, suffix = "EV"),
                            )
                            // Reads "None" at 0 (no number, so no unit), "4.37 px" otherwise.
                            LabeledSlider(
                                "Stroke", stroke, { stroke = it }, 0f..20f,
                                valueText = if (stroke <= 0f) "None" else "%.2f px".format(java.util.Locale.US, stroke),
                            )
                        }
                    }
                }
            }
        }
        settle()

        // Off (the default): a slider moves freely, exactly as v1.5.
        requireNotNull(slider("Layer opacity").node.config[SemanticsActions.SetProgress].action).invoke(0.437f)
        settle(4)
        assertEquals(0.437f, opacity, 1e-6f)

        // Long-press "Layer opacity": the Percent step's popup, in its own window.
        longClick(valueButton("Layer opacity"))
        assertTrue("the popup shows: ${SmokeUi.shown()}", SmokeUi.has("Step for percentages", exact = true))
        assertTrue("its own window", SmokeUi.windows().size >= 2)
        SmokeUi.click("Use increments", exact = true)
        assertTrue("the popup's switch turns increments on", inc.enabled)
        SmokeUi.typeAndDone("Percent step", "10")
        assertEquals(10f, inc.state.percent, 0f)
        assertFalse("Done closes the popup", SmokeUi.has("Step for percentages", exact = true))

        // The slider now lands on multiples of 10 %; its ends stay reachable.
        requireNotNull(slider("Layer opacity").node.config[SemanticsActions.SetProgress].action).invoke(0.463f)
        settle(4)
        assertEquals(0.5f, opacity, 1e-5f)
        val s = slider("Layer opacity")
        RobolectricUi.drag(s.window, s.bounds.left + s.bounds.width * 0.2f to s.bounds.center.y, s.bounds.left + s.bounds.width * 0.73f to s.bounds.center.y)
        assertTrue("a real drag lands on a multiple of 10 %: $opacity", onMultiple(opacity, 0.1f))
        requireNotNull(slider("Layer opacity").node.config[SemanticsActions.SetProgress].action).invoke(1f)
        settle(4)
        assertEquals(1f, opacity, 0f)

        // "Exposure" has no kind: a step of its own under "Exposure|EV".
        longClick(valueButton("Exposure"))
        assertTrue(SmokeUi.has("Step for Exposure", exact = true))
        SmokeUi.typeAndDone("Exposure step", "0.25")
        assertEquals(0.25f, inc.state.custom["Exposure|EV"])
        requireNotNull(slider("Exposure").node.config[SemanticsActions.SetProgress].action).invoke(1.13f)
        settle(4)
        assertEquals(1.25f, exposure, 1e-5f)
        val ex = slider("Exposure")
        RobolectricUi.drag(ex.window, ex.bounds.left + ex.bounds.width * 0.5f to ex.bounds.center.y, ex.bounds.left + ex.bounds.width * 0.61f to ex.bounds.center.y)
        assertTrue("a real drag lands on a multiple of 0.25 EV: $exposure", onMultiple(exposure, 0.25f))

        // A real finger held on the value opens the popup, not the value editor.
        hold(valueButton("Layer opacity"), holdMs = 900)
        assertTrue("held: the popup", SmokeUi.has("Step for percentages", exact = true))
        assertTrue("held: no editor behind it", RobolectricUi.textFields().none { it.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Layer opacity") == true })
        SmokeUi.click("Cancel", exact = true)
        assertFalse(SmokeUi.has("Step for percentages", exact = true))

        // Typed values are never quantized.
        SmokeUi.click("Type a value for Layer opacity")
        val editor = RobolectricUi.textFields().last { it.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Layer opacity") == true }
        editor.type("37")
        settle(2)
        requireNotNull(editor.node.config.getOrNull(SemanticsActions.OnImeAction)?.action).invoke()
        settle(4)
        assertEquals(0.37f, opacity, 1e-6f)

        // "No step" removes a control's own step.
        longClick(valueButton("Exposure"))
        SmokeUi.click("No step", exact = true)
        assertEquals(null, inc.state.custom["Exposure|EV"])

        // A slider that reads "None" at 0 keeps the unit it showed last ("px": a Size), so it
        // doesn't turn into a control of its own while it rests at 0.
        stroke = 4.37f
        settle(4)
        stroke = 0f
        settle(4)
        assertTrue(SmokeUi.has("None", exact = true))
        val strokeValue = RobolectricUi.elements().lastOrNull { it.node.config.getOrNull(SemanticsActions.OnLongClick)?.label == "Step for sizes" }
        assertNotNull("\"None\" still offers the Size step: ${RobolectricUi.elements().mapNotNull { it.node.config.getOrNull(SemanticsActions.OnLongClick)?.label }}", strokeValue)
        val strokeSlider = RobolectricUi.elements()
            .filter { it.node.layoutInfo.isPlaced && it.node.config.getOrNull(SemanticsActions.SetProgress) != null }
            .sortedBy { it.bounds.top }[2]
        requireNotNull(strokeSlider.node.config[SemanticsActions.SetProgress].action).invoke(6.63f)
        settle(4)
        assertEquals("from 0 the first move already lands on the Size step", 7f, stroke, 1e-5f)
    }
}
