package com.brushwork.paint.v110.a5

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.v110.a5.GroupSnapFixtures.Companion.CURVE_KINDS
import com.brushwork.paint.v110.a5.GroupSnapFixtures.Companion.CURVE_POINTS
import com.brushwork.paint.v110.a5.GroupSnapFixtures.Companion.SHAPE_BR
import com.brushwork.paint.v110.a5.GroupSnapFixtures.Companion.SHAPE_TL
import com.brushwork.paint.v110.a5.GroupSnapFixtures.Companion.assertVec
import com.brushwork.paint.v110.a5.GroupSnapFixtures.Companion.drag
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 (A5, R8): a gizmo scale handle snaps the edge it drags (design: "Scale handles snap the
 * dragged edge only"): the factor about the box centre becomes the one that puts that edge of the
 * selected points' bounds on the target. The other edges follow the factor.
 */
@RunWith(RobolectricTestRunner::class)
class GroupScaleEdgeSnapTest {
    private val fx = GroupSnapFixtures()

    @After
    fun tearDown() = fx.release()

    @Test
    fun theShapeGizmosRightHandleSnapsTheRightEdge() {
        val (c, tool) = fx.shapeGroup()
        val tl = fx.shapeIndex(tool, SHAPE_TL)
        val br = fx.shapeIndex(tool, SHAPE_BR)
        // The box 60..140 x 70..130 (pivot 100, 100); its E handle is at (140, 100). Dragged to
        // x 197, the right edge is 3 px from the canvas edge: it lands on 200, so x scales by
        // 100 / 40 = 2.5 and the left edge goes to 100 - 40 x 2.5 = 0.
        c.drag(Vec2(140f, 100f), Vec2(150f, 100f), Vec2(170f, 100f), Vec2(197f, 100f))
        assertVec("bottom right", Vec2(200f, 130f), fx.shapePoint(tool, br))
        assertVec("top left", Vec2(0f, 70f), fx.shapePoint(tool, tl))
    }

    @Test
    fun theCurveGizmosRightHandleSnapsTheRightEdge() {
        for (id in CURVE_KINDS) {
            val (c, tool) = fx.curveGroup(id)
            // Points 0 (40, 200) and 2 (300, 220): the box 40..300, pivot x 170; its E handle is at
            // (300, 210). Dragged to x 397, the right edge snaps to 400: x scales by 230 / 130.
            c.drag(Vec2(300f, 210f), Vec2(310f, 210f), Vec2(350f, 210f), Vec2(397f, 210f))
            assertVec("$id point 2", Vec2(400f, 220f), tool.pointAt(2))
            assertVec("$id point 0", Vec2(-60f, 200f), tool.pointAt(0))
            assertVec("$id point 1 stays", CURVE_POINTS[1], tool.pointAt(1))
            tool.discard()
        }
    }

    @Test
    fun aCurveGizmoCornerKeepingProportionsLetsTheSnappedEdgeDecideBothAxes() {
        val (c, tool) = fx.curveGroup(ToolId.PATH)
        // The box 40..300 x 200..220 is grown to 56 px high about its centre (170, 210): its NE
        // corner is at (300, 182). Moving it along the line from the centre by k = 227 / 130 puts
        // the right edge at 397: it lands on 400, so both axes scale by 230 / 130.
        val k = 227f / 130f
        val to = Vec2(170f + 130f * k, 210f - 28f * k)
        c.drag(Vec2(300f, 182f), Vec2(310f, 180f), Vec2(350f, 172f), to)
        val s = 230f / 130f
        assertVec("point 2", Vec2(400f, 210f + 10f * s), tool.pointAt(2), eps = 0.05f)
        assertVec("point 0", Vec2(170f - 130f * s, 210f - 10f * s), tool.pointAt(0), eps = 0.05f)
        tool.discard()
    }
}
