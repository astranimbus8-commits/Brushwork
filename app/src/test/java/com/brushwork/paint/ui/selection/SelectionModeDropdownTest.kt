package com.brushwork.paint.ui.selection

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.model.SelectionMode
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.MagneticLabels18
import com.brushwork.paint.ui.tools.ToolStrips
import com.brushwork.paint.ui.tools.strip.LocalStripContext
import com.brushwork.paint.ui.tools.strip.StripContext
import com.brushwork.paint.ui.tools.strip.StripRig
import com.brushwork.paint.ui.tools.strip.StripRow
import com.brushwork.paint.ui.tools.strip.StripSpec
import androidx.activity.compose.setContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 (C, design §3.9 and §4.8): every selection strip's mode dropdown ([MagneticLabels18.SELECTION_MODE]). It is row 1's
 * first item, 56 dp; its state is the mode's v1.7 description; the four v1.7 mode buttons are gone
 * from the strip and live in its opaque dropdown, whose entries set the tool's mode and close it.
 * "Deselect" and "Selection menu" are More-row items only while the selection bar is hidden.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.selection.modedropdownsandbox"])
class SelectionModeDropdownTest {

    /** The selection tools with a mode, and how to read and reset theirs. */
    private val tools: List<ToolId> = listOf(
        ToolId.MAGIC_WAND, ToolId.LASSO, ToolId.MARQUEE, ToolId.OBJECT_SELECT, ToolId.MAGNETIC_LASSO, ToolId.COLOR_SELECT,
    )

    private fun modeOf(rig: StripRig): SelectionMode {
        val tool = rig.c.currentTool
        return tool.javaClass.getMethod("getMode").invoke(tool) as SelectionMode
    }

    private fun setMode(rig: StripRig, m: SelectionMode) {
        val tool = rig.c.currentTool
        tool.javaClass.getMethod("setMode", SelectionMode::class.java).invoke(tool, m)
        rig.settle()
    }

    private fun modeButtonState(): String? = RobolectricUi.elements().lastOrNull { e ->
        e.node.layoutInfo.isPlaced && e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(MagneticLabels18.SELECTION_MODE) == true
    }?.node?.config?.getOrNull(SemanticsProperties.StateDescription)

    private val modes = SelectionMode.entries.map { modeDescription(it) }

    @Test
    fun everySelectionStripHasTheModeDropdown() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val rig = StripRig(panel = false).show()
        try {
            for (id in tools) {
                rig.select(id)
                val spec = rig.spec
                assertTrue("$id: items on the strip", spec.items.isNotEmpty())
                val first = spec.items.first()
                assertEquals("$id: row 1 starts with the dropdown", MODE_KEY, first.key)
                assertEquals(MagneticLabels18.SELECTION_MODE, first.label)
                assertEquals(56f, first.width.value, 0f)
                assertEquals(StripRow.ROW1, first.row)
                assertEquals("$id: no AUTO", 0, first.overflowRank)
                for (m in modes) assertEquals("$id: \"$m\" lives in the dropdown", MODE_KEY, spec.movedInto[m])

                // Closed: the button says the mode; no mode button on the strip, nor on the More row.
                assertEquals(SelectionMode.REPLACE, modeOf(rig))
                assertTrue(SmokeUi.has(MagneticLabels18.SELECTION_MODE, exact = true))
                assertEquals(modeDescription(SelectionMode.REPLACE), modeButtonState())
                StripTestKit.openMoreRow()
                for (m in modes) assertFalse("$id: \"$m\" is not a strip button", SmokeUi.has(m, exact = true))
                StripTestKit.closeMoreRow()

                // Open: the four modes, in a popup; picking one sets it and closes the dropdown.
                SmokeUi.click(MagneticLabels18.SELECTION_MODE, exact = true)
                for (m in modes) assertTrue("$id: \"$m\" in the dropdown", SmokeUi.has(m, exact = true))
                assertTrue("$id: a popup window", SmokeUi.windows().size >= 2)
                SmokeUi.click(modeDescription(SelectionMode.INTERSECT), exact = true)
                assertEquals(SelectionMode.INTERSECT, modeOf(rig))
                for (m in modes) assertFalse("$id: the dropdown closed", SmokeUi.has(m, exact = true))
                assertEquals(modeDescription(SelectionMode.INTERSECT), modeButtonState())
                SmokeUi.click(MagneticLabels18.SELECTION_MODE, exact = true)
                SmokeUi.click(modeDescription(SelectionMode.ADD), exact = true)
                assertEquals(SelectionMode.ADD, modeOf(rig))
                setMode(rig, SelectionMode.REPLACE)

                // The selection bar hidden (the rig's): "Deselect" and "Selection menu" on the More row.
                val keys = spec.items.filter { it.row == StripRow.MORE }.map { it.label }
                assertTrue("$id: $keys", keys.containsAll(listOf("Deselect", "Selection menu")))
                assertTrue(StripTestKit.openMoreRow())
                assertTrue(SmokeUi.has("Deselect", exact = true) && SmokeUi.has("Selection menu", exact = true))
                assertFalse("nothing to deselect", SmokeUi.isEnabled("Deselect"))
                StripTestKit.closeMoreRow()
            }

            // The selection bar shown: neither is a strip item (the bar has both). (The rig's own
            // content is replaced here, so its spec is stale: the shown spec decides.)
            for (id in tools) {
                rig.select(id)
                var shownBar: StripSpec? = null
                rig.activity.setContent {
                    CompositionLocalProvider(LocalStripContext provides StripContext(selectionBarShown = true, vectorMode = false)) {
                        val s = ToolStrips.of(rig.c)
                        SideEffect { shownBar = s }
                    }
                }
                rig.settle()
                val bar = requireNotNull(shownBar) { "$id: no spec" }
                assertTrue("$id: items on the strip under the selection bar", bar.items.isNotEmpty())
                val labels = bar.items.map { it.label }
                assertFalse("$id: $labels", labels.contains("Deselect") || labels.contains("Selection menu"))
                assertEquals("$id: the mode dropdown stays", MODE_KEY, bar.items.first().key)
            }
        } finally {
            rig.close()
        }
    }
}
