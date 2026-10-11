package com.brushwork.paint.tools.vector

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.8 F (design §3.1): with Editable on, a mirrored shape becomes ONE shape layer that keeps its
 * copies (its stored shape's `copies`), the source and the copy painted, as one undo step. Tapping
 * the COPY opens the layer (its source); the edit is previewed with the copy and committed with
 * the same copies.
 */
@RunWith(RobolectricTestRunner::class)
class ShapeLayerCopiesTest {
    private val kit = ShapeCopiesKit()

    @After
    fun close() = kit.close()

    @Test
    fun aShapeLayerKeepsItsCopiesThroughAnEdit() {
        val c = kit.controller()
        val red = 0xFFE02020.toInt()
        val green = 0xFF20C040.toInt()
        val tool = kit.rectTool(c, ShapeStyle.FILL, editable = true, fill = red)
        val base = c.doc.activeLayer
        kit.drawRect(c, 40f, 60f, 100f, 140f)
        assertTrue("previewed with its copy", kit.shownAt(c, 250, 100))
        val layers = c.doc.layers.size
        val steps = c.undoManager.undoCount
        tool.commit()
        assertEquals("ONE step", steps + 1, c.undoManager.undoCount)
        assertEquals("a layer of its own", layers + 1, c.doc.layers.size)
        val layer = c.doc.activeLayer
        assertTrue(layer.isShapeLayer)
        val stored = ShapeCodec.decode(layer.shapeData!!)!!
        assertEquals("the mirror's two instances", 2, stored.copies.count)
        assertEquals(70f, stored.cx, 0.5f)
        assertEquals(red, layer.bitmap.getPixel(70, 100))
        assertEquals("the copy", red, layer.bitmap.getPixel(250, 100))
        assertEquals(0, kit.alphaAt(layer.bitmap, 160, 100))
        assertEquals("nothing on the layer below", 0, kit.alphaAt(base.bitmap, 250, 100))

        // Tapping the copy opens the layer; the edit shows on both sides.
        kit.tap(c, 250f, 100f)
        assertTrue(tool.hasPendingWork)
        assertSame(layer, c.doc.activeLayer)
        assertSame(layer, tool.editingLayer)
        assertEquals("the stored maps", stored.copies, tool.pendingObject()!!.copies)
        assertEquals(70f, tool.pendingObject()!!.cx, 0.5f)
        tool.update { it.copy(fillColor = green) }
        val shown = kit.composite(c)
        assertEquals(green, shown.getPixel(70, 100))
        assertEquals("the copy previewed", green, shown.getPixel(250, 100))
        tool.commit()
        assertFalse(tool.hasPendingWork)
        assertEquals("ONE step", steps + 2, c.undoManager.undoCount)
        assertEquals(stored.copies, ShapeCodec.decode(layer.shapeData!!)!!.copies)
        assertEquals(green, layer.bitmap.getPixel(70, 100))
        assertEquals(green, layer.bitmap.getPixel(250, 100))
        c.undo()
        assertEquals(red, layer.bitmap.getPixel(250, 100))
        c.undo()
        assertEquals(layers, c.doc.layers.size)
        assertNotEquals(layer, c.doc.activeLayer)
    }
}
