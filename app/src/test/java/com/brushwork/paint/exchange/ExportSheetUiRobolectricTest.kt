package com.brushwork.paint.exchange

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.brushwork.paint.exchange.export.PdfPage
import com.brushwork.paint.exchange.export.StrokeExport
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.ui.exchange.ExchangeHost
import com.brushwork.paint.ui.exchange.rememberExchangeUi
import com.brushwork.paint.ui.exchange.ExchangeUiState
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** v1.5 §4.10a (A8): the export sheet at the user's phone size: options, notes, Save as…. */
@RunWith(RobolectricTestRunner::class)
// Own sandbox (the test recomposer policy and paused Choreographer are global).
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.exchange.sheetsandbox"])
class ExportSheetUiRobolectricTest {

    @Test
    fun theSheetSetsTheOptionsAndSaveAsAsksForAFile() {
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val doc = Smoke.document(1050, 1400, layers = 2)
        doc.dpi = 350f
        doc.layers[1].blendMode = LayerBlendMode.ADD
        val c = Smoke.controller(activity, doc)
        var state: ExchangeUiState? = null
        activity.setContent { BrushworkTheme { val s = rememberExchangeUi(c); state = s; ExchangeHost(s) } }
        SmokeUi.settle()
        val s = state!!
        var asked: Pair<VectorFormat, String>? = null
        s.createPicker = { f, name -> asked = f to name }

        s.requestExport(VectorFormat.PDF)
        SmokeUi.settle()
        assertTrue(SmokeUi.has("Export PDF", exact = true))
        assertTrue("canvas page size", SmokeUi.has("Canvas 7.6 × 10.2 cm", exact = true))
        assertTrue("canvas page dpi", SmokeUi.has("The canvas size at 350 dpi", exact = true))
        assertTrue("PDF text note", SmokeUi.has("Text is exported as outlines in PDF", exact = true))
        assertTrue("Add note", SmokeUi.has("Add (Glow) is exported as Screen in PDF", exact = true))
        SmokeUi.click("Pictures (exact look)", exact = true)
        SmokeUi.click("A4", exact = true)
        assertTrue("fit note", SmokeUi.has("The artwork fitted and centred on the page", exact = true))
        SmokeUi.click("White", exact = true)
        assertEquals(StrokeExport.PICTURES, s.exportOptions.strokes)
        assertEquals(PdfPage.A4, s.exportOptions.page)
        assertTrue(s.exportOptions.whiteBackground)
        SmokeUi.click("Save as…", exact = true)
        assertEquals(VectorFormat.PDF to "Smoke.pdf", asked)
        assertNull(s.exportSheet)
        assertFalse(SmokeUi.has("Export PDF", exact = true))

        // SVG keeps the choices made and offers editable text.
        s.requestExport(VectorFormat.SVG)
        SmokeUi.settle()
        assertTrue(SmokeUi.has("Editable where possible", exact = true))
        assertEquals(StrokeExport.PICTURES, s.exportOptions.strokes)
        assertFalse(SmokeUi.has("Add (Glow) is exported as Screen in PDF", exact = true))
    }
}
