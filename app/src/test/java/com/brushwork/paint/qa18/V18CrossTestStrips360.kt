package com.brushwork.paint.qa18

import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.ui.common.ColorSelectLabels18
import com.brushwork.paint.ui.common.CurveGenLabels18
import com.brushwork.paint.ui.common.MagneticLabels18
import com.brushwork.paint.ui.common.MirrorLabels18
import com.brushwork.paint.ui.common.StripLabels18
import com.brushwork.paint.ui.common.TransformLabels18
import com.brushwork.paint.ui.tools.strip.StripRig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 integration (design §6.2 item 2, scenario e) on a 360 × 640 dp phone: every tool's strip,
 * on the raster and on the vector layer, and the v1.8 states of [V18CrossTestKit.states], with
 * the More row open: at most `StripLayout.MAX_MORE_LINES` lines, nothing clipped, and no handle or
 * visible text inside another where one of the two is a v1.8 text (LabelsV18Test's rule 4 on
 * every strip, with the exemptions CurvesLabelsTest and ShapesLabelsTest carry). The 392 dp
 * phone is [V18CrossTestStrips392].
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.cross360sandbox"])
class V18CrossTestStrips360 {

    @Test
    fun everyStripKeepsTheV18RulesAt360dp() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 300_000)
        val rig = StripRig(panel = false).show()
        try {
            assertEquals("the phone is 360 dp wide", 360, rig.activity.resources.configuration.screenWidthDp)
            V18CrossTestStrips.check(rig)
        } finally {
            rig.close()
            dog.interrupt()
        }
    }
}

/** Scenario e's run and its floors, shared by [V18CrossTestStrips360] and [V18CrossTestStrips392]. */
internal object V18CrossTestStrips {
    fun check(rig: StripRig) {
        val seen = sortedSetOf<String>()
        val walked = mutableListOf<String>()
        assertEquals("the checks catch what they look for", emptyList<String>(), V18CrossTestKit.machineryCatches(rig))
        val problems = V18CrossTestKit.stripsKeepTheRules(rig, seen, walked).toMutableList()
        val dp = rig.activity.resources.configuration.screenWidthDp
        System.err.println("[v18 cross e] $dp dp: ${walked.size} More rows walked; v1.8 texts seen: $seen")
        // The walk reached the strips that carry the v1.8 controls.
        val floor = listOf(
            StripLabels18.MORE_TOGGLE,
            MagneticLabels18.SELECTION_MODE,
            MagneticLabels18.EDGE_REACH,
            ColorSelectLabels18.COLOR_RANGE,
            ColorSelectLabels18.FUZZINESS,
            CurveGenLabels18.CURVES_DESC,
            CurveGenLabels18.CHANGE,
            MirrorLabels18.REMOVE,
            TransformLabels18.MESH_SIZE,
        )
        if (!seen.containsAll(floor)) problems += "the v1.8 controls were not all shown: missing ${floor - seen}"
        if (walked.size < 40) problems += "only ${walked.size} More rows walked: $walked"
        assertTrue("at $dp dp: the strips keep the v1.8 rules:\n" + problems.joinToString("\n"), problems.isEmpty())
    }
}
