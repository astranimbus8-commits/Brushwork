package com.brushwork.paint.tools.select

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Eyedropper as the painting tools' long-press color pick (controller.holdPicking), its cached
 * sampling and the preview square above the finger (real Skia).
 */
@RunWith(RobolectricTestRunner::class)
class EyedropperHoldPickRobolectricTest {
    private lateinit var c: EditorController
    private lateinit var layer: Layer

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", 400, 300)
        layer = Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(400, 300))
        // Left half blue, a green block on the right, the rest transparent.
        val cv = Canvas(layer.bitmap)
        cv.drawRect(0f, 0f, 200f, 300f, Paint().apply { color = BLUE })
        cv.drawRect(260f, 100f, 360f, 200f, Paint().apply { color = GREEN })
        doc.layers += layer
        c = EditorController(app, doc, CoroutineScope(Dispatchers.Unconfined), settings)
        c.viewTransform.set(Matrix())
        c.color = RED
    }

    private fun eyedropper() = c.tools.getValue(ToolId.EYEDROPPER) as EyedropperTool

    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    @Test
    fun longPressWithTheBrushPicksAndKeepsTheBrush() {
        c.selectTool(ToolId.BRUSH)
        val before = pixels(layer.bitmap)
        val undo0 = c.undoManager.undoCount
        c.pointerDown(ToolPoint(100f, 150f))
        assertTrue("long press turned into color picking", c.pointerLongPress(ToolPoint(100f, 150f)))
        assertTrue(c.holdPicking)
        // The finger slides a little, then onto the green block.
        c.pointerMove(ToolPoint(103f, 151f))
        c.pointerMove(ToolPoint(230f, 150f))
        c.pointerMove(ToolPoint(300f, 150f))
        c.pointerUp(ToolPoint(300f, 150f))
        assertFalse(c.holdPicking)
        assertEquals(GREEN, c.color)
        assertEquals("the brush stays", ToolId.BRUSH, c.activeToolId)
        assertEquals("no undo step", undo0, c.undoManager.undoCount)
        assertArrayEquals("no paint", before, pixels(layer.bitmap))
        assertNull(c.renderOverride)
        assertFalse(c.isInteracting)
    }

    @Test
    fun holdOnAnEmptySpotKeepsTheColorQuietly() {
        c.selectTool(ToolId.BRUSH)
        c.pointerDown(ToolPoint(230f, 20f))
        assertTrue(c.pointerLongPress(ToolPoint(230f, 20f)))
        c.pointerUp(ToolPoint(230f, 20f))
        assertEquals(RED, c.color)
        assertNull("no message for a held brush", c.message)
        assertEquals(ToolId.BRUSH, c.activeToolId)
    }

    @Test
    fun theEyedropperToolStillReturnsToTheBrush() {
        c.selectTool(ToolId.EYEDROPPER)
        assertTrue(eyedropper().settings.returnToBrush)
        c.pointerDown(ToolPoint(50f, 50f))
        c.pointerUp(ToolPoint(50f, 50f))
        assertEquals(BLUE, c.color)
        assertEquals(ToolId.BRUSH, c.activeToolId)
        // Transparent: a message, the color stays.
        c.selectTool(ToolId.EYEDROPPER)
        c.pointerDown(ToolPoint(230f, 20f))
        c.pointerUp(ToolPoint(230f, 20f))
        assertEquals(BLUE, c.color)
        assertTrue(c.message!!.startsWith("Nothing to pick here"))
    }

    @Test
    fun cachedSamplingMatchesTheCanvasAcrossALongDrag() {
        val tool = eyedropper()
        tool.settings = tool.settings.copy(sampleSize = 3)
        // Reference values: sample() outside a gesture always renders fresh.
        val xs = (0 until 400 step 7).map { it + 0.5f }
        val expected = xs.map { tool.sample(it, 150f) }
        c.selectTool(ToolId.EYEDROPPER)
        tool.settings = tool.settings.copy(returnToBrush = false)
        c.pointerDown(ToolPoint(xs[0], 150f))
        for ((i, x) in xs.withIndex()) {
            c.pointerMove(ToolPoint(x, 150f))
            assertEquals("x=$x", expected[i], tool.sample(x, 150f))
        }
        c.pointerUp(ToolPoint(xs.last(), 150f))
        // The document changes between gestures: the next one sees it.
        Canvas(layer.bitmap).drawRect(0f, 0f, 400f, 300f, Paint().apply { color = RED })
        c.pointerDown(ToolPoint(10f, 10f))
        c.pointerUp(ToolPoint(10f, 10f))
        assertEquals(RED, c.color)
    }

    @Test
    fun layerSourceReadsTheActiveLayer() {
        val tool = eyedropper()
        tool.settings = tool.settings.copy(source = SampleSource.LAYER, returnToBrush = false)
        c.selectTool(ToolId.EYEDROPPER)
        c.pointerDown(ToolPoint(300f, 150f))
        c.pointerUp(ToolPoint(300f, 150f))
        assertEquals(GREEN, c.color)
    }

    @Test
    fun previewSquareSitsAboveTheFingerOrBesideIt() {
        val d = 2f
        val r = RectF()
        EyedropperTool.placePreview(500f, 900f, 1080f, 2000f, d, r)
        assertEquals(500f, r.centerX(), 1e-3f)
        assertEquals(900f - EyedropperTool.PREVIEW_OFFSET_DP * d, r.centerY(), 1e-3f)
        assertEquals(EyedropperTool.PREVIEW_SIZE_DP * d, r.width(), 1e-3f)
        // Near the top: beside the finger (towards the middle), never below it.
        EyedropperTool.placePreview(100f, 50f, 1080f, 2000f, d, r)
        assertTrue(r.centerX() > 100f + r.width() / 2f)
        assertTrue(r.top >= 0f && r.bottom <= 2000f)
        EyedropperTool.placePreview(1000f, 50f, 1080f, 2000f, d, r)
        assertTrue(r.centerX() < 1000f - r.width() / 2f)
        // At the side edges it stays inside the view.
        EyedropperTool.placePreview(5f, 900f, 1080f, 2000f, d, r)
        assertTrue(r.left > 0f)
        EyedropperTool.placePreview(1078f, 900f, 1080f, 2000f, d, r)
        assertTrue(r.right < 1080f)
    }

    @Test
    fun previewSquareStaysBetweenTheBars() {
        val d = 2.75f // the user's phone
        val top = 130f * d // status bar + top bar + tool options
        val bottom = 2408f - 120f * d // slider bar + hotbar
        val area = RectF(0f, top, 1080f, bottom)
        val r = RectF()
        // Just below the top bar: there is no room above the finger, so it goes beside it.
        EyedropperTool.placePreview(700f, top + 40f * d, area, d, r)
        assertTrue("below the top bar", r.top >= top)
        assertTrue("beside the finger, towards the middle", r.right < 700f)
        // Lower down it sits above the finger as usual.
        EyedropperTool.placePreview(540f, 1200f, area, d, r)
        assertEquals(1200f - EyedropperTool.PREVIEW_OFFSET_DP * d, r.centerY(), 1e-3f)
        // A finger dragged under the top bar keeps the square in the free area.
        EyedropperTool.placePreview(100f, top - 30f, area, d, r)
        assertTrue(r.top >= top && r.left > 100f)
        // Next to the hotbar it never goes into it.
        EyedropperTool.placePreview(540f, bottom + 50f, area, d, r)
        assertTrue(r.bottom <= bottom)
    }

    @Test
    fun previewAvoidsTheChromeReportedByTheCanvas() {
        c.selectTool(ToolId.BRUSH)
        c.viewTransform.density = 1f
        eyedropper().setChromeInsets(0f, 120f, 0f, 20f)
        c.pointerDown(ToolPoint(300f, 150f))
        assertTrue(c.pointerLongPress(ToolPoint(300f, 150f)))
        val out = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        c.drawOverlays(Canvas(out), 0f)
        val box = RectF()
        EyedropperTool.placePreview(300f, 150f, RectF(0f, 120f, 400f, 280f), 1f, box)
        assertTrue(box.top >= 120f)
        assertEquals(GREEN, out.getPixel(box.centerX().toInt(), (box.top + box.height() / 4f).toInt()))
        assertEquals(RED, out.getPixel(box.centerX().toInt(), (box.bottom - box.height() / 4f).toInt()))
        c.pointerUp(ToolPoint(300f, 150f))
        assertEquals(GREEN, c.color)
    }

    @Test
    fun previewShowsTheNewColorOverTheCurrentOne() {
        c.selectTool(ToolId.BRUSH)
        c.viewTransform.density = 1f
        c.pointerDown(ToolPoint(300f, 180f))
        assertTrue(c.pointerLongPress(ToolPoint(300f, 180f)))
        val out = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        c.drawOverlays(Canvas(out), 0f)
        val box = RectF()
        EyedropperTool.placePreview(300f, 180f, 400f, 300f, 1f, box)
        assertEquals("new color on top", GREEN, out.getPixel(box.centerX().toInt(), (box.top + box.height() / 4f).toInt()))
        assertEquals("current color below", RED, out.getPixel(box.centerX().toInt(), (box.bottom - box.height() / 4f).toInt()))
        // Nothing picked yet (transparent) draws a checkerboard instead: no crash either way.
        c.pointerCancel()
        c.pointerDown(ToolPoint(230f, 20f))
        assertTrue(c.pointerLongPress(ToolPoint(230f, 20f)))
        c.drawOverlays(Canvas(out), 0f)
        c.pointerUp(ToolPoint(230f, 20f))
        assertFalse(c.holdPicking)
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
        const val BLUE = 0xFF0000FF.toInt()
        const val GREEN = 0xFF00FF00.toInt()
    }
}
