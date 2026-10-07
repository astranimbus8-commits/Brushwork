package com.brushwork.paint.ui.common

import android.graphics.Canvas
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.ui.editor.SliderMath
import com.brushwork.paint.ui.editor.ValueInputDialog
import com.brushwork.paint.ui.theme.BrushworkTheme
import com.brushwork.paint.ui.tools.CoordinatePill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.7 (item 15, design §3.15, F4): relative text typed into the shared fields applies to the
 * value the edit started from: "/2" in the X / Y pill's X at 300 gives 150, "*2" in a NumberField
 * thickness at 4 gives 8, "/2" in a ValueInputDialog opened at 60 (an opacity held as 0..1 and
 * shown in percent) gives 30. 392 dp phone; own sandbox, one test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.common.relativefieldssandbox"])
class RelativeFieldsRobolectricTest {

    @Test
    fun relativeTextAppliesToTheCurrentValue() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val c = Smoke.controller(activity, Smoke.document(400, 300, layers = 2))
        c.editWholeLayer(c.activeLayer, "Seed") { b -> Canvas(b).drawRect(100f, 100f, 180f, 140f, Paint().apply { color = RED }) }
        val tool = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        var thickness by mutableDoubleStateOf(4.0)
        var opacity by mutableFloatStateOf(0.6f)
        var dialog by mutableStateOf(false)
        activity.setContent {
            BrushworkTheme {
                CompositionLocalProvider(LocalIncrements provides c.increments) {
                    Column {
                        CoordinatePill(c)
                        NumberField("Thickness", thickness, { thickness = it }, decimals = 1, suffix = "px", min = 0.0, max = 100.0, step = 1.0, adjust = NumberAdjust.NONE)
                        if (dialog) {
                            ValueInputDialog(
                                title = "Layer opacity",
                                label = "Opacity",
                                initial = opacity,
                                format = { SliderMath.formatPercent(it).removeSuffix("%") },
                                parse = SliderMath::parsePercent,
                                step = SliderMath::stepPercent,
                                toFraction = { it },
                                fromFraction = { kotlin.math.round(it * 100f) / 100f },
                                rangeText = "0 – 100 %",
                                suffix = "%",
                                onApply = { opacity = it },
                                onDismiss = { dialog = false },
                            )
                        }
                    }
                }
            }
        }
        c.selectTool(ToolId.TRANSFORM)
        Smoke.pump(100)
        SmokeUi.settle()
        assertTrue("lifted", tool.hasPendingWork)

        // The pill's X: 300, then "/2" applies to the 300 the dialog opened with.
        SmokeUi.click("Type X")
        SmokeUi.typeAndDone("X", "300")
        assertEquals(300f, tool.anchorPosition!!.x, 1e-3f)
        SmokeUi.click("Type X")
        SmokeUi.typeAndDone("X", "/2")
        assertEquals(150f, tool.anchorPosition!!.x, 1e-3f)

        // A NumberField: "*2" at 4 gives 8, applied once although the field commits while typing
        // and again when it loses focus.
        SmokeUi.typeAndDone("Thickness", "*2")
        assertEquals(8.0, thickness, 1e-9)
        SmokeUi.typeAndLeave("Thickness", "*2")
        assertEquals(16.0, thickness, 1e-9)

        // A value dialog opened at 60 (%): "/2" gives 30 %.
        dialog = true
        SmokeUi.settle()
        SmokeUi.typeAndDone("Opacity", "/2")
        assertEquals(0.3f, opacity, 1e-6f)
        c.dispose()
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
    }
}
