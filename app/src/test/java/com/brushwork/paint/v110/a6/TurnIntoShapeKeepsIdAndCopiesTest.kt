package com.brushwork.paint.v110.a6

import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.vector.CopyMaps
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.select.ObjectActions
import com.brushwork.paint.vector.select.ObjectTestKit
import com.brushwork.paint.vector.select.vec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.cos
import kotlin.math.sin

/**
 * v1.10 A6 (R11 (a) "What"): "Turn into shape" converts the selected path in place: the shape
 * has the path's id, its place in the stacking order and its live copies as they are (moved
 * sides' offsets, S2, included); the objects around it are untouched. The object selection is
 * cleared and the Shape tool opens the shape; ✕ there keeps it.
 */
@RunWith(RobolectricTestRunner::class)
class TurnIntoShapeKeepsIdAndCopiesTest {
    private val kit = ObjectTestKit(400, 300)

    @After
    fun close() = kit.close()

    /** A mirror copy about x = 200 and a copy turned 120 degrees about (200, 150), moved 30 px right (S2). */
    private val copies: CopyMaps = run {
        val a = Math.toRadians(120.0)
        val co = cos(a).toFloat()
        val si = sin(a).toFloat()
        CopyMaps.of(
            listOf(
                floatArrayOf(-1f, 0f, 400f, 0f, 1f, 0f, 0f, 0f, 1f),
                floatArrayOf(co, -si, 200f - co * 200f + si * 150f + 30f, si, co, 150f - si * 200f - co * 150f, 0f, 0f, 1f),
            ),
        )
    }

    @Test
    fun theShapeTakesThePathsIdPlaceAndCopies() {
        val c = kit.controller()
        assertEquals("the copies are usable", 3, copies.maps.size)
        val ids = A6Paths.add(kit, c, kit.rectangle(80f, 80f, 60f, 40f), A6Paths.curve(closed = true).copy(fill = VPaint.Solid(RED), copies = copies), A6Paths.polyline())
        val before = c.vec.vector!!
        A6Paths.select(c, ids[1])
        assertTrue(ObjectActions.turnIntoShape(c))
        assertTrue(kit.idleUntil { !c.vectors.isRendering })
        val after = c.vec.vector!!
        assertEquals("the same ids in the same order", ids, after.objects.map { it.id })
        val shape = after.objects[1] as? VShape ?: throw AssertionError("a shape: ${after.objects[1]}")
        assertEquals("the copies as they were", copies, shape.shape.copies)
        assertEquals("the objects around it are untouched", before.objects[0], after.objects[0])
        assertEquals(before.objects[2], after.objects[2])
        assertTrue("the object selection is cleared", c.vectors.selectedIds.isEmpty())

        assertEquals("the Shape tool", ToolId.SHAPE, c.activeToolId)
        val tool = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        assertTrue("the shape is open", kit.idleUntil { tool.editingObject && tool.hasPendingWork })
        tool.discard()
        kit.idle()
        assertSame("✕ keeps the shape", after, c.vec.vector)
        assertEquals("the user stays in the Shape tool", ToolId.SHAPE, c.activeToolId)
    }

    private companion object {
        const val RED = 0xFFE04020.toInt()
    }
}
