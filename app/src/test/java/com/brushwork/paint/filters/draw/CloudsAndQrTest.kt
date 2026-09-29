package com.brushwork.paint.filters.draw

import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.FilterContext
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CloudsAndQrTest {
    private val ctx = FilterContext()
    private val black = 0xFF000000.toInt()

    private fun lum(c: Int) = ColorUtils.luminance(c)

    private fun clouds(w: Int, h: Int, ctx: FilterContext = this.ctx, edit: (com.brushwork.paint.filters.FilterValues) -> Unit = {}): PixelBuffer {
        val f = CloudsFilter()
        val v = f.defaultValues().set("color", black).set("color2", -1)
        edit(v)
        return f.apply(PixelBuffer(w, h), v, ctx)
    }

    @Test
    fun cloudsSpanTheColorRangeAndAreOpaque() {
        for (type in 0..2) {
            val out = clouds(160, 120) { it.set("type", type) }
            val l = out.pixels.map { lum(it) }.sorted()
            assertTrue("type $type p5=${l[l.size / 20]}", l[l.size / 20] < 70)
            assertTrue("type $type p95=${l[l.size * 19 / 20]}", l[l.size * 19 / 20] > 185)
            assertTrue(out.pixels.all { it ushr 24 == 255 })
        }
    }

    @Test
    fun cloudsAreDeterministicPerSeed() {
        val a = clouds(50, 40); val b = clouds(50, 40)
        val c = clouds(50, 40) { it.set("seed", 99) }
        assertTrue(a.pixels.contentEquals(b.pixels))
        assertFalse(a.pixels.contentEquals(c.pixels))
    }

    @Test
    fun cloudsPreviewMatchesFullResolution() {
        val full = clouds(240, 160)
        val small = clouds(60, 40, FilterContext(scale = 0.25f))
        var diff = 0L
        for (y in 0 until 40) for (x in 0 until 60) diff += abs(lum(small[x, y]) - lum(full[x * 4 + 2, y * 4 + 2]))
        val mean = diff.toDouble() / (60 * 40)
        assertTrue("mean diff $mean", mean < 18)
    }

    @Test
    fun cloudsBrightnessShiftsTheTone() {
        val dark = clouds(64, 64) { it.set("brightness", -60f) }.pixels.map { lum(it) }.average()
        val bright = clouds(64, 64) { it.set("brightness", 60f) }.pixels.map { lum(it) }.average()
        assertTrue(bright > dark + 60)
    }

    @Test
    fun cloudsStretchElongatesHorizontally() {
        val out = clouds(200, 200) { it.set("stretch", 100f).set("scale", 15f) }
        var dx = 0L; var dy = 0L
        for (y in 1 until 200) for (x in 1 until 200) {
            dx += abs(lum(out[x, y]) - lum(out[x - 1, y])); dy += abs(lum(out[x, y]) - lum(out[x, y - 1]))
        }
        assertTrue("dx=$dx dy=$dy", dy > dx * 1.5)
    }

    // ------------------------------------------------------------------ QR

    private fun decode(img: PixelBuffer): String {
        // The luminance source ignores alpha: flatten onto white first.
        val px = IntArray(img.size) { ColorUtils.over(img.pixels[it], -1) }
        val source = RGBLuminanceSource(img.width, img.height, px)
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    }

    @Test
    fun qrCodeDecodesToTheText() {
        val f = QrCodeFilter()
        for (text in listOf("https://example.com/brushwork?x=1", "héllo wörld · 日本語 🎨")) {
            val v = f.defaultValues().set("text", text).set("size", 70f)
            val out = f.apply(PixelBuffer(400, 400), v, ctx)
            assertEquals(text, decode(out))
        }
    }

    @Test
    fun qrCodeDecodesAtEveryErrorCorrectionAndOnAnOffsetPosition() {
        val f = QrCodeFilter()
        for (ecc in 0..3) {
            val v = f.defaultValues().set("ecc", ecc).set("size", 45f).set("position", floatArrayOf(0.3f, 0.65f))
            assertEquals("https://example.com", decode(f.apply(PixelBuffer.filled(500, 420, 0xFF8899AA.toInt()), v, ctx)))
        }
    }

    @Test
    fun qrModulesAreWholePixelsAndCodeColorsAreExact() {
        val f = QrCodeFilter()
        val red = 0xFFCC0000.toInt()
        val v = f.defaultValues().set("color", red).set("transparent", true)
        val out = f.apply(PixelBuffer(333, 301), v, ctx)
        // Only untouched transparent pixels or the exact code color: no antialiasing blur.
        assertTrue(out.pixels.all { it == 0 || it == red })
        val m = f.encode("https://example.com", 1)!!
        val module = (0.4f * 301 / (m.size + 8)).toInt()
        val inked = out.pixels.count { it == red }
        assertEquals(m.dark.count { it } * module * module, inked)
    }

    @Test
    fun qrFallsBackToLowerCorrectionForLongTextAndSkipsImpossibleText() {
        val f = QrCodeFilter()
        val long = "a".repeat(1800) // byte mode: too long for levels H (1273) and Q (1663), fits M
        val m = f.encode(long, 3)
        assertNotNull(m)
        assertTrue(m!!.size > 150)
        val src = PixelBuffer.filled(20, 20, 0xFF445566.toInt())
        val out = f.apply(src, f.defaultValues().set("text", "x".repeat(10000)), ctx)
        assertTrue(out !== src && out.pixels.contentEquals(src.pixels))
        val empty = f.apply(src, f.defaultValues().set("text", ""), ctx)
        assertTrue(empty.pixels.contentEquals(src.pixels))
    }

    @Test
    fun qrPreviewCoversTheSameRelativeArea() {
        val f = QrCodeFilter()
        val v = f.defaultValues()
        fun bbox(img: PixelBuffer): FloatArray {
            var x0 = img.width; var y0 = img.height; var x1 = -1; var y1 = -1
            for (y in 0 until img.height) for (x in 0 until img.width) if (img[x, y] != 0) {
                x0 = minOf(x0, x); y0 = minOf(y0, y); x1 = maxOf(x1, x); y1 = maxOf(y1, y)
            }
            return floatArrayOf(x0.toFloat() / img.width, y0.toFloat() / img.height, (x1 + 1f) / img.width, (y1 + 1f) / img.height)
        }
        val full = bbox(f.apply(PixelBuffer(800, 600), v, ctx))
        val small = bbox(f.apply(PixelBuffer(200, 150), v, FilterContext(scale = 0.25f)))
        for (i in 0..3) assertEquals(full[i], small[i], 0.012f)
    }
}
