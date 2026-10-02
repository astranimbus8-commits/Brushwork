package com.brushwork.paint.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.filters.GradientStop
import com.brushwork.paint.masks.AdjustmentEffects
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs

/**
 * v1.5 integration (A5 stage x A6 mappers): Gradation Map is the one effect whose mapper lowers
 * alpha (semi-transparent stops, equal to its apply()). On an adjustment layer that must reveal
 * the image below — over an opaque composite (one SRC_OVER pass) and over a semi-transparent one
 * (the alpha-keeping two passes) alike — never fade the image.
 */
@RunWith(RobolectricTestRunner::class)
class AdjustmentStageIntegrationRobolectricTest {
    private val w = 64
    private val h = 48

    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun photo(doc: Document, alphaEnd: Int): Layer =
        Layer(doc.newLayerId(), "Photo", BitmapUtils.createLayerBitmap(w, h)).also {
            Canvas(it.bitmap).drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF2040C0.toInt(), (alphaEnd shl 24) or 0xF0E020, Shader.TileMode.CLAMP) })
        }

    /** Flattened [photoAlphaEnd]-faded photo under a Gradation Map whose two stops have alpha [stopAlpha] (null = no adjustment). */
    private fun composite(photoAlphaEnd: Int, stopAlpha: Int?): IntArray {
        val doc = Document("g", "g", w, h)
        doc.layers += photo(doc, photoAlphaEnd)
        if (stopAlpha != null) {
            val f = FilterRegistry.byId("adjust.gradation_map")!!
            val values = f.defaultValues().set("gradient", listOf(GradientStop(0f, (stopAlpha shl 24) or 0x00FF00), GradientStop(1f, (stopAlpha shl 24) or 0xFF00FF)))
            doc.layers += Layer(doc.newLayerId(), "Gradation", BitmapUtils.createLayerBitmap(w, h)).also { it.adjustment = AdjustmentEffects.spec(f, values) }
        }
        return pixels(Compositor(doc) { null }.renderFlattened())
    }

    private fun channel(c: Int, sh: Int) = c shr sh and 0xFF

    @Test
    fun transparentStopsShowTheImageBelowOverOpaqueAndSemiTransparentPixels() {
        for (alphaEnd in listOf(0xFF, 0x30)) {
            val plain = composite(alphaEnd, null)
            val clear = composite(alphaEnd, 0)
            for (i in plain.indices) {
                for (sh in 0..24 step 8) {
                    val d = abs(channel(plain[i], sh) - channel(clear[i], sh))
                    assertTrue("alpha end $alphaEnd: pixel $i channel $sh differs by $d", d <= 1)
                }
            }
        }
    }

    @Test
    fun halfTransparentStopsMixHalfwayAndKeepTheAlpha() {
        for (alphaEnd in listOf(0xFF, 0x30)) {
            val plain = composite(alphaEnd, null)
            val full = composite(alphaEnd, 0xFF)
            val half = composite(alphaEnd, 0x80)
            var checked = 0
            for (i in plain.indices) {
                val a = plain[i] ushr 24
                // The adjustment never changes the composite's coverage.
                assertTrue("alpha at $i: $a -> ${half[i] ushr 24}", abs(a - (half[i] ushr 24)) <= 1)
                assertTrue("alpha at $i: $a -> ${full[i] ushr 24}", abs(a - (full[i] ushr 24)) <= 1)
                if (a < 64) continue // premultiplied colours of nearly transparent pixels are coarse
                for (sh in 0..16 step 8) {
                    val want = (channel(plain[i], sh) * 0x7F + channel(full[i], sh) * 0x80) / 0xFF
                    val d = abs(want - channel(half[i], sh))
                    assertTrue("alpha end $alphaEnd: pixel $i channel $sh is ${channel(half[i], sh)}, want $want", d <= 3 + 255 / a)
                }
                checked++
            }
            assertTrue(checked > plain.size / 4)
        }
    }

    @Test
    fun theRevealArithmeticKeepsTheCompositesAlpha() {
        // Opaque below: a half-alpha mapped colour is the midpoint.
        val mid = AdjustmentStage.revealed(0xFF000000.toInt(), 0x80FFFFFF.toInt())
        assertEquals(0xFF, mid ushr 24)
        for (sh in 0..16 step 8) assertTrue(abs(channel(mid, sh) - 0x80) <= 1)
        // Semi-transparent below: the alpha stays, a transparent stop gives the original back.
        assertEquals(0x40123456, AdjustmentStage.revealed(0x40123456, 0x00FFFFFF))
        assertEquals(0x40FFFFFF, AdjustmentStage.revealed(0x40123456, 0x40FFFFFF))
        // A raised alpha is held to the composite's; nothing is added where it is empty.
        assertEquals(0x40ABCDEF, AdjustmentStage.revealed(0x40123456, 0xFFABCDEF.toInt()))
        assertEquals(0, AdjustmentStage.revealed(0, 0xFFABCDEF.toInt()))
    }
}
