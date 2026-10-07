package com.brushwork.paint.ui.common

import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.remove.ContentAwareFillJob
import com.brushwork.paint.ui.editor.HistoryLabels
import com.brushwork.paint.ui.layers.LayerLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * v1.7 F5 (design §4.8, I10): every label pre-declared in `LabelsV17.kt` is unique, and on the
 * screens where the foundation puts them (the selection bar with a selection, the tool menu, the
 * Ruler panel, the layer ⋮) no content description is a substring of another (SmokeUi matches
 * substrings, case-sensitively, and clicks the LAST match). "Array" and "Save" are visible texts
 * only (each button's description is "Array from selection" / "Save selection"), so they are
 * not handles and are left out of the substring check. The v1.7 history labels are the §4.8 list.
 */
class LabelsV17Test {
    private val labelObjects: List<Any> = listOf(
        FolderLabels, ArrayLabels, PointLabels, PillLabels, CurveLabels17, SavedSelectionLabels,
        TransformLabels17, SymmetryLabels, PathfinderLabels, KerningLabels, ExpressionLabels,
    )

    /** "Object.NAME" to value, for every `const val` String of [objs]. */
    private fun constants(vararg objs: Any): Map<String, String> = objs.flatMap { o ->
        o.javaClass.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { f -> f.isAccessible = true; "${o.javaClass.simpleName}.${f.name}" to (f.get(null) as String) }
    }.toMap()

    /** Visible texts only: never a handle (design §4.8, substring safety). */
    private val visibleOnly = setOf(ArrayLabels.BUTTON, SavedSelectionLabels.SAVE_BUTTON)

    @Test
    fun everyV17LabelIsUniqueAndNotBlank() {
        val all = constants(*labelObjects.toTypedArray())
        assertTrue("the objects have their labels: ${all.size}", all.size >= 80)
        val dups = all.entries.groupBy({ it.value }, { it.key }).filterValues { it.size > 1 }
        assertTrue("labels declared twice: $dups", dups.isEmpty())
        assertTrue("blank: ${all.filterValues { it.isBlank() }}", all.values.none { it.isBlank() })
        val tags = constants(V17Tags)
        assertEquals("tags unique", tags.size, tags.values.toSet().size)
        assertTrue("a tag is never a label", tags.values.none { it in all.values })
    }

    /** Pairs (a, b) of [labels] where a is a substring of b (a != b counted too when equal). */
    private fun substringPairs(labels: List<String>): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (i in labels.indices) for (j in labels.indices) {
            if (i != j && labels[j].contains(labels[i])) out += labels[i] to labels[j]
        }
        return out
    }

    private fun assertNoSubstrings(screen: String, v17: List<String>, v16: List<String>, allowed: Set<Pair<String, String>> = emptySet()) {
        val handles = v17.filter { it !in visibleOnly }
        val pairs = substringPairs(handles + v16).filter { (a, b) -> (a in handles || b in handles) && (a to b) !in allowed }
        assertTrue("$screen: a v1.7 description inside another (SmokeUi would click the wrong one): $pairs", pairs.isEmpty())
    }

    @Test
    fun noDescriptionIsASubstringOfAnotherOnTheSelectionBar() {
        assertNoSubstrings(
            "selection bar",
            v17 = listOf(ArrayLabels.BUTTON, ArrayLabels.FROM_SELECTION, SavedSelectionLabels.SAVE_BUTTON, SavedSelectionLabels.SAVE),
            v16 = listOf(
                "Copy selection", "Cut selection", "Paste as a new layer", "Clear the selection", "Delete the selected pixels",
                ContentAwareFillJob.FILL_LABEL, "Invert the selection", "Selection menu",
            ),
        )
    }

    @Test
    fun noDescriptionIsASubstringOfAnotherInTheToolMenu() {
        val tools = ToolId.entries.map { it.label }
        assertTrue(ArrayLabels.BUTTON in tools && SymmetryLabels.TOOL in tools && "Pathfinder" in tools)
        // The tool cells are named by their tools: "Array" and "Symmetry" (LabelsV17) are handles here.
        val v17 = listOf(ToolId.ARRAY.label, SymmetryLabels.TOOL)
        val pairs = substringPairs(tools + listOf("Filters", "Canvas", "Settings")).filter { (a, b) -> a in v17 || b in v17 }
        assertTrue("tool menu: $pairs", pairs.isEmpty())
        // The one known pair (two tool names; tests click "Path" with exact = true, §4.8).
        val pathfinder = substringPairs(tools).filter { (a, b) -> a == ToolId.PATHFINDER.label || b == ToolId.PATHFINDER.label }
        assertEquals(listOf(ToolId.PATH.label to ToolId.PATHFINDER.label), pathfinder)
    }

    @Test
    fun noDescriptionIsASubstringOfAnotherOnTheRulerPanel() {
        assertNoSubstrings(
            "Ruler panel",
            v17 = listOf(SymmetryLabels.TOOL, SymmetryLabels.DIVISIONS, SymmetryLabels.RESET),
            v16 = SymmetryType.entries.map { it.label } + listOf("Use ruler", "Edit on canvas"),
        )
        // "Array" (a visible text) beside "Array ruler" is why it is never a handle.
        assertTrue(SymmetryType.entries.any { ArrayLabels.BUTTON in it.label })
    }

    @Test
    fun noDescriptionIsASubstringOfAnotherInTheLayerMenu() {
        assertNoSubstrings(
            "layer ⋮",
            v17 = listOf(
                FolderLabels.PUT_IN_NEW, FolderLabels.MOVE_IN, FolderLabels.MOVE_OUT, FolderLabels.RENAME, FolderLabels.DUPLICATE,
                FolderLabels.MERGE, FolderLabels.FROM_FOLDER, FolderLabels.UNGROUP, FolderLabels.PASS_THROUGH,
                ArrayLabels.OPEN, ArrayLabels.EDIT, ArrayLabels.APPLY, ArrayLabels.REMOVE, ArrayLabels.EDIT_SOURCE, ArrayLabels.FINISH_SOURCE,
            ),
            v16 = listOf(
                "Edit text", "Edit shape", "Edit objects", "Rasterize vector layer", "Edit adjustment", "Edit mask", "Use mask as selection",
                "Convert to vector layer", LayerLabels.NEW_VECTOR, LayerLabels.NEW_ADJUSTMENT, "Rename…", "Move layer up", "Move layer down",
                LayerLabels.MASK_ACTIONS, "Flip horizontal", "Flip vertical", "Clear", "Fill with current color",
            ),
        )
        // "New folder" sits in the "+" menu beside the two special layers.
        assertNoSubstrings("special layer menu", listOf(FolderLabels.NEW), listOf(LayerLabels.NEW_VECTOR, LayerLabels.NEW_ADJUSTMENT))
    }

    @Test
    fun theV17HistoryLabelsAreTheDesignList() {
        val expected = listOf(
            "New folder", "Put in new folder", "Move into folder", "Move out of folder", "Move layer", "Merge folder",
            "Layer from folder", "Ungroup folder", "Delete folder", "Duplicate folder", "Pass through",
            "Array", "Edit array", "Apply array", "Remove array", "Edit source pixels", "Finish source edit", "Turn into path",
            "Save selection", "Update saved selection", "Rename saved selection", "Delete saved selection", "Free deform",
            "Delete shape", "Delete text", "Delete curve", "Delete polyline", "Delete path", "Scale",
        ) + listOf("Unite", "Minus front", "Minus back", "Intersect", "Exclude", "Divide", "Trim", "Merge", "Crop", "Outline").map { "Pathfinder: $it" }
        assertEquals(expected.toSet(), HistoryLabels.V17.toSet())
        assertEquals("no step name twice", HistoryLabels.V17.size, HistoryLabels.V17.toSet().size)
        assertEquals(10, HistoryLabels.PATHFINDER_OPS.size)
        assertEquals("Pathfinder: Outline", HistoryLabels.pathfinder("Outline"))
        for (kind in listOf("shape", "text", "curve", "polyline", "path")) {
            assertTrue("Delete $kind", PillLabels.deleteObject(kind) in HistoryLabels.V17)
        }
        assertFalse("not the points' own step", PillLabels.DELETE_POINTS in HistoryLabels.V17)
    }
}
