package com.brushwork.paint.exchange

import android.graphics.Bitmap
import android.net.Uri
import com.brushwork.paint.EditorController
import com.brushwork.paint.exchange.ExchangeFixtures.app
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.ui.exchange.ExchangeDialog
import com.brushwork.paint.ui.exchange.ExchangeUiState
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VectorOps
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * Final QA (v1.5 §4.11b security): hostile and broken files picked through "Import SVG or PDF…"
 * are imported safely or refused with a message — no entity expansion, nothing read from other
 * files or the network, no stack overflow, no endless work, no infinite coordinates — and never
 * leave a half import, a busy overlay or an undo step behind.
 */
@RunWith(RobolectricTestRunner::class)
class ExchangeQaHostileFilesRobolectricTest {

    private val controllers = ArrayList<EditorController>()

    @After
    fun tearDown() {
        controllers.forEach { it.dispose() }
    }

    private fun controller(): EditorController = Smoke.controller(app, Smoke.document(300, 200, layers = 1)).also { controllers += it }

    private fun file(name: String, bytes: ByteArray) = File(app.cacheDir, name).apply { writeBytes(bytes) }

    /** Imports [f] as the editor's menu entry does; returns the state once nothing is running. */
    private fun import(c: EditorController, f: File, timeoutMs: Long = 30_000): ExchangeUiState {
        val state = ExchangeUiState(c)
        state.context = app
        Smoke.scopeErrors.clear()
        val t0 = System.currentTimeMillis()
        state.importUri(Uri.fromFile(f))
        assertTrue("${f.name} finished", Smoke.pumpUntil(timeoutMs) { c.busyMessage == null })
        println("[qa] ${f.name}: ${System.currentTimeMillis() - t0} ms; message ${c.message}; dialog ${state.dialog}")
        assertTrue("no escaped exception: ${Smoke.scopeErrors}", Smoke.scopeErrors.isEmpty())
        return state
    }

    private fun assertRefused(c: EditorController, steps: Int, what: String) {
        assertEquals("$what: nothing imported", 1, c.doc.layers.size)
        assertEquals("$what: no undo step", steps, c.undoManager.undoCount)
        assertNotNull("$what: the user is told", c.message)
    }

    @Test
    fun entityBombsAndExternalEntitiesStayLiteralText() {
        val c = controller()
        val svg = """<?xml version="1.0"?>
            <!DOCTYPE svg [
              <!ENTITY a "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa">
              <!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;">
              <!ENTITY c "&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;">
              <!ENTITY d "&c;&c;&c;&c;&c;&c;&c;&c;&c;&c;&c;&c;&c;&c;&c;&c;&c;&c;&c;&c;">
              <!ENTITY e "&d;&d;&d;&d;&d;&d;&d;&d;&d;&d;&d;&d;&d;&d;&d;&d;&d;&d;&d;&d;">
              <!ENTITY secret SYSTEM "file:///etc/passwd">
              <!ENTITY remote SYSTEM "http://example.invalid/steal">
            ]>
            <svg xmlns="http://www.w3.org/2000/svg" width="300" height="200" fill="&e;">
              <rect id="&e;" x="10" y="10" width="50" height="50" class="&d;"/>
              <text x="10" y="150" font-size="20">&e;&secret;&remote;</text>
            </svg>""".trimIndent()
        import(c, file("bomb.svg", svg.toByteArray()))
        val vector = c.doc.layers.single { it.isVectorLayer }
        assertEquals(1, vector.vector!!.objects.size)
        val text = c.doc.layers.single { it.isTextLayer }
        val item = com.brushwork.paint.tools.text.TextCodec.decode(text.textData)!!
        assertEquals("entities are never expanded or fetched", "&e;&secret;&remote;", item.text)
        c.currentTool.discard()
    }

    @Test
    fun externalPicturesReferencesAndStyleSheetsAreNeverFetched() {
        val c = controller()
        val secret = file("secret.png", pngBytes(4, 4, 0xFFFF0000.toInt()))
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="300" height="200">
            <style>@import url("http://example.invalid/x.css"); rect { fill: url(http://example.invalid/p.svg#p) blue }</style>
            <image x="0" y="0" width="300" height="200" xlink:href="file://${secret.absolutePath.replace('\\', '/')}"/>
            <image x="0" y="0" width="300" height="200" href="${Uri.fromFile(secret)}"/>
            <image x="0" y="0" width="300" height="200" href="http://example.invalid/a.png"/>
            <image x="0" y="0" width="300" height="200" href="content://com.android.contacts/photo"/>
            <use xlink:href="http://example.invalid/sprites.svg#icon"/>
            <use href="secret.svg#a"/>
            <rect x="10" y="10" width="40" height="40"/>
            </svg>"""
        import(c, file("external.svg", svg.toByteArray()))
        assertTrue("only the rect came in: ${c.doc.layers.map { it.name }}", c.doc.layers.none { it.name == "SVG pictures" })
        assertEquals(1, c.doc.layers.single { it.isVectorLayer }.vector!!.objects.size)
        assertTrue("the summary says what was left out: ${c.message}", c.message!!.contains("external"))
        c.currentTool.discard()
    }

    @Test
    fun deepNestingIsSkippedWithoutAStackOverflow() {
        val c = controller()
        val sb = StringBuilder("""<svg xmlns="http://www.w3.org/2000/svg" width="300" height="200"><rect width="20" height="20"/>""")
        repeat(100_000) { sb.append("<g>") }
        sb.append("""<rect x="50" width="20" height="20"/>""")
        repeat(100_000) { sb.append("</g>") }
        sb.append("""<circle cx="150" cy="100" r="10"/></svg>""")
        import(c, file("deep.svg", sb.toString().toByteArray()))
        val objects = c.doc.layers.single { it.isVectorLayer }.vector!!.objects
        assertEquals("the shallow shapes come in, the buried one is left out", 2, objects.size)
        assertTrue(c.message!!, c.message!!.contains("nested"))
        c.currentTool.discard()
    }

    @Test
    fun hugeAndBrokenNumbersAreClampedAndFinite() {
        val c = controller()
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="300" height="200">
            <rect x="1e308" y="-1e308" width="1e308" height="1e999" stroke="black" stroke-width="1e300"/>
            <path d="M 1e308 1e308 L -1e308 5 L 10 NaN L 20 20 C 1e40 1e40 -1e40 1e40 30 30 Z" fill="red"/>
            <circle cx="150" cy="100" r="40" transform="scale(1e30) rotate(1e30)" fill="green"/>
            <ellipse cx="50" cy="50" rx="1e-30" ry="1e-30"/>
            <polygon points="0,0 1e39,0 1e39,1e39 Infinity,5"/>
            <text x="1e308" y="50" font-size="1e308">big</text>
            <rect x="20" y="120" width="60" height="40" fill="blue"/>
            </svg>"""
        val state = import(c, file("numbers.svg", svg.toByteArray()))
        assertNull(state.dialog)
        val layer = c.doc.layers.single { it.isVectorLayer }
        for (o in layer.vector!!.objects) {
            val b = VectorOps.bounds(o)
            assertTrue("finite bounds $b", b.left.isFinite() && b.top.isFinite() && b.right.isFinite() && b.bottom.isFinite())
            if (o is VPath) for (s in o.subpaths) for (a in s.anchors) assertTrue(a.x.isFinite() && a.y.isFinite())
        }
        // The ordinary rect still lands where it should (the file fits: kept as it is).
        assertEquals(0xFF0000FF.toInt(), layer.bitmap.getPixel(50, 140))
        assertTrue("a text with an absurd size is clamped", c.doc.layers.single { it.isTextLayer }.let { t ->
            com.brushwork.paint.tools.text.TextCodec.decode(t.textData)!!.spec.sizePx <= com.brushwork.paint.tools.text.TextSpec.MAX_SIZE_PX
        })
        // Transform is open on every imported object, far-away ones included: ✓ works and keeps them.
        assertTrue(c.currentTool.hasPendingWork)
        val t0 = System.currentTimeMillis()
        c.currentTool.commit()
        assertTrue(Smoke.pumpUntil { c.busyMessage == null && !c.currentTool.hasPendingWork })
        assertTrue("✓ in ${System.currentTimeMillis() - t0} ms", System.currentTimeMillis() - t0 < 10_000)
        assertTrue(layer.isVectorLayer)
        assertEquals(6, layer.vector!!.objects.size)
    }

    @Test
    fun aGzipBombIsRefusedWhileReading() {
        val c = controller()
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).use { gz ->
            gz.write("""<svg xmlns="http://www.w3.org/2000/svg" width="300" height="200"><!--""".toByteArray())
            val spaces = ByteArray(1 shl 20) { ' '.code.toByte() }
            repeat(64) { gz.write(spaces) } // 64 MB of comment
            gz.write("--></svg>".toByteArray())
        }
        assertTrue("small on disk: ${bytes.size()}", bytes.size() < 200_000)
        val steps = c.undoManager.undoCount
        import(c, file("bomb.svgz", bytes.toByteArray()))
        assertRefused(c, steps, "gzip bomb")
        assertTrue(c.message!!, c.message!!.contains("too large"))
    }

    @Test
    fun aPictureThatDecodesHugeIsSampledDown() {
        val c = controller()
        val png = pngBytes(6000, 6000, 0)
        val b64 = java.util.Base64.getEncoder().encodeToString(png)
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="300" height="200"><image width="300" height="200" href="data:image/png;base64,$b64"/><rect width="10" height="10"/></svg>"""
        import(c, file("bigpicture.svg", svg.toByteArray()))
        assertTrue(c.doc.layers.any { it.name == "SVG pictures" })
        c.currentTool.discard()
    }

    @Test
    fun brokenFilesAreRefusedWithAMessage() {
        val cases = mapOf(
            "truncated.svg" to """<svg xmlns="http://www.w3.org/2000/svg" width="300" height="200"><rect width="50" hei""",
            "html.svg" to """<?xml version="1.0"?><html><body><p>not a picture</p></body></html>""",
            "empty.svg" to """<?xml version="1.0"?>""",
            "binary.pdf" to "%PDF-1.7\n" + "\u0000ÿ".repeat(500),
            "text.txt" to "Shopping list: eggs, milk",
        )
        for ((name, content) in cases) {
            val c = controller()
            val steps = c.undoManager.undoCount
            val state = import(c, file(name, content.toByteArray(Charsets.ISO_8859_1)))
            if (name == "truncated.svg") {
                // What was read before the file broke off: an empty rect is nothing to draw.
                assertNull(state.dialog)
                assertEquals(steps, c.undoManager.undoCount)
                continue
            }
            assertNull("$name: no question asked", state.dialog)
            assertRefused(c, steps, name)
        }
    }

    @Test
    fun aPdfWithoutPagesOrTooBrokenToOpenIsRefused() {
        val c = controller()
        val state = ExchangeUiState(c)
        state.context = app
        state.rasterizers = com.brushwork.paint.exchange.pdf.PageRasterizerFactory { _, _ -> throw ImportException("Protected PDFs can't be opened") }
        val steps = c.undoManager.undoCount
        state.importUri(Uri.fromFile(file("locked.pdf", "%PDF-1.4\n%locked\n".toByteArray())))
        assertTrue(Smoke.pumpUntil { c.busyMessage == null })
        assertEquals("Protected PDFs can't be opened", c.message)
        assertEquals(steps, c.undoManager.undoCount)
        assertTrue(state.dialog !is ExchangeDialog.Pages)
    }

    private fun pngBytes(w: Int, h: Int, color: Int): ByteArray {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        b.eraseColor(color)
        val out = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.PNG, 100, out)
        b.recycle()
        return out.toByteArray()
    }
}
