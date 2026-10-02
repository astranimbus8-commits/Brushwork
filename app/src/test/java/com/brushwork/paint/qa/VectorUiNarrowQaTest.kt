package com.brushwork.paint.qa

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** QA: [VectorUiFlow] on a 360 dp phone (the narrowest the design supports). Its own sandbox. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h740dp-xhdpi", instrumentedPackages = ["com.brushwork.paint.qa.vectoruinarrowsandbox"])
class VectorUiNarrowQaTest {
    @Test
    fun vectorWorkflowThroughTheEditorScreenAt360dp() = VectorUiFlow.run(this)
}
