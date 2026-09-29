package com.brushwork.paint.tools.frame

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FrameDividerRobolectricTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    /** 400 x 300 page, 20 px margins, 4 px border, gutters 20 (rows) / 10 (columns). */
    private fun setup(): Triple<EditorController, FrameDividerTool, Layer> {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val doc = Document("t", "t", 400, 300)
        doc.layers += Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(400, 300))
        val c = EditorController(ctx, doc, CoroutineScope(Dispatchers.Unconfined), AppSettings(ctx))
        c.selectTool(ToolId.FRAME_DIVIDER)
        val tool = c.tools.getValue(ToolId.FRAME_DIVIDER) as FrameDividerTool
        assertEquals(FrameDividerTool.Status.NONE, tool.status())
        tool.settings = tool.settings.withUniformMargin(20f).copy(borderWidth = 4f, borderColor = black, gutterH = 20f, gutterV = 10f, fillOutside = true, rows = 1, cols = 1)
        assertTrue(tool.createFrameLayer())
        return Triple(c, tool, c.doc.layers[1])
    }

    private fun drag(c: EditorController, x0: Float, y0: Float, x1: Float, y1: Float) {
        c.pointerDown(ToolPoint(x0, y0))
        c.pointerMove(ToolPoint((x0 + x1) / 2f, (y0 + y1) / 2f))
        c.pointerUp(ToolPoint(x1, y1))
    }

    @Test
    fun newFrameLayerHasMarginsBorderAndClearPanel() {
        val (c, tool, layer) = setup()
        assertEquals(2, c.doc.layers.size)
        assertEquals("Frame", layer.name)
        assertEquals(FrameDividerTool.Status.READY, tool.status())
        val b = layer.bitmap
        assertEquals(white, b.getPixel(5, 5))       // margin
        assertEquals(white, b.getPixel(390, 290))
        assertEquals(black, b.getPixel(21, 150))    // border ring inside the panel edge
        assertEquals(black, b.getPixel(378, 150))
        assertEquals(black, b.getPixel(200, 22))
        assertEquals(0, b.getPixel(25, 150))        // panel interior is transparent
        assertEquals(0, b.getPixel(200, 150))
        c.undo()
        assertEquals(1, c.doc.layers.size)
        assertEquals(FrameDividerTool.Status.NONE, tool.status())
        c.redo()
        assertEquals(FrameDividerTool.Status.READY, tool.status())
    }

    @Test
    fun verticalCutSplitsWithColumnGutterAndUndoRestoresModel() {
        val (c, tool, layer) = setup()
        // Slightly slanted (0.6°) drag: snaps to exactly vertical at x = 200.
        drag(c, 200f, 10f, 203f, 290f)
        assertEquals(2, tool.model!!.panels.size)
        val b = layer.bitmap
        assertEquals(white, b.getPixel(200, 150))   // 10 px gutter: 195..205
        assertEquals(white, b.getPixel(196, 150))
        assertEquals(black, b.getPixel(193, 150))   // left panel border 191..195
        assertEquals(0, b.getPixel(189, 150))
        assertEquals(black, b.getPixel(206, 150))   // right panel border 205..209
        assertEquals(0, b.getPixel(211, 150))
        assertEquals(black, b.getPixel(21, 150))    // untouched parts unchanged
        assertEquals(FrameDividerTool.Status.READY, tool.status())

        c.undo()
        assertEquals(1, tool.model!!.panels.size)
        assertEquals(0, b.getPixel(200, 150))
        assertEquals(FrameDividerTool.Status.READY, tool.status())
        c.redo()
        assertEquals(2, tool.model!!.panels.size)
        assertEquals(white, layer.bitmap.getPixel(200, 150))
        assertEquals(FrameDividerTool.Status.READY, tool.status())
    }

    @Test
    fun horizontalCutAcrossTwoPanelsUsesRowGutter() {
        val (c, tool, layer) = setup()
        drag(c, 200f, 10f, 200f, 290f)
        drag(c, 5f, 150f, 395f, 152f) // crosses both panels, snaps horizontal
        assertEquals(4, tool.model!!.panels.size)
        val b = layer.bitmap
        assertEquals(white, b.getPixel(100, 150))  // 20 px row gutter: 140..160
        assertEquals(white, b.getPixel(100, 141))
        assertEquals(black, b.getPixel(100, 138))  // top panel bottom border 136..140
        assertEquals(black, b.getPixel(300, 161))  // bottom-right panel top border 160..164
        assertEquals(0, b.getPixel(300, 170))
    }

    @Test
    fun angledCutProducesSlantedPanels() {
        val (c, tool, _) = setup()
        drag(c, 10f, 100f, 390f, 200f)
        val panels = tool.model!!.panels
        assertEquals(2, panels.size)
        // Not snapped: the cut edge is slanted, so pieces are not rectangles.
        assertTrue(panels.any { it.points.size >= 4 && it.points.map { p -> p.y }.distinct().size > 2 })
    }

    @Test
    fun cancelAndShortDragsLeaveNoTrace() {
        val (c, tool, layer) = setup()
        val edits = c.editCount
        val version = layer.contentVersion
        c.pointerDown(ToolPoint(200f, 10f))
        c.pointerMove(ToolPoint(200f, 290f))
        c.pointerCancel()
        c.pointerDown(ToolPoint(200f, 150f))
        c.pointerUp(ToolPoint(203f, 152f)) // a tap
        assertEquals(edits, c.editCount)
        assertEquals(version, layer.contentVersion)
        assertEquals(1, tool.model!!.panels.size)
    }

    @Test
    fun externalEditMakesFrameOutOfSyncUntilRedrawn() {
        val (c, tool, layer) = setup()
        drag(c, 200f, 10f, 200f, 290f)
        c.editWholeLayer(layer, "Scribble", EditTarget.CONTENT) { it.eraseColor(0) }
        assertEquals(FrameDividerTool.Status.OUT_OF_SYNC, tool.status())
        drag(c, 5f, 150f, 395f, 150f) // refused while out of sync
        assertEquals(2, tool.model!!.panels.size)
        assertEquals(0, layer.bitmap.getPixel(200, 150))
        assertTrue(tool.redraw())
        assertEquals(FrameDividerTool.Status.READY, tool.status())
        assertEquals(white, layer.bitmap.getPixel(200, 150))
        // Undoing the redraw brings back the scribbled pixels: out of sync again, not "ready".
        c.undo()
        assertEquals(FrameDividerTool.Status.OUT_OF_SYNC, tool.status())
        assertEquals(0, layer.bitmap.getPixel(200, 150))
        c.redo()
        assertEquals(FrameDividerTool.Status.READY, tool.status())
    }

    @Test
    fun gridPresetAndPanelRemoval() {
        val (c, tool, layer) = setup()
        assertTrue(tool.applyGrid(2, 3))
        val panels = tool.model!!.panels
        assertEquals(6, panels.size)
        assertEquals(white, layer.bitmap.getPixel(200, 150)) // row gutter centre
        tool.removeMode = true
        c.pointerDown(ToolPoint(60f, 60f))
        c.pointerUp(ToolPoint(60f, 60f))
        assertEquals(5, tool.model!!.panels.size)
        assertEquals(white, layer.bitmap.getPixel(60, 60))   // removed panel becomes outside fill
        c.undo()
        assertEquals(6, tool.model!!.panels.size)
        assertEquals(0, layer.bitmap.getPixel(60, 60))
    }

    @Test
    fun onlyTilesThatChangeAreRewrittenAndSnapshotted() {
        val w = 1200; val h = 1000
        val style = FrameStyle(6f, black, fillOutside = true)
        val area = FrameRect(40f, 40f, 1160f, 960f)
        val one = FrameModel(area, listOf(Panel(area.toPolygon())), style)
        val split = one.copy(panels = FrameMath.divide(one.panels, com.brushwork.paint.core.Vec2(600f, 0f), com.brushwork.paint.core.Vec2(600f, 1000f), 30f, 12f, 8f)!!)
        val layer = BitmapUtils.createLayerBitmap(w, h)
        FrameRenderer.render(android.graphics.Canvas(layer), one)
        val touched = mutableListOf<android.graphics.Rect>()
        val changed = FrameRenderer.renderChangedTiles(layer, split, android.graphics.Rect(0, 0, w, h), com.brushwork.paint.model.ColorMode.RGB) { touched += it }
        // A vertical cut at x = 600 only changes the tile column(s) around it (512..768).
        assertTrue(touched.isNotEmpty())
        assertTrue(touched.all { it.left == 512 })
        assertEquals(android.graphics.Rect(512, 0, 768, 1000), changed)
        // The result equals a direct full render of the new model.
        val expected = BitmapUtils.createLayerBitmap(w, h)
        FrameRenderer.render(android.graphics.Canvas(expected), split)
        assertTrue(expected.sameAs(layer))
    }

    @Test
    fun undoingAnExternalEditMakesTheFrameUsableAgain() {
        val (c, tool, layer) = setup()
        drag(c, 200f, 10f, 200f, 290f)
        c.editWholeLayer(layer, "Scribble", EditTarget.CONTENT) { it.eraseColor(0xFFFF0000.toInt()) }
        assertEquals(FrameDividerTool.Status.OUT_OF_SYNC, tool.status())
        assertFalse(tool.refreshSync())                 // the pixels really differ
        assertEquals(FrameDividerTool.Status.OUT_OF_SYNC, tool.status())
        c.undo()                                        // scribble undone: pixels match the model again
        assertEquals(FrameDividerTool.Status.OUT_OF_SYNC, tool.status())
        assertTrue(tool.refreshSync())
        assertEquals(FrameDividerTool.Status.READY, tool.status())
        val edits = c.editCount
        drag(c, 5f, 150f, 395f, 150f)                   // cutting works again without a redraw
        assertEquals(4, tool.model!!.panels.size)
        assertEquals(edits + 1, c.editCount)
    }

    @Test
    fun frameInGrayscaleDocumentIsGrayAndStaysInSync() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val doc = Document("t", "t", 300, 270) // not a multiple of the 256 px tiles
        doc.colorMode = ColorMode.GRAYSCALE
        doc.layers += Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(300, 270))
        val c = EditorController(ctx, doc, CoroutineScope(Dispatchers.Unconfined), AppSettings(ctx))
        c.selectTool(ToolId.FRAME_DIVIDER)
        val tool = c.tools.getValue(ToolId.FRAME_DIVIDER) as FrameDividerTool
        tool.settings = tool.settings.withUniformMargin(20f).copy(borderWidth = 6f, borderColor = 0xFF2080F0.toInt(), gutterH = 20f, gutterV = 10f)
        assertTrue(tool.createFrameLayer())
        val layer = c.doc.layers[1]
        val border = layer.bitmap.getPixel(22, 135)
        assertEquals(255, border ushr 24)
        val r = (border shr 16) and 0xFF; val g = (border shr 8) and 0xFF; val b = border and 0xFF
        assertTrue("border $r,$g,$b must be gray", r == g && g == b)
        // The tile renderer applies the same conversion: a mask change keeps the frame usable.
        c.addMask(layer, fromSelection = false)
        assertEquals(FrameDividerTool.Status.OUT_OF_SYNC, tool.status())
        assertTrue(tool.refreshSync())
        val version = layer.contentVersion
        drag(c, 150f, 5f, 150f, 265f)
        assertEquals(2, tool.model!!.panels.size)
        assertTrue(layer.contentVersion > version)
        assertEquals(FrameDividerTool.Status.READY, tool.status())
        val px = IntArray(300 * 270)
        layer.bitmap.getPixels(px, 0, 300, 0, 0, 300, 270)
        assertTrue(px.all { ((it shr 16) and 0xFF) == ((it shr 8) and 0xFF) && ((it shr 8) and 0xFF) == (it and 0xFF) })
    }

    @Test
    fun matchesDetectsSinglePixelDifferences() {
        val style = FrameStyle(3f, black, fillOutside = true)
        val area = FrameRect(10f, 10f, 290f, 530f)
        val model = FrameModel(area, FrameMath.grid(area, 3, 2, 12f, 8f)!!, style)
        val bmp = BitmapUtils.createLayerBitmap(300, 540)
        FrameRenderer.render(android.graphics.Canvas(bmp), model)
        assertTrue(FrameRenderer.matches(bmp, model, com.brushwork.paint.model.ColorMode.RGB))
        bmp.setPixel(299, 539, 0xFF000001.toInt()) // bottom-right edge tile
        assertFalse(FrameRenderer.matches(bmp, model, com.brushwork.paint.model.ColorMode.RGB))
        // Rewriting only the changed tiles restores the exact rendering.
        val touched = mutableListOf<android.graphics.Rect>()
        FrameRenderer.renderChangedTiles(bmp, model, android.graphics.Rect(0, 0, 300, 540), com.brushwork.paint.model.ColorMode.RGB) { touched += it }
        assertEquals(listOf(android.graphics.Rect(256, 512, 300, 540)), touched)
        assertTrue(FrameRenderer.matches(bmp, model, com.brushwork.paint.model.ColorMode.RGB))
    }

    @Test
    fun tapsWithoutAFrameDoNothing() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val doc = Document("t", "t", 200, 200)
        doc.layers += Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(200, 200))
        val c = EditorController(ctx, doc, CoroutineScope(Dispatchers.Unconfined), AppSettings(ctx))
        c.selectTool(ToolId.FRAME_DIVIDER)
        c.message = null
        c.pointerDown(ToolPoint(100f, 100f))
        c.pointerUp(ToolPoint(101f, 100f))
        assertEquals(null, c.message)                   // a tap is not nagged about
        c.pointerDown(ToolPoint(100f, 10f))
        c.pointerUp(ToolPoint(100f, 190f))
        assertTrue(c.message != null)                   // a cut explains that a frame layer is needed
        assertEquals(1, c.doc.layers.size)
    }

    @Test
    fun lockedOrAlphaLockedFrameLayerIsNotChanged() {
        val (c, tool, layer) = setup()
        layer.locked = true
        drag(c, 200f, 10f, 200f, 290f)
        assertEquals(1, tool.model!!.panels.size)
        layer.locked = false
        layer.alphaLocked = true
        drag(c, 200f, 10f, 200f, 290f)
        assertEquals(1, tool.model!!.panels.size)
        layer.alphaLocked = false
        layer.editingMask = true // no mask: still edits the content
        drag(c, 200f, 10f, 200f, 290f)
        assertEquals(2, tool.model!!.panels.size)
        assertFalse(layer.bitmap.getPixel(200, 150) == 0)
    }
}
