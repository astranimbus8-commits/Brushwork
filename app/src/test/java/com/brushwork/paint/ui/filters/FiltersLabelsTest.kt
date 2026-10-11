package com.brushwork.paint.ui.filters

import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.art.ChromaticAberrationMovingFilter
import com.brushwork.paint.filters.art.ChromaticAberrationZoomingFilter
import com.brushwork.paint.filters.art.FringeColors
import com.brushwork.paint.filters.art.GlitchFilter
import com.brushwork.paint.ui.color.PickerMode
import com.brushwork.paint.ui.common.FringeLabels18
import com.brushwork.paint.ui.common.LabelsV18
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.8 area A (item 5, design §4.8): the fringe colors' texts on their screens. Pure Kotlin.
 *
 * On the Chromatic aberration panels (Moving, Zooming) and the Glitch panel, no handle and no
 * visible text is a substring of another, where one of the two is a v1.8 text (SmokeUi.matches
 * is a case-sensitive `contains` over texts and descriptions). Equal texts at two places count.
 * The v1.8 texts are the ones [FringeControl] and the static color's control show: the label,
 * the preset chips, the three swatches' descriptions and the "No color" chip
 * ([FringeLabels18.NO_COLOR]); for Glitch the static color's label too. The other texts are the
 * panel's v1.7 ones: each parameter's label and its control's descriptions, and the panel's own
 * buttons.
 *
 * The color picker over the panel (a fringe swatch or the static's swatch tapped) adds the
 * picker's fixed v1.7 texts. Its meetings with the v1.8 texts are listed and asserted to be
 * exactly those; none is a handle the tests use (the preset chips are reached by their tags).
 */
class FiltersLabelsTest {

    /** The v1.7 texts of a filter panel: the parameters but [skip], and the panel's buttons. */
    private fun panel(f: Filter, skip: Set<String>): List<String> = f.params.filter { it.key !in skip }.flatMap { p ->
        when (p) {
            is FilterParam.Slider -> listOf(p.label, "Decrease ${p.label}", "Increase ${p.label}", "Reset ${p.label}")
            is FilterParam.Choice -> listOf(p.label) + p.options
            // v18p: the shuffle button reads "New random seed" (its description is composed by
            // newSeedDescription, no longer "New random " + the label, which said "random" twice).
            is FilterParam.Seed -> listOf(p.label, newSeedDescription(p.label))
            is FilterParam.Color -> listOf(p.label, "Choose ${p.label.lowercase()}")
            else -> listOf(p.label)
        }
    } + listOf(
        f.name, "Apply filter", "Cancel filter", "Preview", "Reset to defaults", "Compare with the original (press and hold)",
        "As adjustment layer", "Add as adjustment layer", "Stop", "Filters", "Search filters",
    )

    /** The texts [FringeControl] shows for [p]: its label, the presets, the swatches, "No color". */
    private fun fringeTexts(p: FilterParam.Fringes): List<String> =
        listOf(p.label) + p.presets.map { it.name } + listOf(FringeLabels18.FIRST, FringeLabels18.MIDDLE, FringeLabels18.LAST, FringeLabels18.NO_COLOR)

    private fun fringesOf(f: Filter): FilterParam.Fringes = f.params.filterIsInstance<FilterParam.Fringes>().single()

    /** The v1.8 texts of [f]'s panel: the fringe control's and (Glitch) the static color's label and its swatch's name. */
    private fun v18Texts(f: Filter): List<String> =
        fringeTexts(fringesOf(f)) + f.params.filter { it.key == GlitchFilter.STATIC_COLOR_KEY }.flatMap { listOf(it.label, "Choose ${it.label.lowercase()}") }

    private val v18Keys = setOf(FringeColors.KEY, GlitchFilter.STATIC_COLOR_KEY)

    /**
     * Pairs (a, b), a a substring of b, among [v18] + [v17] (each text at each place it shows:
     * equal texts at two places count), where a or b is one of [v18].
     */
    private fun pairs(v18: List<String>, v17: List<String>): Set<Pair<String, String>> {
        val texts = v18 + v17
        val out = mutableSetOf<Pair<String, String>>()
        for (i in texts.indices) for (j in texts.indices) {
            if (i != j && (i < v18.size || j < v18.size) && texts[j].contains(texts[i])) out += texts[i] to texts[j]
        }
        return out
    }

    private val chromatic: List<Filter> = listOf(ChromaticAberrationMovingFilter(), ChromaticAberrationZoomingFilter())

    @Test
    fun theFringeControlShowsTheDeclaredLabels() {
        for (f in chromatic + GlitchFilter()) {
            val p = fringesOf(f)
            assertEquals(f.name, FringeLabels18.FRINGE_COLORS, p.label)
            assertEquals(
                f.name,
                listOf(FringeLabels18.RGB, FringeLabels18.RED_CYAN, FringeLabels18.MAGENTA_GREEN, FringeLabels18.BLUE_YELLOW),
                p.presets.map { it.name },
            )
            // The panel shows each v1.8 text once.
            val mine = v18Texts(f)
            assertEquals(f.name, mine.distinct(), mine)
        }
        assertEquals(FringeLabels18.STATIC_COLOR, GlitchFilter().params.single { it.key == GlitchFilter.STATIC_COLOR_KEY }.label)
        // No v1.7 "Color order" left on the Chromatic aberration panels: the fringes replace it.
        for (f in chromatic) assertTrue(f.name, f.params.none { it is FilterParam.Choice })
    }

    @Test
    fun theChromaticAberrationPanels() {
        for (f in chromatic) {
            val found = pairs(v18Texts(f), panel(f, skip = v18Keys))
            assertTrue("${f.name}: a v1.8 text inside another (SmokeUi would match the wrong one): $found", found.isEmpty())
        }
    }

    @Test
    fun theGlitchPanel() {
        val f = GlitchFilter()
        val found = pairs(v18Texts(f), panel(f, skip = v18Keys))
        assertTrue("${f.name}: a v1.8 text inside another (SmokeUi would match the wrong one): $found", found.isEmpty())
        // v1.7's "Static" (the amount) never meets the static's color: the case differs where they would.
        assertTrue(f.params.any { it.label == "Static" })
    }

    /** The color picker's fixed texts (ui/color, frozen, v1.7): its buttons, modes, rows and fields. */
    private val picker: List<String> = listOf("Cancel", "OK", "Hex", "Previous", "Current", "Color wheel", "Opacity") +
        PickerMode.entries.map { it.label } + listOf("Red", "Green", "Blue", "Hue", "Saturation", "Brightness")

    @Test
    fun theColorPickerOverThePanel() {
        for (f in chromatic + GlitchFilter()) {
            val swatches = listOf(FringeLabels18.FIRST, FringeLabels18.MIDDLE, FringeLabels18.LAST) +
                (if (f is GlitchFilter) listOf(FringeLabels18.STATIC_COLOR) else emptyList())
            for (title in swatches) {
                // The picker is titled after the swatch it edits (v1.7's color parameters: after their label).
                val found = pairs(v18Texts(f) + title, panel(f, skip = v18Keys) + picker)
                val known = setOf(
                    // The swatch (or the static's label) and the picker's title: the picker is open, so the swatch is not reached.
                    title to title,
                    // Design §4.8 (2): the "RGB" chip and the picker's "RGB" mode, both visible texts (LabelsV18.SHARED).
                    FringeLabels18.RGB to FringeLabels18.RGB,
                    // The picker's RGB rows inside the preset names: the chips are reached by their tags.
                    "Red" to FringeLabels18.RED_CYAN, "Green" to FringeLabels18.MAGENTA_GREEN, "Blue" to FringeLabels18.BLUE_YELLOW,
                )
                assertEquals("${f.name}, $title: the picker's meetings with the fringe texts", known, found)
            }
        }
        assertTrue(FringeLabels18.RGB in LabelsV18.SHARED)
    }
}
