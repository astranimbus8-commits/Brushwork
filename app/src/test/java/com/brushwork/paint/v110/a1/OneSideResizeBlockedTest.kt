package com.brushwork.paint.v110.a1

import android.graphics.Canvas
import android.graphics.Color
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.tools.transform.TransformTool.Mode
import com.brushwork.paint.ui.common.MirrorLabels110
import com.brushwork.paint.v110.a1.A1Kit.identity
import com.brushwork.paint.v110.a1.A1Kit.mirrorX
import com.brushwork.paint.v110.a1.A1Kit.withMaps
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.VectorOps
import com.brushwork.paint.vector.copies.CopyEdit
import com.brushwork.paint.vector.copies.CopyPick
import com.brushwork.paint.vector.lift.CopyLift
import com.brushwork.paint.vector.lift.VectorLift
import com.brushwork.paint.vector.select.ObjectTestKit
import com.brushwork.paint.vector.select.vec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs

/**
 * v1.10 (R1 (a), LA4: the user's answer "Block resizing"): with one side picked, no scale handle
 * is drawn or hit (a drag on a corner moves the side), the Scale row is hidden, Distort and Free
 * deform are refused with `MirrorLabels110.SIDE_RESIZE_BLOCKED` and so are typed sizes and Fit to
 * canvas, the toast shows once per pick (also for a drag at the box's corner); a drifting
 * transform is snapped to an isometry before the commit. After
 * the same-spot second tap (all sides) every handle and mode works as in v1.9.
 */
@RunWith(RobolectricTestRunner::class)
class OneSideResizeBlockedTest {
    private val kit = ObjectTestKit()

    @After
    fun tearDown() = kit.close()

    private fun EditorController.tap(x: Float, y: Float) {
        pointerDown(ToolPoint(x, y))
        pointerUp(ToolPoint(x, y))
    }

    private fun EditorController.drag(from: Vec2, dx: Float, dy: Float) {
        pointerDown(ToolPoint(from.x, from.y))
        for (i in 1..4) pointerMove(ToolPoint(from.x + dx * i / 4f, from.y + dy * i / 4f))
        pointerUp(ToolPoint(from.x + dx, from.y + dy))
    }

    private fun mirrored(): Pair<EditorController, TransformTool> {
        val c = kit.controller()
        c.vectors.addObjects(c.vec, listOf(withMaps(kit.box(60f, 100f, 120f, 140f), listOf(identity(), mirrorX()))), "Add")
        return c to kit.transform(c)
    }

    /** The overlay's alpha just outside the box's bottom-right corner (where its handle square is drawn). */
    private fun cornerHandleAlpha(c: EditorController, tool: TransformTool): Int {
        val t = c.viewTransform
        val bmp = BitmapUtils.createLayerBitmap(kit.w, kit.h)
        tool.drawOverlay(Canvas(bmp), t)
        val corner = t.docToScreen(tool.transformState!!.corners()[2])
        val x = (corner.x + t.dp(4f)).toInt()
        val y = (corner.y + t.dp(4f)).toInt()
        return Color.alpha(bmp.getPixel(x, y))
    }

    @Test
    fun aPickedSideCanOnlyBeMovedTurnedAndFlipped() {
        val (c, tool) = mirrored()
        c.tap(422f, 120f)
        assertTrue(kit.idleUntil { VectorLift.activeLift(c)?.side == 1 })
        assertTrue(tool.sidePicked)
        assertFalse("no side handles", tool.sideHandlesShown)
        assertNull("the Scale row is hidden", tool.objectScale)
        assertEquals(MirrorLabels110.SIDE_RESIZE_BLOCKED, tool.modeRefusal(Mode.DISTORT))
        assertEquals(MirrorLabels110.SIDE_RESIZE_BLOCKED, tool.modeRefusal(Mode.MESH))
        assertNull(tool.modeRefusal(Mode.FREE))
        assertFalse(tool.canRasterizeFor(Mode.MESH))
        assertTrue(tool.flipsAllowed)
        assertEquals("no corner handle drawn", 0, cornerHandleAlpha(c, tool))

        // The toast once per pick: Distort, then a typed scale and size.
        c.message = null
        tool.mode = Mode.DISTORT
        assertEquals(Mode.FREE, tool.mode)
        assertEquals(MirrorLabels110.SIDE_RESIZE_BLOCKED, c.message)
        c.message = null
        tool.setScalePercent(150.0)
        tool.setSize(width = 200.0)
        tool.mode = Mode.MESH
        assertNull("once per pick", c.message)
        assertEquals(1f, tool.transformState!!.sx, 0f)
        assertEquals(1f, tool.transformState!!.sy, 0f)

        // A drag on the corner moves the side; a flip is allowed.
        val corner = tool.transformState!!.corners()[2]
        c.drag(corner, 37f, 23f)
        val st = tool.transformState!!
        assertEquals("not resized", 1f, abs(st.sx), 1e-4f)
        assertEquals("not resized", 1f, abs(st.sy), 1e-4f)
        assertTrue("moved", st.center().x > 430f)
        tool.flip(horizontal = true)
        assertEquals(-1f, tool.transformState!!.sx, 1e-4f)
        tool.commit()
        assertTrue(kit.idleUntil { !c.vectors.isRendering })
        val o = c.vec.vector!!.byId(1)!!
        assertEquals("still linked", 2, ObjectCopies.of(o).maps.size)
        assertTrue("an isometry", CopyEdit.isIsometry(ObjectCopies.of(o).maps[1]))

        // All sides (a side first, then the same spot): every handle and mode as in v1.9.
        val copy = CopyPick.boundsOf(o, 1)
        c.tap(copy.centerX(), copy.centerY())
        assertTrue(kit.idleUntil { VectorLift.activeLift(c)?.side == 1 })
        c.message = null
        c.tap(copy.centerX(), copy.centerY())
        assertTrue(kit.idleUntil { VectorLift.activeLift(c)?.side == -1 })
        assertFalse(tool.sidePicked)
        assertTrue(tool.sideHandlesShown)
        assertNotNull(tool.objectScale)
        assertNull(tool.modeRefusal(Mode.DISTORT))
        assertNotEquals("a corner handle", 0, cornerHandleAlpha(c, tool))
        c.drag(tool.transformState!!.corners()[2], 30f, 30f)
        assertTrue("all sides resize", abs(tool.transformState!!.sx) > 1.01f)
        tool.mode = Mode.DISTORT
        assertEquals(Mode.DISTORT, tool.mode)
        tool.discard()
    }

    @Test
    fun fitToCanvasAndACornerDragShowTheToastOncePerPick() {
        val (c, tool) = mirrored()
        c.tap(422f, 120f)
        assertTrue(kit.idleUntil { VectorLift.activeLift(c)?.side == 1 })

        // Fit to canvas would resize the side alone: refused, with the toast; nothing changes.
        val st0 = tool.transformState!!
        c.message = null
        tool.fitToCanvas()
        assertEquals(MirrorLabels110.SIDE_RESIZE_BLOCKED, c.message)
        assertEquals("not fitted", st0, tool.transformState)
        // A drag at the handle-less corner moves the side; the toast was already shown for this pick.
        c.message = null
        c.drag(st0.corners()[2], 37f, 23f)
        assertNull("once per pick", c.message)
        assertEquals("not resized", 1f, abs(tool.transformState!!.sx), 1e-4f)
        tool.commit()
        assertTrue(kit.idleUntil { !c.vectors.isRendering })

        // A new pick (the tool chosen again: ✓ keeps the pick): a drag inside the side says
        // nothing; a drag at its corner shows the toast once it drags, and moves the side without
        // resizing it.
        c.selectTool(ToolId.BRUSH)
        kit.transform(c)
        val copy = CopyPick.boundsOf(c.vec.vector!!.byId(1)!!, 1)
        c.tap(copy.centerX(), copy.centerY())
        assertTrue(kit.idleUntil { VectorLift.activeLift(c)?.side == 1 })
        c.message = null
        c.drag(tool.transformState!!.center(), -40f, 0f)
        assertNull("a move inside the side", c.message)
        assertTrue("still one side", tool.sidePicked)
        val st1 = tool.transformState!!
        c.drag(st1.corners()[2], -30f, 20f)
        assertEquals(MirrorLabels110.SIDE_RESIZE_BLOCKED, c.message)
        val st2 = tool.transformState!!
        assertEquals("not resized", 1f, abs(st2.sx), 1e-4f)
        assertEquals("not resized", 1f, abs(st2.sy), 1e-4f)
        assertTrue("moved", st2.center().x < st1.center().x - 20f && st2.center().y > st1.center().y + 10f)
        c.message = null
        tool.fitToCanvas()
        assertNull("once per pick", c.message)
        assertEquals("not fitted", st2, tool.transformState)
        tool.commit()
        assertTrue(kit.idleUntil { !c.vectors.isRendering })
        val maps = ObjectCopies.of(c.vec.vector!!.byId(1)!!).maps
        assertEquals("still linked", 2, maps.size)
        assertTrue("an isometry", CopyEdit.isIsometry(maps[1]))
    }

    @Test
    fun aDriftIsSnappedToTheNearestIsometry() {
        val (c, tool) = mirrored()
        val original = c.vec.vector!!.byId(1)!!
        val before = CopyPick.boundsOf(original, 1)
        c.tap(422f, 120f)
        assertTrue(kit.idleUntil { VectorLift.activeLift(c)?.side == 1 })
        val lift = VectorLift.activeLift(c) as CopyLift
        // A 3 % / −2 % residual scale and a turn, as a numeric drift would leave.
        val drift = tool.transformState!!.copy(sx = 1.03f, sy = 0.98f, rotationDeg = 10f)
        assertTrue(lift.commit(drift, TransformTool.TRANSFORM_OBJECTS_LABEL))
        assertTrue(kit.idleUntil { !c.vectors.isRendering })
        val o = c.vec.vector!!.byId(1)!!
        val maps = ObjectCopies.of(o).maps
        assertEquals(2, maps.size)
        assertTrue("snapped to an isometry", CopyEdit.isIsometry(maps[1]))
        // Turned about its centre at its own size: the centre stays.
        val after = CopyPick.boundsOf(o, 1)
        assertEquals(before.centerX(), after.centerX(), 0.6f)
        assertEquals(before.centerY(), after.centerY(), 0.6f)
        val turned = VectorOps.bounds(VectorOps.transformed(CopyPick.side(original, 1)!!, A1Kit.turnAbout(before.centerX(), before.centerY(), 10.0)))
        assertEquals("its own size", turned.width(), after.width(), 0.6f)
        tool.discard()
    }
}
