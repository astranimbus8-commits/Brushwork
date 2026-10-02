package com.brushwork.paint.tools.vector.spline

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.GridSettings
import com.brushwork.paint.model.GridType
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.controller
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.drag
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tap
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.6 §3.4 (area B hooks): Curve, Polyline and Path point drags move by multiples of the Length
 * step from where the point was (per axis, after grid snapping), the readout shows the move while
 * the finger is down; with increments off a drag is exactly v1.5 (the point under the finger).
 */
@RunWith(RobolectricTestRunner::class)
class CurveIncrementsRobolectricTest {

    @Test
    fun curvePointDragsStepFromWhereThePointWas() {
        val c = controller()
        val t = c.tool(ToolId.CURVE)
        c.tap(100f, 100f)
        c.tap(300f, 200f)
        // Off (I8): the point follows the finger exactly.
        c.drag(103f to 102f, 115f to 120f, 127f to 141f)
        assertEquals(Vec2(127f, 141f), t.anchors[0].pos)
        t.undoStep()
        assertEquals(Vec2(100f, 100f), t.anchors[0].pos)
        // On (10 px): +24, +39 from the grab → +20, +40 from where the point was.
        c.increments.update { it.copy(enabled = true) }
        c.pointerDown(ToolPoint(103f, 102f))
        c.pointerMove(ToolPoint(115f, 120f))
        c.pointerMove(ToolPoint(127f, 141f))
        assertEquals(Vec2(120f, 140f), t.anchors[0].pos)
        assertEquals("+20, +40 px", c.increments.readout)
        c.pointerUp(ToolPoint(127f, 141f))
        assertNull("the readout goes with the finger", c.increments.readout)
        assertEquals(Vec2(120f, 140f), t.anchors[0].pos)
        // A custom Length step.
        c.increments.update { it.copy(lengthPx = 25f) }
        c.drag(120f to 140f, 150f to 150f, 160f to 128f)
        assertEquals("+40 → +50, −12 → 0", Vec2(170f, 140f), t.anchors[0].pos)
        // A new point dragged right away steps from where it was placed.
        c.increments.update { it.copy(lengthPx = 10f) }
        c.drag(50f to 250f, 60f to 255f, 73f to 266f)
        assertEquals(Vec2(70f, 270f), t.anchors.last().pos)
    }

    @Test
    fun theGridComesBeforeTheIncrement() {
        val c = controller()
        val t = c.tool(ToolId.POLYLINE)
        c.tap(100f, 100f)
        c.tap(300f, 200f)
        c.increments.update { it.copy(enabled = true, lengthPx = 7f) }
        c.updateGrid(GridSettings(enabled = true, snap = true, type = GridType.SQUARE, spacingPx = 25f))
        c.drag(300f to 200f, 320f to 230f, 333f to 241f)
        assertEquals("on the grid, not on 7 px steps", Vec2(325f, 250f), t.anchors[1].pos)
    }

    @Test
    fun pathControlPointDragsStepToo() {
        val c = controller()
        val t = c.tool(ToolId.PATH)
        c.tap(100f, 100f)
        c.tap(300f, 120f)
        c.tap(200f, 250f)
        c.increments.update { it.copy(enabled = true) }
        c.drag(300f to 120f, 310f to 130f, 318f to 96f)
        assertEquals(Vec2(320f, 100f), SplineEditing.pos(t.spline!!.points[1]))
        c.increments.update { it.copy(enabled = false) }
        c.drag(320f to 100f, 330f to 110f, 333f to 107f)
        assertEquals(Vec2(333f, 107f), SplineEditing.pos(t.spline!!.points[1]))
    }
}
