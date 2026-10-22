package com.brushwork.paint.v110.a2

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.ObjectColorEditor
import com.brushwork.paint.tools.ObjectColorFace
import com.brushwork.paint.tools.ObjectColorTarget
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextColorsDelegate
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.BLACK
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.BLUE
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.GREEN
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.RED
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.composite
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.frame
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.tap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 (R7 (e); design 4.4 A2 step 5): a color change of the text open in the Text tool is shown
 * on the canvas live: the text color and the outline color follow each preview of the Object
 * colors editor, for a new text (drawn over the canvas) and for a text opened again in its layer
 * (drawn in the layer); Cancel puts back the pixels the sheet opened with; ✓ keeps the previewed
 * colors in the text's ONE step (no step of their own), and the drawing color is never touched.
 * The start values end with the text's session.
 */
@RunWith(RobolectricTestRunner::class)
class TextColorLivePreviewTest {

    private val rig = A2ToolRig()

    @After
    fun release() = rig.release()

    private fun setup(): Pair<EditorController, TextTool> {
        val c = rig.controller(400, 300)
        c.viewTransform.set(Matrix())
        c.snapping.enabled = false
        c.selectTool(ToolId.TEXT)
        return c to (c.tools.getValue(ToolId.TEXT) as TextTool)
    }

    /** A new text "HH" at the canvas centre, black, 80 px, no outline; the editor closed, the text still open. */
    private fun newText(c: EditorController, tool: TextTool) {
        c.tap(200f, 150f)
        assertTrue("the editor opened for a new text", tool.editorOpen && tool.editingNew)
        tool.setText("HH")
        tool.updateSpec { it.copy(sizePx = 80f, color = BLACK, strokeWidthPx = 0f) }
        tool.confirmEditor()
        assertNotNull(tool.item)
        assertFalse(tool.editorOpen)
    }

    private fun editor(tool: TextTool): ObjectColorEditor = tool.objectColors ?: throw AssertionError("an open text has colors")

    /** What the tool draws over the canvas (a new text is previewed there). */
    private fun overlay(c: EditorController, tool: TextTool): Bitmap {
        val b = BitmapUtils.createLayerBitmap(c.doc.width, c.doc.height)
        tool.drawOverlay(Canvas(b), c.viewTransform)
        return b
    }

    private fun count(b: Bitmap, color: Int): Int {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return px.count { it == color }
    }

    @Test
    fun aNewTextShowsEachPreviewAndCancelPutsItBack() {
        val (c, tool) = setup()
        assertNull("no text open: no colors", tool.objectColors)
        newText(c, tool)
        assertTrue("drawn black", count(overlay(c, tool), BLACK) > 100)
        val ed = editor(tool)
        assertEquals(listOf(ObjectColorTarget.TEXT, ObjectColorTarget.OUTLINE), ed.targets)
        assertNull("a text has no paint mode", ed.paintMode)
        assertEquals(ObjectColorFace.Solid(BLACK), ed.face(ObjectColorTarget.TEXT))
        assertEquals("no outline", ObjectColorFace.None, ed.face(ObjectColorTarget.OUTLINE))

        ed.begin()
        ed.preview(ObjectColorTarget.TEXT, BLUE)
        var shown = overlay(c, tool)
        assertTrue("the text color preview is on the canvas", count(shown, BLUE) > 100)
        assertEquals(0, count(shown, BLACK))
        ed.preview(ObjectColorTarget.OUTLINE, GREEN)
        shown = overlay(c, tool)
        assertTrue("the outline preview is on the canvas", count(shown, GREEN) > 50)
        assertTrue(count(shown, BLUE) > 100)
        assertEquals("the drawing color stays", RED, c.color)
        ed.cancel()
        shown = overlay(c, tool)
        assertEquals("Cancel: the text color is back", 0, count(shown, BLUE))
        assertEquals("Cancel: no outline again", 0, count(shown, GREEN))
        assertTrue(count(shown, BLACK) > 100)
        assertEquals(0f, tool.item!!.spec.strokeWidthPx, 0f)

        // ✓ keeps the previewed colors in the text's one step.
        ed.begin()
        ed.preview(ObjectColorTarget.TEXT, BLUE)
        ed.preview(ObjectColorTarget.OUTLINE, GREEN)
        ed.commit()
        val steps = c.undoManager.undoCount
        tool.commit()
        frame()
        assertEquals("the text's one step", steps + 1, c.undoManager.undoCount)
        assertNull("no text open", tool.objectColors)
        val placed = c.composite()
        assertTrue("placed with the previewed text color", count(placed, BLUE) > 100)
        assertTrue("placed with the previewed outline", count(placed, GREEN) > 50)
        assertEquals("the drawing color never changed", RED, c.color)
        // Nothing open: the editor does nothing (C13).
        ed.preview(ObjectColorTarget.TEXT, BLACK)
        ed.cancel()
        c.undo()
        assertEquals("one undo takes the text back", 0, count(c.composite(), BLUE))
    }

    @Test
    fun anOpenedTextShowsThePreviewInItsLayer() {
        val (c, tool) = setup()
        newText(c, tool)
        assertTrue(tool.commitItem())
        val layer = c.activeLayer
        assertTrue(layer.isTextLayer)
        c.color = BLUE
        c.tap(200f, 150f)
        assertNotNull("the tap opens the text", tool.item)
        assertTrue("drawn in its layer", count(c.composite(), BLACK) > 100)
        val ed = editor(tool)
        ed.begin()
        ed.preview(ObjectColorTarget.TEXT, GREEN)
        assertTrue("the preview is in the layer", count(c.composite(), GREEN) > 100)
        ed.cancel()
        assertEquals("Cancel: back", 0, count(c.composite(), GREEN))
        assertTrue(count(c.composite(), BLACK) > 100)

        ed.begin()
        ed.preview(ObjectColorTarget.OUTLINE, RED)
        ed.preview(ObjectColorTarget.TEXT, GREEN)
        assertTrue(count(c.composite(), RED) > 50)
        ed.commit()
        val steps = c.undoManager.undoCount
        tool.commit()
        frame()
        assertEquals("the edit's one step", steps + 1, c.undoManager.undoCount)
        val stored = TextCodec.decodeAll(layer.textData).single().spec
        assertEquals(GREEN, stored.color)
        assertEquals(RED, stored.strokeColor)
        assertEquals("the default outline width", TextColorsDelegate.defaultOutlineWidth(80f), stored.strokeWidthPx, 1e-4f)
        assertEquals("the drawing color never changed", BLUE, c.color)
        c.undo()
        val undone = TextCodec.decodeAll(layer.textData).single().spec
        assertEquals("one undo takes the colors back", BLACK, undone.color)
        assertEquals(0f, undone.strokeWidthPx, 0f)

        // The start values end with the session: a Cancel never reaches the next text.
        c.tap(200f, 150f)
        assertNotNull(tool.item)
        ed.begin()
        ed.preview(ObjectColorTarget.TEXT, GREEN)
        tool.commit()
        frame()
        c.tap(200f, 150f)
        val reopened = tool.item!!.spec
        assertEquals(GREEN, reopened.color)
        ed.cancel()
        assertEquals("a Cancel after the session ended changes nothing", reopened, tool.item!!.spec)
    }
}
