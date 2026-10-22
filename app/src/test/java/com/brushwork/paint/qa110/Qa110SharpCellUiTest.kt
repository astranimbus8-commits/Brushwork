package com.brushwork.paint.qa110

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa16.QaCurves
import com.brushwork.paint.qa19.Qa19Harness
import com.brushwork.paint.qa19.Qa19Ui
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.testing.PillTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.tools.vector.CornerStyle
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.common.PointLabels110
import com.brushwork.paint.ui.common.V110Tags
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.theme.IbisDims
import com.brushwork.paint.ui.vector.SHARP_MIXED
import com.brushwork.paint.ui.vector.ShapeStripItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.10 final QA of R12 ("put the make sharp or smooth icon next or below the delete and select
 * several icon") on the real editor at 392 and 360 dp and font scale 1.3 and 2.0, every tap on
 * the cell by a finger:
 * - Curve and Path: the cell (tag [V110Tags.PILL_SHARP]) ends row 2 under "Select several",
 *   whole on screen; smooth points read "Sharp corner" and a tap makes them sharp ("Smooth"
 *   then); a mix reads [PointLabels110.MIXED_MAKES_SHARP] and a tap makes every selected point
 *   sharp; the ends of an open Path stay as they are;
 * - Shape's Points mode: the rectangle's corners read "Smooth"; a tap smooths them; a mix makes
 *   them sharp again;
 * - Polyline: the cell greyed in the same place ([PointLabels110.POLYLINE_ALWAYS_SHARP]), a tap
 *   changes nothing;
 * - the More rows no longer hold a Sharp / Smooth item;
 * and no text overflows its box.
 *
 * One test: Compose's frame clock only serves the first test of a Robolectric sandbox. The
 * 360 dp phone comes from `RuntimeEnvironment.setQualifiers` (as `MoreRowOffsetsTest`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q392, instrumentedPackages = ["com.brushwork.paint.qa110.sharpcellsandbox"])
class Qa110SharpCellUiTest {

    private val three = listOf(Vec2(120f, 150f), Vec2(200f, 90f), Vec2(280f, 160f))

    @Test
    fun theSharpCellByFingerAt392And360() = Qa19Ui.uiTest { h ->
        try {
            for (width in listOf(392, 360)) {
                if (width == 360) RuntimeEnvironment.setQualifiers(Qa19Ui.Q360)
                for (scale in Qa19Ui.FONT_SCALES) {
                    val at = "$width dp, font scale $scale"
                    for (id in listOf(ToolId.CURVE, ToolId.PATH)) h.section("$at: $id") { curve(h, id, width, scale) }
                    h.section("$at: Shape points") { shape(h, width, scale) }
                    h.section("$at: Polyline") { polyline(h, width, scale) }
                }
            }
        } finally {
            RuntimeEnvironment.setQualifiers(Qa19Ui.Q392)
        }
    }

    private fun open(h: Qa19Harness, id: ToolId, width: Int, scale: Float): ChromeScreen {
        val s = h.editor(fontScale = scale)
        assertEquals("the phone", width.toFloat(), s.widthDp, 1f)
        s.c.snapping.enabled = false
        s.c.selectTool(id)
        Qa19Ui.settle()
        return s
    }

    private fun drawThree(s: ChromeScreen): CurveTool {
        val tool = s.c.currentTool as CurveTool
        for (p in three) assertTrue("a point at $p", tool.addAnchor(p))
        tool.deselect()
        return tool
    }

    private fun select(s: ChromeScreen, tool: com.brushwork.paint.tools.points.PointEditor, vararg i: Int) {
        tool.selectPoints(PointSelection.of(tool.pointCount, *i))
        Qa19Ui.settle()
        assertCellPlace(s)
    }

    /** The cell ends row 2: under "Select several", whole inside the window less 8 dp; the cell. */
    private fun assertCellPlace(s: ChromeScreen): Rect {
        val cell = requireNotNull(s.tagged(V110Tags.PILL_SHARP)) { "the Sharp / Smooth cell; shown: ${Qa19Ui.shown().take(80)}" }
        val several = requireNotNull(s.tagged(V18Tags.PILL_SEVERAL)) { "Select several" }
        val trash = s.tagged(com.brushwork.paint.ui.common.V17Tags.PILL_TRASH)
        assertEquals("under Select several ($cell, $several)", several.center.x, cell.center.x, EPS)
        assertEquals("in row 2", several.center.y + IbisDims.PillHeight.value + IbisDims.PillRowGap.value, cell.center.y, EPS)
        if (trash != null) assertTrue("not under the trash ($cell, $trash)", cell.left >= trash.right - EPS)
        val margin = IbisDims.PillStart.value
        assertTrue("whole on screen: $cell in ${s.widthDp} dp", cell.left >= margin - EPS && cell.right <= s.widthDp - margin + EPS && cell.width > 0f)
        return cell
    }

    private fun tapCell(s: ChromeScreen) {
        val r = assertCellPlace(s)
        Finger.tapAt(s, r.center.x, r.center.y)
    }

    /** No More-row item named as the cell's faces (the pill's cell itself aside). */
    private fun assertNoMoreItem(what: String) {
        Qa19Ui.openMoreRow()
        val names = setOf(ShapeStripItems.SHARP_LABEL, ShapeStripItems.SMOOTH_LABEL, SHARP_MIXED, PointLabels.ENDS_SHARP)
        val items = RobolectricUi.elements().filter { e ->
            e.node.layoutInfo.isPlaced &&
                e.node.config.getOrNull(SemanticsProperties.TestTag) != V110Tags.PILL_SHARP &&
                (e.node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
                    e.node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                    listOfNotNull(e.node.config.getOrNull(SemanticsActions.OnClick)?.label)).any { it in names }
        }
        assertTrue("$what: no Sharp / Smooth item on the More row: ${items.map { it.bounds }}", items.isEmpty())
        Qa19Ui.closeMoreRow()
    }

    private fun curve(h: Qa19Harness, id: ToolId, width: Int, scale: Float) {
        val s = open(h, id, width, scale)
        val tool = drawThree(s)
        fun sharp(i: Int) = if (tool.isPath) tool.spline!!.points[i].sharp else tool.anchors[i].sharp
        select(s, tool, 1)
        assertEquals("$id: smooth, the tap makes it sharp", ShapeStripItems.SHARP_LABEL, PillTestKit.sharpName())
        tapCell(s)
        assertTrue("$id: a finger's tap made point 2 sharp", sharp(1))
        assertEquals("$id: now the tap smooths it", ShapeStripItems.SMOOTH_LABEL, PillTestKit.sharpName())
        Qa19Ui.assertNoTextOverflow("$id, one point")
        // A mix: point 2 sharp, point 1 (Curve) or the Path's end with its inner point.
        select(s, tool, 0, 1)
        if (id == ToolId.PATH) {
            assertEquals("Path: the end is always sharp, the inner point decides", ShapeStripItems.SMOOTH_LABEL, PillTestKit.sharpName())
            tool.setSharpPoints(listOf(1), false)
            Qa19Ui.settle()
            assertEquals(ShapeStripItems.SHARP_LABEL, PillTestKit.sharpName())
            tapCell(s)
            assertEquals("Path: the inner point sharp, the end as it was", listOf(false, true, false), (0..2).map { sharp(it) })
        } else {
            assertEquals("Curve: a mix", PointLabels110.MIXED_MAKES_SHARP, PillTestKit.sharpName())
            tapCell(s)
            assertEquals("Curve: Mixed means Sharp", listOf(true, true, false), (0..2).map { sharp(it) })
        }
        assertEquals(ShapeStripItems.SMOOTH_LABEL, PillTestKit.sharpName())
        Qa19Ui.assertNoTextOverflow("$id, a mix")
        assertNoMoreItem("$id")
        tool.discard()
        Qa19Ui.settle()
        Smoke.assertQuiet(s.c, "$id at $width dp, font $scale")
    }

    private fun shape(h: Qa19Harness, width: Int, scale: Float) {
        val s = open(h, ToolId.SHAPE, width, scale)
        val tool = s.c.currentTool as ShapeTool
        tool.update { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE, corner = CornerStyle.SHARP, keepProportions = false, fromCenter = false, snapAngle = false) }
        QaCurves.drag(s, Vec2(60f, 70f), Vec2(110f, 80f))
        tool.setPointEditing(true)
        Qa19Ui.settle()
        assertEquals("a rectangle's four points", 4, tool.pointCount)
        fun smooth() = tool.docAnchors()!!.map { it.smooth }
        select(s, tool, 0, 1, 2, 3)
        assertEquals("the corners are sharp, the tap smooths them", ShapeStripItems.SMOOTH_LABEL, PillTestKit.sharpName())
        tapCell(s)
        assertEquals("a finger's tap smoothed every selected point", List(4) { true }, smooth())
        assertEquals(ShapeStripItems.SHARP_LABEL, PillTestKit.sharpName())
        tool.setPointSmooth(0, false)
        Qa19Ui.settle()
        assertEquals("a mix", PointLabels110.MIXED_MAKES_SHARP, PillTestKit.sharpName())
        tapCell(s)
        assertEquals("Mixed means Sharp", List(4) { false }, smooth())
        Qa19Ui.assertNoTextOverflow("Shape points")
        assertNoMoreItem("Shape points")
        tool.discard()
        Qa19Ui.settle()
        Smoke.assertQuiet(s.c, "Shape at $width dp, font $scale")
    }

    private fun polyline(h: Qa19Harness, width: Int, scale: Float) {
        val s = open(h, ToolId.POLYLINE, width, scale)
        val tool = drawThree(s)
        select(s, tool, 1)
        assertEquals(PointLabels110.POLYLINE_ALWAYS_SHARP, PillTestKit.sharpName())
        assertEquals("greyed", true, PillTestKit.sharpGreyed())
        // Before the tap: the tap's long-press bubble (ui/common/LongPressLabel.kt, one 12 sp line)
        // cannot hold this name at 360 dp and font scale 2.0; a LeadRequest asks for two lines.
        Qa19Ui.assertNoTextOverflow("Polyline")
        val anchors = tool.anchors
        tapCell(s)
        assertTrue("a finger's tap changes nothing", tool.anchors === anchors)
        assertTrue("the bubble says why", Qa19Ui.tagged(V18Tags.LONG_PRESS_BUBBLE) != null)
        tool.discard()
        Qa19Ui.settle()
        Smoke.assertQuiet(s.c, "Polyline at $width dp, font $scale")
    }

    private companion object {
        const val EPS = 0.5f
    }
}
