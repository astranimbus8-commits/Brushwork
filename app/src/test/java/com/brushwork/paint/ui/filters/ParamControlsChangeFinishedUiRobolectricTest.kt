package com.brushwork.paint.ui.filters

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brushwork.paint.AppSettings
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterValues
import com.brushwork.paint.filters.GradientStop
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.snap.Increments
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.LocalIncrements
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.6 §3.1a review fix: every parameter control tells its host when a change is DONE
 * ([ParamHost.onChangeFinished]; the Adjust sheet then lets the live adjustment refine to the
 * exact image at once instead of 150 ms later): a switch and a chip on the tap, the tone-curve and
 * gradient editors when the finger lifts after changing them, the −/+ on release. With increments
 * on (§3.4) the −/+ move by the slider's step. Own sandbox (one Compose screen per test class).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.filters.settlesandbox"])
class ParamControlsChangeFinishedUiRobolectricTest {

    @Test
    fun switchesChipsAndTheCurveAndGradientEditorsSayWhenAChangeIsDone() {
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val params = listOf(
            FilterParam.Toggle("t", "Invert result", false),
            FilterParam.Choice("ch", "Channel", listOf("Red", "Green", "Blue")),
            FilterParam.Curve("cv", "Curve"),
            FilterParam.Gradient("g", "Colors", listOf(GradientStop(0f, 0xFF000000.toInt()), GradientStop(1f, -1))),
            FilterParam.Slider("ev", "Exposure", -5f, 5f, 0f, 0.01f, suffix = "EV"),
        )
        var values by mutableStateOf(FilterValues(params.associate { it.key to it.defaultValue() }))
        val updates = mutableListOf<String>()
        var finished = 0
        val host = ParamHost(
            valuesOf = { values },
            update = { k, v -> updates += k; values = values.copy().set(k, v) },
            resetParam = {},
            onChangeFinished = { finished++ },
        )
        // Increments on, with a 0.25 EV step for Exposure (the editor provides LocalIncrements).
        val settings = AppSettings(activity.applicationContext)
        settings.prefs.edit().clear().commit()
        val inc = Increments(settings).also { i -> i.update { it.copy(enabled = true).withCustom("Exposure|EV", 0.25f) } }
        activity.setContent {
            BrushworkTheme {
                CompositionLocalProvider(LocalIncrements provides inc) {
                    Column { params.forEach { FilterParamControl(host, it, enabled = true) } }
                }
            }
        }
        SmokeUi.settle()

        SmokeUi.click("Invert result", exact = true)
        assertEquals(listOf("t"), updates)
        assertEquals("a switch is done on the tap", 1, finished)

        SmokeUi.click("Green", exact = true)
        assertEquals(listOf("t", "ch"), updates)
        assertEquals("a chip is done on the tap", 2, finished)

        // A point added in the middle of the curve box and dragged up: done when the finger lifts.
        val curve = RobolectricUi.byDescription("Tone curve editor")
        val cb = curve.bounds
        RobolectricUi.drag(curve.window, cb.center.x to cb.center.y, cb.center.x to cb.center.y - cb.height / 6f)
        assertTrue("the curve changed: $updates", updates.count { it == "cv" } >= 2)
        assertEquals("the curve drag is done once, on lift", 3, finished)

        // A tap on the gradient bar between its two stops adds one: done when the finger lifts.
        val gradient = RobolectricUi.byDescription("Gradient editor")
        val gb = gradient.bounds
        RobolectricUi.tap(gradient.window, gb.center.x, gb.top + 6f)
        assertTrue("a stop was added: $updates", "g" in updates)
        assertEquals("the gradient tap is done once, on lift", 4, finished)

        // §3.4: the −/+ beside a slider move by its increment (0.25 EV), done on release.
        fun press(label: String) {
            val b = RobolectricUi.byDescription(label)
            RobolectricUi.tap(b.window, b.bounds.center.x, b.bounds.center.y)
        }
        press("Increase Exposure")
        assertEquals(0.25f, values.float("ev"), 1e-6f)
        assertEquals("a −/+ press is done on release", 5, finished)
        press("Increase Exposure")
        assertEquals(0.5f, values.float("ev"), 1e-6f)
        values = values.copy().set("ev", 0.13f)
        SmokeUi.settle()
        press("Decrease Exposure")
        assertEquals("from between two steps: the nearer one that way", 0f, values.float("ev"), 1e-6f)
    }
}
