package com.brushwork.paint.tools.select.colorrange

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.select.SelectionMasks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 (C, design §3.5 c budgets, §6.3 Q8): Color select's costs on photo-like pixels (smooth
 * gradients with fine noise, so nearly every pixel is a color of its own). The Lab cache of a
 * 1024 × 1280 proxy (the proxy of a 4000 × 5000 canvas) builds within 250 ms; a preview (the
 * proxy's membership and its overlay bitmap) within 120 ms for eight samples at the widest
 * fuzziness and for the presets; the tool's own preview follows a change within 120 ms; the
 * selection of a 4000 × 5000 canvas (the full-resolution mask and the selection made of it)
 * within 1.5 s; the proxy holds at most 16 MB (its Lab at most 10 MB) at the largest, 1280².
 * The T606's budgets, checked on the desktop JVM (×4 on CI).
 */
@RunWith(RobolectricTestRunner::class)
@Category(Timing::class)
class ColorSelectPerfTest {

    /** A photo-like pixel: three slow gradients and 0..15 of noise. */
    private fun photo(x: Int, y: Int, w: Int, h: Int): Int {
        var n = x * 73856093 xor y * 19349663
        n = (n xor (n ushr 13)) * 0x5bd1e995
        val noise = (n ushr 28) and 15
        val r = (x * 239 / w + noise).coerceAtMost(255)
        val g = (y * 239 / h + noise).coerceAtMost(255)
        val b = ((x + y) * 239 / (w + h) + (15 - noise)).coerceAtMost(255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun pixels(w: Int, h: Int): IntArray = IntArray(w * h) { i -> photo(i % w, i / w, w, h) }

    private fun bitmap(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val row = IntArray(w)
        for (y in 0 until h) {
            for (x in 0 until w) row[x] = photo(x, y, w, h)
            bmp.setPixels(row, 0, w, 0, y, w, 1)
        }
        return bmp
    }

    private val pw = 1024
    private val ph = 1280
    private val proxy by lazy { requireNotNull(ColorMasks.proxy(pixels(pw, ph), pw, ph)) }

    /** Eight samples spread over the picture. */
    private val samples = IntArray(8) { k -> photo(100 + k * 110, 150 + k * 130, pw, ph) }

    @Test
    fun theFirstLabCacheWithin250Ms() {
        val bmp = bitmap(pw, ph)
        PerfBudget.within(PerfBudget.ms(250.0), what = "Lab cache 1024 × 1280") {
            requireNotNull(ColorMasks.proxy(bmp))
        }
        bmp.recycle()
    }

    @Test
    fun aPreviewWithin120Ms() {
        val cases = listOf(
            "8 samples, fuzziness 200" to ColorRange.Params.of(ColorPreset.SAMPLED, 200, samples, invert = false, antiAlias = true),
            "8 samples, fuzziness 40, inverted" to ColorRange.Params.of(ColorPreset.SAMPLED, 40, samples, invert = true, antiAlias = true),
            "reds" to ColorRange.Params.of(ColorPreset.REDS, 40, IntArray(0), invert = false, antiAlias = true),
            "skin" to ColorRange.Params.of(ColorPreset.SKIN, 100, IntArray(0), invert = false, antiAlias = true),
            "vivid" to ColorRange.Params.of(ColorPreset.VIVID, 40, IntArray(0), invert = false, antiAlias = true),
            "midtones" to ColorRange.Params.of(ColorPreset.MIDTONES, 40, IntArray(0), invert = false, antiAlias = true),
        )
        val px = proxy
        for ((what, p) in cases) {
            PerfBudget.within(PerfBudget.ms(120.0), what = "preview 1024 × 1280, $what") {
                val mask = requireNotNull(ColorMasks.previewMask(px, p))
                BitmapUtils.bytesToAlpha8(mask, px.width, px.height).recycle()
            }
        }
    }

    @Test
    fun theToolsPreviewFollowsAChangeWithin120Ms() {
        ShadowLog.stream = null
        // A 2560 × 3200 canvas: its proxy is 1024 × 1280.
        val doc = Smoke.document(2560, 3200)
        val bmp = doc.layers[0].bitmap
        val row = IntArray(2560)
        for (y in 0 until 3200) {
            for (x in 0 until 2560) row[x] = photo(x, y, 2560, 3200)
            bmp.setPixels(row, 0, 2560, 0, y, 2560, 1)
        }
        doc.activeLayerIndex = 0
        val c = Smoke.controller(ApplicationProvider.getApplicationContext(), doc)
        val tool = c.tools.getValue(ToolId.COLOR_SELECT) as ColorSelectTool
        c.selectTool(ToolId.COLOR_SELECT)
        assertTrue(Smoke.pumpUntil { tool.proxyReady && tool.idle && tool.preview != null })
        assertEquals(pw, tool.preview!!.width)
        assertEquals(ph, tool.preview!!.height)
        for (k in 0 until 8) tool.addSample(samples[k])
        assertTrue(Smoke.pumpUntil { tool.idle })
        var fuzziness = 40
        PerfBudget.measuredWithin(PerfBudget.ms(120.0), what = "the tool's preview after a fuzziness change") {
            val before = tool.preview!!.version
            fuzziness = if (fuzziness >= 190) 40 else fuzziness + 10
            val t0 = System.nanoTime()
            tool.updateSettings(tool.settings.copy(fuzziness = fuzziness))
            assertTrue(Smoke.pumpUntil { (tool.preview?.version ?: 0) > before && tool.idle })
            (System.nanoTime() - t0) / 1e6
        }
        c.selectTool(ToolId.MAGIC_WAND)
        Smoke.assertQuiet(c, "color select perf")
    }

    @Test
    fun theSelectionOf4000x5000Within1500Ms() {
        val w = 4000
        val h = 5000
        val bmp = bitmap(w, h)
        val p = ColorRange.Params.of(ColorPreset.REDS, 40, IntArray(0), invert = false, antiAlias = true)
        PerfBudget.within(PerfBudget.ms(1500.0), tries = 3, what = "select colors 4000 × 5000") {
            val bytes = requireNotNull(ColorMasks.fullMask(bmp, p))
            SelectionMasks.fromWindow(bytes, 0, 0, w, h, w, h)
        }
        val bytes = requireNotNull(ColorMasks.fullMask(bmp, p))
        // Each pixel is its own membership; some are in, some out.
        var inside = 0
        var outside = 0
        for (y in 0 until h step 97) for (x in 0 until w step 89) {
            val v = bytes[y * w + x].toInt() and 0xFF
            assertEquals("($x, $y)", ColorMasks.member(photo(x, y, w, h), p), v)
            if (v > 200) inside++ else if (v == 0) outside++
        }
        assertTrue("$inside in, $outside out", inside > 0 && outside > 0)
        bmp.recycle()
    }

    @Test
    fun theProxyHoldsAtMost16Mb() {
        val n = ColorMasks.PROXY_MAX
        val px = requireNotNull(ColorMasks.proxy(pixels(n, n), n, n))
        val lab = (px.l.size + px.a.size + px.b.size) * 2L
        assertTrue("Lab: $lab bytes", lab <= 10L * 1024 * 1024)
        assertTrue("the proxy: ${px.bytes} bytes", px.bytes <= 16L * 1024 * 1024)
    }
}
