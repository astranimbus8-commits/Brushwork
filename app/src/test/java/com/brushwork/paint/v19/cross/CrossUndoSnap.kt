package com.brushwork.paint.v19.cross

import android.graphics.Bitmap
import android.os.Looper
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.model.LayerProps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.robolectric.Shadows.shadowOf

/**
 * v1.9 Gate 2 (lane text; I1, I2, I22): the document as the user sees it (each layer's place,
 * properties, data, pixels and mask, the active layer and the selection), and the check that an
 * action is ONE document step whose Undo gives that document back exactly and whose Redo gives
 * the result back exactly. Nothing is rendered anew for the comparison: a pixel edit's undo swaps
 * the saved tiles back and a data change restores its snapshot, so nothing may differ by a level.
 * As `MergeUndoReopenTest` (A4) checks its merges, for the cross tests' controllers of any rig.
 */
internal object CrossUndoSnap {
    /** What one layer is: its place, data, properties and pixels (and mask). */
    class LayerSnap(val id: Long, val parent: Long, val props: LayerProps, val data: LayerData, val pixels: IntArray, val mask: IntArray?)

    /** The whole document, the active layer and the selection. */
    class Snap(val layers: List<LayerSnap>, val active: Int, val selection: ByteArray?)

    fun px(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** Lets the object edits waiting for a render land, and their renders finish (about 5 s at most). */
    fun settle(c: EditorController) {
        c.vectors.flushPending()
        val looper = shadowOf(Looper.getMainLooper())
        repeat(1000) {
            looper.idle()
            if (!c.vectors.isRendering) return
            Thread.sleep(5)
        }
        looper.idle()
        if (c.vectors.isRendering) fail("the vector layers are still rendering")
    }

    fun snap(c: EditorController): Snap {
        settle(c)
        return Snap(
            c.doc.layers.map { LayerSnap(it.id, it.parentId, it.props(), it.dataSnapshot(), px(it.bitmap), it.mask?.let { m -> px(m) }) },
            c.doc.activeLayerIndex,
            c.selection?.toBytes(),
        )
    }

    fun assertSameDoc(what: String, a: Snap, b: Snap) {
        assertEquals("$what: the layers", a.layers.map { it.id }, b.layers.map { it.id })
        assertEquals("$what: the folders", a.layers.map { it.parent }, b.layers.map { it.parent })
        assertEquals("$what: the active layer", a.active, b.active)
        for ((x, y) in a.layers.zip(b.layers)) {
            val name = "$what, \"${x.props.name}\""
            assertEquals("$name: properties", x.props, y.props)
            assertEquals("$name: data", x.data, y.data)
            if (!x.pixels.contentEquals(y.pixels)) {
                val at = x.pixels.indices.first { x.pixels[it] != y.pixels[it] }
                fail("$name: pixels differ at $at (${Integer.toHexString(x.pixels[at])} against ${Integer.toHexString(y.pixels[at])})")
            }
            assertEquals("$name: a mask", x.mask == null, y.mask == null)
            if (x.mask != null) assertTrue("$name: the mask's pixels", x.mask.contentEquals(y.mask))
        }
        assertEquals("$what: a selection", a.selection == null, b.selection == null)
        if (a.selection != null) assertTrue("$what: the selection", a.selection.contentEquals(b.selection))
    }

    /**
     * [act] (a whole session: its in-tool steps, then its ✓) is ONE document step, labelled [label]
     * unless that is null; one Undo gives back the document before it exactly and one Redo the
     * one after it. Ends in the redone state; returns the documents before and after.
     */
    fun oneExactStep(why: String, c: EditorController, label: String?, act: () -> Unit): Pair<Snap, Snap> {
        val before = snap(c)
        val steps = c.undoManager.undoCount
        act()
        val after = snap(c)
        assertEquals("$why: one document step", steps + 1, c.undoManager.undoCount)
        if (label != null) assertEquals("$why: the step's label", label, c.undoManager.undoLabel)
        assertTrue("$why: something changed", !sameDoc(before, after))
        c.undo()
        assertEquals("$why: one undo", steps, c.undoManager.undoCount)
        assertSameDoc("$why, undone", before, snap(c))
        c.redo()
        assertEquals("$why: one redo", steps + 1, c.undoManager.undoCount)
        assertSameDoc("$why, redone", after, snap(c))
        return before to after
    }

    private fun sameDoc(a: Snap, b: Snap): Boolean = try {
        assertSameDoc("", a, b)
        true
    } catch (e: AssertionError) {
        false
    }
}
