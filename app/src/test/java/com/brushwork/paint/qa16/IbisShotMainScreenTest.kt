package com.brushwork.paint.qa16

import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.theme.IbisColors
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * The main screen at rest on the user's phone (392 × 873 dp), rendered with real Skia next to
 * ibisPaint's (`ibis-main-screen.png`): the light surround, the top row's grey circles, the two
 * slider rows floating on the surround and the dark bottom bar, sampled at their bands.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.shotmainsandbox"])
class IbisShotMainScreenTest {
    @Test
    fun mainScreenLooksLikeIbisPaint() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        val h = ChromeHarness()
        h.section("main screen idle") {
            val s = h.editor(Smoke.document(300, 430, layers = 2, whiteBottom = true))
            val (st, nav) = s.insetsDp()
            val shot = IbisShots.capture()
            IbisShots.save("main", shot, s.density, ref = "ibis-main-screen.png")
            val hh = s.heightDp
            fun at(x: Float, y: Float) = IbisShots.at(shot, s.density, x, y)
            // The surround between the top row's circles and beside the canvas.
            val surround = at(4f, st + 120f)
            assertTrue("surround ${IbisShots.hex(surround)}", IbisShots.close(IbisColors.Surround.toArgb(), surround))
            // A top-row circle's fill (Redo is disabled on a fresh document: #B1B1B1; Vector is off: #9A9A9A).
            val vector = at(24f + 48f * 2 + 8f - 12f, st + 24f) // (+ 8: the gap after Redo, v16 polish)
            assertTrue("Vector circle ${IbisShots.hex(vector)}", IbisShots.close(IbisColors.TopButton.toArgb(), vector))
            // The bottom bar's grey, between slots.
            val bar = at(56f * 4 + 6f, hh - nav - 4f)
            assertTrue("bottom bar ${IbisShots.hex(bar)}", IbisShots.close(IbisColors.BottomBar.toArgb(), bar))
        }
        dog.interrupt()
        h.finish()
    }
}
