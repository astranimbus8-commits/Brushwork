package com.brushwork.paint.qa110

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.qa16.QaCurves
import com.brushwork.paint.qa19.Qa19Harness
import com.brushwork.paint.qa19.Qa19Ui
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.tools.vector.CornerStyle
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * v1.10 final QA of R8 ("fix snapping not working when selecting more than one point") on the
 * real editor at 392 and 360 dp and font scale 1.3 and 2.0, every drag by a finger with
 * "Snap to objects" on:
 * - Shape's Points mode, two corners of a rectangle selected: a drag of one of them brings the
 *   other along and the group snaps (the bottom right lands on the canvas edge, both move the
 *   same); the gizmo's right handle snaps the right edge it drags;
 * - Curve, Polyline and Path, two of three points selected: a drag of one snaps the group to the
 *   canvas edge; the unselected point stays.
 * No text overflows its box.
 *
 * One test: Compose's frame clock only serves the first test of a Robolectric sandbox. The
 * 360 dp phone comes from `RuntimeEnvironment.setQualifiers` (as `MoreRowOffsetsTest`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q392, instrumentedPackages = ["com.brushwork.paint.qa110.groupsnapsandbox"])
class Qa110GroupSnapUiTest {

    @Test
    fun groupsSnapUnderAFingerAt392And360() = Qa19Ui.uiTest { h ->
        try {
            for (width in listOf(392, 360)) {
                if (width == 360) RuntimeEnvironment.setQualifiers(Qa19Ui.Q360)
                for (scale in Qa19Ui.FONT_SCALES) {
                    val at = "$width dp, font scale $scale"
                    h.section("$at: Shape points, a group move") { shape(h, width, scale, handle = false) }
                    h.section("$at: Shape points, the gizmo's right handle") { shape(h, width, scale, handle = true) }
                    for (id in listOf(ToolId.CURVE, ToolId.POLYLINE, ToolId.PATH)) h.section("$at: $id, a group move") { curve(h, id, width, scale) }
                }
            }
        } finally {
            RuntimeEnvironment.setQualifiers(Qa19Ui.Q392)
        }
    }

    private fun open(h: Qa19Harness, w: Int, ht: Int, id: ToolId, width: Int, scale: Float): ChromeScreen {
        val s = h.editor(Smoke.document(w, ht, layers = 2, whiteBottom = true), fontScale = scale)
        assertEquals("the phone", width.toFloat(), s.widthDp, 1f)
        s.c.snapping.enabled = false
        s.c.selectTool(id)
        Qa19Ui.settle()
        return s
    }

    private fun assertNear(what: String, expected: Vec2, actual: Vec2, eps: Float = 0.05f) =
        assertTrue("$what: $expected vs $actual", abs(expected.x - actual.x) <= eps && abs(expected.y - actual.y) <= eps)

    /**
     * A 200 x 200 canvas (targets 0, 100, 200), the rectangle 60..140 x 70..130 drawn with
     * snapping off, its top left and bottom right points selected, snapping on. A move by 57 px
     * puts the bottom right 3 px from the canvas edge (x 200): the group lands there, both points
     * 60 px right. The gizmo's right handle (140, 100) dragged to x 197: the right edge lands on
     * 200, the left one on 0 (x 2.5 about the centre 100).
     */
    private fun shape(h: Qa19Harness, width: Int, scale: Float, handle: Boolean) {
        val s = open(h, 200, 200, ToolId.SHAPE, width, scale)
        val tool = s.c.currentTool as ShapeTool
        tool.update { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE, corner = CornerStyle.SHARP, keepProportions = false, fromCenter = false, snapAngle = false) }
        QaCurves.drag(s, TL, BR - TL)
        tool.setPointEditing(true)
        Qa19Ui.settle()
        val anchors = tool.docAnchors()!!
        assertEquals("a rectangle's four points", 4, anchors.size)
        val tl = anchors.indexOfFirst { it.pos.distanceTo(TL) < 0.5f }
        val br = anchors.indexOfFirst { it.pos.distanceTo(BR) < 0.5f }
        assertTrue("the corners at $TL and $BR: ${anchors.map { it.pos }}", tl >= 0 && br >= 0)
        val start = anchors.map { it.pos }
        tool.selectPoints(PointSelection.of(4, tl, br))
        s.c.snapping.enabled = true
        Qa19Ui.settle()
        if (handle) {
            QaCurves.drag(s, Vec2(140f, 100f), Vec2(57f, 0f))
            assertNear("the dragged right edge snapped to the canvas edge", Vec2(200f, BR.y), tool.docAnchors()!![br].pos)
            assertNear("the left edge followed the factor", Vec2(0f, TL.y), tool.docAnchors()!![tl].pos)
        } else {
            QaCurves.drag(s, BR, Vec2(57f, 0f))
            assertNear("the bottom right snapped to the canvas edge", Vec2(200f, BR.y), tool.docAnchors()!![br].pos)
            assertNear("the top left moved with it", Vec2(TL.x + 60f, TL.y), tool.docAnchors()!![tl].pos)
        }
        for (i in 0 until 4) if (i != tl && i != br) assertNear("unselected point $i stays", start[i], tool.docAnchors()!![i].pos)
        Qa19Ui.assertNoTextOverflow("Shape points")
        tool.discard()
        Qa19Ui.settle()
        Smoke.assertQuiet(s.c, "Shape at $width dp, font $scale")
    }

    /**
     * A 400 x 300 canvas (targets x 0, 200, 400), points 0 and 2 of three selected, snapping on.
     * Point 2 dragged 97 px right ends 3 px from the canvas edge: the group lands there (both
     * 100 px right); point 1 stays.
     */
    private fun curve(h: Qa19Harness, id: ToolId, width: Int, scale: Float) {
        val s = open(h, 400, 300, id, width, scale)
        val tool = s.c.currentTool as CurveTool
        for (p in CURVE) assertTrue("$id: a point at $p", tool.addAnchor(p))
        tool.deselect()
        tool.selectPoints(PointSelection.of(3, 0, 2))
        s.c.snapping.enabled = true
        Qa19Ui.settle()
        QaCurves.drag(s, CURVE[2], Vec2(97f, 0f))
        assertNear("$id: point 3 snapped to the canvas edge", Vec2(400f, CURVE[2].y), tool.pointAt(2))
        assertNear("$id: point 1 moved with it", Vec2(CURVE[0].x + 100f, CURVE[0].y), tool.pointAt(0))
        assertNear("$id: point 2 stays", CURVE[1], tool.pointAt(1))
        Qa19Ui.assertNoTextOverflow("$id")
        tool.discard()
        Qa19Ui.settle()
        Smoke.assertQuiet(s.c, "$id at $width dp, font $scale")
    }

    private companion object {
        val TL = Vec2(60f, 70f)
        val BR = Vec2(140f, 130f)
        val CURVE = listOf(Vec2(40f, 200f), Vec2(250f, 260f), Vec2(300f, 220f))
    }
}
