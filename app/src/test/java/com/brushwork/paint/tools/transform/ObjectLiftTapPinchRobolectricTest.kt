package com.brushwork.paint.tools.transform

import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.vector.VectorLayers
import com.brushwork.paint.vector.VectorOps
import com.brushwork.paint.vector.lift.VectorLift
import com.brushwork.paint.vector.select.ObjectTestKit
import com.brushwork.paint.vector.select.vec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.5 integration (lead): on a vector layer the Transform tool passes taps INSIDE the lifted box
 * to the object provider too (after the default "every object" lift, tapping an object picks it
 * alone, tapping the same spot again goes one object deeper), a tap's finger jitter never moves
 * lifted objects, and a pinch is accepted while the lift of the objects is still being prepared in
 * the background (it scales them when they land instead of zooming the view).
 *
 * Objects: 1 = a box (40..140, 40..120), 2 = an ellipse around (100, 80) on top of it, 3 = a box
 * (300..380, 200..260). The view is the identity (screen px = document px).
 */
@RunWith(RobolectricTestRunner::class)
class ObjectLiftTapPinchRobolectricTest {
    private val kit = ObjectTestKit()

    @After
    fun tearDown() = kit.close()

    private fun drawing(c: EditorController) {
        val ids = c.vectors.addObjects(c.vec, listOf(kit.box(40f, 40f, 140f, 120f), kit.ellipse(100f, 80f), kit.box(300f, 200f, 380f, 260f)), "Add")
        assertEquals(listOf(1L, 2L, 3L), ids)
    }

    private fun EditorController.tap(x: Float, y: Float, jitter: Float = 0f) {
        pointerDown(ToolPoint(x, y))
        if (jitter != 0f) pointerMove(ToolPoint(x + jitter, y + jitter / 2f))
        pointerUp(ToolPoint(x + jitter, y + jitter / 2f))
    }

    private fun lifted(c: EditorController): Set<Long>? = VectorLift.activeLift(c)?.ids

    @Test
    fun aTapInsideTheEveryObjectBoxPicksTheObjectAndTappingAgainGoesDeeper() {
        val c = kit.controller()
        drawing(c)
        val tool = kit.transform(c)
        assertEquals("every object is lifted at first", setOf(1L, 2L, 3L), lifted(c))
        val steps = c.undoManager.undoCount
        // On the ellipse (on top of the box), inside the box around everything.
        c.tap(100f, 80f)
        assertEquals(setOf(2L), c.vectors.selectedIds)
        assertEquals(setOf(2L), lifted(c))
        assertNotNull(tool.transformState)
        // The same spot again: the box under it, then round again to the ellipse.
        c.tap(100f, 80f)
        assertEquals(setOf(1L), lifted(c))
        c.tap(100f, 80f)
        assertEquals(setOf(2L), lifted(c))
        // Selecting is not history, and nothing moved.
        assertEquals(steps, c.undoManager.undoCount)
        tool.discard()
    }

    @Test
    fun aTapOnEmptySpaceInsideTheBoxChangesNothingAndJitterNeverMovesObjects() {
        val c = kit.controller()
        drawing(c)
        val tool = kit.transform(c)
        val start = tool.transformState!!
        val content = c.vec.vector
        // Between the objects, inside the box around all of them; the finger jitters by 3 px.
        c.tap(220f, 150f, jitter = 3f)
        assertEquals(setOf(1L, 2L, 3L), lifted(c))
        assertTrue("the jitter of a tap is no move", tool.transformState!!.sameGeometry(start))
        // A jittery tap on an object picks it: no step, nothing moved.
        val steps = c.undoManager.undoCount
        c.tap(340f, 230f, jitter = 3f)
        assertEquals(setOf(3L), lifted(c))
        assertEquals(steps, c.undoManager.undoCount)
        assertSame(content, c.vec.vector)
        tool.discard()
    }

    @Test
    fun onceMovedATapInsideTheBoxKeepsTheTransform() {
        val c = kit.controller()
        drawing(c)
        val tool = kit.transform(c)
        tool.moveBy(30f, 0f)
        val moved = tool.transformState!!
        // On the (moved) ellipse: the user is placing the drawing, nothing is picked.
        c.tap(130f, 80f)
        assertEquals(setOf(1L, 2L, 3L), lifted(c))
        assertTrue(tool.transformState!!.sameGeometry(moved))
        val steps = c.undoManager.undoCount
        tool.commit()
        assertEquals(steps + 1, c.undoManager.undoCount)
    }

    @Test
    fun aMovedObjectIsNotHitWhereItWas() {
        val c = kit.controller()
        drawing(c)
        c.vectors.setSelection(c.vec, setOf(2L))
        val tool = kit.transform(c)
        assertEquals(setOf(2L), lifted(c))
        tool.moveBy(200f, 0f)
        val steps = c.undoManager.undoCount
        // Where the ellipse was (its hole shows the box there): the box is picked, and the
        // ellipse's move is applied (one step).
        c.tap(100f, 80f)
        assertEquals(setOf(1L), lifted(c))
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(TransformTool.TRANSFORM_OBJECTS_LABEL, c.undoManager.undoLabel)
        val ellipse = VectorOps.bounds(c.vec.vector!!.byId(2)!!)
        assertEquals(300f, (ellipse.left + ellipse.right) / 2f, 0.5f)
        tool.discard()
    }

    // ------------------------------------------------------------------ pinch while the lift is prepared

    /**
     * The Transform tool idle while a move of object 1 still renders (its landing is held back:
     * returns what lands it), so the next lift is prepared (waits) until then. Object 3 is
     * selected.
     */
    private fun idleWhileRendering(c: EditorController): Pair<TransformTool, () -> Unit> {
        c.vectors.setSelection(c.vec, setOf(1L))
        val tool = kit.transform(c)
        assertEquals(setOf(1L), lifted(c))
        val provider = VectorLift.providerOf(c)
        var land: (() -> Unit)? = null
        provider.update = { l, after, label, shift, done -> land = { c.vectors.update(l, after, label, shift = shift, onDone = done) } }
        tool.moveBy(10f, 0f)
        tool.commit()
        assertNull(tool.transformState)
        c.vectors.setSelection(c.vec, setOf(3L))
        return tool to {
            provider.update = { l, after, label, shift, done -> c.vectors.update(l, after, label, shift = shift, onDone = done) }
            requireNotNull(land).invoke()
        }
    }

    @Test
    fun aPinchOnObjectsWhoseLiftIsPreparedScalesThemWhenTheyLand() {
        val c = kit.controller()
        drawing(c)
        val (tool, land) = idleWhileRendering(c)
        // A touch starts the lift of object 3, which waits for the render in flight.
        tool.start()
        assertTrue(tool.isPreparing)
        assertNull(tool.transformState)
        // Two fingers on either side of its box, neither on it: the view zooms.
        assertFalse(c.twoFingerStart(Vec2(340f, 230f), Vec2(250f, 230f), Vec2(430f, 230f)))
        // One finger on the box: the pinch is the objects', even before they are lifted.
        assertTrue(c.twoFingerStart(Vec2(370f, 230f), Vec2(340f, 230f), Vec2(400f, 230f)))
        c.twoFingerGesture(Vec2.ZERO, 2f, 0f)
        c.twoFingerEnd(cancelled = false)
        assertNull(tool.transformState)
        land()
        assertTrue(kit.idleUntil { tool.transformState != null && !tool.isPreparing })
        assertEquals(setOf(3L), lifted(c))
        val st = tool.transformState!!
        assertEquals("the finished pinch was applied when the objects landed", 2f * st.srcW, st.width, 1f)
        assertTrue(tool.hasUserChanges)
        tool.commit()
        val box = VectorOps.bounds(c.vec.vector!!.byId(3)!!)
        assertTrue("scaled about the pinch: $box", box.width() > 150f)
    }

    @Test
    fun aPinchOnObjectsOfAnIdleToolStartsTheirLiftAndGoesOnOnceTheyLand() {
        val c = kit.controller()
        drawing(c)
        val (tool, land) = idleWhileRendering(c)
        // Beside the selected object (on another one): the view zooms, nothing is lifted.
        assertFalse(c.twoFingerStart(Vec2(100f, 80f), Vec2(90f, 80f), Vec2(110f, 80f)))
        assertFalse(tool.isPreparing)
        // On it: its lift starts (and waits), the pinch waits for it and goes on.
        assertTrue(c.twoFingerStart(Vec2(370f, 230f), Vec2(340f, 230f), Vec2(400f, 230f)))
        assertTrue(tool.isPreparing)
        land()
        assertTrue(kit.idleUntil { tool.transformState != null })
        val src = tool.transformState!!.srcW.toFloat()
        c.twoFingerGesture(Vec2.ZERO, 1.5f, 0f)
        assertEquals(1.5f * src, tool.transformState!!.width, 1e-2f)
        c.twoFingerEnd(cancelled = true)
        assertEquals("cancelled: back to the lifted objects", src, tool.transformState!!.width, 1e-2f)
        assertFalse(tool.hasUserChanges)
        tool.discard()
    }

    /** The lift renders its preview in the background (A1's asynchronous beginEdit): the pinch scales the objects. */
    @Test
    fun aPinchWhileTheLiftRendersInTheBackgroundScalesTheObjects() {
        val c = kit.controller()
        drawing(c)
        c.vectors.setSelection(c.vec, setOf(3L))
        c.vectors.policy = VectorLayers.Policy.ASYNC
        c.selectTool(ToolId.TRANSFORM)
        val tool = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        tool.discard()
        assertTrue(c.twoFingerStart(Vec2(370f, 230f), Vec2(340f, 230f), Vec2(400f, 230f)))
        c.twoFingerGesture(Vec2.ZERO, 2f, 0f)
        c.twoFingerEnd(cancelled = false)
        assertTrue(kit.idleUntil { tool.transformState != null && !tool.isPreparing })
        val st = tool.transformState!!
        assertEquals(2f * st.srcW, st.width, 1f)
        tool.commit()
        c.vectors.flushPending()
        assertTrue(VectorOps.bounds(c.vec.vector!!.byId(3)!!).width() > 150f)
    }

    @Test
    fun anAbandonedLiftIsLetGoWhenItLands() {
        val c = kit.controller()
        drawing(c)
        val (tool, land) = idleWhileRendering(c)
        tool.start()
        assertTrue(tool.isPreparing)
        // ✕ while it is being prepared: when it lands it is not shown.
        tool.discard()
        assertFalse(tool.isPreparing)
        land()
        kit.idleUntil { !c.vectors.isRendering }
        kit.idle()
        assertNull(tool.transformState)
        assertNull(VectorLift.activeLift(c))
        assertNull(c.renderOverride)
    }
}
