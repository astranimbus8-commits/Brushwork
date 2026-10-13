package com.brushwork.paint.ui.tools.strip

import android.view.MotionEvent
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
 * v1.8 merge (design §3.9, D25): the host's state rules that fake specs show best. v1.9 (R3,
 * design 2.8): rows close only by their toggle; hiding never forgets.
 * - A tool whose spec loses its More toggle (nothing goes to the More row any more) hides its
 *   open row, remembered: [StripState.anyOpen] turns false meanwhile, and when the toggle comes
 *   back the row shows again. Tool entry shows the remembered row again after a closeAll.
 * - The object bar's row hides the tool's row while it shows and keeps every tool's entry; the
 *   objects deselected (the bar gone), the tool's row shows again.
 * - A canvas touch fades the rows from the touch slop to its last lift, also when that lift came
 *   on a finger that joined the gesture on a row.
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

    private fun show(state: StripState, tool: () -> ToolId = { ToolId.SHAPE }, spec: () -> StripSpec) {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            BrushworkTheme {
                Surface {
                    CompositionLocalProvider(LocalStripContext provides StripContext(false, false, closeMore = state::closeAll)) {
                        StripHost(spec(), tool(), vectorMode = false, state = state)
                    }
                }
            }
        }
        RobolectricUi.settle(10, 50)
    }

    @Test
    fun aSpecWithoutTheToggleHidesItsOpenRowRemembered() {
        val state = StripState()
        val withMore = StripSpec(
            listOf(
                StripItem("row", "Probe row item", 100.dp) { ProbeButton("Probe row item") },
                StripItem("more", "Probe more item", 100.dp, row = StripRow.MORE) { ProbeButton("Probe more item") },
            ),
        )
        val rowOnly = StripSpec(listOf(StripItem("row", "Probe row item", 100.dp) { ProbeButton("Probe row item") }))
        var spec by mutableStateOf(withMore)
        var tool by mutableStateOf(ToolId.SHAPE)
        show(state, { tool }) { spec }

        // Open, then the spec loses its More items: no toggle, the row hides, remembered.
        assertTrue(StripTestKit.openMoreRow())
        assertTrue(state.isOpen(ToolId.SHAPE) && state.anyOpen)
        assertTrue(state.moreRowDp > 0f)
        spec = rowOnly
        RobolectricUi.settle(10, 50)
        assertNull("no toggle", StripTestKit.toggle())
        assertFalse("no More row", StripTestKit.moreRowShown())
        assertFalse("hidden while there is no toggle", state.isOpen(ToolId.SHAPE) || state.anyOpen)
        assertTrue("…and remembered (v1.9, R3)", state.remembers(ToolId.SHAPE))
        assertEquals(0f, state.moreRowDp, 0f)

        // The toggle comes back: the remembered row shows again.
        spec = withMore
        RobolectricUi.settle(10, 50)
        assertNotNull(StripTestKit.toggle())
        assertTrue("open again when the toggle comes back", StripTestKit.moreRowShown() && state.isOpen(ToolId.SHAPE))
        assertTrue(state.moreRowDp > 0f)

        // closeAll (the rig's closeMore) only hides: the tool's next entry shows the row again.
        state.closeAll()
        RobolectricUi.settle(10, 50)
        assertFalse("closeAll hides the row", StripTestKit.moreRowShown() || state.anyOpen)
        assertTrue("…without forgetting it", state.remembers(ToolId.SHAPE))
        tool = ToolId.BRUSH
        RobolectricUi.settle(10, 50)
        assertFalse("another tool: its own (closed) row", StripTestKit.moreRowShown())
        tool = ToolId.SHAPE
        RobolectricUi.settle(10, 50)
        assertTrue("tool entry restores the row after closeAll", StripTestKit.moreRowShown() && state.isOpen(ToolId.SHAPE))

        // Only the toggle closes it, and forgets it.
        assertTrue(StripTestKit.closeMoreRow())
        assertFalse(StripTestKit.moreRowShown() || state.remembers(ToolId.SHAPE))
    }

    /** v1.9 (R3): the object bar's row and the tools' rows, as `VectorObjectBar` and the host drive them (no UI). */
    @Test
    fun theObjectRowHidesTheToolsRowWithoutForgettingIt() {
        val state = StripState()
        state.enter(ToolId.SHAPE)
        state.toggle(ToolId.SHAPE)
        state.toggle(ToolId.BRUSH)
        assertTrue(state.isOpen(ToolId.SHAPE) && state.remembers(ToolId.BRUSH))

        // Objects selected: the bar shows its toggle; opening its row hides the tool's row.
        state.objectBarShown(true)
        assertFalse("the object row is closed at first", state.objectRowOpen)
        state.toggleObjectRow()
        assertTrue(state.objectRowOpen && state.objectRowRemembered)
        assertFalse("the tool's row hides", state.isOpen(ToolId.SHAPE))
        assertTrue("…and every tool keeps its entry", state.remembers(ToolId.SHAPE) && state.remembers(ToolId.BRUSH))

        // Deselected (the bar leaves): the tool's row shows again; the object row stays remembered.
        state.objectBarGone()
        assertFalse(state.objectRowOpen)
        assertTrue("deselecting brings the tool's row back", state.isOpen(ToolId.SHAPE))
        assertTrue(state.objectRowRemembered)

        // Selected again: the remembered object row shows again, over the tool's.
        state.objectBarShown(true)
        assertTrue(state.objectRowOpen && !state.isOpen(ToolId.SHAPE))

        // The strip's toggle while the object row shows: the tool's row shows, the object row
        // hides (one row at a time), remembered.
        state.toggle(ToolId.SHAPE)
        assertTrue(state.isOpen(ToolId.SHAPE) && state.remembers(ToolId.SHAPE))
        assertFalse(state.objectRowOpen)
        assertTrue(state.objectRowRemembered)

        // Its own toggle closes the object row and forgets it; the tools' rows stay.
        state.toggleObjectRow()
        assertTrue("its toggle shows it again first", state.objectRowOpen)
        state.toggleObjectRow()
        assertFalse(state.objectRowOpen || state.objectRowRemembered)
        assertTrue(state.isOpen(ToolId.SHAPE) && state.remembers(ToolId.BRUSH))

        // closeAll and the chrome covers hide everything, remembered.
        state.toggleObjectRow()
        state.closeAll()
        assertFalse(state.anyOpen)
        assertTrue(state.remembers(ToolId.SHAPE) && state.objectRowRemembered)
        state.enter(ToolId.SHAPE)
        assertTrue("the entry shows the object row again", state.objectRowOpen)
        state.hiddenByChrome = true
        assertFalse(state.anyOpen || state.objectRowShows())
        state.hiddenByChrome = false
        assertTrue(state.objectRowOpen)
    }

    /**
     * v1.9 (R3 (a)): a canvas touch, as `CanvasView` reports it (down, past the slop, the last
     * lift) and as a finger that joined it on a row ends it (no UI): the rows fade only between the
     * slop and the end, to [StripState.ROW_FADE_ALPHA].
     */
    @Test
    fun aCanvasTouchFadesTheRowsFromTheSlopToTheLastLift() {
        val state = StripState()
        assertEquals(0.25f, StripState.ROW_FADE_ALPHA, 0f)
        assertFalse(state.canvasTouchDown || state.canvasTouchMoved)
        state.canvasTouchSlop()
        assertFalse("no slop without a touch", state.canvasTouchMoved)

        state.canvasTouchStarted()
        assertTrue("down", state.canvasTouchDown)
        assertFalse("…not faded before the slop", state.canvasTouchMoved)
        state.canvasTouchSlop()
        assertTrue("past the slop: faded", state.canvasTouchDown && state.canvasTouchMoved)
        state.canvasTouchEnded()
        assertFalse("the last lift: shown again", state.canvasTouchDown || state.canvasTouchMoved)

        // A new touch starts unfaded.
        state.canvasTouchStarted()
        state.canvasTouchSlop()
        state.canvasTouchStarted()
        assertTrue(state.canvasTouchDown)
        assertFalse("a new touch starts unfaded", state.canvasTouchMoved)

        // A gesture whose last finger joined it on a row: its end goes to the canvas view, and
        // the touch ends here, also without a view.
        state.canvasTouchSlop()
        val ended = mutableListOf<Int>()
        state.endCanvasGesture = { ended += it.actionMasked }
        val up = MotionEvent.obtain(0L, 10L, MotionEvent.ACTION_UP, 10f, 10f, 0)
        try {
            state.joinedGestureEnded(up)
        } finally {
            up.recycle()
        }
        assertEquals(listOf(MotionEvent.ACTION_UP), ended)
        assertFalse(state.canvasTouchDown || state.canvasTouchMoved)
        state.endCanvasGesture = null
        state.canvasTouchStarted()
        val cancel = MotionEvent.obtain(0L, 10L, MotionEvent.ACTION_CANCEL, 10f, 10f, 0)
        try {
            state.joinedGestureEnded(cancel)
        } finally {
            cancel.recycle()
        }
        assertFalse("ended without a view", state.canvasTouchDown)
        assertEquals(1, ended.size)
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
