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
import com.brushwork.paint.ui.editor.HistoryLabels
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.7 review (item 8, §3.8 "Moving"): the long-press drag of the layer window across folders,
 * with a finger on the ≡ handle on the user's 392 dp phone. A folder drags with its whole block
 * (its rows hide while it moves); a layer dragged down onto an open folder's row becomes its top
 * child; a child dragged up past its folder's row leaves the folder. Each drop is ONE "Move
 * layer" step that undo restores exactly and redo applies again exactly.
 */
// Own sandbox (the test recomposer policy and paused Choreographer are global); the user's phone size.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.layers.folderblockdragsandbox"])
class FolderBlockDragUiRobolectricTest {

    private lateinit var c: EditorController
    private var density = 1f

    /** The tree as the window and the file see it: order, parents and every folder's state. */
    private fun tree(): List<String> = c.doc.layers.map { l ->
        "${l.name}<${c.doc.layerById(l.parentId)?.name ?: "root"}>" + (l.folder?.let { " ${it.passThrough} ${l.folderOpen}" } ?: "")
    }

    private fun names(): List<String> = c.doc.layers.map { it.name }

    /**
     * Drags [layer]'s ≡ handle vertically by [dy] dp in 8 dp moves (down when positive). With
     * [whileDragging], the window is checked mid-drag (the finger still down).
     */
    private fun drag(layer: Layer, dy: Float, whileDragging: (() -> Unit)? = null) {
        // The rows' placement animations end first (the list finds the row under the finger by
        // its layout), as a user's finger waits: the handle stays put over two settles.
        val label = LayerLabels.reorder(c.doc.indexOf(layer) + 1)
        var handle = SmokeUi.find(label, exact = true)!!
        for (i in 0 until 20) {
            SmokeUi.settle()
            val now = SmokeUi.find(label, exact = true)!!
            if (now.bounds == handle.bounds) break
            handle = now
        }
        val hb = handle.bounds
        val touch = Smoke.Touch(handle.window)
        val x = hb.center.x
        val y0 = hb.center.y
        touch.send(MotionEvent.ACTION_DOWN, Smoke.P(0, x, y0))
        val steps = (kotlin.math.abs(dy) / 8f).toInt()
        val dir = if (dy > 0) 1f else -1f
        for (i in 1..steps) {
            touch.idle(16)
            touch.send(MotionEvent.ACTION_MOVE, Smoke.P(0, x, y0 + dir * i * 8f * density))
            if (i == 3 && whileDragging != null) {
                SmokeUi.settle()
                whileDragging()
            }
        }
        touch.idle(16)
        touch.send(MotionEvent.ACTION_UP, Smoke.P(0, x, y0 + dir * steps * 8f * density))
        SmokeUi.settle()
    }

    /** [move] is ONE "Move layer" step giving [expected]; undo restores, redo re-applies, exactly. */
    private fun oneMove(what: String, expected: List<String>, move: () -> Unit) {
        val before = tree()
        val steps = c.undoManager.undoCount
        move()
        assertEquals("$what: the tree", expected, tree())
        assertEquals("$what: one step", steps + 1, c.undoManager.undoCount)
        assertEquals("$what: its name", HistoryLabels.MOVE_LAYER, c.undoManager.undoLabel)
        c.undoManager.undo(c)
        SmokeUi.settle()
        assertEquals("$what undone", before, tree())
        c.undoManager.redo(c)
        SmokeUi.settle()
        assertEquals("$what redone", expected, tree())
        c.undoManager.undo(c)
        SmokeUi.settle()
        assertEquals("$what undone again", before, tree())
    }

    @Test
    fun foldersDragAsBlocksAndDropsFollowTheRowAboveTheGap() {
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        density = activity.resources.displayMetrics.density
        c = Smoke.controller(activity, Smoke.document(64, 48, layers = 4, whiteBottom = true))
        val (l1, l2, l3, l4) = c.doc.layers
        // F (open) holds Layer 2 and Layer 3: [Layer 1, Layer 2, Layer 3, F, Layer 4].
        val folder = c.putInNewFolder(l3)!!
        folder.name = "F"
        assertTrue(c.putIntoFolderAbove(l2))
        activity.setContent {
            BrushworkTheme {
                Box(Modifier.fillMaxSize()) {
                    LayersPanel(
                        c,
                        onDismiss = {},
                        onImportPicture = {},
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = 5.dp, bottom = 92.dp).size(382.dp, 700.dp),
                    )
                }
            }
        }
        SmokeUi.settle()
        // A window tall enough for every row: the bottom-aligned list does not scroll.
        val start = listOf("Layer 1<root>", "Layer 2<F>", "Layer 3<F>", "F<root> true true", "Layer 4<root>")
        assertEquals(start, tree())

        // The folder dragged down one row: its rows hide while it moves, the rows above it stay
        // where they were (review: the list used to shift Layer 4 down under the finger, and the
        // folder went ABOVE it), and the whole block lands below Layer 1 with both children inside.
        fun top(l: Layer) = SmokeUi.find(LayerLabels.selectRow(c.doc.indexOf(l) + 1), exact = true)!!.bounds.top
        val l4Top = top(l4)
        oneMove("The folder down", listOf("Layer 2<F>", "Layer 3<F>", "F<root> true true", "Layer 1<root>", "Layer 4<root>")) {
            drag(folder, 96f) {
                assertFalse("a dragged folder's rows are not listed", SmokeUi.has(LayerLabels.selectRow(c.doc.indexOf(l3) + 1), exact = true))
                assertTrue(SmokeUi.has(LayerLabels.selectRow(c.doc.indexOf(l1) + 1), exact = true))
                assertEquals("the row above the dragged folder stays put", l4Top, top(l4))
            }
        }
        assertTrue("the rows are back after the drop", SmokeUi.has(LayerLabels.selectRow(c.doc.indexOf(l3) + 1), exact = true))

        // Layer 4 dragged down onto the open folder's row: its top child.
        oneMove("A layer onto an open folder", listOf("Layer 1<root>", "Layer 2<F>", "Layer 3<F>", "Layer 4<F>", "F<root> true true")) {
            drag(l4, 88f)
        }

        // Layer 3 (the folder's top child) dragged up past the folder's row: out of it.
        oneMove("A child up out of its folder", listOf("Layer 1<root>", "Layer 2<F>", "F<root> true true", "Layer 3<root>", "Layer 4<root>")) {
            drag(l3, -88f)
        }

        // A closed folder: a layer dragged down past it passes the whole block and stays at its level.
        c.setFolderOpen(folder, open = false)
        SmokeUi.settle()
        oneMove("A layer past a closed folder", listOf("Layer 1<root>", "Layer 4<root>", "Layer 2<F>", "Layer 3<F>", "F<root> true false")) {
            drag(l4, 88f)
        }
        assertEquals(start.dropLast(2) + "F<root> true false" + "Layer 4<root>", tree())
        assertEquals(listOf("Layer 1", "Layer 2", "Layer 3", "F", "Layer 4"), names())
    }
}
