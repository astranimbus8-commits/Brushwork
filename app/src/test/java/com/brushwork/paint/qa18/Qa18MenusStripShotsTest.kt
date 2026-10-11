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
 * v1.8 final QA (items 8 and 9) on the user's phone (392 × 873 dp): every tool of the tool menu,
 * picked through the tool menu by a finger, on a raster and on a vector layer, its More row opened
 * by a finger on the toggle ([Qa18MenusShots.walkStrips]). The whole top of the screen is rendered
 * (`menus-392-<nn>-<tool>[-vector].png`) for a human to read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.menusstripshotssandbox"])
class Qa18MenusStripShotsTest {

    @Test
    fun everyToolsStripOpensItsMoreRowWholeOnScreen() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 600_000)
        val h = ChromeHarness()
        for (vector in listOf(false, true)) {
            h.section(if (vector) "every strip on a vector layer" else "every strip on a raster layer") {
                Qa18MenusShots.walkStrips(h, vector, widthDp = 392f)
            }
        }
        dog.interrupt()
        h.finish()
    }
}
