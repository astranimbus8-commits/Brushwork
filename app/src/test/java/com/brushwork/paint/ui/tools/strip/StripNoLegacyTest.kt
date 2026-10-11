package com.brushwork.paint.ui.tools.strip

import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 integration (design §3.9, §6.2): the migration path is gone, and every [ToolId] has its
 * area's converted strip, on a raster and on a vector layer (the rig's fresh 400 × 300 document):
 * something to show (items or a hint), and either the More toggle at 360 or 392 dp (the split of
 * [StripLayout] at [StripInventory.content], with the VECTOR cell when the tool is in vector mode)
 * or a declared reason in [NO_TOGGLE] why everything stays on row 1. On this 392 dp screen the
 * host draws the toggle exactly when the split says so. An entry of [NO_TOGGLE] for a state that
 * shows the toggle, or for no tool state at all, is stale and fails too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.tools.strip.nolegacysandbox"])
class StripNoLegacyTest {
    @Test
    fun everyToolHasAConvertedStripWithAToggleOrAReason() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val rig = StripRig(panel = false).show()
        val bad = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        try {
            for (vector in listOf(false, true)) for (id in ToolId.entries) {
                val key = id.name + if (vector) "@vector" else ""
                seen += key
                rig.select(id, vector)
                if (rig.c.activeToolId != id) {
                    bad += "$key: not selected"
                    continue
                }
                val spec = rig.spec
                if (spec === StripSpec.EMPTY || spec.items.isEmpty() && spec.hint == null) {
                    bad += "$key: no converted strip (nothing to show)"
                    continue
                }
                val chip = if (rig.c.isVectorMode) StripLayout.VECTOR_CHIP_DP + StripLayout.GAP_DP else 0f
                val toggleAt = listOf(360, 392).filter { StripLayout.split(spec, StripInventory.content(it.toFloat()) - chip).toggle }
                if ((392 in toggleAt) != (StripTestKit.toggle() != null)) {
                    bad += "$key: the toggle on the 392 dp screen disagrees with the split (toggle at $toggleAt dp)"
                }
                val reason = NO_TOGGLE[key]
                if (toggleAt.isEmpty() && reason.isNullOrBlank()) bad += "$key: no More toggle at 360 or 392 dp, and no reason in NO_TOGGLE"
                if (toggleAt.isNotEmpty() && reason != null) bad += "$key: the toggle shows at $toggleAt dp, so its NO_TOGGLE entry is stale"
            }
        } finally {
            rig.close()
        }
        for (k in NO_TOGGLE.keys - seen) bad += "$k: in NO_TOGGLE, but no such tool state"
        assertTrue("strips without the migration path:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    private companion object {
        /**
         * The tool states (as `StripInventory` keys them: the [ToolId]'s name, "@vector" on the
         * vector layer) whose strip keeps everything on row 1 at 360 and 392 dp, so it shows no
         * More toggle, and why.
         */
        val NO_TOGGLE: Map<String, String> = buildMap {
            val brush = "four controls, 288 dp: row 1 holds them at 360 dp, with the VECTOR cell too (area D)"
            for (id in listOf(ToolId.BRUSH, ToolId.SMUDGE, ToolId.BLUR)) {
                put(id.name, brush)
                put("${id.name}@vector", brush)
            }
            // On the vector layer the Eraser erases objects: its modes send two controls to the More row.
            put(ToolId.ERASER.name, brush)
            val eyedropper = "three controls, 256 dp, nothing more to show (area C)"
            val transform = "nothing to transform on the rig's empty layers: the status text is the only content (area G)"
            val frames = "no frame layer yet: the button that makes one and the status text, both on row 1 (area G)"
            val remove = "three controls on row 1 only, by design (§3.9, area G)"
            val array = "no array yet: how to make one is a row-1 item, not a hint that would add a More row (area B)"
            val pathfinder = "the operation dropdown, select-all and the status fit at 360 dp; the status leaves row 1 only below (area G)"
            for (vector in listOf(false, true)) {
                val at = if (vector) "@vector" else ""
                put(ToolId.EYEDROPPER.name + at, eyedropper)
                put(ToolId.TRANSFORM.name + at, transform)
                put(ToolId.FRAME_DIVIDER.name + at, frames)
                put(ToolId.REMOVE.name + at, remove)
                put(ToolId.ARRAY.name + at, array)
                put(ToolId.PATHFINDER.name + at, pathfinder)
            }
        }
    }
}
