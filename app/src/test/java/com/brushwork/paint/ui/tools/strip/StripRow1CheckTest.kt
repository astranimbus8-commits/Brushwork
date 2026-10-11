package com.brushwork.paint.ui.tools.strip

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.V18Tags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 integration (v18I): at the design's narrowest window, 360 dp, row 1 is fixed (I18): the
 * real strips compose without a scroller and without the check throwing, and a row 1 wider than
 * its budget (two 200 dp items that never leave it) makes a debug build's check throw, naming
 * I18. `StripRow1NarrowTest` covers the narrower window, where row 1 scrolls instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.tools.strip.row1checksandbox"])
class StripRow1CheckTest {

    @Test
    fun at360dpRow1IsFixedAndAnOverBudgetRowThrows() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        // Compose hands an exception thrown while composing to the main thread's handler.
        val thrown = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val thread = Thread.currentThread()
        val handler = thread.uncaughtExceptionHandler
        thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, t -> thrown += t }
        try {
            val rig = StripRig(panel = false).show()
            try {
                for (vector in listOf(false, true)) for (id in listOf(ToolId.BRUSH, ToolId.CURVE, ToolId.SHAPE, ToolId.LASSO)) {
                    rig.select(id, vector)
                    assertTrue("$id (vector $vector): nothing thrown at 360 dp: $thrown", thrown.isEmpty())
                    val row1 = StripTestKit.tagged(V18Tags.STRIP_ROW1)
                    assertTrue("$id (vector $vector): row 1 shows", row1 != null)
                    val scrollers = RobolectricUi.elements().map { it.node }.filter { n ->
                        n.config.getOrNull(SemanticsActions.ScrollBy) != null && generateSequence(n.parent) { it.parent }.any { it.id == row1!!.id }
                    }
                    assertEquals("$id (vector $vector): row 1 never scrolls at 360 dp", 0, scrollers.size)
                }
                rig.select(ToolId.BRUSH)
                rig.override = StripSpec(
                    listOf("Wide one", "Wide two").map { label ->
                        StripItem("check.$label", label, 200.dp) { Box(Modifier.size(200.dp, 40.dp).semantics { contentDescription = label }) }
                    },
                )
                rig.settle()
            } finally {
                rig.close()
            }
        } finally {
            thread.uncaughtExceptionHandler = handler
        }
        val i18 = thrown.filterIsInstance<IllegalStateException>().firstOrNull()
        assertTrue("a row 1 over its budget at 360 dp throws (I18): $thrown", i18 != null && i18.message.orEmpty().contains("(I18)"))
    }
}
