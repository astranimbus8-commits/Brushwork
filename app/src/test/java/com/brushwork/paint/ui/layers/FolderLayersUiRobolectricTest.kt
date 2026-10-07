package com.brushwork.paint.ui.layers

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
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.ui.common.FolderLabels
import com.brushwork.paint.ui.editor.HistoryLabels
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.7 (item 8, §3.8 d): folders in the layer window, driven by their labels on the user's
 * phone size. "New folder" makes "Folder 1" above the active layer; a swipe right on a row's ≡
 * handle moves it into the folder above and a swipe left out of its folder; the folder's
 * thumbnail opens and closes it ("Close Folder 1" / "Open Folder 1", no step); Delete asks
 * "Delete folder and its 2 layers?" and both answers work; each operation is one undo step.
 */
// Own sandbox (the test recomposer policy and paused Choreographer are global); the user's phone size.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.layers.folderlayersuisandbox"])
class FolderLayersUiRobolectricTest {

    private lateinit var c: EditorController
    private var density = 1f

    private fun steps() = c.undoManager.undoCount

    /** [block] adds exactly one undo step named [label]. */
    private fun oneStep(what: String, label: String, block: () -> Unit) {
        val before = steps()
        block()
        SmokeUi.settle()
        assertEquals("$what: one undo step", before + 1, steps())
        assertEquals("$what: step name", label, c.undoManager.undoLabel)
    }

    private fun undo() {
        c.undoManager.undo(c)
        SmokeUi.settle()
    }

    private fun click(label: String) = SmokeUi.click(label, exact = true)

    /** A horizontal swipe of 48 dp on [layer]'s ≡ handle (right: into the folder above). */
    private fun swipe(layer: Layer, right: Boolean) {
        // The rows' placement animations end first (the list finds the row under the finger
        // by its layout, the handle's bounds are where it is drawn), as a user's finger waits.
        Smoke.pump(1_000)
        val handle = SmokeUi.find(LayerLabels.reorder(c.doc.indexOf(layer) + 1), exact = true)!!
        val hb = handle.bounds
        val dir = if (right) 1f else -1f
        val touch = Smoke.Touch(handle.window)
        touch.send(MotionEvent.ACTION_DOWN, Smoke.P(0, hb.center.x, hb.center.y))
        for (i in 1..6) {
            touch.idle(16)
            touch.send(MotionEvent.ACTION_MOVE, Smoke.P(0, hb.center.x + dir * i * 8f * density, hb.center.y))
        }
        touch.idle(16)
        touch.send(MotionEvent.ACTION_UP, Smoke.P(0, hb.center.x + dir * 48f * density, hb.center.y))
        SmokeUi.settle()
    }

    @Test
    fun foldersAreMadeFilledOpenedClosedAndDeletedFromTheWindow() {
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        density = activity.resources.displayMetrics.density
        c = Smoke.controller(activity, Smoke.document(64, 48, layers = 3, whiteBottom = true))
        val (l1, l2, l3) = c.doc.layers
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

        // "New folder" (the special-layer menu): "Folder 1" right above the active layer, open.
        click(LayerLabels.SPECIAL)
        oneStep("New folder", HistoryLabels.NEW_FOLDER) { click(FolderLabels.NEW) }
        val folder = c.doc.layers[3]
        assertTrue(folder.isFolder)
        assertEquals("Folder 1", folder.name)
        assertSame("the new folder is active", folder, c.activeLayer)
        assertEquals(listOf(l1, l2, l3, folder), c.doc.layers)
        assertTrue("its thumbnail closes it", SmokeUi.has(FolderLabels.close("Folder 1"), exact = true))

        // Swipe right: into the folder above (parents only; the order stays).
        oneStep("Swipe right", HistoryLabels.MOVE_INTO_FOLDER) { swipe(l3, right = true) }
        assertEquals(folder.id, l3.parentId)
        oneStep("Swipe right below it", HistoryLabels.MOVE_INTO_FOLDER) { swipe(l2, right = true) }
        assertEquals(folder.id, l2.parentId)
        assertEquals(Layer.ROOT_ID, l1.parentId)
        assertEquals(listOf(l1, l2, l3, folder), c.doc.layers)

        // Swipe left on the folder's bottom child: out of the folder (below its block), then
        // back in through undo. Its top child does not leave by a swipe (it would have to move).
        val before = steps()
        swipe(l3, right = false)
        assertEquals("the top child stays", folder.id, l3.parentId)
        assertEquals(before, steps())
        oneStep("Swipe left", HistoryLabels.MOVE_OUT_OF_FOLDER) { swipe(l2, right = false) }
        assertEquals(Layer.ROOT_ID, l2.parentId)
        assertEquals(listOf(l1, l2, l3, folder), c.doc.layers)
        undo()
        assertEquals(folder.id, l2.parentId)
        assertEquals(listOf(l1, l2, l3, folder), c.doc.layers)

        // The thumbnail closes and opens the folder: its rows go and come back, with no step.
        val beforeToggle = steps()
        click(FolderLabels.close("Folder 1"))
        assertTrue(SmokeUi.has(FolderLabels.open("Folder 1"), exact = true))
        assertFalse("a closed folder's rows are not listed", SmokeUi.has(LayerLabels.selectRow(3), exact = true))
        assertTrue(SmokeUi.has(LayerLabels.selectRow(1), exact = true))
        click(FolderLabels.open("Folder 1"))
        assertTrue(SmokeUi.has(FolderLabels.close("Folder 1"), exact = true))
        assertTrue(SmokeUi.has(LayerLabels.selectRow(3), exact = true))
        assertEquals("opening and closing is no step", beforeToggle, steps())

        // Delete on the folder asks; "Folder only" keeps its layers at its place.
        c.selectLayer(folder)
        SmokeUi.settle()
        click(LayerLabels.DELETE)
        assertTrue(SmokeUi.has(FolderLabels.deleteAsk(2), exact = true))
        oneStep("Folder only", HistoryLabels.DELETE_FOLDER) { click(FolderLabels.FOLDER_ONLY) }
        assertEquals(listOf(l1, l2, l3), c.doc.layers)
        assertTrue(c.doc.layers.all { it.parentId == Layer.ROOT_ID })
        assertFalse(SmokeUi.has(FolderLabels.deleteAsk(2), exact = true))
        undo()
        assertEquals(listOf(l1, l2, l3, folder), c.doc.layers)
        assertEquals(listOf(folder.id, folder.id), listOf(l2.parentId, l3.parentId))

        // "Delete all": the folder and its layers go in one step; undo brings them back.
        c.selectLayer(folder)
        SmokeUi.settle()
        click(LayerLabels.DELETE)
        oneStep("Delete all", HistoryLabels.DELETE_FOLDER) { click(FolderLabels.DELETE_ALL) }
        assertEquals(listOf(l1), c.doc.layers)
        undo()
        assertEquals(listOf(l1, l2, l3, folder), c.doc.layers)
        assertEquals(listOf(folder.id, folder.id), listOf(l2.parentId, l3.parentId))
    }
}
