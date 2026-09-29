package com.brushwork.paint.tools.frame

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.EditTarget
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
