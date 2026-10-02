package com.brushwork.paint.exchange

import android.net.Uri
import com.brushwork.paint.EditorController
import com.brushwork.paint.exchange.ExchangeFixtures.app
import com.brushwork.paint.exchange.export.ExportOptions
import com.brushwork.paint.exchange.export.ExportSceneBuilder
import com.brushwork.paint.exchange.export.PdfWriter
import com.brushwork.paint.exchange.export.SvgWriter
import com.brushwork.paint.exchange.export.TextSource
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.ui.exchange.ExchangeDialog
import com.brushwork.paint.ui.exchange.ExchangeUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Final QA (v1.5 §4.10 / §4.11): Brushwork files made with the real tools, exported and brought
 * back the way the editor does it (the import question answered "Editable layers"), into an open
 * artwork and into the new artwork the gallery makes for the file.
 */
@RunWith(RobolectricTestRunner::class)
class ExchangeQaRoundTripRobolectricTest {

    private fun allKinds(): EditorController {
        val doc = Smoke.document(480, 360, layers = 2, whiteBottom = true)
        doc.dpi = 300f
        val c = Smoke.controller(app, doc)
        QaExchange.allKinds(c)
        return c
    }

    private fun export(c: EditorController, format: VectorFormat): File {
        val scene = runBlocking { ExportSceneBuilder(c, ExportOptions(format), TextSource.Default, Dispatchers.Unconfined, Dispatchers.Unconfined).build() }
        val out = ByteArrayOutputStream()
        runBlocking { if (format == VectorFormat.SVG) SvgWriter(scene).write(out) else PdfWriter(scene).write(out) }
        return File(app.cacheDir, "qa-roundtrip.${format.extension}").apply { writeBytes(out.toByteArray()) }
    }

    /** Imports [file] into [c] as the editor does, answering "Editable layers". */
    private fun importEditable(c: EditorController, file: File) {
        val state = ExchangeUiState(c)
        state.context = app
        val before = c.doc.layers.size
        state.importUri(Uri.fromFile(file))
        assertTrue("asked", Smoke.pumpUntil { state.dialog is ExchangeDialog.MadeWithBrushwork && c.busyMessage == null })
        state.answerEditable()
        assertTrue("imported", Smoke.pumpUntil { c.busyMessage == null && c.doc.layers.size > before })
    }

    private fun wrapLink(text: Layer): Long = TextCodec.decode(text.textData)!!.wrap.sourceLayerId

    private fun moveWithTransform(c: EditorController, layer: Layer, dx: Float) {
        c.selectLayer(layer)
        c.selectTool(ToolId.TRANSFORM)
        assertTrue(Smoke.pumpUntil { c.currentTool.hasPendingWork })
        val tt = c.currentTool as TransformTool
        tt.moveBy(dx, 0f)
        tt.commit()
        Smoke.pumpUntil { c.busyMessage == null }
    }

    @Test
    fun aWrappedTextImportedIntoAnOpenArtworkFollowsTheImportedPicture() {
        for (format in VectorFormat.entries) {
            val source = allKinds()
            val file = export(source, format)
            // The open artwork has its own layers; "Layer 2" has the id the exported picture had.
            val target = Smoke.controller(app, Smoke.document(480, 360, layers = 2, whiteBottom = true))
            val own = target.doc.layers[1]
            assertEquals("the case that matters", source.doc.layers.single { it.name == "Raster" }.id, own.id)
            importEditable(target, file)
            val raster = target.doc.layers.single { it.name == "Raster" }
            val text = target.doc.layers.single { it.name == "Text: Words flow a" }
            assertEquals("$format: the text wraps the imported picture", raster.id, wrapLink(text))

            // Painting on the artwork's own layer leaves the text alone...
            val reflows = target.textWrap.reflowCount
            val textPixels = ExchangeFixtures.pixels(text.bitmap)
            target.selectLayer(own)
            QaExchange.brush(target, 0xFF00FF00.toInt(), 100f to 150f, 300f to 160f, size = 40f)
            assertEquals("$format: no re-flow for an unrelated layer", reflows, target.textWrap.reflowCount)
            assertTrue(textPixels.contentEquals(ExchangeFixtures.pixels(text.bitmap)))
            // ...moving the imported picture re-flows it, in the same step.
            val steps = target.undoManager.undoCount
            moveWithTransform(target, raster, 150f)
            assertEquals(steps + 1, target.undoManager.undoCount)
            assertEquals("$format: the text follows its picture", reflows + 1, target.textWrap.reflowCount)
            assertNotEquals(textPixels.toList(), ExchangeFixtures.pixels(text.bitmap).toList())
            target.dispose()
            source.dispose()
        }
    }

    private fun svgOf(c: EditorController, options: ExportOptions): org.w3c.dom.Document {
        val scene = runBlocking { ExportSceneBuilder(c, options, TextSource.Default, Dispatchers.Unconfined, Dispatchers.Unconfined).build() }
        val out = ByteArrayOutputStream()
        runBlocking { SvgWriter(scene).write(out) }
        return QaExchange.parseXml(out.toByteArray())
    }

    @Test
    fun everyChoiceOfTheExportSheetChangesTheFile() {
        val c = allKinds()
        val root = { d: org.w3c.dom.Document -> d.documentElement }
        fun group(d: org.w3c.dom.Document, name: String) = QaExchange.layerGroups(d).single { it.getAttributeNS(QaExchange.INKSCAPE_NS, "label") == name }
        val plain = svgOf(c, ExportOptions(VectorFormat.SVG))
        assertTrue(QaExchange.elements(group(plain, "Text: Hello export"), "text").isNotEmpty())
        assertTrue(QaExchange.elements(group(plain, "Vector 1"), "image").isEmpty())
        assertTrue(QaExchange.elements(root(plain), "rect").none { it.getAttribute("id") == "background" })
        assertEquals(1, plain.getElementsByTagNameNS(com.brushwork.paint.exchange.export.Payload.SVG_NAMESPACE, "payload").length)
        assertTrue(QaExchange.layerGroups(plain).none { it.getAttributeNS(QaExchange.INKSCAPE_NS, "label") == "Hidden" })

        val other = svgOf(
            c,
            ExportOptions(
                VectorFormat.SVG,
                strokes = com.brushwork.paint.exchange.export.StrokeExport.PICTURES,
                text = com.brushwork.paint.exchange.export.TextExportMode.OUTLINES,
                includeHidden = true, whiteBackground = true, includePayload = false,
            ),
        )
        // Text as outlines: no <text> anywhere.
        assertEquals(0, QaExchange.elements(root(other), "text").size)
        assertTrue(QaExchange.elements(group(other, "Text: Hello export"), "path").isNotEmpty())
        // Brush strokes as pictures: the vector layer's stroke is a picture now (its paths stay paths).
        assertEquals(1, QaExchange.elements(group(other, "Vector 1"), "image").size)
        assertTrue(QaExchange.elements(group(other, "Vector 1"), "path").size >= 3)
        // A white background under everything, the hidden layer written hidden, no Brushwork data.
        val bg = QaExchange.elements(root(other), "rect").single { it.getAttribute("id") == "background" }
        assertEquals("#ffffff", bg.getAttribute("fill"))
        assertTrue(group(other, "Hidden").getAttribute("style").contains("display:none"))
        assertEquals(0, other.getElementsByTagNameNS(com.brushwork.paint.exchange.export.Payload.SVG_NAMESPACE, "payload").length)
        c.dispose()
    }

    @Test
    fun aBrushworkSvgCanComeInAsOnePicture() {
        val source = allKinds()
        val file = export(source, VectorFormat.SVG)
        val target = Smoke.controller(app, Smoke.document(480, 360, layers = 1))
        val state = ExchangeUiState(target)
        state.context = app
        state.importUri(Uri.fromFile(file))
        assertTrue(Smoke.pumpUntil { state.dialog is ExchangeDialog.MadeWithBrushwork && target.busyMessage == null })
        val steps = target.undoManager.undoCount
        state.answerPicture()
        Smoke.pumpUntil { target.busyMessage == null && target.doc.layers.size >= 2 }
        // "The artwork as a picture": one layer that looks exactly like the artwork (the layer
        // mask, blend modes and texts included, which the SVG drawing alone can't all show).
        assertEquals("message ${target.message}: ${target.doc.layers.map { it.name }}", 2, target.doc.layers.size)
        val picture = target.doc.layers[1]
        assertEquals("Imported SVG (picture)", picture.name)
        assertEquals(steps + 1, target.undoManager.undoCount)
        target.currentTool.commit()
        Smoke.pumpUntil { target.busyMessage == null }
        val flat = source.compositor.renderFlattened()
        val raster = source.doc.layers.single { it.name == "Raster" }
        // A point the raster layer's mask hides: the picture shows what the canvas shows there.
        assertEquals(0, raster.mask!!.getPixel(60, 180) and 0xFF)
        assertEquals(flat.getPixel(60, 180), picture.bitmap.getPixel(60, 180))
        assertTrue("exactly the artwork", ExchangeFixtures.pixels(flat).contentEquals(ExchangeFixtures.pixels(picture.bitmap)))
        target.dispose()
        source.dispose()
    }

    @Test
    fun exportingCommitsAMoveInProgressFirstAsItsOwnStep() {
        val c = allKinds()
        val vector = c.doc.layers.single { it.name == "Vector 1" }
        c.selectLayer(vector)
        c.selectTool(ToolId.TRANSFORM)
        assertTrue(Smoke.pumpUntil { c.currentTool.hasPendingWork })
        (c.currentTool as TransformTool).moveBy(-200f, 0f)
        val steps = c.undoManager.undoCount
        val before = vector.vector!!.objects.map { com.brushwork.paint.vector.VectorOps.bounds(it).left }
        // Export PDF... runs the real job (its pending-work rule), into a file.
        val out = File(app.cacheDir, "moved.pdf")
        val state = ExchangeUiState(c)
        state.context = app
        state.exportOptions = ExportOptions(VectorFormat.PDF)
        val uri = Uri.fromFile(out)
        org.robolectric.Shadows.shadowOf(app.contentResolver).registerOutputStreamSupplier(uri) { java.io.FileOutputStream(out) }
        state.exportTo(uri)
        assertTrue(Smoke.pumpUntil { c.busyMessage == null })
        assertEquals("the move became its own step", steps + 1, c.undoManager.undoCount)
        assertTrue("still a vector layer", vector.isVectorLayer)
        val after = vector.vector!!.objects.map { com.brushwork.paint.vector.VectorOps.bounds(it).left }
        for ((a, b) in before.zip(after)) assertEquals(a - 200f, b, 1f)
        // The file has the moved objects (its Brushwork data equals the document now).
        val r = QaExchange.checkPdf(out.readBytes())
        assertEquals(vector.vector, r.payload()!!.layers.single { it.props.name == "Vector 1" }.vector)
        c.dispose()
    }

    @Test
    fun aWrappedTextRestoredIntoTheGallerysNewArtworkKeepsItsPicture() {
        val source = allKinds()
        val file = export(source, VectorFormat.SVG)
        // The gallery's new artwork for the file: Background + empty Layer 1, replaced by the file.
        val doc = Smoke.document(480, 360, layers = 2, whiteBottom = true)
        doc.dpi = 300f
        doc.layers[0].name = "Background"
        doc.layers[1].name = "Layer 1"
        val c = Smoke.controller(app, doc)
        val uri = Uri.fromFile(file)
        PendingImports.putRequest(doc.id, PendingImport(uri))
        val state = ExchangeUiState(c)
        state.context = app
        state.importUri(uri)
        assertTrue(Smoke.pumpUntil { c.busyMessage == null && c.doc.layers.any { it.name == "Raster" } })
        assertEquals(source.doc.layers.map { it.name }, c.doc.layers.map { it.name })
        val raster = c.doc.layers.single { it.name == "Raster" }
        val text = c.doc.layers.single { it.name == "Text: Words flow a" }
        assertEquals(raster.id, wrapLink(text))
        val reflows = c.textWrap.reflowCount
        moveWithTransform(c, raster, 150f)
        assertEquals("the text follows its picture", reflows + 1, c.textWrap.reflowCount)
    }
}
