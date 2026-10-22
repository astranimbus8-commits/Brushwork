package com.brushwork.paint.qa110

import com.brushwork.paint.qa19.Qa19Ui
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.10 (R6, R7; A4 accept; I27): [Qa110CdcObjects] on the user's 392 dp phone, at font scale 1
 * and at each of [Qa19Ui.FONT_SCALES]: Change Drawing Color on a vector layer and on a text layer
 * with a pixel selection keeps the layer editable, previews the picked color live and applies
 * as one step. The 360 dp phone is [Qa110CdcObjectsNarrowUiTest]'s.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q392, instrumentedPackages = ["com.brushwork.paint.qa110.cdcobjects392sandbox"])
class Qa110CdcObjectsUiTest {

    @Test
    fun changeDrawingColorOnObjectsAt392() = Qa19Ui.uiTest { h -> Qa110CdcObjects.run(h, 392f) }
}
