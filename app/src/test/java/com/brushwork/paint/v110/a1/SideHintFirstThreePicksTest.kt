package com.brushwork.paint.v110.a1

import com.brushwork.paint.EditorController
import com.brushwork.paint.UiPrefs
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.ui.common.MirrorLabels110
import com.brushwork.paint.v110.a1.A1Kit.identity
import com.brushwork.paint.v110.a1.A1Kit.mirrorX
import com.brushwork.paint.v110.a1.A1Kit.withMaps
import com.brushwork.paint.vector.lift.VectorLift
import com.brushwork.paint.vector.select.ObjectTestKit
import com.brushwork.paint.vector.select.vec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 (R1 (a), finding 34): the first 3 side picks on the phone, across editor sessions
 * (`UiPrefs.hintSidePicks`), show `MirrorLabels110.SIDE_HINT`; the 4th does not, and picking all
 * sides never does. While a side is picked the pill's trash is named `MirrorLabels110.DELETE_SIDE`.
 */
@RunWith(RobolectricTestRunner::class)
class SideHintFirstThreePicksTest {
    private val kit = ObjectTestKit()

    @After
    fun tearDown() = kit.close()

    private val onCopy = Vec2(420f, 120f)
    private val onSource = Vec2(90f, 120f)

    private fun session(): EditorController {
        val c = kit.controller()
        c.vectors.addObjects(c.vec, listOf(withMaps(kit.box(60f, 100f, 120f, 140f), listOf(identity(), mirrorX()))), "Add")
        return c
    }

    /** Taps [p] and returns the message it showed (null: none). */
    private fun tap(c: EditorController, p: Vec2): String? {
        c.message = null
        VectorLift.providerOf(c).tapped(p)
        return c.message
    }

    @Test
    fun theHintShowsOnTheFirstThreePicksOnly() {
        val first = session()
        assertEquals("pick 1", MirrorLabels110.SIDE_HINT, tap(first, onCopy))
        assertNotEquals("all sides is not a side pick", MirrorLabels110.SIDE_HINT, tap(first, onCopy))
        assertEquals("pick 2", MirrorLabels110.SIDE_HINT, tap(first, onSource))
        assertEquals(2, UiPrefs(first.appContext).hintSidePicks)

        // Another editor session on the same phone.
        val second = session()
        assertEquals("pick 3", MirrorLabels110.SIDE_HINT, tap(second, onCopy))
        assertNotEquals("pick 4", MirrorLabels110.SIDE_HINT, tap(second, onSource))
        assertEquals(3, UiPrefs(second.appContext).hintSidePicks)
    }

    /** While a side is picked the pill's trash cell is named "Delete one side"; with all sides, Transform has none (v1.7). */
    @Test
    fun theTrashIsNamedDeleteOneSideWhileASideIsPicked() {
        val c = session()
        val tool = kit.transform(c)
        assertNull("every object lifted: no trash", tool.objectDeletion)
        c.pointerDown(ToolPoint(onCopy.x, onCopy.y))
        c.pointerUp(ToolPoint(onCopy.x, onCopy.y))
        assertTrue(kit.idleUntil { VectorLift.activeLift(c)?.side == 1 })
        assertEquals(MirrorLabels110.DELETE_SIDE, tool.objectDeletion?.deleteLabel)
        c.pointerDown(ToolPoint(onCopy.x, onCopy.y))
        c.pointerUp(ToolPoint(onCopy.x, onCopy.y))
        assertTrue(kit.idleUntil { VectorLift.activeLift(c)?.side == -1 })
        assertNull("all sides: no trash", tool.objectDeletion)
        tool.discard()
    }
}
