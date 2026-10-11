package com.brushwork.paint.tools.vector

import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.VShape
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.8 F (design §3.1): a shape drawn with the mirror ruler on shows its copy from its start point
 * on and commits it in ONE undo step:
 * - into a raster layer (Editable off) as the union of the copies: a translucent fill is not
 *   darker where the copies overlap;
 * - with a brush line into a raster layer: the brush paints every copy;
 * - into a vector layer as ONE VShape that keeps the copies (`shape.copies`), its pixels what the
 *   renderer draws for it.
 */
@RunWith(RobolectricTestRunner::class)
class ShapeMirrorCommitTest {
    private val kit = ShapeCopiesKit()

    @After
    fun close() = kit.close()

    @Test
    fun theCopiesArePreviewedAndCommittedInOneStep() {
        // 1. Raster, plain fill at half alpha, overlapping its copy (x 140..180 is in both).
        run {
            val c = kit.controller()
            val tool = kit.rectTool(c, ShapeStyle.FILL, editable = false, fill = 0x80E02020.toInt())
            c.pointerDown(com.brushwork.paint.tools.ToolPoint(120f, 60f))
            c.pointerMove(com.brushwork.paint.tools.ToolPoint(150f, 100f))
            c.pointerMove(com.brushwork.paint.tools.ToolPoint(180f, 140f))
            assertTrue("the copy shows while the shape is drawn", kit.shownAt(c, 190, 100))
            c.pointerUp(com.brushwork.paint.tools.ToolPoint(180f, 140f))
            assertTrue(tool.hasPendingWork)
            assertTrue("the copy shows while the shape is open", kit.shownAt(c, 190, 100))
            val layer = c.doc.activeLayer
            val layers = c.doc.layers.size
            val steps = c.undoManager.undoCount
            tool.commit()
            assertFalse(tool.hasPendingWork)
            assertEquals("ONE step", steps + 1, c.undoManager.undoCount)
            assertEquals("no new layer", layers, c.doc.layers.size)
            val single = kit.alphaAt(layer.bitmap, 130, 100)
            assertTrue("half alpha: $single", single in 100..160)
            assertEquals("the copy", single, kit.alphaAt(layer.bitmap, 190, 100))
            assertEquals("the overlap is not darker", single, kit.alphaAt(layer.bitmap, 150, 100))
            assertEquals(0, kit.alphaAt(layer.bitmap, 110, 100))
            assertEquals(0, kit.alphaAt(layer.bitmap, 210, 100))
            c.undo()
            assertEquals(0, kit.alphaAt(layer.bitmap, 190, 100))
            assertEquals(0, kit.alphaAt(layer.bitmap, 130, 100))
        }
        // 2. Raster, a brush line: the brush paints the source and the copy, in one step.
        run {
            val c = kit.controller()
            val tool = kit.rectTool(c, ShapeStyle.STROKE, editable = false) { it.copy(strokeWith = ShapeStroke.BRUSH) }
            kit.drawRect(c, 40f, 60f, 100f, 140f)
            kit.idle()
            assertTrue("the source previewed", kit.shownAt(c, 40, 100))
            assertTrue("the copy previewed", kit.shownAt(c, 280, 100))
            val layer = c.doc.activeLayer
            val steps = c.undoManager.undoCount
            tool.commit()
            assertEquals("ONE step", steps + 1, c.undoManager.undoCount)
            assertNotEquals("the source's left side", 0, kit.alphaAt(layer.bitmap, 40, 100))
            assertNotEquals("the copy's right side", 0, kit.alphaAt(layer.bitmap, 280, 100))
            assertEquals("inside the copy", 0, kit.alphaAt(layer.bitmap, 250, 100))
            c.undo()
            assertEquals(0, kit.alphaAt(layer.bitmap, 280, 100))
        }
        // 3. Vector layer: one object keeping its copies.
        run {
            val c = kit.controller(vector = true)
            val tool = kit.rectTool(c, ShapeStyle.STROKE_FILL, editable = true)
            kit.drawRect(c, 40f, 60f, 100f, 140f)
            assertTrue(kit.shownAt(c, 250, 100))
            val layer = c.doc.activeLayer
            val layers = c.doc.layers.size
            val steps = c.undoManager.undoCount
            tool.commit()
            c.vectors.flushPending()
            assertEquals("ONE step", steps + 1, c.undoManager.undoCount)
            assertEquals(layers, c.doc.layers.size)
            val o = layer.vector!!.objects.single() as VShape
            assertEquals("the mirror's two instances", 2, o.shape.copies.count)
            assertEquals(ObjectCopies.fromSymmetry(ObjectCopies.mapsAt(c, 40f, 60f)), o.shape.copies)
            assertEquals("the source is stored", 70f, o.shape.cx, 0.5f)
            assertArrayEquals("data and pixels together", kit.render(layer.vector!!), kit.rig.pixels(layer.bitmap))
            assertEquals(255, kit.alphaAt(layer.bitmap, 70, 100))
            assertEquals(255, kit.alphaAt(layer.bitmap, 250, 100))
            c.undo()
            c.vectors.flushPending()
            assertTrue(layer.vector!!.objects.isEmpty())
            assertEquals(0, kit.alphaAt(layer.bitmap, 250, 100))
        }
    }
}
