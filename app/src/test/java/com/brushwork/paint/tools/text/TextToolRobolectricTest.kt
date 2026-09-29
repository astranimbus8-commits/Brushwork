package com.brushwork.paint.tools.text

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Real Skia text rendering (Robolectric NATIVE graphics) through the controller. */
@RunWith(RobolectricTestRunner::class)
class TextToolRobolectricTest {

    private fun newController(w: Int = 240, h: Int = 160): Pair<EditorController, TextTool> {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val doc = Document("t", "t", w, h)
        doc.layers += Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(w, h))
        val c = EditorController(ctx, doc, CoroutineScope(Dispatchers.Unconfined), AppSettings(ctx))
        c.selectTool(ToolId.TEXT)
        return c to (c.tools.getValue(ToolId.TEXT) as TextTool)
    }

    private fun tap(c: EditorController, x: Float, y: Float) {
        c.pointerDown(ToolPoint(x, y))
        c.pointerUp(ToolPoint(x, y))
    }

    /** Bounds of pixels with alpha > 0, or an empty rect. */
    private fun inkBounds(b: Bitmap): Rect {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        val r = Rect()
        for (y in 0 until b.height) for (x in 0 until b.width) {
            if ((px[y * b.width + x] ushr 24) != 0) {
                if (r.isEmpty) r.set(x, y, x + 1, y + 1) else r.union(x, y)
            }
        }
        return r
    }

    private fun opaquePixels(b: Bitmap): List<Int> {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return px.filter { (it ushr 24) == 255 }
    }

    @Test
    fun committedTextGoesToNewLayerAndUndoRemovesIt() {
        val (c, tool) = newController()
        tap(c, 120f, 80f)
        assertTrue(tool.editorOpen)
        assertTrue(tool.editingNew)
        tool.setText("Hi")
        tool.setSizePx(48f)
        tool.updateSpec { it.copy(color = 0xFFFF0000.toInt()) }
        tool.confirmEditor()
        assertFalse(tool.editorOpen)
        assertTrue(tool.hasPendingWork)

        tool.commit()
        assertFalse(tool.hasPendingWork)
        assertEquals(2, c.doc.layers.size)
        val layer = c.doc.layers[1]
        assertEquals("Text: Hi", layer.name)
        assertEquals(1, c.doc.activeLayerIndex)
        val ink = inkBounds(layer.bitmap)
        assertFalse("text pixels must appear in the new layer", ink.isEmpty)
        assertTrue("ink $ink should surround the tap point", ink.contains(120, 80) || Rect.intersects(ink, Rect(100, 60, 140, 100)))
        val solid = opaquePixels(layer.bitmap)
        assertTrue(solid.size > 50)
        assertTrue(solid.all { it == 0xFFFF0000.toInt() })
        assertTrue(inkBounds(c.doc.layers[0].bitmap).isEmpty) // the original layer is untouched

        c.undo()
        assertEquals(1, c.doc.layers.size)
        c.redo()
        assertEquals(2, c.doc.layers.size)
        assertTrue(c.doc.layers[1] === layer)
    }

    @Test
    fun outlineIsDrawnBehindTheFill() {
        val (c, tool) = newController()
        tool.startTextAt(120f, 80f)
        tool.setText("O")
        tool.updateSpec { it.copy(sizePx = 80f, color = 0xFF000000.toInt(), strokeWidthPx = 5f, strokeColor = 0xFF00FF00.toInt()) }
        tool.confirmEditor()
        assertTrue(tool.commitItem())
        val solid = opaquePixels(c.doc.layers[1].bitmap)
        assertTrue(solid.any { it == 0xFF00FF00.toInt() }) // outline visible around the glyph
        assertTrue(solid.any { it == 0xFF000000.toInt() }) // fill on top of it
    }

    @Test
    fun commitIsClippedToTheSelection() {
        val (c, tool) = newController()
        val w = c.doc.width; val h = c.doc.height
        // Select the left half only.
        c.setSelection(Selection.fromBytes(ByteArray(w * h) { if (it % w < w / 2) -1 else 0 }, w, h))
        tool.startTextAt(120f, 80f)
        tool.setText("WWWW")
        tool.setSizePx(50f)
        tool.confirmEditor()
        assertTrue(tool.commitItem())
        val ink = inkBounds(c.doc.layers[1].bitmap)
        assertFalse(ink.isEmpty)
        assertTrue("ink $ink must stay inside the selection", ink.right <= w / 2)
    }

    @Test
    fun verticalTextRunsTopToBottom() {
        val (c, tool) = newController(200, 300)
        tool.startTextAt(100f, 150f)
        tool.updateSpec { it.copy(vertical = true, sizePx = 40f) }
        tool.setText("漢字です")
        tool.confirmEditor()
        val block = tool.blockFor(tool.item!!)
        assertEquals(40f, block.width, 0.01f)
        assertEquals(160f, block.height, 0.01f)
        assertTrue(tool.commitItem())
        val ink = inkBounds(c.doc.layers[1].bitmap)
        assertTrue("vertical ink $ink should be tall and narrow", ink.height() > 2 * ink.width())
    }

    @Test
    fun grayscaleDocumentGetsGrayText() {
        val (c, tool) = newController()
        c.doc.colorMode = ColorMode.GRAYSCALE
        tool.startTextAt(120f, 80f)
        tool.setText("Gray")
        tool.updateSpec { it.copy(sizePx = 40f, color = 0xFF2080F0.toInt()) }
        tool.confirmEditor()
        assertTrue(tool.commitItem())
        val px = IntArray(c.doc.width * c.doc.height)
        c.doc.layers[1].bitmap.getPixels(px, 0, c.doc.width, 0, 0, c.doc.width, c.doc.height)
        val colored = px.filter { (it ushr 24) != 0 }
        assertTrue(colored.isNotEmpty())
        assertTrue(colored.all { ((it shr 16) and 0xFF) == ((it shr 8) and 0xFF) && ((it shr 8) and 0xFF) == (it and 0xFF) })
    }

    @Test
    fun editorCancelAndEmptyTextLeaveNothing() {
        val (c, tool) = newController()
        tap(c, 50f, 50f)
        tool.setText("abc")
        tool.cancelEditor()
        assertNull(tool.item)       // cancelling a new text removes it
        tap(c, 50f, 50f)
        tool.setText("   ")
        tool.confirmEditor()
        assertNull(tool.item)       // empty text -> nothing
        assertEquals(1, c.doc.layers.size)
        assertFalse(c.canUndo)
    }

    @Test
    fun reopenedEditorCancelRestoresPreviousState() {
        val (_, tool) = newController()
        tool.startTextAt(60f, 60f)
        tool.setText("keep")
        tool.confirmEditor()
        tool.openEditor()
        tool.setText("changed")
        tool.updateSpec { it.copy(bold = true) }
        tool.cancelEditor()
        assertEquals("keep", tool.item!!.text)
        assertFalse(tool.item!!.spec.bold)
    }

    @Test
    fun dragMovesAndCancelLeavesNoTrace() {
        val (c, tool) = newController()
        tool.startTextAt(100f, 80f)
        tool.setText("Move")
        tool.confirmEditor()
        // Tiny text: grabbing its center must move it even though the corner handle is near.
        assertTrue(tool.item!!.spec.sizePx < 10f)
        c.pointerDown(ToolPoint(100f, 80f))
        c.pointerMove(ToolPoint(130f, 90f))
        c.pointerUp(ToolPoint(130f, 90f))
        assertEquals(130f, tool.item!!.cx, 0.01f)
        assertEquals(90f, tool.item!!.cy, 0.01f)
        // A second finger cancels the drag: back to where it was.
        c.pointerDown(ToolPoint(130f, 90f))
        c.pointerMove(ToolPoint(10f, 10f))
        c.pointerCancel()
        assertEquals(130f, tool.item!!.cx, 0.01f)
        assertEquals(90f, tool.item!!.cy, 0.01f)
        assertEquals(1, c.doc.layers.size)
        assertFalse(c.canUndo)
        // Undo while text is pending discards it.
        c.undo()
        assertNull(tool.item)
    }

    @Test
    fun cornerHandleScalesAndTopHandleRotates() {
        val (c, tool) = newController(400, 400)
        tool.startTextAt(200f, 200f)
        tool.setText("Size")
        tool.updateSpec { it.copy(sizePx = 40f, strokeWidthPx = 2f) }
        tool.confirmEditor()
        val block = tool.blockFor(tool.item!!)
        val pad = 6f // BOX_PAD_DP at density 1, zoom 1
        val corner = tool.item!!.localToDoc(block.width + pad, block.height + pad, block.width, block.height)
        c.pointerDown(ToolPoint(corner.x, corner.y))
        val far = corner + (corner - com.brushwork.paint.core.Vec2(200f, 200f))
        c.pointerMove(ToolPoint(far.x, far.y))
        c.pointerUp(ToolPoint(far.x, far.y))
        assertEquals(80f, tool.item!!.spec.sizePx, 0.5f)
        assertEquals(4f, tool.item!!.spec.strokeWidthPx, 0.05f)

        val b2 = tool.blockFor(tool.item!!)
        val topMid = tool.item!!.localToDoc(b2.width / 2f, -pad, b2.width, b2.height)
        val handle = com.brushwork.paint.core.Vec2(topMid.x, topMid.y - 30f)
        c.pointerDown(ToolPoint(handle.x, handle.y))
        // Drag the handle to the right of the center: +90°.
        c.pointerMove(ToolPoint(200f + 200f, 200f))
        c.pointerUp(ToolPoint(400f, 200f))
        assertEquals(90f, tool.item!!.rotationDeg, 0.01f)
    }

    @Test
    fun textOutsideTheCanvasIsNotCommitted() {
        val (c, tool) = newController()
        tool.startTextAt(10f, 10f)
        tool.setText("x")
        tool.setSizePx(10f)
        tool.confirmEditor()
        tool.setCenter(-500f, -500f)
        assertFalse(tool.commitItem())
        assertNotNull(tool.item)
        assertEquals(1, c.doc.layers.size)
        // Switching tools discards it instead of keeping invisible pending work.
        c.selectTool(ToolId.BRUSH)
        assertNull(tool.item)
    }

    @Test
    fun dragWithoutTextCreatesNothing() {
        val (c, tool) = newController()
        c.pointerDown(ToolPoint(20f, 20f))
        c.pointerMove(ToolPoint(120f, 100f))
        c.pointerUp(ToolPoint(120f, 100f))
        assertNull(tool.item)
        assertFalse(tool.editorOpen)
    }

    @Test
    fun noTextIsStartedAtTheLayerLimit() {
        val (c, tool) = newController(40, 30)
        while (c.canAddLayer) c.doc.layers += Layer(c.doc.newLayerId(), "L${c.doc.layers.size}", BitmapUtils.createLayerBitmap(40, 30))
        c.message = null
        tap(c, 20f, 15f)
        assertNull(tool.item)
        assertFalse(tool.editorOpen)
        assertNotNull(c.message)
    }

    @Test
    fun monochromeCommitIsPureBlackAndWhite() {
        val (c, tool) = newController()
        c.doc.colorMode = ColorMode.MONOCHROME
        tool.startTextAt(120f, 80f)
        tool.setText("Mono")
        tool.updateSpec { it.copy(sizePx = 50f, color = 0xFF3050A0.toInt()) }
        tool.confirmEditor()
        assertTrue(tool.commitItem())
        val px = IntArray(c.doc.width * c.doc.height)
        c.doc.layers[1].bitmap.getPixels(px, 0, c.doc.width, 0, 0, c.doc.width, c.doc.height)
        assertTrue(px.any { it == 0xFF000000.toInt() })
        assertTrue(px.all { it == 0 || it == 0xFF000000.toInt() || it == 0xFFFFFFFF.toInt() })
    }

    @Test
    fun tapAwayCommitsAndStartsANewText() {
        val (c, tool) = newController()
        tool.startTextAt(60f, 40f)
        tool.setText("One")
        tool.confirmEditor()
        tap(c, 200f, 140f)
        assertEquals(2, c.doc.layers.size)
        assertTrue(tool.editorOpen)
        assertEquals("", tool.item!!.text)
        assertEquals(200f, tool.item!!.cx, 0.01f)
    }
}
