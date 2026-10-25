package com.brushwork.paint.v110.a1

import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.v110.a1.A1Kit.identity
import com.brushwork.paint.v110.a1.A1Kit.mirrorX
import com.brushwork.paint.v110.a1.A1Kit.translate
import com.brushwork.paint.v110.a1.A1Kit.withMaps
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.lift.VectorLift
import com.brushwork.paint.vector.select.ObjectTestKit
import com.brushwork.paint.vector.select.vec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs

/**
 * v1.10 (R1 (a), `CopyMaps.of` rule): a side moved exactly onto another side, or onto the source,
 * merges with it (a map within 1e-4 of another, or of the identity, is removed) in the same ✓
 * step "Transform objects"; a Mirror whose copy lands on its source becomes a plain object. The
 * merged side is no longer picked.
 */
@RunWith(RobolectricTestRunner::class)
class CoincidingMapsMergeTest {
    private val kit = ObjectTestKit()

    @After
    fun tearDown() = kit.close()

    private fun EditorController.tap(x: Float, y: Float) {
        pointerDown(ToolPoint(x, y))
        pointerUp(ToolPoint(x, y))
    }

    private fun setUp(maps: List<FloatArray>): Pair<EditorController, TransformTool> {
        val c = kit.controller()
        c.vectors.addObjects(c.vec, listOf(withMaps(kit.box(60f, 100f, 120f, 140f), maps)), "Add")
        return c to kit.transform(c)
    }

    /** Commits [move] on the side picked at ([x], [y]) as one step and returns the object after. */
    private fun moveSide(c: EditorController, tool: TransformTool, x: Float, y: Float, k: Int, move: () -> Unit): VObject {
        c.tap(x, y)
        assertTrue(kit.idleUntil { VectorLift.activeLift(c)?.side == k })
        val steps = c.undoManager.undoCount
        move()
        tool.commit()
        assertTrue(kit.idleUntil { !c.vectors.isRendering })
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertEquals(TransformTool.TRANSFORM_OBJECTS_LABEL, c.undoManager.undoLabel)
        assertNull("the merged side is no longer picked", VectorLift.providerOf(c).sidePick)
        return c.vec.vector!!.byId(1)!!
    }

    private fun same(a: FloatArray, b: FloatArray) = (0 until 9).all { abs(a[it] - b[it]) <= 1e-4f }

    @Test
    fun aSideOnAnotherSideMerges() {
        // Array: copy 1 (moved 120 px right) moved onto copy 2 (160 px down).
        val (c, tool) = setUp(listOf(identity(), translate(120f, 0f), translate(0f, 160f), translate(300f, 200f)))
        val o = moveSide(c, tool, 210f, 120f, 1) { tool.moveBy(-120f, 160f) }
        val maps = ObjectCopies.of(o).maps
        assertEquals("one map fewer", 3, maps.size)
        assertTrue(same(identity(), maps[0]))
        assertTrue(same(translate(0f, 160f), maps[1]))
        assertTrue(same(translate(300f, 200f), maps[2]))

        // Copy 2 (now the 300, 200 one) moved onto the source: removed.
        val o2 = moveSide(c, tool, 390f, 320f, 2) { tool.moveBy(-300f, -200f) }
        assertEquals(2, ObjectCopies.of(o2).maps.size)
    }

    @Test
    fun aMirrorCopyFlippedOntoItsSourceBecomesPlain() {
        val (c, tool) = setUp(listOf(identity(), mirrorX()))
        // The copy at x 392..452 flipped back and moved onto the source at x 60..120.
        val o = moveSide(c, tool, 422f, 120f, 1) {
            tool.flip(horizontal = true)
            tool.moveBy(-332f, 0f)
        }
        assertTrue("plain", ObjectCopies.of(o).isEmpty)
    }
}
