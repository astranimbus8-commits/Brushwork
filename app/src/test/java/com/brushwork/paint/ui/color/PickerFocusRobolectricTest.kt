package com.brushwork.paint.ui.color

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.ui.color.RobolectricUi.byDescription
import com.brushwork.paint.ui.color.RobolectricUi.settle
import com.brushwork.paint.ui.color.RobolectricUi.tap
import com.brushwork.paint.ui.color.RobolectricUi.textFields
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Text fields vs. the other picker controls, with real focus and touch events: a number typed
 * into a channel field must not come back (on the field's focus-loss commit) over a change made
 * afterwards with a slider, and a focused hex field must follow color changes made elsewhere.
 *
 * Own sandbox (see [ColorPickerUiSmokeTest]): later tests in a shared sandbox get no frames.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-hdpi", instrumentedPackages = ["com.brushwork.paint.ui.color.focustestsandbox"])
class PickerFocusRobolectricTest {

    @Test
    fun typedValuesDoNotOverwriteLaterChanges() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val start = ColorUtils.rgb(10, 20, 30)
        val state = ColorEditState(start, Hsb.fromColor(start)) {}
        activity.setContent {
            BrushworkTheme { Surface { Column { HexField(state, withAlpha = false); RgbSliders(state); HsbWheel(state, Modifier.size(160.dp)) } } }
        }
        settle(10, 50)

        // --- Channel field, then the same channel's slider. Fields: hex, R, G, B.
        textFields()[1].focus()
        settle(10, 50)
        textFields()[1].type("200")
        settle(10, 50)
        assertEquals("typed values apply live", 200, ColorUtils.red(state.color))
        assertTrue(textFields()[1].focused)

        val slider = byDescription("Red")
        val b = slider.bounds
        val r = 12f * activity.resources.displayMetrics.density // thumb radius
        tap(slider.window, b.left + r + 0.2f * (b.width - 2f * r), b.center.y)
        assertEquals("slider tap sets red", 51, ColorUtils.red(state.color))
        assertTrue("touching a slider takes focus from the field", textFields().none { it.focused })
        // Whatever happens to focus later, the typed 200 must not come back.
        slider.window.clearFocus()
        settle(10, 50)
        assertEquals(51, ColorUtils.red(state.color))
        assertEquals(20, ColorUtils.green(state.color))
        assertEquals(30, ColorUtils.blue(state.color))
        assertEquals("51", textFields()[1].text)

        // --- Focused hex field with half-typed text follows a change made elsewhere.
        textFields()[0].focus()
        settle(10, 50)
        textFields()[0].type("12")
        settle(10, 50)
        assertEquals(51, ColorUtils.red(state.color))
        state.setBlue(255)
        settle(10, 50)
        val hex = textFields()[0]
        assertTrue(hex.focused)
        assertEquals(hexDigits(state.color, false), hex.text)
        // A complete hex value applies while typing and stays as typed.
        hex.type("00ff00")
        settle(10, 50)
        assertEquals(ColorUtils.rgb(0, 255, 0), state.color)
        assertEquals("00FF00", textFields()[0].text)

        // The wheel's screen-reader state follows the color.
        assertEquals("Hue 120°, saturation 100%, brightness 100%", byDescription("Color wheel").stateDescription)
    }
}
