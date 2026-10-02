package com.brushwork.paint.ui.tools

import android.graphics.Matrix
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.masks.RadialMask
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.mask.MaskTool
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.5 integration (A4 X/Y strip x A5 Masks tool): the real strip ends the Masks tool's position
 * edit (`ObjectPosition.endPositionEdit`) when an arrow is released, when a slider drag ends and
 * when a typed value is applied — each finished edit of the selected mask component is exactly
 * one undo step "Move mask". 392 dp phone; its own sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.tools.maskstripsandbox"])
class CoordinateStripMaskUiRobolectricTest {

    @Test
    fun everyFinishedStripEditOfAMaskComponentIsOneStep() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val ctl = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val activity = ctl.get()
            val c = Smoke.controller(activity, Smoke.document(200, 150, layers = 2))
            c.viewTransform.set(Matrix())
            c.selectTool(ToolId.MASK)
            val tool = c.tools.getValue(ToolId.MASK) as MaskTool
            tool.arm(MaskTool.Kind.RADIAL)
            c.pointerDown(ToolPoint(100f, 75f)); c.pointerMove(ToolPoint(115f, 75f)); c.pointerMove(ToolPoint(140f, 75f)); c.pointerUp(ToolPoint(140f, 75f))
            val adj = c.activeLayer
            assertTrue(adj.isAdjustmentLayer)
            fun cx() = (adj.maskSpec!!.components.single() as RadialMask).cx
            activity.setContent { BrushworkTheme { CoordinateStrip(c) } }
            settle()
            assertTrue("the strip shows the component", SmokeUi.has("X slider"))
            var steps = c.undoManager.undoCount

            // An arrow tap: moved by 1 px, one step on release.
            RobolectricUi.byDescription("X plus 1 pixel").tap()
            settle()
            assertEquals("arrow: one step", steps + 1, c.undoManager.undoCount)
            assertEquals("Move mask", c.undoManager.undoLabel)
            assertEquals(101f, cx(), 1e-3f)
            steps = c.undoManager.undoCount

            // A slider drag's end (the semantics action sets and ends, as the finger's release does).
            val slider = RobolectricUi.elements().last { e ->
                e.node.config.contains(SemanticsActions.SetProgress) &&
                    e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("X slider") == true
            }
            requireNotNull(slider.node.config.getOrNull(SemanticsActions.SetProgress)?.action).invoke(60f)
            settle(4)
            assertEquals("slider: one step", steps + 1, c.undoManager.undoCount)
            assertEquals(60f, cx(), 1e-3f)
            steps = c.undoManager.undoCount

            // A typed value.
            SmokeUi.click("Type X")
            SmokeUi.typeAndDone("X", "42")
            assertEquals("typed: one step", steps + 1, c.undoManager.undoCount)
            assertEquals(42f, cx(), 1e-3f)

            // Undo takes back one edit at a time.
            c.undo()
            assertEquals(60f, cx(), 1e-3f)
        } finally {
            runCatching { ctl.pause().stop().destroy() }
        }
    }
}
