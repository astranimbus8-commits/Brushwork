package com.brushwork.paint.ui.editor.chrome

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.editor.EditorIcons
import com.brushwork.paint.ui.editor.ToolMenu
import com.brushwork.paint.ui.editor.ToolMenuEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 (design §3.9, V14): the tool menu as a finger sees it. 30 cells in 15 rows of two, in
 * [ToolMenu] order: every v1.7 cell keeps its index, [26] "Magnetic lasso" and [27] "Color select"
 * share a row, then Symmetry [28] and Ruler [29]. A tap on either new cell selects its tool (the
 * bottom bar's Tools slot names it), and each has its own glyph.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.editor.chrome.toolmenuv18sandbox"])
class ToolMenuV18Test {

    private fun label(e: ToolMenuEntry): String = when (e) {
        is ToolMenuEntry.Tool -> e.id.label
        ToolMenuEntry.Filters -> "Filters"
        ToolMenuEntry.Canvas -> "Canvas"
        ToolMenuEntry.Settings -> "Settings"
    }

    private fun texts(n: SemanticsNode): List<String> =
        n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + n.children.flatMap { texts(it) }

    @Test
    fun thirtyCellsAndTheTwoNewTools() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        val h = ChromeHarness()
        h.section("v1.8: the tool menu's 30 cells") {
            val s = h.editor()
            click("Tools (current: Brush)")
            val panel = s.placed().lastOrNull { it.node.config.getOrNull(SemanticsProperties.PaneTitle) == TOOL_MENU_TITLE }?.node
                ?: throw AssertionError("the tool menu is not open")
            // Its cells (every one composed: the menu scrolls, it is not lazy), top to bottom, left to right.
            val cells = generateSequence(listOf(panel)) { level -> level.flatMap { it.children }.ifEmpty { null } }.flatten()
                .filter { it.config.getOrNull(SemanticsActions.OnClick) != null }
                .sortedWith(compareBy<SemanticsNode>({ it.positionInRoot.y }, { it.positionInRoot.x }))
                .toList()
            assertEquals("30 cells", 30, cells.size)
            assertEquals(ToolMenu.entries.map(::label), cells.map { texts(it).joinToString(" ") })
            assertEquals(15, cells.map { it.positionInRoot.y }.distinct().size)
            // [26] and [27] share row 14, left then right.
            assertEquals(cells[26].positionInRoot.y, cells[27].positionInRoot.y, 0.5f)
            assertTrue(cells[26].positionInRoot.x < cells[27].positionInRoot.x)
            assertEquals(listOf("Magnetic lasso", "Color select", "Symmetry", "Ruler"), (26..29).map { texts(cells[it]).joinToString(" ") })

            // The new cells select their tools.
            click("Magnetic lasso", exact = true)
            assertEquals(ToolId.MAGNETIC_LASSO, s.c.activeToolId)
            click("Tools (current: Magnetic lasso)")
            click("Color select", exact = true)
            assertEquals(ToolId.COLOR_SELECT, s.c.activeToolId)
            assertTrue("the Tools slot names it", SmokeUi.has("Tools (current: Color select)", exact = true))
            s.c.selectTool(ToolId.BRUSH)
            SmokeUi.settle()

            // Their own glyphs.
            for (id in listOf(ToolId.MAGNETIC_LASSO, ToolId.COLOR_SELECT)) {
                val others = ToolId.entries.filter { it != id }.map { EditorIcons.tool(it) }
                assertTrue("$id has its own glyph", others.none { it === EditorIcons.tool(id) })
            }
            Smoke.assertQuiet(s.c, "tool menu v1.8")
        }
        dog.interrupt()
        h.finish()
    }
}
