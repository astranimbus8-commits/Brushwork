package com.brushwork.paint.ui.vector

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.spline.SplineEditing
import com.brushwork.paint.ui.theme.BrushworkTheme
import com.brushwork.paint.ui.tools.ToolOptionsBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * I2 for the Path tool's Numbers sheet: a weight typed there and never confirmed with Done (the
 * sheet goes away with the keyboard still up; the field's focus loss on removal finishes it)
 * must not leave the edit held, or every later run of same-kind edits (nudges of a point
 * seconds apart) would collapse into one undo step.
 */
// Own sandbox (the test recomposer policy and paused Choreographer are global); the user's phone size.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.vector.pathnumberssandbox"])
class PathNumbersSheetHoldUiRobolectricTest {

    @Test
    fun aTypedWeightLeftUnconfirmedDoesNotHoldLaterEditsTogether() {
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val c = Smoke.controller(activity, Smoke.document(400, 300, layers = 2))
        c.tools
        c.selectTool(ToolId.PATH)
        val tool = c.tools.getValue(ToolId.PATH) as CurveTool
        var shown by mutableStateOf(true)
        activity.setContent { BrushworkTheme { if (shown) ToolOptionsBar(c, Modifier.fillMaxWidth()) } }
        settle()
        for (p in listOf(Vec2(60f, 200f), Vec2(150f, 80f), Vec2(260f, 210f))) tool.addAnchor(p)
        tool.select(1)
        settle()

        // The Numbers sheet: type a weight, and leave without Done or a focus change.
        SmokeUi.click("Numbers", exact = true)
        assertTrue("sheet: ${SmokeUi.shown()}", SmokeUi.has("Point 2 of 3", exact = true))
        val field = SmokeUi.field("Weight")
        field.focus()
        settle(2)
        SmokeUi.field("Weight").type("3")
        settle(2)
        assertEquals(3f, tool.spline!!.points[1].weight, 0f)
        shown = false
        settle(4)

        // Two nudges of the point ten seconds apart are two steps.
        var now = 100_000L
        tool.clock = { now }
        val start = SplineEditing.pos(tool.spline!!.points[1])
        tool.nudge(1, 0)
        now += 10_000L
        tool.nudge(1, 0)
        val step = tool.settings.nudgeStepPx
        assertEquals(start + Vec2(2 * step, 0f), SplineEditing.pos(tool.spline!!.points[1]))
        assertTrue(tool.undoStep())
        assertEquals("one nudge taken back", start + Vec2(step, 0f), SplineEditing.pos(tool.spline!!.points[1]))
        assertTrue(tool.undoStep())
        assertEquals(start, SplineEditing.pos(tool.spline!!.points[1]))
        assertTrue(tool.undoStep())
        assertEquals("then the typed weight", 1f, tool.spline!!.points[1].weight, 0f)
    }
}
