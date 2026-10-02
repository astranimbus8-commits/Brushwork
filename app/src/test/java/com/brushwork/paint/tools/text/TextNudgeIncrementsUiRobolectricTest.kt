package com.brushwork.paint.tools.text

import android.graphics.Matrix
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.placement.TextToolOptions
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.6 §3.4 in the Text tool's "Position & size" sheet, at the user's phone size: the ‹ › nudge
 * arrows move the text by the unit's step (1 px) while increments are off (v1.5), and by the
 * Length step (10 px) while they are on; the section header names the step.
 */
// Own sandbox (the test recomposer policy and paused Choreographer are global); the user's phone size.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.tools.text.nudgesandbox"])
class TextNudgeIncrementsUiRobolectricTest {

    @Test
    fun theNudgeArrowsMoveByTheLengthStepWhileIncrementsAreOn() {
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val c = Smoke.controller(activity, Smoke.document(600, 800, layers = 1, whiteBottom = true))
        c.viewTransform.set(Matrix())
        c.selectTool(ToolId.TEXT)
        val tool = c.tools.getValue(ToolId.TEXT) as TextTool
        activity.setContent { BrushworkTheme { TextToolOptions(tool) } }
        tool.startTextAt(300f, 400f)
        tool.setText("Nudge me")
        tool.confirmEditor()
        tool.numbersOpen = true
        SmokeUi.settle()

        // Off: the unit's step, as in v1.5.
        assertTrue("shown: ${SmokeUi.shown().take(60)}", SmokeUi.has("NUDGE (1 PX)", exact = true))
        press("Move right")
        assertEquals(301f, tool.item!!.cx, 1e-3f)
        assertEquals(400f, tool.item!!.cy, 1e-3f)

        // On: the Length step.
        c.increments.update { it.copy(enabled = true) }
        SmokeUi.settle()
        assertTrue("shown: ${SmokeUi.shown().take(60)}", SmokeUi.has("NUDGE (10 PX)", exact = true))
        press("Move right")
        press("Move down")
        assertEquals(311f, tool.item!!.cx, 1e-3f)
        assertEquals(410f, tool.item!!.cy, 1e-3f)

        // Nothing is history until ✓; then one step.
        assertEquals(0, c.undoManager.undoCount)
        tool.commit()
        SmokeUi.settle()
        assertEquals(1, c.undoManager.undoCount)
        Smoke.assertQuiet(c, "nudge increments")
    }

    /**
     * Taps the arrow labelled [label] with real touch events (the arrows repeat while held, so they
     * have no click action), after scrolling the sheet so that it is on screen (the nudge pad is
     * at the end of the sheet).
     */
    private fun press(label: String) {
        val e = SmokeUi.find(label, exact = true) ?: throw AssertionError("no \"$label\": ${SmokeUi.shown().take(60)}")
        var n: SemanticsNode? = e.node.parent
        while (n != null && n.config.getOrNull(SemanticsActions.ScrollBy) == null) n = n.parent
        n?.config?.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(0f, 10_000f)
        SmokeUi.settle()
        val target = SmokeUi.find(label, exact = true)!!
        val visible = target.window.height
        assertTrue("\"$label\" at ${target.bounds} is on screen (height $visible)", target.bounds.bottom <= visible && target.bounds.top >= 0f)
        target.tap()
        SmokeUi.settle()
    }
}
