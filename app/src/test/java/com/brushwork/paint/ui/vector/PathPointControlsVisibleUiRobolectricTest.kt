package com.brushwork.paint.ui.vector

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.theme.BrushworkTheme
import com.brushwork.paint.ui.tools.ToolOptionsBar
import com.brushwork.paint.vector.VectorContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.6 review fix (area B, §3.2a): selecting a Path control point scrolls the strip so the
 * point's controls START at the left edge: the whole Weight control and the Thickness arrows and
 * value (the two controls together are wider than a phone; before, the whole Thickness slider
 * showed and Weight's value was off the screen). Both work where they are.
 */
// One test per class, each in its own sandbox (the test recomposer policy and paused
// Choreographer are global).
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.vector.pathpoint392sandbox"])
class PathPointControlsVisibleUiRobolectricTest {
    @Test
    fun onTheUsersPhone() = PathPointControlsVisibleCheck.run(narrow = false)
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h780dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.vector.pathpoint360sandbox"])
class PathPointControlsVisibleNarrowUiRobolectricTest {
    @Test
    fun onANarrowPhone() = PathPointControlsVisibleCheck.run(narrow = true)
}

private object PathPointControlsVisibleCheck {

    /** [n] frames as a phone runs them (layout included: see CurveThicknessVisibleUiRobolectricTest). */
    private fun frames(n: Int) = repeat(n) {
        settle(1)
        RobolectricUi.elements()
    }

    /** Where [e] really is in its window, NOT clipped by the scrolling strip. */
    private fun assertOnScreen(what: String, e: RobolectricUi.Element, screenW: Int) {
        val l = e.node.positionInWindow.x
        val r = l + e.node.size.width
        assertTrue("$what is on the screen: $l..$r in 0..$screenW", l >= 0f && r <= screenW)
    }

    fun run(narrow: Boolean) {
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val doc = Smoke.document(400, 300, layers = 2)
        doc.layers.last().vector = VectorContent.EMPTY
        val c = Smoke.controller(activity, doc)
        c.tools
        assertTrue(c.isVectorMode)
        c.selectTool(ToolId.PATH)
        val tool = c.tools.getValue(ToolId.PATH) as CurveTool
        activity.setContent { BrushworkTheme { ToolOptionsBar(c, Modifier.fillMaxWidth()) } }
        settle()
        for (p in listOf(Vec2(60f, 200f), Vec2(200f, 80f), Vec2(340f, 200f), Vec2(380f, 60f))) tool.addAnchor(p)
        tool.select(1)
        // (The strip's scroll animation springs into place.)
        frames(40)
        val screenW = activity.window.decorView.width
        assertOnScreen("the weight value", RobolectricUi.byDescription("Point weight 1"), screenW)
        val weight = RobolectricUi.byDescription("Point weight slider")
        assertOnScreen("the weight slider", weight, screenW)
        assertOnScreen("the thinner arrow", RobolectricUi.byDescription("Thinner point"), screenW)
        assertOnScreen("the thickness value", RobolectricUi.byDescription("Point thickness 100"), screenW)
        if (!narrow) assertOnScreen("the thicker arrow", RobolectricUi.byDescription("Thicker point"), screenW)
        // The weight slider works where it is: one in-tool step.
        requireNotNull(weight.node.config.getOrNull(SemanticsActions.SetProgress)?.action).invoke(1f)
        frames(4)
        assertEquals(10f, tool.spline!!.points[1].weight, 0f)
        assertTrue(tool.undoStep())
        assertEquals(1f, tool.spline!!.points[1].weight, 0f)
        // Another point: its controls start at the left edge too.
        tool.select(3)
        frames(40)
        assertOnScreen("the weight value of point 4", RobolectricUi.byDescription("Point weight 1"), screenW)
        assertOnScreen("the thinner arrow of point 4", RobolectricUi.byDescription("Thinner point"), screenW)
    }
}
