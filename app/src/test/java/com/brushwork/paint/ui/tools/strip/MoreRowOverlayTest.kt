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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import com.brushwork.paint.ui.common.BwDialog
import com.brushwork.paint.ui.common.BwSheet
import com.brushwork.paint.ui.common.StripLabels18
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 (F3, design §3.9 overlays, D25): a fake converted spec whose More-row items open a sheet, a
 * dialog and an anchored dropdown. The sheet and the dialog live in [StripSpec.overlays] (their
 * open flags outside the items): the item calls `StripContext.closeMore()` and opens them. The
 * dropdown stays inside its item and keeps the row open, and its dismissal leaves the row open.
 * v1.9 (R3, design 2.6): `closeMore()` is a no-op (the rig passes `EditorScreen`'s), so the row
 * stays open under the sheet and the dialog; a chrome cover ([StripState.hiddenByChrome], what
 * `EditorScreen` sets while a sheet shows) hides it, remembered, and the overlays outlive that;
 * only the toggle closes it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.tools.strip.moreoverlaysandbox"])
class MoreRowOverlayTest {

    @Composable
    private fun ProbeButton(label: String, onClick: () -> Unit) {
        Box(Modifier.size(100.dp, 40.dp).clickable(onClick = onClick).semantics { contentDescription = label })
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

    private fun toggleState(): String? = StripTestKit.toggle()?.config?.getOrNull(SemanticsProperties.StateDescription)

    @Test
    fun overlaysShowOverTheOpenRowAndOnlyTheToggleClosesIt() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val state = StripState()
        var sheetOpen by mutableStateOf(false)
        var dialogOpen by mutableStateOf(false)
        var menuOpen by mutableStateOf(false)
        var picked by mutableIntStateOf(0)
        val spec = StripSpec(
            items = listOf(
                StripItem("row", "Probe row item", 100.dp) { ProbeButton("Probe row item") {} },
                StripItem("sheet", "Open probe sheet", 104.dp, row = StripRow.MORE) {
                    val ctx = LocalStripContext.current
                    ProbeButton("Open probe sheet") {
                        ctx.closeMore()
                        sheetOpen = true
                    }
                },
                StripItem("dialog", "Open probe dialog", 104.dp, row = StripRow.MORE) {
                    val ctx = LocalStripContext.current
                    ProbeButton("Open probe dialog") {
                        ctx.closeMore()
                        dialogOpen = true
                    }
                },
                StripItem("menu", "Open probe menu", 104.dp, row = StripRow.MORE) {
                    Box {
                        ProbeButton("Open probe menu") { menuOpen = true }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Probe menu entry") }, onClick = {
                                picked++
                                menuOpen = false
                            })
                        }
                    }
                },
            ),
            overlays = {
                if (sheetOpen) BwSheet(title = "Probe sheet", onDismiss = { sheetOpen = false }) { Text("Probe sheet body") }
                if (dialogOpen) BwDialog(title = "Probe dialog", onDismiss = { dialogOpen = false }) { Text("Probe dialog body") }
            },
        )
        activity.setContent {
            BrushworkTheme {
                Surface {
                    CompositionLocalProvider(LocalStripContext provides StripContext(false, false, closeMore = {})) {
                        StripHost(spec, ToolId.SHAPE, vectorMode = false, state = state)
                    }
                }
            }
        }
        RobolectricUi.settle(10, 50)

        // Closed at first: row 1 and the toggle, no More row.
        assertTrue(node("Probe row item") != null)
        assertEquals(StripLabels18.COLLAPSED, toggleState())
        assertFalse(StripTestKit.moreRowShown())
        assertEquals(0f, state.moreRowDp, 0f)

        // The sheet: closeMore() is a no-op, the row stays open with the sheet over it.
        assertTrue(StripTestKit.openMoreRow())
        assertTrue(StripTestKit.moreRowShown())
        assertEquals(StripLabels18.EXPANDED, toggleState())
        assertEquals("one line", StripLayout.moreRowDp(1), state.moreRowDp, 0f)
        click("Open probe sheet")
        assertTrue("the sheet shows", sheetOpen && node("Probe sheet body") != null)
        assertTrue("closeMore() left the row open (v1.9)", StripTestKit.moreRowShown() && state.isOpen(ToolId.SHAPE))
        assertEquals(StripLayout.moreRowDp(1), state.moreRowDp, 0f)
        // The editor covers the row while a sheet shows: hidden, remembered; the sheet outlives it.
        state.hiddenByChrome = true
        RobolectricUi.settle(10, 50)
        assertFalse("covered: the row hides", StripTestKit.moreRowShown() || state.anyOpen)
        assertEquals(0f, state.moreRowDp, 0f)
        assertTrue("…remembered", state.remembers(ToolId.SHAPE))
        assertTrue("the sheet outlives the row", sheetOpen && node("Probe sheet body") != null)
        sheetOpen = false
        state.hiddenByChrome = false
        RobolectricUi.settle(10, 50)
        assertTrue("the sheet closed: the row shows again", StripTestKit.moreRowShown() && state.isOpen(ToolId.SHAPE))

        // The dialog: it shows over the open row, which stays.
        click("Open probe dialog")
        RobolectricUi.settle(10, 50)
        assertTrue("the dialog shows", dialogOpen && node("Probe dialog body") != null)
        assertTrue("closeMore() left the row open (v1.9)", state.isOpen(ToolId.SHAPE))
        dialogOpen = false
        RobolectricUi.settle(10, 50)
        assertTrue("the dialog closed: the row still open", StripTestKit.moreRowShown() && state.isOpen(ToolId.SHAPE))

        // The dropdown: the row stays open while it shows and after it is dismissed.
        click("Open probe menu")
        assertTrue("the dropdown shows", node("Probe menu entry") != null)
        assertTrue("the dropdown keeps the row open", StripTestKit.moreRowShown() && state.isOpen(ToolId.SHAPE))
        click("Probe menu entry")
        assertEquals(1, picked)
        assertFalse(menuOpen)
        assertTrue("the row is still open after the dropdown", StripTestKit.moreRowShown() && state.isOpen(ToolId.SHAPE))
        // Only the toggle closes it.
        assertTrue(StripTestKit.closeMoreRow())
        assertFalse(StripTestKit.moreRowShown())
        assertEquals(StripLabels18.COLLAPSED, toggleState())
    }
}
