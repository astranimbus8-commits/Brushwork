package com.brushwork.paint.exchange

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.ui.exchange.PdfPagePicker
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** v1.5 §4.11e (A8): the PDF page picker with a fake 3-page PDF, at the user's phone size. */
// Own sandbox (the test recomposer policy and paused Choreographer are global).
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.exchange.pickersandbox"])
class PdfPagePickerUiRobolectricTest {
    private val a4 = 595.2756f to 841.8898f

    private fun fake(n: Int = 3) = FakeRasterizer(List(n) { a4 }, listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFF000000.toInt()).take(n))

    @Test
    fun thePagePickerShowsThePagesAndReturnsTheChoice() {
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val r = fake()
        var chosen: List<Int>? = null
        activity.setContent { BrushworkTheme { PdfPagePicker(r, onDismiss = {}, onImport = { p, _ -> chosen = p }) } }
        SmokeUi.settle()
        assertTrue(SmokeUi.has("3 pages · 1 selected"))
        assertTrue(SmokeUi.has("Page 3", exact = true))
        SmokeUi.click("Select all", exact = true)
        assertTrue(SmokeUi.has("3 pages · 3 selected"))
        SmokeUi.click("Page 2", exact = true)
        SmokeUi.click("Import 2", exact = true)
        assertEquals(listOf(0, 2), chosen)
    }
}
