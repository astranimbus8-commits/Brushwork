package com.brushwork.paint.ui.layers

import android.graphics.Path
import android.view.MotionEvent
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
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.SavedSelection
import com.brushwork.paint.model.Selection
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.ui.common.V17Tags
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.7 (§3.14, area G): the saved selections' rows do not disturb the layer list's ≡ drag. With
 * 0 and with 3 saved selections, the bottom layer dragged onto the top row goes to the top (one
 * undo step); the top layer dragged onto the saved rows, or onto the Selection Layer row, moves
 * nothing and records no step (the headers are never dropped on).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.layers.savedselectionreordersandbox"])
class SavedSelectionRowsReorderUiRobolectricTest {
    private val w = 64
    private val h = 48

    private fun saved(doc: com.brushwork.paint.model.Document, i: Int): SavedSelection {
        val p = Path().apply { addRect(4f + i * 6, 4f, 20f + i * 6, 30f, Path.Direction.CW) }
        return SavedSelection.of(doc.newSelectionId(), "Selection ${i + 1}", Selection.fromPath(p, w, h, antiAlias = false), 1L)!!
    }

    /** Drags the ≡ handle labelled [handleLabel] to window y [toY] in 12 moves, then lifts. */
    private fun drag(handleLabel: String, toY: Float) {
        val handle = SmokeUi.find(handleLabel, exact = true) ?: throw AssertionError("no $handleLabel")
        val hb = handle.bounds
        val touch = Smoke.Touch(handle.window)
        touch.send(MotionEvent.ACTION_DOWN, Smoke.P(0, hb.center.x, hb.center.y))
        for (i in 1..12) {
            touch.idle(16)
            touch.send(MotionEvent.ACTION_MOVE, Smoke.P(0, hb.center.x, hb.center.y + (toY - hb.center.y) * i / 12f))
        }
        touch.idle(16)
        touch.send(MotionEvent.ACTION_UP, Smoke.P(0, hb.center.x, toY))
        SmokeUi.settle()
    }

    /** Whether the rows of [layers] are placed top to bottom in that order. */
    private fun rowsTopFirst(probe: LayerWindowProbe, vararg layers: Layer): Boolean =
        layers.map { probe.tagged(LayerWindowTags.row(it.id)).top }.zipWithNext().all { (a, b) -> a < b }

    /** Settles the UI until [done] (at most 6 s of virtual time). */
    private fun settleUntil(done: () -> Boolean): Boolean {
        repeat(60) {
            if (done()) return true
            SmokeUi.settle(2, 50)
        }
        return done()
    }

    @Test
    fun theLayerDragIgnoresTheSavedRows() {
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val c: EditorController = Smoke.controller(activity, Smoke.document(w, h, layers = 3, whiteBottom = true))
        val density = activity.resources.displayMetrics.density
        val doc = c.doc
        val (l1, l2, l3) = doc.layers.toList()
        activity.setContent {
            BrushworkTheme {
                Box(Modifier.fillMaxSize()) {
                    LayersPanel(
                        c,
                        onDismiss = {},
                        onImportPicture = {},
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = 5.dp, bottom = 40.dp).size(382.dp, 760.dp),
                    )
                }
            }
        }
        SmokeUi.settle()
        val probe = LayerWindowProbe(density)

        for (count in listOf(0, 3)) {
            doc.savedSelections = List(count) { saved(doc, it) }
            c.notifyLayersChanged()
            c.selectLayer(l3)
            SmokeUi.settle()
            for (e in doc.savedSelections) assertTrue("$count saved: row ${e.name}", probe.hasTag(V17Tags.savedSelectionRow(e.id)))

            // The bottom layer onto the top row: it goes to the top, one step.
            val steps = c.undoManager.undoCount
            val top = probe.tagged(LayerWindowTags.row(l3.id))
            drag(LayerLabels.reorder(1), top.center.y)
            assertEquals("$count saved: bottom to top", listOf<Layer>(l2, l3, l1), doc.layers.toList())
            assertEquals("$count saved: one step", steps + 1, c.undoManager.undoCount)
            c.undo()
            assertEquals("$count saved: undone", listOf<Layer>(l1, l2, l3), doc.layers.toList())
            // The rows slide back (animateItem) before the next drag measures them.
            assertTrue("$count saved: rows in place", settleUntil { rowsTopFirst(probe, l3, l2, l1) })

            // The top layer onto the saved rows (when there are some), then onto the Selection Layer row: nothing.
            val stepsBefore = c.undoManager.undoCount
            if (count > 0) {
                val savedRow = probe.tagged(V17Tags.savedSelectionRow(doc.savedSelections[1].id))
                drag(LayerLabels.reorder(3), savedRow.center.y)
                assertEquals("$count saved: onto a saved row", listOf<Layer>(l1, l2, l3), doc.layers.toList())
                assertEquals("$count saved: no step (saved row)", stepsBefore, c.undoManager.undoCount)
            }
            val selectionRow = probe.tagged(LayerWindowTags.SELECTION_ROW)
            drag(LayerLabels.reorder(3), selectionRow.center.y)
            assertEquals("$count saved: onto the Selection Layer row", listOf<Layer>(l1, l2, l3), doc.layers.toList())
            assertEquals("$count saved: no step (Selection Layer row)", stepsBefore, c.undoManager.undoCount)
            assertEquals("$count saved: the saved selections kept", count, doc.savedSelections.size)
        }
        Smoke.assertQuiet(c, "reorder with saved rows")
    }
}
