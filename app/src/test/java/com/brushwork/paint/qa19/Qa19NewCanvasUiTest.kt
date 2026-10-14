package com.brushwork.paint.qa19

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.9 A8 (R15; I27): the New canvas dialog's "Background" row on the 392 dp phone, at font scale
 * 1 and at each of [Qa19Ui.FONT_SCALES] ([Qa19NewCanvas.dialog]), and the gallery's "New canvas"
 * making one layer over the paper ([Qa19NewCanvas.gallery]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q392, instrumentedPackages = ["com.brushwork.paint.qa19.newcanvas392sandbox"])
class Qa19NewCanvasUiTest {

    @Test
    fun theBackgroundRowAt392AndLargeFonts() = Qa19Ui.uiTest { h ->
        for (scale in listOf(1f) + Qa19Ui.FONT_SCALES) h.section("the dialog at font scale $scale") {
            Qa19NewCanvas.dialog(h, scale, 392f)
        }
        h.section("the gallery makes one layer") { Qa19NewCanvas.gallery(h, 392f) }
    }
}
