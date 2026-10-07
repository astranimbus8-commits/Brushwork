package com.brushwork.paint.ui.symmetry

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.assist.RulerPanel
import com.brushwork.paint.ui.common.SymmetryLabels
import com.brushwork.paint.ui.theme.BrushworkTheme
import com.brushwork.paint.ui.tools.ToolOptionsBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.7 (item 18, §3.18): the symmetry controls at the user's phone size, used like a finger
 * would. The Symmetry tool's strip: the ruler chips, "Divisions" typed in, "Reset symmetry", and
 * "Done" back to the brush. The Ruler panel: the symmetry rulers under the ruler's controls,
 * "Divisions" there too, and "Symmetry" (Place on canvas) picks the tool and closes the panel.
 * None of it is an undo step.
 */
// Own sandbox (the test recomposer policy and paused Choreographer are global); the user's phone size.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.symmetry.uisandbox"])
class SymmetryUiRobolectricTest {

    @Test
    fun theStripAndTheRulerPanelSetTheSymmetryRulers() {
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val c = Smoke.controller(activity, Smoke.document(400, 300, layers = 2))
        c.selectTool(ToolId.BRUSH)
        c.selectTool(ToolId.SYMMETRY)
        assertEquals("picking the tool turns the mirror on", SymmetryType.MIRROR, c.symmetry.type)
        var panel by mutableStateOf(false)
        var dismissed = false
        activity.setContent {
            BrushworkTheme {
                if (panel) RulerPanel(c) { dismissed = true } else ToolOptionsBar(c)
            }
        }
        SmokeUi.settle()

        // The strip: every ruler is a chip; the mirror shows its angle.
        for (t in SymmetryType.entries) assertTrue(t.label, SmokeUi.has(t.label, exact = true))
        assertTrue(SmokeUi.has("Angle 0°", exact = true))
        assertFalse("nothing to reset yet", SmokeUi.isEnabled(SymmetryLabels.RESET))
        SmokeUi.click(SymmetryType.KALEIDOSCOPE.label, exact = true)
        assertEquals(SymmetryType.KALEIDOSCOPE, c.symmetry.type)
        SmokeUi.click("${SymmetryLabels.DIVISIONS} 6", exact = true)
        SmokeUi.typeAndDone(SymmetryLabels.DIVISIONS, "8")
        assertEquals(8, c.symmetry.divisions)
        SmokeUi.click(SymmetryType.ARRAY.label, exact = true)
        assertTrue(SmokeUi.has("Spacing X 300 px", exact = true) && SmokeUi.has("Spacing Y 300 px", exact = true))
        SmokeUi.click(SymmetryType.KALEIDOSCOPE.label, exact = true)
        assertTrue(SmokeUi.isEnabled(SymmetryLabels.RESET))
        SmokeUi.click(SymmetryLabels.RESET, exact = true)
        assertEquals("reset keeps the ruler", SymmetryType.KALEIDOSCOPE, c.symmetry.type)
        assertEquals(6, c.symmetry.divisions)
        SmokeUi.click("Done", exact = true)
        assertEquals("back to the brush, symmetry still on", ToolId.BRUSH, c.activeToolId)
        assertEquals(SymmetryType.KALEIDOSCOPE, c.symmetry.type)

        // The Ruler panel lists the symmetry rulers.
        panel = true
        SmokeUi.settle()
        assertTrue(SmokeUi.has(SymmetryLabels.DIVISIONS))
        SmokeUi.click(SymmetryType.ROTATION.label, exact = true)
        assertEquals(SymmetryType.ROTATION, c.symmetry.type)
        SmokeUi.click(SymmetryType.ARRAY.label, exact = true)
        assertEquals(SymmetryType.ARRAY, c.symmetry.type)
        assertFalse("no divisions for the array", SmokeUi.has(SymmetryLabels.DIVISIONS))
        SmokeUi.click(SymmetryLabels.TOOL, exact = true)
        assertTrue("the panel closes", dismissed)
        assertEquals(ToolId.SYMMETRY, c.activeToolId)
        assertEquals(SymmetryType.ARRAY, c.symmetry.type)
        assertEquals("never an undo step", 0, c.undoManager.undoCount)
        SmokeUi.assertWindowsLaidOut()
    }
}
