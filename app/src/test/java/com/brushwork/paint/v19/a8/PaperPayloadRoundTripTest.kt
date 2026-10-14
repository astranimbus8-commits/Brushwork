package com.brushwork.paint.v19.a8

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.exchange.PendingImport
import com.brushwork.paint.exchange.PendingImports
import com.brushwork.paint.exchange.VectorFormat
import com.brushwork.paint.exchange.export.BrushworkPayload
import com.brushwork.paint.exchange.export.ExportOptions
import com.brushwork.paint.exchange.export.ExportSceneBuilder
import com.brushwork.paint.exchange.export.Payload
import com.brushwork.paint.exchange.export.PdfWriter
import com.brushwork.paint.exchange.export.SvgWriter
import com.brushwork.paint.exchange.export.TextSource
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.PaperSpec
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.ui.exchange.ExchangeDialog
import com.brushwork.paint.ui.exchange.ExchangeUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * v1.9 A8 (R15, I13, I21, design 2.4.9): the paper travels in the Brushwork payload of an SVG
 * (base64 of deflated JSON) and of a PDF (a JSON stream). Read back by the editor's import: the
 * new artwork the gallery makes for the file takes the file's paper, outside the import's undo
 * step (undoing the import leaves it); a file without a paper leaves the new artwork without one
 * (v1.8); an import into an open artwork ("Editable layers") never changes its paper. Without a
 * paper the payload has no `paper` key (I13).
 */
@RunWith(RobolectricTestRunner::class)
class PaperPayloadRoundTripTest {
    private val cream = 0xFFF4ECD8.toInt()
    private val app: Context get() = ApplicationProvider.getApplicationContext()

    /** A painted "Ink" layer and a "Wash" layer, with [paper]. */
    private fun source(paper: PaperSpec?): EditorController {
        val d = Document("paper-source", "Source", 80, 60)
        val ink = Layer(d.newLayerId(), "Ink", BitmapUtils.createLayerBitmap(80, 60))
        Canvas(ink.bitmap).drawRect(10f, 10f, 40f, 30f, Paint().apply { color = 0xFF2050C0.toInt() })
        val wash = Layer(d.newLayerId(), "Wash", BitmapUtils.createLayerBitmap(80, 60))
        Canvas(wash.bitmap).drawCircle(55f, 35f, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x80C03020.toInt() })
        d.layers += ink
        d.layers += wash
        d.activeLayerIndex = 1
        d.paper = paper
        return Smoke.controller(app, d)
    }

    private fun export(c: EditorController, format: VectorFormat): File {
        val scene = runBlocking { ExportSceneBuilder(c, ExportOptions(format), TextSource.Default, Dispatchers.Unconfined, Dispatchers.Unconfined).build() }
        val out = ByteArrayOutputStream()
        runBlocking { if (format == VectorFormat.SVG) SvgWriter(scene).write(out) else PdfWriter(scene).write(out) }
        return File(app.cacheDir, "paper-roundtrip-${c.doc.paper != null}.${format.extension}").apply { writeBytes(out.toByteArray()) }
    }

    /** The gallery's new artwork for a file: "Background" (white) and an empty "Layer 1", no paper ([paper] to start with another). */
    private fun newArtwork(paper: PaperSpec? = null): EditorController {
        val doc = Smoke.document(80, 60, layers = 2, whiteBottom = true)
        doc.layers[0].name = "Background"
        doc.layers[1].name = "Layer 1"
        doc.paper = paper
        return Smoke.controller(app, doc)
    }

    /** Imports [file] into the gallery's new artwork [c], as the editor does when it opens. */
    private fun importAsNewArtwork(c: EditorController, file: File) {
        val uri = Uri.fromFile(file)
        PendingImports.putRequest(c.doc.id, PendingImport(uri))
        val state = ExchangeUiState(c)
        state.context = app
        state.importUri(uri)
        assertTrue("restored", Smoke.pumpUntil { c.busyMessage == null && c.doc.layers.any { it.name == "Ink" } })
    }

    /** Imports [file] into the open artwork [c], answering "Editable layers". */
    private fun importIntoOpenArtwork(c: EditorController, file: File) {
        val state = ExchangeUiState(c)
        state.context = app
        val before = c.doc.layers.size
        state.importUri(Uri.fromFile(file))
        assertTrue("asked", Smoke.pumpUntil { state.dialog is ExchangeDialog.MadeWithBrushwork && c.busyMessage == null })
        state.answerEditable()
        assertTrue("imported", Smoke.pumpUntil { c.busyMessage == null && c.doc.layers.size > before })
    }

    @Test
    fun thePayloadCarriesThePaperOnlyWhenThereIsOne() {
        val p = BrushworkPayload(width = 80, height = 60, dpi = 300f, activeLayer = 0, layers = emptyList(), paper = PaperSpec(cream))
        val json = String(Payload.toJson(p), Charsets.UTF_8)
        assertTrue(json, json.contains("\"paper\""))
        assertEquals(PaperSpec(cream), Payload.fromJson(Payload.toJson(p)).paper)
        assertEquals(PaperSpec(cream), Payload.fromBase64(Payload.toBase64(p)).paper)
        val none = p.copy(paper = null)
        assertFalse("I13: no paper key", String(Payload.toJson(none), Charsets.UTF_8).contains("\"paper\""))
        assertNull(Payload.fromJson(Payload.toJson(none)).paper)
        assertNull(Payload.fromBase64(Payload.toBase64(none)).paper)
    }

    @Test
    fun aNewArtworkTakesTheFilesPaperOutsideTheUndoStep() {
        for (format in VectorFormat.entries) {
            val src = source(PaperSpec(cream))
            val file = export(src, format)
            val c = newArtwork()
            val steps = c.undoManager.undoCount
            importAsNewArtwork(c, file)
            assertEquals("$format: the file's layers", listOf("Ink", "Wash"), c.doc.layers.map { it.name })
            assertEquals("$format: the file's paper", PaperSpec(cream), c.doc.paper)
            assertEquals("$format: the layers are not on the paper", 0, c.doc.layers[0].bitmap.getPixel(70, 50))
            assertEquals("$format: one undo step", steps + 1, c.undoManager.undoCount)
            c.undo()
            assertTrue(Smoke.pumpUntil(5_000) { c.doc.layers.map { it.name } == listOf("Background", "Layer 1") })
            assertEquals("$format: undoing the import leaves the paper (I21)", PaperSpec(cream), c.doc.paper)
            c.redo()
            assertTrue(Smoke.pumpUntil(5_000) { c.doc.layers.map { it.name } == listOf("Ink", "Wash") })
            assertEquals(PaperSpec(cream), c.doc.paper)
            c.dispose()
            src.dispose()
        }
    }

    @Test
    fun aFileWithoutAPaperGivesTheNewArtworkNone() {
        for (format in VectorFormat.entries) {
            val src = source(null)
            val file = export(src, format)
            val c = newArtwork(PaperSpec(cream))
            importAsNewArtwork(c, file)
            assertEquals("$format", listOf("Ink", "Wash"), c.doc.layers.map { it.name })
            assertNull("$format: v1.8, no paper", c.doc.paper)
            c.dispose()
            src.dispose()
        }
    }

    @Test
    fun anImportIntoAnOpenArtworkKeepsItsPaper() {
        for (format in VectorFormat.entries) {
            val file = export(source(PaperSpec(cream)), format)
            for (own in listOf(null, PaperSpec())) {
                val c = newArtwork(own)
                importIntoOpenArtwork(c, file)
                assertEquals("$format: the open artwork's layers stay", listOf("Background", "Layer 1"), c.doc.layers.take(2).map { it.name })
                assertTrue("$format: the file's layers come in", c.doc.layers.any { it.name == "Ink" })
                assertEquals("$format: the open artwork's paper ($own)", own, c.doc.paper)
                c.dispose()
            }
        }
    }
}
