package com.brushwork.paint.tools.vector

import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.vector.VShape
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.8 F (design §3.1): a shape keeps the maps it was drawn with. Reopened with the ruler off,
 * or with another ruler on, it shows and commits its STORED copies (not the ruler's). Tapping a
 * copy opens the SOURCE object (on a vector layer and on a shape layer).
 */
@RunWith(RobolectricTestRunner::class)
class ShapeMirrorReopenRulerOffTest {
    private val kit = ShapeCopiesKit()

    @After
    fun close() = kit.close()

    @Test
    fun aReopenedShapeKeepsItsStoredCopiesWhateverTheRulerIsNow() {
        // 1. A vector object.
        run {
            val c = kit.controller(vector = true)
            val tool = kit.rectTool(c, ShapeStyle.STROKE_FILL, editable = true)
            kit.drawRect(c, 40f, 60f, 100f, 140f)
            tool.commit()
            c.vectors.flushPending()
            val layer = c.doc.activeLayer
            val shape = layer.vector!!.objects.single() as VShape
            val copies = shape.shape.copies
            assertEquals(2, copies.count)

            // The ruler off: tapping the COPY opens the source object.
            c.updateSymmetry(SymmetrySettings())
            kit.tap(c, 250f, 100f)
            assertTrue("opened from its copy", tool.hasPendingWork)
            assertEquals(70f, tool.pendingObject()!!.cx, 0.5f)
            assertEquals("the stored maps", copies, tool.pendingObject()!!.copies)
            assertTrue("the copy shows", kit.shownAt(c, 250, 100))
            tool.update { it.copy(strokeWidth = 8f) }
            tool.commit()
            c.vectors.flushPending()
            val edited = layer.vector!!.objects.single() as VShape
            assertEquals("same object", shape.id, edited.id)
            assertEquals(8f, edited.shape.strokeWidth, 0f)
            assertEquals(copies, edited.shape.copies)
            assertArrayEquals(kit.render(layer.vector!!), kit.rig.pixels(layer.bitmap))
            assertEquals(255, kit.alphaAt(layer.bitmap, 250, 100))

            // Another ruler on (rotation, 4): still the stored mirror.
            c.updateSymmetry(SymmetrySettings(type = SymmetryType.ROTATION, divisions = 4))
            assertTrue(tool.editShapeObject(layer, shape.id))
            assertEquals(copies, tool.pendingObject()!!.copies)
            tool.update { it.copy(strokeWidth = 6f) }
            tool.commit()
            c.vectors.flushPending()
            assertEquals(copies, (layer.vector!!.objects.single() as VShape).shape.copies)
            assertEquals("nothing where a rotation copy would be", 0, kit.alphaAt(layer.bitmap, 200, 30))
        }
        // 2. A shape layer, the ruler off: the copy opens the layer; the copies stay.
        run {
            val c = kit.controller()
            val tool = kit.rectTool(c, ShapeStyle.FILL, editable = true)
            kit.drawRect(c, 40f, 60f, 100f, 140f)
            tool.commit()
            val layer = c.doc.activeLayer
            assertTrue(layer.isShapeLayer)
            val copies = ShapeCodec.decode(layer.shapeData!!)!!.copies
            assertEquals(2, copies.count)
            c.updateSymmetry(SymmetrySettings())
            c.selectLayer(c.doc.layers.first())
            kit.tap(c, 250f, 100f)
            assertTrue(tool.hasPendingWork)
            assertSame(layer, tool.editingLayer)
            assertEquals(copies, tool.pendingObject()!!.copies)
            tool.update { it.copy(fillColor = 0xFF20C040.toInt()) }
            tool.commit()
            assertFalse(tool.hasPendingWork)
            assertEquals(copies, ShapeCodec.decode(layer.shapeData!!)!!.copies)
            assertEquals(0xFF20C040.toInt(), layer.bitmap.getPixel(250, 100))
        }
    }
}
