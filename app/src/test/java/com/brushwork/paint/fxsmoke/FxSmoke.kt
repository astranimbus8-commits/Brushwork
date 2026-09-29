package com.brushwork.paint.fxsmoke

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Looper
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.filters.CurvePoint
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterSession
import com.brushwork.paint.filters.FilterValues
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.ui.filters.CurveEditing
import com.brushwork.paint.ui.filters.GradientEditing
import com.brushwork.paint.ui.filters.SliderFormat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import java.util.Collections
import kotlin.math.abs

/**
 * Shared harness of the fxsmoke tests: the app's real coroutine scope (SupervisorJob +
 * Main.immediate, as in EditorSession) with a handler that records exceptions escaping it, test
 * documents, looper pumping and a few pixel helpers.
 */
internal class RecordingScope {
    val errors: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e -> errors += e })

    fun assertNoErrors(what: String) {
        if (errors.isEmpty()) return
        val first = errors.first()
        throw AssertionError("$what: ${errors.size} exception(s) escaped controller.scope: $first", first)
    }

    fun close() = scope.cancel()
}

internal object Fx {
    const val W = 64
    const val H = 48
    /** Drawing color used by every test (distinct from every filter default). */
    const val DRAW_COLOR = 0xFF2BA84A.toInt()
    /** A second color for Color parameters (distinct from DRAW_COLOR and the defaults). */
    const val ALT_COLOR = 0xFFD0306A.toInt()
    const val ALT_COLOR_2 = 0xFF3050E0.toInt()

    /** Opaque, colorful: gradients plus saturated blocks and a dark diagonal band. */
    fun paintPixels(w: Int = W, h: Int = H): IntArray = IntArray(w * h) { i ->
        val x = i % w; val y = i / w
        var r = x * 255 / (w - 1); var g = y * 255 / (h - 1); var b = ((x + y) * 13) % 256
        if (x in w / 8 until w / 3 && y in h / 6 until h / 2) { r = 230; g = 40; b = 30 }
        if (x in w / 2 until 3 * w / 4 && y in h / 2 until 5 * h / 6) { r = 20; g = 70; b = 220 }
        if (abs(x - y) < 3) { r /= 5; g /= 5; b /= 5 }
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Transparent line art: anti-aliased dark strokes, a colored ring and a semi-transparent stroke. */
    fun drawLineArt(bmp: Bitmap) {
        val c = Canvas(bmp)
        val w = bmp.width.toFloat(); val h = bmp.height.toFloat()
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = 0xFF151515.toInt(); strokeCap = Paint.Cap.ROUND }
        c.drawLine(w * 0.08f, h * 0.9f, w * 0.9f, h * 0.12f, ink)
        c.drawLine(w * 0.1f, h * 0.2f, w * 0.45f, h * 0.85f, ink)
        ink.color = 0xFFE05020.toInt(); ink.strokeWidth = 3f
        c.drawOval(RectF(w * 0.55f, h * 0.2f, w * 0.9f, h * 0.7f), ink)
        ink.color = 0x803050FF.toInt(); ink.strokeWidth = 5f
        c.drawLine(w * 0.05f, h * 0.5f, w * 0.95f, h * 0.55f, ink)
    }

    class TestDoc(val controller: EditorController, val paint: Layer, val lineArt: Layer?)

    /** [w]x[h] document: "Paint" (opaque, colorful) at the bottom and "Line art" (transparent) above. */
    fun newDoc(context: Context, scope: CoroutineScope, w: Int = W, h: Int = H, withLineArt: Boolean = true): TestDoc {
        val doc = Document("fx", "fx", w, h)
        val paint = Layer(doc.newLayerId(), "Paint", BitmapUtils.createLayerBitmap(w, h))
        paint.bitmap.setPixels(paintPixels(w, h), 0, w, 0, 0, w, h)
        doc.layers += paint
        var line: Layer? = null
        if (withLineArt) {
            line = Layer(doc.newLayerId(), "Line art", BitmapUtils.createLayerBitmap(w, h))
            drawLineArt(line.bitmap)
            doc.layers += line
        }
        doc.activeLayerIndex = 0
        val c = EditorController(context, doc, scope, AppSettings(context))
        c.color = DRAW_COLOR
        return TestDoc(c, paint, line)
    }

    fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** [buf] as it reads back after being written into an ARGB_8888 (premultiplied) bitmap. */
    fun roundTrip(buf: PixelBuffer): IntArray {
        val b = BitmapUtils.fromPixelBuffer(buf)
        try { return pixels(b) } finally { b.recycle() }
    }

    /** What the filter produces directly (no session) on [src], read back through a bitmap. */
    fun direct(filter: Filter, src: PixelBuffer, values: FilterValues, scale: Float = 1f, dpi: Float = 350f): PixelBuffer =
        filter.apply(src, values.copy(), FilterContext(scale = scale, dpi = dpi))

    /** The layer content as the filter preview draws it (renderOverride.drawContent), doc-sized. */
    fun overrideContent(c: EditorController): IntArray? {
        val ov = c.renderOverride ?: return null
        val out = BitmapUtils.createLayerBitmap(c.doc.width, c.doc.height)
        try {
            if (!ov.drawContent(Canvas(out))) return null
            return pixels(out)
        } finally { out.recycle() }
    }

    /** The mask as the filter preview draws it (renderOverride.drawMask with a plain paint). */
    fun overrideMask(c: EditorController): IntArray? {
        val ov = c.renderOverride ?: return null
        val out = BitmapUtils.createLayerBitmap(c.doc.width, c.doc.height)
        try {
            if (!ov.drawMask(Canvas(out), Paint())) return null
            return pixels(out)
        } finally { out.recycle() }
    }

    /** Pumps the main looper (advancing its clock) until [cond] holds. */
    fun waitUntil(what: String, timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val looper = shadowOf(Looper.getMainLooper())
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) fail("Timed out waiting for $what")
            looper.idleFor(Duration.ofMillis(10))
            Thread.sleep(1)
        }
        looper.idle()
    }

    /** Advances the main looper clock by [ms] in 10 ms steps (lets background work finish too). */
    fun pump(ms: Long) {
        val looper = shadowOf(Looper.getMainLooper())
        var t = 0L
        while (t < ms) { looper.idleFor(Duration.ofMillis(10)); Thread.sleep(1); t += 10 }
    }

    /** Renders the preview for the current values (asks for it if the filter has no live preview) and waits. */
    fun awaitPreview(s: FilterSession, what: String) {
        if (!s.filter.livePreview && s.previewStale) s.renderPreview()
        waitUntil("preview of ${s.filter.id} ($what)") { s.isClosed || (!s.isRendering && !s.previewStale) }
        assertTrue("${s.filter.id}: session closed while previewing ($what)", !s.isClosed)
    }

    /** Applies and waits for the busy job to finish. */
    fun applyAndWait(s: FilterSession) {
        val c = s.controller
        s.apply()
        waitUntil("apply of ${s.filter.id}") { !s.isApplying && c.busyMessage == null }
    }

    /** Fails if the controller shows an error message (filter failed / out of memory). */
    fun assertNoErrorMessage(c: EditorController, what: String) {
        val m = c.message ?: return
        if (m.contains("failed", ignoreCase = true) || m.contains("memory", ignoreCase = true) || m.contains("cancelled", ignoreCase = true)) {
            fail("$what: error message \"$m\"")
        }
    }

    /** A valid value different from [p]'s default (null for Point parameters, which are dragged). */
    fun nonDefault(p: FilterParam): Any? = when (p) {
        is FilterParam.Slider -> {
            val range = p.max - p.min
            // Pixel distances stay at the scale of the small test documents (half the default, or a
            // few pixels); other values move by a third of their range.
            var v = if (p.pixels) {
                SliderFormat.snap(p, if (p.default > p.min) p.default * 0.5f else p.min + maxOf(3f, p.step))
            } else {
                SliderFormat.snap(p, if (p.default + range * 0.35f <= p.max) p.default + range * 0.35f else p.default - range * 0.35f)
            }
            if (v == p.default) v = SliderFormat.snap(p, p.default * 1.5f)
            if (v == p.default) v = if (p.default != p.max) p.max else p.min
            v
        }
        is FilterParam.Toggle -> !p.default
        is FilterParam.Choice -> if (p.options.size > 1) (p.default + 1) % p.options.size else p.default
        is FilterParam.Color -> if (p.default != ALT_COLOR) ALT_COLOR else ALT_COLOR_2
        is FilterParam.Curve -> CurveEditing.add(CurveEditing.normalized(p.default), 0.35f, 0.7f)?.first
            ?: listOf(CurvePoint(0f, 1f), CurvePoint(1f, 0f))
        is FilterParam.Gradient -> {
            val rev = GradientEditing.reverse(p.default)
            if (rev != GradientEditing.sorted(p.default)) rev else GradientEditing.presets.first().second
        }
        is FilterParam.Text -> "Brushwork 42"
        is FilterParam.Seed -> p.default + 17
        is FilterParam.Point -> null
    }

    /** Normalized target of the [index]-th point parameter. */
    fun pointTarget(index: Int): FloatArray = floatArrayOf(0.3f + 0.2f * (index % 3), 0.7f - 0.25f * (index % 3))

    /**
     * Drags point parameter [key] from its handle to [target] (normalized) through the controller's
     * canvas input (as the canvas view does), with the identity view transform.
     */
    fun dragPoint(c: EditorController, s: FilterSession, key: String, target: FloatArray) {
        val (x0, y0) = s.pointPosition(key)
        val w = c.doc.width.toFloat(); val h = c.doc.height.toFloat()
        c.pointerDown(ToolPoint(x0, y0))
        assertTrue("${s.filter.id}: point '$key' should be grabbed, dragging ${s.draggingPoint}", s.draggingPoint == key)
        c.pointerMove(ToolPoint((x0 + target[0] * w) / 2f, (y0 + target[1] * h) / 2f))
        c.pointerMove(ToolPoint(target[0] * w, target[1] * h))
        c.pointerUp(ToolPoint(target[0] * w, target[1] * h))
        val v = s.values.point(key)
        assertTrue("${s.filter.id}: point '$key' is at ${v.toList()}, expected ${target.toList()}",
            abs(v[0] - target[0]) < 1.5f / w && abs(v[1] - target[1]) < 1.5f / h)
    }

    /** Sets every parameter to [nonDefault] (points are dragged on the canvas). */
    fun setNonDefaults(c: EditorController, s: FilterSession) {
        var pointIndex = 0
        for (p in s.filter.params) {
            if (p is FilterParam.Point) dragPoint(c, s, p.key, pointTarget(pointIndex++))
            else s.update(p.key, nonDefault(p)!!)
        }
    }

    /** The value a fresh session must start with for [p] (drawing color for useDrawingColor colors). */
    fun sessionDefault(p: FilterParam, drawColor: Int): Any =
        if (p is FilterParam.Color && p.useDrawingColor) drawColor else p.defaultValue()

    fun sameValue(a: Any?, b: Any?): Boolean = when {
        a is FloatArray && b is FloatArray -> a.contentEquals(b)
        a is Number && b is Number -> a.toFloat() == b.toFloat()
        else -> a == b
    }

    /** Asserts that every value of [s] is the session default. */
    fun assertSessionDefaults(s: FilterSession, drawColor: Int, what: String) {
        for (p in s.filter.params) {
            val expected = sessionDefault(p, drawColor)
            val actual = s.values.raw(p.key)
            assertTrue("${s.filter.id} ($what): '${p.key}' is ${show(actual)}, expected ${show(expected)}", sameValue(expected, actual))
        }
    }

    fun show(v: Any?): String = when (v) {
        is FloatArray -> v.toList().toString()
        is Int -> if (v.toLong() and 0xFF000000L != 0L) "0x" + Integer.toHexString(v) else v.toString()
        else -> v.toString()
    }

    /** Number of pixels that differ, plus the first difference, for failure messages. */
    fun diff(expected: IntArray, actual: IntArray, w: Int): String {
        var n = 0; var first = -1
        for (i in expected.indices) if (expected[i] != actual[i]) { n++; if (first < 0) first = i }
        if (n == 0) return "no difference"
        return "$n pixels differ, first at (${first % w},${first / w}): expected 0x${Integer.toHexString(expected[first])} got 0x${Integer.toHexString(actual[first])}"
    }

    /** Max per-channel difference between two premultiplied-roundtripped pixel arrays. */
    fun maxChannelDiff(a: IntArray, b: IntArray): Int {
        var m = 0
        for (i in a.indices) {
            val x = a[i]; val y = b[i]
            if (x == y) continue
            for (sh in intArrayOf(24, 16, 8, 0)) m = maxOf(m, abs(((x ushr sh) and 0xFF) - ((y ushr sh) and 0xFF)))
        }
        return m
    }
}
