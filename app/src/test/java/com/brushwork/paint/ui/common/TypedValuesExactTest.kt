package com.brushwork.paint.ui.common

import android.graphics.Canvas
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.placement.TransformToolOptions
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
 * v1.6 §3.4 (d) TypedValuesExactTest (area G): with increments on (Length 10 px, Percent 5 %,
 * Scale 10 %, Angle 15°) a TYPED value is never quantized, anywhere: a percentage NumberField, a
 * LengthField, the X / Y pill's "Type X" (ValueInputDialog), the Transform Numbers sheet's Scale
 * and Rotation. Their -/+ buttons and sliders, on the other hand, go to the step's multiples (the
 * Scale field by the Scale step, not the Percent one). 392 dp phone; own sandbox, one test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.common.typedexactsandbox"])
class TypedValuesExactTest {

    /** The NumberField slider labelled [label] (its content description), placed. */
    private fun slider(label: String): RobolectricUi.Element = RobolectricUi.elements().last { e ->
        e.node.layoutInfo.isPlaced && e.node.config.getOrNull(SemanticsActions.SetProgress) != null &&
            e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true
    }

    @Test
    fun typedValuesAreNeverStepped() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val c = Smoke.controller(activity, Smoke.document(400, 300, layers = 2))
        c.editWholeLayer(c.activeLayer, "Seed") { b -> Canvas(b).drawRect(100f, 100f, 180f, 140f, Paint().apply { color = RED }) }
        c.increments.update { it.copy(enabled = true, lengthPx = 10f, percent = 5f, scalePercent = 10f, angleDeg = 15f) }
        val tool = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        var amount by mutableDoubleStateOf(50.0)
        var offset by mutableDoubleStateOf(100.0)
        activity.setContent {
            BrushworkTheme {
                CompositionLocalProvider(LocalIncrements provides c.increments) {
                    Column {
                        CoordinatePill(c)
                        NumberField("Amount", amount, { amount = it }, decimals = 0, suffix = "%", min = 0.0, max = 100.0, step = 1.0, adjust = NumberAdjust.NONE)
                        LengthField("Offset", offset, { offset = it }, LengthUnit.PX, 72.0, step = 1.0, minPx = 0.0, maxPx = 1000.0, adjust = NumberAdjust.NONE)
                        Row(Modifier.horizontalScroll(rememberScrollState())) { TransformToolOptions(tool) }
                    }
                }
            }
        }
        c.selectTool(ToolId.TRANSFORM)
        Smoke.pump(100)
        SmokeUi.settle()
        assertTrue("lifted", tool.hasPendingWork)

        // A percentage field: typed 37 % stays 37; its + / − go to the next multiples of 5.
        SmokeUi.typeAndDone("Amount", "37")
        assertEquals(37.0, amount, 0.0)
        SmokeUi.tap("Increase Amount", exact = true)
        SmokeUi.settle(4)
        assertEquals(40.0, amount, 1e-9)
        SmokeUi.tap("Decrease Amount", exact = true)
        SmokeUi.settle(4)
        assertEquals(35.0, amount, 1e-9)

        // A length field (Length step 10 px): typed 37 px stays 37; + goes to 40.
        SmokeUi.typeAndDone("Offset", "37")
        assertEquals(37.0, offset, 0.0)
        SmokeUi.tap("Increase Offset", exact = true)
        SmokeUi.settle(4)
        assertEquals(40.0, offset, 1e-9)

        // The pill's "Type X" (ValueInputDialog): 237 stays 237; the dialog's + goes to 240.
        SmokeUi.click("Type X")
        SmokeUi.typeAndDone("X", "237")
        assertEquals(237f, tool.anchorPosition!!.x, 1e-3f)
        SmokeUi.click("Type X")
        SmokeUi.tap("Increase X", exact = true)
        SmokeUi.settle(4)
        SmokeUi.clickIn("X position", "OK")
        assertEquals(240f, tool.anchorPosition!!.x, 1e-3f)

        // The Transform Numbers sheet: Scale typed 37 % stays 37 (not 40); its + goes to 40 %
        // of the original (the Scale step), not 42 (the Percent one).
        SmokeUi.click("Numbers", exact = true)
        SmokeUi.settle(20, 50)
        SmokeUi.typeAndDone("Scale", "37")
        assertEquals(37f, tool.transformState!!.scalePercent, 1e-3f)
        SmokeUi.tap("Increase Scale", exact = true)
        SmokeUi.settle(4)
        assertEquals(40f, tool.transformState!!.scalePercent, 1e-3f)
        // Rotation typed 22.5° stays 22.5; its slider lands on multiples of 15°.
        SmokeUi.typeAndDone("Rotation", "22.5")
        assertEquals(22.5f, tool.transformState!!.rotationDeg, 1e-3f)
        val rot = slider("Rotation")
        requireNotNull(rot.node.config[SemanticsActions.SetProgress].action).invoke((37f + 180f) / 360f)
        SmokeUi.settle(4)
        assertEquals(30f, tool.transformState!!.rotationDeg, 1e-3f)
        c.dispose()
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
    }
}
