package com.brushwork.paint.qa18

import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.ui.tools.strip.StripRig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 integration (design §6.2 item 2, scenario e) on the T606 (392 × 873 dp): every tool's strip,
 * on the raster and on the vector layer, and the v1.8 states of [V18CrossTestKit.states], with
 * the More row open: at most `StripLayout.MAX_MORE_LINES` lines, nothing clipped, and no handle or
 * visible text inside another where one of the two is a v1.8 text (LabelsV18Test's rule 4 on
 * every strip, with the exemptions CurvesLabelsTest and ShapesLabelsTest carry). The 360 dp
 * phone is [V18CrossTestStrips360].
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.cross392sandbox"])
class V18CrossTestStrips392 {

    @Test
    fun everyStripKeepsTheV18RulesAt392dp() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 300_000)
        val rig = StripRig(panel = false).show()
        try {
            assertEquals("the phone is 392 dp wide", 392, rig.activity.resources.configuration.screenWidthDp)
            V18CrossTestStrips.check(rig)
        } finally {
            rig.close()
            dog.interrupt()
        }
    }
}
