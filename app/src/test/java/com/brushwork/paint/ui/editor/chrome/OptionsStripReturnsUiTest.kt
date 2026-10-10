package com.brushwork.paint.ui.editor.chrome

import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.tools.FakePillTool
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * The options strip (v1.6 §3.7.2) hides for a tool that shows no options and comes back for the
 * next tool that does: Brush, then a tool without options ([FakePillTool]), then Brush again, and
 * the same once more (a regression test: the hidden strip used to stay hidden for every later
 * tool, because Compose does not measure an unplaced bar again on its own).
 * One test (Compose's frame clock serves the first test of a sandbox only), own sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.editor.chrome.optionsstripreturnssandbox"])
class OptionsStripReturnsUiTest {

    @Test
    fun theStripComesBackAfterAToolWithoutOptions() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        val h = ChromeHarness()
        h.section("the options strip comes back") {
            val s = h.editor(Smoke.document(400, 300, layers = 2, whiteBottom = true))
            val c = s.c
            assertNotNull("Brush shows its options", s.tagged(ChromeTags.OPTIONS_STRIP))
            @Suppress("UNCHECKED_CAST")
            (c.tools as MutableMap<ToolId, Tool>)[ToolId.CURVE] = FakePillTool(c)
            repeat(2) { round ->
                c.selectTool(ToolId.CURVE)
                settle()
                Smoke.pump(300)
                settle()
                assertNull("round $round: no options, no strip", s.tagged(ChromeTags.OPTIONS_STRIP))
                c.selectTool(ToolId.BRUSH)
                settle()
                assertNotNull("round $round: Brush again, the strip is back; shown: ${SmokeUi.shown().take(30)}", s.tagged(ChromeTags.OPTIONS_STRIP))
                assertTrue("with Brush's options in it", SmokeUi.has("Choose brush", exact = true))
            }
            Smoke.assertQuiet(c, "the options strip comes back")
        }
        dog.interrupt()
        h.finish()
    }
}
