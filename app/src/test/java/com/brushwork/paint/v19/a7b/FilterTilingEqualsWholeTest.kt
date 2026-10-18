package com.brushwork.paint.v19.a7b

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.ColorModeOps
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.filters.FilterSessionMath
import com.brushwork.paint.filters.FilterTiling
import com.brushwork.paint.filters.PixelRect
import com.brushwork.paint.fxsmoke.Fx
import com.brushwork.paint.fxsmoke.RecordingScope
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Selection
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer
import kotlin.random.Random

/**
 * v1.9 A7b step 3 (design 3.2 A7b, R9): a local filter's apply runs in bands ([FilterTiling]) and
 * commits exactly the bytes v1.8's whole-image apply commits. Every filter with a pixel mapper is
 * applied twice with its non-default values, tiled and whole ([FilterTiling.enabled] off), on the
 * same document: plain, through a soft selection (its region is not at the origin), on an
 * alpha-locked layer, on a layer mask, in a grayscale document and in a monochrome one through
 * the selection. The bands are 7 rows (the last one shorter), so every case crosses several. The
 * layer's stored (premultiplied) bytes, the mask's, the step count and the message must match,
 * and undo gives back the layer as it was.
 */
@RunWith(RobolectricTestRunner::class)
class FilterTilingEqualsWholeTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val rs = RecordingScope()

    private enum class Case { PLAIN, SELECTION, ALPHA_LOCK, MASK, GRAYSCALE, MONOCHROME_SELECTION }

    private class Outcome(val layer: ByteArray, val mask: ByteArray?, val steps: Int, val message: String?)

    @Before
    fun setUp() {
        FilterTiling.bandPixels = Fx.W * 7
    }

    @After
    fun tearDown() {
        FilterTiling.enabled = true
        FilterTiling.bandPixels = FilterTiling.BAND_PIXELS
        rs.close()
    }

    private val local: List<Filter> get() = FilterRegistry.all.filter { FilterTiling.mapperFor(it, it.defaultValues()) != null }

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

    /** Whether [f] keeps its pixel mapper with [Fx.setNonDefaults]'s values. */
    private fun nonDefaultsAreLocal(f: Filter): Boolean {
        val v = f.defaultValues()
        for (p in f.params) if (p !is FilterParam.Point) Fx.nonDefault(p)?.let { v.set(p.key, it) }
        return f.pixelMapper(v) != null
    }

    private fun raw(b: Bitmap): ByteArray = ByteBuffer.allocate(b.byteCount).also { b.copyPixelsToBuffer(it) }.array()

    private fun applyOnce(f: Filter, case: Case, tiled: Boolean): Outcome {
        FilterTiling.enabled = tiled
        val d = Fx.newDoc(context, rs.scope)
        val c = d.controller
        try {
            val layer = if (case == Case.ALPHA_LOCK) d.lineArt!!.also { it.alphaLocked = true } else d.paint
            when (case) {
                Case.SELECTION -> c.setSelection(Selection.fromBytes(softSelection(Fx.W, Fx.H), Fx.W, Fx.H), recordUndo = false)
                Case.MASK -> {
                    val mask = BitmapUtils.createMaskBitmap(Fx.W, Fx.H)
                    Canvas(mask).drawRect(0f, 0f, Fx.W.toFloat(), Fx.H.toFloat(), Paint().apply {
                        shader = LinearGradient(0f, 0f, Fx.W.toFloat(), 0f, 0xFF202020.toInt(), 0xFFF0F0F0.toInt(), Shader.TileMode.CLAMP)
                    })
                    Canvas(mask).drawCircle(40f, 20f, 9f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() })
                    layer.mask = mask
                    layer.editingMask = true
                }
                Case.GRAYSCALE -> c.doc.colorMode = ColorMode.GRAYSCALE
                Case.MONOCHROME_SELECTION -> {
                    c.doc.colorMode = ColorMode.MONOCHROME
                    c.setSelection(Selection.fromBytes(softSelection(Fx.W, Fx.H), Fx.W, Fx.H), recordUndo = false)
                }
                else -> {}
            }
            val before = raw(layer.bitmap)
            val maskBefore = layer.mask?.let(::raw)
            c.selectLayer(layer)
            c.startFilter(f)
            val s = c.filterSession ?: throw AssertionError("no session: ${c.message}")
            s.debounceMs = 0
            if (case == Case.MASK) assertEquals(EditTarget.MASK, s.target)
            // Some settings make a filter content-dependent (an automatic level): those keep the
            // whole path, so such a filter is applied with its defaults here.
            if (nonDefaultsAreLocal(f)) Fx.setNonDefaults(c, s)
            Fx.awaitPreview(s, "$case")
            val bands = FilterTiling.bandsRun
            val expectBands = tiled && FilterTiling.mapperFor(f, s.values) != null
            assertEquals("${f.id} $case: tiled when local", tiled, expectBands)
            Fx.applyAndWait(s)
            Fx.assertNoErrorMessage(c, "${f.id} $case")
            if (tiled) assertTrue("${f.id} $case: the tiled path ran", FilterTiling.bandsRun > bands)
            else assertEquals("${f.id} $case: the whole path ran", bands, FilterTiling.bandsRun)
            val out = Outcome(raw(layer.bitmap), layer.mask?.let(::raw), c.undoManager.undoCount, c.message)
            if (out.steps > 0) {
                c.undo()
                assertArrayEquals("${f.id} $case: undo gives the layer back", before, raw(layer.bitmap))
                if (maskBefore != null) assertArrayEquals("${f.id} $case: undo gives the mask back", maskBefore, raw(layer.mask!!))
            }
            return out
        } finally {
            c.filterSession?.cancel()
            c.dispose()
        }
    }

    @Test
    fun everyLocalFilterCommitsTheWholeImageBytes() {
        assertTrue("the local filters: ${local.map { it.id }}", local.size >= 12)
        val problems = ArrayList<String>()
        var applied = 0
        for (f in local) for (case in Case.values()) {
            try {
                val whole = applyOnce(f, case, tiled = false)
                val tiled = applyOnce(f, case, tiled = true)
                rs.assertNoErrors("${f.id} $case")
                assertEquals("steps", whole.steps, tiled.steps)
                assertEquals("message", whole.message, tiled.message)
                assertTrue("layer bytes", whole.layer.contentEquals(tiled.layer))
                assertTrue("mask bytes", (whole.mask == null && tiled.mask == null) || whole.mask!!.contentEquals(tiled.mask!!))
                if (case == Case.PLAIN && nonDefaultsAreLocal(f)) assertEquals("one step", 1, tiled.steps)
                applied += tiled.steps
            } catch (e: Throwable) {
                problems += "${f.id} $case: ${e.message}"
                rs.errors.clear()
            }
        }
        if (problems.isNotEmpty()) fail("${problems.size} case(s) differ:\n" + problems.joinToString("\n"))
        assertTrue("most cases changed pixels: $applied", applied >= local.size * 3)
    }

    /**
     * The bands themselves, without a session: a region partly outside the layer, one-row and
     * odd-sized bands, a selection, alpha lock and a colour mode give the whole-image path's
     * pixels inside the region and the same changed bounds.
     */
    @Test
    fun bandsOfAnySizeGiveTheWholeImageResult() {
        val w = 37; val h = 23
        val rnd = Random(19)
        val src = PixelBuffer(w, h, IntArray(w * h) { i -> if (i % 7 == 0) 0 else rnd.nextInt() })
        val sel = ByteArray(w * h) { rnd.nextInt(256).toByte() }
        val f = FilterRegistry.byId("adjust.invert")!!
        val mapper = FilterTiling.mapperFor(f, f.defaultValues())!!
        val region = PixelRect(-5, 3, 30, 40)
        val r = PixelRect(0, 3, 30, h)
        for (mode in listOf(ColorMode.RGB, ColorMode.GRAYSCALE)) for (alphaLocked in listOf(false, true)) {
            // The whole-image path (FilterSession.runFilter's order).
            val out = PixelBuffer(w, h, src.pixels.copyOf())
            mapper.map(out.pixels, 0, out.size)
            FilterSessionMath.compose(src, out, sel, alphaLocked, false, r)
            if (mode != ColorMode.RGB) for (y in r.top until r.bottom) for (x in r.left until r.right) out[x, y] = ColorModeOps.constrainPixel(out[x, y], mode)
            val changed = FilterSessionMath.changedBounds(src, out, r)
            assertNotNull(changed)
            for (pixels in listOf(1, w, 3 * 30 + 7, 1 shl 20)) {
                FilterTiling.bandPixels = pixels
                val t = FilterTiling.run(w, h, { dst, x, y, cols, rows ->
                    for (row in 0 until rows) System.arraycopy(src.pixels, (y + row) * w + x, dst, row * cols, cols)
                }, mapper, sel, alphaLocked, false, mode, region)
                assertEquals("$mode $alphaLocked $pixels", r, t.region)
                assertEquals("$mode $alphaLocked $pixels", changed, t.changed)
                for (y in r.top until r.bottom) for (x in r.left until r.right) {
                    if (t.pixels[t.offsetOf(x, y)] != out[x, y]) fail("$mode $alphaLocked $pixels: ($x, $y)")
                }
            }
        }
        // A region outside the layer: nothing to do.
        assertEquals(null, FilterTiling.run(w, h, { _, _, _, _, _ -> fail("read") }, mapper, null, false, false, ColorMode.RGB, PixelRect(40, 0, 50, 10)).changed)
    }
}
