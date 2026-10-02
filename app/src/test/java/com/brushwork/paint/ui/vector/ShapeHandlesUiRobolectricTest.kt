package com.brushwork.paint.ui.vector

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.vector.ShapeHandleSide
import com.brushwork.paint.tools.vector.ShapePoints
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.LocalIncrements
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.6 §3.3 (G): the Handles group in the shape tool's options strip, points mode, at the user's
 * 392 dp. Its controls carry their labels ("Shorter handles", the value "100 %" with "Type handle
 * scale", "Longer handles", the "Handle scale" slider, "In and out" / "In" / "Out", "All points");
 * ‹ › and the slider change the selected point's handles, each one in-tool step; the value reads
 * 100 % at rest; a typed value is exact; a long-press on the value opens the Scale step's popup.
 * Own sandbox, one test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.vector.handlessandbox"])
class ShapeHandlesUiRobolectricTest {

    @Test
    fun theHandlesGroup() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val c = Smoke.controller(activity, Smoke.document(400, 300, layers = 1))
        c.viewTransform.set(android.graphics.Matrix())
        c.selectTool(ToolId.SHAPE)
        val tool = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        tool.update { it.copy(type = ShapeType.ELLIPSE, style = ShapeStyle.STROKE, useBrushSize = false, strokeWidth = 4f, keepProportions = false, fromCenter = false) }
        c.pointerDown(ToolPoint(40f, 80f)); c.pointerMove(ToolPoint(80f, 120f)); c.pointerMove(ToolPoint(120f, 160f)); c.pointerUp(ToolPoint(120f, 160f))
        activity.setContent {
            BrushworkTheme {
                CompositionLocalProvider(LocalIncrements provides c.increments) {
                    // Wrapped (not scrolled) so every control is on screen for real taps.
                    FlowRow(Modifier.fillMaxWidth()) { ShapeToolOptions(tool) }
                }
            }
        }
        SmokeUi.settle()
        assertFalse("no group outside points mode", SmokeUi.has("Shorter handles", exact = true))
        SmokeUi.click("Points", exact = true)
        assertTrue(tool.pointsMode)
        for (label in listOf("Shorter handles", "Type handle scale", "Longer handles", "Handle scale", "In and out", "In", "Out", "All points")) {
            assertTrue("\"$label\" in the strip: ${SmokeUi.shown().take(80)}", SmokeUi.has(label, exact = true))
        }
        assertTrue(SmokeUi.has("100 %", exact = true))
        tool.selectPoint(0)
        SmokeUi.settle(4)
        fun out0() = ShapePoints.handles(tool.docAnchors()!!, 0, true).second.length
        val base = out0()

        // › tapped: × 1.1, one in-tool step; the value is back at 100 % when the finger lifts.
        SmokeUi.tap("Longer handles", exact = true)
        SmokeUi.settle(4)
        assertEquals(base * 1.1f, out0(), 1e-3f)
        assertTrue(SmokeUi.has("100 %", exact = true))
        assertTrue(tool.undoStep())
        assertEquals(base, out0(), 1e-3f)

        // The slider (screen readers set it): 200 % of the handles when it began.
        val slider = RobolectricUi.elements().last { e ->
            e.node.config.getOrNull(SemanticsActions.SetProgress) != null &&
                e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Handle scale") == true
        }
        val f = ln1p((2.0 - 0.1) / 0.1) / ln1p((4.0 - 0.1) / 0.1)
        requireNotNull(slider.node.config[SemanticsActions.SetProgress].action).invoke(f.toFloat())
        SmokeUi.settle(4)
        assertEquals(base * 2f, out0(), 0.05f)
        assertFalse("ended", tool.handleScaling)
        assertTrue(tool.undoStep())

        // Typed: 137 % exactly.
        SmokeUi.click("Type handle scale")
        SmokeUi.typeAndDone("Scale", "137")
        assertEquals(base * 1.37f, out0(), 1e-3f)

        // The side chips.
        SmokeUi.click("Out", exact = true)
        assertEquals(ShapeHandleSide.OUT, tool.handleSide)
        SmokeUi.click("In and out", exact = true)
        assertEquals(ShapeHandleSide.BOTH, tool.handleSide)
        SmokeUi.click("All points", exact = true)
        assertTrue(tool.handlesAllPoints)

        // A long-press on the value: the Scale step's popup.
        val value = RobolectricUi.elements().last { it.node.config.getOrNull(SemanticsActions.OnClick)?.label == "Type handle scale" }
        requireNotNull(value.node.config[SemanticsActions.OnLongClick].action).invoke()
        SmokeUi.settle()
        assertTrue("the Step popup: ${SmokeUi.shown().take(60)}", SmokeUi.has("Step for scales", exact = true))
        SmokeUi.click("Cancel", exact = true)
        c.dispose()
    }

    private fun ln1p(x: Double) = kotlin.math.ln1p(x)
}
