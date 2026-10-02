package com.brushwork.paint.exchange

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.brushwork.paint.EditorController
import com.brushwork.paint.exchange.ExchangeFixtures.app
import com.brushwork.paint.exchange.ExchangeFixtures.pixels
import com.brushwork.paint.exchange.QaExchange.INKSCAPE_NS
import com.brushwork.paint.exchange.QaExchange.answerPicker
import com.brushwork.paint.exchange.QaExchange.elements
import com.brushwork.paint.exchange.QaExchange.waitIdle
import com.brushwork.paint.exchange.QaExchange.writableUri
import com.brushwork.paint.exchange.pdf.PageRasterizerFactory
import com.brushwork.paint.exchange.svg.SvgParser
import com.brushwork.paint.model.Document
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.ui.editor.EditorScreen
import com.brushwork.paint.ui.exchange.GalleryImports
import com.brushwork.paint.ui.theme.BrushworkTheme
import com.brushwork.paint.vector.VectorOps
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.w3c.dom.Element
import java.io.File

/**
 * Final QA of the SVG / PDF exchange (v1.5 §4.10, §4.11) as a user meets it on a 392 dp phone:
 * the editor's overflow menu, the export sheet, the system's file pickers (answered like a user
 * would), the busy overlay, the import questions and the summary message; the files written are
 * checked with a real XML parser and the project's own PDF reader.
 *
 * Compose's frame clock only runs in the first test of a sandbox: one test, sections whose failures
 * are collected.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.exchange.qaeditorsandbox"])
class ExchangeQaEditorUiRobolectricTest {

    private val failures = mutableListOf<Throwable>()
    private val activities = mutableListOf<org.robolectric.android.controller.ActivityController<*>>()

    /** The files the export sections wrote (the import sections read them back). */
    private var svgFile: File? = null
    private var pdfFile: File? = null

    /** The documents they were exported from (by file). */
    private val exported = HashMap<File, Document>()

    private fun section(name: String, block: () -> Unit) {
        Smoke.scopeErrors.clear()
        Smoke.step("section $name")
        try {
            block()
            if (Smoke.scopeErrors.isNotEmpty()) throw AssertionError("coroutine errors: ${Smoke.scopeErrors}", Smoke.scopeErrors.first())
        } catch (t: Throwable) {
            System.err.println("=== SECTION FAILED: $name")
            t.printStackTrace()
            failures += AssertionError("[$name] $t", t)
        } finally {
            activities.forEach { runCatching { it.pause().stop().destroy() } }
            activities.clear()
            runCatching { settle() }
        }
    }

    private fun editor(c: EditorController): ComponentActivity {
        SmokeUi.markBaseline()
        val ctl = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activities += ctl
        val activity = ctl.get()
        activity.setContent { BrushworkTheme { EditorScreen(c, onExit = {}, onSaveNow = {}) } }
        settle()
        return activity
    }

    private fun allKindsController(): EditorController {
        val doc = Smoke.document(480, 360, layers = 2, whiteBottom = true)
        doc.dpi = 300f
        val c = Smoke.controller(app, doc)
        QaExchange.allKinds(c)
        return c
    }

    private fun freshController(): EditorController {
        val doc = Smoke.document(480, 360, layers = 2, whiteBottom = true)
        doc.dpi = 300f
        return Smoke.controller(app, doc)
    }

    @Test
    fun exchangeFlows() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        section("export SVG through the menu and the file picker") { exportSvg() }
        section("export PDF through the menu: pages, layers, hidden layers") { exportPdf() }
        section("share an SVG") { shareSvg() }
        section("Brushwork SVG and PDF back into an open artwork") { importOwnFiles() }
        section("a foreign SVG into an open artwork: one step, Transform on its objects") { importForeignSvg() }
        section("import over an untouched Transform lift") { importOverUntouchedLift() }
        section("Undo right after an import takes it back in one press") { undoRightAfterImport() }
        section("a PDF's pages through the page picker") { pdfPagePicker() }
        section("the export sheet fits a 360 dp phone") { exportSheetAt360() }
        dog.interrupt()
        if (failures.isNotEmpty()) {
            val first = failures.first()
            failures.drop(1).forEach { first.addSuppressed(it) }
            throw first
        }
    }

    private fun label(g: Element) = g.getAttributeNS(INKSCAPE_NS, "label")

    // ================================================================== export SVG

    private fun exportSvg() {
        val c = allKindsController()
        val activity = editor(c)
        click("More options")
        click("Export SVG…", exact = true)
        assertTrue("the export sheet", has("Export SVG", exact = true))
        assertTrue("adjustment note", has("Layers below an adjustment layer are exported as one picture", exact = true))
        assertTrue("clipping note", has("Clipping groups are exported as pictures", exact = true))
        click("Include", exact = true)
        click("Save as…", exact = true)
        assertFalse("the sheet closed for the picker", has("Export SVG", exact = true))
        val (uri, out) = writableUri(activity, "Smoke.svg")
        val request = answerPicker(activity, uri, Intent.ACTION_CREATE_DOCUMENT)
        assertEquals("the suggested name", "Smoke.svg", request.getStringExtra(Intent.EXTRA_TITLE))
        assertEquals("image/svg+xml", request.type)
        assertTrue("export finished", waitIdle(c) { out.size() > 0 })
        settle()
        assertTrue("the user is told: ${SmokeUi.shown().take(40)}", has("Saved \"Smoke.svg\""))
        val bytes = out.toByteArray()
        svgFile = File(app.cacheDir, "qa-export.svg").apply { writeBytes(bytes) }.also { exported[it] = c.doc }

        val svg = QaExchange.parseXml(bytes)
        val root = svg.documentElement
        assertEquals("svg", root.localName)
        // 480 x 360 px at 300 dpi.
        assertEquals("40.64mm", root.getAttribute("width"))
        assertEquals("30.48mm", root.getAttribute("height"))
        assertEquals("0 0 480 360", root.getAttribute("viewBox"))
        val groups = QaExchange.layerGroups(svg)
        val layers = c.doc.layers
        val expected = listOf("Tone 1 (merged with the layers below)") + layers.drop(2).filter { !it.clipping }.map { it.name }
        assertEquals(expected, groups.map(::label))
        val g = groups.associateBy(::label)
        assertTrue("hidden layer written hidden", g.getValue("Hidden").getAttribute("style").contains("display:none"))
        assertFalse(g.getValue("Raster").getAttribute("style").contains("display:none"))
        // The raster layer: its picture under a luminance mask.
        val maskRef = g.getValue("Raster").getAttribute("mask")
        assertTrue(maskRef, maskRef.startsWith("url(#"))
        val maskEl = elements(root, "mask").single { "url(#${it.getAttribute("id")})" == maskRef }
        assertTrue(maskEl.getAttribute("style").contains("luminance"))
        assertEquals(1, elements(maskEl, "image").size)
        assertEquals(1, elements(g.getValue("Raster"), "image").size)
        // The clipping group: one picture.
        assertEquals(1, elements(g.getValue("Base"), "image").size)
        assertEquals(0, elements(g.getValue("Base"), "path").size)
        // The vector layer: paths (the stroke's outline, the curve, the shape, the gradient box).
        val vpaths = elements(g.getValue("Vector 1"), "path")
        assertTrue("vector paths: ${vpaths.size}", vpaths.size >= 4)
        val gradFill = vpaths.map { it.getAttribute("fill") }.single { it.startsWith("url(#") }
        val grad = elements(root, "linearGradient").single { "url(#${it.getAttribute("id")})" == gradFill }
        assertEquals(2, elements(grad, "stop").size)
        // Horizontal and wrapped text stay text; vertical and text on a path are outlines.
        fun textOf(name: String) = elements(g.getValue(name), "text").joinToString("\n") { t -> elements(t, "tspan").joinToString(" ") { it.textContent } }
        assertEquals("Hello export", textOf("Text: Hello export"))
        val wrappedLines = elements(g.getValue("Text: Words flow a"), "tspan")
        assertTrue("wrapped into lines: ${wrappedLines.size}", wrappedLines.size >= 3)
        assertEquals(
            "Words flow around the red picture on this layer and keep going for a while.",
            wrappedLines.joinToString(" ") { it.textContent.trim() },
        )
        for (name in listOf("Text: Tate", "Text: Around and a")) {
            assertEquals("$name has no <text>", 0, elements(g.getValue(name), "text").size)
            assertTrue("$name is outlines", elements(g.getValue(name), "path").isNotEmpty())
        }
        // The Brushwork data: every layer, hidden ones too.
        val payload = SvgParser.parse(bytes).payload()!!
        assertEquals(layers.map { it.name }, payload.layers.map { it.props.name })
        Smoke.assertQuiet(c, "SVG exported")
    }

    // ================================================================== export PDF

    private fun exportPdf() {
        val c = allKindsController()
        val activity = editor(c)
        click("More options")
        click("Export PDF…", exact = true)
        assertTrue(has("Export PDF", exact = true))
        assertTrue(has("Canvas 4.1 × 3.0 cm", exact = true))
        click("Letter", exact = true)
        click("Save as…", exact = true)
        val (uri, out) = writableUri(activity, "Smoke.pdf")
        val request = answerPicker(activity, uri, Intent.ACTION_CREATE_DOCUMENT)
        assertEquals("Smoke.pdf", request.getStringExtra(Intent.EXTRA_TITLE))
        assertEquals("application/pdf", request.type)
        assertTrue("export finished", waitIdle(c) { out.size() > 0 })
        settle()
        assertTrue(has("Saved \"Smoke.pdf\""))
        val r = QaExchange.checkPdf(out.toByteArray())
        val pages = QaExchange.pdfPages(r)
        assertEquals(1, pages.size)
        // A landscape artwork on a landscape Letter page.
        assertEquals(listOf(0.0, 0.0, 792.0, 612.0), QaExchange.mediaBox(pages[0]).map { Math.round(it * 1000) / 1000.0 })
        val layers = c.doc.layers
        val (names, off) = QaExchange.ocgs(r)
        val visible = listOf("Tone 1 (merged with the layers below)") + layers.drop(2).filter { !it.clipping && it.visible }.map { it.name }
        assertEquals(visible, names)
        assertTrue(off.isEmpty())
        assertEquals(layers.map { it.name }, r.payload()!!.layers.map { it.props.name })
        pdfFile = File(app.cacheDir, "qa-export.pdf").apply { writeBytes(out.toByteArray()) }.also { exported[it] = c.doc }

        // Again at the canvas size with the hidden layer: its layer is there, switched off.
        click("More options")
        click("Export PDF…", exact = true)
        assertTrue("Letter is remembered", has("The artwork fitted and centred on the page", exact = true))
        click("Canvas 4.1 × 3.0 cm", exact = true)
        assertTrue(has("The canvas size at 300 dpi", exact = true))
        click("Include", exact = true)
        click("Save as…", exact = true)
        val (uri2, out2) = writableUri(activity, "Smoke2.pdf")
        answerPicker(activity, uri2)
        assertTrue(waitIdle(c) { out2.size() > 0 })
        val r2 = QaExchange.checkPdf(out2.toByteArray())
        // 480 x 360 px at 300 dpi = 115.2 x 86.4 pt.
        val box = QaExchange.mediaBox(QaExchange.pdfPages(r2)[0])
        assertEquals(115.2, box[2], 1e-3)
        assertEquals(86.4, box[3], 1e-3)
        val (names2, off2) = QaExchange.ocgs(r2)
        assertTrue(names2.contains("Hidden"))
        assertEquals(listOf("Hidden"), off2)
        // A backed-out picker writes nothing and leaves no busy overlay.
        click("More options")
        click("Export PDF…", exact = true)
        click("Save as…", exact = true)
        answerPicker(activity, null)
        settle()
        assertNull(c.busyMessage)
        Smoke.assertQuiet(c, "PDF exported")
    }

    // ================================================================== share

    private fun shareSvg() {
        val c = allKindsController()
        val activity = editor(c)
        val shared = File(activity.cacheDir, "exports/Smoke.svg")
        shared.delete()
        click("More options")
        click("Export SVG…", exact = true)
        click("Share", exact = true)
        assertTrue("share finished", waitIdle(c))
        settle()
        assertTrue("the shared file was written", shared.length() > 0)
        QaExchange.parseXml(shared.readBytes())
        // FileProvider matches its roots with '/': on a Windows test host no Uri can be made.
        if (File.separatorChar == '/') {
            val chooser = shadowOf(activity).nextStartedActivity
            assertNotNull("the share sheet opened", chooser)
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            @Suppress("DEPRECATION")
            val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("image/svg+xml", send.type)
            @Suppress("DEPRECATION")
            val stream = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            assertEquals("content", stream.scheme)
        }
    }

    // ================================================================== import Brushwork files

    private fun importFrom(activity: ComponentActivity, file: File) {
        click("More options")
        click("Import SVG or PDF…", exact = true)
        val request = answerPicker(activity, Uri.fromFile(file), Intent.ACTION_OPEN_DOCUMENT)
        val types = request.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)!!.toList()
        assertTrue(types.toString(), types.containsAll(listOf("image/svg+xml", "application/pdf")))
    }

    /** [data] with the wrap link of its text left out (layer ids are the document's own). */
    private fun unlinked(data: com.brushwork.paint.model.LayerData): com.brushwork.paint.model.LayerData {
        val t = data.text?.let { com.brushwork.paint.tools.text.TextCodec.decode(it) } ?: return data
        if (!t.wrap.isOn) return data
        return data.copy(text = com.brushwork.paint.tools.text.TextCodec.encode(t.copy(wrap = t.wrap.copy(sourceLayerId = -1L))))
    }

    private fun importOwnFiles() {
        assertTrue("the export sections ran", exported.size == 2)
        for (file in listOfNotNull(svgFile, pdfFile)) {
            val source = exported.getValue(file)
            Smoke.step("import ${file.name}")
            val c = freshController()
            val activity = editor(c)
            val before = c.undoManager.undoCount
            importFrom(activity, file)
            assertTrue("asks what to import", Smoke.pumpUntil { settle(1); has("Made with Brushwork", exact = true) })
            assertEquals("nothing came in while asking", 2, c.doc.layers.size)
            click("Editable layers", exact = true)
            assertTrue("imported", waitIdle(c) { c.doc.layers.size == 2 + source.layers.size })
            settle()
            assertEquals("one undo step", before + 1, c.undoManager.undoCount)
            val added = c.doc.layers.drop(2)
            assertEquals(source.layers.map { it.name }, added.map { it.name })
            assertEquals(source.layers.map { it.props() }, added.map { it.props() })
            assertEquals(source.layers.map { unlinked(it.dataSnapshot()) }, added.map { unlinked(it.dataSnapshot()) })
            // The wrapped text follows the imported picture, not a layer of this artwork that
            // happens to have the exported picture's id.
            val wrapped = added.single { it.name == "Text: Words flow a" }
            val link = com.brushwork.paint.tools.text.TextCodec.decode(wrapped.textData)!!.wrap.sourceLayerId
            assertEquals("the wrapped text's picture", added.single { it.name == "Raster" }.id, link)
            for ((e, a) in source.layers.zip(added)) {
                if (!e.isVectorLayer) assertArrayEquals("${e.name} pixels", pixels(e.bitmap), pixels(a.bitmap))
                assertEquals(e.name, e.mask != null, a.mask != null)
                e.mask?.let { assertArrayEquals("${e.name} mask", pixels(it), pixels(a.mask!!)) }
            }
            assertEquals("the exported file's active layer", source.activeLayer.name, c.activeLayer.name)
            assertTrue("summary: ${SmokeUi.shown().take(40)}", has("Imported ${source.layers.size} layers"))
            // The whole import is one undo.
            c.undo()
            assertEquals(listOf("Layer 1", "Layer 2"), c.doc.layers.map { it.name })
            Smoke.assertQuiet(c, "own file ${file.name}")
            activities.forEach { runCatching { it.pause().stop().destroy() } }
            activities.clear()
            settle()
        }
    }

    // ================================================================== foreign SVG

    private fun fixture(name: String): File = File(app.cacheDir, name).apply { writeBytes(QaExchange.svgFixture(name)) }

    private fun importForeignSvg() {
        val c = freshController()
        val activity = editor(c)
        val before = c.undoManager.undoCount
        val activeBefore = c.doc.activeLayerIndex
        importFrom(activity, fixture("illustrator.svg"))
        assertTrue("imported", waitIdle(c) { c.doc.layers.size == 4 })
        settle()
        assertEquals("one undo step", before + 1, c.undoManager.undoCount)
        val vector = c.doc.layers.single { it.name == "Imported SVG" }
        assertTrue(vector.isVectorLayer)
        assertEquals(8, vector.vector!!.objects.size)
        assertTrue(c.doc.layers.any { it.isTextLayer })
        assertTrue("summary: ${SmokeUi.shown().take(40)}", has("Imported 8 shapes"))
        // Transform opened on the imported objects (lifted as objects, not pixels).
        assertEquals(ToolId.TRANSFORM, c.activeToolId)
        val tt = c.currentTool as TransformTool
        assertTrue(tt.hasPendingWork)
        assertEquals(vector, c.activeLayer)
        assertEquals(vector.vector!!.objects.map { it.id }.toSet(), c.vectors.selectedIds)
        val boundsBefore = vector.vector!!.objects.map { VectorOps.bounds(it) }
        tt.moveBy(20f, 10f)
        settle()
        click("Apply transform edit")
        assertTrue(waitIdle(c) { !c.currentTool.hasPendingWork })
        settle()
        assertEquals("✓ is its own step", before + 2, c.undoManager.undoCount)
        assertTrue("still a vector layer", vector.isVectorLayer)
        val moved = vector.vector!!.objects.map { VectorOps.bounds(it) }
        assertEquals(8, moved.size)
        for ((a, b) in boundsBefore.zip(moved)) {
            assertEquals(a.left + 20f, b.left, 0.6f)
            assertEquals(a.top + 10f, b.top, 0.6f)
        }
        c.undo()
        assertEquals(boundsBefore, vector.vector!!.objects.map { VectorOps.bounds(it) })
        c.undo()
        assertEquals(listOf("Layer 1", "Layer 2"), c.doc.layers.map { it.name })
        assertEquals("the active layer is back", activeBefore, c.doc.activeLayerIndex)
        Smoke.assertQuiet(c, "foreign SVG")
    }

    // ================================================================== untouched lift

    private fun importOverUntouchedLift() {
        val c = freshController()
        c.toggleVectorMode()
        val mine = c.activeLayer
        QaExchange.brush(c, 0xFF000000.toInt(), 50f to 50f, 200f to 80f)
        val objects = mine.vector!!.objects
        assertEquals(1, objects.size)
        val activity = editor(c)
        c.selectTool(ToolId.TRANSFORM)
        assertTrue("Transform lifted the layer's objects", Smoke.pumpUntil { settle(1); c.currentTool.hasPendingWork })
        val before = c.undoManager.undoCount
        importFrom(activity, fixture("figma.svg"))
        assertTrue("imported", waitIdle(c) { c.doc.layers.any { it.name == "Imported SVG" } })
        settle()
        assertEquals("the untouched lift adds no step; the import is one", before + 1, c.undoManager.undoCount)
        assertTrue("my layer is still a vector layer", mine.isVectorLayer)
        assertEquals(objects, mine.vector!!.objects)
        c.currentTool.discard()
        settle()
        Smoke.assertQuiet(c, "import over a lift")
    }

    // ================================================================== undo after import

    private fun undoRightAfterImport() {
        val c = freshController()
        val activity = editor(c)
        val before = c.undoManager.undoCount
        val active = c.doc.activeLayerIndex
        importFrom(activity, fixture("inkscape-layers.svg"))
        assertTrue(waitIdle(c) { c.doc.layers.any { it.name == "Imported SVG" } })
        settle()
        assertTrue("Transform is open on the import", c.currentTool.hasPendingWork)
        assertTrue("Undo is offered", SmokeUi.isEnabled("Undo"))
        click("Undo", exact = true)
        settle()
        assertEquals("one press takes the import back", listOf("Layer 1", "Layer 2"), c.doc.layers.map { it.name })
        assertEquals(before, c.undoManager.undoCount)
        assertEquals(active, c.doc.activeLayerIndex)
        assertFalse("no lift left over", c.currentTool.hasPendingWork)
        assertTrue("Redo brings it back", SmokeUi.isEnabled("Redo"))
        click("Redo", exact = true)
        settle()
        assertTrue(c.doc.layers.single { it.name == "Imported SVG" }.isVectorLayer)
        Smoke.assertQuiet(c, "undo after import")
    }

    // ================================================================== PDF pages

    private fun pdfPagePicker() {
        val fake = FakeRasterizer(List(3) { 595.2756f to 841.8898f }, listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt()))
        val saved = GalleryImports.rasterizers
        GalleryImports.rasterizers = PageRasterizerFactory { _, _ -> fake }
        try {
            val c = freshController()
            val activity = editor(c)
            val before = c.undoManager.undoCount
            val pdf = File(app.cacheDir, "three pages.pdf").apply { writeBytes("%PDF-1.4\n% three pages\n".toByteArray()) }
            importFrom(activity, pdf)
            assertTrue("page picker", Smoke.pumpUntil { settle(1); has("Import PDF pages", exact = true) })
            assertTrue(has("3 pages · 1 selected", exact = true))
            click("Page 3", exact = true)
            assertTrue(has("3 pages · 2 selected", exact = true))
            click("Import 2", exact = true)
            assertTrue("pages imported", waitIdle(c) { c.doc.layers.size == 4 })
            settle()
            assertEquals(listOf("Layer 1", "Layer 2", "Page 1", "Page 3"), c.doc.layers.map { it.name })
            assertEquals(before + 1, c.undoManager.undoCount)
            assertTrue(has("Imported 2 pages"))
            assertTrue("the renderer was closed", fake.closed)
            assertEquals(0xFF0000FF.toInt(), c.doc.layers[3].bitmap.getPixel(240, 180))
            // Without "Transparent background" the paper is white (inside the page only).
            assertEquals("white paper", 0xFFFFFFFF.toInt(), c.doc.layers[3].bitmap.getPixel(240, 40))
            assertEquals("no paper beside the page", 0, c.doc.layers[3].bitmap.getPixel(20, 180) ushr 24)
            c.undo()
            assertEquals(2, c.doc.layers.size)
            Smoke.assertQuiet(c, "PDF pages")
        } finally {
            GalleryImports.rasterizers = saved
        }
    }

    // ================================================================== 360 dp

    private fun exportSheetAt360() {
        org.robolectric.RuntimeEnvironment.setQualifiers("w360dp-h640dp-xhdpi")
        try {
            val c = allKindsController()
            val activity = editor(c)
            click("More options")
            click("Export PDF…", exact = true)
            val window = SmokeUi.windows().last()
            for (label in listOf("Save as…", "Share", "Letter", "A4", "Canvas 4.1 × 3.0 cm", "Include Brushwork data")) {
                val e = SmokeUi.find(label, exact = true) ?: throw AssertionError("\"$label\" not shown at 360 dp")
                assertTrue("\"$label\" inside the screen: ${e.bounds} in ${window.width}", e.bounds.left >= 0f && e.bounds.right <= window.width + 0.5f)
            }
            assertTrue("Save as… is clickable", SmokeUi.isEnabled("Save as…"))
            click("Close", exact = true)
            // The import question's three answers fit too.
            val file = svgFile ?: throw AssertionError("the SVG export section did not run")
            val layers = c.doc.layers.size
            val steps = c.undoManager.undoCount
            importFrom(activity, file)
            assertTrue(Smoke.pumpUntil { settle(1); has("Made with Brushwork", exact = true) })
            val dialog = SmokeUi.windows().last()
            for (label in listOf("Editable layers", "Picture", "Cancel")) {
                val e = SmokeUi.find(label, exact = true) ?: throw AssertionError("\"$label\" not shown at 360 dp")
                assertTrue("\"$label\" inside the dialog: ${e.bounds} in ${dialog.width}", e.bounds.left >= 0f && e.bounds.right <= dialog.width + 0.5f && e.bounds.height >= 40f)
            }
            click("Cancel", exact = true)
            assertFalse(has("Made with Brushwork", exact = true))
            settle()
            assertEquals("nothing imported", layers, c.doc.layers.size)
            assertEquals("no undo step", steps, c.undoManager.undoCount)
            assertNull(c.busyMessage)
        } finally {
            org.robolectric.RuntimeEnvironment.setQualifiers("w392dp-h873dp-xxhdpi")
        }
    }
}
