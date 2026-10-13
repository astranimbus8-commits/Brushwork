package com.brushwork.paint.ui.points

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.qa19.Qa19Ui
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.ui.common.PointLabels19
import com.brushwork.paint.ui.common.V19Tags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.9 (R18, design 2.4.10): [BevelPopup] shows its two lines with every control at least 40 dp,
 * whole texts at each font scale of I27, and reports each change to its caller (it holds no state).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q360, instrumentedPackages = ["com.brushwork.paint.ui.points.bevelpopupsandbox"])
class BevelPopupRobolectricTest {

    @Test
    fun theBevelPopupShowsTwoLinesAndReportsEachChange() = Qa19Ui.uiTest { h ->
        for (scale in listOf(1f) + Qa19Ui.FONT_SCALES) h.section("at font scale $scale") {
            val counts = mutableListOf<Int>()
            var done = 0
            val activity = h.show(fontScale = scale) {
                BevelPopup(amount = 0.15f, segments = 1, onAmount = {}, onSegments = { counts += it }, onDone = { done++ })
            }
            assertNotNull("the popup is tagged", Qa19Ui.tagged(V19Tags.BEVEL_POPUP))
            for (label in listOf(PointLabels19.AMOUNT, "15 %", PointLabels19.FEWER_SEGMENTS, "1", PointLabels19.MORE_SEGMENTS, "Done")) {
                assertTrue("\"$label\" shows; shown: ${Qa19Ui.shown()}", Qa19Ui.has(label, exact = true))
            }
            val density = activity.resources.displayMetrics.density
            for (label in listOf(PointLabels19.FEWER_SEGMENTS, PointLabels19.MORE_SEGMENTS, "Done")) {
                var n: SemanticsNode? = Qa19Ui.find(label, exact = true)!!.node
                while (n != null && n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent
                val b = n!!.boundsInRoot
                assertTrue("\"$label\" is at least 40 dp: $b", b.width / density >= 39.5f && b.height / density >= 39.5f)
            }
            assertFalse("one segment is the fewest", SmokeUi.isEnabled(PointLabels19.FEWER_SEGMENTS))
            Qa19Ui.click(PointLabels19.MORE_SEGMENTS, exact = true)
            assertEquals(listOf(2), counts)
            Qa19Ui.click("Done", exact = true)
            assertEquals(1, done)
            Qa19Ui.assertNoTextOverflow("at font scale $scale")
        }
    }
}
