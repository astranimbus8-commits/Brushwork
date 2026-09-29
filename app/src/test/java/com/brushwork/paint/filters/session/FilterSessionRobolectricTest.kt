package com.brushwork.paint.filters.session

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterCategory
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterMath
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterRecents
import com.brushwork.paint.filters.FilterSession
import com.brushwork.paint.filters.FilterValues
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.tools.ToolPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * End-to-end tests of [FilterSession] on real Skia (Robolectric NATIVE graphics): preview through
 * the render override, apply inside the selection, undo, cancel, compare, masks, alpha lock,
 * point parameters and failure handling. Filters are defined here (the registry may be empty).
 */
@RunWith(RobolectricTestRunner::class)
class FilterSessionRobolectricTest {

    /** Inverts RGB, blended by "amount" (0..100 %). */
    private class InvertFilter(live: Boolean = true) : Filter("test_invert", "Test invert", FilterCategory.ADJUST) {
        override val livePreview = live
        override val params = listOf(FilterParam.Slider("amount", "Amount", 0f, 100f, 100f, step = 1f, suffix = "%"))
        override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
            val t = values.float("amount") / 100f
            return FilterMath.mapPixels(src, ctx) { c -> ColorUtils.lerp(c, (c and 0xFF000000.toInt()) or (c.inv() and 0xFFFFFF), t) }
        }
    }

    /** Fills everything with opaque green (changes alpha of transparent pixels). */
    private class FillFilter : Filter("test_fill", "Test fill", FilterCategory.DRAW) {
        override val params = emptyList<FilterParam>()
        override val generatesContent = true
        override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext) = PixelBuffer.filled(src.width, src.height, GREEN)
    }

    /** Has a draggable point; output is a copy of the source. */
    private class PointFilter : Filter("test_point", "Test point", FilterCategory.DISTORT) {
        override val params = listOf(FilterParam.Point("center", "Center"), FilterParam.Point("focus", "Focus", 0.9f, 0.9f))
        override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext) = src.copy()
    }

    /** Fills with the "tint" color, which starts at the drawing color. */
    private class TintFilter : Filter("test_tint", "Test tint", FilterCategory.DRAW) {
        override val params = listOf(
            FilterParam.Color("tint", "Tint", 0xFF123456.toInt(), useDrawingColor = true),
            FilterParam.Color("other", "Other", 0xFF654321.toInt()),
        )
        override val generatesContent = true
        override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext) = PixelBuffer.filled(src.width, src.height, values.color("tint"))
    }

    /** Throws (an exception or out-of-memory) when [armed]. */
    private class FailingFilter(private val oom: Boolean) : Filter("test_fail", "Test fail", FilterCategory.STYLE) {
        @Volatile var armed = true
        override val params = emptyList<FilterParam>()
        override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
            if (armed) { if (oom) throw OutOfMemoryError("test") else throw IllegalStateException("boom") }
            return PixelBuffer.filled(src.width, src.height, GREEN)
        }
    }

    /** Blocks until cancelled when [armed] (a very slow filter). */
    private class BlockingFilter : Filter("test_block", "Test block", FilterCategory.BLUR) {
        @Volatile var armed = false
        override val params = emptyList<FilterParam>()
        override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
            val end = System.currentTimeMillis() + 10_000
            while (armed && System.currentTimeMillis() < end) { ctx.checkCancelled(); Thread.sleep(2) }
            return PixelBuffer.filled(src.width, src.height, GREEN)
        }
    }

    private lateinit var context: Context
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun newController(w: Int = 40, h: Int = 30, fill: Int = BLUE): Pair<EditorController, Layer> {
        val doc = Document("t", "t", w, h)
        val layer = Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(w, h))
        layer.bitmap.eraseColor(fill)
        doc.layers += layer
        return EditorController(context, doc, scope, AppSettings(context)) to layer
    }

    /** Runs the main looper (advancing its clock) until [cond] holds. */
    private fun waitUntil(what: String, timeoutMs: Long = 15_000, cond: () -> Boolean) {
        val looper = shadowOf(Looper.getMainLooper())
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) fail("Timed out waiting for $what")
            looper.idleFor(Duration.ofMillis(10))
            Thread.sleep(1)
        }
        looper.idle()
    }

    private fun startSession(c: EditorController, filter: Filter, waitForPreview: Boolean = true): FilterSession {
        c.startFilter(filter)
        val s = assertNotNullSession(c)
        s.debounceMs = 0
        if (waitForPreview) waitUntil("first preview") { s.hasPreview && !s.isRendering }
        return s
    }

    private fun assertNotNullSession(c: EditorController): FilterSession {
        val s = c.filterSession
        assertNotNull("startFilter should open a session", s)
        return s!!
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** The composite as shown on screen (with the preview override). */
    private fun onScreen(c: EditorController): Bitmap {
        val out = BitmapUtils.createLayerBitmap(c.doc.width, c.doc.height)
        c.compositor.drawDocument(Canvas(out), null, useOverrides = true)
        return out
    }

    private fun leftHalfSelection(w: Int, h: Int): Selection =
        Selection.fromBytes(ByteArray(w * h) { if (it % w < w / 2) -1 else 0 }, w, h)

    @Test
    fun previewShowsFilterInsideSelectionOnlyAndLeavesLayerUntouched() {
        val (c, layer) = newController()
        c.setSelection(leftHalfSelection(40, 30), recordUndo = false)
        val before = pixels(layer.bitmap)
        val s = startSession(c, InvertFilter())

        val ov = c.renderOverride
        assertNotNull(ov)
        assertSame(layer, ov!!.layer)
        val screen = onScreen(c)
        assertEquals(INVERTED_BLUE, screen.getPixel(5, 5))
        assertEquals(BLUE, screen.getPixel(30, 5))
        assertArrayEquals("preview must not modify the layer", before, pixels(layer.bitmap))
        assertEquals(1f, s.previewScale, 0f)
        assertFalse(c.canUndo)
        assertEquals("test_invert", FilterRecents.load(context).first())
    }

    @Test
    fun applyWritesOnlyInsideSelectionAndUndoRestores() {
        val (c, layer) = newController()
        c.setSelection(leftHalfSelection(40, 30), recordUndo = false)
        val before = pixels(layer.bitmap)
        val s = startSession(c, InvertFilter())

        s.apply()
        waitUntil("apply") { c.filterSession == null && c.busyMessage == null }
        assertTrue(s.isClosed)
        assertNull(c.renderOverride)
        for (y in 0 until 30) for (x in 0 until 40) {
            val expected = if (x < 20) INVERTED_BLUE else BLUE
            assertEquals("pixel $x,$y", expected, layer.bitmap.getPixel(x, y))
        }
        assertTrue(c.canUndo)
        assertEquals("Test invert", c.undoManager.undoLabel)

        c.undo()
        assertArrayEquals("undo restores the layer", before, pixels(layer.bitmap))
        c.redo()
        assertEquals(INVERTED_BLUE, layer.bitmap.getPixel(0, 0))
        assertEquals(BLUE, layer.bitmap.getPixel(39, 29))
    }

    @Test
    fun cancelLeavesNoTrace() {
        val (c, layer) = newController()
        val before = pixels(layer.bitmap)
        val s = startSession(c, InvertFilter())
        assertNotNull(c.renderOverride)

        s.cancel()
        assertTrue(s.isClosed)
        assertNull(c.filterSession)
        assertNull(c.renderOverride)
        assertFalse(c.canUndo)
        assertArrayEquals(before, pixels(layer.bitmap))
        assertEquals(BLUE, onScreen(c).getPixel(3, 3))
        // Undo while a session is open cancels it instead of undoing.
        val s2 = startSession(c, InvertFilter())
        c.undo()
        assertTrue(s2.isClosed)
        assertArrayEquals(before, pixels(layer.bitmap))
    }

    @Test
    fun compareShowsTheOriginalWhileHeld() {
        val (c, _) = newController()
        val s = startSession(c, InvertFilter())
        assertEquals(INVERTED_BLUE, onScreen(c).getPixel(1, 1))
        s.compare(true)
        assertNull(c.renderOverride)
        assertEquals(BLUE, onScreen(c).getPixel(1, 1))
        s.compare(false)
        assertNotNull(c.renderOverride)
        assertEquals(INVERTED_BLUE, onScreen(c).getPixel(1, 1))
    }

    @Test
    fun slowFiltersPreviewOnlyOnDemand() {
        val (c, _) = newController()
        val s = startSession(c, InvertFilter(live = false), waitForPreview = false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        assertFalse(s.hasPreview)
        assertNull(c.renderOverride)
        assertTrue(s.previewStale)
        s.renderPreview()
        waitUntil("on-demand preview") { s.hasPreview && !s.isRendering }
        assertFalse(s.previewStale)
        assertEquals(INVERTED_BLUE, onScreen(c).getPixel(1, 1))
        s.update("amount", 0f)
        assertTrue(s.previewStale)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        assertFalse(s.isRendering)
        assertEquals(INVERTED_BLUE, onScreen(c).getPixel(1, 1)) // not re-rendered until asked
    }

    @Test
    fun selectionChangedDuringTheSessionUpdatesThePreview() {
        val (c, _) = newController()
        val s = startSession(c, InvertFilter())
        assertEquals(INVERTED_BLUE, onScreen(c).getPixel(30, 5))
        c.setSelection(leftHalfSelection(40, 30), recordUndo = false)
        Snapshot.sendApplyNotifications()
        waitUntil("preview for the new selection") { !s.isRendering && !s.previewStale && onScreen(c).getPixel(30, 5) == BLUE }
        assertEquals(INVERTED_BLUE, onScreen(c).getPixel(5, 5))
    }

    @Test
    fun parameterChangesRerenderAndResetRestoresDefaults() {
        val (c, _) = newController()
        val s = startSession(c, InvertFilter())
        s.update("amount", 0f)
        waitUntil("re-render") { !s.isRendering && !s.previewStale }
        assertEquals(BLUE, onScreen(c).getPixel(1, 1))
        s.reset()
        assertEquals(100f, s.values.float("amount"), 0f)
        waitUntil("re-render after reset") { !s.isRendering && !s.previewStale }
        assertEquals(INVERTED_BLUE, onScreen(c).getPixel(1, 1))
    }

    @Test
    fun maskEditingFiltersTheMaskOnly() {
        val (c, layer) = newController()
        val mask = BitmapUtils.createMaskBitmap(40, 30)
        Canvas(mask).drawRect(0f, 0f, 20f, 30f, Paint().apply { color = 0xFF000000.toInt() })
        layer.mask = mask
        layer.editingMask = true
        val content = pixels(layer.bitmap)
        val maskBefore = pixels(mask)

        val s = startSession(c, InvertFilter())
        s.apply()
        waitUntil("apply") { c.filterSession == null && c.busyMessage == null }
        assertEquals(0xFFFFFFFF.toInt(), mask.getPixel(5, 5))
        assertEquals(0xFF000000.toInt(), mask.getPixel(30, 5))
        assertArrayEquals("content untouched while editing the mask", content, pixels(layer.bitmap))
        c.undo()
        assertArrayEquals(maskBefore, pixels(mask))
    }

    @Test
    fun alphaLockKeepsTransparentPixelsTransparent() {
        val (c, layer) = newController(fill = 0)
        Canvas(layer.bitmap).drawRect(0f, 0f, 20f, 30f, Paint().apply { color = RED })
        layer.alphaLocked = true
        val s = startSession(c, FillFilter())
        s.apply()
        waitUntil("apply") { c.filterSession == null && c.busyMessage == null }
        assertEquals(GREEN, layer.bitmap.getPixel(5, 5))
        assertEquals(0, layer.bitmap.getPixel(30, 5))

        layer.alphaLocked = false
        c.undo()
        val s2 = startSession(c, FillFilter())
        s2.apply()
        waitUntil("apply unlocked") { c.filterSession == null && c.busyMessage == null }
        assertEquals(GREEN, layer.bitmap.getPixel(30, 5))
    }

    @Test
    fun grayscaleDocumentPreviewsAndAppliesGray() {
        val (c, layer) = newController()
        c.doc.colorMode = ColorMode.GRAYSCALE
        val s = startSession(c, InvertFilter())
        val shown = onScreen(c).getPixel(2, 2)
        assertEquals(ColorUtils.red(shown), ColorUtils.green(shown))
        assertEquals(ColorUtils.green(shown), ColorUtils.blue(shown))
        s.apply()
        waitUntil("apply") { c.filterSession == null && c.busyMessage == null }
        assertEquals(shown, layer.bitmap.getPixel(2, 2))
    }

    @Test
    fun pointParametersAreDraggedOnTheCanvas() {
        val (c, _) = newController()
        c.viewTransform.set(Matrix().apply { setScale(10f, 10f) }) // 10 screen px per document px
        val s = startSession(c, PointFilter())
        // Near the "center" handle (20, 15): the grab keeps the offset.
        c.pointerDown(ToolPoint(21f, 15f))
        assertEquals("center", s.draggingPoint)
        c.pointerMove(ToolPoint(31f, 25f))
        c.pointerUp(ToolPoint(31f, 25f))
        assertNull(s.draggingPoint)
        val p = s.values.point("center")
        assertEquals(30f / 40f, p[0], 1e-4f)
        assertEquals(25f / 30f, p[1], 1e-4f)
        // Far from every handle: the nearest point jumps to the finger; values stay in 0..1.
        c.pointerDown(ToolPoint(5f, 5f))
        assertEquals("center", s.draggingPoint)
        assertArrayEquals(floatArrayOf(5f / 40f, 5f / 30f), s.values.point("center"), 1e-4f)
        c.pointerMove(ToolPoint(500f, -80f))
        c.pointerUp(ToolPoint(500f, -80f))
        assertArrayEquals(floatArrayOf(1f, 0f), s.values.point("center"), 0f)
        assertArrayEquals(floatArrayOf(0.9f, 0.9f), s.values.point("focus"), 0f)
        s.resetParam("center")
        assertArrayEquals(floatArrayOf(0.5f, 0.5f), s.values.point("center"), 0f)

        // Filters without point parameters don't take canvas gestures.
        val s2 = startSession(c, InvertFilter())
        assertFalse(s2.onPointerDown(ToolPoint(1f, 1f)))
    }

    @Test
    fun filterErrorsKeepTheSessionOpen() {
        val (c, layer) = newController()
        val before = pixels(layer.bitmap)
        val filter = FailingFilter(oom = false)
        val s = startSession(c, filter, waitForPreview = false)
        waitUntil("error message") { c.message != null && !s.isRendering }
        assertTrue(c.message!!.contains("boom"))
        assertSame(s, c.filterSession)
        assertFalse(s.hasPreview)

        c.message = null
        s.apply()
        waitUntil("failed apply") { !s.isApplying && c.busyMessage == null }
        assertNotNull(c.message)
        assertSame(s, c.filterSession)
        assertFalse(c.canUndo)
        assertArrayEquals(before, pixels(layer.bitmap))
    }

    @Test
    fun outOfMemoryOnApplyKeepsTheSessionOpen() {
        val (c, layer) = newController()
        val before = pixels(layer.bitmap)
        val filter = FailingFilter(oom = true).apply { armed = false }
        val s = startSession(c, filter)
        filter.armed = true
        s.apply()
        waitUntil("failed apply") { !s.isApplying && c.busyMessage == null }
        assertTrue(c.message!!.contains("memory"))
        assertSame(s, c.filterSession)
        assertArrayEquals(before, pixels(layer.bitmap))
        filter.armed = false
        s.apply()
        waitUntil("apply") { c.filterSession == null }
        assertTrue(c.canUndo)
    }

    @Test
    fun longApplyCanBeStoppedFromTheBusyOverlay() {
        val (c, layer) = newController()
        val before = pixels(layer.bitmap)
        val filter = BlockingFilter()
        val s = startSession(c, filter)
        filter.armed = true
        s.apply()
        waitUntil("apply running") { s.isApplying && c.busyMessage != null }
        val stop = c.busyCancel
        assertNotNull("the busy overlay must offer Stop", stop)
        stop!!.invoke()
        waitUntil("apply stopped") { !s.isApplying && c.busyMessage == null }
        assertNull(c.busyCancel)
        assertSame(s, c.filterSession)
        assertFalse(c.canUndo)
        assertArrayEquals(before, pixels(layer.bitmap))
        filter.armed = false
    }

    @Test
    fun stopRequestedBeforeTheApplyStartsAppliesNothing() {
        val (c, layer) = newController()
        val before = pixels(layer.bitmap)
        val s = startSession(c, InvertFilter())
        s.apply()
        s.cancelApply() // the busy coroutine hasn't run yet
        waitUntil("apply stopped") { !s.isApplying && c.busyMessage == null }
        assertSame(s, c.filterSession)
        assertFalse(c.canUndo)
        assertArrayEquals(before, pixels(layer.bitmap))
        assertNotNull("the preview stays", c.renderOverride)
        // A later apply still works.
        s.apply()
        waitUntil("apply") { c.filterSession == null && c.busyMessage == null }
        assertEquals(INVERTED_BLUE, layer.bitmap.getPixel(0, 0))
    }

    @Test
    fun drawingColorParametersStartAtTheDrawingColorAndResetToIt() {
        val (c, _) = newController()
        c.color = RED
        val s = startSession(c, TintFilter())
        assertEquals(RED, s.values.color("tint"))
        assertEquals(0xFF654321.toInt(), s.values.color("other"))
        assertEquals(RED, onScreen(c).getPixel(1, 1))

        s.update("tint", GREEN)
        s.update("other", GREEN)
        c.color = BLUE
        s.resetParam("tint")
        assertEquals("reset uses the drawing color at reset time", BLUE, s.values.color("tint"))
        assertEquals(GREEN, s.values.color("other"))

        c.color = 0xFF808080.toInt()
        s.reset()
        assertEquals(0xFF808080.toInt(), s.values.color("tint"))
        assertEquals(0xFF654321.toInt(), s.values.color("other"))
        waitUntil("re-render after reset") { !s.isRendering && !s.previewStale }
        assertEquals(0xFF808080.toInt(), onScreen(c).getPixel(1, 1))
    }

    @Test
    fun largeCanvasPreviewsDownscaledAndAppliesAtFullSize() {
        val (c, layer) = newController(w = 3000, h = 200)
        val s = startSession(c, InvertFilter())
        assertEquals(1280f / 3000f, s.previewScale, 1e-3f)
        assertEquals(INVERTED_BLUE, onScreen(c).getPixel(1500, 100))
        s.apply()
        waitUntil("apply") { c.filterSession == null && c.busyMessage == null }
        assertEquals(INVERTED_BLUE, layer.bitmap.getPixel(0, 0))
        assertEquals(INVERTED_BLUE, layer.bitmap.getPixel(2999, 199))
    }

    @Test
    fun deletedLayerCancelsInsteadOfApplying() {
        val (c, layer) = newController()
        c.addLayer("Other", index = 0)
        c.selectLayer(layer)
        val s = startSession(c, InvertFilter())
        c.deleteLayer(layer)
        s.apply()
        assertTrue(s.isClosed)
        assertNull(c.filterSession)
        assertNull(c.renderOverride)
        assertEquals(BLUE, layer.bitmap.getPixel(0, 0))
    }

    @Test
    fun lockedLayerCannotBeFiltered() {
        val (c, layer) = newController()
        layer.locked = true
        c.startFilter(InvertFilter())
        assertNull(c.filterSession)
        assertNotNull(c.message)
    }

    private companion object {
        const val BLUE = 0xFF2040C0.toInt()
        const val INVERTED_BLUE = 0xFFDFBF3F.toInt()
        const val GREEN = 0xFF00FF00.toInt()
        const val RED = 0xFFFF0000.toInt()
    }
}
