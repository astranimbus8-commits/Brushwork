package com.brushwork.paint.fxsmoke

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.filters.FilterSessionMath
import com.brushwork.paint.fxsmoke.Fx.DRAW_COLOR
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/**
 * Every real filter through the real FilterSession in the situations the editor produces: a soft
 * selection, a layer mask being edited, an empty layer, grayscale/monochrome documents, alpha
 * lock, a canvas large enough for a downscaled preview, the real segmentation service, and the
 * session being cancelled while it applies. Session-only (no Compose).
 */
@RunWith(RobolectricTestRunner::class)
class FilterScenariosTest {

    private val rs = RecordingScope()
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() = rs.close()

    /** Runs [body] for every filter, collecting failures so one run reports all broken filters. */
    private fun forEachFilter(filters: List<Filter> = FilterRegistry.all, body: (Filter) -> Unit) {
        val problems = mutableListOf<String>()
        for (f in filters) {
            try {
                body(f)
                rs.assertNoErrors(f.id)
            } catch (e: Throwable) {
                problems += "${f.id}: ${e.javaClass.simpleName}: ${e.message} @ " +
                    e.stackTrace.take(5).joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                rs.errors.clear()
            }
        }
        if (problems.isNotEmpty()) fail("${problems.size} filter(s) failed:\n" + problems.joinToString("\n"))
    }

    /** Starts [f] on [layer]; no debounce (AllFiltersSessionTest covers the real one). */
    private fun startOn(d: Fx.TestDoc, f: Filter, layer: Layer) = d.controller.let { c ->
        c.selectLayer(layer)
        c.startFilter(f)
        val s = c.filterSession ?: throw AssertionError("session did not start: ${c.message}")
        s.debounceMs = 0
        s
    }

    // ------------------------------------------------------------------ selection

    /** Soft-edged selection: full on the left, a ramp, nothing on the right; rows 6..41 only. */
    private fun softSelection(w: Int, h: Int): ByteArray = ByteArray(w * h) { i ->
        val x = i % w; val y = i / w
        val v = when {
            y !in 6 until 42 -> 0
            x < 20 -> 255
            x < 28 -> (x - 20) * 32
            else -> 0
        }
        v.toByte()
    }

    @Test
    fun selectionLimitsPreviewAndApplyToTheSelectedPixels() = forEachFilter { f ->
        val d = Fx.newDoc(context, rs.scope)
        val c = d.controller
        val sel = softSelection(Fx.W, Fx.H)
        c.setSelection(Selection.fromBytes(sel, Fx.W, Fx.H), recordUndo = false)
        val original = Fx.pixels(d.paint.bitmap)
        try {
            val s = startOn(d, f, d.paint)
            Fx.awaitPreview(s, "defaults")
            Fx.setNonDefaults(c, s)
            Fx.awaitPreview(s, "non-default values")
            Fx.assertNoErrorMessage(c, f.id)
            val src = BitmapUtils.toPixelBuffer(d.paint.bitmap)
            val out = Fx.direct(f, src, s.values, 1f, c.doc.dpi)
            FilterSessionMath.compose(src, out, sel, alphaLocked = false, maskTarget = false)
            val expected = Fx.roundTrip(out)
            val exact = f.id != BG_REMOVAL
            val preview = Fx.overrideContent(c) ?: throw AssertionError("no preview")
            if (exact) assertTrue("preview: ${Fx.diff(expected, preview, Fx.W)}", expected.contentEquals(preview))
            val undoBefore = c.undoManager.undoCount
            Fx.applyAndWait(s)
            Fx.assertNoErrorMessage(c, f.id)
            assertTrue("session still open (${c.message})", s.isClosed)
            val after = Fx.pixels(d.paint.bitmap)
            for (i in sel.indices) if (sel[i].toInt() == 0 && after[i] != original[i]) {
                fail("pixel (${i % Fx.W},${i / Fx.W}) outside the selection changed: 0x${Integer.toHexString(original[i])} -> 0x${Integer.toHexString(after[i])}")
            }
            if (exact) assertTrue("applied: ${Fx.diff(expected, after, Fx.W)}", expected.contentEquals(after))
            if (!after.contentEquals(original)) {
                assertEquals(undoBefore + 1, c.undoManager.undoCount)
                c.undo()
                assertTrue("undo", Fx.pixels(d.paint.bitmap).contentEquals(original))
                assertNotNull("undoing the filter keeps the selection", c.selection)
            }
        } finally {
            c.filterSession?.cancel(); c.dispose()
        }
    }

    // ------------------------------------------------------------------ layer mask

    @Test
    fun editingTheLayerMaskFiltersTheMaskOnly() = forEachFilter { f ->
        val d = Fx.newDoc(context, rs.scope)
        val c = d.controller
        val mask = BitmapUtils.createMaskBitmap(Fx.W, Fx.H)
        Canvas(mask).drawRect(0f, 0f, Fx.W.toFloat(), Fx.H.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, Fx.W.toFloat(), 0f, 0xFF202020.toInt(), 0xFFF0F0F0.toInt(), Shader.TileMode.CLAMP)
        })
        Canvas(mask).drawCircle(40f, 20f, 9f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() })
        d.paint.mask = mask
        d.paint.editingMask = true
        val content = Fx.pixels(d.paint.bitmap)
        val maskBefore = Fx.pixels(mask)
        try {
            val s = startOn(d, f, d.paint)
            assertEquals(EditTarget.MASK, s.target)
            Fx.awaitPreview(s, "defaults")
            Fx.setNonDefaults(c, s)
            Fx.awaitPreview(s, "non-default values")
            Fx.assertNoErrorMessage(c, f.id)
            val src = BitmapUtils.toPixelBuffer(mask)
            val out = Fx.direct(f, src, s.values, 1f, c.doc.dpi)
            FilterSessionMath.compose(src, out, null, alphaLocked = false, maskTarget = true)
            val expected = Fx.roundTrip(out)
            val exact = f.id != BG_REMOVAL
            val preview = Fx.overrideMask(c) ?: throw AssertionError("no mask preview")
            if (exact) assertTrue("mask preview: ${Fx.diff(expected, preview, Fx.W)}", expected.contentEquals(preview))
            assertNull("the content is not overridden while editing the mask", Fx.overrideContent(c))
            Fx.applyAndWait(s)
            Fx.assertNoErrorMessage(c, f.id)
            assertTrue("session still open (${c.message})", s.isClosed)
            assertTrue("content must stay untouched", Fx.pixels(d.paint.bitmap).contentEquals(content))
            val after = Fx.pixels(mask)
            assertTrue("mask stays opaque gray", after.all { it ushr 24 == 0xFF && (it shr 16 and 0xFF) == (it and 0xFF) })
            if (exact) assertTrue("applied mask: ${Fx.diff(expected, after, Fx.W)}", expected.contentEquals(after))
            if (!after.contentEquals(maskBefore)) {
                assertEquals(1, c.undoManager.undoCount)
                c.undo()
                assertTrue("undo restores the mask", Fx.pixels(mask).contentEquals(maskBefore))
            }
        } finally {
            c.filterSession?.cancel(); c.dispose()
        }
    }

    // ------------------------------------------------------------------ empty layer

    @Test
    fun contentGeneratingFiltersDrawOnAnEmptyLayer() {
        val generators = FilterRegistry.all.filter { it.generatesContent }
        assertTrue("expected several content-generating filters, got ${generators.map { it.id }}", generators.size >= 8)
        forEachFilter(generators) { f ->
            // Large enough for every default size (Table (Size): 100 px cells inside a 40 px margin).
            val d = Fx.newDoc(context, rs.scope, 320, 240, withLineArt = false)
            val c = d.controller
            val empty = c.addLayer("Empty") ?: throw AssertionError("addLayer failed")
            c.undoManager.clear()
            try {
                val s = startOn(d, f, empty)
                Fx.awaitPreview(s, "defaults")
                Fx.waitUntil("analysis") { s.histogram != null }
                assertNull("no 'layer is empty' warning for a content-generating filter", c.message)
                val preview = Fx.overrideContent(c) ?: throw AssertionError("no preview")
                assertTrue("the preview draws something", preview.any { it ushr 24 != 0 })
                Fx.applyAndWait(s)
                Fx.assertNoErrorMessage(c, f.id)
                assertTrue("session still open (${c.message})", s.isClosed)
                val after = Fx.pixels(empty.bitmap)
                assertTrue("the layer has content", after.any { it ushr 24 != 0 })
                assertTrue("the preview matches the result", preview.contentEquals(after))
                assertEquals(1, c.undoManager.undoCount)
                c.undo()
                assertTrue("undo empties the layer", Fx.pixels(empty.bitmap).all { it == 0 })
            } finally {
                c.filterSession?.cancel(); c.dispose()
            }
        }
    }

    @Test
    fun otherFiltersOnAnEmptyLayerWarnAndDoNothing() = forEachFilter(FilterRegistry.all.filter { !it.generatesContent }) { f ->
        val d = Fx.newDoc(context, rs.scope, withLineArt = false)
        val c = d.controller
        val empty = c.addLayer("Empty") ?: throw AssertionError("addLayer failed")
        c.undoManager.clear()
        try {
            val s = startOn(d, f, empty)
            Fx.awaitPreview(s, "defaults")
            Fx.waitUntil("analysis") { s.histogram != null }
            assertTrue("expected the empty-layer hint, got ${c.message}", c.message?.contains("is empty") == true)
            c.message = null
            Fx.applyAndWait(s)
            Fx.assertNoErrorMessage(c, f.id)
            assertTrue(s.isClosed)
            val after = Fx.pixels(empty.bitmap)
            if (after.all { it == 0 }) assertEquals("nothing to undo", 0, c.undoManager.undoCount)
            else assertEquals(1, c.undoManager.undoCount)
        } finally {
            c.filterSession?.cancel(); c.dispose()
        }
    }

    // ------------------------------------------------------------------ color modes / alpha lock

    @Test
    fun grayscaleAndMonochromeDocumentsPreviewExactlyWhatIsApplied() = forEachFilter { f ->
        for (mode in listOf(ColorMode.GRAYSCALE, ColorMode.MONOCHROME)) {
            val d = Fx.newDoc(context, rs.scope)
            val c = d.controller
            c.doc.colorMode = mode
            try {
                val s = startOn(d, f, d.lineArt!!)
                Fx.setNonDefaults(c, s)
                Fx.awaitPreview(s, "$mode")
                val preview = Fx.overrideContent(c) ?: throw AssertionError("no preview")
                Fx.applyAndWait(s)
                Fx.assertNoErrorMessage(c, "${f.id} $mode")
                assertTrue(s.isClosed)
                val after = Fx.pixels(d.lineArt.bitmap)
                if (c.undoManager.undoCount > 0) {
                    assertTrue("$mode: preview != applied: ${Fx.diff(preview, after, Fx.W)}", preview.contentEquals(after))
                    for (px in after) if (px ushr 24 != 0) {
                        val r = (px shr 16) and 0xFF; val g = (px shr 8) and 0xFF; val b = px and 0xFF
                        assertTrue("$mode: colored pixel 0x${Integer.toHexString(px)}", r == g && g == b)
                    }
                }
            } finally {
                c.filterSession?.cancel(); c.dispose()
            }
        }
    }

    @Test
    fun alphaLockKeepsEveryPixelsAlpha() = forEachFilter { f ->
        val d = Fx.newDoc(context, rs.scope)
        val c = d.controller
        val line = d.lineArt!!
        line.alphaLocked = true
        val original = Fx.pixels(line.bitmap)
        try {
            val s = startOn(d, f, line)
            Fx.setNonDefaults(c, s)
            Fx.awaitPreview(s, "alpha locked")
            val preview = Fx.overrideContent(c) ?: throw AssertionError("no preview")
            for (i in original.indices) assertEquals("preview alpha at $i", original[i] ushr 24, preview[i] ushr 24)
            Fx.applyAndWait(s)
            Fx.assertNoErrorMessage(c, f.id)
            val after = Fx.pixels(line.bitmap)
            for (i in original.indices) assertEquals("alpha at (${i % Fx.W},${i / Fx.W})", original[i] ushr 24, after[i] ushr 24)
        } finally {
            c.filterSession?.cancel(); c.dispose()
        }
    }

    // ------------------------------------------------------------------ downscaled preview

    @Test
    fun wideCanvasPreviewsDownscaledAndAppliesAtFullResolution() = forEachFilter { f ->
        val w = 1400; val h = 40
        val d = Fx.newDoc(context, rs.scope, w, h)
        val c = d.controller
        val original = Fx.pixels(d.paint.bitmap)
        try {
            val s = startOn(d, f, d.paint)
            assertTrue("preview should be downscaled, scale ${s.previewScale}", s.previewScale < 0.95f)
            Fx.setNonDefaults(c, s)
            Fx.awaitPreview(s, "downscaled")
            Fx.assertNoErrorMessage(c, f.id)
            assertNotNull(Fx.overrideContent(c))
            val expected = Fx.roundTrip(Fx.direct(f, BitmapUtils.toPixelBuffer(d.paint.bitmap), s.values, 1f, c.doc.dpi))
            Fx.applyAndWait(s)
            Fx.assertNoErrorMessage(c, f.id)
            assertTrue("session still open (${c.message})", s.isClosed)
            val after = Fx.pixels(d.paint.bitmap)
            if (f.id != BG_REMOVAL) assertTrue("applied at full size: ${Fx.diff(expected, after, w)}", expected.contentEquals(after))
            if (!after.contentEquals(original)) {
                assertEquals(1, c.undoManager.undoCount)
                c.undo()
                assertTrue(Fx.pixels(d.paint.bitmap).contentEquals(original))
            }
        } finally {
            c.filterSession?.cancel(); c.dispose()
        }
    }

    /**
     * A downscaled preview must look like the full-size result: filters scale pixel distances by
     * ctx.scale. Compares both at preview size (mean absolute channel difference) against the
     * error the down/up-scaling alone causes on the unfiltered image.
     */
    @Test
    fun downscaledPreviewLooksLikeTheFullSizeResult() {
        val w = 2560; val h = 96
        val rows = mutableListOf<Pair<String, Float>>()
        forEachFilter { f ->
            val d = Fx.newDoc(context, rs.scope, w, h, withLineArt = false)
            val c = d.controller
            d.paint.bitmap.setPixels(structuredPixels(w, h), 0, w, 0, 0, w, h)
            try {
                val s = startOn(d, f, d.paint)
                assertEquals(0.5f, s.previewScale, 1e-3f)
                Fx.awaitPreview(s, "defaults")
                val (pw, ph) = FilterSessionMath.previewSize(w, h)
                val shown = BitmapUtils.createLayerBitmap(w, h)
                c.renderOverride!!.drawContent(Canvas(shown))
                Fx.applyAndWait(s)
                assertTrue("session still open (${c.message})", s.isClosed)
                val preview = Fx.pixels(com.brushwork.paint.filters.FilterSessionBitmaps.downscale(shown, pw, ph))
                val full = Fx.pixels(com.brushwork.paint.filters.FilterSessionBitmaps.downscale(d.paint.bitmap, pw, ph))
                rows += f.id to meanAbsDiff(preview, full)
            } finally {
                c.filterSession?.cancel(); c.dispose()
            }
        }
        // Baseline: the same comparison without any filter (resampling error only).
        val src = BitmapUtils.createLayerBitmap(w, h).apply { setPixels(structuredPixels(w, h), 0, w, 0, 0, w, h) }
        val (pw, ph) = FilterSessionMath.previewSize(w, h)
        val small = com.brushwork.paint.filters.FilterSessionBitmaps.downscale(src, pw, ph)
        val up = BitmapUtils.createLayerBitmap(w, h)
        Canvas(up).drawBitmap(small, null, android.graphics.RectF(0f, 0f, w.toFloat(), h.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        val baseline = meanAbsDiff(Fx.pixels(com.brushwork.paint.filters.FilterSessionBitmaps.downscale(up, pw, ph)), Fx.pixels(small))
        println("fxsmoke preview-vs-full (baseline %.2f):\n".format(baseline) + rows.sortedByDescending { it.second }.joinToString("\n") { "%-40s %.2f".format(it.first, it.second) })
        assertEquals(FilterRegistry.all.size, rows.size)
        // Random per-pixel effects (frosted glass, noise, halftone dots) differ most, ~25; a preview
        // at the wrong place or scale differs by far more.
        val bad = rows.filter { it.second > 32f }
        assertTrue("downscaled preview far from the full-size result: $bad", bad.isEmpty())
        // Filters that change nothing at defaults show just the resampling error.
        assertTrue("baseline $baseline", baseline < 6f)
    }

    /** Mean absolute difference per channel (0..255) of two same-size pixel arrays, alpha-weighted colors. */
    private fun meanAbsDiff(a: IntArray, b: IntArray): Float {
        var sum = 0.0
        for (i in a.indices) {
            val x = a[i]; val y = b[i]
            val ax = x ushr 24; val ay = y ushr 24
            sum += kotlin.math.abs(ax - ay)
            for (sh in intArrayOf(16, 8, 0)) {
                // Premultiplied comparison: the color of transparent pixels doesn't matter.
                sum += kotlin.math.abs(((x shr sh) and 0xFF) * ax - ((y shr sh) and 0xFF) * ay) / 255.0
            }
        }
        return (sum / (a.size * 4)).toFloat()
    }

    /** Opaque test image with features of 8..48 px: checker cells, thick lines, a disc. */
    private fun structuredPixels(w: Int, h: Int): IntArray = IntArray(w * h) { i ->
        val x = i % w; val y = i / w
        var r = if ((x / 24 + y / 24) % 2 == 0) 200 else 60
        var g = (x * 255) / w
        var b = if ((x / 48) % 3 == 0) 220 else 40
        if ((x + 2 * y) % 64 < 8) { r = 20; g = 20; b = 20 }
        val dx = x % 400 - 200; val dy = y - h / 2
        if (dx * dx + dy * dy < 30 * 30) { r = 250; g = 230; b = 40 }
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    // ------------------------------------------------------------------ cancel while applying

    @Test
    fun undoWhileApplyingCancelsWithoutATrace() {
        var midApply = 0
        forEachFilter { f ->
            // With Main.immediate, apply() returns while the filter runs in the background; only a
            // filter that finishes before the main thread suspends completes inside apply().
            val w = 320; val h = 120
            val d = Fx.newDoc(context, rs.scope, w, h)
            val c = d.controller
            val original = Fx.pixels(d.paint.bitmap)
            try {
                val s = startOn(d, f, d.paint)
                Fx.awaitPreview(s, "before apply")
                s.apply()
                if (!s.isApplying) {
                    // Finished synchronously: applied and closed like a normal apply.
                    assertTrue(s.isClosed)
                    assertNull(c.busyMessage)
                    c.undo()
                } else {
                    midApply++
                    c.undo() // two-finger tap: cancels the session
                    assertTrue(s.isClosed)
                    assertNull(c.filterSession)
                    Fx.waitUntil("apply job to end") { !s.isApplying && c.busyMessage == null }
                    Fx.pump(100)
                    assertEquals(0, c.undoManager.undoCount)
                }
                assertNull(c.renderOverride)
                assertTrue("layer untouched", Fx.pixels(d.paint.bitmap).contentEquals(original))
                Fx.assertNoErrorMessage(c, f.id)
            } finally {
                c.filterSession?.cancel(); c.dispose()
            }
        }
        assertTrue("only $midApply filters were still applying when undone", midApply >= FilterRegistry.all.size * 3 / 4)
    }

    // ------------------------------------------------------------------ background removal

    private fun subjectDoc(w: Int, h: Int): Fx.TestDoc {
        val d = Fx.newDoc(context, rs.scope, w, h, withLineArt = false)
        val b = d.paint.bitmap
        b.eraseColor(0xFFE6EEF6.toInt())
        val cv = Canvas(b)
        cv.drawCircle(w / 2f, h / 2f, h * 0.3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD02A1E.toInt() })
        cv.drawCircle(w / 2f, h / 2f, h * 0.3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF202020.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f })
        return d
    }

    @Test
    fun backgroundRemovalRunsWithTheRealSegmentationService() {
        val f = FilterRegistry.byId(BG_REMOVAL) ?: throw AssertionError("no background removal filter")
        ShadowLog.clear()
        for (method in 0..1) {
            val w = 96; val h = 72
            val d = subjectDoc(w, h)
            val c = d.controller
            val original = Fx.pixels(d.paint.bitmap)
            try {
                val s = startOn(d, f, d.paint)
                s.update("method", method)
                Fx.awaitPreview(s, "method $method")
                Fx.assertNoErrorMessage(c, "method $method preview")
                val preview = Fx.overrideContent(c) ?: throw AssertionError("no preview")
                Fx.applyAndWait(s)
                rs.assertNoErrors("background removal")
                Fx.assertNoErrorMessage(c, "method $method apply")
                assertTrue("session still open (${c.message})", s.isClosed)
                val after = Fx.pixels(d.paint.bitmap)
                assertTrue("preview matches the result (method $method)", preview.contentEquals(after))
                val corner = after[0] ushr 24
                val center = after[(h / 2) * w + w / 2] ushr 24
                assertTrue("method $method: background corner alpha $corner should be removed", corner < 32)
                assertTrue("method $method: subject alpha $center should stay", center > 220)
                // Only alpha changes: fully opaque pixels keep their exact color.
                for (i in after.indices) if (after[i] ushr 24 == 0xFF) assertEquals(original[i], after[i])
                assertEquals(1, c.undoManager.undoCount)
                c.undo()
                assertTrue(Fx.pixels(d.paint.bitmap).contentEquals(original))
            } finally {
                c.filterSession?.cancel(); c.dispose()
            }
        }
        val mainThread = ShadowLog.getLogs().filter { it.msg?.contains("main thread", ignoreCase = true) == true }
        assertTrue("segmentation ran on the main thread: ${mainThread.map { it.msg }}", mainThread.isEmpty())
    }

    // ------------------------------------------------------------------ tool interplay

    /**
     * A moved (pending) transform when a filter starts: the transform is committed first and the
     * filter previews/applies the committed pixels inside the moved selection; both are separate
     * undo steps; afterwards the still-current Transform tool lifts again on the next touch.
     */
    @Test
    fun startingAFilterCommitsAPendingTransformFirst() {
        val d = Fx.newDoc(context, rs.scope, withLineArt = false)
        val c = d.controller
        val original = Fx.pixels(d.paint.bitmap)
        val sel = ByteArray(Fx.W * Fx.H) { i -> if (i % Fx.W in 8 until 24 && i / Fx.W in 8 until 24) -1 else 0 }
        c.setSelection(Selection.fromBytes(sel, Fx.W, Fx.H), recordUndo = false)
        c.selectTool(com.brushwork.paint.tools.ToolId.TRANSFORM)
        Fx.pump(50)
        c.pointerDown(com.brushwork.paint.tools.ToolPoint(16f, 16f))
        c.pointerMove(com.brushwork.paint.tools.ToolPoint(24f, 20f))
        c.pointerMove(com.brushwork.paint.tools.ToolPoint(36f, 26f))
        c.pointerUp(com.brushwork.paint.tools.ToolPoint(36f, 26f))
        Fx.pump(50)
        assertTrue("the transform is pending", c.currentTool.hasPendingWork)

        val invert = FilterRegistry.byId("adjust.invert") ?: throw AssertionError("no invert filter")
        c.startFilter(invert)
        val s = c.filterSession ?: throw AssertionError("session did not start: ${c.message}")
        s.debounceMs = 0
        assertFalse("the transform was committed", c.currentTool.hasPendingWork)
        assertEquals(1, c.undoManager.undoCount)
        val committed = Fx.pixels(d.paint.bitmap)
        assertFalse(committed.contentEquals(original))
        val movedSel = c.selection ?: throw AssertionError("the selection should move with the transform")
        Fx.awaitPreview(s, "after transform")
        assertSame("the filter owns the render override now", s.layer, c.renderOverride?.layer)
        val src = BitmapUtils.toPixelBuffer(d.paint.bitmap)
        val out = Fx.direct(invert, src, s.values)
        FilterSessionMath.compose(src, out, movedSel.toBytes(), alphaLocked = false, maskTarget = false)
        val expected = Fx.roundTrip(out)
        assertTrue("preview: ${Fx.diff(expected, Fx.overrideContent(c)!!, Fx.W)}", expected.contentEquals(Fx.overrideContent(c)!!))
        Fx.applyAndWait(s)
        assertTrue(s.isClosed)
        assertTrue("applied: ${Fx.diff(expected, Fx.pixels(d.paint.bitmap), Fx.W)}", expected.contentEquals(Fx.pixels(d.paint.bitmap)))
        assertEquals(2, c.undoManager.undoCount)
        assertEquals(invert.name, c.undoManager.undoLabel)

        // The Transform tool is still current: the next touch lifts the (filtered) selection again.
        c.pointerDown(com.brushwork.paint.tools.ToolPoint(30f, 20f))
        c.pointerMove(com.brushwork.paint.tools.ToolPoint(28f, 18f))
        c.pointerUp(com.brushwork.paint.tools.ToolPoint(28f, 18f))
        Fx.pump(50)
        assertTrue("the transform tool works again after the filter", c.currentTool.hasPendingWork)
        c.currentTool.discard()
        Fx.pump(50)
        assertTrue(Fx.pixels(d.paint.bitmap).contentEquals(expected))
        c.undo(); c.undo()
        assertTrue("undoing both steps restores the original", Fx.pixels(d.paint.bitmap).contentEquals(original))
        rs.assertNoErrors("transform + filter")
        c.dispose()
    }

    // ------------------------------------------------------------------ misc

    @Test
    fun drawingColorParametersFollowTheDrawingColorAtStart() {
        val withDrawingColor = FilterRegistry.all.filter { f -> f.params.any { it is com.brushwork.paint.filters.FilterParam.Color && it.useDrawingColor } }
        assertTrue(withDrawingColor.isNotEmpty())
        forEachFilter(withDrawingColor) { f ->
            val d = Fx.newDoc(context, rs.scope)
            val c = d.controller
            try {
                for (color in listOf(DRAW_COLOR, Fx.ALT_COLOR_2)) {
                    c.color = color
                    val s = startOn(d, f, d.paint)
                    Fx.assertSessionDefaults(s, color, "drawing color")
                    s.cancel()
                }
            } finally {
                c.filterSession?.cancel(); c.dispose()
            }
        }
    }

    @Test
    fun startingAnotherFilterReplacesTheSession() {
        val d = Fx.newDoc(context, rs.scope)
        val c = d.controller
        val original = Fx.pixels(d.paint.bitmap)
        val filters = FilterRegistry.all
        var previous: com.brushwork.paint.filters.FilterSession? = null
        for (f in filters.take(12)) {
            c.startFilter(f)
            val s = c.filterSession ?: throw AssertionError("${f.id} did not start")
            previous?.let { assertTrue("the previous session was closed", it.isClosed) }
            assertSame(f, s.filter)
            previous = s
        }
        Fx.awaitPreview(previous!!, "last")
        previous.cancel()
        Fx.pump(200)
        assertNull(c.renderOverride)
        assertTrue(Fx.pixels(d.paint.bitmap).contentEquals(original))
        assertFalse(c.canUndo)
        rs.assertNoErrors("switching filters")
        c.dispose()
    }

    private companion object {
        const val BG_REMOVAL = "ai.background_removal"
    }

    @Suppress("unused")
    private fun PixelBuffer.opaqueCount() = pixels.count { it ushr 24 == 0xFF }
}
