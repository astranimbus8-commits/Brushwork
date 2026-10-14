package com.brushwork.paint.v19.a8

import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.EditorController
import com.brushwork.paint.exchange.VectorFormat
import com.brushwork.paint.exchange.export.ExportOptions
import com.brushwork.paint.exchange.export.ExportSceneBuilder
import com.brushwork.paint.exchange.export.TextSource
import com.brushwork.paint.model.PaperSpec
import com.brushwork.paint.qa19.Qa19Harness
import com.brushwork.paint.qa19.Qa19Ui
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.ui.common.CanvasLabels19
import com.brushwork.paint.ui.exchange.ExchangeHost
import com.brushwork.paint.ui.exchange.ExchangeUiState
import com.brushwork.paint.ui.exchange.rememberExchangeUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.9 A8 (R15, step 10): the SVG and PDF export sheet of a document on paper has
 * [CanvasLabels19.INCLUDE_PAPER], on by default. On, the paper is the file's background and the
 * Transparent / White choice is hidden; off, the file has no paper and that choice decides. A
 * document without a paper has no switch. On the 360 dp phone at font scale 1 and at
 * [Qa19Ui.FONT_SCALES].
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q360, instrumentedPackages = ["com.brushwork.paint.v19.a8.includepapersandbox"])
class IncludePaperSwitchTest {
    private val cream = 0xFFF4EEDC.toInt()
    private val white = 0xFFFFFFFF.toInt()

    private fun background(c: EditorController, options: ExportOptions): Int? = runBlocking {
        ExportSceneBuilder(c, options, TextSource.Default, Dispatchers.Unconfined, Dispatchers.Unconfined).build().background
    }

    /** The export sheet's host over a 200 x 150 document (on cream paper when [paper]). */
    private fun sheet(h: Qa19Harness, fontScale: Float, paper: Boolean): Pair<EditorController, ExchangeUiState> {
        val doc = Smoke.document(200, 150, layers = 1)
        if (paper) doc.paper = PaperSpec(cream)
        val c = Smoke.controller(ApplicationProvider.getApplicationContext(), doc)
        var state: ExchangeUiState? = null
        h.show(fontScale) { val s = rememberExchangeUi(c); state = s; ExchangeHost(s) }
        return c to state!!
    }

    private fun onPaper(h: Qa19Harness, fontScale: Float) {
        val where = "at font scale $fontScale"
        val (c, s) = sheet(h, fontScale, paper = true)
        s.requestExport(VectorFormat.SVG)
        Qa19Ui.settle()
        assertTrue("the switch, $where; shown: ${Qa19Ui.shown()}", Qa19Ui.has(CanvasLabels19.INCLUDE_PAPER, exact = true))
        assertTrue("on by default", s.exportOptions.includePaper)
        assertFalse("no Transparent / White while the paper is the background, $where", Qa19Ui.has("Transparent", exact = true))
        assertEquals("the SVG's background is the paper", cream, background(c, s.exportOptions))
        Qa19Ui.assertNoTextOverflow("the SVG sheet, $where")

        Qa19Ui.click(CanvasLabels19.INCLUDE_PAPER, exact = true)
        assertFalse(s.exportOptions.includePaper)
        assertTrue("Transparent / White back, $where", Qa19Ui.has("Transparent", exact = true))
        assertNull("no paper and Transparent: no background", background(c, s.exportOptions))
        Qa19Ui.click("White", exact = true)
        assertEquals("no paper and White", white, background(c, s.exportOptions))
        Qa19Ui.assertNoTextOverflow("the SVG sheet without the paper, $where")

        // The choice is kept for the session; PDF has the same switch.
        s.closeExportSheet()
        s.requestExport(VectorFormat.PDF)
        Qa19Ui.settle()
        assertTrue(where, Qa19Ui.has(CanvasLabels19.INCLUDE_PAPER, exact = true))
        assertFalse("kept off", s.exportOptions.includePaper)
        Qa19Ui.click(CanvasLabels19.INCLUDE_PAPER, exact = true)
        assertTrue(s.exportOptions.includePaper)
        assertEquals("the PDF's background is the paper", cream, background(c, s.exportOptions))
        Qa19Ui.assertNoTextOverflow("the PDF sheet, $where")
    }

    private fun noPaper(h: Qa19Harness) {
        val (c, s) = sheet(h, 1f, paper = false)
        s.requestExport(VectorFormat.SVG)
        Qa19Ui.settle()
        assertFalse("no switch without a paper", Qa19Ui.has(CanvasLabels19.INCLUDE_PAPER, exact = true))
        assertTrue(Qa19Ui.has("Transparent", exact = true))
        assertNull(background(c, s.exportOptions))
    }

    @Test
    fun theSwitchLeavesThePaperOutOfSvgAndPdf() = Qa19Ui.uiTest { h ->
        for (scale in listOf(1f) + Qa19Ui.FONT_SCALES) h.section("a document on paper at font scale $scale") { onPaper(h, scale) }
        h.section("a document without a paper") { noPaper(h) }
    }
}
