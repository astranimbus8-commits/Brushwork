package com.brushwork.paint.qa18

import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 final QA (Color select's polish) at the T606's 392 dp: the "Color range" chip shows the samples as
 * swatches, "Sampled" with none and every other preset's name whole, at its 92 dp
 * ([Qa18SelectRangeChipKit]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.selectrangechip392sandbox"])
class Qa18SelectRangeChipUiTest {

    private val h = ChromeHarness()

    @Test
    fun theChipShowsTheSamplesAndEveryPresetWhole() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 300_000)
        h.section("the range chip at 392 dp") {
            val doc = Smoke.document(Qa18SelectPictures.SIZE, Qa18SelectPictures.SIZE, layers = 1)
            Qa18SelectPictures.colors(doc.layers[0].bitmap)
            val s = h.editor(doc) { it.snapping.enabled = false }
            Qa18SelectRangeChipKit.run(s, 392)
        }
        dog.interrupt()
        h.finish()
    }
}
