package com.brushwork.paint.qa110

import android.graphics.Bitmap
import android.net.Uri
import com.brushwork.paint.exchange.ImportFile
import com.brushwork.paint.exchange.ImportKind
import com.brushwork.paint.exchange.PendingImport
import com.brushwork.paint.exchange.PendingImports
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.ui.exchange.ExchangeUiState
import com.brushwork.paint.ui.exchange.GalleryImports
import com.brushwork.paint.v110.a11.A11Kit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import kotlin.math.abs

/**
 * v1.10 Gate 2 (design 7, lane docs): [DeviceCheckArtworks]' own check. The four specs are the
 * design's, and the generator's file at 64 x 64 comes back through the real import path the user
 * takes on the phone: the gallery's "New from SVG or PDF" sizes the artwork from the file's
 * Brushwork data ([GalleryImports.pdfPayloadSpec]) and the editor restores every layer into it
 * ([ExchangeUiState.importUri] with the gallery's pending request), with the names, blend modes
 * and pixels written. The device checks' table and ARCHITECTURE's paragraph describe the same
 * four files.
 */
@RunWith(RobolectricTestRunner::class)
class DeviceCheckArtworksTest {
    private val app get() = RuntimeEnvironment.getApplication()

    /** The generator at a tiny size: 4 layers, Multiply and Screen above layer 2. */
    private val tiny = DeviceCheckArtworks.Spec("check-tiny", 64, 64, 4, blendAbove = 2)

    @Test
    fun theFourSpecsAreTheDesigns() {
        val specs = DeviceCheckArtworks.ALL.map { listOf(it.name, it.width, it.height, it.layers) }
        assertEquals(
            listOf(
                listOf("check-200", 1080, 2408, 200),
                listOf("check-500", 1080, 2408, 500),
                listOf("check-100-blend", 1080, 2408, 100),
                listOf("check-4000x5000-50", 4000, 5000, 50),
            ),
            specs,
        )
        val blend = DeviceCheckArtworks.CHECK_100_BLEND
        assertEquals(LayerBlendMode.NORMAL, DeviceCheckArtworks.blendOf(blend, 50))
        assertEquals(LayerBlendMode.MULTIPLY, DeviceCheckArtworks.blendOf(blend, 51))
        assertEquals(LayerBlendMode.SCREEN, DeviceCheckArtworks.blendOf(blend, 52))
        assertEquals(LayerBlendMode.SCREEN, DeviceCheckArtworks.blendOf(blend, 100))
        for (spec in listOf(DeviceCheckArtworks.CHECK_200, DeviceCheckArtworks.CHECK_500, DeviceCheckArtworks.CHECK_4000X5000_50)) {
            assertEquals(spec.name, LayerBlendMode.NORMAL, DeviceCheckArtworks.blendOf(spec, spec.layers))
        }
    }

    /** The user's table and the lead's paragraph name the files the generator writes, at its sizes. */
    @Test
    fun theDocsDescribeTheGeneratedFiles() {
        val checks = docFile("docs/device-checks-2.0.md").readText(Charsets.UTF_8)
        val architecture = docFile("docs/ARCHITECTURE.md").readText(Charsets.UTF_8)
        for (spec in DeviceCheckArtworks.ALL) {
            val row = "| `${spec.fileName}` | ${spec.layers} painted layers at ${spec.width} × ${spec.height}"
            assertTrue("device-checks-2.0.md has the row \"$row\"", checks.contains(row))
            assertTrue("ARCHITECTURE.md names ${spec.fileName}", architecture.contains("`${spec.fileName}`"))
        }
        val blend = DeviceCheckArtworks.CHECK_100_BLEND
        val alternate = "layers ${blend.blendAbove + 1} to ${blend.layers} alternate Multiply and Screen"
        assertTrue("device-checks-2.0.md says \"$alternate\"", checks.contains(alternate))
        assertTrue("the generator's switch", architecture.contains("BW_DEVICE_ARTWORKS"))
    }

    /** [path] in the repository, found above the test JVM's working directory (the app module). */
    private fun docFile(path: String): File {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val file = generateSequence(start) { it.parentFile }.map { File(it, path) }.firstOrNull { it.isFile }
        assertNotNull("$path not found above $start", file)
        return file!!
    }

    @Test
    fun theGeneratorsFileComesBackThroughTheGalleryImport() {
        val file = File(app.cacheDir, tiny.fileName)
        val made = ArrayList<Int>()
        DeviceCheckArtworks.write(tiny, file) { made += it }
        assertEquals("one layer's pixels at a time, bottom first", (1..tiny.layers).toList(), made)

        // The gallery: a new artwork exactly the file's size (the import copies the file first).
        val probe = File(app.cacheDir, "probe-${tiny.fileName}")
        file.copyTo(probe, overwrite = true)
        val spec = ImportFile(ImportKind.PDF, tiny.name, null, probe).use { runBlocking { GalleryImports.pdfPayloadSpec(it) } }
        assertNotNull("a Brushwork PDF", spec)
        assertEquals(tiny.width to tiny.height, spec!!.width to spec.height)
        assertEquals(DeviceCheckArtworks.DPI, spec.dpi, 0f)

        // The editor: the gallery's Background + empty Layer 1, replaced by the file's layers.
        val doc = Smoke.document(spec.width, spec.height, layers = 2, whiteBottom = true)
        doc.dpi = spec.dpi
        doc.layers[0].name = "Background"
        doc.layers[1].name = "Layer 1"
        val c = Smoke.controller(app, doc)
        val uri = Uri.fromFile(file)
        PendingImports.putRequest(doc.id, PendingImport(uri))
        val state = ExchangeUiState(c)
        state.context = app
        state.importUri(uri)
        val names = (1..tiny.layers).map { DeviceCheckArtworks.nameOf(it) }
        assertTrue("restored: ${c.message}", Smoke.pumpUntil { c.busyMessage == null && c.doc.layers.map { it.name } == names })
        assertEquals(
            listOf(LayerBlendMode.NORMAL, LayerBlendMode.NORMAL, LayerBlendMode.MULTIPLY, LayerBlendMode.SCREEN),
            c.doc.layers.map { it.props().blendMode },
        )
        assertTrue("all visible", c.doc.layers.all { it.props().visible })

        for ((i, layer) in c.doc.layers.withIndex()) {
            val n = i + 1
            val written = DeviceCheckArtworks.pixels(tiny, n)
            val share = DeviceCheckArtworks.coverage(written)
            assertTrue("layer $n covers about 35 % ($share)", share >= DeviceCheckArtworks.COVERAGE && share < DeviceCheckArtworks.COVERAGE + 0.05)
            assertBytes("layer $n", premultiplied(written, tiny.width, tiny.height), A11Kit.bytesOf(layer.pixels, tiny.width, tiny.height))
        }
        c.dispose()
        file.delete()
    }

    /** [px] (non-premultiplied ARGB) as a layer's stored bytes, premultiplied by Skia. */
    private fun premultiplied(px: IntArray, w: Int, h: Int): ByteArray {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        b.setPixels(px, 0, w, 0, 0, w, h)
        return A11Kit.raw(b).also { b.recycle() }
    }

    /** Equal, but for one level of rounding in a premultiplied color channel; alpha exactly. */
    private fun assertBytes(what: String, expected: ByteArray, actual: ByteArray) {
        assertEquals(what, expected.size, actual.size)
        val alpha = A11Kit.alphaIndex
        for (i in expected.indices) {
            val e = expected[i].toInt() and 0xFF
            val a = actual[i].toInt() and 0xFF
            if (i % 4 == alpha) {
                assertEquals("$what alpha at pixel ${i / 4}", e, a)
            } else {
                assertTrue("$what channel at pixel ${i / 4}: $e vs $a", abs(e - a) <= 1)
            }
        }
    }
}
