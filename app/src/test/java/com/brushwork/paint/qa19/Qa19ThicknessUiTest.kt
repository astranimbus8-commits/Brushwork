package com.brushwork.paint.qa19

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.CurveLabels17
import com.brushwork.paint.ui.common.CurveLabels19
import com.brushwork.paint.ui.common.EraseLabels19
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.common.V19Tags
import com.brushwork.paint.ui.points.ThicknessRowDefaults
import com.brushwork.paint.ui.points.ThicknessRowLabels
import com.brushwork.paint.ui.vector.POINT_THICKNESS_LABEL
import com.brushwork.paint.ui.vector.POINT_WEIGHT_LABEL
import com.brushwork.paint.ui.vector.STROKE_KINDS
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.draw.VectorEraseMode
import com.brushwork.paint.vector.draw.VectorEraserModes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * (A1 step 10): a section per tool turns on "Editable (own layer)" in its settings sheet, and at
 * 360 dp and font scales 1.3 and 2 the row keeps its texts inside its box. v1.9 R11 (a) (A1
 * steps 6 and 7, the review): erasing a vector layer with fingers at 392 and 360 dp and every
 * font scale, the three tools in turn: the Stroke-kind menu's last row, slot 1, the row-1 mode
 * cell, its menu and its pixels dialog.
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
        // v1.9 R14 (I27; A1 review): the own layer row at 360 dp and the large font scales.
        try {
            for (scale in Qa19Ui.FONT_SCALES) {
                val where = "${CurveLabels19.OWN_LAYER} at 360, font scale $scale"
                h.section(where) {
                    RuntimeEnvironment.setQualifiers(Qa19Ui.Q360)
                    checkTheOwnLayerRowFits(h, scale, where)
                }
            }
            // v1.9 R11 (a) (A1 steps 6, 7; I27; A1 review): erasing a vector layer with fingers, at
            // both widths and every font scale, the three tools in turn.
            var k = 0
            for (q in listOf(Qa19Ui.Q392, Qa19Ui.Q360)) for (scale in listOf(1f) + Qa19Ui.FONT_SCALES) {
                // (At 360 dp the tools take the font scales in another order.)
                val id = listOf(ToolId.CURVE, ToolId.POLYLINE, ToolId.PATH)[(k++ + if (q == Qa19Ui.Q360) 1 else 0) % 3]
                val where = "$id erasing a vector layer at ${q.substringBefore('-')}, font scale $scale"
                h.section(where) {
                    RuntimeEnvironment.setQualifiers(q)
                    checkErasingAVectorLayer(h, id, scale, where)
                }
            }
        } finally {
            RuntimeEnvironment.setQualifiers(Qa19Ui.Q392)
        }
    }

    /** I27: the own layer row and its description keep their texts inside their boxes at [scale]. */
    private fun checkTheOwnLayerRowFits(h: Qa19Harness, scale: Float, where: String) {
        val s = h.editor(fontScale = scale)
        s.c.selectTool(ToolId.POLYLINE)
        Qa19Ui.settle()
        Qa19Ui.click("${ToolId.POLYLINE.label} settings", exact = true)
        assertTrue("$where: the row", Qa19Ui.has(CurveLabels19.OWN_LAYER, exact = true))
        assertTrue("$where: its description", Qa19Ui.has(CurveLabels19.OWN_LAYER_DESC, exact = true))
        assertNoOverflowOf(where, setOf(CurveLabels19.OWN_LAYER, CurveLabels19.OWN_LAYER_DESC))
        s.activity.onBackPressedDispatcher.onBackPressed()
        Qa19Ui.settle()
    }

    /**
     * v1.9 R11 (a): with three points on a vector layer, a finger on "Stroke kind" and on its last
     * row ([EraseLabels19.INSTEAD_OF_PAINT]) turns erase mode on; slot 1 then reads the tool's
     * [CurveTool.erasingLabel]; row 1 shows the mode cell ([V19Tags.ERASE_MODE_CELL]) on screen; its
     * menu picks "Partial" (the cell says it); its [EraseLabels19.TURN_INTO_PIXELS] asks first and
     * turns the layer into pixels (the cell goes, the outline stays pending, erasing); ✓ makes ONE
     * [EraseLabels19.STEP_LINE] / [EraseLabels19.STEP_PATH] step; a tap on slot 1 turns erase off.
     * The menus and the dialog keep their texts inside their boxes.
     */
    private fun checkErasingAVectorLayer(h: Qa19Harness, id: ToolId, scale: Float, where: String) {
        val doc = Smoke.document(400, 300, layers = 2, whiteBottom = true).also { it.layers[1].vector = VectorContent.EMPTY }
        val s = h.editor(doc, fontScale = scale)
        val c = s.c
        // A filled box under the outline: the erase has something to take.
        val box = VPath(
            0, subpaths = listOf(VSubpath(listOf(VAnchor(40f, 70f, true), VAnchor(230f, 70f, true), VAnchor(230f, 230f, true), VAnchor(40f, 230f, true)), closed = true)),
            fill = VPaint.Solid(0xFF3070C0.toInt()),
        )
        c.vectors.addObjects(c.doc.activeLayer, listOf(box), "Add")
        VectorEraserModes.setMode(c, VectorEraseMode.OBJECT)
        c.selectTool(id)
        val t = c.tools.getValue(id) as CurveTool
        for (p in listOf(Vec2(60f, 200f), Vec2(130f, 90f), Vec2(200f, 160f))) assertTrue(t.addAnchor(p))
        t.deselect()
        Qa19Ui.settle()
        assertNull("$where: no mode cell while painting", Qa19Ui.tagged(V19Tags.ERASE_MODE_CELL))

        Qa19Ui.click(CurveLabels17.STROKE_KIND, exact = true)
        assertTrue("$where: the menu's last row; shown ${Qa19Ui.shown().take(60)}", Qa19Ui.has(EraseLabels19.INSTEAD_OF_PAINT, exact = true))
        assertNoOverflowOf("$where, the Stroke kind menu", setOf(EraseLabels19.INSTEAD_OF_PAINT) + STROKE_KINDS.map { it.label })
        Qa19Ui.click(EraseLabels19.INSTEAD_OF_PAINT, exact = true)
        assertTrue("$where: erasing", t.eraseOn)
        assertTrue("$where: slot 1 says so", Qa19Ui.has(t.erasingLabel, exact = true))

        val cell = requireNotNull(Qa19Ui.tagged(V19Tags.ERASE_MODE_CELL)) { "$where: the mode cell in row 1; shown ${Qa19Ui.shown().take(60)}" }
        val left = cell.positionInWindow.x
        assertTrue("$where: the cell on screen ($left + ${cell.size.width} of ${s.root.width})", left >= 0f && left + cell.size.width <= s.root.width + 0.5f)
        Qa19Ui.click(EraseLabels19.MODE_CELL.format(VectorEraseMode.OBJECT.label), exact = true)
        assertNoOverflowOf("$where, the mode menu", VectorEraseMode.entries.flatMap { listOf(it.label, it.note) }.toSet() + EraseLabels19.TURN_INTO_PIXELS)
        Qa19Ui.click(VectorEraseMode.PARTIAL.label, exact = true)
        assertEquals("$where: the mode chosen", VectorEraseMode.PARTIAL, VectorEraserModes.mode(c))
        assertTrue("$where: the cell says it", Qa19Ui.has(EraseLabels19.MODE_CELL.format(VectorEraseMode.PARTIAL.label), exact = true))

        Qa19Ui.click(EraseLabels19.MODE_CELL.format(VectorEraseMode.PARTIAL.label), exact = true)
        Qa19Ui.click(EraseLabels19.TURN_INTO_PIXELS, exact = true)
        assertTrue("$where: asks first", Qa19Ui.has(EraseLabels19.PIXELS_TITLE, exact = true))
        assertTrue(c.doc.activeLayer.isVectorLayer)
        assertNoOverflowOf("$where, the pixels dialog", setOf(EraseLabels19.PIXELS_TITLE, EraseLabels19.PIXELS_BODY, EraseLabels19.PIXELS_CONFIRM))
        Qa19Ui.click(EraseLabels19.PIXELS_CONFIRM, exact = true)
        assertFalse("$where: the layer is pixels now", c.doc.activeLayer.isVectorLayer)
        assertNull("$where: no mode cell on pixels", Qa19Ui.tagged(V19Tags.ERASE_MODE_CELL))
        assertTrue("$where: the outline still pending, erasing", t.hasPendingWork && t.eraseOn)

        val steps = c.undoManager.undoCount
        t.commit()
        Qa19Ui.settle()
        assertEquals("$where: ONE step", steps + 1, c.undoManager.undoCount)
        assertEquals(if (id == ToolId.PATH) EraseLabels19.STEP_PATH else EraseLabels19.STEP_LINE, c.undoManager.undoLabel)
        Qa19Ui.click(t.erasingLabel, exact = true)
        assertFalse("$where: slot 1 turns it off", t.eraseOn)
        assertTrue(Qa19Ui.has(t.eraseLabel, exact = true))
    }

    /** I27 for the texts [texts] on screen: none overflows its box. */
    private fun assertNoOverflowOf(where: String, texts: Set<String>) {
        val bad = Qa19Ui.textOverflows { e -> e.node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } in texts }
        if (bad.isNotEmpty()) throw AssertionError("texts overflow their box $where:\n" + bad.joinToString("\n"))
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
