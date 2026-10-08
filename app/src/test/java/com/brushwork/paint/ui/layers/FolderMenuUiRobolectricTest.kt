package com.brushwork.paint.ui.layers

import android.graphics.Canvas
import android.graphics.Paint
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
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.engine.FolderComposite
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.ui.common.FolderLabels
import com.brushwork.paint.ui.editor.HistoryLabels
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.7 review (item 8, §3.8 "Folder properties" and "⋮ on a folder"), driven by their labels on
 * the user's 392 dp phone: the folder ⋮ items, the layer ⋮ "Put in new folder" and "Move into
 * folder above", the blend list starting with "Pass through" (a mode picked turns it off in the
 * same step), alpha lock and mask disabled with their reasons, and "Merge folder" on the strip
 * with its confirmation. Every operation is ONE step that undo restores exactly (the tree, every
 * folder's state and the picture) and redo applies again exactly.
 */
// Own sandbox (the test recomposer policy and paused Choreographer are global); the user's phone size.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.layers.foldermenuuisandbox"])
class FolderMenuUiRobolectricTest {

    private lateinit var c: EditorController

    private fun click(label: String) = SmokeUi.click(label, exact = true)

    /** The tree, every layer's properties and the picture. */
    private fun state(): Pair<List<String>, Int> {
        val tree = c.doc.layers.map { l ->
            "${l.name}<${c.doc.layerById(l.parentId)?.name ?: "root"}> ${l.blendMode} ${l.opacity} ${l.visible} ${l.clipping}" +
                (l.folder?.let { " pass=${it.passThrough} open=${l.folderOpen}" } ?: "")
        }
        return tree to picture().contentHashCode()
    }

    private fun picture(): IntArray {
        val b = Compositor(c.doc) { null }.renderFlattened()
        return IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
    }

    private fun undo() { c.undoManager.undo(c); SmokeUi.settle() }
    private fun redo() { c.undoManager.redo(c); SmokeUi.settle() }

    /**
     * [act] is ONE step named [label]; undo restores the state exactly, redo gives the state
     * after it exactly. Leaves the step done when [keep], else undone. Returns the state after it.
     */
    private fun oneStep(what: String, label: String, keep: Boolean = false, act: () -> Unit): Pair<List<String>, Int> {
        val before = state()
        val steps = c.undoManager.undoCount
        act()
        SmokeUi.settle()
        val after = state()
        assertEquals("$what: one step", steps + 1, c.undoManager.undoCount)
        assertEquals("$what: its name", label, c.undoManager.undoLabel)
        undo()
        assertEquals("$what undone", before, state())
        redo()
        assertEquals("$what redone", after, state())
        if (!keep) {
            undo()
            assertEquals("$what undone again", before, state())
        }
        return after
    }

    private fun menu(layer: Layer) {
        c.selectLayer(layer)
        SmokeUi.settle()
        click(LayerLabels.MORE)
    }

    private fun fill(l: Layer, color: Int, left: Int, top: Int, right: Int, bottom: Int) {
        Canvas(l.bitmap).drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), Paint().apply { this.color = color })
        l.markChanged()
    }

    @Test
    fun theFolderMenusBlendListAndMergeWorkByTheirLabels() {
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        c = Smoke.controller(activity, Smoke.document(64, 48, layers = 3, whiteBottom = true))
        val (l1, l2, l3) = c.doc.layers
        fill(l1, 0xFF3080C0.toInt(), 0, 0, 32, 48)
        fill(l2, 0xFFE05030.toInt(), 8, 8, 40, 40)
        fill(l3, 0xFF40C060.toInt(), 24, 4, 56, 30)
        l3.blendMode = LayerBlendMode.MULTIPLY
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

        // Layer ⋮ "Put in new folder" on Layer 3: no "Move into folder above" before a folder exists.
        menu(l3)
        assertFalse(SmokeUi.has(FolderLabels.MOVE_IN, exact = true))
        oneStep("Put in new folder", HistoryLabels.PUT_IN_NEW_FOLDER, keep = true) { click(FolderLabels.PUT_IN_NEW) }
        val folder = c.doc.layers.single { it.isFolder }
        assertEquals("Folder 1", folder.name)
        assertEquals(folder.id, l3.parentId)

        // Layer ⋮ on Layer 2 (right below the folder): "Move into folder above"; "Move out of folder" off.
        menu(l2)
        assertTrue(SmokeUi.isEnabled(FolderLabels.MOVE_IN))
        assertFalse(SmokeUi.isEnabled(FolderLabels.MOVE_OUT))
        oneStep("Move into folder above", HistoryLabels.MOVE_INTO_FOLDER, keep = true) { click(FolderLabels.MOVE_IN) }
        assertEquals(folder.id, l2.parentId)
        assertEquals(listOf(l1, l2, l3, folder), c.doc.layers)

        // The folder's ⋮: its own items, none of the pixel ones.
        menu(folder)
        for (label in listOf(FolderLabels.RENAME, FolderLabels.DUPLICATE, FolderLabels.FROM_FOLDER, FolderLabels.UNGROUP)) {
            assertTrue("$label is offered", SmokeUi.isEnabled(label))
        }
        assertFalse("a folder has no pixels to flip", SmokeUi.has("Flip horizontal", exact = true))
        assertFalse(SmokeUi.isEnabled(FolderLabels.MOVE_IN))
        assertFalse(SmokeUi.isEnabled(FolderLabels.MOVE_OUT))
        // The mask page: "Add mask" disabled, with the reason.
        click("${LayerLabels.MASK_ACTIONS} (no mask)")
        assertFalse(SmokeUi.isEnabled("Add mask"))
        assertTrue(SmokeUi.has(FolderLabels.NO_MASK, exact = true))
        click("Back")
        // "Rename folder" asks with its own title.
        click(FolderLabels.RENAME)
        assertTrue(SmokeUi.has(FolderLabels.RENAME, exact = true))
        click("Cancel")

        // Duplicate, Layer from folder and Ungroup: one step each.
        menu(folder)
        oneStep("Duplicate folder", HistoryLabels.DUPLICATE_FOLDER) { click(FolderLabels.DUPLICATE) }
        menu(folder)
        val fromFolder = oneStep("Layer from folder", HistoryLabels.LAYER_FROM_FOLDER, keep = true) { click(FolderLabels.FROM_FOLDER) }
        val made = c.doc.layers[c.doc.indexOf(folder) + 1]
        assertFalse(made.isFolder)
        assertArrayEquals(
            "its pixels are the folder's composite",
            FolderComposite.renderBlock(c.doc, folder).let { b -> IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) } },
            IntArray(made.bitmap.width * made.bitmap.height).also { made.bitmap.getPixels(it, 0, made.bitmap.width, 0, 0, made.bitmap.width, made.bitmap.height) },
        )
        assertEquals(fromFolder, state())
        undo()
        menu(folder)
        oneStep("Ungroup folder", HistoryLabels.UNGROUP_FOLDER) { click(FolderLabels.UNGROUP) }

        // The blend list starts with "Pass through"; Multiply turns it off in the same step.
        c.selectLayer(folder)
        SmokeUi.settle()
        click(LayerLabels.BLEND)
        assertTrue(SmokeUi.has(FolderLabels.PASS_THROUGH, exact = true))
        oneStep("Multiply on a pass-through folder", LayerOps.BLEND_STEP, keep = true) { click(LayerBlendMode.MULTIPLY.label) }
        assertFalse(folder.folder!!.passThrough)
        assertEquals(LayerBlendMode.MULTIPLY, folder.blendMode)
        click(LayerLabels.BLEND)
        oneStep("Pass through again", HistoryLabels.PASS_THROUGH) { click(FolderLabels.PASS_THROUGH) }
        undo()
        assertTrue(folder.folder!!.passThrough)
        assertEquals(LayerBlendMode.NORMAL, folder.blendMode)

        // Alpha lock is disabled on a folder; a tap says why and changes nothing.
        assertFalse(SmokeUi.isEnabled(LayerLabels.ALPHA_LOCK))
        val steps = c.undoManager.undoCount
        SmokeUi.tap(LayerLabels.ALPHA_LOCK, exact = true)
        SmokeUi.settle()
        assertEquals(FolderLabels.NO_ALPHA_LOCK, c.message)
        assertEquals(steps, c.undoManager.undoCount)

        // "Merge folder" on the strip: the Multiply child blends with Layer 1 through the
        // pass-through folder, so the window asks first; "Merge" makes one layer of the folder.
        val beforeMerge = picture()
        click(FolderLabels.MERGE)
        assertTrue(SmokeUi.has(FolderLabels.MERGE_BLEND_WARNING, exact = true))
        click("Cancel")
        assertTrue("Cancel merges nothing", folder in c.doc.layers)
        click(FolderLabels.MERGE)
        oneStep("Merge folder", HistoryLabels.MERGE_FOLDER, keep = true) { click("Merge") }
        assertEquals(2, c.doc.layers.size)
        assertFalse(c.doc.layers.any { it.isFolder })
        assertNotEquals("the warned change: Multiply now meets transparency", beforeMerge.contentHashCode(), picture().contentHashCode())
    }
}
