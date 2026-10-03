package com.brushwork.paint.qa

import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.engine.CanvasOps
import com.brushwork.paint.engine.CanvasRotation
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VectorLayers
import com.brushwork.paint.vector.draw.VectorEraseMode
import com.brushwork.paint.vector.draw.VectorEraserModes
import com.brushwork.paint.vector.select.ObjectActions
import com.brushwork.paint.vector.select.PendingRenders
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
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
import org.robolectric.annotation.Config
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs

/**
 * QA (I1, I3, §4.9 undo): vector edits that render in the background (policy ASYNC, as big edits
 * do on the phone) followed AT ONCE — before the render lands — by undo / redo, a layer
 * operation, a canvas operation, an Object bar action or a save. Nothing may be lost or come back.
 * The render worker is slowed down (each job starts 250 ms late) so the next action surely comes
 * while it is still in flight.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h857dp-xxhdpi")
class VectorAsyncQaTest {
    private lateinit var r: VectorQaRig
    private val c get() = r.c
    private lateinit var vec: Layer
    private val ink = 0xFF1A2A6C.toInt()

    /** A worker that starts each job late (on its own thread). */
    private class SlowWorker(private val delayMs: Long) : CoroutineDispatcher() {
        private val ex = Executors.newSingleThreadExecutor { Thread(it, "slow-vector-worker").apply { isDaemon = true } }
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            ex.execute { Thread.sleep(delayMs); block.run() }
        }
    }

    @After
    fun tearDown() { if (this::r.isInitialized) r.close() }

    /** A new canvas in vector mode, three strokes s1 (y 60), s2 (y 170), s3 (y 260); then ASYNC renders. */
    private fun setup() {
        r = VectorQaRig(480, 320)
        // Long enough that the render is still in flight after the next gesture, also on a slow CI runner.
        c.vectors.workerDispatcher = SlowWorker(PerfBudget.ms(250.0).toLong())
        c.toggleVectorMode()
        r.checkpoint("Vector on")
        vec = c.activeLayer
        r.tool(ToolId.BRUSH)
        c.color = ink
        c.brush = BrushLibrary.defaultBrush.copy(size = 9f)
        r.stroke(40f to 60f, 240f to 70f, 440f to 60f); r.checkpoint("s1")
        r.stroke(40f to 170f, 240f to 180f, 440f to 170f); r.checkpoint("s2")
        r.stroke(40f to 260f, 240f to 270f, 440f to 260f); r.checkpoint("s3")
        c.vectors.policy = VectorLayers.Policy.ASYNC
    }

    private fun strokeAt(y: Float, layer: Layer = vec) = layer.vector!!.objects.filterIsInstance<VStroke>().firstOrNull { abs(it.points.bounds().centerY() - y) < 15f }

    /** Erases s2 with the Object eraser; returns while its render is still in flight. */
    private fun eraseS2Pending() {
        r.tool(ToolId.ERASER)
        VectorEraserModes.setMode(c, VectorEraseMode.OBJECT)
        c.eraser = c.eraser.copy(size = 12f)
        r.stroke(240f to 150f, 240f to 175f, 240f to 200f)
        assertTrue("the erase renders in the background", c.vectors.isRendering)
        assertNotNull("not landed yet", strokeAt(175f))
    }

    @Test
    fun undoAndRedoRightAfterAnEraseNeverBringItBackOrLoseIt() {
        setup()
        val before = r.snapshot()
        val steps = c.undoManager.undoCount
        eraseS2Pending()
        c.undo()
        Smoke.pump(40)
        assertEquals(steps, c.undoManager.undoCount)
        r.assertState("the erase landed and was undone", before)
        assertTrue(c.canRedo)
        c.redo()
        assertTrue(Smoke.pumpUntil(10_000) { !c.vectors.isRendering })
        assertNull("redone: s2 erased", strokeAt(175f))
        r.assertCacheFresh("after redo", vec)
        assertEquals("Erase", c.undoManager.undoLabel)
    }

    @Test
    fun deletingTheLayerRightAfterAnEraseKeepsTheErase() {
        setup()
        eraseS2Pending()
        c.deleteLayer(vec)
        assertTrue(Smoke.pumpUntil(10_000) { !c.vectors.isRendering })
        assertEquals(1, c.doc.layers.size)
        assertEquals("Delete layer", c.undoManager.undoLabel)
        // Undo the deletion: the layer comes back as it was deleted, WITH the erase.
        c.undo()
        Smoke.pump(40)
        assertEquals(2, c.doc.layers.size)
        assertSame(vec, c.doc.layers[1])
        assertNull("the erase is not undone by undoing the deletion", strokeAt(175f))
        r.assertCacheFresh("restored layer", vec)
        assertEquals("Erase", c.undoManager.undoLabel)
        c.undo()
        Smoke.pump(40)
        assertNotNull(strokeAt(175f))
        r.assertCacheFresh("erase undone", vec)
    }

    @Test
    fun hidingOrLockingTheLayerRightAfterAnEraseKeepsTheErase() {
        for (lock in listOf(false, true)) {
            if (this::r.isInitialized) r.close()
            setup()
            eraseS2Pending()
            // The eye (or the lock) in the layer row, tapped at once.
            if (lock) c.toggleLock(vec) else c.toggleVisibility(vec)
            assertTrue(Smoke.pumpUntil(10_000) { !c.vectors.isRendering })
            assertEquals(if (lock) "Lock layer" else "Visibility", c.undoManager.undoLabel)
            if (lock) c.toggleLock(vec) else c.toggleVisibility(vec)
            Smoke.pump(40)
            assertNull("lock=$lock: the erase is kept", strokeAt(175f))
            r.assertCacheFresh("lock=$lock", vec)
            // Undo: the property, the property, then the erase.
            c.undo(); c.undo()
            assertEquals("Erase", c.undoManager.undoLabel)
            c.undo()
            assertNotNull(strokeAt(175f))
            r.assertCacheFresh("lock=$lock erase undone", vec)
        }
    }

    @Test
    fun duplicatingTheLayerRightAfterAnEraseCopiesTheErase() {
        setup()
        eraseS2Pending()
        val copy = c.duplicateLayer(vec)!!
        assertTrue(Smoke.pumpUntil(10_000) { !c.vectors.isRendering })
        assertNull("the original is erased", strokeAt(175f))
        assertNull("the copy is made after the erase", strokeAt(175f, copy))
        assertEquals(vec.vector, copy.vector)
        r.assertCacheFresh("copy", copy)
        assertEquals(listOf("Duplicate layer", "Erase"), listOf(c.undoManager.undoLabel, run { c.undo(); c.undoManager.undoLabel }))
    }

    @Test
    fun aCanvasRotationRightAfterAWaitingObjectBarActionAppliesBoth() {
        setup()
        val s1 = strokeAt(60f)!!
        c.vectors.setSelection(vec, setOf(s1.id))
        eraseS2Pending()
        c.color = 0xFF20A040.toInt()
        // Recolor (Object bar) waits for the erase to land.
        assertTrue(ObjectActions.recolor(c, linesOnly = false))
        assertTrue(PendingRenders.busy(c))
        c.message = null
        assertTrue(CanvasOps.applyRotate(c, CanvasRotation.CW_90))
        assertTrue(Smoke.pumpUntil(20_000) { c.busyMessage == null && !c.vectors.isRendering && c.undoManager.undoLabel == CanvasRotation.CW_90.label })
        Smoke.pump(100)
        assertNull("no 'the drawing changed' refusal: ${c.message}", c.message?.takeIf { it.contains("changed") })
        assertEquals("rotated", 320, c.doc.width)
        val objs = vec.vector!!.objects
        assertEquals("s2 erased", 2, objs.size)
        assertEquals("s1 recolored", 0xFF20A040.toInt(), (vec.vector!!.byId(s1.id) as VStroke).color)
        r.assertCacheFresh("after all three", vec, tolerance = 2, maxOffPermille = 5)
    }

    @Test
    fun objectBarActionsInARowWhileRenderingLandInOrderAndUndoInOrder() {
        setup()
        val s1 = strokeAt(60f)!!
        val s3 = strokeAt(260f)!!
        c.vectors.setSelection(vec, setOf(s1.id, s3.id))
        val start = r.snapshot()
        val steps = c.undoManager.undoCount
        assertTrue(ObjectActions.duplicate(c))
        assertTrue(c.vectors.isRendering)
        // A second action right away waits for the first.
        c.color = 0xFFC02040.toInt()
        assertTrue(ObjectActions.recolor(c, linesOnly = false))
        assertTrue(Smoke.pumpUntil(10_000) { !PendingRenders.busy(c) && !c.vectors.isRendering })
        assertEquals(steps + 2, c.undoManager.undoCount)
        assertEquals(ObjectActions.RECOLOR_LABEL, c.undoManager.undoLabel)
        val copies = c.vectors.selectedIds
        assertEquals(2, copies.size)
        assertTrue("the copies are recolored", copies.all { (vec.vector!!.byId(it) as VStroke).color == 0xFFC02040.toInt() })
        assertEquals("the originals keep their color", ink, (vec.vector!!.byId(s1.id) as VStroke).color)
        r.assertCacheFresh("after both", vec)
        c.undo(); c.undo()
        Smoke.pump(40)
        r.assertState("both undone", start)
    }

    @Test
    fun undoRightAfterATransformCommitTakesBackTheMove() {
        setup()
        val start = r.snapshot()
        r.tool(ToolId.TRANSFORM)
        val t = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        assertTrue(Smoke.pumpUntil(10_000) { t.transformState != null })
        t.setRotation(15.0); t.endNumericEdit()
        t.commit()
        assertTrue("the transform renders in the background", c.vectors.isRendering)
        c.undo()
        assertTrue(Smoke.pumpUntil(10_000) { !c.vectors.isRendering })
        Smoke.pump(40)
        r.assertState("the transform landed and was undone", start)
        assertTrue(c.canRedo)
        c.redo()
        assertTrue(Smoke.pumpUntil(10_000) { !c.vectors.isRendering })
        assertFalse(vec.vector == start.layer(vec.id).vector)
        r.assertCacheFresh("redone transform", vec)
        // The tool lifts the moved objects again on the next touch, not the old ones.
        c.selectTool(ToolId.BRUSH)
        r.tool(ToolId.TRANSFORM)
        assertTrue(Smoke.pumpUntil(10_000) { t.transformState != null })
        assertSame("it lifts the redone content", vec.vector, com.brushwork.paint.vector.lift.VectorLift.activeLift(c)!!.layer.vector)
        t.discard()
    }

    @Test
    fun aSaveWhileAnEditRendersStoresAConsistentLayerAndTheNextSaveTheEdit() {
        setup()
        eraseS2Pending()
        // The autosave runs now (the main thread is in the save: the render can't land meanwhile).
        runBlocking { r.repo.save(c.doc, null) }
        val first = runBlocking { r.repo.load(r.projectId) }
        val v1 = first.layers[1]
        assertTrue(v1.isVectorLayer)
        r.assertCacheFresh("first save", v1)
        assertTrue(Smoke.pumpUntil(10_000) { !c.vectors.isRendering })
        assertNull(strokeAt(175f))
        runBlocking { r.repo.save(c.doc, null) }
        val second = runBlocking { r.repo.load(r.projectId) }
        assertEquals("the edit is saved", vec.vector, second.layers[1].vector)
        r.assertCacheFresh("second save", second.layers[1])
    }
}
