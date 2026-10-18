package com.brushwork.paint.v19.a7b

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.storage.LayerCodec
import com.brushwork.paint.storage.LayerEntries
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.ByteBuffer
import kotlin.random.Random

/**
 * v1.9 A7b step 4 (design 3.2 A7b, R9): a save writes each layer's and mask's pixel file a band
 * at a time, from a native copy taken on the main thread, through at most 6 MB of Java scratch.
 * Every file is byte-identical to v1.8's write from a buffer the size of the layer
 * ([LayerEntries.bandedSave] false): translucent, opaque, empty and uniform layers, a mask, a
 * non-premultiplied bitmap, bands of one row, of a few rows with a shorter last one, and the
 * default size; a 2400 x 1800 layer (17 MB) needs a scratch of at most 6 MB instead of 17 MB.
 */
@RunWith(RobolectricTestRunner::class)
class BandedSaveByteIdenticalTest {
    private val app: Context get() = ApplicationProvider.getApplicationContext()
    private val made = ArrayList<Layer>()

    @After
    fun tearDown() {
        LayerEntries.bandedSave = true
        LayerEntries.bandBytes = LayerEntries.BAND_BYTES
        made.forEach { it.recycleBitmaps() }
    }

    private fun layer(doc: Document, name: String, fill: (Bitmap) -> Unit): Layer {
        val b = BitmapUtils.createLayerBitmap(doc.width, doc.height)
        fill(b)
        return Layer(doc.newLayerId(), name, b).also { doc.layers += it; made += it }
    }

    private fun document(w: Int, h: Int): Document {
        val doc = Document("banded", "Banded", w, h)
        val rnd = Random(w * 31 + h)
        layer(doc, "Translucent") { it.setPixels(IntArray(w * h) { i -> if (i % 13 == 0) 0 else rnd.nextInt() }, 0, w, 0, 0, w, h) }
        layer(doc, "Opaque with a mask") {
            Canvas(it).drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
                shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF102030.toInt(), 0xFFF0C080.toInt(), Shader.TileMode.CLAMP)
            })
        }.mask = BitmapUtils.createMaskBitmap(w, h).also { m ->
            Canvas(m).drawCircle(w / 2f, h / 2f, h / 3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() })
        }
        layer(doc, "Empty") {}
        layer(doc, "Uniform") { it.eraseColor(0x80336699.toInt()) }
        return doc
    }

    private class Saved(val files: Map<String, ByteArray>, val scratch: Int)

    private fun save(doc: Document, banded: Boolean, bandBytes: Int = LayerEntries.BAND_BYTES): Saved {
        val dir = File(app.cacheDir, "banded-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try {
            LayerEntries.bandedSave = banded
            LayerEntries.bandBytes = bandBytes
            val writer = LayerEntries.Writer(dir, doc, 7L, emptySet(), null)
            val names = runBlocking { doc.layers.map { writer.entryFor(it)!! } }.flatMap { e -> listOfNotNull(e.contentFileName, if (e.hasMask) e.maskFileName else null) }
            val files = dir.listFiles()!!.filter { it.isFile }.associate { it.name to it.readBytes() }
            assertEquals("one file per layer and mask, nothing else", names.toSet(), files.keys)
            return Saved(files, writer.scratchBytes)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun assertSameFiles(what: String, whole: Saved, banded: Saved) {
        assertEquals(what, whole.files.keys, banded.files.keys)
        for ((name, bytes) in whole.files) assertArrayEquals("$what: $name", bytes, banded.files.getValue(name))
    }

    @Test
    fun everyBandSizeWritesTheWholeLayerBytes() {
        val w = 97; val h = 61
        val doc = document(w, h)
        val whole = save(doc, banded = false)
        assertEquals("v1.8: a buffer the size of the layer", w * h * 4, whole.scratch)
        for (bandBytes in listOf(LayerEntries.BAND_BYTES, w * 4 * 7 + 5, w * 4, 1)) {
            val banded = save(doc, banded = true, bandBytes = bandBytes)
            assertSameFiles("bands of $bandBytes bytes", whole, banded)
            val rows = minOf(LayerCodec.bandRows(w, bandBytes), h)
            assertEquals("a band of $rows rows", rows * w * 4, banded.scratch)
        }
    }

    @Test
    fun aNonPremultipliedBitmapIsConvertedAsBefore() {
        val w = 40; val h = 30
        val doc = Document("np", "np", w, h)
        val rnd = Random(4)
        layer(doc, "Unpremultiplied") {
            it.isPremultiplied = false
            it.setPixels(IntArray(w * h) { rnd.nextInt() }, 0, w, 0, 0, w, h)
        }
        assertFalse(LayerCodec.isStorable(doc.layers[0].bitmap))
        assertSameFiles("converted", save(doc, banded = false), save(doc, banded = true, bandBytes = w * 4 * 3))
    }

    @Test
    fun aLargeLayerNeedsAtMost6MBOfScratch() {
        val w = 2400; val h = 1800
        val doc = Document("large", "Large", w, h)
        val rnd = Random(9)
        val row = IntArray(w)
        val l = layer(doc, "Large") { b ->
            for (y in 0 until h) {
                for (x in 0 until w) row[x] = if ((x + y) % 7 == 0) rnd.nextInt() else (0xFF shl 24) or (x * 255 / w shl 16) or (y * 255 / h shl 8) or ((x xor y) and 0xFF)
                b.setPixels(row, 0, w, 0, y, w, 1)
            }
        }
        l.mask = BitmapUtils.createMaskBitmap(w, h)
        val whole = save(doc, banded = false)
        assertEquals(w * h * 4, whole.scratch)
        val banded = save(doc, banded = true)
        assertTrue("scratch ${banded.scratch}", banded.scratch <= 6 * 1024 * 1024)
        assertTrue("several bands", banded.scratch < w * h * 4 / 3)
        assertSameFiles("2400 x 1800", whole, banded)
        // And the file reads back as the layer.
        val dir = File(app.cacheDir, "banded-read").apply { mkdirs() }
        try {
            val f = File(dir, "layer.bin").apply { writeBytes(banded.files.getValue(banded.files.keys.first { it.startsWith("layer_") })) }
            val read = ByteArray(w * h * 4)
            LayerCodec.read(f, w, h, read)
            val expected = ByteArray(w * h * 4).also { l.bitmap.copyPixelsToBuffer(ByteBuffer.wrap(it)) }
            assertArrayEquals(expected, read)
        } finally {
            dir.deleteRecursively()
        }
    }
}
