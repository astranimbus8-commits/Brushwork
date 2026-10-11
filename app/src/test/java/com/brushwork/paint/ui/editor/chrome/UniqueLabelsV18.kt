package com.brushwork.paint.ui.editor.chrome

import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 I10 (F3, design §4.8, §4.9): the v1.7 strip label walk over every tool's strip, row 1
 * plus the opened More row (a converted strip no longer scrolls; a legacy one is walked sideways
 * as in v1.7). Labels stay unique among the visible clickables at every position and over the
 * whole strip ([UniqueLabelsV17.everyStrip]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.editor.chrome.uniquelabelsv18sandbox"])
class UniqueLabelsV18 {
    @Test
    fun everyStripsLabelsAreUnique() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        val h = ChromeHarness()
        UniqueLabelsV17.everyStrip(h)
        dog.interrupt()
        h.finish()
    }
}
