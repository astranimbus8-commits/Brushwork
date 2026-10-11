package com.brushwork.paint.tools.vector

import com.brushwork.paint.vector.CopyMaps
import com.brushwork.paint.vector.VShape
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.8 F (design §3.1): "Remove mirror copies" drops the open shape's copies as ONE in-tool step
 * (the editor's undo brings them back, redo takes them away again); the commit then stores none
 * and paints the source alone. Offered ([ShapeTool.hasCopies]) only while the open shape has
 * copies.
 */
@RunWith(RobolectricTestRunner::class)
class ShapeRemoveCopiesTest {
    private val kit = ShapeCopiesKit()

    @After
    fun close() = kit.close()

    @Test
    fun removingTheCopiesIsOneInToolStepAndTheCommitStoresNone() {
        // 1. A new shape on a vector layer.
        run {
            val c = kit.controller(vector = true)
            val tool = kit.rectTool(c, ShapeStyle.FILL, editable = true)
            assertFalse(tool.hasCopies)
            kit.drawRect(c, 40f, 60f, 100f, 140f)
            assertTrue(tool.hasCopies)
            assertTrue(kit.shownAt(c, 250, 100))
            val steps = c.undoManager.undoCount
            assertTrue(tool.removeCopies())
            assertFalse(tool.hasCopies)
            assertFalse("nothing more to remove", tool.removeCopies())
            assertEquals(CopyMaps.NONE, tool.pendingObject()!!.copies)
            assertFalse("the copy is gone", kit.shownAt(c, 250, 100))
            assertTrue("the source stays", kit.shownAt(c, 70, 100))
            assertTrue(tool.canUndoStep)
            c.undo()
            assertTrue("undo brings them back", tool.hasCopies)
            assertTrue(tool.hasPendingWork)
            assertTrue(kit.shownAt(c, 250, 100))
            c.redo()
            assertFalse("redo takes them away", tool.hasCopies)
            assertEquals("in-tool steps only", steps, c.undoManager.undoCount)
            tool.commit()
            c.vectors.flushPending()
            assertEquals("ONE step", steps + 1, c.undoManager.undoCount)
            val layer = c.doc.activeLayer
            val o = layer.vector!!.objects.single() as VShape
            assertTrue("no copies stored", o.shape.copies.isEmpty)
            assertEquals(255, kit.alphaAt(layer.bitmap, 70, 100))
            assertEquals(0, kit.alphaAt(layer.bitmap, 250, 100))
        }
        // 2. A shape layer reopened from its copy.
        run {
            val c = kit.controller()
            val tool = kit.rectTool(c, ShapeStyle.FILL, editable = true)
            kit.drawRect(c, 40f, 60f, 100f, 140f)
            tool.commit()
            val layer = c.doc.activeLayer
            val before = layer.shapeData
            kit.tap(c, 250f, 100f)
            assertSame(layer, tool.editingLayer)
            assertTrue(tool.hasCopies)
            val steps = c.undoManager.undoCount
            assertTrue(tool.removeCopies())
            tool.commit()
            assertFalse(tool.hasPendingWork)
            assertFalse(tool.hasCopies)
            assertEquals("ONE step", steps + 1, c.undoManager.undoCount)
            assertTrue(ShapeCodec.decode(layer.shapeData!!)!!.copies.isEmpty)
            assertEquals(255, kit.alphaAt(layer.bitmap, 70, 100))
            assertEquals(0, kit.alphaAt(layer.bitmap, 250, 100))
            c.undo()
            assertEquals(before, layer.shapeData)
            assertEquals(255, kit.alphaAt(layer.bitmap, 250, 100))
        }
    }
}
