package com.brushwork.paint.ui.tools.strip

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 merge (design §3.9, D25): the host's state rules that fake specs show best.
 * - A tool whose spec loses its More toggle (nothing goes to the More row any more, or a legacy
 *   spec) drops its open entry: [StripState.anyOpen] turns false, so the next canvas touch is not
 *   swallowed by a row nobody sees; when the toggle comes back, the row is closed.
 * - Each item is keyed by [StripItem.key]: an item that comes before an open dropdown, on row 1 or
 *   on the More row, never resets it (the dropdown stays open).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.tools.strip.hoststatesandbox"])
class StripHostStateTest {

    @Composable
    private fun ProbeButton(label: String, onClick: () -> Unit = {}) {
        Box(Modifier.size(100.dp, 40.dp).clickable(onClick = onClick).semantics { contentDescription = label })
    }

    /** A dropdown whose open flag is the item's own remembered state (as a strip popup keeps it). */
    @Composable
    private fun ProbeMenu(label: String, entry: String) {
        var open by remember { mutableStateOf(false) }
        Box {
            ProbeButton(label) { open = true }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text(entry) }, onClick = { open = false })
            }
        }
    }

    private fun node(label: String): SemanticsNode? = RobolectricUi.elements().lastOrNull { e ->
        e.node.layoutInfo.isPlaced && (
            e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true ||
                e.node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
            )
    }?.node

    private fun click(label: String) {
        var n: SemanticsNode? = node(label) ?: throw AssertionError("\"$label\" is not shown")
        while (n != null && n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent
        requireNotNull(n?.config?.getOrNull(SemanticsActions.OnClick)?.action) { "\"$label\" is not clickable" }.invoke()
        RobolectricUi.settle(10, 50)
    }

    private fun show(state: StripState, spec: () -> StripSpec) {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            BrushworkTheme {
                Surface {
                    CompositionLocalProvider(LocalStripContext provides StripContext(false, false, closeMore = state::closeAll)) {
                        StripHost(spec(), ToolId.SHAPE, vectorMode = false, state = state)
                    }
                }
            }
        }
        RobolectricUi.settle(10, 50)
    }

    @Test
    fun aSpecWithoutTheToggleDropsItsOpenRow() {
        val state = StripState()
        val withMore = StripSpec(
            listOf(
                StripItem("row", "Probe row item", 100.dp) { ProbeButton("Probe row item") },
                StripItem("more", "Probe more item", 100.dp, row = StripRow.MORE) { ProbeButton("Probe more item") },
            ),
        )
        val rowOnly = StripSpec(listOf(StripItem("row", "Probe row item", 100.dp) { ProbeButton("Probe row item") }))
        val legacy = StripSpec.legacy { ProbeButton("Probe legacy item") }
        var spec by mutableStateOf(withMore)
        show(state) { spec }

        // Open, then the spec loses its More items: no toggle, the entry is dropped.
        assertTrue(StripTestKit.openMoreRow())
        assertTrue(state.isOpen(ToolId.SHAPE) && state.anyOpen)
        assertTrue(state.moreRowDp > 0f)
        spec = rowOnly
        RobolectricUi.settle(10, 50)
        assertNull("no toggle", StripTestKit.toggle())
        assertFalse("no More row", StripTestKit.moreRowShown())
        assertFalse("the toggle-less spec dropped the open entry", state.isOpen(ToolId.SHAPE) || state.anyOpen)
        assertEquals(0f, state.moreRowDp, 0f)

        // The toggle comes back closed.
        spec = withMore
        RobolectricUi.settle(10, 50)
        assertNotNull(StripTestKit.toggle())
        assertFalse("closed when the toggle comes back", StripTestKit.moreRowShown() || state.anyOpen)

        // A legacy spec has no toggle either.
        assertTrue(StripTestKit.openMoreRow())
        assertTrue(state.anyOpen)
        spec = legacy
        RobolectricUi.settle(10, 50)
        assertNotNull(node("Probe legacy item"))
        assertFalse("the legacy spec dropped the open entry", state.isOpen(ToolId.SHAPE) || state.anyOpen)
        assertEquals(0f, state.moreRowDp, 0f)
    }

    @Test
    fun anInsertedItemKeepsAnOpenDropdown() {
        val state = StripState()
        var extra by mutableStateOf(false)
        val rowExtra = StripItem("row.extra", "Probe row extra", 100.dp) { ProbeButton("Probe row extra") }
        val rowMenu = StripItem("row.menu", "Probe row menu", 100.dp) { ProbeMenu("Probe row menu", "Probe row entry") }
        val moreExtra = StripItem("more.extra", "Probe more extra", 100.dp, row = StripRow.MORE) { ProbeButton("Probe more extra") }
        val moreMenu = StripItem("more.menu", "Probe more menu", 100.dp, row = StripRow.MORE) { ProbeMenu("Probe more menu", "Probe more entry") }
        show(state) {
            StripSpec(if (extra) listOf(rowExtra, rowMenu, moreExtra, moreMenu) else listOf(rowMenu, moreMenu))
        }

        // Row 1: the dropdown opens, then an item comes before it.
        click("Probe row menu")
        assertNotNull("the row-1 dropdown shows", node("Probe row entry"))
        extra = true
        RobolectricUi.settle(10, 50)
        assertNotNull("the new item shows", node("Probe row extra"))
        assertNotNull("the row-1 dropdown is still open", node("Probe row entry"))
        click("Probe row entry")
        assertNull(node("Probe row entry"))

        // The More row: likewise, the item going away this time.
        assertTrue(StripTestKit.openMoreRow())
        click("Probe more menu")
        assertNotNull("the More-row dropdown shows", node("Probe more entry"))
        extra = false
        RobolectricUi.settle(10, 50)
        assertNull("the extra item went", node("Probe more extra"))
        assertTrue("the row stays open", StripTestKit.moreRowShown() && state.isOpen(ToolId.SHAPE))
        assertNotNull("the More-row dropdown is still open", node("Probe more entry"))
    }
}
