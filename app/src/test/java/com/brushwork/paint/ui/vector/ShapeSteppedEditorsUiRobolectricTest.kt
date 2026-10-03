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
import com.brushwork.paint.tools.vector.CornerStyle
import com.brushwork.paint.tools.vector.ShapeStroke
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.LocalIncrements
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.math.abs
import kotlin.math.round

/**
 * v1.6 §3.4 (G): the shape tool's Settings sheet at the user's 392 dp. The stroke width is a Size
 * and the corner radius a Length: with increments off their logarithmic sliders move freely (as
 * v1.5); with increments on they land on the multiples of the Size / Length step, their ends
 * still reachable. Own sandbox, one test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.vector.steppededitorssandbox"])
class ShapeSteppedEditorsUiRobolectricTest {

    private fun slider(label: String): RobolectricUi.Element = RobolectricUi.elements().last { e ->
        e.node.layoutInfo.isPlaced && e.node.config.getOrNull(SemanticsActions.SetProgress) != null &&
            e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true
    }

    private fun setProgress(label: String, f: Float) {
        requireNotNull(slider(label).node.config[SemanticsActions.SetProgress].action).invoke(f)
        SmokeUi.settle(4)
    }

    private fun onMultiple(v: Float, step: Float): Boolean = abs(v / step - round(v / step)) < 1e-3f

    @Test
    fun strokeWidthAndCornerRadiusStep() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val c = Smoke.controller(activity, Smoke.document(400, 300, layers = 1))
        c.viewTransform.set(android.graphics.Matrix())
        c.selectTool(ToolId.SHAPE)
        val tool = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        tool.update {
            it.copy(
                type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE, strokeWith = ShapeStroke.PLAIN, useBrushSize = false,
                strokeWidth = 4f, corner = CornerStyle.ROUND, cornerRadius = 12f,
            )
        }
        activity.setContent {
            BrushworkTheme {
                CompositionLocalProvider(LocalIncrements provides c.increments) {
                    FlowRow(Modifier.fillMaxWidth()) { ShapeToolOptions(tool) }
                }
            }
        }
        SmokeUi.settle()
        SmokeUi.click("Settings", exact = true)
        assertTrue("the settings sheet: ${SmokeUi.shown().take(40)}", SmokeUi.has("Stroke width slider") && SmokeUi.has("Corner radius slider"))

        // Off (the default): free, as in v1.5.
        setProgress("Corner radius slider", 0.43f)
        val free = tool.settings.cornerRadius
        assertTrue("free: $free", !onMultiple(free, 10f))
        setProgress("Stroke width slider", 0.43f)
        val freeWidth = tool.strokeWidth
        assertTrue("free: $freeWidth", !onMultiple(freeWidth, 2f))

        // On: Length 10 px for the corner radius, Size 2 px for the stroke width.
        c.increments.update { it.copy(enabled = true, lengthPx = 10f, sizePx = 2f) }
        SmokeUi.settle(4)
        // (A slider ignores a set-progress to where it already is: another position.)
        setProgress("Corner radius slider", 0.47f)
        val raw = Math.pow(1000.0, 0.47).toFloat() // the log slider's 1..1000 px at 0.47
        assertTrue("the radius lands on 10 px: ${tool.settings.cornerRadius}", onMultiple(tool.settings.cornerRadius, 10f))
        assertEquals(raw, tool.settings.cornerRadius, 5f)
        setProgress("Corner radius slider", 0f)
        assertEquals("the slider's start stays reachable", 1f, tool.settings.cornerRadius, 1e-4f)
        setProgress("Stroke width slider", 0.47f)
        assertTrue("the width lands on 2 px: ${tool.strokeWidth}", onMultiple(tool.strokeWidth, 2f))
        assertTrue("near where the finger is: ${tool.strokeWidth} / $freeWidth", tool.strokeWidth >= freeWidth - 1f)
        c.dispose()
    }
}
