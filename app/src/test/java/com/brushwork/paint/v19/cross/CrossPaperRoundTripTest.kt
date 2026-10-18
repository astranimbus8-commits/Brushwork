package com.brushwork.paint.v19.cross

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.BrushworkApp
import com.brushwork.paint.EditorController
import com.brushwork.paint.EditorSession
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.diagnostics.CrashLog
import com.brushwork.paint.exchange.PendingImport
import com.brushwork.paint.exchange.PendingImports
import com.brushwork.paint.exchange.VectorFormat
import com.brushwork.paint.exchange.export.ExportOptions
import com.brushwork.paint.exchange.export.ExportSceneBuilder
import com.brushwork.paint.exchange.export.PdfWriter
import com.brushwork.paint.exchange.export.SvgWriter
import com.brushwork.paint.exchange.export.TextSource
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.model.PaperSpec
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.storage.CanvasStart
import com.brushwork.paint.storage.ExportFormat
import com.brushwork.paint.storage.NewCanvasSpec
import com.brushwork.paint.storage.ProjectFormat
import com.brushwork.paint.storage.backup.BackupService
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.ui.common.BackupLabels19
import com.brushwork.paint.ui.exchange.ExchangeUiState
import com.brushwork.paint.v19.a8.BackupKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs

/**
 * v1.9 Gate 2 cross test M2 (A8 x A4 x A6, design 3.4): an artwork on paper (R15: one layer on
 * white paper) keeps its paper, and its pixels, from the New canvas dialog's start to a restore
 * into a fresh install, and the paper is never pixels on the way (I21).
 *
 * The artwork is made as the dialog's Paper choice makes it and opened in an editor session. A
 * brush stroke and an eraser stroke (A6's drawing path) and a new "Ink" layer are drawn; the
 * layers stay transparent where nothing is painted. The session's save writes the gallery's
 * thumbnail: the artwork as the canvas shows it, on the paper. The gallery's PNG, WEBP and JPEG
 * exports are the same image (PNG and WEBP bit for bit). The SVG and the PDF (Include paper, A4)
 * have the paper as their background and in their payload; imported as a new artwork they give
 * back the layers and the paper. "Merge down" (A4) merges the layers without the paper, as one
 * step that undoes. Closed, the artwork is mirrored to the backup folder (A8) with its paper; after
 * an uninstall the restore brings it back with its paper and its pixels, and a second restore
 * never overwrites it (I23): the copy gets a new id and the "(restored)" suffix.
 */
@RunWith(RobolectricTestRunner::class)
class CrossPaperRoundTripTest {
    private val app: BrushworkApp get() = ApplicationProvider.getApplicationContext()
    private val w = 64
    private val h = 48
    private val white = 0xFFFFFFFF.toInt()
    private val red = 0xFFC02020.toInt()
    private val blue = 0xFF2050C0.toInt()
    private val green = 0xFF208040.toInt()

    /** What MediaStore inserts write to (Robolectric's has no provider: the collection plus a counter). */
    private val gallery = HashMap<Uri, ByteArrayOutputStream>()

    @Before
    fun setUp() {
        BackupKit.freshInstall()
        CrashLog.deleteAll(app)
        CrashLog.resetRepeats()
        Smoke.scopeErrors.clear()
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        for (n in 1..20) {
            val uri = Uri.parse("$collection/$n")
            val out = ByteArrayOutputStream()
            gallery[uri] = out
            shadowOf(app.contentResolver).registerOutputStream(uri, out)
        }
    }

    @After
    fun tearDown() {
        BackupKit.idle()
        CrashLog.drain()
        CrashLog.deleteAll(app)
    }

    @Test
    fun aPaperArtworkKeepsItsPaperAndPixelsFromTheNewCanvasToARestore() {
        BackupKit.choose()

        // ---- A8: the New canvas dialog's Paper choice.
        val id = runBlocking { app.repository.create(NewCanvasSpec("Paper cross", w, h, 300f, null, CanvasStart.PAPER)) }
        runBlocking { app.repository.load(id) }.let { doc ->
            assertEquals("R15: one layer", listOf("Layer 1"), doc.layers.map { it.name })
            assertEquals("on white paper", PaperSpec(), doc.paper)
            assertEquals("the layer is empty", 0, doc.layers.single().bitmap.getPixel(2, 2))
            doc.layers.forEach { it.recycleBitmaps() }
        }

        // ---- A6: the editor draws on the layer, never on the paper.
        val session = EditorSession(app, id)
        assertTrue("the editor opened", Smoke.pumpUntil { session.state is EditorSession.State.Ready })
        val c = (session.state as EditorSession.State.Ready).controller
        val layer1 = c.doc.layers.single()
        val rec = c.beginEdit(layer1)
        rec.touch(Rect(8, 8, 40, 36))
        Canvas(layer1.bitmap).drawRect(8f, 8f, 40f, 36f, Paint().apply { color = red })
        assertTrue(c.commitEdit(rec, "Paint"))
        c.color = green
        c.brush = BrushLibrary.defaultBrush.copy(size = 6f)
        c.selectTool(ToolId.BRUSH)
        stroke(c, 10f to 42f, 56f to 42f)
        c.selectTool(ToolId.ERASER)
        c.eraser = BrushLibrary.defaultEraser.copy(size = 8f)
        stroke(c, 24f to 4f, 24f to 40f)
        val ink = c.addLayerWithContent("Ink", "Add layer") { cv -> cv.drawRect(30f, 20f, 58f, 30f, Paint().apply { color = blue }) }!!
        assertEquals(listOf("Layer 1", "Ink"), c.doc.layers.map { it.name })
        assertEquals("the paper stays", PaperSpec(), c.doc.paper)
        assertEquals("the layer is transparent where nothing is painted", 0, layer1.bitmap.getPixel(60, 2) ushr 24)
        assertEquals("and where the eraser went", 0, layer1.bitmap.getPixel(24, 20) ushr 24)
        val onPaper = flatOnPaper(c)
        assertEquals("the canvas shows the paper there", white, onPaper[20 * w + 24])
        assertEquals(white, onPaper[2 * w + 60])
        assertEquals(red, onPaper[12 * w + 12])
        assertEquals(blue, onPaper[25 * w + 50])
        assertTrue("the stroke", onPaper[42 * w + 50] != white)

        // ---- A8: the save writes the gallery's thumbnail, on the paper.
        await("the save", session.saveNow())
        val thumb = decode(File(BackupKit.local(id), ProjectFormat.THUMB_FILE).readBytes(), "the thumbnail")
        assertArrayEquals("the thumbnail is the artwork on its paper", onPaper, pixels(thumb))

        // ---- A8: the gallery's raster exports, on the paper.
        for (format in listOf(ExportFormat.PNG, ExportFormat.WEBP, ExportFormat.JPEG)) {
            val uri = runBlocking { app.repository.exportProject(id, format) }
            val bytes = gallery[uri]?.toByteArray() ?: throw AssertionError("${format.name}: nothing written for $uri")
            val px = pixels(decode(bytes, format.name))
            if (format == ExportFormat.JPEG) {
                for ((x, y) in listOf(60 to 2, 24 to 20, 12 to 12, 50 to 25)) {
                    assertTrue("JPEG at ($x, $y): ${hex(px[y * w + x])}", near(onPaper[y * w + x], px[y * w + x]))
                }
            } else {
                assertArrayEquals("${format.name}: bit for bit the artwork on its paper", onPaper, px)
            }
        }

        // ---- A4: SVG and PDF with Include paper; imported as a new artwork, layers and paper come back.
        val layerPixels = c.doc.layers.map { pixels(it.bitmap) }
        for (vf in VectorFormat.entries) {
            val options = ExportOptions(vf)
            assertTrue("$vf: Include paper is on by default", options.includePaper)
            val scene = runBlocking { ExportSceneBuilder(c, options, TextSource.Default, Dispatchers.Unconfined, Dispatchers.Unconfined).build() }
            assertEquals("$vf: the paper is the background", white, scene.background)
            assertEquals("$vf: the payload carries the paper", PaperSpec(), scene.payload!!.paper)
            val out = ByteArrayOutputStream()
            runBlocking { if (vf == VectorFormat.SVG) SvgWriter(scene).write(out) else PdfWriter(scene).write(out) }
            val file = File(app.cacheDir, "cross-paper.${vf.extension}").apply { writeBytes(out.toByteArray()) }
            val imported = importAsNewArtwork(file, "$vf")
            assertEquals("$vf: the paper", PaperSpec(), imported.doc.paper)
            assertEquals("$vf: the layers", listOf("Layer 1", "Ink"), imported.doc.layers.map { it.name })
            for (k in layerPixels.indices) assertArrayEquals("$vf: ${imported.doc.layers[k].name}'s pixels", layerPixels[k], pixels(imported.doc.layers[k].bitmap))
            assertArrayEquals("$vf: the same artwork on its paper", onPaper, flatOnPaper(imported))
            imported.dispose()
        }

        // ---- A4: "Merge down" merges the layers, never the paper (I21), as one step.
        val steps = c.undoManager.undoCount
        c.mergeDown(ink)
        assertEquals(listOf("Layer 1"), c.doc.layers.map { it.name })
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(PaperSpec(), c.doc.paper)
        val merged = pixels(c.doc.layers.single().bitmap)
        assertEquals("the merged layer is transparent where nothing is painted", 0, merged[2 * w + 60] ushr 24)
        assertEquals("no paper in it", 0, merged.count { it == white })
        assertArrayEquals("the same artwork on its paper", onPaper, flatOnPaper(c))
        c.undo()
        assertEquals(listOf("Layer 1", "Ink"), c.doc.layers.map { it.name })
        for (k in layerPixels.indices) assertArrayEquals("undo: ${c.doc.layers[k].name}", layerPixels[k], pixels(c.doc.layers[k].bitmap))
        c.redo()
        assertArrayEquals("redo", merged, pixels(c.doc.layers.single().bitmap))

        // ---- A8: closing saves; the backup mirrors the artwork with its paper.
        var closed = false
        session.close { closed = true }
        assertTrue("the editor closed", Smoke.pumpUntil { closed })
        BackupKit.idle()
        assertEquals("the backup's copy is on paper", PaperSpec(), ProjectFormat.read(BackupKit.remote(id)).paper)
        assertTrue(BackupKit.index().artworks.any { it.id == id })
        val savedThumb = File(BackupKit.local(id), ProjectFormat.THUMB_FILE).readBytes()
        assertArrayEquals("the closing save's thumbnail", onPaper, pixels(decode(savedThumb, "the closing thumbnail")))

        // ---- A8: uninstall, install again and restore: the paper and the pixels come back.
        BackupKit.reinstall()
        assertTrue(runBlocking { app.repository.list() }.isEmpty())
        val src = BackupKit.source()
        val item = runBlocking { BackupService.listRestore(app, src) }.single()
        assertEquals(id, item.id)
        assertTrue(item.checkedByDefault)
        assertEquals(1, runBlocking { BackupService.restore(app, src, listOf(id), alsoSettings = false) })
        assertRestored(id, merged, onPaper, "restored")
        assertArrayEquals("the same thumbnail", savedThumb, File(BackupKit.local(id), ProjectFormat.THUMB_FILE).readBytes())

        // ---- I23: a second restore never overwrites the artwork: a copy with a new id.
        val before = BackupKit.local(id).listFiles().orEmpty().associate { it.name to it.readBytes().toList() }
        val again = runBlocking { BackupService.listRestore(app, src) }.single()
        assertTrue("already in the gallery: unchecked", again.already && !again.checkedByDefault)
        assertEquals(1, runBlocking { BackupService.restore(app, src, listOf(id), alsoSettings = false) })
        assertEquals("the gallery's artwork byte for byte", before, BackupKit.local(id).listFiles().orEmpty().associate { it.name to it.readBytes().toList() })
        val infos = runBlocking { app.repository.list() }
        assertEquals(setOf("Paper cross", "Paper cross" + BackupLabels19.RESTORED_SUFFIX), infos.map { it.name }.toSet())
        val copy = infos.single { it.id != id }.id
        assertRestored(copy, merged, onPaper, "the copy")

        CrashLog.drain()
        assertEquals("no crash and no handled report", emptyList<String>(), CrashLog.reports(app).map { "${it.id}: ${it.summary}" })
        assertEquals("no coroutine failed", emptyList<String>(), Smoke.scopeErrors.map { it.toString() })
    }

    /** The stored artwork [id]: on white paper, one layer with [layer]'s pixels, [onPaper] flattened on it. */
    private fun assertRestored(id: String, layer: IntArray, onPaper: IntArray, where: String) {
        val doc = runBlocking { app.repository.load(id) }
        try {
            assertEquals("$where: the paper", PaperSpec(), doc.paper)
            assertEquals("$where: the layer", listOf("Layer 1"), doc.layers.map { it.name })
            assertArrayEquals("$where: its pixels", layer, pixels(doc.layers.single().bitmap))
            val flat = Compositor(doc) { null }.renderFlattened(withPaper = true)
            assertArrayEquals("$where: on its paper", onPaper, pixels(flat))
            flat.recycle()
        } finally {
            doc.layers.forEach { it.recycleBitmaps() }
        }
        val thumb = decode(File(BackupKit.local(id), ProjectFormat.THUMB_FILE).readBytes(), "$where: thumbnail")
        assertArrayEquals("$where: the gallery's thumbnail", onPaper, pixels(thumb))
    }

    /** A stroke of the current tool through the controller's canvas input (the identity view). */
    private fun stroke(c: EditorController, from: Pair<Float, Float>, to: Pair<Float, Float>) {
        val steps = c.undoManager.undoCount
        val n = 12
        fun at(k: Int) = ToolPoint(from.first + (to.first - from.first) * k / n, from.second + (to.second - from.second) * k / n, 1f, 8L * k)
        c.pointerDown(at(0))
        for (k in 1 until n) c.pointerMove(at(k))
        c.pointerUp(at(n))
        assertTrue("the stroke landed", Smoke.pumpUntil(10_000) { c.undoManager.undoCount == steps + 1 && c.busyMessage == null })
    }

    /** Imports [file] into a gallery's new artwork for it (no paper), as the editor does when it opens. */
    private fun importAsNewArtwork(file: File, where: String): EditorController {
        val doc = Smoke.document(w, h, layers = 2, whiteBottom = true)
        doc.layers[0].name = "Background"
        doc.layers[1].name = "Layer 1"
        val c = Smoke.controller(app, doc)
        val uri = Uri.fromFile(file)
        PendingImports.putRequest(c.doc.id, PendingImport(uri))
        val state = ExchangeUiState(c)
        state.context = app
        state.importUri(uri)
        assertTrue("$where: imported", Smoke.pumpUntil { c.busyMessage == null && c.doc.layers.any { it.name == "Ink" } })
        return c
    }

    private fun await(what: String, job: Job) = assertTrue(what, Smoke.pumpUntil { job.isCompleted })

    /** The artwork as the canvas, the thumbnails and the flattened exports show it: on its paper. */
    private fun flatOnPaper(c: EditorController): IntArray {
        val flat = c.compositor.renderFlattened(withPaper = true)
        try { return pixels(flat) } finally { flat.recycle() }
    }

    private fun decode(bytes: ByteArray, what: String): Bitmap =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size).also { assertNotNull("$what: decoded", it) }!!.also {
            assertEquals("$what: the artwork's size", w to h, it.width to it.height)
        }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** JPEG is lossy: at most 6 per channel. */
    private fun near(a: Int, b: Int): Boolean = (0..24 step 8).all { s -> abs(((a ushr s) and 0xFF) - ((b ushr s) and 0xFF)) <= 6 }

    private fun hex(c: Int) = String.format("#%08X", c)
}
