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
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 (A5, R8 cause B): a drag on one point of a selected group snaps the GROUP: a selected
 * point other than the one under the finger that comes within reach of a target lands on it, and
 * the whole group moves by the same correction. In 1.9 only the grabbed point was snapped, so the
 * others passed every guide.
 */
@Ignore("v110-A5 repro: fails on 1.9")
@RunWith(RobolectricTestRunner::class)
class GroupPointSnapOtherPointTest {
    private val fx = GroupSnapFixtures()

    @After
    fun tearDown() = fx.release()

    @Test
    fun aShapeGroupDraggedByOnePointSnapsAnotherSelectedPoint() {
        val (c, tool) = fx.shapeGroup()
        val tl = fx.shapeIndex(tool, SHAPE_TL)
        val br = fx.shapeIndex(tool, SHAPE_BR)
        // Grabbed by the top left point (nothing near it); the bottom right one reaches the edge.
        c.dragBy(SHAPE_TL, SHAPE_MOVE)
        assertVec("bottom right on the canvas edge", Vec2(200f, 130f), fx.shapePoint(tool, br))
        assertVec("the grabbed point moved with it", Vec2(120f, 70f), fx.shapePoint(tool, tl))
    }

    @Test
    fun aCurveGroupDraggedByOnePointSnapsAnotherSelectedPoint() {
        for (id in CURVE_KINDS) {
            val (c, tool) = fx.curveGroup(id)
            // Grabbed by point 0 (nothing near it); point 2 reaches the canvas edge.
            c.dragBy(CURVE_POINTS[0], CURVE_MOVE)
            assertVec("$id: point 2 on the canvas edge", Vec2(400f, 220f), tool.pointAt(2))
            assertVec("$id: the grabbed point moved with it", CURVE_POINTS[0] + Vec2(100f, 0f), tool.pointAt(0))
            assertVec("$id: point 1 stays", CURVE_POINTS[1], tool.pointAt(1))
            tool.discard()
        }
    }
}
