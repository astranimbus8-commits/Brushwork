package com.brushwork.paint.ui.vector

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveStroke
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
 * v1.5 §4.5 (A4 review): in vector mode the options strip starts with the VECTOR chip, so on the
 * user's 392 dp phone (and on 360 dp ones) the selected point's thickness slider used to begin
 * off the screen. Selecting a point now scrolls the strip so the whole thickness control shows,
 * and it still works there (one in-tool step).
 */
// One test per class, each in its own sandbox (the test recomposer policy and paused
// Choreographer are global: a second Compose test in the same sandbox stops recomposing).
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.vector.curvethickness392sandbox"])
class CurveThicknessVisibleUiRobolectricTest {
    @Test
    fun onTheUsersPhone() = CurveThicknessVisibleCheck.run()
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h780dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.vector.curvethickness360sandbox"])
class CurveThicknessVisibleNarrowUiRobolectricTest {
    @Test
    fun onANarrowPhone() = CurveThicknessVisibleCheck.run()
}

private object CurveThicknessVisibleCheck {

    private fun slider(): RobolectricUi.Element = RobolectricUi.elements().lastOrNull { e ->
        e.node.config.contains(SemanticsActions.SetProgress) &&
            e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Point thickness slider") == true
    } ?: throw AssertionError("no thickness slider; shown: ${SmokeUi.shown()}")

    /**
     * [n] frames as a phone runs them: each one also lays the screen out (under Robolectric the
     * Compose layout otherwise waits for the next input event, and the strip's scroll animation
     * would keep going by a distance it never sees shrink).
     */
    private fun frames(n: Int) = repeat(n) {
        settle(1)
        RobolectricUi.elements()
    }

    /** Where [e] really is in its window: NOT clipped by the scrolling strip (boundsInWindow is). */
    private fun unclipped(e: RobolectricUi.Element): Pair<Float, Float> {
        val x = e.node.positionInWindow.x
        return x to x + e.node.size.width
    }

    private fun assertOnScreen(what: String, e: RobolectricUi.Element, screenW: Int) {
        val (l, r) = unclipped(e)
        assertTrue("$what is on the screen: $l..$r in 0..$screenW", l >= 0f && r <= screenW)
    }

    fun run() {
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val doc = Smoke.document(400, 300, layers = 2)
        doc.layers.last().vector = VectorContent.EMPTY
        val c = Smoke.controller(activity, doc)
        c.tools
        assertTrue(c.isVectorMode)
        c.selectTool(ToolId.CURVE)
        val tool = c.tools.getValue(ToolId.CURVE) as CurveTool
        tool.update { it.copy(stroke = CurveStroke.PLAIN) }
        activity.setContent { BrushworkTheme { ToolOptionsBar(c, Modifier.fillMaxWidth()) } }
        settle()
        assertTrue("the VECTOR chip leads the strip", SmokeUi.has("Vector mode is on"))
        for (p in listOf(Vec2(60f, 200f), Vec2(200f, 80f), Vec2(340f, 200f))) tool.addAnchor(p)
        tool.select(1)
        frames(12)
        val screenW = activity.window.decorView.width
        val s = slider()
        assertOnScreen("the slider", s, screenW)
        assertOnScreen("the thinner arrow", RobolectricUi.byDescription("Thinner point"), screenW)
        // It works where it is: one in-tool step.
        requireNotNull(s.node.config.getOrNull(SemanticsActions.SetProgress)?.action).invoke(200f)
        frames(4)
        assertEquals(2f, tool.anchors[1].width, 1e-4f)
        assertTrue(tool.undoStep())
        assertEquals(1f, tool.anchors[1].width, 0f)
        // Another point: still on the screen.
        tool.select(2)
        frames(12)
        assertOnScreen("the slider of point 3", slider(), screenW)
    }

}
