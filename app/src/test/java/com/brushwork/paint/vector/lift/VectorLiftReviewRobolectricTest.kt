package com.brushwork.paint.vector.lift

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.SelectionMode
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VectorOps
import com.brushwork.paint.vector.select.ObjectActions
import com.brushwork.paint.vector.select.ObjectTestKit
import com.brushwork.paint.vector.select.PendingRenders
import com.brushwork.paint.vector.select.VectorObjectSelection
import com.brushwork.paint.vector.select.vec
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.floor

/**
 * v1.5 A2 review: the Transform lift and the Object bar stay correct while a vector update renders
 * in the background (they wait for it and start from the landed objects), a stopped search never
 * leaves the Transform tool waiting, Delete while transforming is one step, a tap's "every object"
 * request does not outlive the selection it was made with, and Duplicate's offset stays visible
 * on a zoomed-out canvas.
 */
@RunWith(RobolectricTestRunner::class)
class VectorLiftReviewRobolectricTest {
    private var kit = ObjectTestKit()

    @After
    fun tearDown() = kit.close()

    private fun anchorsOf(p: VPath) = p.subpaths.flatMap { s -> s.anchors.map { Vec2(it.x, it.y) } }

    private fun ids(c: EditorController) = c.vec.vector!!.objects.map { it.id }

    /** Makes the lifts of [c] apply their result later: returns what lands the newest one. */
    private fun delayLanding(c: EditorController): () -> (() -> Unit) {
        var land: (() -> Unit)? = null
        VectorLift.providerOf(c).update = { l, after, label, shift, done ->
            land = { c.vectors.update(l, after, label, shift = shift, onDone = done) }
        }
        return { requireNotNull(land) }
    }

    @Test
    fun stoppingTheSearchNeverLeavesTheTransformToolWaiting() {
        val c = kit.controller()
        val layer = c.vec
        c.vectors.addObjects(layer, (0 until 70).map { i -> kit.box(10f + (i % 10) * 40f, 10f + (i / 10) * 40f, 40f + (i % 10) * 40f, 40f + (i / 10) * 40f) }, "Add")
        val tool = kit.transform(c)
        tool.discard()
        assertNull(tool.transformState)
        c.setSelection(kit.rectSelection(0, 0, 130, 85))
        // A large selection is searched in the background ...
        tool.start()
        val provider = VectorLift.providerOf(c)
        val search = provider.searchJob
        assertNotNull(search)
        assertTrue(tool.isPreparing)
        // ... and Stop (the busy overlay) cancels it: the tool no longer waits.
        search!!.cancel()
        kit.idle()
        assertFalse(tool.isPreparing)
        assertNull(tool.transformState)
        // The next touch lifts again.
        tool.start()
        assertTrue(kit.idleUntil { tool.transformState != null })
        val expected = layer.vector!!.objects.filter { o -> val r = VectorOps.bounds(o); r.left < 130f && r.top < 85f }.map { it.id }.toSet()
        assertEquals(expected, VectorLift.activeLift(c)!!.ids)
        tool.discard()
    }

    @Test
    fun aLiftAskedForWhileACommitRendersStartsFromTheLandedObjects() {
        val c = kit.controller()
        val layer = c.vec
        c.vectors.addObjects(layer, listOf(kit.box(40f, 40f, 140f, 120f), kit.stroke(200f, 200f, 300f, 220f)), "Add")
        val box0 = anchorsOf(layer.vector!!.byId(1) as VPath)
        c.vectors.setSelection(layer, setOf(1L))
        val tool = kit.transform(c)
        val landing = delayLanding(c)
        tool.moveBy(100f, 50f)
        tool.commit()
        assertTrue(PendingRenders.busy(c))
        // A touch right away: the lift waits (it would show the box where it was).
        tool.start()
        assertNull(tool.transformState)
        assertTrue(tool.isPreparing)
        assertNull(VectorLift.activeLift(c))
        // The render lands: the box is lifted where it went.
        VectorLift.providerOf(c).update = { l, after, label, shift, done -> c.vectors.update(l, after, label, shift = shift, onDone = done) }
        landing()()
        assertFalse(PendingRenders.busy(c))
        val lift = VectorLift.activeLift(c)
        assertNotNull(lift)
        assertNotNull(tool.transformState)
        val moved = VectorOps.bounds(layer.vector!!.byId(1)!!)
        assertEquals(floor(moved.left).toInt(), lift!!.sourceRect.left)
        assertEquals(floor(moved.top).toInt(), lift.sourceRect.top)
        // Moving it again moves the moved box (never the old place, never twice).
        tool.moveBy(10f, 0f)
        tool.commit()
        assertEquals(box0.map { it + Vec2(110f, 50f) }, anchorsOf(layer.vector!!.byId(1) as VPath))
        assertArrayEquals(kit.render(layer.vector!!), kit.pixels(layer.bitmap))
    }

    @Test
    fun anObjectBarActionWhileACommitRendersWorksOnTheLandedObjects() {
        val c = kit.controller()
        val layer = c.vec
        c.vectors.addObjects(layer, listOf(kit.box(40f, 40f, 140f, 120f), kit.stroke(200f, 200f, 300f, 220f)), "Add")
        val box0 = anchorsOf(layer.vector!!.byId(1) as VPath)
        c.vectors.setSelection(layer, setOf(1L))
        val tool = kit.transform(c)
        val landing = delayLanding(c)
        tool.moveBy(100f, 50f)
        val steps = c.undoManager.undoCount
        tool.commit()
        // Duplicate pressed while the move renders: nothing happens yet ...
        assertTrue(ObjectActions.duplicate(c))
        assertEquals(listOf(1L, 2L), ids(c))
        assertEquals(steps, c.undoManager.undoCount)
        // ... and more presses meanwhile don't pile up (one action waits at a time).
        assertFalse(ObjectActions.duplicate(c))
        assertFalse(ObjectActions.delete(c))
        assertEquals(ObjectActions.STILL_UPDATING, c.message)
        // ... and when the move landed, the moved box is copied (two steps: the move, the copy).
        landing()()
        assertEquals(listOf(1L, 3L, 2L), ids(c))
        assertEquals(steps + 2, c.undoManager.undoCount)
        assertEquals(ObjectActions.DUPLICATE_LABEL, c.undoManager.undoLabel)
        val moved = anchorsOf(layer.vector!!.byId(1) as VPath)
        assertEquals(box0.map { it + Vec2(100f, 50f) }, moved)
        assertEquals(moved.map { it + Vec2(16f, 16f) }, anchorsOf(layer.vector!!.byId(3) as VPath))
        assertEquals(setOf(3L), c.vectors.selectedIds)
        assertArrayEquals(kit.render(layer.vector!!), kit.pixels(layer.bitmap))
        c.undo()
        c.undo()
        assertEquals(box0, anchorsOf(layer.vector!!.byId(1) as VPath))
    }

    @Test
    fun anAreaSelectionWhileACommitRendersLooksAtTheLandedObjects() {
        val c = kit.controller()
        val layer = c.vec
        c.vectors.addObjects(layer, listOf(kit.box(40f, 40f, 140f, 120f), kit.stroke(200f, 300f, 300f, 320f)), "Add")
        c.vectors.setSelection(layer, setOf(1L))
        val tool = kit.transform(c)
        val landing = delayLanding(c)
        tool.moveBy(250f, 150f)
        tool.commit()
        c.vectors.setSelection(null, emptySet())
        // An area over where the box goes (it is still at its old place in the data).
        assertTrue(VectorObjectSelection.select(c, kit.rectSelection(280, 180, 400, 280), SelectionMode.REPLACE))
        assertTrue(c.vectors.selectedIds.isEmpty())
        landing()()
        assertEquals(setOf(1L), c.vectors.selectedIds)
        assertNull(c.message)
    }

    @Test
    fun theBoxesShowWhereTheObjectsGoWhileTheirMoveRenders() {
        val c = kit.controller()
        val layer = c.vec
        c.vectors.addObjects(layer, listOf(kit.box(40f, 40f, 140f, 120f), kit.stroke(200f, 300f, 300f, 320f)), "Add")
        c.vectors.setSelection(layer, setOf(1L))
        val b = VectorOps.bounds(layer.vector!!.byId(1)!!)
        val tool = kit.transform(c)
        val landing = delayLanding(c)
        tool.moveBy(200f, 150f)
        tool.commit()
        assertNotNull(VectorLift.landingLift(c))
        val overlay = Bitmap.createBitmap(kit.w, kit.h, Bitmap.Config.ARGB_8888)
        VectorObjectSelection.drawOverlay(c, Canvas(overlay), c.viewTransform)
        fun painted(x: Float, y: Float): Boolean {
            for (dy in -3..3) for (dx in -3..3) {
                val xx = x.toInt() + dx; val yy = y.toInt() + dy
                if (xx in 0 until kit.w && yy in 0 until kit.h && Color.alpha(overlay.getPixel(xx, yy)) > 0) return true
            }
            return false
        }
        assertTrue(painted(b.left + 200f, (b.top + b.bottom) / 2 + 150f))
        assertFalse(painted(b.left, (b.top + b.bottom) / 2))
        landing()()
        assertNull(VectorLift.landingLift(c))
    }

    @Test
    fun deleteFromTheBarWhileTransformingIsOneStep() {
        val c = kit.controller()
        val layer = c.vec
        c.vectors.addObjects(layer, listOf(kit.box(40f, 40f, 140f, 120f), kit.stroke(200f, 200f, 300f, 220f)), "Add")
        val before = layer.vector!!
        val pix = kit.pixels(layer.bitmap)
        c.vectors.setSelection(layer, setOf(1L))
        val tool = kit.transform(c)
        tool.moveBy(60f, 30f)
        val steps = c.undoManager.undoCount
        assertTrue(ObjectActions.delete(c))
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(TransformTool.DELETE_LABEL, c.undoManager.undoLabel)
        assertEquals(listOf(2L), ids(c))
        assertNull(tool.transformState)
        assertTrue(c.vectors.selectedIds.isEmpty())
        assertArrayEquals(kit.render(layer.vector!!), kit.pixels(layer.bitmap))
        // One undo brings the box back where it was.
        c.undo()
        assertSame(before, layer.vector)
        assertArrayEquals(pix, kit.pixels(layer.bitmap))
    }

    @Test
    fun aTapForEveryObjectDoesNotOutliveTheSelectionItWasMadeWith() {
        val c = kit.controller()
        val layer = c.vec
        c.vectors.addObjects(layer, listOf(kit.box(40f, 40f, 140f, 120f), kit.ellipse(260f, 200f), kit.box(400f, 250f, 480f, 330f)), "Add")
        val provider = VectorLift.providerOf(c)
        val tool = kit.transform(c)
        tool.discard()
        // A tap on empty canvas asks for every object ...
        assertTrue(provider.tapped(Vec2(10f, 370f)))
        // ... but objects selected since win.
        c.vectors.setSelection(layer, setOf(2L))
        tool.start()
        assertEquals(setOf(2L), VectorLift.activeLift(c)!!.ids)
        tool.discard()
        // ... and so does a pixel selection made since.
        c.vectors.setSelection(layer, setOf(3L))
        tool.start()
        assertTrue(provider.tapped(Vec2(10f, 370f)))
        tool.discard()
        c.setSelection(kit.rectSelection(30, 30, 150, 130))
        tool.start()
        assertEquals(setOf(1L), VectorLift.activeLift(c)!!.ids)
        tool.discard()
        // Unchanged since the tap: every object.
        c.vectors.setSelection(null, emptySet())
        assertTrue(provider.tapped(Vec2(10f, 370f)))
        tool.start()
        assertEquals(setOf(1L, 2L, 3L), VectorLift.activeLift(c)!!.ids)
        tool.discard()
    }

    @Test
    fun duplicatesStayVisibleOnAZoomedOutCanvas() {
        val c = kit.controller()
        val layer = c.vec
        c.vectors.addObjects(layer, listOf(kit.box(40f, 40f, 140f, 120f)), "Add")
        assertEquals(16f, ObjectActions.duplicateOffset(c), 0f)
        c.viewTransform.set(Matrix().apply { setScale(0.5f, 0.5f) })
        // 16 dp on screen = 32 document px at 50 %.
        assertEquals(32f, ObjectActions.duplicateOffset(c), 0f)
        // Far out: a tenth of the canvas at most (384 / 10).
        c.viewTransform.set(Matrix().apply { setScale(0.1f, 0.1f) })
        assertEquals(38f, ObjectActions.duplicateOffset(c), 0f)
        // Zoomed in: never less than 16 px.
        c.viewTransform.set(Matrix().apply { setScale(4f, 4f) })
        assertEquals(16f, ObjectActions.duplicateOffset(c), 0f)
        c.viewTransform.set(Matrix().apply { setScale(0.5f, 0.5f) })
        val box0 = anchorsOf(layer.vector!!.byId(1) as VPath)
        c.vectors.setSelection(layer, setOf(1L))
        assertTrue(ObjectActions.duplicate(c))
        assertEquals(box0.map { it + Vec2(32f, 32f) }, anchorsOf(layer.vector!!.byId(2) as VPath))
    }
}
