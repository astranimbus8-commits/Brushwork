package com.brushwork.paint.tools.transform

import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.ui.common.TransformLabels17
import com.brushwork.paint.ui.placement.TransformToolOptions
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.7 (item 11, design §3.11 a) on a 392 dp phone: transforming a text layer kept as text (a
 * fake map stands in for area D's), the strip has no flips, the Distort chip carries "Rasterize
 * to deform" as its state, and a tap on it offers "Rasterize and deform", which rasterizes the
 * layer and switches to Distort (the flips come back).
 *
 * One test: Compose's frame clock only serves the first test of a Robolectric sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h860dp-xhdpi", instrumentedPackages = ["com.brushwork.paint.tools.transform.datachipsandbox"])
class DataLiftModeChipUiRobolectricTest {

    /** A text map that moves the centre only (enough for this strip). */
    private object MoveOnlyMaps : DataMaps {
        override fun textCanMap(m: FloatArray): Boolean = true
        override fun text(textData: String, m: FloatArray): String? = TextCodec.decode(textData)?.let { t ->
            TextCodec.encode(t.copy(cx = m[0] * t.cx + m[1] * t.cy + m[2], cy = m[3] * t.cx + m[4] * t.cy + m[5]))
        }
        override fun shape(shapeData: String, m: FloatArray): String? = null
        override fun array(layer: Layer, m: FloatArray): LayerData? = null
    }

    /** The state description of the clickable node labelled [label]. */
    private fun stateOf(label: String): String? {
        var n: SemanticsNode? = SmokeUi.find(label, exact = true)?.node
        while (n != null && n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent
        return n?.config?.getOrNull(SemanticsProperties.StateDescription)
    }

    @Test
    fun aTextLayersStripRefusesDistortWithItsCaptionAndRasterizesOnRequest() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val c: EditorController = Smoke.controller(activity, Smoke.document(400, 300, layers = 2))
        val item = TextItem("Hello", spec = TextSpec(sizePx = 64f), cx = 200f, cy = 150f)
        val layer = c.addLayerWithContent("Text", "Add text", textData = TextCodec.encode(item)) { cv: Canvas ->
            TextRenderer.drawItem(cv, item, TextRenderer.prepare(item), null)
        }!!
        val tool = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        tool.dataMaps = { MoveOnlyMaps }
        activity.setContent {
            BrushworkTheme {
                Row(Modifier.horizontalScroll(rememberScrollState())) { TransformToolOptions(tool) }
            }
        }
        c.selectTool(ToolId.TRANSFORM)
        Smoke.pump(100)
        SmokeUi.settle()
        assertEquals("lifted as a text", TransformTool.Lifted.TEXT, tool.lifted)
        assertFalse("no flips for a text", SmokeUi.has("Flip horizontally", exact = true))
        assertFalse(SmokeUi.has("Flip vertically", exact = true))
        assertTrue(SmokeUi.has("Free", exact = true))
        assertEquals("the Distort chip's caption", TransformLabels17.RASTERIZE_TO_DEFORM, stateOf("Distort"))
        assertNull("Free is available", stateOf("Free"))

        // A tap on Distort shows the caption and the way out; Distort itself is not switched to.
        SmokeUi.click("Distort", exact = true)
        assertEquals(TransformTool.Mode.FREE, tool.mode)
        assertTrue(SmokeUi.has(TransformLabels17.RASTERIZE_TO_DEFORM, exact = true))
        val steps = c.undoManager.undoCount
        SmokeUi.click(TransformLabels17.RASTERIZE_AND_DEFORM, exact = true)
        SmokeUi.settle()
        assertNull("rasterized", layer.textData)
        assertEquals("one step (nothing was moved before)", steps + 1, c.undoManager.undoCount)
        assertEquals(TransformTool.RASTERIZE_TEXT_LABEL, c.undoManager.undoLabel)
        assertEquals(TransformTool.Mode.DISTORT, tool.mode)
        assertEquals(TransformTool.Lifted.PIXELS, tool.lifted)
        assertTrue("the flips are back", SmokeUi.has("Flip horizontally", exact = true))
        assertNull(stateOf("Distort"))
        Smoke.assertQuiet(c, "data lift strip")
        c.dispose()
    }
}
