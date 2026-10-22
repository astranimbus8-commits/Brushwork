package com.brushwork.paint.v110.a6

import android.graphics.Paint
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.editor.SelectionBarEdit
import com.brushwork.paint.vector.select.ObjectTestKit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 A6 (R10): the selection bar's Edit for a legacy shape layer (a v1.4-style layer of one
 * shape). With a selection drawn on it, Edit opens its shape in the Shape tool
 * ([com.brushwork.paint.ui.layers.LayerOps.editShape]) and records no step; ✕ leaves the layer
 * as it was. A locked layer says why and opens nothing; no selection, no Edit.
 */
@RunWith(RobolectricTestRunner::class)
class SelectionBarEditLegacyShapeTest {
    private val kit = ObjectTestKit(400, 300)

    @After
    fun close() = kit.close()

    private val box = ShapeObject(ShapeType.RECTANGLE, cx = 200f, cy = 150f, w = 120f, h = 80f, style = ShapeStyle.FILL)

    /** A shape layer of [box], made active. */
    private fun shapeLayer(c: EditorController): Layer {
        val layer = c.addLayerWithContent("Shape", "Add shape", shapeData = ShapeCodec.encode(box)) { cv ->
            cv.drawRect(box.cx - box.w / 2f, box.cy - box.h / 2f, box.cx + box.w / 2f, box.cy + box.h / 2f, Paint().apply { color = 0xFF2050C0.toInt() })
        }!!
        assertSame(layer, c.activeLayer)
        return layer
    }

    @Test
    fun aShapeLayerWithASelectionOpensItsShape() {
        val c = kit.controller()
        val layer = shapeLayer(c)
        c.setSelection(kit.rectSelection(150, 120, 250, 180), recordUndo = false)
        val t = SelectionBarEdit.target(c)
        assertEquals(SelectionBarEdit.Shape(layer), t)
        val steps = c.undoManager.undoCount
        val data = layer.shapeData
        assertTrue(SelectionBarEdit.open(c, t!!))
        kit.idle()
        assertEquals(ToolId.SHAPE, c.activeToolId)
        val tool = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        assertSame("its shape is open", layer, tool.editingLayer)
        assertNotNull(tool.box)
        assertEquals("Edit records no step", steps, c.undoManager.undoCount)
        tool.discard()
        kit.idle()
        assertEquals("✕ leaves it", data, layer.shapeData)
        assertEquals(steps, c.undoManager.undoCount)
        assertEquals("the user stays in the Shape tool", ToolId.SHAPE, c.activeToolId)
    }

    @Test
    fun aLockedShapeLayerSaysWhy() {
        val c = kit.controller()
        val layer = shapeLayer(c)
        layer.locked = true
        c.setSelection(kit.rectSelection(0, 0, 400, 300), recordUndo = false)
        val t = SelectionBarEdit.target(c) ?: throw AssertionError("Edit is offered (the tap says why)")
        val tool = c.activeToolId
        c.message = null
        assertFalse(SelectionBarEdit.open(c, t))
        assertNotNull("the reason shows", c.message)
        assertEquals("the tool stays", tool, c.activeToolId)
    }

    @Test
    fun withoutASelectionThereIsNoEdit() {
        val c = kit.controller()
        shapeLayer(c)
        assertNull(SelectionBarEdit.target(c))
        c.setSelection(kit.rectSelection(10, 10, 20, 20), recordUndo = false)
        assertNotNull("any selection on the layer", SelectionBarEdit.target(c))
    }
}
