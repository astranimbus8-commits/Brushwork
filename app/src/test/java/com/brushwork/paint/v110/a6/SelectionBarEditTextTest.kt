package com.brushwork.paint.v110.a6

import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.ui.editor.SelectionBarEdit
import com.brushwork.paint.vector.select.ObjectTestKit
import com.brushwork.paint.vector.select.vec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 A6 (R10): the selection bar's Edit for text. It is offered when exactly one text of the
 * active text layer meets the selection (a selected pixel in its painted rect, not just in the
 * selection's bounds), and opens that text, by its index, in the Text tool with the editor open.
 * Two texts or none, no selection, or another kind of layer: no Edit.
 */
@RunWith(RobolectricTestRunner::class)
class SelectionBarEditTextTest {
    private val kit = ObjectTestKit(400, 300)

    @After
    fun close() = kit.close()

    private val left = TextItem("Left", spec = TextSpec(sizePx = 40f), cx = 100f, cy = 80f)
    private val right = TextItem("Right", spec = TextSpec(sizePx = 40f), cx = 300f, cy = 220f)

    /** A text layer holding [left] (index 0) and [right] (index 1), made active. */
    private fun textLayer(c: EditorController): Layer {
        val items = listOf(left, right)
        val layer = c.addLayerWithContent("Text", "Add text", textData = TextCodec.encodeAll(items)) { cv ->
            for (t in items) TextRenderer.drawItem(cv, t, TextRenderer.prepare(t), null)
        }!!
        assertSame(layer, c.activeLayer)
        return layer
    }

    /** A hard-edged selection of the rectangles [rects] (l, t, r, b each). */
    private fun select(c: EditorController, vararg rects: IntArray) {
        val bytes = ByteArray(kit.w * kit.h)
        for (r in rects) for (y in r[1] until r[3]) for (x in r[0] until r[2]) bytes[y * kit.w + x] = 0xFF.toByte()
        c.setSelection(Selection.fromBytes(bytes, kit.w, kit.h), recordUndo = false)
    }

    @Test
    fun aSelectionOverOneTextOpensThatText() {
        val c = kit.controller()
        val layer = textLayer(c)
        select(c, intArrayOf(230, 180, 370, 260))
        val t = SelectionBarEdit.target(c)
        assertEquals("the right text, by its index", SelectionBarEdit.Text(layer, 1), t)
        val steps = c.undoManager.undoCount
        assertTrue(SelectionBarEdit.open(c, t!!))
        kit.idle()
        assertEquals(ToolId.TEXT, c.activeToolId)
        val tool = c.tools.getValue(ToolId.TEXT) as TextTool
        assertSame("the text layer is edited", layer, tool.editingLayer)
        assertEquals("that text", 1, tool.openIndex)
        assertEquals("Right", tool.item?.text)
        assertTrue("with its editor open", tool.editorOpen)
        assertEquals("Edit records no step", steps, c.undoManager.undoCount)
    }

    @Test
    fun onlySelectedPixelsCountNotTheSelectionsBounds() {
        val c = kit.controller()
        val layer = textLayer(c)
        // Two rectangles: one over the left text, one in the far corner. Their bounds hold the
        // right text too, but no selected pixel of it.
        select(c, intArrayOf(40, 40, 160, 120), intArrayOf(380, 285, 395, 295))
        assertEquals(SelectionBarEdit.Text(layer, 0), SelectionBarEdit.target(c))
    }

    @Test
    fun bothTextsOrNoneMeansNoEdit() {
        val c = kit.controller()
        textLayer(c)
        select(c, intArrayOf(0, 0, 400, 300))
        assertNull("two texts: which one is unclear", SelectionBarEdit.target(c))
        select(c, intArrayOf(10, 200, 60, 290))
        assertNull("no text", SelectionBarEdit.target(c))
        c.setSelection(null, recordUndo = false)
        assertNull("no selection", SelectionBarEdit.target(c))
    }

    @Test
    fun otherLayerKindsHaveNoEdit() {
        val c = kit.controller()
        select(c, intArrayOf(0, 0, 400, 300))
        assertSame(c.vec, c.activeLayer)
        assertNull("a vector layer", SelectionBarEdit.target(c))
        c.selectLayer(c.doc.layers[0])
        assertNull("a picture layer", SelectionBarEdit.target(c))
    }
}
