package com.brushwork.paint.v19.a8

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.BrushworkApp
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.GovernorPolicy
import com.brushwork.paint.engine.MemoryGovernor
import com.brushwork.paint.engine.MemoryPressure
import com.brushwork.paint.exchange.VectorFormat
import com.brushwork.paint.exchange.export.ExportOptions
import com.brushwork.paint.exchange.export.ExportSceneBuilder
import com.brushwork.paint.exchange.export.SceneItem
import com.brushwork.paint.exchange.export.SceneLayer
import com.brushwork.paint.exchange.export.TextSource
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.PaperSpec
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.storage.CanvasStart
import com.brushwork.paint.storage.ExportFormat
import com.brushwork.paint.storage.ImageExport
import com.brushwork.paint.storage.NewCanvasSpec
import com.brushwork.paint.storage.NotEnoughMemoryException
import com.brushwork.paint.ui.common.StabilityLabels19
import com.brushwork.paint.ui.editor.EditorActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs

/**
 * v1.9 A8 (R15, design 2.4.9; F1b G2): every flattened raster export and share of a paper
 * document is on the paper. PNG, WEBP and JPEG from the gallery (Export to Pictures/Brushwork and
 * Share, which load and flatten the stored artwork) and from the editor (its overflow menu's
 * Export and Share) show the paper where nothing is painted, also under JPEG's white. A layer
 * export (the SVG / PDF scene's layer pictures and the Brushwork payload's) is the layer's own
 * pixels, never on the paper; the scene's background is the paper. A memory refusal of the
 * gallery's flatten or of JPEG's white copy says so ([StabilityLabels19.NO_MEMORY_FOR]) instead
 * of v1.8's "could not be saved".
 */
@RunWith(RobolectricTestRunner::class)
class PaperExportTest {
    private val cream = 0xFFF4ECD8.toInt()
    private val blue = 0xFF2050C0.toInt()
    private val formats = listOf(ExportFormat.PNG, ExportFormat.WEBP, ExportFormat.JPEG)

    private lateinit var app: BrushworkApp
    private lateinit var policy: GovernorPolicy
    private val gallery = HashMap<Uri, ByteArrayOutputStream>()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        policy = MemoryGovernor.policy
        File(app.filesDir, "projects").deleteRecursively()
        File(app.cacheDir, "exports").deleteRecursively()
        // Robolectric's MediaStore has no provider: an insert is the collection plus a counter.
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        for (n in 1..40) {
            val uri = Uri.parse("$collection/$n")
            val out = ByteArrayOutputStream()
            gallery[uri] = out
            shadowOf(app.contentResolver).registerOutputStream(uri, out)
        }
    }

    @After
    fun tearDown() {
        MemoryGovernor.policy = policy
    }

    /** True when [a] and [b] differ by at most [tolerance] per channel (JPEG is lossy). */
    private fun near(a: Int, b: Int, tolerance: Int = 6): Boolean =
        (0..24 step 8).all { s -> abs(((a ushr s) and 0xFF) - ((b ushr s) and 0xFF)) <= tolerance }

    private fun hex(c: Int) = String.format("#%08X", c)

    /** [image]'s bare corner is the paper and its square is blue: on the paper, not on white or transparency. */
    private fun assertOnThePaper(image: Bitmap?, where: String) {
        assertNotNull("$where: decoded", image)
        val img = image!!
        assertEquals(where, 64 to 48, img.width to img.height)
        val corner = img.getPixel(2, 2)
        assertTrue("$where: the paper in the corner, not ${hex(corner)}", near(cream, corner))
        val square = img.getPixel(30, 20)
        assertTrue("$where: the square, not ${hex(square)}", near(blue, square))
    }

    /** A stored artwork: one "Layer 1" with a blue square over cream paper. */
    private fun storedPaperArtwork(name: String): String = runBlocking {
        val repo = app.repository
        val id = repo.create(NewCanvasSpec(name, 64, 48, 300f, null, CanvasStart.PAPER))
        val doc = repo.load(id)
        doc.paper = PaperSpec(cream)
        val layer = doc.layers.single()
        Canvas(layer.bitmap).drawRect(20f, 10f, 40f, 30f, Paint().apply { color = blue })
        layer.markChanged()
        repo.save(doc, null)
        for (l in doc.layers) l.recycleBitmaps()
        id
    }

    /** The same artwork open in the editor. */
    private fun paperEditor(name: String): EditorController {
        val d = Document("paper-export", name, 64, 48)
        val layer = Layer(d.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(64, 48))
        Canvas(layer.bitmap).drawRect(20f, 10f, 40f, 30f, Paint().apply { color = blue })
        d.layers += layer
        d.activeLayerIndex = 0
        d.paper = PaperSpec(cream)
        return Smoke.controller(app, d).also { it.viewTransform.set(Matrix()) }
    }

    private fun galleryImage(uri: Uri?, where: String): Bitmap? {
        val out = gallery[uri] ?: throw AssertionError("$where: no gallery file for $uri")
        val bytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    @Test
    fun theGallerysExportsAndSharesAreOnThePaper() = runBlocking<Unit> {
        val id = storedPaperArtwork("Paper art")
        for (format in formats) {
            val where = "the gallery's ${format.name} export"
            val uri = app.repository.exportProject(id, format)
            assertOnThePaper(galleryImage(uri, where), where)
            // Share (FileProvider maps no Uri on a Windows host; the file is written either way).
            app.repository.shareProject(id, format)
            val file = File(app.cacheDir, "exports/Paper art.${format.extension}")
            assertTrue("the gallery's ${format.name} share was written", file.isFile)
            assertOnThePaper(BitmapFactory.decodeFile(file.path), "the gallery's ${format.name} share")
        }
        // The stored artwork is unchanged: its layer is transparent outside the square.
        val doc = app.repository.load(id)
        assertEquals(0, doc.layers.single().bitmap.getPixel(2, 2))
        assertEquals(PaperSpec(cream), doc.paper)
        for (l in doc.layers) l.recycleBitmaps()
    }

    @Test
    fun theEditorsExportsAndShareAreOnThePaper() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val c = paperEditor("Paper editor")
        val actions = EditorActions(c, activity)
        for (format in formats) {
            val before = gallery.values.count { it.size() > 0 }
            actions.exportToGallery(format)
            assertTrue("the editor's ${format.name} export", Smoke.pumpUntil(10_000) { c.busyMessage == null && gallery.values.count { it.size() > 0 } > before })
            val written = gallery.entries.filter { it.value.size() > 0 }.maxBy { it.key.lastPathSegment!!.toInt() }.key
            assertOnThePaper(galleryImage(written, format.name), "the editor's ${format.name} export")
        }
        val png = File(app.cacheDir, "exports/Paper editor.png")
        actions.share()
        assertTrue("the editor's share", Smoke.pumpUntil(10_000) { c.busyMessage == null && png.length() > 0 })
        assertOnThePaper(BitmapFactory.decodeFile(png.path), "the editor's share")
        // The flatten the editor encodes, in every format.
        for (format in formats) {
            val flat = c.compositor.renderFlattened(if (format == ExportFormat.JPEG) 0xFFFFFFFF.toInt() else null, withPaper = true)
            val bytes = ByteArrayOutputStream().also { ImageExport.encode(flat, format, it) }.toByteArray()
            assertOnThePaper(BitmapFactory.decodeByteArray(bytes, 0, bytes.size), "the editor's ${format.name} flatten")
        }
        assertEquals("the layer is still transparent", 0, c.doc.layers.single().bitmap.getPixel(2, 2))
        c.dispose()
    }

    @Test
    fun aLayerExportIsNotOnThePaper() = runBlocking<Unit> {
        val c = paperEditor("Layers")
        val disc = Layer(c.doc.newLayerId(), "Disc", BitmapUtils.createLayerBitmap(64, 48))
        Canvas(disc.bitmap).drawCircle(48f, 30f, 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFC03020.toInt() })
        c.doc.layers += disc
        for (vf in VectorFormat.entries) {
            val scene = ExportSceneBuilder(c, ExportOptions(vf), TextSource.Default, Dispatchers.Unconfined, Dispatchers.Unconfined).build()
            assertEquals("$vf: the paper is the scene's background", cream, scene.background)
            fun all(l: SceneLayer): List<SceneLayer> = listOf(l) + l.children.flatMap { all(it) }
            val pictures = scene.layers.flatMap { all(it) }.flatMap { it.items.filterIsInstance<SceneItem.Image>().map { i -> i.image } }
            assertEquals("$vf: the two layers' pictures", 2, pictures.size)
            val payload = scene.payloadImages
            for (img in pictures + payload) {
                val px = img.source.load().pixels
                assertTrue("$vf: ${img.key} has no paper in it", px.none { it == cream })
            }
            val discLayer = scene.layers.flatMap { all(it) }.single { it.name == "Disc" }
            val discPicture = discLayer.items.filterIsInstance<SceneItem.Image>().single().image.source.load()
            assertEquals("$vf: the disc's corner stays transparent", 0, discPicture.pixels[0] ushr 24)
            assertEquals("$vf: the payload carries the paper itself", PaperSpec(cream), scene.payload!!.paper)
        }
        c.dispose()
    }

    /** A policy that admits the first [admitted] requests and refuses the rest, recording each. */
    private class Refusing(private val admitted: Int = 0) : GovernorPolicy {
        val asked = ArrayList<Pair<Long, String>>()
        override fun onPressure(pressure: MemoryPressure, entries: List<MemoryGovernor.Entry>) {}
        override fun admit(bytes: Long, purpose: String): Boolean {
            asked += bytes to purpose
            return asked.size <= admitted
        }
    }

    private suspend fun refusal(block: suspend () -> Any?): String? = try {
        block()
        null
    } catch (e: NotEnoughMemoryException) {
        e.message
    }

    @Test
    fun aRefusedFlattenSaysWhy() = runBlocking<Unit> {
        val id = storedPaperArtwork("Big")
        val bytes = 64L * 48 * 4
        val none = Refusing()
        MemoryGovernor.policy = none
        for ((format, purpose) in listOf(ExportFormat.PNG to "Export PNG", ExportFormat.WEBP to "Export WEBP", ExportFormat.JPEG to "Export JPG")) {
            assertEquals(String.format(StabilityLabels19.NO_MEMORY_FOR, purpose), refusal { app.repository.exportProject(id, format) })
        }
        assertEquals(String.format(StabilityLabels19.NO_MEMORY_FOR, "Share"), refusal { app.repository.shareProject(id, ExportFormat.PNG) })
        assertEquals("the flatten asks first, for its size", listOf(bytes to "Export PNG", bytes to "Export WEBP", bytes to "Export JPG", bytes to "Share"), none.asked)
        assertTrue("nothing was written", gallery.values.all { it.size() == 0 } && !File(app.cacheDir, "exports/Big.png").exists())
        assertNull("a missing artwork is still a plain failure", app.repository.shareProject("missing-project"))

        // JPEG: the flatten fits, its white copy does not.
        val one = Refusing(admitted = 1)
        MemoryGovernor.policy = one
        assertEquals(String.format(StabilityLabels19.NO_MEMORY_FOR, "Export JPG"), refusal { app.repository.exportProject(id, ExportFormat.JPEG) })
        assertEquals(listOf(bytes to "Export JPG", bytes to "Export JPG"), one.asked)

        // Admitted, the same calls export (DefaultPolicy: v1.8).
        MemoryGovernor.policy = MemoryGovernor.DefaultPolicy
        assertNotNull(app.repository.exportProject(id, ExportFormat.JPEG))
        // The artwork is intact after the refusals.
        val doc = app.repository.load(id)
        assertEquals(blue, doc.layers.single().bitmap.getPixel(30, 20))
        for (l in doc.layers) l.recycleBitmaps()
    }
}
