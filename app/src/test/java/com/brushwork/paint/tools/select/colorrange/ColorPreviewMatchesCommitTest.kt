package com.brushwork.paint.tools.select.colorrange

import android.graphics.Canvas
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.SelectionMode
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.select.ColorSelectSettings
import com.brushwork.paint.tools.select.SampleSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import kotlin.math.abs

/**
 * v1.8 (C, design §3.5 c): what the red preview shows is what the strip's check selects. On a
 * 2560 × 1600 document (the proxy is exactly half: 1280 × 800) of flat swatches on even
 * boundaries over a slow red gradient, the committed full-resolution mask, averaged over each
 * 2 × 2 block, is within 2/255 of `ColorSelectTool.preview.mask`, for samples, presets, the
 * invert option, anti-alias off and Refer: canvas. `preview.version` advances with every change.
 */
@RunWith(RobolectricTestRunner::class)
class ColorPreviewMatchesCommitTest {

    private val w = 2560
    private val h = 1600
    private val half = 800

    private val swatches = intArrayOf(
        0xFFE01010.toInt(), 0xFFD82020.toInt(), 0xFFC83838.toInt(), 0xFFB05050.toInt(),
        0xFFFF8000.toInt(), 0xFFFFFF00.toInt(), 0xFF00C000.toInt(), 0xFF00FFFF.toInt(),
        0xFF1030D0.toInt(), 0xFFFF00FF.toInt(), 0xFFE0AC69.toInt(), 0xFF777777.toInt(),
        0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0x80E01010.toInt(), 0x00000000,
    )

    private fun setUp(): Pair<EditorController, ColorSelectTool> {
        ShadowLog.stream = null
        val doc = Smoke.document(w, h)
        val bmp = doc.layers[0].bitmap
        val canvas = Canvas(bmp)
        val paint = Paint()
        // Top: 16 flat swatches, 160 px wide.
        for ((i, col) in swatches.withIndex()) {
            paint.color = col
            canvas.drawRect(i * 160f, 0f, (i + 1) * 160f, half.toFloat(), paint)
        }
        // Bottom: reds from 255 down to 96, one level every 16 px.
        val row = IntArray(w) { x -> 0xFF000000.toInt() or ((255 - x / 16) shl 16) or (0x28 shl 8) or 0x28 }
        for (y in half until h) bmp.setPixels(row, 0, w, 0, y, w, 1)
        doc.activeLayerIndex = 0
        val c = Smoke.controller(ApplicationProvider.getApplicationContext(), doc)
        val tool = c.tools.getValue(ToolId.COLOR_SELECT) as ColorSelectTool
        c.selectTool(ToolId.COLOR_SELECT)
        assertTrue("the proxy is built", Smoke.pumpUntil { tool.proxyReady && tool.idle && tool.preview != null })
        return c to tool
    }

    /** Applies [change], waits for the new preview, commits it and compares over [rows] (proxy rows). */
    private fun check(c: EditorController, t: ColorSelectTool, what: String, rows: IntRange = 0 until h / 2, change: () -> Unit) {
        val before = t.preview?.version ?: 0
        change()
        assertTrue("$what: a new preview", Smoke.pumpUntil { (t.preview?.version ?: 0) > before && t.idle })
        val pv = t.preview!!
        assertEquals(w / 2, pv.width)
        assertEquals(h / 2, pv.height)
        t.mode = SelectionMode.REPLACE
        t.selectColors()
        assertTrue("$what: selected", Smoke.pumpUntil { t.idle })
        val full = c.selection?.toBytes() ?: ByteArray(w * h)
        var worst = 0
        var at = ""
        var partial = 0
        for (y in rows) for (x in 0 until pv.width) {
            val i = 2 * y * w + 2 * x
            val sum = (full[i].toInt() and 0xFF) + (full[i + 1].toInt() and 0xFF) + (full[i + w].toInt() and 0xFF) + (full[i + w + 1].toInt() and 0xFF)
            val p = pv.mask[y * pv.width + x].toInt() and 0xFF
            if (p in 1..254) partial++
            val d = abs((sum + 2) / 4 - p)
            if (d > worst) { worst = d; at = "($x, $y): full ${(sum + 2) / 4}, preview $p" }
        }
        assertTrue("$what: worst difference $worst at $at", worst <= 2)
        // The full-resolution mask isn't all one value: the comparison means something.
        assertTrue("$what: something selected", full.any { it.toInt() != 0 })
        if (what.startsWith("soft")) assertTrue("$what: partial membership shows", partial > 0)
    }

    private fun ColorSelectTool.set(f: (ColorSelectSettings) -> ColorSelectSettings) = updateSettings(f(settings))

    @Test
    fun thePreviewIsWhatIsSelected() {
        val (c, t) = setUp()
        check(c, t, "soft samples") { t.addSample(swatches[0]) }
        check(c, t, "soft samples, two") { t.addSample(swatches[10]) }
        check(c, t, "soft reds") { t.set { it.copy(preset = ColorPreset.REDS, fuzziness = 0) } }
        check(c, t, "soft skin, inverted") { t.set { it.copy(preset = ColorPreset.SKIN, fuzziness = 100, invert = true) } }
        check(c, t, "soft highlights") { t.set { it.copy(preset = ColorPreset.HIGHLIGHTS, fuzziness = 40, invert = false) } }
        // Anti-alias off: in or out; the flat swatches only (on the gradient a 2 x 2 block may straddle the cut).
        check(c, t, "hard vivid", 0 until half / 2) { t.set { it.copy(preset = ColorPreset.VIVID, antiAlias = false) } }
        check(c, t, "soft canvas") { t.set { it.copy(preset = ColorPreset.SAMPLED, antiAlias = true, source = SampleSource.CANVAS) } }
        // A sample added and taken back: the preview follows each step.
        check(c, t, "soft undo") { t.addSample(swatches[4]); c.undo() }
        assertNotNull(t.preview)
        assertTrue(t.preview!!.version > 8)
        Smoke.assertQuiet(c, "color preview")
    }
}
