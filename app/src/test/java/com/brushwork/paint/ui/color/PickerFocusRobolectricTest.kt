package com.brushwork.paint.ui.color

import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

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

    private fun settle() = repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)) }

    private fun composeRoot(activity: ComponentActivity): ViewRootForTest {
        fun find(v: View): ViewRootForTest? = when (v) {
            is ViewRootForTest -> v
            is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
            else -> null
        }
        return requireNotNull(find(activity.window.decorView)) { "no Compose root" }
    }

    private fun nodes(root: ViewRootForTest): List<SemanticsNode> {
        val out = mutableListOf<SemanticsNode>()
        fun walk(n: SemanticsNode) { out += n; n.children.forEach(::walk) }
        walk(root.semanticsOwner.unmergedRootSemanticsNode)
        return out
    }

    /** Editable text fields, top to bottom. */
    private fun textFields(root: ViewRootForTest) =
        nodes(root).filter { it.config.getOrNull(SemanticsActions.SetText) != null }.sortedBy { it.boundsInRoot.top }

    private fun byDescription(root: ViewRootForTest, text: String) =
        nodes(root).first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true }

    private fun SemanticsNode.focus() { requireNotNull(config.getOrNull(SemanticsActions.RequestFocus)?.action).invoke() }
    private fun SemanticsNode.type(text: String) { requireNotNull(config.getOrNull(SemanticsActions.SetText)?.action).invoke(AnnotatedString(text)) }
    private val SemanticsNode.focused get() = config.getOrNull(SemanticsProperties.Focused) == true
    private val SemanticsNode.text get() = config.getOrNull(SemanticsProperties.EditableText)?.text

    private fun tap(v: View, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }.also { v.dispatchTouchEvent(it); it.recycle() }
        MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_UP, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }.also { v.dispatchTouchEvent(it); it.recycle() }
        settle()
    }

    @Test
    fun typedValuesDoNotOverwriteLaterChanges() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val start = ColorUtils.rgb(10, 20, 30)
        val state = ColorEditState(start, Hsb.fromColor(start)) {}
        activity.setContent { BrushworkTheme { Surface { Column { HexField(state, withAlpha = false); RgbSliders(state) } } } }
        settle()
        val root = composeRoot(activity)
        val view = root as View

        // --- Channel field, then the same channel's slider.
        val (_, redField) = textFields(root)
        redField.focus()
        settle()
        redField.type("200")
        settle()
        assertEquals("typed values apply live", 200, ColorUtils.red(state.color))
        assertTrue(textFields(root)[1].focused)

        val slider = byDescription(root, "Red").boundsInRoot
        val r = 12f * 1.5f // thumb radius, hdpi
        tap(view, slider.left + r + 0.2f * (slider.width - 2f * r), slider.center.y)
        assertEquals("slider tap sets red", 51, ColorUtils.red(state.color))
        assertTrue("touching a slider takes focus from the field", textFields(root).none { it.focused })
        // Whatever happens to focus later, the typed 200 must not come back.
        view.clearFocus()
        settle()
        assertEquals(51, ColorUtils.red(state.color))
        assertEquals(20, ColorUtils.green(state.color))
        assertEquals(30, ColorUtils.blue(state.color))
        assertEquals("51", textFields(root)[1].text)

        // --- Focused hex field with half-typed text follows a change made elsewhere.
        textFields(root)[0].focus()
        settle()
        textFields(root)[0].type("12")
        settle()
        assertEquals(51, ColorUtils.red(state.color))
        state.setBlue(255)
        settle()
        val hex = textFields(root)[0]
        assertTrue(hex.focused)
        assertEquals(hexDigits(state.color, false), hex.text)
        // A complete hex value applies while typing and stays as typed.
        hex.type("00ff00")
        settle()
        assertEquals(ColorUtils.rgb(0, 255, 0), state.color)
        assertEquals("00FF00", textFields(root)[0].text)
    }
}
