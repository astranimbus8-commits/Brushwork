package com.brushwork.paint.exchange

import android.content.Intent
import android.net.Uri
import com.brushwork.paint.BrushworkApp
import com.brushwork.paint.EditorController
import com.brushwork.paint.EditorSession
import com.brushwork.paint.MainActivity
import com.brushwork.paint.exchange.QaExchange.answerPicker
import com.brushwork.paint.exchange.export.ExportOptions
import com.brushwork.paint.exchange.export.ExportSceneBuilder
import com.brushwork.paint.exchange.export.SvgWriter
import com.brushwork.paint.exchange.export.TextSource
import com.brushwork.paint.exchange.pdf.PageRasterizerFactory
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.ui.editor.CanvasView
import com.brushwork.paint.ui.exchange.GalleryImports
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowToast
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Final QA of the gallery's "New from SVG or PDF" (v1.5 §4.11a) through the real app: the
 * gallery's button, the system picker, the new artwork's size, the editor importing the file
 * (one vector layer per Inkscape layer), saving it on the way back and loading it again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.exchange.qagallerysandbox"])
class GalleryExchangeQaUiRobolectricTest {

    private val failures = mutableListOf<Throwable>()

    private fun section(name: String, block: () -> Unit) {
        Smoke.scopeErrors.clear()
        Smoke.step("section $name")
        try {
            block()
        } catch (t: Throwable) {
            System.err.println("=== SECTION FAILED: $name")
            t.printStackTrace()
            failures += AssertionError("[$name] $t", t)
            // The next section starts in the gallery.
            runCatching { if (app.editorSession != null) backToGallery() }
        }
    }

    private lateinit var activity: MainActivity
    private lateinit var ctl: org.robolectric.android.controller.ActivityController<MainActivity>
    private lateinit var app: BrushworkApp

    private fun projects() = runBlocking { app.repository.list() }

    /** Picks [file] through the gallery's "New from SVG or PDF" and waits for the editor on the new artwork. */
    private fun newFrom(file: File, expectEditor: Boolean = true): EditorController? {
        val before = projects().map { it.id }.toSet()
        click("New from SVG or PDF")
        val request = answerPicker(activity, Uri.fromFile(file), Intent.ACTION_OPEN_DOCUMENT)
        assertTrue(request.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)!!.contains("image/svg+xml"))
        if (!expectEditor) return null
        assertTrue("the editor opened on a new artwork", Smoke.pumpUntil(30_000) {
            settle(1)
            val s = app.editorSession
            (s?.state as? EditorSession.State.Ready) != null && s.projectId !in before &&
                Smoke.find(activity.window.decorView, CanvasView::class.java)?.width ?: 0 > 0
        })
        return (app.editorSession!!.state as EditorSession.State.Ready).controller
    }

    private fun backToGallery() {
        click("Back to gallery", settleAfter = false)
        assertTrue("back in the gallery", Smoke.pumpUntil(30_000) { settle(1); app.editorSession == null && has("New canvas") })
    }

    @Test
    fun newArtworksFromFiles() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        SmokeUi.markBaseline()
        ctl = Robolectric.buildActivity(MainActivity::class.java).setup()
        activity = ctl.get()
        app = activity.application as BrushworkApp
        assertTrue("gallery loaded", Smoke.pumpUntil { settle(1); has("New canvas") })
        section("an Inkscape SVG: its size, one vector layer per layer, saved and loaded") { inkscape() }
        section("a PDF: the gallery's page picker, then the pages") { pdf() }
        section("a Brushwork SVG: restored exactly") { brushwork() }
        section("files that aren't SVG or PDF, and a backed-out picker") { refusals() }
        section("Export PDF while the activity is recreated behind the file picker") { exportAcrossRecreation() }
        dog.interrupt()
        if (failures.isNotEmpty()) {
            val first = failures.first()
            failures.drop(1).forEach { first.addSuppressed(it) }
            throw first
        }
    }

    private fun inkscape() {
        val file = File(activity.cacheDir, "poster.svg").apply { writeBytes(QaExchange.svgFixture("inkscape-layers.svg")) }
        val c = newFrom(file)!!
        assertTrue("imported", Smoke.pumpUntil { settle(1); c.busyMessage == null && c.doc.layers.any { it.name == "Sun" } })
        // 2.5 x 1.5 in at 350 dpi is 875 x 525: scaled up to the 1024 px long side.
        assertEquals(1024 to 614, c.doc.width to c.doc.height)
        assertEquals(350f, c.doc.dpi)
        assertEquals("poster", c.doc.name)
        assertEquals(listOf("Background", "Sky", "Hills", "Sun"), c.doc.layers.map { it.name })
        assertTrue(c.doc.layers.drop(1).all { it.isVectorLayer })
        assertEquals(listOf(1, 1, 2), c.doc.layers.drop(1).map { it.vector!!.objects.size })
        assertFalse("a new artwork made for the file isn't lifted for Transform", c.currentTool.hasPendingWork)
        // (The snackbar needs a few frames to appear.)
        assertTrue("the summary: ${SmokeUi.shown().take(30)}", Smoke.pumpUntil(5_000) { settle(1); has("Imported 4 shapes") })
        // The sky fills the canvas (the file's viewport is the canvas).
        val top = c.doc.layers[1].bitmap.getPixel(512, 1)
        assertTrue("sky blue at the top: ${Integer.toHexString(top)}", top ushr 24 == 0xFF && (top and 0xFF) > ((top shr 16) and 0xFF) + 60)
        val id = app.editorSession!!.projectId
        val expected = c.doc.layers.map { it.name to it.dataSnapshot() }
        backToGallery()
        val saved = runBlocking { app.repository.load(id) }
        assertEquals(expected, saved.layers.map { it.name to it.dataSnapshot() })
        assertEquals(1024 to 614, saved.width to saved.height)
    }

    private fun pdf() {
        val fake = FakeRasterizer(List(2) { 216f to 144f }, listOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt()))
        val saved = GalleryImports.rasterizers
        GalleryImports.rasterizers = PageRasterizerFactory { _, _ -> fake }
        try {
            val file = File(activity.cacheDir, "two pages.pdf").apply { writeBytes("%PDF-1.4\n% two pages\n".toByteArray()) }
            val before = projects().map { it.id }.toSet()
            click("New from SVG or PDF")
            answerPicker(activity, Uri.fromFile(file))
            assertTrue("the page picker", Smoke.pumpUntil { settle(1); has("New artwork from PDF pages", exact = true) })
            click("Select all", exact = true)
            click("Import 2", exact = true)
            assertTrue("the editor opened", Smoke.pumpUntil(30_000) {
                settle(1)
                val s = app.editorSession
                (s?.state as? EditorSession.State.Ready) != null && s.projectId !in before
            })
            val c = (app.editorSession!!.state as EditorSession.State.Ready).controller
            assertTrue("pages imported", Smoke.pumpUntil { settle(1); c.busyMessage == null && c.doc.layers.any { it.name == "Page 2" } })
            // 3 x 2 in at 300 dpi.
            assertEquals(900 to 600, c.doc.width to c.doc.height)
            assertEquals(300f, c.doc.dpi)
            assertEquals(listOf("Background", "Page 1", "Page 2"), c.doc.layers.map { it.name })
            assertEquals(0xFF0000FF.toInt(), c.doc.layers[2].bitmap.getPixel(450, 300))
            backToGallery()
        } finally {
            GalleryImports.rasterizers = saved
        }
    }

    private fun brushwork() {
        // A Brushwork file made with the tools elsewhere.
        val doc = Smoke.document(480, 360, layers = 2, whiteBottom = true)
        doc.dpi = 300f
        val src = Smoke.controller(app, doc)
        QaExchange.allKinds(src)
        val scene = runBlocking { ExportSceneBuilder(src, ExportOptions(VectorFormat.SVG), TextSource.Default, Dispatchers.Unconfined, Dispatchers.Unconfined).build() }
        val out = ByteArrayOutputStream()
        runBlocking { SvgWriter(scene).write(out) }
        val file = File(activity.cacheDir, "mine.svg").apply { writeBytes(out.toByteArray()) }
        val c = newFrom(file)!!
        assertTrue("restored", Smoke.pumpUntil { settle(1); c.busyMessage == null && c.doc.layers.size == src.doc.layers.size && c.doc.layers.any { it.name == "Raster" } })
        assertEquals(480 to 360, c.doc.width to c.doc.height)
        assertEquals(300f, c.doc.dpi)
        assertEquals(src.doc.layers.map { it.name }, c.doc.layers.map { it.name })
        assertEquals(src.doc.layers.map { it.props() }, c.doc.layers.map { it.props() })
        val text = c.doc.layers.single { it.name == "Text: Words flow a" }
        assertEquals(c.doc.layers.single { it.name == "Raster" }.id, TextCodec.decode(text.textData)!!.wrap.sourceLayerId)
        assertTrue(c.doc.layers.single { it.name == "Tone 1" }.isAdjustmentLayer)
        backToGallery()
        src.dispose()
    }

    /**
     * Export PDF…, Letter, Save as…: while the system's file picker is in front, the editor's
     * activity is recreated (a configuration change it doesn't handle itself: font size, dark
     * theme, a language change; or "Don't keep activities"). The picked file must still get the
     * PDF the user asked for, with the options chosen.
     */
    private fun exportAcrossRecreation() {
        val id = runBlocking { app.repository.create(com.brushwork.paint.storage.NewCanvasSpec("Recreated", 400, 300, 300f)) }
        assertTrue(Smoke.pumpUntil { settle(1); has("Recreated", exact = true) })
        click("Recreated", exact = true)
        assertTrue("editor", Smoke.pumpUntil(30_000) {
            settle(1)
            (app.editorSession?.state as? EditorSession.State.Ready) != null && Smoke.find(activity.window.decorView, CanvasView::class.java)?.width ?: 0 > 0
        })
        val c = (app.editorSession!!.state as EditorSession.State.Ready).controller
        assertEquals(id, c.doc.id)
        click("More options")
        click("Export PDF…", exact = true)
        click("Letter", exact = true)
        click("Save as…", exact = true)
        val started = org.robolectric.Shadows.shadowOf(activity).nextStartedActivityForResult ?: throw AssertionError("no picker")
        assertEquals("Recreated.pdf", started.intent.getStringExtra(Intent.EXTRA_TITLE))
        // Recreated behind the picker (Robolectric's recreate() needs frames to run by themselves).
        org.robolectric.shadows.ShadowChoreographer.setPaused(false)
        ctl.recreate()
        org.robolectric.shadows.ShadowChoreographer.setPaused(true)
        activity = ctl.get()
        settle()
        val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2FRecreated.pdf")
        val out = java.io.ByteArrayOutputStream()
        org.robolectric.Shadows.shadowOf(activity.contentResolver).registerOutputStreamSupplier(uri) { out }
        org.robolectric.Shadows.shadowOf(activity).receiveResult(started.intent, android.app.Activity.RESULT_OK, Intent().setData(uri))
        assertTrue("exported", Smoke.pumpUntil(30_000) { settle(1); c.busyMessage == null && out.size() > 0 })
        val bytes = out.toByteArray()
        assertEquals("the file the picker made for a PDF holds a PDF", "%PDF-", String(bytes, 0, 5, Charsets.ISO_8859_1))
        val r = QaExchange.checkPdf(bytes)
        assertEquals("the Letter page chosen before", listOf(0.0, 0.0, 792.0, 612.0), QaExchange.mediaBox(QaExchange.pdfPages(r)[0]).map { Math.round(it * 1000) / 1000.0 })
        backToGallery()
    }

    private fun refusals() {
        val count = projects().size
        val txt = File(activity.cacheDir, "notes.txt").apply { writeText("just some notes, not a picture") }
        newFrom(txt, expectEditor = false)
        assertTrue("told why", Smoke.pumpUntil { settle(1); ShadowToast.getTextOfLatestToast()?.contains("neither an SVG nor a PDF") == true })
        assertEquals("no artwork made", count, projects().size)
        assertTrue(has("New canvas"))
        // An SVG that isn't well-formed at all.
        val broken = File(activity.cacheDir, "broken.svg").apply { writeText("<?xml version=\"1.0\"?><html><body>not svg</body></html>") }
        newFrom(broken, expectEditor = false)
        assertTrue("told why", Smoke.pumpUntil { settle(1); ShadowToast.getTextOfLatestToast()?.contains("SVG") == true })
        assertEquals(count, projects().size)
        // Backing out of the picker does nothing.
        click("New from SVG or PDF")
        answerPicker(activity, null)
        settle()
        assertEquals(count, projects().size)
        assertTrue(has("New canvas"))
    }
}
