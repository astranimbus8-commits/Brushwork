package com.brushwork.paint.ui.layers

import android.graphics.Path
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Selection
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.ui.common.SavedSelectionLabels
import com.brushwork.paint.ui.common.V17Tags
import com.brushwork.paint.ui.editor.HistoryLabels
import com.brushwork.paint.ui.theme.BrushworkTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.7 (§3.14 (c), area G): at the user's phone size, "Add selection layer" shows the new saved
 * selection's row at once (under the Selection Layer row, 48 dp, its name) with a spinner while
 * it compresses; the row can't be tapped (no menu: nothing to load, rename or delete yet) and no
 * step is recorded. When it lands: one "Save selection" step, the row has its menu. An entry
 * being updated shows the spinner and can't be tapped until the update lands. Compression is
 * held by the controller's test seam (released before any undo or mark, which would wait for it).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.layers.savedselectionpendingsandbox"])
class SavedSelectionPendingRowUiRobolectricTest {
    private val w = 64
    private val h = 48

    private fun SemanticsNode.subtree(): List<SemanticsNode> = listOf(this) + children.flatMap { it.subtree() }
    private fun SemanticsNode.ancestors(): List<SemanticsNode> = generateSequence(parent) { it.parent }.toList()
    private fun SemanticsNode.clickable() = config.getOrNull(SemanticsActions.OnClick) != null
    private fun SemanticsNode.spinning() = subtree().any { it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) != null }

    private fun row(probe: LayerWindowProbe, id: Long): SemanticsNode =
        probe.elements().lastOrNull { it.node.config.getOrNull(SemanticsProperties.TestTag) == V17Tags.savedSelectionRow(id) }?.node
            ?: throw AssertionError("no row for saved selection $id")

    @Test
    fun aPendingSaveShowsItsRowAtOnceWithASpinnerAndNoMenu() {
        val started = System.nanoTime()
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val c: EditorController = Smoke.controller(activity, Smoke.document(w, h, layers = 2, whiteBottom = true))
        val density = activity.resources.displayMetrics.density
        val p = Path().apply { addRect(4f, 4f, 30f, 24f, Path.Direction.CW) }
        c.setSelection(Selection.fromPath(p, w, h, antiAlias = false), recordUndo = false)
        activity.setContent {
            BrushworkTheme {
                Box(Modifier.fillMaxSize()) {
                    LayersPanel(
                        c,
                        onDismiss = {},
                        onImportPicture = {},
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = 5.dp, bottom = 92.dp).size(382.dp, 520.dp),
                    )
                }
            }
        }
        SmokeUi.settle()
        val probe = LayerWindowProbe(density)
        val steps = c.undoManager.undoCount

        // Save, held: the row is there at once, with its name and a spinner, and no menu.
        val gate = CompletableDeferred<Unit>()
        c.beforeSavedSelectionPack = { gate.await() }
        SmokeUi.click(SavedSelectionLabels.ADD_LAYER, exact = true)
        val pending = c.pendingSavedSelections.single()
        val tag = V17Tags.savedSelectionRow(pending.id)
        assertTrue("the row at once", probe.hasTag(tag))
        val selectionRow = probe.tagged(LayerWindowTags.SELECTION_ROW)
        val topLayer = probe.tagged(LayerWindowTags.row(c.doc.layers.last().id))
        assertTrue("below the Selection Layer row", probe.tagged(tag).top >= selectionRow.bottom - 1f)
        assertTrue("above the layers", probe.tagged(tag).bottom <= topLayer.top + 1f)
        assertEquals(48f, probe.taggedHeightDp(tag), 1f)
        assertTrue("its name", SmokeUi.has(pending.name, exact = true))
        val held = row(probe, pending.id)
        assertTrue("a spinner", held.spinning())
        assertFalse("no menu while pending", (held.subtree() + held.ancestors()).any { it.clickable() })
        assertEquals("no step yet", steps, c.undoManager.undoCount)
        assertTrue(c.doc.savedSelections.isEmpty())

        // Landed: one step, the row has its menu, no spinner.
        gate.complete(Unit)
        assertTrue("landed", Smoke.pumpUntil { c.pendingSavedSelections.isEmpty() })
        SmokeUi.settle()
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(SavedSelectionLabels.SAVE, c.undoManager.undoLabel)
        assertEquals(pending.id, c.doc.savedSelections.single().id)
        val landed = row(probe, pending.id)
        assertTrue("tappable", landed.clickable())
        assertFalse(landed.spinning())

        // Update, held: the entry's row spins and has no menu until the update lands.
        val gate2 = CompletableDeferred<Unit>()
        c.beforeSavedSelectionPack = { gate2.await() }
        SmokeUi.click(pending.name, exact = true)
        SmokeUi.click(SavedSelectionLabels.UPDATE, exact = true)
        assertTrue(c.pendingSavedSelections.single().update)
        val updating = row(probe, pending.id)
        assertTrue("a spinner while updating", updating.spinning())
        assertFalse("no menu while updating", updating.clickable())
        gate2.complete(Unit)
        assertTrue("updated", Smoke.pumpUntil { c.pendingSavedSelections.isEmpty() })
        SmokeUi.settle()
        assertEquals(steps + 2, c.undoManager.undoCount)
        assertEquals(HistoryLabels.UPDATE_SAVED_SELECTION, c.undoManager.undoLabel)
        assertTrue(row(probe, pending.id).clickable())
        assertFalse(row(probe, pending.id).spinning())
        Smoke.assertQuiet(c, "pending saved selection row")
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $ms ms", ms < PerfBudget.ms(60_000.0))
    }
}
