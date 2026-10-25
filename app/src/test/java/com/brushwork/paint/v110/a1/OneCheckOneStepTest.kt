package com.brushwork.paint.v110.a1

import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.v110.a1.A1Kit.withMaps
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.copies.CopyPick
import com.brushwork.paint.vector.lift.VectorLift
import com.brushwork.paint.vector.select.ObjectTestKit
import com.brushwork.paint.vector.select.vec
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 (R1 (a), I2): whatever is done to a picked side before ✓ (a move, a turn, a flip), ✓ is ONE
 * undo step "Transform objects" and ✕ records none; undo puts every map back exactly, redo
 * applies the same maps again. For a path and a shape, for every map kind.
 */
@RunWith(RobolectricTestRunner::class)
class OneCheckOneStepTest {
    private val kit = ObjectTestKit()

    @After
    fun tearDown() = kit.close()

    private fun EditorController.tap(x: Float, y: Float) {
        pointerDown(ToolPoint(x, y))
        pointerUp(ToolPoint(x, y))
    }

    private fun maps(o: VObject): List<List<Float>> = ObjectCopies.of(o).maps.map { it.toList() }

    @Test
    fun oneCheckIsOneStepAndUndoRestoresEveryMap() {
        for ((name, base) in listOf("path" to kit.box(60f, 100f, 120f, 140f), "shape" to kit.rectangle(90f, 120f, 60f, 40f)))
            for ((kind, list) in A1Kit.kinds()) {
                val what = "$name, $kind"
                val c = kit.controller()
                c.vectors.addObjects(c.vec, listOf(withMaps(base, list)), "Add")
                val tool: TransformTool = kit.transform(c)
                val start = c.vec.vector!!.byId(1)!!
                val k = list.lastIndex
                val box = CopyPick.boundsOf(start, k)
                c.tap(box.centerX(), box.centerY())
                assertTrue(what, kit.idleUntil { VectorLift.activeLift(c)?.side == k })
                val steps = c.undoManager.undoCount

                // ✕ records nothing.
                tool.moveBy(11f, 7f)
                tool.discard()
                assertTrue(what, kit.idleUntil { !c.vectors.isRendering })
                assertEquals("$what: ✕", steps, c.undoManager.undoCount)
                assertEquals("$what: ✕", maps(start), maps(c.vec.vector!!.byId(1)!!))

                // A move, a turn and a flip, then ✓: one step (the tool chosen again: a new tap cycle).
                c.selectTool(ToolId.BRUSH)
                kit.transform(c)
                c.tap(box.centerX(), box.centerY())
                assertTrue(what, kit.idleUntil { VectorLift.activeLift(c)?.side == k })
                tool.moveBy(11f, 7f)
                tool.rotate90(clockwise = true)
                tool.flip(horizontal = false)
                tool.commit()
                assertTrue(what, kit.idleUntil { !c.vectors.isRendering })
                assertEquals("$what: one step", steps + 1, c.undoManager.undoCount)
                assertEquals(what, TransformTool.TRANSFORM_OBJECTS_LABEL, c.undoManager.undoLabel)
                val after = c.vec.vector!!.byId(1)!!
                assertFalse("$what: moved", maps(start) == maps(after))
                assertEquals("$what: still linked", list.size, ObjectCopies.of(after).maps.size)

                c.undo()
                assertTrue(what, kit.idleUntil { !c.vectors.isRendering })
                val undone = c.vec.vector!!.byId(1)!!
                for (j in list.indices) assertArrayEquals("$what: undo, map $j", ObjectCopies.of(start).maps[j], ObjectCopies.of(undone).maps[j], 0f)
                c.redo()
                assertTrue(what, kit.idleUntil { !c.vectors.isRendering })
                assertEquals("$what: redo", maps(after), maps(c.vec.vector!!.byId(1)!!))
            }
    }
}
