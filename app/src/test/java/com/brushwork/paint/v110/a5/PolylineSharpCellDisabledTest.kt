package com.brushwork.paint.v110.a5

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa19.Qa19Ui
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.testing.PillTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.points.CornerState
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.common.PointLabels110
import com.brushwork.paint.ui.common.V110Tags
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.theme.IbisDims
import com.brushwork.paint.ui.vector.ShapeStripItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.10 (A5, R12, UQ18) on the real editor at 392 dp: with Polyline points selected the
 * Sharp / Smooth cell shows greyed in the same place as Curve's (the end of row 2, under
 * "Select several"), named [PointLabels110.POLYLINE_ALWAYS_SHARP] and disabled for
 * accessibility. A finger's tap (or its click action) changes nothing (no point, no step, no new
 * point on the canvas under it) and shows its name in the long-press bubble. The same for the
 * ends of an open Path ([PointLabels.ENDS_SHARP]); an inner Path point gives the working cell.
 *
 * One test: Compose's frame clock only serves the first test of a Robolectric sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q392, instrumentedPackages = ["com.brushwork.paint.v110.a5.polylinesharpsandbox"])
class PolylineSharpCellDisabledTest {

    private val three = listOf(Vec2(120f, 150f), Vec2(200f, 90f), Vec2(280f, 160f))

    /** Picks [id], adds [three] and selects the middle point; the tool. */
    private fun drawThree(s: ChromeScreen, id: ToolId, select: Int = 1): CurveTool {
        s.c.selectTool(id)
        Qa19Ui.settle()
        val tool = s.c.currentTool as CurveTool
        for (p in three) assertTrue("$id: a point at $p", tool.addAnchor(p))
        tool.deselect()
        tool.selectPoints(PointSelection.of(three.size, select))
        Qa19Ui.settle()
        return tool
    }

    private fun cell(s: ChromeScreen, what: String): Rect =
        requireNotNull(s.tagged(V110Tags.PILL_SHARP)) { "$what: the Sharp / Smooth cell; shown: ${Qa19Ui.shown().take(80)}" }

    /** Whether the long-press bubble shows [label] (its text, not a description). */
    private fun bubbleSays(label: String): Boolean {
        val bubble = Qa19Ui.tagged(V18Tags.LONG_PRESS_BUBBLE) ?: return false
        fun texts(n: SemanticsNode): List<String> =
            n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + n.children.flatMap(::texts)
        return label in texts(bubble)
    }

    private fun assertSamePlace(what: String, expected: Rect, actual: Rect) {
        for ((n, e, a) in listOf(Triple("left", expected.left, actual.left), Triple("top", expected.top, actual.top), Triple("right", expected.right, actual.right), Triple("bottom", expected.bottom, actual.bottom))) {
            assertEquals("$what: $n ($expected, $actual)", e, a, EPS)
        }
    }

    @Test
    fun polylineShowsTheCellGreyedInCurvesPlace() = Qa19Ui.uiTest { h ->
        h.section("Polyline: greyed in Curve's place, a tap changes nothing and says why") {
            val s = h.editor()
            // Curve first: the working cell, where Polyline's must be.
            val curve = drawThree(s, ToolId.CURVE)
            val curveCell = cell(s, "Curve")
            assertEquals("Curve: smooth points, the tap makes them sharp", ShapeStripItems.SHARP_LABEL, PillTestKit.sharpName())
            assertEquals(false, PillTestKit.sharpGreyed())
            curve.discard()
            Qa19Ui.settle()

            val tool = drawThree(s, ToolId.POLYLINE)
            assertEquals(CornerState.ALWAYS_SHARP, tool.pointCorners?.corner)
            val r = cell(s, "Polyline")
            assertSamePlace("Polyline's cell in Curve's place", curveCell, r)
            val several = requireNotNull(s.tagged(V18Tags.PILL_SEVERAL)) { "Select several" }
            assertEquals("under Select several", several.center.x, r.center.x, EPS)
            assertEquals("in row 2", several.center.y + ROW, r.center.y, EPS)
            assertEquals(PointLabels110.POLYLINE_ALWAYS_SHARP, PillTestKit.sharpName())
            assertEquals("greyed: disabled for accessibility", true, PillTestKit.sharpGreyed())

            val anchors = tool.anchors
            val selection = tool.pointSelection
            Finger.tapAt(s, r.center.x, r.center.y)
            assertTrue("a finger's tap changes no point (and adds none under it)", tool.anchors === anchors)
            assertEquals("nor the selection", selection, tool.pointSelection)
            assertTrue("the bubble says why", bubbleSays(PointLabels110.POLYLINE_ALWAYS_SHARP))
            PillTestKit.tapSharp()
            assertTrue("its click action changes nothing either", tool.anchors === anchors)
            assertEquals(PointLabels110.POLYLINE_ALWAYS_SHARP, PillTestKit.sharpName())
            // No selected point: no cell.
            tool.selectPoints(PointSelection.none(three.size))
            Qa19Ui.settle()
            assertTrue("nothing selected: no cell", s.tagged(V110Tags.PILL_SHARP) == null)
            tool.discard()
            Qa19Ui.settle()
            Smoke.assertQuiet(s.c, "Polyline")
        }

        h.section("Path: only an end selected, greyed; an inner point, the working cell") {
            val s = h.editor()
            val tool = drawThree(s, ToolId.PATH, select = 0)
            assertEquals(CornerState.ENDS_ONLY, tool.pointCorners?.corner)
            val r = cell(s, "Path's end")
            assertEquals(PointLabels.ENDS_SHARP, PillTestKit.sharpName())
            assertEquals(true, PillTestKit.sharpGreyed())
            val spline = tool.spline
            Finger.tapAt(s, r.center.x, r.center.y)
            assertTrue("a tap changes nothing", tool.spline === spline)
            assertTrue("the bubble says why", bubbleSays(PointLabels.ENDS_SHARP))
            tool.selectPoints(PointSelection.of(three.size, 1))
            Qa19Ui.settle()
            assertSamePlace("the inner point's cell in the same place", r, cell(s, "Path's inner point"))
            assertEquals(ShapeStripItems.SHARP_LABEL, PillTestKit.sharpName())
            assertEquals(false, PillTestKit.sharpGreyed())
            Finger.tapAt(s, r.center.x, r.center.y)
            assertTrue("the inner point became sharp", tool.spline!!.points[1].sharp)
            assertFalse("the ends are as they were", tool.spline!!.points[0].sharp)
            assertEquals("the face now offers Smooth", ShapeStripItems.SMOOTH_LABEL, PillTestKit.sharpName())
            tool.discard()
            Qa19Ui.settle()
            Smoke.assertQuiet(s.c, "Path")
        }
    }

    private companion object {
        const val EPS = 0.5f

        /** Row 2's centre under row 1's: the 32 dp row and the 4 dp gap. */
        val ROW = IbisDims.PillHeight.value + IbisDims.PillRowGap.value
    }
}
