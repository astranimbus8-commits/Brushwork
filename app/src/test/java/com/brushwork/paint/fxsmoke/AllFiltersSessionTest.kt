package com.brushwork.paint.fxsmoke

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.filters.FilterSession
import com.brushwork.paint.fxsmoke.Fx.DRAW_COLOR
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.filters.FilterSessionPanel
import com.brushwork.paint.ui.filters.SliderFormat
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * "Be the device" for filters: EVERY filter in [FilterRegistry.all] runs through the real
 * [FilterSession] (real Skia, the app's Main.immediate scope) with the real [FilterSessionPanel]
 * composed in an activity:
 *  - the session starts; drawing-color parameters start at the drawing color;
 *  - the panel composes, lays out and draws for the filter's real parameters, follows parameter
 *    changes, and its Reset button restores the session defaults;
 *  - every choice option is previewed; non-default values (points dragged on the canvas) produce
 *    a preview identical to the filter run directly, which differs from the source whenever the
 *    filter changes anything;
 *  - apply writes exactly that result, adds exactly one undo step, closes the session, leaves
 *    the other layer alone; undo/redo restore exactly;
 *  - the same on a transparent line-art layer, and a cancel right while a preview renders leaves
 *    no trace.
 *
 * Only one Compose-driving test per class (own sandbox): Compose's frame clock is process-static
 * and stalls in later tests of a sandbox (see ColorPickerUiSmokeTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-hdpi", instrumentedPackages = ["com.brushwork.paint.fxsmoke.allfilterssandbox"])
class AllFiltersSessionTest {

    private val rs = RecordingScope()

    @After
    fun tearDown() = rs.close()

    private lateinit var activity: ComponentActivity

    @Test
    fun everyFilterPreviewsAndAppliesThroughTheRealPanel() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        ShadowLog.clear()
        val all = FilterRegistry.all
        assertTrue("registry should hold the full filter set, has ${all.size}", all.size >= 80)
        val problems = mutableListOf<String>()
        val identity = mutableListOf<String>()
        var drawWorks = true
        for (f in all) {
            try {
                val changed = runOnPaintLayerWithPanel(f, drawWorks)
                if (!changed) identity += f.id
            } catch (e: DrawUnsupported) {
                drawWorks = false
                problems += "${f.id}: panel draw unsupported under Robolectric: ${e.cause}"
            } catch (e: Throwable) {
                problems += "${f.id} [paint layer + panel]: ${describe(e)}"
            }
            try {
                runOnLineArtLayer(f)
            } catch (e: Throwable) {
                problems += "${f.id} [line-art layer]: ${describe(e)}"
            }
            try {
                cancelWhileRendering(f)
            } catch (e: Throwable) {
                problems += "${f.id} [cancel mid-preview]: ${describe(e)}"
            }
        }
        // No preview/segmentation work may run on the main thread.
        ShadowLog.getLogs().filter { it.msg?.contains("main thread", ignoreCase = true) == true }
            .forEach { problems += "log ${it.tag}: ${it.msg}" }
        println("fxsmoke: ${all.size} filters; unchanged by non-default values: $identity")
        if (problems.isNotEmpty()) fail("${problems.size} problem(s):\n" + problems.joinToString("\n"))
        // The visual comparisons must not be vacuous: nearly every filter changes the test image.
        assertTrue("too many filters left the image unchanged: $identity", identity.size <= all.size / 10)
    }

    private class DrawUnsupported(cause: Throwable) : RuntimeException(cause)

    private fun describe(e: Throwable): String {
        val frames = e.stackTrace.take(6).joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
        val cause = e.cause?.let { " / cause ${it.javaClass.simpleName}: ${it.message} @ ${it.stackTrace.take(4).joinToString(" <- ") { f -> "${f.className.substringAfterLast('.')}.${f.methodName}:${f.lineNumber}" }}" } ?: ""
        return "${e.javaClass.simpleName}: ${e.message} @ $frames$cause"
    }

    private fun showPanel(s: FilterSession) {
        activity.setContent {
            BrushworkTheme {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) { FilterSessionPanel(s, Modifier.fillMaxWidth()) }
                }
            }
        }
        RobolectricUi.settle(3, 50)
    }

    private fun clearPanel() {
        activity.setContent { BrushworkTheme { Box(Modifier.fillMaxSize()) } }
        RobolectricUi.settle(1, 50)
    }

    /** Draws the activity window into a software canvas (runs every Canvas {} block of the panel). */
    private fun drawWindow() {
        val root = activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(bmp))
        } catch (e: Throwable) {
            throw DrawUnsupported(e)
        } finally {
            bmp.recycle()
        }
    }

    /** Returns true if the non-default values visibly change the paint layer. */
    private fun runOnPaintLayerWithPanel(f: Filter, draw: Boolean): Boolean {
        val d = Fx.newDoc(activity.applicationContext, rs.scope)
        val c = d.controller
        val paint = d.paint
        val line = d.lineArt!!
        c.selectLayer(paint)
        val originalPaint = Fx.pixels(paint.bitmap)
        val originalLine = Fx.pixels(line.bitmap)
        try {
            // ---- 1. start
            c.startFilter(f)
            val s = c.filterSession ?: throw AssertionError("session did not start: ${c.message}")
            assertSame(f, s.filter)
            assertSame(paint, s.layer)
            assertFalse(s.isClosed)
            Fx.assertSessionDefaults(s, DRAW_COLOR, "start")
            Fx.awaitPreview(s, "defaults")
            Fx.assertNoErrorMessage(c, "${f.id} defaults preview")
            assertTrue("${f.id}: preview missing", s.hasPreview)
            assertNotNull("${f.id}: no render override", c.renderOverride)
            assertSame(paint, c.renderOverride!!.layer)
            assertTrue("${f.id}: histogram", s.histogram != null || f.params.none { it is FilterParam.Curve })

            // ---- 2. the panel composes / lays out / draws
            showPanel(s)
            assertTrue("${f.id}: panel shows the filter name", RobolectricUi.hasText(f.name))
            if (draw) drawWindow()

            // ---- 3. every choice option previews; then non-default values
            for (p in f.params.filterIsInstance<FilterParam.Choice>()) {
                for (o in p.options.indices) {
                    s.update(p.key, o)
                    Fx.awaitPreview(s, "${p.key}=$o")
                    Fx.assertNoErrorMessage(c, "${f.id} ${p.key}=${p.options[o]}")
                }
            }
            Fx.setNonDefaults(c, s)
            Fx.awaitPreview(s, "non-default values")
            Fx.assertNoErrorMessage(c, "${f.id} non-default preview")
            RobolectricUi.settle(2, 50)
            if (draw) drawWindow()
            f.params.filterIsInstance<FilterParam.Slider>().firstOrNull()?.let { p ->
                val text = SliderFormat.format(p, s.values.float(p.key))
                assertTrue("${f.id}: panel should show '${p.label}' = $text after the change", RobolectricUi.hasText(text))
            }
            rs.assertNoErrors(f.id)

            val values = s.values.copy()
            val expected = Fx.roundTrip(Fx.direct(f, BitmapUtils.toPixelBuffer(paint.bitmap), values, s.previewScale, c.doc.dpi))
            val preview = Fx.overrideContent(c) ?: throw AssertionError("no preview drawn")
            val exact = f.id != BG_REMOVAL
            if (exact) assertTrue("${f.id}: preview != direct filter result: ${Fx.diff(expected, preview, c.doc.width)}", expected.contentEquals(preview))
            val changes = !expected.contentEquals(originalPaint)
            if (changes) assertFalse("${f.id}: preview shows the unchanged layer", preview.contentEquals(originalPaint))
            assertTrue("${f.id}: previewing changed the layer", Fx.pixels(paint.bitmap).contentEquals(originalPaint))

            // ---- 4. Reset (through the panel button) restores the session defaults
            c.color = Fx.ALT_COLOR_2 // Reset uses the drawing color at reset time
            RobolectricUi.byDescription("Reset to defaults").tap()
            Fx.assertSessionDefaults(s, Fx.ALT_COLOR_2, "after Reset")
            c.color = DRAW_COLOR
            Fx.setNonDefaults(c, s)
            // Colors with useDrawingColor were set to ALT_COLOR by setNonDefaults: same values as before.
            for (p in f.params) assertTrue("${f.id}: '${p.key}' differs after reset + set", Fx.sameValue(values.raw(p.key), s.values.raw(p.key)))
            Fx.awaitPreview(s, "after reset")

            // ---- 5. apply
            val undoBefore = c.undoManager.undoCount
            Fx.applyAndWait(s)
            rs.assertNoErrors(f.id)
            Fx.assertNoErrorMessage(c, "${f.id} apply")
            assertTrue("${f.id}: session still open after apply (${c.message})", s.isClosed)
            assertNull(c.filterSession)
            assertNull("${f.id}: override left after apply", c.renderOverride)
            RobolectricUi.settle(2, 50)
            assertFalse("${f.id}: panel still shown after apply", RobolectricUi.hasText(f.name))
            val after = Fx.pixels(paint.bitmap)
            assertTrue("${f.id}: apply touched the other layer", Fx.pixels(line.bitmap).contentEquals(originalLine))
            if (!changes) {
                assertEquals("${f.id}: no-op apply must not add an undo step", undoBefore, c.undoManager.undoCount)
                assertTrue(after.contentEquals(originalPaint))
                return false
            }
            assertEquals("${f.id}: exactly one undo step", undoBefore + 1, c.undoManager.undoCount)
            assertEquals(f.name, c.undoManager.undoLabel)
            if (exact) assertTrue("${f.id}: applied != direct filter result: ${Fx.diff(expected, after, c.doc.width)}", expected.contentEquals(after))
            else assertFalse(after.contentEquals(originalPaint))

            // ---- 6. undo / redo
            c.undo()
            assertTrue("${f.id}: undo must restore exactly: ${Fx.diff(originalPaint, Fx.pixels(paint.bitmap), c.doc.width)}", Fx.pixels(paint.bitmap).contentEquals(originalPaint))
            c.redo()
            assertTrue("${f.id}: redo", Fx.pixels(paint.bitmap).contentEquals(after))
            c.undo()
            return true
        } finally {
            c.filterSession?.cancel()
            clearPanel()
            c.dispose()
        }
    }

    /** The same flow (session only) on the transparent line-art layer. */
    private fun runOnLineArtLayer(f: Filter) {
        val d = Fx.newDoc(activity.applicationContext, rs.scope)
        val c = d.controller
        val line = d.lineArt!!
        c.selectLayer(line)
        val original = Fx.pixels(line.bitmap)
        val originalPaint = Fx.pixels(d.paint.bitmap)
        try {
            c.startFilter(f)
            val s = c.filterSession ?: throw AssertionError("session did not start: ${c.message}")
            assertSame(line, s.layer)
            Fx.awaitPreview(s, "defaults")
            Fx.setNonDefaults(c, s)
            Fx.awaitPreview(s, "non-default values")
            Fx.assertNoErrorMessage(c, "${f.id} line-art preview")
            // The canvas overlays (point handles and their labels) draw on the view's canvas.
            val overlay = Bitmap.createBitmap(Fx.W * 3, Fx.H * 3, Bitmap.Config.ARGB_8888)
            c.drawOverlays(Canvas(overlay), 0f)
            val handles = Fx.pixels(overlay).any { it != 0 }
            overlay.recycle()
            assertEquals("${f.id}: point handles drawn", f.params.any { it is FilterParam.Point }, handles)
            val values = s.values.copy()
            val expected = Fx.roundTrip(Fx.direct(f, BitmapUtils.toPixelBuffer(line.bitmap), values, s.previewScale, c.doc.dpi))
            val exact = f.id != BG_REMOVAL
            if (exact) {
                val preview = Fx.overrideContent(c) ?: throw AssertionError("no preview drawn")
                assertTrue("${f.id}: preview != direct result: ${Fx.diff(expected, preview, c.doc.width)}", expected.contentEquals(preview))
            }
            val undoBefore = c.undoManager.undoCount
            Fx.applyAndWait(s)
            rs.assertNoErrors(f.id)
            Fx.assertNoErrorMessage(c, "${f.id} line-art apply")
            assertTrue("${f.id}: session still open (${c.message})", s.isClosed)
            assertNull(c.renderOverride)
            val after = Fx.pixels(line.bitmap)
            assertTrue("${f.id}: other layer touched", Fx.pixels(d.paint.bitmap).contentEquals(originalPaint))
            if (exact) assertTrue("${f.id}: applied != direct result: ${Fx.diff(expected, after, c.doc.width)}", expected.contentEquals(after))
            if (after.contentEquals(original)) {
                assertEquals(undoBefore, c.undoManager.undoCount)
            } else {
                assertEquals(undoBefore + 1, c.undoManager.undoCount)
                c.undo()
                assertTrue("${f.id}: undo", Fx.pixels(line.bitmap).contentEquals(original))
            }
        } finally {
            c.filterSession?.cancel()
            c.dispose()
        }
    }

    /** Cancel right after the debounce fired (the render is running in the background). */
    private fun cancelWhileRendering(f: Filter) {
        val d = Fx.newDoc(activity.applicationContext, rs.scope, withLineArt = false)
        val c = d.controller
        val original = Fx.pixels(d.paint.bitmap)
        try {
            c.startFilter(f)
            val s = c.filterSession ?: throw AssertionError("session did not start: ${c.message}")
            val p = f.params.firstOrNull { it !is FilterParam.Point }
            if (p != null) s.update(p.key, Fx.nonDefault(p)!!) else s.renderPreview()
            Fx.pump(90) // past the 80 ms debounce: the render starts
            s.cancel()
            assertTrue(s.isClosed)
            assertNull(c.filterSession)
            assertNull("${f.id}: override after cancel", c.renderOverride)
            Fx.pump(150)
            Fx.waitUntil("background work to end") { true }
            assertNull("${f.id}: a late preview re-installed the override", c.renderOverride)
            assertTrue("${f.id}: cancel changed the layer", Fx.pixels(d.paint.bitmap).contentEquals(original))
            assertEquals(0, c.undoManager.undoCount)
            Fx.assertNoErrorMessage(c, "${f.id} cancel")
            rs.assertNoErrors(f.id)
        } finally {
            c.dispose()
        }
    }

    private companion object {
        const val BG_REMOVAL = "ai.background_removal"
    }
}
