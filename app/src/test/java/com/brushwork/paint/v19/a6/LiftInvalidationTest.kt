package com.brushwork.paint.v19.a6

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.BrushTool
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.DisplayTiles
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 A6 step 1 (P1): a lift redraws only the tiles its stroke touched, not its bounding box,
 * and the canvas still shows exactly the committed document afterwards.
 *
 * The stroke is an L from the top-left to the bottom-right corner of a 512 px canvas: its
 * bounding box is the whole canvas, yet no dab reaches the bottom-left 256 px quadrant.
 */
@RunWith(RobolectricTestRunner::class)
class LiftInvalidationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun newController(fill: Int): EditorController {
        val doc = Document("t", "t", SIZE, SIZE)
        val layer = Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(SIZE, SIZE))
        layer.bitmap.eraseColor(fill)
        doc.layers += layer
        val c = EditorController(context, doc, CoroutineScope(Dispatchers.Unconfined), AppSettings(context))
        c.tools
        return c
    }

    /** Draws the L with [id], rendering the display before the lift; returns what the lift invalidated. */
    private fun EditorController.liftInvalidations(id: ToolId): List<Rect> {
        selectTool(id)
        val tool = tools.getValue(id) as BrushTool
        tool.onDown(ToolPoint(40f, 40f))
        for (i in 1..20) tool.onMove(ToolPoint(40f + 430f * i / 20, 40f))
        for (i in 1..20) tool.onMove(ToolPoint(470f, 40f + 430f * i / 20))
        tiles.update(compositor, null)
        val rects = ArrayList<Rect>()
        val prev = tiles.invalidationHook
        tiles.invalidationHook = { prev?.invoke(it); rects += Rect(it) }
        tool.onUp(ToolPoint(470f, 470f))
        tiles.invalidationHook = prev
        return rects
    }

    private fun DisplayTiles.pixels(): IntArray {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        draw(Canvas(bmp), null, smooth = false)
        return IntArray(SIZE * SIZE).also { bmp.getPixels(it, 0, SIZE, 0, 0, SIZE, SIZE) }
    }

    private fun check(c: EditorController, id: ToolId) {
        val before = c.doc.layers[0].bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val rects = c.liftInvalidations(id)
        assertTrue("the lift redraws something", rects.isNotEmpty())
        for (r in rects) assertTrue("$r reaches the untouched quadrant $UNTOUCHED", !Rect.intersects(r, UNTOUCHED))
        // Every pixel the commit changed is redrawn.
        val after = c.doc.layers[0].bitmap
        var changed = 0
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            if (before.getPixel(x, y) == after.getPixel(x, y)) continue
            changed++
            assertTrue("changed pixel ($x, $y) is redrawn", rects.any { it.contains(x, y) })
        }
        assertTrue("the stroke changed pixels", changed > 0)
        // The canvas shows exactly a full redraw of the committed document.
        c.tiles.update(c.compositor, null)
        val fresh = DisplayTiles(SIZE, SIZE).also { it.update(c.compositor, null) }
        assertArrayEquals(fresh.pixels(), c.tiles.pixels())
    }

    @Test
    fun brushLiftRedrawsOnlyItsTiles() {
        val c = newController(0)
        c.brush = BrushLibrary.byId("hardround")!!.copy(size = 20f, pressureSize = false)
        check(c, ToolId.BRUSH)
    }

    @Test
    fun taperedBrushLiftRedrawsOnlyItsTiles() {
        // G-pen: the end taper redraws the last dabs smaller at the lift.
        val c = newController(0)
        c.brush = BrushLibrary.byId("gpen")!!.copy(size = 24f)
        check(c, ToolId.BRUSH)
    }

    @Test
    fun rasterEraserLiftRedrawsOnlyItsTiles() {
        val c = newController(0xFF3366CC.toInt())
        c.eraser = c.eraser.copy(size = 20f, pressureSize = false)
        check(c, ToolId.ERASER)
    }

    private companion object {
        const val SIZE = 512
        val UNTOUCHED = Rect(0, 256, 256, SIZE)
    }
}
