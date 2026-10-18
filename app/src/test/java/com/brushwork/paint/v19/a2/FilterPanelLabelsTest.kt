package com.brushwork.paint.v19.a2

import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.filters.art.CaBlend
import com.brushwork.paint.filters.art.ChromaticAberrationMovingFilter
import com.brushwork.paint.filters.art.ChromaticAberrationZoomingFilter
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.ui.common.FilterLabels19
import com.brushwork.paint.ui.filters.newSeedDescription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.9 (R8, R10, A2 accept): the label scan of every filter panel. Pure Kotlin.
 *
 * The panel's v1.9 texts are each control's reset icon ("Reset <label>", [FilterLabels19.RESET_ONE],
 * now on every type) and, on the two Chromatic aberration panels, "Blend mode" with its menu rows
 * (the layers panel's names, [LayerBlendMode.label]) and "Opacity". As rule 4 of `LabelsV18Test`
 * (SmokeUi matches a case-sensitive `contains` over texts and descriptions): none of them is a
 * substring of another text on its panel, nor holds one, where equal texts at two places count.
 * By design a reset names its own control (its label is inside it, as "Decrease <label>" was in
 * v1.7), and the open menu's rows meet each other as they do in the layers panel ("Color" inside
 * "Color Dodge"); those pairs are allowed, and so is a reset that meets another text only where
 * its label already did ([allowed]); nothing else. The widths (392 and 360 dp,
 * font scale 1.3 and 2.0) are `Qa19FilterUndoUiTest`'s and its narrow twin's.
 */
class FilterPanelLabelsTest {

    /** The panel's older texts: each parameter's label and its control's descriptions, and the panel's buttons (as `LabelsV19Test`). */
    private fun older(f: Filter): List<String> = f.params.flatMap { p ->
        when (p) {
            is FilterParam.Slider -> listOf(p.label, "Decrease ${p.label}", "Increase ${p.label}")
            is FilterParam.Choice -> if (CaBlend.isModeChoice(p)) emptyList() else listOf(p.label) + p.options
            is FilterParam.Seed -> listOf(p.label, newSeedDescription(p.label))
            is FilterParam.Color -> listOf(p.label, "Choose ${p.label.lowercase()}")
            else -> listOf(p.label)
        }
    } + listOf(
        f.name, "Apply filter", "Cancel filter", "Preview", "Reset to defaults", "Compare with the original (press and hold)",
        "As adjustment layer", "Add as adjustment layer", "Stop", "Filters", "Search filters",
    )

    private fun reset(label: String) = String.format(FilterLabels19.RESET_ONE, label)

    /** The panel's v1.9 texts: the resets, and the blend mode's label and menu rows. */
    private fun v19(f: Filter): List<String> = f.params.map { reset(it.label) } +
        f.params.filter { CaBlend.isModeChoice(it) }.flatMap { p -> listOf(p.label) + (p as FilterParam.Choice).options }

    /** Pairs (a, b), a inside b, among [mine] + [rest] (each text at each place), where a or b is one of [mine]. */
    private fun pairs(mine: List<String>, rest: List<String>): Set<Pair<String, String>> {
        val texts = mine + rest
        val out = mutableSetOf<Pair<String, String>>()
        for (i in texts.indices) for (j in texts.indices) {
            if (i != j && (i < mine.size || j < mine.size) && texts[j].contains(texts[i])) out += texts[i] to texts[j]
        }
        return out
    }

    /**
     * The allowed pairs of [f]'s panel: a reset and its own control's texts; the menu rows among
     * themselves; and a reset that meets another text only where its label already did. A label
     * that holds an older text ("Center radius" holds "Center", "Smoothness" the option "Smooth")
     * holds it in its reset too, and so does the reset of the shorter one ("Reset Center" inside
     * "Reset Center radius"). Those pairs repeat the nesting of the panel's v1.8 labels.
     */
    private fun allowed(f: Filter): Set<Pair<String, String>> {
        val out = mutableSetOf<Pair<String, String>>()
        for (p in f.params) out += p.label to reset(p.label)
        val modes = LayerBlendMode.entries.map { it.label }
        if (f.params.any { CaBlend.isModeChoice(it) }) for (a in modes) for (b in modes) if (a != b && b.contains(a)) out += a to b
        val older = older(f)
        for (p in f.params) {
            for (o in older) if (o != p.label && p.label.contains(o)) out += o to reset(p.label)
            for (q in f.params) if (q !== p && p.label != q.label && p.label.contains(q.label)) out += reset(q.label) to reset(p.label)
        }
        return out
    }

    @Test
    fun noV19TextOfAPanelMeetsAnother() {
        val found = mutableListOf<String>()
        for (f in FilterRegistry.all) {
            val bad = pairs(v19(f), older(f)) - allowed(f)
            if (bad.isNotEmpty()) found += "${f.id}: $bad"
        }
        assertTrue("a v1.9 text inside another on its panel:\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test
    fun theChromaticAberrationPanelsEndWithBlendModeAndOpacity() {
        for (f in listOf(ChromaticAberrationMovingFilter(), ChromaticAberrationZoomingFilter())) {
            val tail = f.params.takeLast(2)
            assertEquals(f.name, listOf(CaBlend.modeParam, CaBlend.opacityParam), tail)
            // The layers panel's names in its order, read by reference.
            assertEquals(LayerBlendMode.entries.map { it.label }, CaBlend.modeParam.options)
            assertEquals(LayerBlendMode.NORMAL.ordinal, CaBlend.modeParam.default)
            assertEquals(100f, CaBlend.opacityParam.default)
            // Its resets read after their controls.
            assertTrue(reset(CaBlend.modeParam.label) in v19(f) && reset(CaBlend.opacityParam.label) in v19(f))
        }
        // Only the two Chromatic aberration filters show the blend mode as a dropdown.
        val dropdowns = FilterRegistry.all.filter { f -> f.params.any { CaBlend.isModeChoice(it) } }.map { it.id }.toSet()
        assertEquals(setOf(ChromaticAberrationMovingFilter().id, ChromaticAberrationZoomingFilter().id), dropdowns)
    }
}
