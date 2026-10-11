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
 * v1.8 integration (design §6.2 item 2, scenario f) on a 360 × 640 dp phone: MoreRowOverlayTest's
 * rule (D25) on the real strips. Every enabled control of every More row of
 * [V18CrossTestKit.states] (each tool on the raster and on the vector layer, and the v1.8 states)
 * is clicked in turn: a sheet or dialog it opens outlives the row it closed, an anchored popup
 * keeps the row open and leaves it open when Back closes it, and a click that opens nothing
 * leaves the row open unless it changed the tool or is known to close it (an overlay composed in
 * its item, which dies with the row, shows nothing at all).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.crossoverlayssandbox"])
class V18CrossTestOverlays {

    @Test
    fun moreRowOverlaysOutliveTheRowOnEveryStrip() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 600_000)
        val rig = StripRig(panel = false).show()
        try {
            assertEquals("the phone is 360 dp wide", 360, rig.activity.resources.configuration.screenWidthDp)
            assertEquals("the walk catches what it looks for", emptyList<String>(), V18CrossTestKit.overlayMachineryCatches(rig))
            val opened = mutableListOf<String>()
            val problems = V18CrossTestKit.overlaysOutliveTheRow(rig, opened).toMutableList()
            System.err.println("[v18 cross f] ${opened.size} openers:\n" + opened.joinToString("\n"))
            // An overlay that dies with the row never shows a window: the openers the design names
            // (ARCHITECTURE's close rules, each strip's own test) must each be found, of their kind.
            val missing = FLOOR - opened.toSet()
            if (missing.isNotEmpty()) problems += "openers not found (as their kind):\n" + missing.joinToString("\n")
            assertTrue("the More rows' overlays:\n" + problems.joinToString("\n"), problems.isEmpty())
        } finally {
            rig.close()
            dog.interrupt()
        }
    }

    private companion object {
        const val SHEET = "(sheet or dialog)"
        const val POPUP = "(popup)"

        /** The More-row openers that must be found at 360 dp: "state: \"handle\" (kind)". */
        val FLOOR = setOf(
            "FILL: \"Expand 0 px\" $POPUP",
            "MAGIC_WAND: \"Refer: Layer\" $POPUP",
            "MAGIC_WAND: \"Selection menu\" $SHEET",
            "SHAPE: \"Stroke with\" $POPUP",
            "SHAPE: \"Numbers\" $SHEET",
            "SHAPE: \"Shape settings\" $SHEET",
            "CURVE: \"Curve settings\" $SHEET",
            "POLYLINE: \"Polyline settings\" $SHEET",
            "PATH: \"Path settings\" $SHEET",
            "PATH: \"Order 4\" $POPUP",
            "RULER: \"Numbers\" $SHEET",
            "CLONE: \"Choose brush\" $SHEET",
            "CLONE: \"Brush size\" $POPUP",
            "MASK: \"Choose what the mask is for\" $POPUP",
            "MASK: \"Components (0)\" $SHEET",
            "MAGNETIC_LASSO: \"Edge reach\" $POPUP",
            "MAGNETIC_LASSO: \"Edge contrast\" $POPUP",
            "COLOR_SELECT: \"Fuzziness\" $POPUP",
            "PATH@vector, a generated rose: \"Stroke kind\" $POPUP",
            "PATH, a generated rose: \"Change the generated curve\" $SHEET",
            "CURVE@vector, a generated rose: \"Change the generated curve\" $SHEET",
            "CURVE, a generated rose: \"Handles\" $POPUP",
            "CURVE, open with mirror copies, a point selected: \"Point thickness 100 %\" $SHEET",
            "PATH, open with mirror copies, a point selected: \"Point weight\" $POPUP",
            "SHAPE, a cog with mirror copies in Points mode: \"Point roundness\" $SHEET",
            "SHAPE@vector, a holed cog open: \"Shape settings\" $SHEET",
            "TEXT, an open text: \"Numbers\" $SHEET",
            "TEXT@vector, an open text: \"Wrap around picture\" $SHEET",
            "TEXT_FRAMES, a frame selected: \"Wrap around picture\" $POPUP",
            "MASK, a brush part on an adjustment layer: \"Adjust…\" $SHEET",
            "TRANSFORM, box modes: \"Choose interpolation\" $POPUP",
            "TRANSFORM, box modes: \"Numbers\" $SHEET",
            "TRANSFORM, Free deform: \"Choose interpolation\" $POPUP",
        )
    }
}
