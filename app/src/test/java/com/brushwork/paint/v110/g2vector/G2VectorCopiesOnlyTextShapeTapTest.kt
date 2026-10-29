package com.brushwork.paint.v110.g2vector

import com.brushwork.paint.EditorController
import com.brushwork.paint.array.ArrayBehind
import com.brushwork.paint.array.ArraySources
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.common.ArrayLabels110
import com.brushwork.paint.vector.select.ObjectTestKit
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
 * v1.10 G2 (V6 review): a copies-only text or shape layer ("Array behind" on a whole text or shape
 * layer, `ArraySpec.hideSource`) keeps the source's text or shape hidden, and the Text and Shape
 * tools never find it: with the copies-only layer active (the tools look at the active layer
 * first) a tap where the hidden one lies edits the visible original directly above it, not the
 * hidden one. The taps record nothing; after undo the original opens as before, and after redo
 * the copies-only layer hides its source again.
 */
@RunWith(RobolectricTestRunner::class)
class G2VectorCopiesOnlyTextShapeTapTest {
    private val kit = ObjectTestKit(512, 384)

    @After
    fun tearDown() {
        ArrayDraw.clearCaches()
        kit.close()
    }

    private fun EditorController.tap(p: Vec2) {
        pointerDown(ToolPoint(p.x, p.y))
        pointerUp(ToolPoint(p.x, p.y))
        kit.idle()
    }

    /** "Array behind" on the whole layer [src]: ONE step; the copies-only layer directly below it, active. */
    private fun behind(c: EditorController, src: Layer): Layer {
        val steps = c.undoManager.undoCount
        assertTrue(ArrayBehind.fromLayerBelow(c, src))
        kit.idle()
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertEquals(ArrayLabels110.ARRAY_BEHIND_STEP, c.undoManager.undoLabel)
        val made = c.activeLayer
        assertTrue("copies only", ArrayBehind.isCopiesOnly(made))
        assertTrue("its source is hidden", c.vectors.hidesObjects(made))
        assertEquals("directly below the source", c.doc.indexOf(src) - 1, c.doc.indexOf(made))
        return made
    }

    /** [tool] current with [layer] active (leaving the Array tool selects the source again first). */
    private fun EditorController.activate(tool: ToolId, layer: Layer) {
        selectTool(tool)
        kit.idle()
        selectLayer(layer)
        kit.idle()
        assertSame("the copies-only layer is active", layer, activeLayer)
    }

    @Test
    fun aTapOnACopiesOnlyTextLayerEditsTheVisibleOriginal() {
        val c = kit.controller()
        val item = TextItem("Hi", spec = TextSpec(sizePx = 48f, color = 0xFF000000.toInt()), cx = 120f, cy = 100f)
        val text = c.addLayerWithContent("Text", "Add text", textData = TextCodec.encode(item)) { cv ->
            TextRenderer.drawItem(cv, item, TextRenderer.prepare(item), null)
        }!!
        kit.idle()
        val at = Vec2(item.cx, item.cy)
        val tool = c.tools.getValue(ToolId.TEXT) as TextTool
        assertSame("the text is found", text, tool.textLayerAt(at))
        val made = behind(c, text)
        assertEquals("the hidden source: the same text", text.textData, made.textData)

        c.activate(ToolId.TEXT, made)
        assertNull("nothing is open before the tap", tool.editingLayer)
        assertSame("the hidden text is not found, the original above it is", text, tool.textLayerAt(at))
        val steps = c.undoManager.undoCount
        c.tap(at)
        assertSame("the tap edits the visible original", text, tool.editingLayer)
        tool.discard()
        kit.idle()
        assertNull(tool.editingLayer)
        assertEquals("the tap recorded nothing", steps, c.undoManager.undoCount)
        assertEquals("the hidden text is unchanged", text.textData, made.textData)

        // Undo: the copies-only layer goes; the original opens as before.
        c.undo()
        kit.idle()
        assertEquals(-1, c.doc.indexOf(made))
        c.selectTool(ToolId.TEXT)
        kit.idle()
        c.selectLayer(text)
        kit.idle()
        c.tap(at)
        assertSame("the original opens", text, tool.editingLayer)
        tool.discard()
        kit.idle()

        // Redo: the copies-only layer is back and still hides its text.
        c.redo()
        kit.idle()
        assertSame(made, c.activeLayer)
        assertTrue(ArrayBehind.isCopiesOnly(made))
        assertSame(text, tool.textLayerAt(at))
    }

    @Test
    fun aTapOnACopiesOnlyShapeLayerOpensTheVisibleOriginal() {
        val c = kit.controller()
        val o = ShapeObject(ShapeType.RECTANGLE, cx = 300f, cy = 200f, w = 80f, h = 60f, style = ShapeStyle.FILL, fillColor = 0xFF2244CC.toInt())
        val src = c.addLayerWithContent("Shape", "Add shape", shapeData = ShapeCodec.encode(o), draw = ArraySources.shapeDraw(o, ColorMode.RGB, kit.w, kit.h))!!
        kit.idle()
        val at = Vec2(o.cx, o.cy)
        val tool = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        assertSame("the shape is found", src, tool.shapeLayerAt(at))
        val made = behind(c, src)
        assertEquals("the hidden source: the same shape", src.shapeData, made.shapeData)

        c.activate(ToolId.SHAPE, made)
        assertNull("nothing is open before the tap", tool.editingLayer)
        assertFalse(tool.hasPendingWork)
        assertSame("the hidden shape is not found, the original above it is", src, tool.shapeLayerAt(at))
        val steps = c.undoManager.undoCount
        c.tap(at)
        assertSame("the tap opens the visible original", src, tool.editingLayer)
        tool.discard()
        kit.idle()
        assertFalse(tool.hasPendingWork)
        assertEquals("the tap recorded nothing", steps, c.undoManager.undoCount)
        assertEquals("the hidden shape is unchanged", src.shapeData, made.shapeData)

        // Undo: the copies-only layer goes; the original opens as before.
        c.undo()
        kit.idle()
        assertEquals(-1, c.doc.indexOf(made))
        c.selectTool(ToolId.SHAPE)
        kit.idle()
        c.selectLayer(src)
        kit.idle()
        c.tap(at)
        assertSame("the original opens", src, tool.editingLayer)
        tool.discard()
        kit.idle()

        // Redo: the copies-only layer is back and still hides its shape.
        c.redo()
        kit.idle()
        assertSame(made, c.activeLayer)
        assertTrue(ArrayBehind.isCopiesOnly(made))
        assertSame(src, tool.shapeLayerAt(at))
    }
}
