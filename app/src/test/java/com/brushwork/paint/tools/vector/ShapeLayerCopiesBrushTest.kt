package com.brushwork.paint.tools.vector

import com.brushwork.paint.tools.ToolId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.8 F review (design §3.1, I17): the brush outline of a shape layer with mirror copies.
 * - Placed: the brush paints the source and the copy in the shape's ONE step.
 * - Reopened from the copy, moved and committed: the outline is replayed on every copy
 *   (`outlineReplay`'s copies), ONE step; undo puts the old pixels back.
 * - Reopened with a tool that moves pixels (blur): the outline is one symmetric stroke on the
 *   layer (`BrushTool.beginPath`'s maps): the copy's edge is blurred as the source's is, ONE step.
 */
@RunWith(RobolectricTestRunner::class)
class ShapeLayerCopiesBrushTest {
    private val kit = ShapeCopiesKit()

    @After
    fun close() = kit.close()

    @Test
    fun aShapeLayersBrushOutlineFollowsItsCopies() {
        val c = kit.controller()
        // A 6 px hard brush outline round a filled rectangle; the mirror's copy spans x 220..280.
        val tool = kit.rectTool(c, ShapeStyle.STROKE_FILL, editable = true) { it.copy(strokeWith = ShapeStroke.BRUSH) }
        kit.drawRect(c, 40f, 60f, 100f, 140f)
        kit.idle()
        val stepsBefore = c.undoManager.undoCount
        tool.commit()
        assertEquals("ONE step", stepsBefore + 1, c.undoManager.undoCount)
        val layer = c.doc.activeLayer
        assertTrue(layer.isShapeLayer)
        val fill = 0xFF40C0E0.toInt()
        val line = c.color
        assertEquals("the source's outline", line, layer.bitmap.getPixel(40, 100))
        assertEquals("the copy's outline", line, layer.bitmap.getPixel(279, 100))
        assertEquals(fill, layer.bitmap.getPixel(250, 100))

        // Reopened from the copy, moved 10 px to the right: the copy moves 10 px to the left.
        kit.tap(c, 250f, 100f)
        assertSame(layer, tool.editingLayer)
        val steps = c.undoManager.undoCount
        tool.place(tool.pendingObject()!!.box.translated(10f, 0f))
        tool.commit()
        assertNull(tool.editingLayer)
        assertEquals("ONE step", steps + 1, c.undoManager.undoCount)
        assertEquals("the source's outline moved", line, layer.bitmap.getPixel(50, 100))
        assertEquals("the copy's outline moved", line, layer.bitmap.getPixel(269, 100))
        assertEquals("the copy's old outline is gone", 0, kit.alphaAt(layer.bitmap, 279, 100))
        c.undo()
        assertEquals(line, layer.bitmap.getPixel(279, 100))
        assertEquals(fill, layer.bitmap.getPixel(250, 100))

        // Reopened with the blur tool's brush (it moves pixels: one stroke with the maps).
        c.blurBrush = c.blurBrush.copy(size = 12f)
        c.selectTool(ToolId.BLUR)
        c.selectTool(ToolId.SHAPE)
        kit.tap(c, 250f, 100f)
        assertSame(layer, tool.editingLayer)
        tool.useCurrentBrush()
        val steps2 = c.undoManager.undoCount
        tool.place(tool.pendingObject()!!.box.translated(-10f, 0f))
        tool.commit()
        assertNull(tool.editingLayer)
        assertEquals("ONE step", steps2 + 1, c.undoManager.undoCount)
        // The fill spans x 30..90 and 230..290 now; the blur runs along both edges: just outside
        // each edge the fill shows faintly, as far out on the copy as on the source.
        val left = kit.alphaAt(layer.bitmap, 28, 100)
        val right = kit.alphaAt(layer.bitmap, 291, 100)
        assertTrue("the source's edge blurred: $left", left in 1..254)
        assertTrue("the copy's edge blurred: $right", right in 1..254)
        assertTrue("as much on both sides: $left, $right", kotlin.math.abs(left - right) <= 48)
        c.undo()
        assertEquals(line, layer.bitmap.getPixel(279, 100))
    }
}
