package com.brushwork.paint.exchange

import com.brushwork.paint.exchange.image.ArgbImage
import com.brushwork.paint.exchange.image.FilteredZlib
import com.brushwork.paint.exchange.image.PngDecoder
import com.brushwork.paint.exchange.image.PngEncoder
import com.brushwork.paint.exchange.image.RowLayout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater
import javax.imageio.ImageIO
import kotlin.random.Random

/** v1.5 §4.10 (A8): the pure-Kotlin PNG / Flate image encoding is standard and lossless. */
class ImageCodecTest {

    private fun image(w: Int, h: Int, seed: Int = 1, opaque: Boolean = false): ArgbImage {
        val rnd = Random(seed)
        // Smooth gradients plus noise: every PNG filter gets used somewhere.
        val px = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val a = if (opaque) 255 else if (x % 17 == 0) 0 else (x * 3 + y) and 0xFF
            val r = (x * 2) and 0xFF
            val g = (y + rnd.nextInt(8)) and 0xFF
            val b = rnd.nextInt(256)
            (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return ArgbImage(w, h, px)
    }

    @Test
    fun pngRoundTripsThroughOwnDecoderAndImageIo() {
        for ((w, h) in listOf(1 to 1, 7 to 3, 64 to 33, 300 to 260)) {
            val img = image(w, h)
            val png = PngEncoder.toByteArray(img)
            val back = PngDecoder.decode(png)
            assertNotNull(back)
            assertEquals(w, back!!.width)
            assertArrayEquals("own decoder $w x $h", img.pixels, back.pixels)
            val bi = ImageIO.read(ByteArrayInputStream(png))
            assertNotNull("ImageIO reads the PNG", bi)
            val read = IntArray(w * h)
            bi.getRGB(0, 0, w, h, read, 0, w)
            for (i in read.indices) {
                // Fully transparent pixels: some readers drop the colour.
                if ((img.pixels[i] ushr 24) == 0) assertEquals(0, read[i] ushr 24) else assertEquals("pixel $i of $w x $h", img.pixels[i], read[i])
            }
        }
    }

    @Test
    fun largeImagesSpanSeveralParallelBandsAndStayOneValidStream() {
        // 900 x 700 RGBA = 2.5 MB of rows: about ten 256 KB bands deflated independently.
        val img = image(900, 700, seed = 5)
        val png = PngEncoder.toByteArray(img)
        assertArrayEquals(img.pixels, PngDecoder.decode(png)!!.pixels)
        val bi = ImageIO.read(ByteArrayInputStream(png))
        assertEquals(900, bi.width)
        assertEquals(img.pixels[123456], bi.getRGB(123456 % 900, 123456 / 900))
        // The bare zlib stream (PDF) inflates to exactly the filtered rows (Adler-32 checked by Inflater).
        val z = FilteredZlib.toByteArray(img, RowLayout.RGB)
        val inf = Inflater()
        inf.setInput(z)
        val raw = ByteArray(700 * (900 * 3 + 1))
        var n = 0
        while (!inf.finished()) n += inf.inflate(raw, n, raw.size - n)
        inf.end()
        assertEquals(raw.size, n)
        FilteredZlib.unfilter(raw, 700, 900 * 3, 3)
        val y = 351
        val x = 422
        val o = y * (900 * 3 + 1) + 1 + x * 3
        val c = img.pixels[y * 900 + x]
        assertEquals((c shr 16) and 0xFF, raw[o].toInt() and 0xFF)
        assertEquals((c shr 8) and 0xFF, raw[o + 1].toInt() and 0xFF)
        assertEquals(c and 0xFF, raw[o + 2].toInt() and 0xFF)
    }

    @Test
    fun opaqueImagesAreRgbAndMasksAreGray() {
        val opaque = image(20, 10, opaque = true)
        val png = PngEncoder.toByteArray(opaque)
        assertEquals(2, png[25].toInt()) // IHDR colour type: RGB
        assertArrayEquals(opaque.pixels, PngDecoder.decode(png)!!.pixels)

        val mask = ArgbImage(4, 1, intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF808080.toInt(), 0xFF00FF00.toInt()))
        val gray = PngEncoder.toByteArray(mask, gray = true)
        assertEquals(0, gray[25].toInt()) // gray
        val back = PngDecoder.decode(gray)!!.pixels
        assertEquals(0xFF000000.toInt(), back[0])
        assertEquals(0xFFFFFFFF.toInt(), back[1])
        assertEquals(0xFF808080.toInt(), back[2])
        // Green's luma (Rec. 601): 150.
        assertEquals(150, back[3] and 0xFF)
    }

    @Test
    fun decoderRejectsWhatItCannotRead() {
        assertNull(PngDecoder.decode(ByteArray(0)))
        assertNull(PngDecoder.decode("not a png at all".toByteArray()))
        val png = PngEncoder.toByteArray(image(10, 10))
        // Truncated data: no crash, no image.
        assertNull(PngDecoder.decode(png.copyOf(png.size / 2)))
        val out = ByteArrayOutputStream()
        PngEncoder.encode(image(3, 3), out)
        assertEquals(3 to 3, PngDecoder.size(out.toByteArray()))
    }
}
