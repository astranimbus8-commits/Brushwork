package com.brushwork.paint.exchange

import com.brushwork.paint.EditorController
import com.brushwork.paint.exchange.ExchangeFixtures.controller
import com.brushwork.paint.exchange.ExchangeFixtures.document
import com.brushwork.paint.exchange.ExchangeFixtures.pixels
import com.brushwork.paint.exchange.ExchangeFixtures.render
import com.brushwork.paint.exchange.export.ExportOptions
import com.brushwork.paint.exchange.export.ExportSceneBuilder
import com.brushwork.paint.exchange.export.PdfWriter
import com.brushwork.paint.exchange.export.SvgWriter
import com.brushwork.paint.exchange.export.TextSource
import com.brushwork.paint.exchange.image.PngDecoder
import com.brushwork.paint.exchange.pdf.ArrayPdfBytes
import com.brushwork.paint.exchange.pdf.OwnPdfReader
import com.brushwork.paint.exchange.svg.SvgParser
import com.brushwork.paint.model.Document
import com.brushwork.paint.smoke.Smoke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * v1.5 §4.10e / §4.11e (A8): export -> own import round trips. A document with every kind of layer
 * comes back with the same layer list, properties, editable data, pixels and masks, through SVG
 * and through PDF; one undo step removes the whole import.
 */
@RunWith(RobolectricTestRunner::class)
class ExchangeRoundTripRobolectricTest {

    private fun export(c: EditorController, format: VectorFormat): ByteArray {
        val options = ExportOptions(format)
        val scene = runBlocking { ExportSceneBuilder(c, options, TextSource.Default, Dispatchers.Unconfined, Dispatchers.Unconfined).build() }
        val out = ByteArrayOutputStream()
        runBlocking {
            if (format == VectorFormat.SVG) SvgWriter(scene).write(out) else PdfWriter(scene).write(out)
        }
        return out.toByteArray()
    }

    /** A new artwork of the payload's size (like the gallery's), restored from [images]. */
    private fun restore(payloadDoc: Document, prepare: (ImportTarget) -> PayloadImport.Prepared): EditorController {
        val fresh = Smoke.document(payloadDoc.width, payloadDoc.height, layers = 2, whiteBottom = true)
        val c = controller(fresh)
        val before = c.undoManager.undoCount
        val replace = fresh.layers.toList()
        val target = ImportTarget(fresh.width, fresh.height, fresh.dpi, fresh.colorMode, ImportLayers.room(c) + replace.size, c.maxLayers)
        PayloadImport.apply(c, prepare(target), replace)
        assertEquals("one undo step", before + 1, c.undoManager.undoCount)
        return c
    }

    private fun assertSameLayers(expected: Document, actual: Document) {
        assertEquals(expected.layers.map { it.name }, actual.layers.map { it.name })
        assertEquals(expected.layers.map { it.props() }, actual.layers.map { it.props() })
        assertEquals(expected.layers.map { it.dataSnapshot() }, actual.layers.map { it.dataSnapshot() })
        assertEquals(expected.activeLayerIndex, actual.activeLayerIndex)
        for ((e, a) in expected.layers.zip(actual.layers)) {
            if (e.isVectorLayer) {
                // Rendered again from the objects: exactly the editor's cache of them.
                assertArrayEquals(e.name, pixels(render(e.vector!!, e.width, e.height)), pixels(a.bitmap))
            } else {
                assertPixels(e.name, pixels(e.bitmap), pixels(a.bitmap))
            }
            assertEquals(e.name, e.mask != null, a.mask != null)
            e.mask?.let { assertArrayEquals("${e.name} mask", pixels(it), pixels(a.mask!!)) }
        }
    }

    /** Equal, but for one level of premultiplied rounding where alpha is partial. */
    private fun assertPixels(what: String, e: IntArray, a: IntArray) {
        assertEquals(e.size, a.size)
        for (i in e.indices) {
            if (e[i] == a[i]) continue
            val ea = e[i] ushr 24
            assertEquals("$what alpha at $i", ea, a[i] ushr 24)
            assertTrue("$what partial alpha at $i", ea in 1..254)
            for (s in intArrayOf(16, 8, 0)) {
                assertTrue("$what channel at $i: ${Integer.toHexString(e[i])} vs ${Integer.toHexString(a[i])}", abs(((e[i] shr s) and 0xFF) - ((a[i] shr s) and 0xFF)) <= 255 / ea + 1)
            }
        }
    }

    @Test
    fun svgRoundTripRestoresEveryLayerExactly() {
        val doc = document(adjustment = true)
        val bytes = export(controller(doc), VectorFormat.SVG)
        val svg = SvgParser.parse(bytes)
        val payload = svg.payload()!!
        val c = restore(doc) { t -> PayloadImport.prepare(payload, { key -> svg.imageData(key)?.let { PngDecoder.decode(it) } }, t) }
        assertSameLayers(doc, c.doc)
        // One undo takes the whole import back.
        c.undo()
        assertEquals(listOf("Layer 1", "Layer 2"), c.doc.layers.map { it.name })
        c.redo()
        assertEquals(doc.layers.size, c.doc.layers.size)
    }

    @Test
    fun pdfRoundTripRestoresEveryLayerExactly() {
        val doc = document(adjustment = true)
        val bytes = export(controller(doc), VectorFormat.PDF)
        val reader = OwnPdfReader(ArrayPdfBytes(bytes))
        assertTrue(reader.hasPayload())
        val payload = reader.payload()!!
        val c = restore(doc) { t -> PayloadImport.prepare(payload, { key -> reader.payloadImage(key) }, t) }
        assertSameLayers(doc, c.doc)
    }

    @Test
    fun aPayloadOnAnotherCanvasIsPlacedAndKeepsVectorsEditable() {
        val doc = document()
        val bytes = export(controller(doc), VectorFormat.SVG)
        val svg = SvgParser.parse(bytes)
        val payload = svg.payload()!!
        // Half the size: scaled to 90 % and centred.
        val small = Smoke.document(150, 100, layers = 1)
        val c = controller(small)
        val target = ImportTarget(150, 100, 300f, small.colorMode, ImportLayers.room(c), c.maxLayers)
        val prepared = PayloadImport.prepare(payload, { key -> svg.imageData(key)?.let { PngDecoder.decode(it) } }, target)
        val o = PayloadImport.apply(c, prepared)
        assertTrue(o.layers > 0)
        val vector = c.doc.layers.first { it.name == "Vector" }
        val v = vector.vector!!
        // The box's corner (20, 20) -> scale 0.9 * 0.5 = 0.45, centred: (150 - 300 * 0.45) / 2 = 7.5.
        val box = v.objects[0] as com.brushwork.paint.vector.VPath
        assertEquals(7.5f + 20f * 0.45f, box.subpaths[0].anchors[0].x, 1e-3f)
        assertArrayEquals(pixels(render(v, 150, 100)), pixels(vector.bitmap))
        // Text and shape layers keep their pixels only (their data was made for the other size).
        assertEquals(null, c.doc.layers.first { it.name == "Text" }.textData)
        assertTrue(o.dropped.keys.any { it.startsWith("text and shape layers") })
    }

    @Test
    fun theSvgCarriesTheVisibleArtworkForOtherApps() {
        val doc = document()
        val svg = SvgParser.parse(export(controller(doc), VectorFormat.SVG))
        // What other apps see: the vector layer's box and ellipse as paths, pictures for the rest.
        val content = com.brushwork.paint.exchange.svg.SvgToVector.convert(svg, 300f)
        assertTrue(content.shapeCount >= 4)
        assertTrue(content.pictureCount >= 3)
        assertNotNull(content.bounds)
    }
}
