package com.brushwork.paint.ui.layers

import android.graphics.Path
import android.graphics.Rect
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.SavedSelection
import com.brushwork.paint.model.Selection
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.ui.common.SavedSelectionLabels
import com.brushwork.paint.ui.common.V17Tags
import com.brushwork.paint.ui.editor.HistoryLabels
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.7 (§3.14, area G): the saved selections' rows in the layer window, at the user's phone size:
 * under the Selection Layer row and above the layers, newest first, 48 dp each; a tap opens the
 * row's menu (Load, Add to, Subtract from, Intersect with, Update from, Rename, Delete; the ones
 * that need an active selection are disabled without one); Load loads it, Rename renames it
 * through its dialog, Delete deletes it, each one step.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.layers.savedselectionrowssandbox"])
class SavedSelectionRowsUiRobolectricTest {
    private val w = 64
    private val h = 48

    private fun rect(r: Rect): Selection {
        val p = Path().apply { addRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), Path.Direction.CW) }
        return Selection.fromPath(p, w, h, antiAlias = false)
    }

    private fun bytes(s: Selection?) = s?.let { BitmapUtils.alpha8ToBytes(it.mask) }

    @Test
    fun theRowsListTheSavedSelectionsAndTheirMenuActs() {
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val c: EditorController = Smoke.controller(activity, Smoke.document(w, h, layers = 2, whiteBottom = true))
        val density = activity.resources.displayMetrics.density
        val doc = c.doc
        doc.savedSelections = listOf(Rect(2, 2, 20, 20), Rect(10, 5, 60, 40), Rect(30, 30, 50, 44)).mapIndexed { i, r ->
            SavedSelection.of(doc.newSelectionId(), "Selection ${i + 1}", rect(r), 1L)!!
        }
        val (s1, s2, s3) = doc.savedSelections
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

        // Under the Selection Layer row, above the layers, newest first, 48 dp each.
        val selectionRow = probe.tagged(LayerWindowTags.SELECTION_ROW)
        val rows = listOf(s3, s2, s1).map { probe.tagged(V17Tags.savedSelectionRow(it.id)) }
        val topLayer = probe.tagged(LayerWindowTags.row(doc.layers.last().id))
        assertTrue("below the Selection Layer row", rows.first().top >= selectionRow.bottom - 1f)
        for (i in 1 until rows.size) assertTrue("newest first ($i)", rows[i].top >= rows[i - 1].bottom - 1f)
        assertTrue("above the layers", rows.last().bottom <= topLayer.top + 1f)
        for (e in doc.savedSelections) assertEquals(48f, probe.taggedHeightDp(V17Tags.savedSelectionRow(e.id)), 1f)
        for (e in doc.savedSelections) assertTrue(SmokeUi.has(e.name, exact = true))

        // The menu without a selection: what needs one is disabled.
        SmokeUi.click("Selection 2", exact = true)
        for (label in listOf(SavedSelectionLabels.LOAD, SavedSelectionLabels.ADD, SavedSelectionLabels.SUBTRACT, SavedSelectionLabels.INTERSECT,
            SavedSelectionLabels.UPDATE, SavedSelectionLabels.RENAME, SavedSelectionLabels.DELETE)) {
            assertTrue("menu item $label", SmokeUi.has(label, exact = true))
        }
        assertTrue(SmokeUi.isEnabled(SavedSelectionLabels.LOAD))
        assertTrue(SmokeUi.isEnabled(SavedSelectionLabels.ADD))
        assertFalse(SmokeUi.isEnabled(SavedSelectionLabels.SUBTRACT))
        assertFalse(SmokeUi.isEnabled(SavedSelectionLabels.INTERSECT))
        assertFalse(SmokeUi.isEnabled(SavedSelectionLabels.UPDATE))

        // Load: the saved mask becomes the selection, one step.
        val steps = c.undoManager.undoCount
        SmokeUi.click(SavedSelectionLabels.LOAD, exact = true)
        assertTrue("loaded", Smoke.pumpUntil { c.selection != null })
        SmokeUi.settle()
        assertArrayEquals(bytes(s2.toSelection(w, h)), bytes(c.selection))
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(SavedSelectionLabels.LOAD, c.undoManager.undoLabel)
        assertFalse("the menu closed", SmokeUi.has(SavedSelectionLabels.DELETE, exact = true))

        // With a selection, Subtract, Intersect and Update are enabled.
        SmokeUi.click("Selection 1", exact = true)
        assertTrue(SmokeUi.isEnabled(SavedSelectionLabels.SUBTRACT))
        assertTrue(SmokeUi.isEnabled(SavedSelectionLabels.INTERSECT))
        assertTrue(SmokeUi.isEnabled(SavedSelectionLabels.UPDATE))

        // Rename through the dialog.
        SmokeUi.click(SavedSelectionLabels.RENAME, exact = true)
        assertTrue("the dialog", SmokeUi.has(SavedSelectionLabels.RENAME, exact = true))
        SmokeUi.typeAndDone("Name", "Sky")
        assertEquals("Sky", doc.savedSelections[0].name)
        assertEquals(HistoryLabels.RENAME_SAVED_SELECTION, c.undoManager.undoLabel)
        assertTrue(SmokeUi.has("Sky", exact = true))
        assertFalse(SmokeUi.has("Selection 1", exact = true))

        // Delete: the row goes; one undo brings it back.
        SmokeUi.click("Selection 3", exact = true)
        SmokeUi.click(SavedSelectionLabels.DELETE, exact = true)
        assertEquals(listOf(s1.id, s2.id), doc.savedSelections.map { it.id })
        assertFalse(probe.hasTag(V17Tags.savedSelectionRow(s3.id)))
        c.undo()
        SmokeUi.settle()
        assertTrue(probe.hasTag(V17Tags.savedSelectionRow(s3.id)))
        assertEquals(3, doc.savedSelections.size)
        Smoke.assertQuiet(c, "saved selection rows")
    }
}
