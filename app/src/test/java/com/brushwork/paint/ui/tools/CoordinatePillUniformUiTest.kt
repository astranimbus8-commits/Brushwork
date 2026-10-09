package com.brushwork.paint.ui.tools

import android.graphics.Matrix
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.ExpressionLabels
import com.brushwork.paint.ui.common.LocalIncrements
import com.brushwork.paint.ui.common.PillLabels
import com.brushwork.paint.ui.common.V17Tags
import com.brushwork.paint.ui.theme.BrushworkTheme
import kotlinx.serialization.builtins.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.7 item 9 (design §3.9; area I): a uniform-only scale (`ObjectScale.uniformOnly`, a text
 * object's) on the user's phone (392 dp): row 2 is `[⛓][Scale X][# 10]` with Scale Y hidden and
 * "Keep scale proportions" shown on and disabled, even with the remembered setting off; a typed
 * Scale X scales both axes in one edit. And the typed Scale dialog's readout: a valid expression
 * under the 0.1 % minimum names the range ("Type a number (…)"), not "Check the expression", and
 * OK stays disabled.
 * One test (Compose's frame clock serves the first test of a sandbox only), own sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.tools.pilluniformsandbox"])
class CoordinatePillUniformUiTest {

    private fun described(label: String): RobolectricUi.Element? = RobolectricUi.elements().lastOrNull { e ->
        e.node.layoutInfo.isPlaced && e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it == label } == true
    }

    private fun tagged(tag: String): RobolectricUi.Element? = RobolectricUi.elements().lastOrNull { e ->
        e.node.layoutInfo.isPlaced && e.node.config.getOrNull(SemanticsProperties.TestTag) == tag
    }

    @Test
    fun uniformOnlyScaleRowAndTheOutOfRangeReadout() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val ctl = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val activity = ctl.get()
            val c = Smoke.controller(activity, Smoke.document(256, 256, layers = 1))
            c.viewTransform.set(Matrix())
            c.snapping.enabled = false
            // The remembered setting is off: a uniform-only scale keeps proportions all the same.
            c.settings.putObject("pill.keepProportions", Boolean.serializer(), false)
            val fake = FakePillTool(c, kind = "text", uniformOnly = true)
            @Suppress("UNCHECKED_CAST")
            (c.tools as MutableMap<ToolId, Tool>)[ToolId.CURVE] = fake
            c.selectTool(ToolId.CURVE)
            assertEquals(ToolId.CURVE, c.activeToolId)
            activity.setContent {
                BrushworkTheme {
                    CompositionLocalProvider(LocalIncrements provides c.increments) {
                        Box(Modifier.fillMaxSize()) { CoordinatePill(c, Modifier.offset(x = 8.dp, y = 135.dp)) }
                    }
                }
            }
            settle()

            // ---------------------------------------------------------------- row 2, uniform only
            assertNotNull("row 2; shown: ${SmokeUi.shown()}", tagged(V17Tags.PILL_SCALE_ROW))
            assertNotNull(described(PillLabels.SCALE_X))
            assertFalse("no Scale Y", SmokeUi.has(PillLabels.SCALE_Y, exact = true))
            val keep = requireNotNull(described(PillLabels.KEEP_PROPORTIONS))
            assertEquals("forced on", ToggleableState.On, keep.node.config[SemanticsProperties.ToggleableState])
            assertFalse("and can't be turned off", SmokeUi.isEnabled(PillLabels.KEEP_PROPORTIONS))
            assertNotNull(described(PillLabels.SCALE_INCREMENTS))
            assertTrue(SmokeUi.has(PillLabels.deleteObject("text"), exact = true))

            // Typed: both axes, one edit.
            val calls = fake.setScaleCalls
            val edits = fake.beginScaleCalls
            SmokeUi.click("Type ${PillLabels.SCALE_X}")
            SmokeUi.typeAndDone(PillLabels.SCALE_X, "50")
            assertEquals(calls + 1, fake.setScaleCalls)
            assertEquals(edits + 1, fake.beginScaleCalls)
            assertEquals(fake.beginScaleCalls, fake.endScaleCalls)
            assertEquals(Vec2(50f, 50f), fake.objectScale!!.scalePercent)
            assertEquals("the setting is left as it was", false, c.settings.getObject("pill.keepProportions", Boolean.serializer()))

            // ---------------------------------------------------------------- the out-of-range readout
            SmokeUi.click("Type ${PillLabels.SCALE_X}")
            SmokeUi.field(PillLabels.SCALE_X).focus()
            settle(2)
            SmokeUi.field(PillLabels.SCALE_X).type("0.05*1")
            settle(2)
            val range = "Type a number (% of the size it was selected at)"
            assertTrue("the range; shown: ${SmokeUi.shown().take(40)}", SmokeUi.has(range, exact = true))
            assertFalse(SmokeUi.has(ExpressionLabels.INVALID, exact = true))
            assertFalse("OK is disabled", SmokeUi.isEnabled("OK"))
            // A broken expression still says so; a valid one in range reads its value.
            SmokeUi.field(PillLabels.SCALE_X).type("(")
            settle(2)
            assertTrue(SmokeUi.has(ExpressionLabels.INVALID, exact = true))
            assertFalse(SmokeUi.has(range, exact = true))
            SmokeUi.field(PillLabels.SCALE_X).type("50*3")
            settle(2)
            assertTrue("= 150 %; shown: ${SmokeUi.shown().take(40)}", SmokeUi.has("= 150 %", exact = true))
            assertTrue(SmokeUi.isEnabled("OK"))
            SmokeUi.click("OK", exact = true)
            assertEquals(Vec2(150f, 150f), fake.objectScale!!.scalePercent)
            c.dispose()
        } finally {
            runCatching { ctl.pause().stop().destroy() }
        }
    }
}
