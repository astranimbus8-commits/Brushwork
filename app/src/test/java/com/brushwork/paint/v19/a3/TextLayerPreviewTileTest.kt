package com.brushwork.paint.v19.a3

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 A3 review (R14 c, T606): while a text of a layer of several is open, the layer's in-place
 * preview is redrawn tile by tile, every frame of a drag. Each tile draws the open text and only
 * those other texts whose ink meets it (the rule ✓ redraws by), so a tile away from them draws
 * as much as the open text alone; and the tiles together give exactly the pixels of the whole
 * layer drawn at once (no text is cut at a tile's edge).
 */
@RunWith(RobolectricTestRunner::class)
class TextLayerPreviewTileTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val black = 0xFF000000.toInt()

    /** A canvas that counts its saves: [TextRenderer.drawItem] saves for every text it draws. */
    private class CountingCanvas(b: Bitmap) : Canvas(b) {
        var saves = 0
        override fun save(): Int {
            saves++
            return super.save()
        }
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun newController(w: Int, h: Int): Pair<EditorController, TextTool> {
        val d = Document("t", "t", w, h).also { it.layers += Layer(it.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(w, h)) }
        val settings = AppSettings(context)
        settings.prefs.edit().clear().commit()
        val c = EditorController(context, d, CoroutineScope(Dispatchers.Unconfined), settings)
        c.viewTransform.set(Matrix())
        c.snapping.enabled = false
        c.selectTool(ToolId.TEXT)
        return c to (c.tools.getValue(ToolId.TEXT) as TextTool)
    }

    private fun tap(c: EditorController, x: Float, y: Float) {
        c.pointerDown(ToolPoint(x, y))
        c.pointerUp(ToolPoint(x, y))
    }

    private fun place(c: EditorController, tool: TextTool, text: String, x: Float, y: Float) {
        tap(c, x, y)
        assertTrue("the editor opened for a new text", tool.editorOpen && tool.editingNew)
        tool.setText(text)
        tool.updateSpec { it.copy(sizePx = 28f, color = black) }
        tool.confirmEditor()
        assertTrue(tool.commitItem())
    }

    @Test
    fun aTileAwayFromTheOtherTextsDrawsOnlyTheOpenTextAndTheTilesAddUpToTheLayer() {
        val w = 400
        val h = 300
        val (c, tool) = newController(w, h)
        place(c, tool, "Alpha", 60f, 40f)
        place(c, tool, "Beta", 330f, 260f)
        place(c, tool, "Gamma", 200f, 150f)
        val layer = c.activeLayer
        assertEquals(listOf("Alpha", "Beta", "Gamma"), TextCodec.decodeAll(layer.textData).map { it.text })

        // Open the middle text of the picture (Beta): Alpha under it, Gamma over it.
        tap(c, 330f, 260f)
        assertEquals("Beta", tool.item?.text)
        val preview = c.renderOverride
        assertNotNull("the layer previews in place", preview)
        assertSame(layer, preview!!.layer)

        // The whole layer at once, then tile by tile.
        val whole = BitmapUtils.createLayerBitmap(w, h)
        assertTrue(preview.drawContent(Canvas(whole)))
        val tiled = BitmapUtils.createLayerBitmap(w, h)
        val tile = 64
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val cv = Canvas(tiled)
                cv.clipRect(Rect(x, y, minOf(w, x + tile), minOf(h, y + tile)))
                preview.drawContent(cv)
                x += tile
            }
            y += tile
        }
        assertArrayEquals("the tiles add up to the layer drawn at once", pixels(whole), pixels(tiled))

        // A tile that meets no text but the open one's neighbourhood: as much as the open text alone.
        val open = tool.item!!
        val alone = CountingCanvas(BitmapUtils.createLayerBitmap(w, h))
        TextRenderer.drawItem(alone, open, TextRenderer.prepare(open), null)
        assertTrue("drawing a text saves the canvas", alone.saves > 0)
        val far = CountingCanvas(BitmapUtils.createLayerBitmap(w, h))
        far.clipRect(Rect(0, 200, 64, 264))
        preview.drawContent(far)
        assertEquals("a tile away from Alpha and Gamma draws only the open text", alone.saves, far.saves)
        // A tile on Alpha draws Alpha too.
        val onAlpha = CountingCanvas(BitmapUtils.createLayerBitmap(w, h))
        onAlpha.clipRect(Rect(40, 20, 104, 84))
        preview.drawContent(onAlpha)
        assertEquals("a tile on Alpha draws Alpha and the open text", 2 * alone.saves, onAlpha.saves)
        c.dispose()
    }
}
