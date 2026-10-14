package com.brushwork.paint.qa19

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.9 A8 (R15; I27): the New canvas dialog's "Background" row on the 360 dp phone, at font scale
 * 1 (one line) and at each of [Qa19Ui.FONT_SCALES] ([Qa19NewCanvas.dialog]), and the gallery's
 * "New canvas" making one layer over the paper ([Qa19NewCanvas.gallery]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q360, instrumentedPackages = ["com.brushwork.paint.qa19.newcanvas360sandbox"])
class Qa19NewCanvasNarrowUiTest {

    @Test
    fun theBackgroundRowAt360AndLargeFonts() = Qa19Ui.uiTest { h ->
        for (scale in listOf(1f) + Qa19Ui.FONT_SCALES) h.section("the dialog at font scale $scale") {
            Qa19NewCanvas.dialog(h, scale, 360f)
        }
        h.section("the gallery makes one layer") { Qa19NewCanvas.gallery(h, 360f) }
    }
}
