package com.brushwork.paint.v110.a1

import com.brushwork.paint.model.SelectionMode
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.select.MarqueeTool
import com.brushwork.paint.v110.a1.A1Kit.identity
import com.brushwork.paint.v110.a1.A1Kit.mirrorX
import com.brushwork.paint.v110.a1.A1Kit.withMaps
import com.brushwork.paint.vector.lift.VectorLift
import com.brushwork.paint.vector.select.ObjectTestKit
import com.brushwork.paint.vector.select.vec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 (R1 (a), C8, J19): Select shapes never picks a side. A side picked in Transform is
 * forgotten when the tool is left; an area of the Select shape tool over the mirrored copy alone
 * selects the whole object (v1.9), and back in Transform that object is lifted whole, every side
 * in its box.
 *
 * The box (60..120, 100..140) and its mirrored copy (392..452, 100..140).
 */
@RunWith(RobolectricTestRunner::class)
class SelectShapesPicksWholeTest {
    private val kit = ObjectTestKit()

    @After
    fun tearDown() = kit.close()

    @Test
    fun anAreaOverOneSideSelectsTheWholeObject() {
        val c = kit.controller()
        c.vectors.addObjects(c.vec, listOf(withMaps(kit.box(60f, 100f, 120f, 140f), listOf(identity(), mirrorX()))), "Add")
        val provider = VectorLift.providerOf(c)

        // A side picked in Transform first.
        kit.transform(c)
        c.pointerDown(ToolPoint(422f, 120f))
        c.pointerUp(ToolPoint(422f, 120f))
        assertTrue(kit.idleUntil { VectorLift.activeLift(c)?.side == 1 })
        assertNotNull(provider.sidePick)

        // Select shape: an area over the copy alone.
        c.selectTool(ToolId.MARQUEE)
        kit.idle()
        assertNull("leaving Transform forgets the side", provider.sidePick)
        val marquee = c.tools.getValue(ToolId.MARQUEE) as MarqueeTool
        marquee.mode = SelectionMode.REPLACE
        marquee.onDown(ToolPoint(380f, 90f))
        marquee.onMove(ToolPoint(425f, 120f))
        marquee.onMove(ToolPoint(470f, 150f))
        marquee.onUp(ToolPoint(470f, 150f))
        assertTrue(kit.idleUntil { !marquee.busy && c.vectors.selectedIds.isNotEmpty() })
        assertEquals("the whole object", setOf(1L), c.vectors.selectedIds)
        assertNull("no pixel selection", c.selection)
        assertNull("Select shapes never picks a side", provider.sidePick)

        // Back in Transform: the object lifted whole, both sides in the box.
        val tool = kit.transform(c)
        assertTrue(kit.idleUntil { VectorLift.activeLift(c) != null && !tool.isPreparing })
        val lift = VectorLift.activeLift(c)!!
        assertEquals("all sides", -1, lift.side)
        assertEquals(setOf(1L), lift.ids)
        assertTrue("the box holds both sides: ${lift.sourceRect}", lift.sourceRect.left <= 62f && lift.sourceRect.right >= 450f)
        assertFalse(tool.sidePicked)
        tool.discard()
    }
}
