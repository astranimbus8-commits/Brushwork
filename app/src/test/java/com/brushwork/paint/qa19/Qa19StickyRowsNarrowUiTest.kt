package com.brushwork.paint.qa19

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.9 F3 (R3 (a), (e); I27): [Qa19StickyRows.canvasTouches] on the 360 dp phone, at font scale
 * 1 and at each of [Qa19Ui.FONT_SCALES]: the open row's lines (more of them at a larger font)
 * still let every canvas touch through, and no text on screen overflows its box with the row open,
 * the Shape tool's "15°" cell included (A9 fits the Shape cells at 2.0, I27).
 *
 * v1.9 Gate 2 (R3 with the v1.9 strip items, design 3.4): [Qa19StickyRows.newItems] at 360 dp and
 * font scale 2.0: the row stays open while each new strip item is used and shows again, open,
 * once its popup, menu, dialog, sheet or panel closes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q360, instrumentedPackages = ["com.brushwork.paint.qa19.stickyrows360sandbox"])
class Qa19StickyRowsNarrowUiTest {

    @Test
    fun theToolsRowStaysOpenAt360AndLargeFonts() = Qa19Ui.uiTest { h ->
        for (scale in listOf(1f) + Qa19Ui.FONT_SCALES) h.section("at font scale $scale") {
            val where = "360 dp at font scale $scale"
            val s = Qa19StickyRows.editor(h, fontScale = scale)
            assertEquals("the phone is 360 dp wide", 360f, s.widthDp, 1f)
            Qa19StickyRows.canvasTouches(s, where)
            Qa19Ui.assertNoTextOverflow("with the row open, $where")
            Qa19StickyRows.closeByToggle(s)
        }
        Qa19StickyRows.newItems(h, "360 dp at font scale 2.0", fontScale = 2f)
    }
}
