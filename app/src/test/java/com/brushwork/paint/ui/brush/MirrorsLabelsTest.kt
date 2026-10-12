package com.brushwork.paint.ui.brush

import com.brushwork.paint.model.RulerType
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.tools.clone.CloneTool
import com.brushwork.paint.ui.assist.RulerStripItems
import com.brushwork.paint.ui.clone.CloneStripItems
import com.brushwork.paint.ui.common.ArrayLabels18
import com.brushwork.paint.ui.common.CogLabels18
import com.brushwork.paint.ui.common.ColorSelectLabels18
import com.brushwork.paint.ui.common.CurveGenLabels18
import com.brushwork.paint.ui.common.FringeLabels18
import com.brushwork.paint.ui.common.LabelsV18
import com.brushwork.paint.ui.common.MagneticLabels18
import com.brushwork.paint.ui.common.MenuLabels18
import com.brushwork.paint.ui.common.MirrorLabels18
import com.brushwork.paint.ui.common.StripLabels18
import com.brushwork.paint.ui.common.SymmetryLabels
import com.brushwork.paint.ui.common.TransformLabels18
import com.brushwork.paint.ui.symmetry.SymmetryStripItems
import com.brushwork.paint.ui.tools.strip.StripHandlesV17
import com.brushwork.paint.ui.tools.strip.StripInventory
import com.brushwork.paint.ui.tools.strip.StripLayout
import com.brushwork.paint.ui.vector.VectorEraserStrip
import com.brushwork.paint.vector.draw.VectorEraseMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * v1.8 D (design §3.9, §4.8): the labels of area D's four converted strips and of the clone
 * stamp's copy cap, without a UI (the strips themselves: BrushStripTest, CloneStripTest,
 * RulerStripTest, SymmetryStripTest):
 * - every handle a D strip item declares is the v1.7 one ([StripHandlesV17]): "the v1.7 label wins";
 * - none is a v1.8-only label (rule L: those come from `LabelsV18` constants only, and D adds none
 *   but [MirrorLabels18.CLONE_TOO_MANY] and, v1.8 polish, [MirrorLabels18.CLONE_TOO_MANY_PERSPECTIVE]);
 * - the declared widths are rounded to 4 dp, tappable ones 40 dp at least (rule 4), and the row
 *   sums keep the design's budgets (rules 1 and 2).
 */
class MirrorsLabelsTest {

    private fun record(key: String) = StripHandlesV17.HANDLES.getValue(key)

    /** The `const val` Strings of [o]. */
    private fun strings(o: Any): List<String> = o.javaClass.declaredFields
        .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
        .map { f -> f.isAccessible = true; f.get(null) as String }

    /** The labels v1.8 adds (equal to no v1.7 text but [LabelsV18.SHARED]). */
    private val v18Only: Set<String> by lazy {
        listOf(
            MirrorLabels18, ArrayLabels18, MagneticLabels18, ColorSelectLabels18, FringeLabels18,
            CurveGenLabels18, CogLabels18, TransformLabels18, StripLabels18, MenuLabels18,
        ).flatMap(::strings).toSet() - LabelsV18.SHARED
    }

    @Test
    fun theCloneCapMessageIsTheV18LabelAndNamesTheCap() {
        assertEquals("Clone stamp mirrors up to 32 copies", MirrorLabels18.CLONE_TOO_MANY)
        assertEquals(32, CloneTool.MAX_MAPS)
        assertTrue(MirrorLabels18.CLONE_TOO_MANY.contains(" ${CloneTool.MAX_MAPS} "))
        // v1.8 polish: the Perspective array's refusal names the cap too, and is neither label's substring.
        assertTrue(MirrorLabels18.CLONE_TOO_MANY_PERSPECTIVE.contains(" ${CloneTool.MAX_MAPS} "))
        assertFalse(MirrorLabels18.CLONE_TOO_MANY_PERSPECTIVE.contains(MirrorLabels18.CLONE_TOO_MANY))
        assertFalse(MirrorLabels18.CLONE_TOO_MANY.contains(MirrorLabels18.CLONE_TOO_MANY_PERSPECTIVE))
    }

    @Test
    fun theBrushStripKeepsTheV17Handles() {
        val brush = record("BRUSH")
        for (h in listOf(BrushStripItems.CHOOSE_BRUSH, BrushStripItems.BRUSH_SIZE, BrushStripItems.OPACITY_LABEL, BrushStripItems.pressureLabel(true))) {
            assertTrue("\"$h\" is a v1.7 handle of the brush strip", h in brush)
        }
        assertTrue(BrushStripItems.pressureLabel(false) in record("ERASER"))
        assertTrue(BrushStripItems.STRENGTH in record("SMUDGE"))
        assertTrue(BrushStripItems.STRENGTH in record("BLUR"))
        // "All brush settings" stays inside the popups (§3.9), never a strip item.
        assertTrue(BrushStripItems.ALL_SETTINGS !in brush)
    }

    @Test
    fun theVectorEraserModesKeepTheirLabels() {
        val eraser = record("ERASER@vector")
        for (m in VectorEraseMode.entries) assertTrue("\"${m.label}\"", m.label in eraser)
        assertEquals(listOf("Object", "Partial", "To intersection"), VectorEraseMode.entries.map { it.label })
    }

    @Test
    fun theCloneStripKeepsTheV17Handles() {
        val clone = record("CLONE")
        for (h in listOf(CloneStripItems.SET_SOURCE_LABEL, CloneStripItems.ALIGNED_LABEL, CloneStripItems.sampleLabel(false), CloneStripItems.SHOW_SOURCE_LABEL, CloneTool.HINT)) {
            assertTrue("\"$h\" is a v1.7 handle of the clone strip", h in clone)
        }
        assertEquals("Tap the source", CloneStripItems.ARMED_LABEL)
        assertEquals("Sample from: All layers", CloneStripItems.sampleLabel(true))
        assertEquals(record("CLONE"), record("CLONE@vector"))
    }

    @Test
    fun theRulerStripKeepsTheV17HandlesAndTheLongerSnapName() {
        val ruler = record("RULER")
        for (t in RulerType.entries) assertTrue(RulerStripItems.typeLabel(t) in ruler)
        assertTrue(RulerStripItems.DONE_LABEL in ruler)
        assertTrue(RulerStripItems.NUMBERS_LABEL in ruler)
        // v1.7's "Snap" becomes "Snap to objects" (§3.9: one name per tool, the longer one).
        assertEquals(RulerStripItems.SNAP_LABEL, StripHandlesV17.SAME.getValue("Snap"))
    }

    @Test
    fun theSymmetryStripKeepsTheV17Handles() {
        val sym = record("SYMMETRY")
        for (t in SymmetryType.entries) assertTrue("the type \"${t.label}\"", t.label in sym)
        val s = SymmetrySettings(type = SymmetryType.MIRROR)
        assertTrue(SymmetryStripItems.angleText(s) in sym)
        assertEquals("Angle 0°", SymmetryStripItems.angleText(s))
        assertTrue(SymmetryLabels.RESET in sym)
        assertTrue(SymmetryStripItems.DONE_LABEL in sym)
        // The v1.7 chip texts of the other types.
        assertEquals("Divisions 6", SymmetryStripItems.divisionsText(SymmetrySettings(type = SymmetryType.KALEIDOSCOPE)))
        assertEquals("Spacing X 300 px", SymmetryStripItems.spacingText(true, SymmetrySettings(type = SymmetryType.ARRAY)))
        assertEquals("Spacing Y 300 px", SymmetryStripItems.spacingText(false, SymmetrySettings(type = SymmetryType.ARRAY)))
    }

    @Test
    fun noLabelOfAreaDsStripsIsAV18OnlyLabel() {
        val d = listOf(BrushStripItems, CloneStripItems, RulerStripItems, SymmetryStripItems, VectorEraserStrip).flatMap(::strings) +
            listOf(BrushStripItems.pressureLabel(true), BrushStripItems.pressureLabel(false), CloneStripItems.sampleLabel(false), CloneStripItems.sampleLabel(true))
        val clash = d.filter { it in v18Only }
        assertEquals("v1.8-only labels typed in D's strips (rule L)", emptyList<String>(), clash)
        // Keys are namespaced by strip, never labels.
        val keys = listOf(
            BrushStripItems.CHOOSE, BrushStripItems.SIZE, BrushStripItems.OPACITY, BrushStripItems.PRESSURE, VectorEraserStrip.KEY,
            CloneStripItems.SET_SOURCE, CloneStripItems.ALIGNED, CloneStripItems.SAMPLE, CloneStripItems.HINT, CloneStripItems.SHOW_SOURCE,
            RulerStripItems.TYPES, RulerStripItems.DONE, RulerStripItems.NUMBERS, RulerStripItems.SNAP,
            SymmetryStripItems.TYPE, SymmetryStripItems.DIVISIONS, SymmetryStripItems.ANGLE, SymmetryStripItems.SPACING_X,
            SymmetryStripItems.SPACING_Y, SymmetryStripItems.DONE, SymmetryStripItems.RESET,
        )
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.all { k -> listOf("brush.", "eraser.", "clone.", "ruler.", "symmetry.").any { k.startsWith(it) } })
    }

    @Test
    fun theDeclaredWidthsAreRoundedAndFingerSized() {
        val widths = mapOf(
            "Choose brush" to BrushStripItems.CHOOSE_DP, "Brush size" to BrushStripItems.SIZE_DP, "Opacity" to BrushStripItems.OPACITY_DP,
            "Pressure" to BrushStripItems.PRESSURE_DP, "eraser modes" to VectorEraserStrip.WIDTH_DP,
            "Set source" to CloneStripItems.SET_SOURCE_DP, "Aligned" to CloneStripItems.ALIGNED_DP, "Sample from" to CloneStripItems.SAMPLE_DP,
            "hint" to CloneStripItems.HINT_DP, "Show source" to CloneStripItems.SHOW_SOURCE_DP,
            "ruler types" to RulerStripItems.TYPES_DP, "ruler Done" to RulerStripItems.DONE_DP, "Numbers" to RulerStripItems.NUMBERS_DP,
            "Snap to objects" to RulerStripItems.SNAP_DP, "symmetry type" to SymmetryStripItems.TYPE_DP, "values" to SymmetryStripItems.VALUE_DP,
            "symmetry Done" to SymmetryStripItems.DONE_DP, "Reset" to SymmetryStripItems.RESET_DP,
        )
        for ((what, w) in widths) {
            assertEquals("$what: $w dp is rounded up to 4 dp", 0f, w % 4f)
            assertTrue("$what: $w dp is finger-sized", w >= 40f)
        }
        // The four ruler types: 40 dp each, 4 dp apart; the vector eraser's three modes: 40 dp each.
        assertEquals(4 * 40f + 3 * StripLayout.GAP_DP, RulerStripItems.TYPES_DP)
        assertEquals(3 * 40f, VectorEraserStrip.WIDTH_DP)
    }

    @Test
    fun theRowSumsKeepTheDesignBudgets() {
        fun sum(vararg w: Float) = w.sum() + (w.size - 1) * StripLayout.GAP_DP
        val noToggle = StripInventory.content(360f) - StripLayout.VECTOR_CHIP_DP - StripLayout.GAP_DP
        val toggle360Chip = noToggle - StripLayout.TOGGLE_DP - StripLayout.GAP_DP
        val toggle392 = StripInventory.content(392f) - StripLayout.TOGGLE_DP - StripLayout.GAP_DP
        // Brush, Eraser, Smudge, Blur: no More row; the whole row fits 360 dp with the VECTOR cell.
        assertEquals(240f, toggle360Chip)
        assertEquals(316f, toggle392)
        val brush = sum(BrushStripItems.CHOOSE_DP, BrushStripItems.SIZE_DP, BrushStripItems.OPACITY_DP, BrushStripItems.PRESSURE_DP)
        assertEquals(288f, brush)
        assertTrue(brush <= noToggle)
        // The vector eraser: modes and size always, Opacity AUTO 1 (it stays at 392 dp).
        assertTrue(sum(VectorEraserStrip.WIDTH_DP, BrushStripItems.SIZE_DP) <= toggle360Chip)
        assertTrue(sum(VectorEraserStrip.WIDTH_DP, BrushStripItems.SIZE_DP, BrushStripItems.OPACITY_DP) <= toggle392)
        // Clone: 236 dp, no AUTO item.
        assertEquals(236f, sum(CloneStripItems.SET_SOURCE_DP, CloneStripItems.ALIGNED_DP, CloneStripItems.SAMPLE_DP))
        assertTrue(236f <= toggle360Chip)
        // Ruler: 240 dp, no AUTO item.
        assertEquals(240f, sum(RulerStripItems.TYPES_DP, RulerStripItems.DONE_DP))
        assertTrue(240f <= toggle360Chip)
        // Symmetry: 200 dp without the AUTO value, 268 dp in full (it stays at 392 dp).
        assertEquals(200f, sum(SymmetryStripItems.TYPE_DP, SymmetryStripItems.VALUE_DP, SymmetryStripItems.DONE_DP))
        assertEquals(268f, sum(SymmetryStripItems.TYPE_DP, SymmetryStripItems.VALUE_DP, SymmetryStripItems.VALUE_DP, SymmetryStripItems.DONE_DP))
        assertTrue(200f <= toggle360Chip && 268f <= toggle392)
    }
}
