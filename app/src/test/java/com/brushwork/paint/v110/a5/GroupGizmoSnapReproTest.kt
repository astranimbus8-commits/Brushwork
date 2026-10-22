package com.brushwork.paint.v110.a5

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.v110.a5.GroupSnapFixtures.Companion.CURVE_KINDS
import com.brushwork.paint.v110.a5.GroupSnapFixtures.Companion.CURVE_MOVE
import com.brushwork.paint.v110.a5.GroupSnapFixtures.Companion.CURVE_POINTS
import com.brushwork.paint.v110.a5.GroupSnapFixtures.Companion.SHAPE_BR
import com.brushwork.paint.v110.a5.GroupSnapFixtures.Companion.SHAPE_MOVE
import com.brushwork.paint.v110.a5.GroupSnapFixtures.Companion.SHAPE_TL
import com.brushwork.paint.v110.a5.GroupSnapFixtures.Companion.assertVec
import com.brushwork.paint.v110.a5.GroupSnapFixtures.Companion.dragBy
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 (A5, R8 cause A): a drag inside the group gizmo (its MOVE part, not on a point) snaps the
 * moving group like any other move. In 1.9 the gizmo never snapped: the group followed the finger
 * past every guide (the grid was ignored too).
 */
@RunWith(RobolectricTestRunner::class)
class GroupGizmoSnapReproTest {
    private val fx = GroupSnapFixtures()

    @After
    fun tearDown() = fx.release()

    @Test
    fun aShapeGizmoMoveSnapsTheGroupsNearestPointToTheCanvasEdge() {
        val (c, tool) = fx.shapeGroup()
        val tl = fx.shapeIndex(tool, SHAPE_TL)
        val br = fx.shapeIndex(tool, SHAPE_BR)
        // The box's centre: inside the gizmo, 30 px from its side handles and 50 px from every point.
        c.dragBy(Vec2(100f, 100f), SHAPE_MOVE)
        assertVec("bottom right on the canvas edge", Vec2(200f, 130f), fx.shapePoint(tool, br))
        assertVec("top left moved with it", Vec2(120f, 70f), fx.shapePoint(tool, tl))
    }

    @Test
    fun aCurveGizmoMoveSnapsTheGroupsNearestPointToTheCanvasEdge() {
        for (id in CURVE_KINDS) {
            val (c, tool) = fx.curveGroup(id)
            // Inside the box (40..300 x 200..220, grown to 56 px tall): 59 px or more from every
            // handle and 80 px from every point.
            c.dragBy(Vec2(120f, 214f), CURVE_MOVE)
            assertVec("$id: point 2 on the canvas edge", Vec2(400f, 220f), tool.pointAt(2))
            assertVec("$id: point 0 moved with it", CURVE_POINTS[0] + Vec2(100f, 0f), tool.pointAt(0))
            assertVec("$id: point 1 stays", CURVE_POINTS[1], tool.pointAt(1))
            tool.discard()
        }
    }
}
