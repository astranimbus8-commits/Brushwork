package com.brushwork.paint.qa19

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.9 (R8, R10; A2 accept; I27): [Qa19FilterUndo] on the user's 392 dp phone, at font scale 1
 * and at each of [Qa19Ui.FONT_SCALES]: a two-finger tap in a filter panel undoes its last step
 * and the panel stays open; the Chromatic aberration panel's "Blend mode" and "Opacity" rows keep
 * label, value and reset icon apart; an adjustment sheet's change is one "Change <label>" step.
 * The 360 dp phone is [Qa19FilterUndoNarrowUiTest]'s.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q392, instrumentedPackages = ["com.brushwork.paint.qa19.filterundo392sandbox"])
class Qa19FilterUndoUiTest {

    @Test
    fun undoAndResetsInTheFilterPanelsAt392() = Qa19Ui.uiTest { h -> Qa19FilterUndo.run(h, 392f) }
}
