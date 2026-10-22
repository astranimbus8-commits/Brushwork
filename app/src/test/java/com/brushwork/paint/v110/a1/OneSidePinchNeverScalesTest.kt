package com.brushwork.paint.v110.a1

import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.v110.a1.A1Kit.identity
import com.brushwork.paint.v110.a1.A1Kit.mirrorX
import com.brushwork.paint.v110.a1.A1Kit.withMaps
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.VectorOps
import com.brushwork.paint.vector.copies.CopyEdit
import com.brushwork.paint.vector.copies.CopyPick
import com.brushwork.paint.vector.lift.VectorLift
import com.brushwork.paint.vector.select.ObjectTestKit
import com.brushwork.paint.vector.select.vec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs

/**
 * v1.10 (R1 (a), J18): while one side is picked, a two-finger gesture moves and turns it only: a
 * 15 % spread with a 20° turn turns the side, keeps its scale at 1.0 and keeps it linked (the
 * committed map is an isometry). After all sides are picked, the same spread scales again.
 */
@RunWith(RobolectricTestRunner::class)
class OneSidePinchNeverScalesTest {
    private val kit = ObjectTestKit()

    @After
    fun tearDown() = kit.close()

    private fun EditorController.tap(x: Float, y: Float) {
        pointerDown(ToolPoint(x, y))
        pointerUp(ToolPoint(x, y))
    }

    @Test
    fun aTurningSpreadTurnsTheSideAndNeverResizesIt() {
        val c = kit.controller()
        c.vectors.addObjects(c.vec, listOf(withMaps(kit.box(60f, 100f, 120f, 140f), listOf(identity(), mirrorX()))), "Add")
        val tool = kit.transform(c)
        val start = c.vec.vector!!.byId(1)!!
        val src = VectorOps.bounds(CopyPick.side(start, 0)!!)
        c.tap(422f, 120f)
        assertTrue(kit.idleUntil { VectorLift.activeLift(c)?.side == 1 })

        assertTrue(tool.onTwoFingerStart(Vec2(422f, 120f), Vec2(400f, 120f), Vec2(444f, 120f)))
        tool.onTwoFingerGesture(Vec2.ZERO, 1.15f, 20f)
        val st = tool.transformState!!
        assertEquals("no resize", 1f, st.sx, 1e-4f)
        assertEquals("no resize", 1f, st.sy, 1e-4f)
        assertEquals("the turn", 20f, abs(st.rotationDeg), 1e-3f)
        tool.onTwoFingerEnd(cancelled = false)
        tool.commit()
        assertTrue(kit.idleUntil { !c.vectors.isRendering })

        val o = c.vec.vector!!.byId(1)!!
        assertEquals("one object", 1, c.vec.vector!!.objects.size)
        val maps = ObjectCopies.of(o).maps
        assertEquals("still linked", 2, maps.size)
        assertTrue("an isometry", CopyEdit.isIsometry(maps[1]))
        val s = VectorOps.bounds(CopyPick.side(o, 0)!!)
        assertTrue("the source stays: $src vs $s", abs(s.left - src.left) < 0.01f && abs(s.right - src.right) < 0.01f)
        // The side turned by 20° at its size: its box is the turned box's, not a larger one.
        val turned = VectorOps.bounds(VectorOps.transformed(CopyPick.side(start, 1)!!, A1Kit.turnAbout(422f, 120f, 20.0)))
        val now = VectorOps.bounds(CopyPick.side(o, 1)!!)
        assertEquals("turned width", turned.width(), now.width(), 0.5f)
        assertEquals("turned height", turned.height(), now.height(), 0.5f)

        // The pinch started a new cycle: the side, then all sides at the same spot. A spread
        // scales them as in v1.9.
        c.tap(422f, 120f)
        c.tap(422f, 120f)
        assertTrue(kit.idleUntil { VectorLift.activeLift(c)?.side == -1 })
        val box = VectorLift.activeLift(c)!!.sourceRect
        assertTrue(tool.onTwoFingerStart(Vec2(box.exactCenterX(), box.exactCenterY()), Vec2(box.exactCenterX() - 40f, box.exactCenterY()), Vec2(box.exactCenterX() + 40f, box.exactCenterY())))
        tool.onTwoFingerGesture(Vec2.ZERO, 1.15f, 0f)
        assertEquals("all sides scale", 1.15f, tool.transformState!!.sx, 1e-3f)
        tool.onTwoFingerEnd(cancelled = true)
        tool.discard()
    }
}
