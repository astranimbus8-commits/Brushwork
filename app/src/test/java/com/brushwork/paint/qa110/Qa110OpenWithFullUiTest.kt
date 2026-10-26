package com.brushwork.paint.qa110

import com.brushwork.paint.OpenFailedScreen
import com.brushwork.paint.qa19.Qa19Ui
import com.brushwork.paint.storage.ClassicOpen
import com.brushwork.paint.ui.common.EngineLabels110
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.10 (A11 merge, lead; J16, UQ16, design 2.9): the screen an artwork that could not open shows
 * (`MainActivity`'s [OpenFailedScreen]) on a 392 dp and a 360 dp phone at font scale 1, 1.3 and
 * 2.0 (I27). When Classic refused a big artwork for a reason other than memory, the refusal comes
 * with the "Open with Full storage" button above "Back to gallery", and a tap on it reaches the
 * session; when the reason is memory there is no such button. No text is cut.
 */
// Own sandbox (the test recomposer policy and paused Choreographer are global).
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q392, instrumentedPackages = ["com.brushwork.paint.qa110.a11openfullsandbox"])
class Qa110OpenWithFullUiTest {
    @Test
    fun theOpenFailureScreenOffersFullStorage() {
        try {
            Qa19Ui.uiTest { h ->
                for (width in listOf(392, 360)) {
                    RuntimeEnvironment.setQualifiers(if (width == 392) Qa19Ui.Q392 else Qa19Ui.Q360)
                    for (scale in listOf(1f) + Qa19Ui.FONT_SCALES) {
                        val where = "at $width dp, font scale $scale"
                        h.section(where) {
                            // Classic refused for a reason other than memory: the button is offered.
                            var taps = 0
                            var back = 0
                            h.show(fontScale = scale) {
                                OpenFailedScreen(ClassicOpen.refusalText(forMemory = false), offersFullStorage = true, onOpenWithFullStorage = { taps++ }, onBack = { back++ })
                            }
                            assertShown(where, ClassicOpen.refusalText(forMemory = false))
                            assertShown(where, EngineLabels110.OPEN_WITH_FULL)
                            assertShown(where, BACK)
                            Qa19Ui.assertNoTextOverflow(where)
                            Qa19Ui.click(EngineLabels110.OPEN_WITH_FULL, exact = true)
                            assertEquals("$where: the tap reaches the session", 1, taps)
                            assertEquals("$where: not back to the gallery", 0, back)

                            // Classic refused for memory: no button, only the way back.
                            h.show(fontScale = scale) {
                                OpenFailedScreen(ClassicOpen.refusalText(forMemory = true), offersFullStorage = false, onOpenWithFullStorage = { taps++ }, onBack = { back++ })
                            }
                            assertShown("$where, for memory", ClassicOpen.refusalText(forMemory = true))
                            assertFalse("$where, for memory: no Full storage button", Qa19Ui.has(EngineLabels110.OPEN_WITH_FULL, exact = true))
                            Qa19Ui.assertNoTextOverflow("$where, for memory")
                            Qa19Ui.click(BACK, exact = true)
                            assertEquals("$where, for memory: back to the gallery", 1, back)
                            assertEquals("$where, for memory", 1, taps)
                        }
                    }
                }
            }
        } finally {
            RuntimeEnvironment.setQualifiers(Qa19Ui.Q392)
        }
    }

    private fun assertShown(where: String, text: String) =
        assertTrue("$where: \"$text\" on screen; shown: ${Qa19Ui.shown()}", Qa19Ui.has(text, exact = true))
}

/** The open-failure screen's way back (v1.9's text, not a v1.10 label). */
private const val BACK = "Back to gallery"
