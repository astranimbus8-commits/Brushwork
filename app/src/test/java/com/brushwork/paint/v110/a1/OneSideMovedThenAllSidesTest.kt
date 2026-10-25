package com.brushwork.paint.v110.a1

import android.graphics.RectF
import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.v110.a1.A1Kit.identity
import com.brushwork.paint.v110.a1.A1Kit.mirrorX
import com.brushwork.paint.v110.a1.A1Kit.withMaps
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.VectorOps
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
 * v1.10 (R1 (a), I2): a picked side is lifted alone (the box hugs that copy) and moves while the
 * other side stays. A tap on the moved side applies its move as one step "Transform objects" and
 * picks all sides; one more drag moves all of them together. The view is the identity.
 *
 * The box (60..120, 100..140) and its mirrored copy (392..452, 100..140).
 */
@RunWith(RobolectricTestRunner::class)
class OneSideMovedThenAllSidesTest {
    private val kit = ObjectTestKit()

    @After
    fun tearDown() = kit.close()

    private fun EditorController.tap(x: Float, y: Float) {
        pointerDown(ToolPoint(x, y))
        pointerUp(ToolPoint(x, y))
    }

    /** [box] is [was] moved by ([dx], [dy]) (within 1 px). */
    private fun near(what: String, was: RectF, dx: Float, dy: Float, box: RectF, tol: Float = 1f) =
        assertTrue("$what: $was by ($dx, $dy) vs $box", abs(box.left - was.left - dx) <= tol && abs(box.top - was.top - dy) <= tol && abs(box.right - was.right - dx) <= tol && abs(box.bottom - was.bottom - dy) <= tol)

    @Test
    fun aMovedSideIsAppliedByATapThatPicksAllSides() {
        val c = kit.controller()
        c.vectors.addObjects(c.vec, listOf(withMaps(kit.box(60f, 100f, 120f, 140f), listOf(identity(), mirrorX()))), "Add")
        val tool = kit.transform(c)
        val steps = c.undoManager.undoCount
        val start = c.vec.vector!!.byId(1)!!
        val src = VectorOps.bounds(CopyPick.side(start, 0)!!)
        val copy = VectorOps.bounds(CopyPick.side(start, 1)!!)

        // The first tap on the copy: that side, lifted alone, its box on the copy.
        c.tap(422f, 120f)
        assertTrue(kit.idleUntil { VectorLift.activeLift(c)?.side == 1 })
        val lift = VectorLift.activeLift(c)!!
        assertEquals(setOf(1L), lift.ids)
        assertTrue("the box hugs the copy: ${lift.sourceRect}", lift.sourceRect.left >= 380 && lift.sourceRect.right <= 466)

        // Moved 40 px down, then tapped where it is now: one step, and all sides picked.
        tool.moveBy(0f, 40f)
        c.tap(422f, 160f)
        assertTrue(kit.idleUntil { VectorLift.activeLift(c)?.let { it.side == -1 && it.ids == setOf(1L) } == true })
        assertEquals("one step for the side's move", steps + 1, c.undoManager.undoCount)
        assertEquals(TransformTool.TRANSFORM_OBJECTS_LABEL, c.undoManager.undoLabel)
        var o = c.vec.vector!!.byId(1)!!
        assertEquals("still linked", 2, ObjectCopies.of(o).maps.size)
        near("the source stays", src, 0f, 0f, VectorOps.bounds(CopyPick.side(o, 0)!!))
        near("the copy moved down", copy, 0f, 40f, VectorOps.bounds(CopyPick.side(o, 1)!!))
        assertTrue("the box holds both sides: ${VectorLift.activeLift(c)!!.sourceRect}", VectorLift.activeLift(c)!!.sourceRect.let { it.left <= 62 && it.right >= 450 })

        // One more drag moves every side.
        tool.moveBy(10f, 0f)
        tool.commit()
        assertTrue(kit.idleUntil { !c.vectors.isRendering })
        assertEquals(steps + 2, c.undoManager.undoCount)
        o = c.vec.vector!!.byId(1)!!
        assertEquals(2, ObjectCopies.of(o).maps.size)
        near("the source moved", src, 10f, 0f, VectorOps.bounds(CopyPick.side(o, 0)!!))
        near("the copy moved with it", copy, 10f, 40f, VectorOps.bounds(CopyPick.side(o, 1)!!))
    }
}
