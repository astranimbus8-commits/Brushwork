package com.brushwork.paint.qa19

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.9 A6 step 8 (P4, LA2; I27): [Qa19DrawPreviewChecks] on the 360 dp phone: "Drawing preview"
 * hidden as shipped, and with it available the label, both chips and the note whole at each font
 * scale, the choice saved and shown again in the editor's Settings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q360, instrumentedPackages = ["com.brushwork.paint.qa19.drawpreview360sandbox"])
class Qa19DrawPreviewNarrowUiTest {

    @Test
    fun theDrawingPreviewSettingAt360() = Qa19Ui.uiTest { h -> Qa19DrawPreviewChecks.run(h, 360f) }
}
