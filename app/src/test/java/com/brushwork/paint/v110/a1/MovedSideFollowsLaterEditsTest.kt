package com.brushwork.paint.v110.a1

import android.graphics.Color
import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.v110.a1.A1Kit.identity
import com.brushwork.paint.v110.a1.A1Kit.mirrorX
import com.brushwork.paint.v110.a1.A1Kit.withMaps
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
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
 * v1.10 (R1 (a), the user's answer "Tap side, stays linked"; I1): a side moved on its own stays a
 * side of the same object, so later edits of its points, its colors and its size repeat on it
 * where it now is. The edit after the move is the object's own (its points and fill changed in one
 * update, as the Curve tool's ✓ does); the layer's pixels are read where each side is drawn.
 *
 * The box (60..120, 100..140), filled red, and its mirrored copy (392..452, 100..140). The edit
 * widens the source to the right (to x = 150) and fills it blue: the mirrored copy then also
 * widens, to the left (from x = 362).
 */
@RunWith(RobolectricTestRunner::class)
class MovedSideFollowsLaterEditsTest {
    private val kit = ObjectTestKit()

    @After
    fun tearDown() = kit.close()

    private val blue = 0xFF2060E0.toInt()

    private fun EditorController.tap(x: Float, y: Float) {
        pointerDown(ToolPoint(x, y))
        pointerUp(ToolPoint(x, y))
    }

    /** The mirrored box on the vector layer, with the Transform tool current. */
    private fun mirrored(): EditorController {
        val c = kit.controller()
        c.vectors.addObjects(c.vec, listOf(withMaps(kit.box(60f, 100f, 120f, 140f), listOf(identity(), mirrorX()))), "Add")
        kit.transform(c)
        return c
    }

    /** Side [k] tapped at ([x], [y]), moved 80 px down and applied with ✓ (one step). */
    private fun moveSideDown(c: EditorController, k: Int, x: Float, y: Float) {
        val steps = c.undoManager.undoCount
        c.tap(x, y)
        assertTrue(kit.idleUntil { VectorLift.activeLift(c)?.side == k })
        val tool = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        tool.moveBy(0f, 80f)
        tool.commit()
        assertTrue(kit.idleUntil { !c.vectors.isRendering })
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
    }

    /**
     * The object's own later edit, from another tool: its right edge to x = [right] and a blue
     * fill, in one update. Returns the maps before it (the edit must not touch them).
     */
    private fun widenAndFillBlue(c: EditorController, right: Float = 150f): List<FloatArray> {
        c.selectTool(ToolId.BRUSH)
        kit.idle()
        val content = c.vec.vector!!
        val o = content.byId(1) as VPath
        val maps = ObjectCopies.of(o).maps.map { it.copyOf() }
        val edge = o.subpaths.single().anchors.maxOf { it.x }
        val sub = o.subpaths.single().let { s -> s.copy(anchors = s.anchors.map { if (it.x == edge) it.copy(x = right) else it }) }
        val edited = o.copy(subpaths = listOf(sub), fill = VPaint.Solid(blue))
        c.vectors.update(c.vec, content.replaced(mapOf(1L to listOf(edited))), "Edit")
        assertTrue(kit.idleUntil { !c.vectors.isRendering })
        val now = ObjectCopies.of(c.vec.vector!!.byId(1)!!).maps
        assertEquals("the edit keeps every side", maps.size, now.size)
        for (j in maps.indices) assertTrue("map $j unchanged by the edit", maps[j].contentEquals(now[j]))
        return maps
    }

    private fun pixelAt(px: IntArray, x: Int, y: Int) = px[y * kit.w + x]

    private fun assertBlue(what: String, px: IntArray, x: Int, y: Int) =
        assertEquals("$what ($x, $y)", Integer.toHexString(blue), Integer.toHexString(pixelAt(px, x, y)))

    private fun assertEmpty(what: String, px: IntArray, x: Int, y: Int) =
        assertEquals("$what ($x, $y)", 0, Color.alpha(pixelAt(px, x, y)))

    @Test
    fun aMovedCopyTakesLaterEditsOfPointsColorsAndSize() {
        val c = mirrored()
        moveSideDown(c, 1, 422f, 120f)

        // Points and colors: the moved copy (now 180..220) widens to the left and turns blue there.
        widenAndFillBlue(c)
        val px = kit.pixels(c.vec.bitmap)
        assertBlue("the source, widened", px, 140, 120)
        assertBlue("the source", px, 90, 120)
        assertBlue("the moved copy, widened where it is now", px, 370, 200)
        assertBlue("the moved copy", px, 430, 200)
        assertEmpty("where the copy was", px, 422, 120)
        assertEmpty("below the source", px, 90, 200)

        // Size: all sides lifted and halved; the moved copy halves with them, still a mirror side.
        val tool = kit.transform(c)
        assertTrue(kit.idleUntil { VectorLift.activeLift(c)?.let { it.side == -1 && it.ids == setOf(1L) } == true })
        val o = c.vec.vector!!.byId(1)!!
        val b0 = CopyPick.boundsOf(o, 0)
        val b1 = CopyPick.boundsOf(o, 1)
        tool.setScalePercent(50.0)
        tool.endNumericEdit()
        tool.commit()
        assertTrue(kit.idleUntil { !c.vectors.isRendering })
        val after = c.vec.vector!!.byId(1)!!
        val maps = ObjectCopies.of(after).maps
        assertEquals("still linked", 2, maps.size)
        assertTrue("still a mirror side", CopyEdit.isIsometry(maps[1]))
        val a0 = CopyPick.boundsOf(after, 0)
        val a1 = CopyPick.boundsOf(after, 1)
        assertEquals("the source halved", b0.width() / 2f, a0.width(), 2f)
        assertEquals("the copy as large as the source", a0.width(), a1.width(), 1f)
        assertEquals("the copy's offset halved", (b1.centerY() - b0.centerY()) / 2f, a1.centerY() - a0.centerY(), 1f)
        c.selectTool(ToolId.BRUSH)
        kit.idle()
        val half = kit.pixels(c.vec.bitmap)
        assertBlue("the halved copy", half, a1.centerX().toInt(), a1.centerY().toInt())
    }

    @Test
    fun aMovedSourceTakesLaterEditsAndTheOtherSideFollowsInPlace() {
        val c = mirrored()
        val copyBefore = CopyPick.boundsOf(c.vec.vector!!.byId(1)!!, 1)
        moveSideDown(c, 0, 90f, 120f)
        val copyAfter = CopyPick.boundsOf(c.vec.vector!!.byId(1)!!, 1)
        assertTrue("the copy stays: $copyBefore vs $copyAfter", abs(copyBefore.left - copyAfter.left) <= 0.5f && abs(copyBefore.top - copyAfter.top) <= 0.5f)

        // The source (now 180..220) widens and turns blue; the copy, where it stayed, follows.
        widenAndFillBlue(c)
        val px = kit.pixels(c.vec.bitmap)
        assertBlue("the moved source, widened", px, 140, 200)
        assertBlue("the moved source", px, 90, 200)
        assertEmpty("where the source was", px, 90, 120)
        assertBlue("the copy in place, widened", px, 370, 120)
        assertBlue("the copy in place", px, 430, 120)
        assertEmpty("below the copy", px, 430, 200)
    }
}
