package com.brushwork.paint.qa110

import com.brushwork.paint.qa19.Qa19Ui
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.10 (R6, R7; A4 accept; I27): [Qa110CdcObjects] on a 360 dp phone, at font scale 1 and at
 * each of [Qa19Ui.FONT_SCALES]. The 392 dp phone is [Qa110CdcObjectsUiTest]'s.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q360, instrumentedPackages = ["com.brushwork.paint.qa110.cdcobjects360sandbox"])
class Qa110CdcObjectsNarrowUiTest {

    @Test
    fun changeDrawingColorOnObjectsAt360() = Qa19Ui.uiTest { h -> Qa110CdcObjects.run(h, 360f) }
}
