package com.brushwork.paint.qa16

import android.content.Intent
import android.net.Uri
import com.brushwork.paint.exchange.QaExchange
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.storage.NewCanvasSpec
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.LetterScaleAlign
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.placement.letterAlignLabel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File

/**
 * v1.6 final QA, letter scaling kept: a scaled ELTON JOHN saved and opened again (same pixels,
 * same settings, still editable through "Edit text"); exported through More › Export SVG… and
 * Export PDF… (as outlines: no `<text>`), brought back with "Editable layers" (the same text
 * layer, scaling on), and the SVG as another app sees it (its data left out) drawn like the canvas.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.lettersexchangesandbox"])
class LettersExchangeUiTest {

    private lateinit var h: ChromeHarness
    private val app get() = RuntimeEnvironment.getApplication()

    private fun pixels(l: Layer) = IntArray(l.width * l.height).also { l.bitmap.getPixels(it, 0, l.width, 0, 0, l.width, l.height) }

    /** ELTON JOHN placed with the Text tool: Bold, 59 px, letters down to 62 %, Top; ✓. */
    private fun elton(s: ChromeScreen): Layer {
        val ui = Qa16Ui(s)
        val c = s.c
        ui.tool("Text")
        ui.tap(c.doc.width / 2f, c.doc.height / 2f)
        assertTrue((c.currentTool as TextTool).editorOpen)
        SmokeUi.field("Text").type("ELTON JOHN")
        settle()
        click("Bold", exact = true)
        ui.textSizePx(59)
        click("Scale letters", exact = true)
        click("Type a value for Smallest letter", exact = true)
        SmokeUi.typeAndDone("Smallest letter", "62")
        click(letterAlignLabel(LetterScaleAlign.TOP), exact = true)
        click("OK", exact = true)
        click("Apply text edit")
        val layer = c.activeLayer
        assertTrue(layer.isTextLayer)
        assertTrue(layer.item().spec.letterScale.isOn)
        return layer
    }

    @Test
    fun savedReopenedExportedAndImported() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        h = ChromeHarness()
        h.section("saved and opened again: same pixels, same settings, still editable") { saveAndReopen() }
        h.section("SVG: outlines, back as editable layers, and as other apps draw it") { svg() }
        h.section("PDF: outlines, back as editable layers") { pdf() }
        dog.interrupt()
        h.finish()
    }

    private fun saveAndReopen() {
        val repo = ProjectRepository(app)
        val id = runBlocking { repo.create(NewCanvasSpec("QA letters", 440, 120, 300f, background = BG)) }
        val s = h.editor(runBlocking { repo.load(id) }) { it.color = WHITE; it.snapping.enabled = false }
        val layer = elton(s)
        val item = layer.item()
        val before = pixels(layer)
        runBlocking { repo.save(s.c.doc, null) }

        val loaded = runBlocking { repo.load(id) }
        assertTrue("no load warnings: ${loaded.loadWarnings}", loaded.loadWarnings.isEmpty())
        val s2 = h.editor(loaded) { it.snapping.enabled = false }
        val c2 = s2.c
        val back = c2.doc.layers.single { it.isTextLayer }
        assertEquals("the same text and settings", item, back.item())
        assertArrayEquals("the same pixels", before, pixels(back))
        // Still editable: the Text tool's "Edit text" on the layer, the letters at Top, switched to Center.
        c2.selectLayer(back)
        val ui = Qa16Ui(s2)
        ui.tool("Text")
        click("Edit text", exact = true)
        val text = c2.currentTool as TextTool
        assertTrue("the editor opens for the layer", text.editorOpen && text.editingLayer === back)
        assertTrue("Smallest letter reads 62 %: ${SmokeUi.shown().filter { "%" in it }}", has("62 %", exact = true))
        click(letterAlignLabel(LetterScaleAlign.CENTER), exact = true)
        click("OK", exact = true)
        val steps = c2.undoManager.undoCount
        click("Apply text edit")
        assertEquals(steps + 1, c2.undoManager.undoCount)
        assertEquals(LetterScaleAlign.CENTER, back.item().spec.letterScale.align)
        assertFalse("the letters moved", before.contentEquals(pixels(back)))
        Smoke.assertQuiet(c2, "reopened")
    }

    /** More › [entry] › Save as… answered with a file; returns its bytes. */
    private fun export(s: ChromeScreen, entry: String, name: String): ByteArray {
        click("More options")
        click(entry, exact = true)
        click("Save as…", exact = true)
        val (uri, out) = QaExchange.writableUri(s.activity, name)
        QaExchange.answerPicker(s.activity, uri, Intent.ACTION_CREATE_DOCUMENT)
        assertTrue("export finished", QaExchange.waitIdle(s.c) { out.size() > 0 })
        settle()
        assertTrue("the user is told: ${SmokeUi.shown().take(40)}", has("Saved \"$name\""))
        return out.toByteArray()
    }

    /** More › Import SVG or PDF… answered with [file]. */
    private fun import(s: ChromeScreen, file: File) {
        click("More options")
        click("Import SVG or PDF…", exact = true)
        QaExchange.answerPicker(s.activity, Uri.fromFile(file), Intent.ACTION_OPEN_DOCUMENT)
    }

    /** "Editable layers": the text layer comes back as it was (one step). */
    private fun backAsEditable(file: File, source: Layer) {
        val s = h.editor(Smoke.document(440, 120, layers = 2, whiteBottom = false)) { it.snapping.enabled = false }
        val c = s.c
        val steps = c.undoManager.undoCount
        import(s, file)
        assertTrue("asks what to import", Smoke.pumpUntil { settle(1); has("Made with Brushwork", exact = true) })
        click("Editable layers", exact = true)
        assertTrue("imported", QaExchange.waitIdle(c) { c.doc.layers.any { it.isTextLayer } })
        settle()
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        val back = c.doc.layers.single { it.isTextLayer }
        assertEquals(source.item(), back.item())
        assertArrayEquals("${file.name}: the same pixels", pixels(source), pixels(back))
        Smoke.assertQuiet(c, "imported ${file.name}")
    }

    private fun svg() {
        val s = h.editor(Smoke.document(440, 120, layers = 2, whiteBottom = false)) { it.color = WHITE; it.snapping.enabled = false }
        val layer = elton(s)
        val bytes = export(s, "Export SVG…", "Smoke.svg")
        val svg = QaExchange.parseXml(bytes)
        val group = QaExchange.layerGroups(svg).single { it.getAttributeNS(QaExchange.INKSCAPE_NS, "label") == layer.name }
        assertEquals("no <text>: outlines", 0, QaExchange.elements(group, "text").size)
        assertTrue("the letters' outlines", QaExchange.elements(group, "path").isNotEmpty())
        val file = File(app.cacheDir, "qa16-elton.svg").apply { writeBytes(bytes) }
        backAsEditable(file, layer)

        // As other apps see it: the SVG without Brushwork's data comes in as shapes, drawn where
        // the letters were.
        val foreign = String(bytes, Charsets.UTF_8).replace(Regex("<metadata[^>]*>.*?</metadata>", RegexOption.DOT_MATCHES_ALL), "")
        assertNotEquals("the data was left out", String(bytes, Charsets.UTF_8), foreign)
        val other = File(app.cacheDir, "qa16-elton-foreign.svg").apply { writeText(foreign) }
        val s3 = h.editor(Smoke.document(440, 120, layers = 1, whiteBottom = false)) { it.snapping.enabled = false }
        val c3 = s3.c
        import(s3, other)
        assertTrue("imported", QaExchange.waitIdle(c3) { c3.doc.layers.size > 1 && c3.activeToolId == ToolId.TRANSFORM })
        settle()
        click("Apply transform edit")
        assertTrue(QaExchange.waitIdle(c3) { !c3.currentTool.hasPendingWork })
        settle()
        val drawn = c3.compositor.renderFlattened()
        val letters = layer.bitmap
        var inter = 0
        var union = 0
        for (y in 0 until 120) for (x in 0 until 440) {
            val a = (letters.getPixel(x, y) ushr 24) >= 128
            val b = (drawn.getPixel(x, y) ushr 24) >= 128
            if (a && b) inter++
            if (a || b) union++
        }
        val iou = inter.toDouble() / union
        Shots.save(c3.compositor.renderFlattened(BG), "letters-svg-as-other-apps-draw-it.png")
        System.err.println("foreign SVG IoU $iou")
        assertTrue("the SVG's outlines cover the letters (IoU $iou)", iou >= 0.9)
        Smoke.assertQuiet(c3, "foreign SVG")
    }

    private fun pdf() {
        val s = h.editor(Smoke.document(440, 120, layers = 2, whiteBottom = false)) { it.color = WHITE; it.snapping.enabled = false }
        val layer = elton(s)
        val bytes = export(s, "Export PDF…", "Smoke.pdf")
        val r = QaExchange.checkPdf(bytes)
        val (names, _) = QaExchange.ocgs(r)
        assertTrue("the text layer is a PDF layer: $names", layer.name in names)
        val payload = r.payload()!!
        assertTrue("its data is in the file", payload.layers.any { it.props.name == layer.name })
        val file = File(app.cacheDir, "qa16-elton.pdf").apply { writeBytes(bytes) }
        backAsEditable(file, layer)
    }

    private companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BG = 0xFF090B0C.toInt()
    }
}
