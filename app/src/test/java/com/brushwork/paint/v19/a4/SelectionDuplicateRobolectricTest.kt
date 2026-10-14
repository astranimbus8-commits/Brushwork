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
import com.brushwork.paint.tools.select.SelectionDuplicate
import com.brushwork.paint.tools.text.TextItemOps
import com.brushwork.paint.ui.common.LayerLabels19
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 (R14, A4): the selection bar's [LayerLabels19.DUPLICATE] copies what the selection holds
 * within the active layer, offset right and down ([SelectionDuplicate.offset]), as ONE step
 * [LayerLabels19.DUPLICATE_STEP] that undo takes back whole:
 * - pixels: the selected pixels drawn again on the same layer, the selection moved onto them
 *   (alpha lock respected); the layer stays the only layer and keeps its kind;
 * - vector objects: the objects the selection touches, copied right above the topmost of them and
 *   selected, with the toast [LayerLabels19.DUPLICATED];
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

    @Test
    fun selectedPixelsAreCopiedOffsetOnTheSameLayerAsOneStep() {
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
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertEquals(LayerLabels19.DUPLICATE_STEP, c.undoManager.undoLabel)
        assertEquals("no new layer", layers, c.doc.layers.size)
        assertEquals(layer, c.activeLayer)
        // The original stays; the copy lies d px right and down.
        assertEquals(red, layer.bitmap.getPixel(21, 21))
        assertEquals(red, layer.bitmap.getPixel(20 + d + 1, 20 + d + 1))
        assertEquals(red, layer.bitmap.getPixel(59 + d, 49 + d))
        assertEquals("nothing past the copy", 0, layer.bitmap.getPixel(61 + d, 51 + d))
        assertEquals("the selection moved onto the copy", Rect(selBefore).apply { offset(d, d) }, c.selection!!.bounds)
        val after = pixels(layer)
        c.undo()
        assertTrue("undone: pixels", before.contentEquals(pixels(layer)))
        assertEquals("undone: the selection", selBefore, c.selection!!.bounds)
        c.redo()
        assertTrue("redone: pixels", after.contentEquals(pixels(layer)))
        assertEquals(Rect(selBefore).apply { offset(d, d) }, c.selection!!.bounds)
    }

    @Test
    fun aLockedTransparencyKeepsTheCopyInsideThePaintedPixels() {
        val c = kit.controller()
        val layer = painted(c)
        c.setLayerProps(layer, layer.props().copy(alphaLocked = true))
        rectSelection(c, 20f, 20f, 40f, 40f)
        val d = SelectionDuplicate.offset(c)
        assertTrue(SelectionDuplicate.duplicate(c))
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
        assertEquals(String.format(SelectionDuplicate.NOTHING_HERE, layer.name), c.message)
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
