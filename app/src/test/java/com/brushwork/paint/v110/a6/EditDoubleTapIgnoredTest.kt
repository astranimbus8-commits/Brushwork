package com.brushwork.paint.v110.a6

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveKind
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.vector.select.ObjectActions
import com.brushwork.paint.vector.select.ObjectTestKit
import com.brushwork.paint.vector.select.PendingRenders
import com.brushwork.paint.vector.select.vec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 A6 (R10): a second tap on Edit while the first is still opening is ignored. While the
 * first waits for a render in flight, the second does nothing and says nothing (no "Still
 * updating"); once the render landed one editor opens. A second open of the object a tool has
 * open already ([CurveTool.openObject], [ShapeTool.editShapeObject]) neither applies its
 * changes nor opens it again.
 */
@RunWith(RobolectricTestRunner::class)
class EditDoubleTapIgnoredTest {
    private val kit = ObjectTestKit(400, 300)

    @After
    fun close() = kit.close()

    @Test
    fun aSecondTapWhileTheFirstWaitsIsIgnored() {
        val c = kit.controller()
        val id = A6Paths.add(kit, c, A6Paths.curve()).single()
        A6Paths.select(c, id)
        val steps = c.undoManager.undoCount
        // A render in flight: the first tap waits for it.
        val landed = PendingRenders.begin(c)
        c.message = null
        assertTrue("the first tap waits (it is on its way)", ObjectActions.openInEditor(c))
        assertEquals("nothing opened yet", setOf(id), c.vectors.selectedIds)
        assertFalse("a second tap is ignored", ObjectActions.openInEditor(c))
        assertNull("silently", c.message)
        landed()
        kit.idle()
        val tool = c.tools.getValue(ToolId.CURVE) as CurveTool
        assertTrue("one editor opened", kit.idleUntil { tool.isReopened })
        assertEquals(ToolId.CURVE, c.activeToolId)
        assertTrue(c.vectors.selectedIds.isEmpty())
        assertEquals(steps, c.undoManager.undoCount)
        assertFalse("nothing waits any more", PendingRenders.hasWaiting(c))
    }

    @Test
    fun aSecondOpenOfTheOpenPathChangesNothing() {
        val c = kit.controller()
        val id = A6Paths.add(kit, c, A6Paths.polyline()).single()
        A6Paths.select(c, id)
        assertTrue(ObjectActions.openInEditor(c))
        val tool = c.tools.getValue(ToolId.POLYLINE) as CurveTool
        assertTrue(kit.idleUntil { tool.isReopened })
        tool.moveAnchor(1, Vec2(310f, 120f))
        assertTrue("a change waits for ✓", tool.hasUserChanges)
        val steps = c.undoManager.undoCount
        val content = c.vec.vector
        assertTrue("already open", tool.openObject(c.vec.id, id, CurveKind.POLYLINE))
        // (Called on another kind's instance too: the Polyline tool's does it.)
        assertTrue((c.tools.getValue(ToolId.CURVE) as CurveTool).openObject(c.vec.id, id, CurveKind.POLYLINE))
        kit.idle()
        assertEquals("the change was not applied", steps, c.undoManager.undoCount)
        assertEquals(content, c.vec.vector)
        assertTrue("still open, with its change", tool.isReopened && tool.hasUserChanges)
        assertEquals(ToolId.POLYLINE, c.activeToolId)
    }

    @Test
    fun aSecondOpenOfTheOpenShapeChangesNothing() {
        val c = kit.controller()
        val id = A6Paths.add(kit, c, kit.ellipse(200f, 150f)).single()
        A6Paths.select(c, id)
        assertTrue(ObjectActions.openInEditor(c))
        val tool = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        assertTrue(kit.idleUntil { tool.editingObject })
        val steps = c.undoManager.undoCount
        assertTrue(tool.editShapeObject(c.vec, id))
        assertTrue(tool.editShapeObject(c.vec, id))
        kit.idle()
        assertTrue(tool.editingObject && tool.hasPendingWork)
        assertEquals(steps, c.undoManager.undoCount)
    }

    @Test
    fun aPathOfAnotherKindIsNotOpened() {
        val c = kit.controller()
        val id = A6Paths.add(kit, c, A6Paths.curve()).single()
        val tool = c.tools.getValue(ToolId.POLYLINE) as CurveTool
        assertFalse("a curve is not a polyline", tool.openObject(c.vec.id, id, CurveKind.POLYLINE))
        assertFalse("no such object", tool.openObject(c.vec.id, id + 100, CurveKind.CURVE))
        assertFalse("no such layer", tool.openObject(-1L, id, CurveKind.CURVE))
    }
}
