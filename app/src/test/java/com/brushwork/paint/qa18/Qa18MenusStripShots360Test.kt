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
 * v1.8 final QA (items 8 and 9) at the design's narrowest phone (360 dp), where AUTO items leave
 * row 1 for the More row: every tool of the tool menu on a raster and on a vector layer, its More
 * row opened by a finger ([Qa18MenusShots.walkStrips]); rendered as `menus-360-*.png`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.menusstripshots360sandbox"])
class Qa18MenusStripShots360Test {

    @Test
    fun everyToolsStripOpensItsMoreRowWholeOnScreenAt360() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 600_000)
        val h = ChromeHarness()
        for (vector in listOf(false, true)) {
            h.section(if (vector) "every strip on a vector layer at 360 dp" else "every strip on a raster layer at 360 dp") {
                Qa18MenusShots.walkStrips(h, vector, widthDp = 360f)
            }
        }
        dog.interrupt()
        h.finish()
    }
}
