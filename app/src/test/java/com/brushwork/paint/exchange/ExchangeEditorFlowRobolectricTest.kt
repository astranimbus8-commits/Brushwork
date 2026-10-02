package com.brushwork.paint.exchange

import android.net.Uri
import com.brushwork.paint.exchange.ExchangeFixtures.app
import com.brushwork.paint.exchange.ExchangeFixtures.controller
import com.brushwork.paint.exchange.export.Payload
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.ui.exchange.ExchangeDialog
import com.brushwork.paint.ui.exchange.ExchangeUiState
import com.brushwork.paint.ui.exchange.GalleryImports
import com.brushwork.paint.ui.exchange.UNREADABLE_DATA
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** v1.5 §4.11 (A8 review): the editor's import flow for SVG files over the limits. */
@RunWith(RobolectricTestRunner::class)
class ExchangeEditorFlowRobolectricTest {

    /** A small file whose references multiply it far beyond the limits, with one red square first. */
    private fun bomb(): File {
        val sb = StringBuilder("""<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="300" height="200"><defs><g id="g7"/>""")
        for (l in 6 downTo 0) {
            sb.append("<g id=\"g$l\">")
            repeat(40) { sb.append("<use xlink:href=\"#g${l + 1}\"/>") }
            sb.append("</g>")
        }
        sb.append("""</defs><rect width="50" height="50" fill="red"/><use xlink:href="#g0"/></svg>""")
        return File(app.cacheDir, "bomb.svg").apply { writeText(sb.toString()) }
    }

    @Test
    fun anSvgOverTheLimitsAsksAndComesInAsAPictureInOneStep() {
        val c = controller(Smoke.document(300, 200, layers = 1))
        val state = ExchangeUiState(c)
        state.context = app
        val before = c.undoManager.undoCount
        state.importUri(Uri.fromFile(bomb()))
        assertTrue("question", Smoke.pumpUntil { state.dialog is ExchangeDialog.TooComplex && c.busyMessage == null })
        // Nothing came in while asking.
        assertEquals(1, c.doc.layers.size)
        assertEquals(before, c.undoManager.undoCount)
        state.answerTooComplex(true)
        assertTrue(Smoke.pumpUntil { c.doc.layers.size == 2 && c.busyMessage == null })
        assertNull(state.dialog)
        assertEquals("Imported SVG (picture)", c.doc.layers[1].name)
        assertEquals(0xFFFF0000.toInt(), c.doc.layers[1].bitmap.getPixel(20, 20))
        assertEquals(before + 1, c.undoManager.undoCount)
    }

    /** A Brushwork SVG whose layer data was damaged (by another app, a broken download...). */
    private fun damaged(): File = File(app.cacheDir, "damaged.svg").apply {
        writeText(
            """<svg xmlns="http://www.w3.org/2000/svg" xmlns:bw="${Payload.SVG_NAMESPACE}" width="300" height="200">
                <metadata><bw:payload version="1">!!! not base64 !!!</bw:payload></metadata>
                <rect width="50" height="50" fill="red"/></svg>""",
        )
    }

    @Test
    fun damagedBrushworkDataFallsBackToTheSvgItself() {
        val c = controller(Smoke.document(300, 200, layers = 1))
        val state = ExchangeUiState(c)
        state.context = app
        state.importUri(Uri.fromFile(damaged()))
        assertTrue(Smoke.pumpUntil { state.dialog is ExchangeDialog.MadeWithBrushwork && c.busyMessage == null })
        state.answerEditable()
        assertTrue(Smoke.pumpUntil { c.doc.layers.size == 2 && c.busyMessage == null })
        val layer = c.doc.layers.single { it.isVectorLayer }
        assertEquals(1, layer.vector!!.objects.size)
        assertTrue(c.message!!, c.message!!.contains(UNREADABLE_DATA))
        c.currentTool.discard()
    }

    @Test
    fun aNewArtworkFromADamagedBrushworkSvgIsSizedAndFilledLikeAnySvg() {
        val bytes = damaged().readBytes()
        val spec = runBlocking { GalleryImports.svgSpec(ImportFile(ImportKind.SVG, "damaged", bytes, null)) }
        assertEquals(NewArtwork.SVG_DPI, spec.dpi)
        val doc = Smoke.document(spec.width, spec.height, layers = 2, whiteBottom = true)
        val c = controller(doc)
        val uri = Uri.fromFile(damaged())
        PendingImports.putRequest(doc.id, PendingImport(uri))
        val state = ExchangeUiState(c)
        state.context = app
        state.importUri(uri)
        assertTrue(Smoke.pumpUntil { c.busyMessage == null && c.doc.layers.any { it.isVectorLayer } })
        assertEquals(2, c.doc.layers.size)
        assertTrue(c.message!!, c.message!!.contains(UNREADABLE_DATA))
    }

    @Test
    fun declinedNothingChanges() {
        val c = controller(Smoke.document(300, 200, layers = 1))
        val state = ExchangeUiState(c)
        state.context = app
        state.importUri(Uri.fromFile(bomb()))
        assertTrue(Smoke.pumpUntil { state.dialog is ExchangeDialog.TooComplex && c.busyMessage == null })
        state.answerTooComplex(false)
        assertNull(state.dialog)
        assertEquals(1, c.doc.layers.size)
    }
}
