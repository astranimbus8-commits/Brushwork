package com.brushwork.paint.ui.filters

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.art.ChromaticAberrationMovingFilter
import com.brushwork.paint.filters.art.FringeColors
import com.brushwork.paint.filters.art.FringeColors.BLUE
import com.brushwork.paint.filters.art.FringeColors.CYAN
import com.brushwork.paint.filters.art.FringeColors.GREEN
import com.brushwork.paint.filters.art.FringeColors.NONE
import com.brushwork.paint.filters.art.FringeColors.RED
import com.brushwork.paint.filters.art.FringeColors.YELLOW
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.FringeLabels18
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.8 (item 5, design §3.6 (a), D16): the "Fringe colors" control. Its preset chips (reached by
 * their tags, [V18Tags.FRINGE_PREFIX] + the name) fill the three swatches and show which preset
 * is current; the "No color" chip beside the middle swatch sets the middle to 0; a swatch opens
 * the unchanged color picker titled after it, and its pick replaces the color ("No color"
 * included). Every change is whole at once. Own sandbox (one Compose screen per test class).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.filters.fringesandbox"])
class FringeControlUiTest {

    private fun tagged(tag: String): RobolectricUi.Element =
        RobolectricUi.elements().lastOrNull { it.node.layoutInfo.isPlaced && it.node.config.getOrNull(SemanticsProperties.TestTag) == tag }
            ?: throw AssertionError("nothing tagged \"$tag\"; shown: ${SmokeUi.shown().take(60)}")

    private fun chip(name: String) = tagged(V18Tags.FRINGE_PREFIX + name)

    private fun clickChip(name: String) {
        val action = chip(name).node.config.getOrNull(SemanticsActions.OnClick)?.action ?: throw AssertionError("chip \"$name\" is not clickable")
        action.invoke()
        SmokeUi.settle()
    }

    private fun selected(name: String): Boolean = chip(name).node.config.getOrNull(SemanticsProperties.Selected) == true

    private fun swatchState(label: String): String? =
        RobolectricUi.elements().last { it.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true }
            .node.config.getOrNull(SemanticsProperties.StateDescription)

    /** The color picker is open, titled [title] (a text: the swatches carry their names as descriptions). */
    private fun pickerShows(title: String): Boolean = RobolectricUi.hasText(title) && SmokeUi.has("OK", exact = true)

    @Test
    fun presetsFillTheSwatchesNoColorEmptiesTheMiddleAndAPickReplacesIt() {
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val filter = ChromaticAberrationMovingFilter()
        val p = filter.params.filterIsInstance<FilterParam.Fringes>().single()
        var values by mutableStateOf(filter.defaultValues())
        var finished = 0
        val host = ParamHost(
            valuesOf = { values },
            update = { k, v -> values = values.copy().set(k, v) },
            resetParam = {},
            onChangeFinished = { finished++ },
        )
        activity.setContent { BrushworkTheme { Column { FilterParamControl(host, p, enabled = true) } } }
        SmokeUi.settle()
        fun colors() = values.colors(FringeColors.KEY).toList()

        // The label, the four presets (RGB, the default, is the current one) and the three swatches.
        assertTrue(SmokeUi.has(FringeLabels18.FRINGE_COLORS, exact = true))
        for (preset in FringeColors.PRESETS) assertEquals(preset.name, selected(preset.name), preset.name == FringeLabels18.RGB)
        for (label in listOf(FringeLabels18.FIRST, FringeLabels18.MIDDLE, FringeLabels18.LAST)) assertTrue(label, SmokeUi.has(label, exact = true))
        assertEquals(listOf(RED, GREEN, BLUE), colors())

        // A preset fills the three swatches; it is then the selected chip.
        clickChip(FringeLabels18.RED_CYAN)
        assertEquals(listOf(RED, NONE, CYAN), colors())
        assertEquals("a chip is a whole change", 1, finished)
        assertTrue(selected(FringeLabels18.RED_CYAN)); assertFalse(selected(FringeLabels18.RGB))
        assertEquals(FringeLabels18.NO_COLOR, swatchState(FringeLabels18.MIDDLE))
        clickChip(FringeLabels18.BLUE_YELLOW)
        assertEquals(listOf(BLUE, NONE, YELLOW), colors())

        // "No color" beside the middle swatch empties the middle only.
        clickChip(FringeLabels18.RGB)
        assertEquals(listOf(RED, GREEN, BLUE), colors())
        SmokeUi.click(FringeLabels18.NO_COLOR, exact = true)
        assertEquals(listOf(RED, NONE, BLUE), colors())
        assertEquals(4, finished)
        assertFalse("no preset is red, none, blue", FringeColors.PRESETS.any { selected(it.name) })

        // The middle swatch opens the color picker, titled after it; OK picks its color (from
        // "No color", the default's green), which replaces "No color".
        SmokeUi.click(FringeLabels18.MIDDLE, exact = true)
        assertTrue("picker titled after the swatch: ${SmokeUi.shown()}", pickerShows(FringeLabels18.MIDDLE))
        SmokeUi.click("OK", exact = true)
        assertEquals(listOf(RED, GREEN, BLUE), colors())
        assertEquals(5, finished)
        assertFalse("the picker closed", pickerShows(FringeLabels18.MIDDLE))
        assertTrue(selected(FringeLabels18.RGB))

        // Cancel changes nothing.
        SmokeUi.click(FringeLabels18.LAST, exact = true)
        assertTrue(pickerShows(FringeLabels18.LAST))
        SmokeUi.click("Cancel", exact = true)
        assertEquals(listOf(RED, GREEN, BLUE), colors())
        assertEquals(5, finished)
    }
}
