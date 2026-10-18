package com.brushwork.paint.v19.a9

import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.vector.VShape
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 A9, R14 S1 (design 3.2 A9 step 4): with "Editable (own layer)" on, a new shape drawn while
 * a legacy shape layer is selected joins it: the layer becomes a vector layer in place and the
 * shape its second object, in ONE step; one undo gives the legacy layer back exactly (its shape
 * data and its pixels). "New layer each time" on gives every new shape a layer of its own (v1.8);
 * so do a locked or hidden shape layer and a layer that is not a shape layer.
 */
@RunWith(RobolectricTestRunner::class)
class ShapeJoinLayerTest {
    private val rig = A9Rig()

    @After
    fun close() = rig.close()

    /** A rectangle placed as a legacy shape layer ("Rectangle", selected). */
    private fun shapeLayer(c: EditorController, tool: ShapeTool): Layer {
        rig.drawRect(c, tool)
        tool.commit()
        val layer = c.activeLayer
        assertTrue("a legacy shape layer", layer.isShapeLayer && !layer.isVectorLayer)
        return layer
    }

    /** A second shape, an ellipse at (190, 150), pending. */
    private fun drawSecond(c: EditorController, tool: ShapeTool) {
        tool.update { it.copy(type = ShapeType.ELLIPSE, style = ShapeStyle.STROKE) }
        rig.drag(c, 160f to 120f, 190f to 150f, 220f to 180f)
        assertTrue(tool.hasPendingWork)
    }

    private fun pixels(l: Layer): IntArray {
        val b = l.bitmap
        return IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
    }

    @Test
    fun aNewShapeJoinsTheSelectedShapeLayerInOneStepAndOneUndoGivesTheLayerBack() {
        val c = rig.controller()
        val tool = rig.shapeTool(c)
        val layer = shapeLayer(c, tool)
        val data = layer.shapeData
        val name = layer.name
        val before = pixels(layer)
        val layers = c.doc.layers.size
        val steps = c.undoManager.undoCount
        drawSecond(c, tool)
        tool.commit()
        assertFalse(tool.hasPendingWork)
        assertEquals("no new layer", layers, c.doc.layers.size)
        assertSame("the same layer, still selected", layer, c.activeLayer)
        val v = layer.vector ?: throw AssertionError("a vector layer")
        assertNull("the legacy data went into the vector content", layer.shapeData)
        assertEquals("both shapes are objects of the layer", 2, v.objects.count { it is VShape })
        assertEquals("the layer keeps its name", name, layer.name)
        assertEquals("ONE step", steps + 1, c.undoManager.undoCount)
        assertEquals("Shape", c.undoManager.undoLabel)
        assertTrue("the ellipse is drawn", layer.bitmap.getPixel(220, 150) != 0)

        c.undo()
        assertFalse(tool.hasPendingWork)
        assertEquals(layers, c.doc.layers.size)
        assertNull("not a vector layer again", layer.vector)
        assertTrue(layer.isShapeLayer)
        assertEquals("the shape data as it was", data, layer.shapeData)
        assertArrayEquals("the pixels as they were", before, pixels(layer))
        assertEquals(steps, c.undoManager.undoCount)

        c.redo()
        assertEquals(2, layer.vector!!.objects.count { it is VShape })
        assertNull(layer.shapeData)
    }

    @Test
    fun withNewLayerEachTimeEveryNewShapeGetsALayerOfItsOwn() {
        val c = rig.controller()
        val tool = rig.shapeTool(c)
        tool.update { it.copy(newLayerEachTime = true) }
        val layer = shapeLayer(c, tool)
        val data = layer.shapeData
        val layers = c.doc.layers.size
        drawSecond(c, tool)
        tool.commit()
        assertEquals("a layer of its own", layers + 1, c.doc.layers.size)
        assertTrue(c.activeLayer !== layer && c.activeLayer.isShapeLayer)
        assertEquals("the first layer is untouched", data, layer.shapeData)
        assertNull(layer.vector)
    }

    @Test
    fun aLockedOrHiddenShapeLayerIsLeftAsItIs() {
        for (hidden in listOf(false, true)) {
            val c = rig.controller()
            val tool = rig.shapeTool(c)
            val layer = shapeLayer(c, tool)
            if (hidden) layer.visible = false else layer.locked = true
            val layers = c.doc.layers.size
            drawSecond(c, tool)
            tool.commit()
            assertEquals("hidden $hidden: a layer of its own (v1.8)", layers + 1, c.doc.layers.size)
            assertNull(layer.vector)
            assertTrue(layer.isShapeLayer)
        }
    }

    @Test
    fun aPlainLayerSelectedKeepsV18sLayerOfItsOwn() {
        val c = rig.controller()
        val tool = rig.shapeTool(c)
        val base = c.activeLayer
        rig.drawRect(c, tool)
        tool.commit()
        assertEquals(2, c.doc.layers.size)
        assertNull(base.vector)
        assertFalse(base.isShapeLayer)
    }

    @Test
    fun withEditableOffTheShapeIsPaintedAsInV18() {
        val c = rig.controller()
        val tool = rig.shapeTool(c)
        val layer = shapeLayer(c, tool)
        val layers = c.doc.layers.size
        tool.update { it.copy(editable = false) }
        drawSecond(c, tool)
        tool.commit()
        assertEquals(layers, c.doc.layers.size)
        assertNull("no conversion", layer.vector)
    }

    @Test
    fun aShapeOutsideTheCanvasStaysPendingAndNothingConverts() {
        val c = rig.controller()
        val tool = rig.shapeTool(c)
        val layer = shapeLayer(c, tool)
        val steps = c.undoManager.undoCount
        rig.drag(c, 400f to 400f, 450f to 450f, 500f to 500f)
        assertTrue(tool.hasPendingWork)
        tool.commit()
        assertTrue("still pending", tool.hasPendingWork)
        assertNull(layer.vector)
        assertTrue(layer.isShapeLayer)
        assertEquals("no step", steps, c.undoManager.undoCount)
    }
}
