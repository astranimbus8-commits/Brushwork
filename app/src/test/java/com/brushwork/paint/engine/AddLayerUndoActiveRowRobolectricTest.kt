package com.brushwork.paint.engine

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.select.SelectionEdits
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.7 (area E): undoing the v1.6 `AddLayerAction` (a plain layer added at the root of a
 * document without folders) selects the row that was active before the add again, as a folder
 * document's `LayerTreeAction` does, instead of whatever row slid into the removed one's place.
 * Redo selects the added layer. One step each, the layers and pixels exactly as before.
 */
@RunWith(RobolectricTestRunner::class)
class AddLayerUndoActiveRowRobolectricTest {
    private val scope = Smoke.newScope()
    private val size = 32

    @After
    fun tearDown() = scope.cancel()

    /** Three layers, bottom first; the bottom one active and painted. */
    private fun controller(): EditorController {
        val doc = Smoke.document(size, size, layers = 3)
        Canvas(doc.layers[0].bitmap).drawRect(Rect(4, 4, 20, 20), Paint().apply { color = 0xFF3366CC.toInt() })
        doc.activeLayerIndex = 0
        return Smoke.controller(RuntimeEnvironment.getApplication(), doc, scope)
    }

    private fun pixels(l: Layer) = IntArray(size * size).also { l.bitmap.getPixels(it, 0, size, 0, 0, size, size) }

    @Test
    fun undoingAnAddSelectsTheRowThatWasActiveBefore() {
        val c = controller()
        val (bottom, middle, top) = c.doc.layers.toList()
        val steps = c.undoManager.undoCount

        // Above the active (bottom) row: the middle row slides into the removed one's place.
        val added = requireNotNull(c.addLayer())
        assertEquals(listOf(bottom, added, middle, top), c.doc.layers.toList())
        assertSame(added, c.activeLayer)
        assertEquals(steps + 1, c.undoManager.undoCount)
        c.undo()
        assertEquals(listOf(bottom, middle, top), c.doc.layers.toList())
        assertSame("undo selects the row active before the add", bottom, c.activeLayer)
        c.redo()
        assertEquals(listOf(bottom, added, middle, top), c.doc.layers.toList())
        assertSame("redo selects the added layer", added, c.activeLayer)
        c.undo()
        assertSame(bottom, c.activeLayer)

        // At the top (an index): the clamp would have selected the top row.
        val atTop = requireNotNull(c.addLayer(index = 3))
        assertSame(atTop, c.activeLayer)
        c.undo()
        assertSame(bottom, c.activeLayer)
        c.redo()
        assertSame(atTop, c.activeLayer)
        c.undo()

        // "Cut to new layer": one step (pixel edit + add); undo gives back the pixels and the row.
        val before = pixels(bottom)
        val p = Path().apply { addRect(2f, 2f, 12f, 12f, Path.Direction.CW) }
        c.setSelection(Selection.fromPath(p, size, size, antiAlias = false), recordUndo = false)
        val stepsCut = c.undoManager.undoCount
        val cut = requireNotNull(SelectionEdits.cutToNewLayer(c))
        assertEquals(stepsCut + 1, c.undoManager.undoCount)
        assertSame(cut, c.activeLayer)
        c.undo()
        assertEquals(listOf(bottom, middle, top), c.doc.layers.toList())
        assertSame("undo of the cut selects the source again", bottom, c.activeLayer)
        assertArrayEquals(before, pixels(bottom))
        c.redo()
        assertSame(cut, c.activeLayer)
        assertEquals(listOf(bottom, cut, middle, top), c.doc.layers.toList())
        Smoke.assertQuiet(c, "add layer undo")
    }
}
