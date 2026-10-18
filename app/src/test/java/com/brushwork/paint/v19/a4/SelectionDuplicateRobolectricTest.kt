package com.brushwork.paint.v19.a4

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import com.brushwork.paint.EditorController
import com.brushwork.paint.masks.AdjustmentEffects
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerArray
import com.brushwork.paint.model.Selection
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.select.SelectionDuplicate
import com.brushwork.paint.tools.text.TextItemOps
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.ui.common.LayerLabels19
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 (R14, A4; Gate 2): the selection bar's [LayerLabels19.DUPLICATE] copies what the selection
 * holds within the active layer as ONE step [LayerLabels19.DUPLICATE_STEP] that undo takes back whole:
 * - pixels (Gate 2): the selected pixels float as a copy in Transform at the same spot of the same
 *   layer ([TransformTool.startInLayerCopy]) with no step yet; ✓ after the user moves it draws it
 *   onto the layer as the step, the selection moving with it (alpha lock respected), and ✕, Delete
 *   or Undo while it floats leave the layer, the selection and the history as they were; the
 *   layer stays the only layer and keeps its kind;
 * - vector objects: the objects the selection touches, copied right above the topmost of them,
 *   offset right and down ([SelectionDuplicate.offset]) and selected, with the toast
 *   [LayerLabels19.DUPLICATED];
 * - it is not offered ([SelectionDuplicate.kindOf] null) where a copy would change the layer's
 *   kind or has nothing to copy: a legacy shape layer, an adjustment layer, a folder, a live
 *   array, a mask being edited, a text layer while [TextItemOps.available] is false.
 */
@RunWith(RobolectricTestRunner::class)
class SelectionDuplicateRobolectricTest {
    private val kit = A4Kit()

    @After
    fun tearDown() = kit.close()

    private val red = 0xFFE02020.toInt()

    private fun rectSelection(c: EditorController, l: Float, t: Float, r: Float, b: Float) =
        c.setSelection(Selection.fromPath(Path().apply { addRect(l, t, r, b, Path.Direction.CW) }, c.doc.width, c.doc.height, antiAlias = false))

    private fun painted(c: EditorController): Layer =
        c.addLayerWithContent("Painted", "Add") { cv: Canvas -> cv.drawRect(20f, 20f, 60f, 50f, Paint().apply { color = red }) }!!

    private fun pixels(layer: Layer) = kit.pixels(layer.bitmap)

    private fun transform(c: EditorController) = c.tools[ToolId.TRANSFORM] as TransformTool

    /** Gate 2: the copy floats in Transform on the same layer with no step; moved and ✓, it is ONE step. */
    @Test
    fun selectedPixelsFloatOnTheSameLayerAndLandAsOneStep() {
        val c = kit.controller()
        val layer = painted(c)
        rectSelection(c, 10f, 10f, 70f, 60f)
        val d = SelectionDuplicate.offset(c)
        assertEquals("16 px at 100 %", 16, d)
        assertEquals(SelectionDuplicate.Kind.PIXELS, SelectionDuplicate.kindOf(c))
        val before = pixels(layer)
        val selBefore = c.selection!!.bounds
        val layers = c.doc.layers.size
        val steps = c.undoManager.undoCount
        assertTrue(SelectionDuplicate.duplicate(c))
        // Floating: Transform holds the copy over the same spot; nothing is recorded or drawn yet.
        val tool = transform(c)
        assertEquals(ToolId.TRANSFORM, c.activeToolId)
        assertTrue("floating", tool.hasPendingWork)
        assertEquals(TransformTool.Lifted.PLACEMENT, tool.lifted)
        assertEquals("no step while it floats", steps, c.undoManager.undoCount)
        assertEquals("no new layer", layers, c.doc.layers.size)
        assertTrue("the layer is untouched while it floats", before.contentEquals(pixels(layer)))
        // The user drags it d px right and down and taps ✓.
        tool.moveBy(d.toFloat(), d.toFloat())
        tool.commit()
        kit.settle(c)
        assertFalse(tool.hasPendingWork)
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertEquals(LayerLabels19.DUPLICATE_STEP, c.undoManager.undoLabel)
        assertEquals("no new layer", layers, c.doc.layers.size)
        assertEquals(layer, c.activeLayer)
        // The original stays; the copy lies d px right and down.
        assertEquals(red, layer.bitmap.getPixel(21, 21))
        assertEquals(red, layer.bitmap.getPixel(20 + d + 1, 20 + d + 1))
        assertEquals(red, layer.bitmap.getPixel(59 + d, 49 + d))
        assertEquals("nothing past the copy", 0, layer.bitmap.getPixel(61 + d, 51 + d))
        // Exactly the original plus the copy (the fixture of compat row 10 stays as it was).
        val expected = before.copyOf().also { px ->
            for (y in 20 + d until 50 + d) for (x in 20 + d until 60 + d) px[y * kit.w + x] = red
        }
        assertTrue("the original and the copy, pixel for pixel", expected.contentEquals(pixels(layer)))
        assertEquals("the selection moved onto the copy", Rect(selBefore).apply { offset(d, d) }, c.selection!!.bounds)
        val after = pixels(layer)
        c.undo()
        assertTrue("undone: pixels", before.contentEquals(pixels(layer)))
        assertEquals("undone: the selection", selBefore, c.selection!!.bounds)
        assertEquals(steps, c.undoManager.undoCount)
        c.redo()
        assertTrue("redone: pixels", after.contentEquals(pixels(layer)))
        assertEquals(Rect(selBefore).apply { offset(d, d) }, c.selection!!.bounds)
    }

    /**
     * Gate 2: ✕, Delete and Undo while the copy floats each leave the layer, the selection and the
     * history as they were (no step, the redo stack kept); the layer is never removed.
     */
    @Test
    fun cancellingTheFloatingCopyLeavesTheLayerAndHistoryAsTheyWere() {
        val c = kit.controller()
        val layer = painted(c)
        rectSelection(c, 10f, 10f, 70f, 60f)
        // A step to redo: changing the layer and undoing it.
        c.setLayerProps(layer, layer.props().copy(opacity = 0.5f))
        c.undo()
        assertTrue(c.undoManager.canRedo)
        val before = pixels(layer)
        val sel = c.selection
        val steps = c.undoManager.undoCount
        val label = c.undoManager.undoLabel
        val layers = c.doc.layers.size
        val tool = transform(c)
        val cancels = listOf<Pair<String, () -> Unit>>(
            "✕" to { tool.discard() },
            "Delete" to { assertTrue(tool.deleteContent()) },
            "Undo" to { c.undo() },
        )
        for ((what, cancel) in cancels) {
            assertTrue(what, SelectionDuplicate.duplicate(c))
            assertTrue(what, tool.hasPendingWork)
            tool.moveBy(16f, 16f)
            cancel()
            kit.settle(c)
            assertFalse("$what: nothing floats", tool.hasPendingWork)
            assertEquals("$what: no step", steps, c.undoManager.undoCount)
            assertEquals("$what: the same last step", label, c.undoManager.undoLabel)
            assertEquals("$what: the layer stays", layers, c.doc.layers.size)
            assertTrue("$what: the layer stays", c.doc.indexOf(layer) >= 0)
            assertTrue("$what: pixels as they were", before.contentEquals(pixels(layer)))
            assertSame("$what: the selection as it was", sel, c.selection)
            assertTrue("$what: the redo stack kept", c.undoManager.canRedo)
        }
        c.redo()
        assertEquals("the step before the Duplicate comes back", 0.5f, layer.opacity, 0.001f)
    }

    /**
     * The moved selection is the old coverage byte for byte, [d] px right and down; what leaves
     * the canvas is dropped and the bounds stay tight even where the cut leaves a clear edge.
     */
    @Test
    fun aMovedSelectionKeepsItsSoftEdgesAndDropsWhatLeavesTheCanvas() {
        val w = 64
        val h = 48
        val d = 16
        val bytes = ByteArray(w * h)
        // A soft patch x 30..55, y 20..39; its column 47 and row 31 are clear, and the canvas
        // edge cuts the moved patch right after them (x 63, y 47).
        for (y in 20 until 40) for (x in 30 until 56) {
            if (x == 47 || y == 31) continue
            bytes[y * w + x] = ((x * 7 + y * 13) % 255 + 1).toByte()
        }
        bytes[25 * w + 35] = 0xFF.toByte()
        val sel = Selection.fromBytes(bytes, w, h)
        val moved = SelectionDuplicate.shifted(sel, d, w, h)
        val expected = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val tx = x + d
            val ty = y + d
            if (tx < w && ty < h) expected[ty * w + tx] = bytes[y * w + x]
        }
        assertTrue("the coverage, shifted exactly", expected.contentEquals(moved.toBytes()))
        assertEquals("tight bounds: the clear cut edge is left out", Rect(46, 36, 63, 47), moved.bounds)
        assertEquals(Selection.computeBounds(moved.mask), moved.bounds)
        // Inside the canvas nothing is lost.
        val small = ByteArray(w * h).also { for (y in 2 until 9) for (x in 3 until 12) it[y * w + x] = (x * 20 + y).toByte() }
        val inside = SelectionDuplicate.shifted(Selection.fromBytes(small, w, h), d, w, h)
        val expectedInside = ByteArray(w * h).also { for (y in 2 until 9) for (x in 3 until 12) it[(y + d) * w + x + d] = small[y * w + x] }
        assertTrue("inside: shifted exactly", expectedInside.contentEquals(inside.toBytes()))
        assertEquals(Rect(3 + d, 2 + d, 12 + d, 9 + d), inside.bounds)
        // Moved off the canvas entirely: an empty selection.
        val corner = ByteArray(w * h).also { it[(h - 2) * w + w - 2] = 0x80.toByte() }
        assertTrue("off the canvas: empty", SelectionDuplicate.shifted(Selection.fromBytes(corner, w, h), d, w, h).isEmpty)
    }

    @Test
    fun aLockedTransparencyKeepsTheCopyInsideThePaintedPixels() {
        val c = kit.controller()
        val layer = painted(c)
        c.setLayerProps(layer, layer.props().copy(alphaLocked = true))
        rectSelection(c, 20f, 20f, 40f, 40f)
        val d = SelectionDuplicate.offset(c)
        assertTrue(SelectionDuplicate.duplicate(c))
        transform(c).run { moveBy(d.toFloat(), d.toFloat()); commit() }
        assertEquals(LayerLabels19.DUPLICATE_STEP, c.undoManager.undoLabel)
        assertEquals("over painted pixels", red, layer.bitmap.getPixel(20 + d + 2, 20 + d + 2))
        assertEquals("outside them: still transparent", 0, layer.bitmap.getPixel(39 + d, 52))
    }

    @Test
    fun theTouchedObjectsAreCopiedSelectedWithTheToast() {
        val c = kit.controller()
        val layer = kit.vectorLayer(c, listOf(kit.box(20f, 20f, 60f, 60f), kit.box(140f, 100f, 200f, 150f)), "Objects")
        val original = layer.vector!!
        rectSelection(c, 10f, 10f, 70f, 70f)
        assertEquals(SelectionDuplicate.Kind.OBJECTS, SelectionDuplicate.kindOf(c))
        val d = SelectionDuplicate.offset(c).toFloat()
        val steps = c.undoManager.undoCount
        assertTrue(SelectionDuplicate.duplicate(c))
        kit.settle(c)
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertEquals(LayerLabels19.DUPLICATE_STEP, c.undoManager.undoLabel)
        val objs = layer.vector!!.objects
        assertEquals(3, objs.size)
        assertTrue("still a vector layer", layer.isVectorLayer)
        // Right above the original (v1.5's object Duplicate), under the object it does not touch.
        val copy = objs[1]
        assertTrue("a new object", original.objects.none { it.id == copy.id })
        assertEquals("the copy is selected", setOf(copy.id), c.vectors.selectedIds)
        val b0 = c.vectors.paintBounds(objs[0])
        val b2 = c.vectors.paintBounds(copy)
        assertEquals(b0.left + d, b2.left, 0.01f)
        assertEquals(b0.top + d, b2.top, 0.01f)
        assertEquals(String.format(LayerLabels19.DUPLICATED, 1), c.message)
        c.undo()
        kit.settle(c)
        assertEquals("undone", original, layer.vector)
    }

    @Test
    fun aSelectionOverNoObjectSaysSoAndMakesNoStep() {
        val c = kit.controller()
        val layer = kit.vectorLayer(c, listOf(kit.box(20f, 20f, 60f, 60f)), "Objects")
        rectSelection(c, 120f, 100f, 200f, 160f)
        val steps = c.undoManager.undoCount
        assertFalse(SelectionDuplicate.duplicate(c))
        kit.settle(c)
        assertEquals(steps, c.undoManager.undoCount)
        assertEquals(1, layer.vector!!.objects.size)
        assertEquals(String.format(LayerLabels19.NOTHING_TO_DUPLICATE, layer.name), c.message)
    }

    @Test
    fun itIsNotOfferedWhereACopyWouldChangeTheLayer() {
        val c = kit.controller()
        assertNull("no selection", SelectionDuplicate.kindOf(c))
        c.selectAll()
        val shape = kit.shapeLayer(c, kit.rect(60f, 50f, 40f, 30f))
        assertNull("a legacy shape layer", SelectionDuplicate.kindOf(c, shape))
        val text = kit.textLayer(c, kit.text("Hi", 60f, 50f))
        assertEquals("a text layer: only once texts can be duplicated", if (TextItemOps.available) SelectionDuplicate.Kind.TEXTS else null, SelectionDuplicate.kindOf(c, text))
        val adjustment = c.addAdjustmentLayer(AdjustmentEffects.defaultSpec(), null)!!
        assertNull("an adjustment layer", SelectionDuplicate.kindOf(c, adjustment))
        val folder = c.addFolder()!!
        assertNull("a folder", SelectionDuplicate.kindOf(c, folder))
        val arrayed = painted(c)
        arrayed.array = LayerArray(ArraySpec(count = 3))
        assertNull("a live array", SelectionDuplicate.kindOf(c, arrayed))
        val masked = painted(c)
        c.addMask(masked, fromSelection = false)
        c.setEditingMask(masked, true)
        assertNull("a mask being edited", SelectionDuplicate.kindOf(c, masked))
        c.setEditingMask(masked, false)
        assertEquals("its pixels", SelectionDuplicate.Kind.PIXELS, SelectionDuplicate.kindOf(c, masked))
    }
}
