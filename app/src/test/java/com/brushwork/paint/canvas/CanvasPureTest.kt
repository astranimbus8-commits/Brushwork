package com.brushwork.paint.canvas

import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.engine.CanvasGeometry
import com.brushwork.paint.engine.CanvasOps
import com.brushwork.paint.engine.CanvasRotation
import com.brushwork.paint.engine.ColorModeConverter
import com.brushwork.paint.engine.IntArrayImage
import com.brushwork.paint.engine.ResampleKernel
import com.brushwork.paint.engine.Resampler
import com.brushwork.paint.engine.RowSink
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.GridSettings
import com.brushwork.paint.model.RulerSettings
import com.brushwork.paint.ui.canvas.CanvasAdjustMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Plain-JVM tests of the pure parts of the canvas module (geometry, resampler, color modes). */
class CanvasPureTest {

    // ------------------------------------------------------------------ geometry

    @Test
    fun anchorOffsets() {
        assertEquals(0, CanvasGeometry.anchorOffset(100, 140, 0))
        assertEquals(20, CanvasGeometry.anchorOffset(100, 140, 1))
        assertEquals(40, CanvasGeometry.anchorOffset(100, 140, 2))
        assertEquals(-3, CanvasGeometry.anchorOffset(10, 5, 1)) // floor(-2.5)
        assertEquals(-5, CanvasGeometry.anchorOffset(10, 5, 2))
        assertEquals(1, CanvasGeometry.anchorOffset(10, 13, 1))
    }

    @Test
    fun rotationsAndInverse() {
        val w = 30; val h = 20
        for (rot in CanvasRotation.entries) {
            val g = CanvasGeometry.rotate(rot, w, h)
            val inv = g.inverse()
            for ((x, y) in listOf(0.0 to 0.0, 7.5 to 3.25, 30.0 to 20.0)) {
                assertEquals(x, inv.mapX(g.mapX(x, y), g.mapY(x, y)), 1e-9)
                assertEquals(y, inv.mapY(g.mapX(x, y), g.mapY(x, y)), 1e-9)
            }
            // Corners of the old canvas land on corners of the new one.
            val newW = if (rot.quarterTurnsCw % 2 == 1) h else w
            val newH = if (rot.quarterTurnsCw % 2 == 1) w else h
            val xs = listOf(g.mapX(0.0, 0.0), g.mapX(w.toDouble(), 0.0), g.mapX(0.0, h.toDouble()), g.mapX(w.toDouble(), h.toDouble()))
            val ys = listOf(g.mapY(0.0, 0.0), g.mapY(w.toDouble(), 0.0), g.mapY(0.0, h.toDouble()), g.mapY(w.toDouble(), h.toDouble()))
            assertEquals(0.0, xs.min(), 1e-9); assertEquals(newW.toDouble(), xs.max(), 1e-9)
            assertEquals(0.0, ys.min(), 1e-9); assertEquals(newH.toDouble(), ys.max(), 1e-9)
        }
        // Clockwise: top-left goes to top-right.
        val cw = CanvasGeometry.rotate(CanvasRotation.CW_90, w, h)
        assertEquals(h.toDouble(), cw.mapX(0.0, 0.0), 1e-9)
        assertEquals(0.0, cw.mapY(0.0, 0.0), 1e-9)
        assertEquals(90f, cw.mapAngleDeg(0f), 1e-4f)
        assertEquals(-90f, CanvasGeometry.rotate(CanvasRotation.CCW_90, w, h).mapAngleDeg(0f), 1e-4f)
    }

    @Test
    fun rulerFollowsScaleShiftAndFlip() {
        val r = RulerSettings(enabled = true, centerX = 100f, centerY = 50f, angleDeg = 30f, radius = 40f, radiusX = 60f, radiusY = 20f)
        val scaled = CanvasGeometry.scale(0.5, 0.5).mapRuler(r, 1000, 1000)
        assertEquals(50f, scaled.centerX, 1e-4f); assertEquals(25f, scaled.centerY, 1e-4f)
        assertEquals(20f, scaled.radius, 1e-4f); assertEquals(30f, scaled.radiusX, 1e-4f); assertEquals(10f, scaled.radiusY, 1e-4f)
        assertEquals(30f, scaled.angleDeg, 1e-4f)

        val shifted = CanvasGeometry.translate(-60.0, 10.0).mapRuler(r, 500, 500)
        assertEquals(40f, shifted.centerX, 1e-4f); assertEquals(60f, shifted.centerY, 1e-4f)

        // Clamped into the new canvas.
        val clamped = CanvasGeometry.translate(-150.0, 0.0).mapRuler(r, 200, 200)
        assertEquals(0f, clamped.centerX, 1e-4f)

        val flipped = CanvasGeometry.flip(true, 400, 300).mapRuler(r, 400, 300)
        assertEquals(300f, flipped.centerX, 1e-4f)
        assertEquals(150f, flipped.angleDeg, 1e-3f)

        // Non-uniform scale changes the line angle.
        val stretched = CanvasGeometry.scale(2.0, 1.0).mapRuler(r.copy(angleDeg = 45f), 1000, 1000)
        assertEquals(Math.toDegrees(Math.atan2(1.0, 2.0)).toFloat(), stretched.angleDeg, 1e-3f)

        // An unplaced ruler is left alone.
        val unplaced = RulerSettings()
        assertEquals(unplaced, CanvasGeometry.scale(2.0, 2.0).mapRuler(unplaced, 10, 10))

        // Same coordinates but a smaller canvas (cropped at the top-left): only clamped.
        val kept = CanvasGeometry.IDENTITY.mapRuler(r, 60, 500)
        assertEquals(60f, kept.centerX, 1e-4f); assertEquals(50f, kept.centerY, 1e-4f)
        assertEquals(r.angleDeg, kept.angleDeg, 0f); assertEquals(r.radius, kept.radius, 0f)
    }

    @Test
    fun colorModeConversionNeeds() {
        assertTrue(CanvasOps.convertsPixels(ColorMode.RGB, ColorMode.GRAYSCALE))
        assertTrue(CanvasOps.convertsPixels(ColorMode.RGB, ColorMode.MONOCHROME))
        assertTrue(CanvasOps.convertsPixels(ColorMode.GRAYSCALE, ColorMode.MONOCHROME))
        assertTrue(!CanvasOps.convertsPixels(ColorMode.MONOCHROME, ColorMode.GRAYSCALE))
        assertTrue(!CanvasOps.convertsPixels(ColorMode.GRAYSCALE, ColorMode.RGB))
        assertTrue(!CanvasOps.convertsPixels(ColorMode.GRAYSCALE, ColorMode.GRAYSCALE))
    }

    @Test
    fun gridFollowsArtwork() {
        val g = GridSettings(spacingPx = 100f, offsetXPx = 10f, offsetYPx = 20f)
        val s = CanvasGeometry.scale(2.0, 2.0).mapGrid(g)
        assertEquals(200f, s.spacingPx, 1e-4f); assertEquals(20f, s.offsetXPx, 1e-4f); assertEquals(40f, s.offsetYPx, 1e-4f)
        val t = CanvasGeometry.translate(-35.0, 5.0).mapGrid(g)
        assertEquals(75f, t.offsetXPx, 1e-4f) // (10 - 35) mod 100
        assertEquals(25f, t.offsetYPx, 1e-4f)
        assertEquals(100f, t.spacingPx, 1e-4f)
    }

    // ------------------------------------------------------------------ resampler

    @Test
    fun tapWeightsAreNormalized() {
        for (k in ResampleKernel.entries) {
            for ((inS, outS) in listOf(10 to 3, 3 to 10, 7 to 7, 100 to 1, 1 to 5)) {
                val t = Resampler.taps(inS, outS, k)
                for (o in 0 until outS) {
                    var sum = 0f
                    for (i in 0 until t.count[o]) sum += t.weights[o * t.stride + i]
                    assertEquals("$k $inS->$outS @$o", 1f, sum, 1e-4f)
                    assertTrue(t.start[o] >= 0 && t.start[o] + t.count[o] <= inS)
                }
            }
        }
    }

    @Test
    fun resampleFlatStaysFlatAndGradientStaysMonotonic() {
        val src = IntArrayImage(37, 23, IntArray(37 * 23) { 0xFF4080C0.toInt() })
        for (k in ResampleKernel.entries) {
            for ((w, h) in listOf(11 to 5, 90 to 70, 37 to 60)) {
                val dst = IntArrayImage(w, h)
                Resampler.resample(src, w, h, k, dst)
                dst.pixels.forEach { assertEquals(0xFF4080C0.toInt(), it) }
            }
        }
        val grad = IntArrayImage(50, 1, IntArray(50) { x -> val v = x * 5; (0xFF shl 24) or (v shl 16) or (v shl 8) or v })
        val out = IntArrayImage(20, 1)
        Resampler.resample(grad, 20, 1, ResampleKernel.TRIANGLE, out)
        for (x in 1 until 20) assertTrue(out.pixels[x] and 0xFF >= out.pixels[x - 1] and 0xFF)
    }

    @Test
    fun resampleIsPremultiplied() {
        // Opaque red next to transparent BLACK: the result must stay pure red with partial alpha.
        val src = IntArrayImage(4, 1, intArrayOf(0xFFFF0000.toInt(), 0xFFFF0000.toInt(), 0, 0))
        val dst = IntArrayImage(3, 1)
        Resampler.resample(src, 3, 1, ResampleKernel.CATMULL_ROM, dst)
        for (p in dst.pixels) if (p ushr 24 != 0) assertEquals(0xFF0000, p and 0xFFFFFF)
        assertTrue(dst.pixels[1] ushr 24 in 1..254)
    }

    @Test
    fun stripedResampleMatchesDirectFormula() {
        // 601 output rows need more than one strip; compare with a direct per-pixel evaluation.
        val w = 300; val h = 257; val dw = 97; val dh = 601
        val px = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val a = if ((x / 13 + y / 11) % 3 == 0) 0 else 128 + (x + y) % 128
            (a shl 24) or ((x * 7 and 0xFF) shl 16) or ((y * 3 and 0xFF) shl 8) or ((x + y) and 0xFF)
        }
        assertTrue(Resampler.stripHeight(w, h, dw, dh, 6) < dh)
        val out = IntArrayImage(dw, dh)
        var writes = 0
        val progress = ArrayList<Float>()
        Resampler.resample(
            IntArrayImage(w, h, px), dw, dh, ResampleKernel.CATMULL_ROM,
            dst = RowSink { y, n, p -> writes++; out.write(y, n, p) },
            onProgress = { progress += it },
        )
        assertTrue(writes > 1)
        // One progress report per strip, increasing up to 1.
        assertEquals(writes, progress.size)
        for (i in 1 until progress.size) assertTrue(progress[i] > progress[i - 1])
        assertEquals(1f, progress.last(), 0f)

        val tx = Resampler.taps(w, dw, ResampleKernel.CATMULL_ROM)
        val ty = Resampler.taps(h, dh, ResampleKernel.CATMULL_ROM)
        for (y in 0 until dh step 7) for (x in 0 until dw step 3) {
            val acc = DoubleArray(4)
            for (j in 0 until ty.count[y]) {
                val wy = ty.weights[y * ty.stride + j]
                val sy = ty.start[y] + j
                for (i in 0 until tx.count[x]) {
                    val wx = tx.weights[x * tx.stride + i]
                    val c = px[sy * w + tx.start[x] + i]
                    val a = c ushr 24
                    val f = a / 255.0
                    acc[0] += wx * wy * a
                    acc[1] += wx * wy * ((c shr 16) and 0xFF) * f
                    acc[2] += wx * wy * ((c shr 8) and 0xFF) * f
                    acc[3] += wx * wy * (c and 0xFF) * f
                }
            }
            val expected = Resampler.unpremultiply(acc[0].toFloat(), acc[1].toFloat(), acc[2].toFloat(), acc[3].toFloat())
            val actual = out.pixels[y * dw + x]
            for (shift in intArrayOf(24, 16, 8, 0)) {
                val e = (expected ushr shift) and 0xFF
                val g = (actual ushr shift) and 0xFF
                // Colors of nearly transparent pixels are imprecise by nature.
                val tol = if (expected ushr 24 < 8) 255 else 2
                assertTrue("($x,$y) ${Integer.toHexString(expected)} vs ${Integer.toHexString(actual)}", abs(e - g) <= tol)
            }
        }
    }

    @Test
    fun nearestPicksPixelCenters() {
        val src = IntArrayImage(3, 1, intArrayOf(1, 2, 3))
        val up = IntArrayImage(6, 2)
        Resampler.nearest(src, 6, 2, up)
        assertTrue(intArrayOf(1, 1, 2, 2, 3, 3, 1, 1, 2, 2, 3, 3).contentEquals(up.pixels))
        val down = IntArrayImage(1, 1)
        Resampler.nearest(src, 1, 1, down)
        assertEquals(2, down.pixels[0])
    }

    // ------------------------------------------------------------------ color modes

    @Test
    fun monochromeThresholdAndDither() {
        val w = 200
        val row = IntArray(w * 40) { i -> val v = (i % w) * 255 / (w - 1); (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        val plain = row.copyOf()
        ColorModeConverter(w, ColorMode.MONOCHROME, threshold = 128).convertRows(plain, 40)
        for (i in plain.indices) {
            val v = (i % w) * 255 / (w - 1)
            assertEquals(if (v >= 128) -1 else 0xFF000000.toInt(), plain[i])
        }
        val dithered = row.copyOf()
        ColorModeConverter(w, ColorMode.MONOCHROME, threshold = 128, dither = true).convertRows(dithered, 40)
        assertTrue(dithered.all { it == -1 || it == 0xFF000000.toInt() })
        // Mean brightness is preserved within a few percent.
        val meanIn = row.sumOf { it and 0xFF } / row.size.toDouble()
        val meanOut = dithered.sumOf { it and 0xFF } / dithered.size.toDouble()
        assertTrue("in $meanIn out $meanOut", abs(meanIn - meanOut) < 8)
    }

    @Test
    fun ditherSkipsTransparentPixels() {
        val px = intArrayOf(0x40FFFFFF, 0xFF808080.toInt(), 0x00000000, 0x7FFFFFFF)
        ColorModeConverter(4, ColorMode.MONOCHROME, dither = true).convertRows(px, 1)
        assertEquals(0, px[0]); assertEquals(0, px[2]); assertEquals(0, px[3])
        assertTrue(px[1] == -1 || px[1] == 0xFF000000.toInt())
    }

    // ------------------------------------------------------------------ dialog math

    @Test
    fun dialogMath() {
        assertEquals(1, CanvasAdjustMath.toPixels(0.2))
        assertEquals(1234, CanvasAdjustMath.toPixels(1233.6))
        // Physical sizes keep their size when the dpi changes; pixel sizes stay.
        assertEquals(600.0, CanvasAdjustMath.pxAfterDpiChange(300.0, 150.0, 300.0, LengthUnit.CM), 1e-9)
        assertEquals(300.0, CanvasAdjustMath.pxAfterDpiChange(300.0, 150.0, 300.0, LengthUnit.PX), 1e-9)
        val e = CanvasAdjustMath.edges(100, 80, 120, 60, 10, -10)
        assertTrue(intArrayOf(10, -10, 10, -10).contentEquals(e))
        assertEquals("Left +10 · Top −10 · Right +10 · Bottom −10 px", CanvasAdjustMath.formatEdges(e))
        assertEquals("2.54 × 5.08 cm", CanvasAdjustMath.formatSize(100.0, 200.0, LengthUnit.CM, 100.0))
        assertEquals("50%", CanvasAdjustMath.percent(50, 100))
        assertEquals("3 layers + 1 mask", CanvasAdjustMath.describeBitmaps(3, 1))
        assertTrue(CanvasAdjustMath.aspectChanged(100, 100, 200, 100))
        assertTrue(!CanvasAdjustMath.aspectChanged(1000, 500, 500, 250))
    }

    @Test
    fun grayscaleMatchesLuminance() {
        val conv = ColorModeConverter(1, ColorMode.GRAYSCALE)
        assertEquals(0x80969696.toInt(), conv.convertPixel(0x8000FF00.toInt())) // lum(0,255,0) = 150
        assertEquals(0, conv.convertPixel(0x00FF00FF))
    }
}
