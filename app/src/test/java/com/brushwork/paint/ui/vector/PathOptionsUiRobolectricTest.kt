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
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.HandleSide
import com.brushwork.paint.ui.color.RobolectricUi
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
 * v1.6 §3.2a / §3.3 at the user's phone size: the Path tool's strip (Order stepper, Endpoint,
 * Cyclic, Shapes ▾ while empty, the selected point's Weight, To Bézier) and the Curve tool's
 * Handles group (‹ › 110 %, typed value, the log slider, Both / In / Out, All points), each
 * control an in-tool step.
 */
// Own sandbox (the test recomposer policy and paused Choreographer are global); the user's phone size.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.vector.pathoptionssandbox"])
class PathOptionsUiRobolectricTest {

    private fun slider(name: String): RobolectricUi.Element = RobolectricUi.elements().last { e ->
        e.node.config.contains(SemanticsActions.SetProgress) &&
            e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(name) == true
    }

    @Test
    fun thePathStrip() {
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val c = Smoke.controller(activity, Smoke.document(400, 300, layers = 2))
        c.tools
        c.selectTool(ToolId.PATH)
        val tool = c.tools.getValue(ToolId.PATH) as CurveTool
        activity.setContent { BrushworkTheme { ToolOptionsBar(c, Modifier.fillMaxWidth()) } }
        settle()
        // Empty: the order, Endpoint, Cyclic and the quick starts; nothing to convert.
        assertTrue("strip: ${SmokeUi.shown()}", SmokeUi.has("Order 4", exact = true))
        assertTrue(SmokeUi.has("Endpoint", exact = true))
        assertTrue(SmokeUi.has("Cyclic", exact = true))
        assertTrue(SmokeUi.has("Path shapes", exact = true))
        assertFalse(SmokeUi.has("To Bézier"))
        assertFalse(SmokeUi.has("Point weight slider"))
        assertFalse("no Handles group in Path", SmokeUi.has("Handle scale"))

        SmokeUi.click("Higher order")
        assertEquals(5, tool.pathOrder)
        assertTrue(SmokeUi.has("Order 5", exact = true))
        SmokeUi.click("Lower order")
        assertEquals(4, tool.pathOrder)

        // Shapes ▾ › Capsule: 12 points, cyclic; the menu goes, To Bézier comes.
        SmokeUi.click("Path shapes")
        SmokeUi.click("Capsule", exact = true)
        assertEquals(12, tool.spline!!.points.size)
        assertTrue(tool.pathCyclic)
        assertFalse(SmokeUi.has("Path shapes"))
        assertTrue(SmokeUi.has("To Bézier"))
        assertFalse("Endpoint is greyed while Cyclic is on", SmokeUi.isEnabled("Endpoint"))

        // A selected point: its weight (log slider, one step per drag) and thickness.
        tool.select(3)
        settle()
        assertTrue(SmokeUi.has("Point weight 1", exact = true))
        assertTrue(SmokeUi.has("Point thickness 100 %"))
        val before = tool.spline
        requireNotNull(slider("Point weight slider").node.config.getOrNull(SemanticsActions.SetProgress)?.action).invoke(1f)
        settle(4)
        assertEquals(10f, tool.spline!!.points[3].weight, 0f)
        assertTrue(tool.undoStep())
        assertEquals("one step", before, tool.spline)
        SmokeUi.click("Type the point weight")
        SmokeUi.typeAndDone("Weight", "2.5")
        assertEquals(2.5f, tool.spline!!.points[3].weight, 0f)

        // Cyclic off: Endpoint comes back.
        SmokeUi.click("Cyclic", exact = true)
        assertFalse(tool.spline!!.cyclic)
        assertTrue(SmokeUi.isEnabled("Endpoint"))

        // To Bézier: the Curve tool takes the path.
        SmokeUi.click("To Bézier")
        assertEquals(ToolId.CURVE, c.activeToolId)
        assertFalse(tool.hasPendingWork)
    }

    @Test
    fun theHandlesGroup() {
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val c = Smoke.controller(activity, Smoke.document(400, 300, layers = 2))
        c.tools
        c.selectTool(ToolId.CURVE)
        val tool = c.tools.getValue(ToolId.CURVE) as CurveTool
        activity.setContent { BrushworkTheme { ToolOptionsBar(c, Modifier.fillMaxWidth()) } }
        settle()
        assertFalse("no path: no Handles group", SmokeUi.has("Handle scale"))
        for (p in listOf(Vec2(60f, 200f), Vec2(150f, 80f), Vec2(260f, 210f), Vec2(350f, 90f))) tool.addAnchor(p)
        tool.select(1)
        settle()
        for (label in listOf("Handles", "Shorter handles", "Longer handles", "Type handle scale", "Handles: Both", "Handles: In", "Handles: Out", "Handles: All points")) {
            assertTrue("$label: ${SmokeUi.shown()}", SmokeUi.has(label, exact = true))
        }
        assertTrue(SmokeUi.has("100 %", exact = true))
        val out0 = tool.handlesOf(1).second.length

        // (‹ › are hold-to-repeat buttons without a click action; HandleScaleRobolectricTest covers their steps.)
        // The slider (log scale): one step per drag.
        val s = slider("Handle scale")
        requireNotNull(s.node.config.getOrNull(SemanticsActions.SetProgress)?.action).invoke(handleScaleToFraction(2f))
        settle(4)
        assertEquals(out0 * 2f, tool.handlesOf(1).second.length, 1e-2f)
        assertEquals(1f, tool.handleScale, 0f)
        assertTrue(tool.undoStep())
        assertEquals(out0, tool.handlesOf(1).second.length, 1e-3f)

        // Typed: exact.
        SmokeUi.click("Type handle scale")
        SmokeUi.typeAndDone("Handles", "150")
        assertEquals(out0 * 1.5f, tool.handlesOf(1).second.length, 1e-3f)

        // The chips.
        SmokeUi.click("Handles: Out", exact = true)
        assertEquals(HandleSide.OUT, tool.handleSide)
        SmokeUi.click("Handles: All points", exact = true)
        assertTrue(tool.handleAllPoints)
    }

    @Test
    fun theSliderMathIsLogarithmic() {
        assertEquals(0f, handleScaleToFraction(0.1f), 1e-6f)
        assertEquals(1f, handleScaleToFraction(4f), 1e-6f)
        assertEquals(1f, fractionToHandleScale(handleScaleToFraction(1f)), 1e-6f)
        assertEquals(0.1f, fractionToHandleScale(0f), 1e-6f)
        assertEquals(4f, fractionToHandleScale(1f), 1e-6f)
        // The weight slider: 1 in the middle of 0.1..10.
        assertEquals(0.5f, com.brushwork.paint.tools.vector.spline.SplineEditing.weightToFraction(1f), 1e-6f)
        assertEquals(1f, com.brushwork.paint.tools.vector.spline.SplineEditing.fractionToWeight(0.5f), 1e-6f)
        assertEquals(1.1f, stepWeight(1f, up = true, step = null), 1e-6f)
        assertEquals(1.25f, stepWeight(1f, up = true, step = 0.25f), 1e-6f)
        assertEquals(0.1f, stepWeight(0.15f, up = false, step = null), 1e-6f)
        // Thickness steps follow a custom Percent step.
        assertEquals(110f, stepThickness(100f, up = true, step = 10f), 0f)
        assertEquals(105f, stepThickness(100f, up = true), 0f)
    }
}
