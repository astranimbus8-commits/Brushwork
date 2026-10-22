package com.brushwork.paint.v110.a1

import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.ui.common.MirrorLabels110
import com.brushwork.paint.v110.a1.A1Kit.identity
import com.brushwork.paint.v110.a1.A1Kit.mirrorX
import com.brushwork.paint.v110.a1.A1Kit.withMaps
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.lift.CopyLift
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

/**
 * v1.10 (R1 (a), I15): a side move whose result the copy limits reject (here a coefficient above
 * 1e7: the side typed 20 000 000 px away) shows `MirrorLabels110.SIDE_REFUSED`, changes nothing
 * and records no step; ✓ keeps the side lifted where it is, and leaving the tool drops it. The
 * same holds for the source side (k = 0, whose move rebases every other map).
 */
@RunWith(RobolectricTestRunner::class)
class SideRefusedTest {
    private val kit = ObjectTestKit()

    @After
    fun tearDown() = kit.close()

    private fun EditorController.tap(x: Float, y: Float) {
        pointerDown(ToolPoint(x, y))
        pointerUp(ToolPoint(x, y))
    }

    @Test
    fun anUnusableSideMoveIsRefusedAndChangesNothing() {
        val c = kit.controller()
        c.vectors.addObjects(c.vec, listOf(withMaps(kit.box(60f, 100f, 120f, 140f), listOf(identity(), mirrorX()))), "Add")
        val tool = kit.transform(c)
        val before = c.vec.vector!!
        val steps = c.undoManager.undoCount

        for ((x, k) in listOf(422f to 1, 90f to 0)) {
            c.tap(x, 120f)
            assertTrue("side $k", kit.idleUntil { VectorLift.activeLift(c)?.side == k })
            tool.setPosition(left = 2.0e7)
            c.message = null
            tool.commit()
            assertEquals("side $k", MirrorLabels110.SIDE_REFUSED, c.message)
            assertTrue("side $k: nothing changed", c.vec.vector === before)
            assertEquals("side $k: no step", steps, c.undoManager.undoCount)
            // ✓ keeps the side lifted where it is.
            assertNotNull("side $k: still lifted", tool.transformState)
            assertTrue("side $k", VectorLift.activeLift(c) is CopyLift)
            assertTrue(tool.sidePicked)
            tool.discard()
            assertTrue(kit.idleUntil { VectorLift.activeLift(c) == null })
        }

        // Leaving the tool with a refused side drops it, with the same message and nothing changed.
        c.tap(422f, 120f)
        assertTrue(kit.idleUntil { VectorLift.activeLift(c)?.side == 1 })
        tool.setPosition(left = 2.0e7)
        c.message = null
        c.selectTool(ToolId.BRUSH)
        assertEquals(MirrorLabels110.SIDE_REFUSED, c.message)
        assertNull(tool.transformState)
        assertNull(VectorLift.activeLift(c))
        assertTrue(c.vec.vector === before)
        assertEquals(steps, c.undoManager.undoCount)
        assertEquals(2, ObjectCopies.of(c.vec.vector!!.byId(1)!!).maps.size)
    }
}
