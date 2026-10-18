package com.brushwork.paint.qa19

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.CurveLabels19
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.common.V19Tags
import com.brushwork.paint.ui.points.ThicknessRowDefaults
import com.brushwork.paint.ui.points.ThicknessRowLabels
import com.brushwork.paint.ui.vector.POINT_THICKNESS_LABEL
import com.brushwork.paint.ui.vector.POINT_WEIGHT_LABEL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.9 R1 (design 1 R1 (a), (e); I27): the several points' thickness row at 392 and 360 dp and at
 * font scales 1, 1.3 and 2: in Polyline, Curve and Path the More row shows the one-point row
 * (‹, the value box reading "Mixed", ›, the slider) whole inside its 260 dp cell, the slider at
 * least 96 dp (128 dp at font scale 1), the value box at most its largest width, and no text of
 * the new controls overflows. Path's group weight popup shows the orange group control. One
 * class: the widths and font scales are sections (`RuntimeEnvironment.setQualifiers`). v1.9 R14
 * (A1 step 10): a last section per tool turns on "Editable (own layer)" in its settings sheet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q392, instrumentedPackages = ["com.brushwork.paint.qa19.thicknesssandbox"])
class Qa19ThicknessUiTest {

    @Test
    fun severalPointsThicknessAtEveryWidthAndFontScale() = Qa19Ui.uiTest { h ->
        try {
            for (q in listOf(Qa19Ui.Q392, Qa19Ui.Q360)) for (scale in listOf(1f) + Qa19Ui.FONT_SCALES) for (id in listOf(ToolId.POLYLINE, ToolId.CURVE, ToolId.PATH)) {
                val where = "$id at ${q.substringBefore('-')}, font scale $scale"
                h.section(where) {
                    RuntimeEnvironment.setQualifiers(q)
                    checkTheGroupRow(h, id, scale, where)
                }
            }
        } finally {
            RuntimeEnvironment.setQualifiers(Qa19Ui.Q392)
        }
        for (id in listOf(ToolId.CURVE, ToolId.POLYLINE, ToolId.PATH)) {
            h.section("$id: ${CurveLabels19.OWN_LAYER} in the settings sheet") { checkTheOwnLayerRow(h, id) }
        }
    }

    /**
     * v1.9 (R14, D22; A1 step 10): the tool's settings sheet has the "Editable (own layer)" row,
     * off by default; turned on there, ✓ puts the pending path on the pixel layer into a new
     * vector layer "Curves 1" (the first vector layer of the drawing).
     */
    private fun checkTheOwnLayerRow(h: Qa19Harness, id: ToolId) {
        val s = h.editor()
        s.c.selectTool(id)
        val t = s.c.tools.getValue(id) as CurveTool
        for (p in listOf(Vec2(60f, 200f), Vec2(130f, 90f), Vec2(200f, 160f))) assertTrue(t.addAnchor(p))
        Qa19Ui.settle()
        assertFalse("$id: off by default", t.settings.ownLayer)
        Qa19Ui.click("${id.label} settings", exact = true)
        Qa19Ui.click(CurveLabels19.OWN_LAYER, exact = true)
        assertTrue("$id: the row turns it on", t.settings.ownLayer)
        assertTrue("$id: with its description", Qa19Ui.has(CurveLabels19.OWN_LAYER_DESC, exact = true))
        s.activity.onBackPressedDispatcher.onBackPressed()
        Qa19Ui.settle()
        val layers = s.c.doc.layers.size
        val raster = s.c.doc.activeLayer
        assertFalse(raster.isVectorLayer)
        t.commit()
        Qa19Ui.settle()
        assertEquals("$id: one new layer", layers + 1, s.c.doc.layers.size)
        assertEquals("$id: the path's own layer", "${CurveLabels19.LAYER_BASE} 1", s.c.doc.activeLayer.name)
        assertEquals(1, s.c.doc.activeLayer.vector?.objects?.size)
    }

    private fun checkTheGroupRow(h: Qa19Harness, id: ToolId, scale: Float, where: String) {
        val s = h.editor(fontScale = scale)
        s.c.selectTool(id)
        val t = s.c.tools.getValue(id) as CurveTool
        for (p in listOf(Vec2(60f, 200f), Vec2(130f, 90f), Vec2(200f, 160f), Vec2(270f, 80f))) assertTrue(t.addAnchor(p))
        t.selectPoints(PointSelection.of(t.pointCount, 1, 2))
        t.setWidths(t.pointSelection.indices, floatArrayOf(0.4f, 1.2f))
        t.endNumericEdit()
        Qa19Ui.settle()
        Qa19Ui.openMoreRow()
        val row = requireNotNull(Qa19Ui.tagged(V19Tags.GROUP_THICKNESS)) { "$where: the group row; shown ${Qa19Ui.shown().take(60)}" }
        val d = s.density
        val left = row.positionInWindow.x
        val right = left + row.size.width
        val screen = s.root.width
        assertTrue("$where: the row is 260 dp (${row.size.width / d})", kotlin.math.abs(row.size.width / d - ThicknessRowDefaults.WIDTH.value) < 1f)
        assertTrue("$where: the row is on screen ($left..$right of $screen)", left >= 0f && right <= screen + 0.5f)
        val thicker = node(ThicknessRowLabels.THICKER)
        // (The slider's own layout: its semantics node reaches 10 dp past each end.)
        val slider = requireNotNull(Qa19Ui.tagged(ThicknessRowDefaults.GROUP_TOUCHES_TAG)) { "$where: the group slider" }
        val value = node("$POINT_THICKNESS_LABEL ${PointLabels.MIXED}")
        val thickerRight = thicker.positionInWindow.x + thicker.size.width
        assertTrue("$where: › inside the cell", thicker.positionInWindow.x >= left && thickerRight <= right + 0.5f)
        assertTrue("$where: the value inside the cell", value.positionInWindow.x >= left && value.positionInWindow.x + value.size.width <= right + 0.5f)
        assertTrue("$where: the value box at most its largest width (${value.size.width / d} dp)", value.size.width / d <= ThicknessRowDefaults.VALUE_MAX.value + 0.5f)
        val sliderDp = slider.size.width / d
        val least = if (scale == 1f) 128f else ThicknessRowDefaults.SLIDER_MIN.value
        assertTrue("$where: the slider is $sliderDp dp (at least $least)", sliderDp >= least - 0.5f)
        assertNoOverflow(where)
        if (id == ToolId.PATH) {
            Qa19Ui.click(POINT_WEIGHT_LABEL, exact = true)
            assertNotNull("$where: the group weight control", Qa19Ui.tagged(V19Tags.GROUP_WEIGHT))
            assertNoOverflow("$where, the weight popup")
        }
    }

    /**
     * I27 for the new controls: no text of the several points' thickness row
     * ([V19Tags.GROUP_THICKNESS]) or weight control ([V19Tags.GROUP_WEIGHT]) overflows its box
     * ([Qa19Ui.textOverflows]). The rest of the screen holds v1.8 cells that outgrow their boxes
     * at font scale 2.0 (the X / Y pill's values; Path's "Order N" and "Point weight" faces), which
     * A1 step 9 (the strip inventory) and the lead's `StripFontScaleTest` take.
     */
    private fun assertNoOverflow(where: String) {
        val bad = Qa19Ui.textOverflows { e -> StripTestKit.under(e.node, V19Tags.GROUP_THICKNESS) || StripTestKit.under(e.node, V19Tags.GROUP_WEIGHT) }
        if (bad.isNotEmpty()) throw AssertionError("texts overflow their box $where:\n" + bad.joinToString("\n"))
    }

    /** The placed node whose content description is [description]. */
    private fun node(description: String): SemanticsNode = RobolectricUi.elements().last { e ->
        e.node.layoutInfo.isPlaced && e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true
    }.node
}
