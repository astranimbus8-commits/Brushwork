package com.brushwork.paint.tools.symmetry

import android.graphics.Bitmap
import android.graphics.Canvas
import com.brushwork.paint.assist.SymmetryGuides
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.StubToolFixtures
import com.brushwork.paint.tools.ToolId
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.7 F5 (design §4.6): the Symmetry stubs keep v1.6 behaviour on `main`: the Symmetry tool
 * leaves no trace on any layer kind and `SymmetryGuides` draws nothing, editing or not. Area H
 * deletes or rewrites this test when it fills the stubs (`StubToolsRobolectricTest` must keep
 * passing with the real tool).
 */
@RunWith(RobolectricTestRunner::class)
class SymmetryStubsRobolectricTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test
    fun theSymmetryToolLeavesNoTrace() {
        val c = Smoke.controller(app)
        StubToolFixtures.assertLeavesNoTrace(c, ToolId.SYMMETRY, StubToolFixtures.everyKind(c))
    }

    @Test
    fun theGuidesDrawNothing() {
        val c = Smoke.controller(app)
        val blank = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        SymmetryGuides.draw(Canvas(blank), c.viewTransform, c.doc, editing = true)
        SymmetryGuides.draw(Canvas(blank), c.viewTransform, c.doc, editing = false)
        assertTrue(blank.sameAs(Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)))
    }
}
