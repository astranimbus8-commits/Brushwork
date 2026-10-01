package com.brushwork.paint.exchange

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.BrushPreset
import com.brushwork.paint.brush.BrushTip
import com.brushwork.paint.brush.StrokeRaster
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.exchange.export.StrokeEnvelopeExport
import com.brushwork.paint.tools.vector.toAndroidPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.cos
import kotlin.math.sin

/**
 * v1.5 §4.10b (A8): the outline of a solid brush stroke matches the replayed stroke — it covers
 * the stroke's visible pixels and reaches at most about a pixel beyond them.
 */
@RunWith(RobolectricTestRunner::class)
class StrokeEnvelopeRobolectricTest {
    private val w = 400
    private val h = 300

    /** A wavy stroke; with [stylus] the pressure rises and falls. */
    private fun points(stylus: Boolean): PackedPoints {
        val n = 40
        val xs = FloatArray(n) { 40f + it * 8f }
        val ys = FloatArray(n) { 150f + 60f * sin(it / 6.0).toFloat() }
        val ps = FloatArray(n) { if (stylus) 0.3f + 0.7f * sin(Math.PI * it / (n - 1)).toFloat() else 1f }
        return PackedPoints(xs, ys, ps)
    }

    private fun alpha(b: Bitmap): IntArray = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }.map { it ushr 24 }.toIntArray()

    private fun check(preset: BrushPreset, stylus: Boolean) {
        val pts = points(stylus)
        val stroke = BitmapUtils.createLayerBitmap(w, h)
        StrokeRaster(TipCache()).render(Canvas(stroke), Rect(0, 0, w, h), preset, 0xFF000000.toInt(), 5L, stylus, pts, cut = Rect(0, 0, w, h))
        val env = StrokeEnvelopeExport.outline(preset, stylus, 5L, pts)
        assertNotNull("${preset.name}: solid", env)
        val filled = BitmapUtils.createLayerBitmap(w, h)
        Canvas(filled).drawPath(env!!.toAndroidPath(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() })
        val s = alpha(stroke)
        val e = alpha(filled)
        val full = (preset.opacity * 255f).toInt()
        // Covered: no clearly painted pixel of the stroke is outside the outline.
        var painted = 0
        var missed = 0
        for (i in s.indices) if (s[i] >= full / 2) { painted++; if (e[i] == 0) missed++ }
        assertTrue("${preset.name} stylus=$stylus: $missed of $painted missed", painted > 500 && missed <= painted / 200)
        // The same area: summed coverage (the stroke's relative to its opacity) within 4 %.
        val sumS = s.sumOf { it.toDouble() } / full
        val sumE = e.sumOf { it.toDouble() } / 255.0
        assertTrue("${preset.name} stylus=$stylus: area $sumE vs $sumS", kotlin.math.abs(sumE - sumS) <= 0.04 * sumS)
        // Tight: outline pixels with no stroke within one pixel are rare.
        var inside = 0
        var beyond = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            if (e[i] < 128) continue
            inside++
            var near = false
            for (dy in -1..1) for (dx in -1..1) if (s[i + dy * w + dx] >= full / 4) near = true
            if (!near) beyond++
        }
        assertTrue("${preset.name} stylus=$stylus: $beyond of $inside beyond", beyond <= inside / 100)
    }

    @Test
    fun solidBrushOutlinesMatchTheirStrokes() {
        val pen = BrushLibrary.defaultBrush.copy(size = 14f)
        check(pen, stylus = false)
        check(pen, stylus = true)
        val gPen = BrushLibrary.all.first { it.id == "gpen" }.copy(size = 18f)
        check(gPen, stylus = false) // finger tapers at both ends
        val calligraphy = BrushLibrary.all.first { it.id == "calligraphy" }
        check(calligraphy, stylus = false)
        check(calligraphy, stylus = true)
        val marker = BrushLibrary.all.first { it.id == "marker" }
        check(marker, stylus = false)
        val square = BrushPreset("sq", "Square", BrushTip.SQUARE, size = 16f, hardness = 1f, spacing = 0.05f, angle = 20f, roundness = 0.6f, pressureSize = false)
        check(square, stylus = false)
    }

    @Test
    fun texturedAndSoftBrushesAreNotOutlined() {
        val pts = points(false)
        for (id in listOf("pencil", "airbrush", "softround", "chalk", "spray", "watercolor", "pixelpen", "feltpen")) {
            val p = BrushLibrary.all.first { it.id == id }
            assertNull(id, StrokeEnvelopeExport.outline(p, false, 1L, pts))
        }
        assertTrue(StrokeEnvelopeExport.isSolid(BrushLibrary.defaultBrush))
    }

    @Test
    fun theFillColorIsTheStrokeOpacity() {
        val marker = BrushLibrary.all.first { it.id == "marker" }
        // Marker opacity 0.55, object opacity 0.5: alpha 70; the colour's own alpha is not used.
        assertEquals((70 shl 24) or 0x123456, StrokeEnvelopeExport.fillColor(0x80123456.toInt(), marker, 0.5f))
        // Few points are kept on straight runs.
        val n = 200
        val pts = PackedPoints(FloatArray(n) { 20f + it }, FloatArray(n) { 100f + 0f * cos(it.toDouble()).toFloat() }, FloatArray(n) { 1f })
        val env = StrokeEnvelopeExport.outline(BrushLibrary.defaultBrush.copy(size = 10f, taperStart = 0f, taperEnd = 0f), false, 1L, pts)!!
        assertTrue("${env.ops.size} ops", env.ops.size < 40)
    }
}
