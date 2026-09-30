package com.brushwork.paint.ui.common

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import com.brushwork.paint.core.Units
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.FieldUi.field
import com.brushwork.paint.ui.common.FieldUi.progress
import com.brushwork.paint.ui.common.FieldUi.setProgress
import com.brushwork.paint.ui.common.FieldUi.slider
import com.brushwork.paint.ui.common.FieldUi.sliders
import com.brushwork.paint.ui.color.RobolectricUi.drag
import com.brushwork.paint.ui.color.RobolectricUi.elements
import com.brushwork.paint.ui.color.RobolectricUi.settle
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/*
 * NumberField with real composition, semantics actions and touch events (phone-sized, hdpi):
 * the slider and the text follow each other, a typed number can't come back over a slider change,
 * the scrub handle of an unbounded field drags the value, and fields survive unbounded widths
 * (scrolling strips) and intrinsic measurement (dropdown menus).
 *
 * Compose's frame clock only serves the first test of a Robolectric sandbox (see
 * ColorPickerUiSmokeTest), and these tests need recomposition: one test per class, each class in
 * its own sandbox.
 */

/** Lookups shared by the NumberField tests. */
internal object FieldUi {
    fun sliders() = elements().filter { it.node.config.getOrNull(SemanticsActions.SetProgress) != null }

    fun slider(label: String) = sliders().single { it.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true }

    fun field(label: String) = RobolectricUi.textFields().single { e ->
        fun labels(n: androidx.compose.ui.semantics.SemanticsNode): List<String> =
            n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + n.children.flatMap { labels(it) }
        label in labels(e.node)
    }

    fun progress(label: String): Float = slider(label).node.config[SemanticsProperties.ProgressBarRangeInfo].current

    fun setProgress(label: String, f: Float) {
        requireNotNull(slider(label).node.config[SemanticsActions.SetProgress].action).invoke(f)
        settle(4, 50)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-hdpi", instrumentedPackages = ["com.brushwork.paint.ui.common.numberfieldsyncsandbox"])
class NumberFieldSliderSyncTest {

    @Test
    fun sliderAndTextFollowEachOther() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        var size by mutableDoubleStateOf(12.0)
        var finished by mutableIntStateOf(0)
        activity.setContent {
            BrushworkTheme {
                Surface {
                    Column(Modifier.padding(16.dp)) {
                        NumberField(
                            "Size", size, { size = it }, Modifier.fillMaxWidth(),
                            decimals = 1, suffix = "px", min = 0.5, max = 1000.0, logSlider = true,
                            onValueChangeFinished = { finished++ },
                        )
                    }
                }
            }
        }
        settle(10, 50)
        val scale = SliderScale.log(0.5, 1000.0)
        assertEquals(scale.fraction(12.0), progress("Size"), 1e-4f)

        // Slider -> value and text, rounded to a sensible precision, one "finished".
        setProgress("Size", 0.5f)
        val expected = NumberSliderMath.sliderValue(0.5f, scale, 1, 0.5, 1000.0)
        assertEquals(expected, size, 0.0)
        assertEquals(Units.formatNumber(expected, 1), field("Size").text)
        assertTrue("slider release finishes the change ($finished)", finished >= 1)

        // Typed text -> value and slider.
        field("Size").focus()
        settle(4, 50)
        field("Size").type("300")
        settle(4, 50)
        assertEquals(300.0, size, 0.0)
        assertEquals(scale.fraction(300.0), progress("Size"), 1e-4f)

        // A number typed and then overridden with the slider doesn't come back on focus loss.
        field("Size").type("12")
        settle(4, 50)
        assertEquals(12.0, size, 0.0)
        setProgress("Size", 0.9f)
        val dragged = NumberSliderMath.sliderValue(0.9f, scale, 1, 0.5, 1000.0)
        assertEquals(dragged, size, 0.0)
        assertEquals("the focused field shows the slider's value", Units.formatNumber(dragged, 1), field("Size").text)
        field("Size").window.clearFocus()
        settle(4, 50)
        assertEquals("focus-loss commit keeps the slider's value", dragged, size, 0.0)
        // Out-of-range typing is clamped on commit and the slider pins at the end.
        field("Size").focus()
        settle(4, 50)
        field("Size").type("5000")
        settle(4, 50)
        field("Size").window.clearFocus()
        settle(4, 50)
        assertEquals(1000.0, size, 0.0)
        assertEquals(1f, progress("Size"), 1e-6f)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-hdpi", instrumentedPackages = ["com.brushwork.paint.ui.common.numberfieldscrubsandbox"])
class NumberFieldScrubTest {

    @Test
    fun scrubHandleDragsAnUnboundedValue() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        var x by mutableDoubleStateOf(100.0)
        var finished by mutableIntStateOf(0)
        activity.setContent {
            BrushworkTheme {
                Surface {
                    Column(Modifier.padding(16.dp)) {
                        NumberField("X", x, { x = it }, Modifier.fillMaxWidth(), decimals = 1, step = 1.0, onValueChangeFinished = { finished++ })
                    }
                }
            }
        }
        settle(10, 50)
        assertTrue("no slider without a range", sliders().isEmpty())
        val handle = RobolectricUi.byDescription("Drag sideways to change X")
        val b = handle.bounds
        val density = activity.resources.displayMetrics.density

        // 100dp to the right: well past the touch slop, a few dozen whole steps.
        drag(handle.window, b.center.x to b.center.y, b.center.x + 100f * density to b.center.y)
        assertTrue("dragging right increases X: $x", x > 110.0)
        assertTrue("and not absurdly: $x", x < 200.0)
        assertEquals("whole steps from the start value", Math.rint(x), x, 0.0)
        assertEquals("one finished change per drag", 1, finished)
        assertEquals(Units.formatNumber(x, 1), field("X").text)

        // Dragging left goes the other way, from where the last drag ended.
        val before = x
        drag(handle.window, b.center.x to b.center.y, b.center.x - 60f * density to b.center.y)
        assertTrue("dragging left decreases X: $before -> $x", x < before)
        assertEquals(2, finished)

        // Screen readers: increase / decrease actions on the handle.
        val actions = handle.node.config.getOrNull(SemanticsActions.CustomActions).orEmpty()
        val v = x
        actions.single { it.label == "Increase X" }.action()
        settle(4, 50)
        assertEquals(v + 1.0, x, 1e-9)
        actions.single { it.label == "Decrease X" }.action()
        settle(4, 50)
        assertEquals(v, x, 1e-9)

        // A mostly vertical move is not a scrub (left to an enclosing scroll).
        val still = x
        drag(handle.window, b.center.x to b.center.y, b.center.x + 4f to b.center.y + 60f * density)
        assertEquals(still, x, 0.0)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-hdpi", instrumentedPackages = ["com.brushwork.paint.ui.common.slidertypingsandbox"])
class LabeledSliderTypingTest {

    private fun clickLabeled(label: String) {
        val e = elements().last { it.node.config.getOrNull(SemanticsActions.OnClick)?.label == label }
        requireNotNull(e.node.config[SemanticsActions.OnClick].action).invoke()
        settle(6, 50)
    }

    private fun editor() = RobolectricUi.textFields().single()

    private fun typeAndDone(text: String) {
        editor().type(text)
        settle(4, 50)
        requireNotNull(editor().node.config[SemanticsActions.OnImeAction].action).invoke()
        settle(6, 50)
    }

    @Test
    fun tappingTheValueLetsItBeTyped() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        var opacity by androidx.compose.runtime.mutableFloatStateOf(0.5f)
        var finished by mutableIntStateOf(0)
        activity.setContent {
            BrushworkTheme {
                Surface {
                    Column(Modifier.padding(16.dp)) {
                        LabeledSlider(
                            "Opacity", opacity, { opacity = it }, 0f..1f,
                            valueText = "${(opacity * 100).toInt()}%",
                            onValueChangeFinished = { finished++ },
                            typing = SliderTyping.Percent,
                        )
                    }
                }
            }
        }
        settle(10, 50)
        assertTrue("no text field until the value is tapped", RobolectricUi.textFields().isEmpty())

        clickLabeled("Type a value for Opacity")
        assertTrue("the editor takes focus to take the typing", editor().focused)
        assertEquals("50", editor().text)
        typeAndDone("25")
        assertEquals(0.25f, opacity, 1e-6f)
        assertEquals(1, finished)
        assertTrue("the editor closes", RobolectricUi.textFields().isEmpty())
        assertTrue(RobolectricUi.hasText("25%"))

        // Out of range is clamped; garbage changes nothing.
        clickLabeled("Type a value for Opacity")
        typeAndDone("250 %")
        assertEquals(1f, opacity, 0f)
        clickLabeled("Type a value for Opacity")
        typeAndDone("abc")
        assertEquals(1f, opacity, 0f)
        assertEquals(2, finished)

        // Leaving the editor (focus moves away) commits too.
        clickLabeled("Type a value for Opacity")
        editor().type("40")
        settle(4, 50)
        editor().window.clearFocus()
        settle(6, 50)
        assertEquals(0.4f, opacity, 1e-6f)
        assertEquals(3, finished)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-hdpi", instrumentedPackages = ["com.brushwork.paint.ui.common.numberfieldlayoutsandbox"])
class NumberFieldLayoutTest {

    @Test
    fun fieldsSurviveUnboundedWidthsAndIntrinsicMeasurement() {
        // A DropdownMenu's popup polls its anchor in an infinite animation: park those.
        com.brushwork.paint.smoke.SmokeUi.installTestRecomposer()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        var a by mutableDoubleStateOf(50.0)
        var b by mutableDoubleStateOf(3.0)
        activity.setContent {
            BrushworkTheme {
                Surface {
                    Column {
                        // A horizontally scrolling strip, like the tool options bar.
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            NumberField("Strip slider", a, { a = it }, min = 0.0, max = 100.0, decimals = 0)
                            NumberField("Strip scrub", b, { b = it }, decimals = 1, step = 1.0)
                        }
                        // A dropdown menu measures its items' intrinsic widths.
                        Box {
                            DropdownMenu(expanded = true, onDismissRequest = {}) {
                                NumberField("Menu slider", a, { a = it }, min = 0.0, max = 100.0, decimals = 0)
                                LengthField("Menu length", 40.0, {}, com.brushwork.paint.core.LengthUnit.PX, 72.0, Modifier.width(260.dp), minPx = 1.0, maxPx = 10000.0)
                            }
                        }
                    }
                }
            }
        }
        settle(10, 50)
        for (label in listOf("Strip slider", "Strip scrub", "Menu slider", "Menu length")) {
            val f = field(label)
            assertTrue("$label has a usable width: ${f.bounds}", f.bounds.width > 60f)
        }
        // Sliders got a width too, and drive their values.
        assertTrue(slider("Strip slider").bounds.width > 60f)
        setProgress("Menu slider", 1f)
        assertEquals(100.0, a, 0.0)
        assertNull(sliders().firstOrNull { it.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Strip scrub") == true })
    }
}
