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
import com.brushwork.paint.core.Affine2
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.common.TransformLabels17
import com.brushwork.paint.ui.placement.MeshStepperLabels
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
 * v1.7 (item 16, design §3.16) on a 392 dp phone: on a text layer kept as text (a fake map stands
 * in for area D's) the "Free deform" chip is dimmed with "Rasterize to free deform" as its state;
 * a tap shows the caption and "Rasterize and deform", which rasterizes the layer and opens Free
 * deform. Its strip then shows "Select several", the "Mesh columns" and "Mesh rows" steppers,
 * "Smooth mesh" and "Reset mesh" (enabled once the mesh is deformed).
 *
 * One test: Compose's frame clock only serves the first test of a Robolectric sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h860dp-xhdpi", instrumentedPackages = ["com.brushwork.paint.tools.transform.freedeformchipsandbox"])
class FreeDeformChipUiRobolectricTest {

    /** A text map that moves the centre only (enough for this strip). */
    private object MoveOnlyMaps : DataMaps {
        override fun textCanMap(m: FloatArray): Boolean = true
        override fun text(textData: String, m: FloatArray): String? = TextCodec.decode(textData)?.let { t ->
            TextCodec.encode(t.copy(cx = m[0] * t.cx + m[1] * t.cy + m[2], cy = m[3] * t.cx + m[4] * t.cy + m[5]))
        }
        override fun shape(shapeData: String, m: FloatArray): String? = null
        override fun array(layer: Layer, m: FloatArray): LayerData? = null
    }

    /** The state description of [label]'s node, or of its nearest clickable ancestor. */
    private fun stateOf(label: String): String? {
        var n: SemanticsNode? = SmokeUi.find(label, exact = true)?.node
        n?.config?.getOrNull(SemanticsProperties.StateDescription)?.let { return it }
        while (n != null && n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent
        return n?.config?.getOrNull(SemanticsProperties.StateDescription)
    }

    @Test
    fun aTextLayersFreeDeformChipCarriesItsCaptionAndTheMeshStripFollowsARasterize() {
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
        assertEquals(TransformTool.Lifted.TEXT, tool.lifted)
        assertTrue(SmokeUi.has(TransformLabels17.FREE_DEFORM, exact = true))
        assertEquals("the chip's caption", TransformLabels17.RASTERIZE_TO_FREE_DEFORM, stateOf(TransformLabels17.FREE_DEFORM))
        assertFalse("no mesh strip yet", SmokeUi.has(TransformLabels17.SMOOTH, exact = true))

        // A tap shows the caption and the way out; Free deform itself is not switched to.
        SmokeUi.click(TransformLabels17.FREE_DEFORM, exact = true)
        assertEquals(TransformTool.Mode.FREE, tool.mode)
        assertTrue(SmokeUi.has(TransformLabels17.RASTERIZE_TO_FREE_DEFORM, exact = true))
        SmokeUi.click(TransformLabels17.RASTERIZE_AND_DEFORM, exact = true)
        SmokeUi.settle()
        assertNull("rasterized", layer.textData)
        assertEquals(TransformTool.Mode.MESH, tool.mode)
        assertTrue(tool.isMeshShown)
        assertNull("now available", stateOf(TransformLabels17.FREE_DEFORM))

        // The Free deform strip.
        assertTrue(SmokeUi.has(PointLabels.SELECT_SEVERAL, exact = true))
        assertTrue(SmokeUi.has(PointLabels.SELECT_ALL, exact = true))
        assertTrue(SmokeUi.has(TransformLabels17.SMOOTH, exact = true))
        assertEquals("3", stateOf(TransformLabels17.COLUMNS))
        assertEquals("3", stateOf(TransformLabels17.ROWS))
        assertFalse("nothing to reset", SmokeUi.isEnabled(TransformLabels17.RESET))
        SmokeUi.click(MeshStepperLabels.MORE_COLUMNS, exact = true)
        assertEquals(4, tool.meshColumns)
        assertEquals("4", stateOf(TransformLabels17.COLUMNS))
        SmokeUi.click(MeshStepperLabels.FEWER_ROWS, exact = true)
        assertEquals(2, tool.meshRows)
        assertEquals(15, tool.pointCount)
        SmokeUi.click(PointLabels.SELECT_SEVERAL, exact = true)
        assertTrue(tool.selectSeveral)
        assertEquals("On", stateOf(PointLabels.SELECT_SEVERAL))
        SmokeUi.click(PointLabels.SELECT_ALL, exact = true)
        assertTrue(tool.allPointsSelected)
        assertTrue(SmokeUi.has(PointLabels.DESELECT_ALL, exact = true))

        tool.selectPoints(PointSelection.of(tool.pointCount, 6))
        tool.beginGroupEdit("Move points")
        tool.setGroupTransform(Affine2.translate(12f, 0f))
        tool.endGroupEdit()
        SmokeUi.settle()
        assertTrue("deformed: Reset mesh is enabled", SmokeUi.isEnabled(TransformLabels17.RESET))
        SmokeUi.click(TransformLabels17.RESET, exact = true)
        assertFalse(tool.isMeshChanged)
        Smoke.assertQuiet(c, "free deform strip")
        c.dispose()
    }
}
