package com.brushwork.paint.model

import android.graphics.Bitmap
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorCodec
import com.brushwork.paint.vector.VectorContent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * v1.7 F1 (item 3, §4.2): the array spec JSON and the `array_<id>_r<rev>.bin` container round-trip
 * for every source kind, and damaged containers (bad magic, unknown version or kind, every
 * truncation, trailing data, oversized pixels) are refused with [CorruptArrayException].
 */
@RunWith(RobolectricTestRunner::class)
class ArrayCodecTest {
    private val docW = 64
    private val docH = 48

    private fun bytesOf(blob: ArraySourceBlob): ByteArray = ByteArrayOutputStream().also { ArrayCodec.writeSource(it, blob) }.toByteArray()

    private fun read(bytes: ByteArray, w: Int = docW, h: Int = docH) = ArrayCodec.readSource(ByteArrayInputStream(bytes), w, h)

    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun sampleBitmap(w: Int = 5, h: Int = 3): Bitmap = BitmapUtils.createLayerBitmap(w, h).apply {
        for (y in 0 until h) for (x in 0 until w) setPixel(x, y, if ((x + y) % 2 == 0) 0xFF102030.toInt() else 0x80402000.toInt())
    }

    private fun content(): VectorContent = VectorContent.EMPTY.plus(
        listOf(
            VStroke(0, preset = BrushLibrary.defaultBrush, color = 0xFF112233.toInt(), seed = 7, stylus = false,
                points = PackedPoints(floatArrayOf(1f, 20f, 40f), floatArrayOf(2f, 10f, 30f), floatArrayOf(0.5f, 0.6f, 1f))),
            VPath(0, subpaths = listOf(VSubpath(listOf(VAnchor(0f, 0f), VAnchor(10f, 5f, sharp = true)), closed = false))),
            VShape(0, shape = ShapeObject(cx = 20f, cy = 20f, w = 10f, h = 8f)),
        ),
    ).first

    @Test
    fun specRoundTripsWithItsDefaults() {
        val spec = ArraySpec(
            mode = ArrayMode.CURVE, count = 7, relativeX = 0.5f, constantY = -3f, centerX = 12f, centerY = null,
            sweepDeg = 180f, rotateCopies = false, guide = VSubpath(listOf(VAnchor(1f, 2f), VAnchor(30f, 4f, sharp = true, inX = -2f, inY = 0f)), closed = false),
            spacing = 15f, alignToCurve = false, moveX = 5f, moveY = 6f, turnDeg = -45f, scale = 1.5f, pivotX = 3f, pivotY = 4f, editingSource = true,
        )
        assertEquals(spec, ArrayCodec.decodeSpec(ArrayCodec.encodeSpec(spec)))
        // An internal blob: defaults are written, so a later change of a default never moves saved copies.
        assertTrue(ArrayCodec.encodeSpec(ArraySpec()).contains("\"count\":3"))
        assertEquals(ArraySpec(), ArrayCodec.decodeSpec("{}"))
    }

    @Test
    fun damagedSpecsDecodeToNullOrSanitized() {
        assertNull(ArrayCodec.decodeSpec(null))
        assertNull(ArrayCodec.decodeSpec("not json"))
        assertNull(ArrayCodec.decodeSpec("[1,2]"))
        val s = ArrayCodec.decodeSpec("{\"mode\":\"WARP\",\"count\":100000,\"scale\":0,\"future\":true}")!!
        assertEquals(ArrayMode.LINE, s.mode)
        assertEquals(ArraySpec.MAX_COUNT, s.count)
        assertEquals(ArraySpec.MIN_SCALE, s.scale, 0f)
    }

    @Test
    fun containerHeaderIsBwarVersionAndKind() {
        val b = bytesOf(ArraySourceBlob.Text("{}"))
        assertEquals("BWAR", String(b, 0, 4, Charsets.US_ASCII))
        assertEquals(ArrayCodec.VERSION, b[4].toInt())
        assertEquals(2, b[5].toInt())
        assertEquals(0, bytesOf(ArraySourceBlob.Pixels(ArrayPixels(sampleBitmap(), 0, 0))).let { it[5].toInt() })
        assertEquals(1, bytesOf(ArraySourceBlob.Vector(content()))[5].toInt())
        assertEquals(3, bytesOf(ArraySourceBlob.Shape("{}"))[5].toInt())
    }

    @Test
    fun everySourceKindRoundTrips() {
        val bmp = sampleBitmap()
        val px = read(bytesOf(ArraySourceBlob.Pixels(ArrayPixels(bmp, -2, 7)))) as ArraySourceBlob.Pixels
        assertEquals(-2, px.pixels.left)
        assertEquals(7, px.pixels.top)
        assertEquals(5, px.pixels.bitmap.width)
        assertEquals(3, px.pixels.bitmap.height)
        assertArrayEquals(pixels(bmp), pixels(px.pixels.bitmap))
        assertEquals(60L, px.pixels.bytes)

        val c = content()
        val vec = read(bytesOf(ArraySourceBlob.Vector(c))) as ArraySourceBlob.Vector
        assertArrayEquals(VectorCodec.encode(c), VectorCodec.encode(vec.content))

        val text = "{\"version\":4,\"text\":\"Grüße ✓ 😀\"}"
        assertEquals(text, (read(bytesOf(ArraySourceBlob.Text(text))) as ArraySourceBlob.Text).textData)
        assertEquals("{\"type\":\"STAR\"}", (read(bytesOf(ArraySourceBlob.Shape("{\"type\":\"STAR\"}"))) as ArraySourceBlob.Shape).shapeData)
    }

    @Test
    fun everyTruncationIsRefused() {
        val blobs = listOf(
            ArraySourceBlob.Pixels(ArrayPixels(sampleBitmap(), 1, 1)),
            ArraySourceBlob.Vector(content()),
            ArraySourceBlob.Text("{\"a\":1}"),
            ArraySourceBlob.Shape("{\"b\":2}"),
        )
        for (blob in blobs) {
            val full = bytesOf(blob)
            for (n in 0 until full.size) assertCorrupt("${blob.javaClass.simpleName} cut at $n of ${full.size}", full.copyOf(n))
        }
    }

    @Test
    fun badHeadersTrailingDataAndOversizedPixelsAreRefused() {
        val text = bytesOf(ArraySourceBlob.Text("{}"))
        assertCorrupt("magic", text.copyOf().also { it[0] = 'X'.code.toByte() })
        assertCorrupt("version 0", text.copyOf().also { it[4] = 0 })
        assertCorrupt("version 2", text.copyOf().also { it[4] = 2 })
        assertCorrupt("kind 9", text.copyOf().also { it[5] = 9 })
        assertCorrupt("trailing data", text + byteArrayOf(1))
        assertCorrupt("negative length", text.copyOf().also { it[6] = 0x80.toByte() })
        val px = bytesOf(ArraySourceBlob.Pixels(ArrayPixels(sampleBitmap(), 0, 0)))
        // Larger than the document, or off the canvas.
        assertCorrupt("too wide", bytesOf(ArraySourceBlob.Pixels(ArrayPixels(sampleBitmap(), 0, 0))), w = 4, h = 48)
        assertCorrupt("off the canvas", bytesOf(ArraySourceBlob.Pixels(ArrayPixels(sampleBitmap(), docW + 1, 0))))
        // A damaged pixel stream (a flipped byte in the compressed data fails the checksum).
        val damaged = px.copyOf().also { it[it.size - 6] = (it[it.size - 6].toInt() xor 0x55).toByte() }
        assertCorrupt("damaged pixels", damaged)
        val vec = bytesOf(ArraySourceBlob.Vector(content()))
        assertCorrupt("damaged vector", vec.copyOf().also { it[10] = (it[10].toInt() xor 0x55).toByte() })
    }

    private fun assertCorrupt(what: String, bytes: ByteArray, w: Int = docW, h: Int = docH) {
        try {
            read(bytes, w, h)
            fail("$what: no CorruptArrayException")
        } catch (e: CorruptArrayException) {
            assertFalse(what, e.message.isNullOrBlank())
        }
    }

    @Test
    fun sourceBlobOfLayerData() {
        val array = LayerArray(ArraySpec())
        assertNull(ArraySourceBlob.of(LayerData(text = "t")))
        assertNull("an array with no source", ArraySourceBlob.of(LayerData(array = array)))
        assertEquals("t", (ArraySourceBlob.of(LayerData(text = "t", array = array)) as ArraySourceBlob.Text).textData)
        assertEquals("s", (ArraySourceBlob.of(LayerData(shape = "s", array = array)) as ArraySourceBlob.Shape).shapeData)
        val c = content()
        assertSame(c, (ArraySourceBlob.of(LayerData(vector = c, array = array)) as ArraySourceBlob.Vector).content)
        val pixels = ArrayPixels(sampleBitmap(), 3, 4)
        assertSame(pixels, (ArraySourceBlob.of(LayerData(array = LayerArray(ArraySpec(), pixels))) as ArraySourceBlob.Pixels).pixels)
    }
}
