package com.brushwork.paint.v110.a1

import android.graphics.RectF
import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.ui.common.MirrorLabels110
import com.brushwork.paint.v110.a1.A1Kit.identity
import com.brushwork.paint.v110.a1.A1Kit.mirrorX
import com.brushwork.paint.v110.a1.A1Kit.withMaps
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VectorOps
import com.brushwork.paint.vector.copies.CopyPick
import com.brushwork.paint.vector.lift.VectorLift
import com.brushwork.paint.vector.select.ObjectTestKit
import com.brushwork.paint.vector.select.vec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs

/**
 * v1.10 (R1 (a)): with one side picked, the pill's trash ("Delete one side") deletes that side
 * only, as ONE undo step `MirrorLabels110.DELETE_SIDE`: k ≥ 1 drops its map (a Mirror with 2 maps
 * becomes a plain object where the source was); k = 0 rebases the object on its first copy (the
 * other sides stay where they are drawn). The object stays selected; undo brings the side back.
 */
@RunWith(RobolectricTestRunner::class)
class DeleteOneSideTest {
    private val kit = ObjectTestKit()

    @After
    fun tearDown() = kit.close()

    private fun EditorController.tap(x: Float, y: Float) {
        pointerDown(ToolPoint(x, y))
        pointerUp(ToolPoint(x, y))
    }

    private fun near(a: RectF, b: RectF, tol: Float = 0.5f) =
        abs(a.left - b.left) <= tol && abs(a.top - b.top) <= tol && abs(a.right - b.right) <= tol && abs(a.bottom - b.bottom) <= tol

    private fun sides(o: VObject): List<RectF> {
        val n = ObjectCopies.of(o).maps.size.coerceAtLeast(1)
        return (0 until n).map { VectorOps.bounds(CopyPick.side(o, it) ?: o) }
    }

    private fun setUp(maps: List<FloatArray>): Pair<EditorController, TransformTool> {
        val c = kit.controller()
        c.vectors.addObjects(c.vec, listOf(withMaps(kit.box(60f, 100f, 120f, 140f), maps)), "Add")
        return c to kit.transform(c)
    }

    /** Picks the side under ([x], [y]) and deletes it with the pill's trash; returns the object after. */
    private fun deleteSide(c: EditorController, tool: TransformTool, x: Float, y: Float, k: Int): VObject {
        c.tap(x, y)
        assertTrue(kit.idleUntil { VectorLift.activeLift(c)?.side == k })
        val trash = tool.objectDeletion
        assertNotNull("the trash cell while a side is picked", trash)
        assertEquals(MirrorLabels110.DELETE_SIDE, trash!!.deleteLabel)
        val steps = c.undoManager.undoCount
        trash.delete()
        assertTrue(kit.idleUntil { !c.vectors.isRendering })
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertEquals(MirrorLabels110.DELETE_SIDE, c.undoManager.undoLabel)
        assertNull("no side picked any more", tool.objectDeletion)
        assertEquals("one object", 1, c.vec.vector!!.objects.size)
        assertEquals("it stays selected", setOf(1L), c.vectors.selectedIds)
        return c.vec.vector!!.byId(1)!!
    }

    @Test
    fun aMirrorWithTwoMapsBecomesAPlainObject() {
        val (c, tool) = setUp(listOf(identity(), mirrorX()))
        val before = c.vec.vector!!.byId(1)!!
        val source = VectorOps.bounds(ObjectCopies.source(before))
        val o = deleteSide(c, tool, 422f, 120f, 1)
        assertTrue("plain", ObjectCopies.of(o).isEmpty)
        assertTrue("where the source was: ${VectorOps.bounds(o)}", near(source, VectorOps.bounds(o)))

        // Undo brings the side back; the tap cycle starts again on the restored object.
        c.undo()
        assertTrue(kit.idleUntil { !c.vectors.isRendering })
        assertEquals(2, ObjectCopies.of(c.vec.vector!!.byId(1)!!).maps.size)

        // The source of a Mirror (k = 0): the copy is what is left, plain, where it was drawn.
        val copy = CopyPick.boundsOf(c.vec.vector!!.byId(1)!!, 1)
        val rest = deleteSide(c, tool, 90f, 120f, 0)
        assertTrue("plain", ObjectCopies.of(rest).isEmpty)
        assertTrue("where the copy was: ${VectorOps.bounds(rest)}", near(copy, VectorOps.bounds(rest)))
    }

    @Test
    fun deletingTheSourceRebasesOnTheFirstCopy() {
        val maps = A1Kit.kinds().first { it.first == "Kaleidoscope" }.second
        val (c, tool) = setUp(maps)
        val before = sides(c.vec.vector!!.byId(1)!!)
        val o = deleteSide(c, tool, 90f, 120f, 0)
        val after = sides(o)
        assertEquals("one side fewer", maps.size - 1, after.size)
        assertTrue("the first copy is the source now", near(before[1], after[0]))
        // Every side that stayed is drawn where it was.
        for (b in before.drop(1)) assertTrue("$b stays (now $after)", after.any { near(it, b) })

        // A copy of the rest (k ≥ 1): its map goes, the others stay.
        val third = deleteSide(c, tool, after[2].centerX(), after[2].centerY(), 2)
        val left = sides(third)
        assertEquals(maps.size - 2, left.size)
        assertTrue(near(after[0], left[0]))
        assertTrue(near(after[1], left[1]))
    }
}
