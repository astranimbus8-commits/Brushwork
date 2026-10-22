package com.brushwork.paint.v110.a6

import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.ui.common.ObjectBarLabels110
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.select.ObjectActions
import com.brushwork.paint.vector.select.ObjectTestKit
import com.brushwork.paint.vector.select.vec
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * v1.10 A6 (R11 (a) "Undo", "Refusals"): "Turn into shape" is ONE undo step labelled
 * [ObjectBarLabels110.TO_SHAPE] (opening the shape adds none); undo brings the path back, data
 * and pixels, and redo the shape. A refused path (several pieces, more than 1,000 points) shows
 * the reason and changes nothing: no step, the same content, tool and selection. A selected shape
 * isn't turned into anything.
 */
@RunWith(RobolectricTestRunner::class)
class TurnIntoShapeOneStepTest {
    private val kit = ObjectTestKit(400, 300)

    @After
    fun close() = kit.close()

    private fun pixels(c: EditorController): IntArray {
        val b = c.vec.bitmap
        return IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
    }

    private fun settle(c: EditorController) = assertTrue(kit.idleUntil { !c.vectors.isRendering })

    @Test
    fun oneStepUndoneAndRedone() {
        val c = kit.controller()
        val id = A6Paths.add(kit, c, A6Paths.curve(closed = true).copy(fill = VPaint.Solid(RED))).single()
        val before = c.vec.vector!!
        val px = pixels(c)
        A6Paths.select(c, id)
        val steps = c.undoManager.undoCount
        assertTrue(ObjectActions.turnIntoShape(c))
        settle(c)
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertEquals(ObjectBarLabels110.TO_SHAPE, c.undoManager.undoLabel)
        val tool = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        assertTrue(kit.idleUntil { tool.editingObject })
        assertEquals("opening the shape is no step", steps + 1, c.undoManager.undoCount)
        val converted = c.vec.vector!!
        assertTrue(converted.byId(id) is VShape)
        tool.discard()
        kit.idle()

        c.undo()
        settle(c)
        assertEquals("undo: the path again", before.objects, c.vec.vector!!.objects)
        assertArrayEquals("and its pixels", px, pixels(c))
        assertEquals(steps, c.undoManager.undoCount)
        c.redo()
        settle(c)
        assertEquals("redo: the shape again", converted.objects, c.vec.vector!!.objects)
    }

    @Test
    fun refusedPathsChangeNothing() {
        val ring = VPath(
            0,
            subpaths = listOf(VSubpath(List(1001) { i -> (2.0 * PI * i / 1001).let { a -> VAnchor(200f + 120f * cos(a).toFloat(), 150f + 120f * sin(a).toFloat()) } }, closed = true)),
            stroke = VStrokeStyle(color = RED, width = 3f),
        )
        for ((p, reason) in listOf(A6Paths.twoParts() to ObjectBarLabels110.REFUSE_PIECES, ring to ObjectBarLabels110.REFUSE_POINTS)) {
            val c = kit.controller()
            val id = A6Paths.add(kit, c, p).single()
            A6Paths.select(c, id)
            val content = c.vec.vector
            val steps = c.undoManager.undoCount
            val tool = c.activeToolId
            c.message = null
            assertFalse(ObjectActions.turnIntoShape(c))
            kit.idle()
            assertEquals("the reason", reason, c.message)
            assertSame("the same content", content, c.vec.vector)
            assertEquals("no step", steps, c.undoManager.undoCount)
            assertEquals("the same tool", tool, c.activeToolId)
            assertEquals("still selected", setOf(id), c.vectors.selectedIds)
        }
    }

    @Test
    fun aShapeIsNotTurnedIntoAnything() {
        val c = kit.controller()
        val id = A6Paths.add(kit, c, kit.rectangle(200f, 150f, 120f, 80f)).single()
        A6Paths.select(c, id)
        assertFalse(ObjectActions.toShapeShown(c))
        val content = c.vec.vector
        val steps = c.undoManager.undoCount
        assertFalse(ObjectActions.turnIntoShape(c))
        kit.idle()
        assertSame(content, c.vec.vector)
        assertEquals(steps, c.undoManager.undoCount)
    }

    private companion object {
        const val RED = 0xFFE04020.toInt()
    }
}
