package com.brushwork.paint.qa19

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.9 (R8, R10; A2 accept; I27): [Qa19FilterUndo] on the 360 dp phone, at font scale 1 and at
 * each of [Qa19Ui.FONT_SCALES] (design R8 (e): the two-finger tap at 360 dp, the Chromatic
 * aberration panel at font scale 1.3; R8 (a): at 360 dp an adjustment sheet's label row keeps the
 * value and the reset icon on one line). The 392 dp phone is [Qa19FilterUndoUiTest]'s.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q360, instrumentedPackages = ["com.brushwork.paint.qa19.filterundo360sandbox"])
class Qa19FilterUndoNarrowUiTest {

    @Test
    fun undoAndResetsInTheFilterPanelsAt360() = Qa19Ui.uiTest { h -> Qa19FilterUndo.run(h, 360f) }
}
