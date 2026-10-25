package com.brushwork.paint.v110.a4

import android.os.Looper
import com.brushwork.paint.EditorController
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.filters.FilterSession
import com.brushwork.paint.ui.common.StabilityLabels19
import com.brushwork.paint.v19.a7b.GatedWorker
import com.brushwork.paint.vector.ObjectColorSlots
import com.brushwork.paint.vector.VectorLayers
import com.brushwork.paint.vector.select.ObjectTestKit
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
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * v1.10 (R6, A4 review; I1): ✓ on Change Drawing Color in object mode while the layer's re-render
 * runs in the background (`VectorLayers.update`, async, as on the phone for a large layer). The
 * preview stays shown until the result lands as ONE step. The render can't be stopped, so nothing
 * lands after the panel has gone: Undo meanwhile lands it and takes it back (v1.5's rule for an
 * edit still rendering), leaving it to Redo; Cancel (✕) lands it and closes the panel; Stop and
 * Redo do nothing. An Undo whose wait hits the limit drops the render and is spent (v1.9 A7b):
 * no step lands or is undone, and the panel stays open.
 */
@RunWith(RobolectricTestRunner::class)
class CdcObjectApplyAsyncTest {
    private val kit = ObjectTestKit()

    @After
    fun tearDown() = kit.close()

    private val cdc get() = FilterRegistry.byId("adjust.change_drawing_color")!!

    /** A box, an ellipse and a brush stroke on the vector layer. */
    private fun drawing(): EditorController = kit.controller().also { c ->
        c.vectors.addObjects(c.vec, listOf(kit.box(40f, 40f, 140f, 120f), kit.ellipse(260f, 200f), kit.stroke(60f, 220f, 280f, 330f)), "Add")
    }

    /** The panel open with its preview shown; every re-render from here on runs in the background. */
    private fun start(c: EditorController): FilterSession {
        c.startFilter(cdc)
        val s = c.filterSession!!
        s.debounceMs = 0L
        s.update("color", 0xFF00A040.toInt())
        s.update("amount", 70f)
        assertTrue(kit.idleUntil { s.hasPreview && !s.isRendering && !s.previewStale })
        assertTrue(s.objectMode)
        c.vectors.policy = VectorLayers.Policy.ASYNC
        return s
    }

    /** ✓, its render still running (a render that is done lands on a later main-thread turn). */
    private fun applyPending(c: EditorController, s: FilterSession) {
        val before = c.vec.vector
        s.apply()
        assertTrue("✓ renders in the background", s.isApplying && c.vectors.isRendering)
        assertSame("nothing landed yet", before, c.vec.vector)
        assertNotNull("the preview stays shown", c.renderOverride)
        assertSame(s, c.filterSession)
    }

    /** Lets every background render land. */
    private fun settle(c: EditorController) {
        repeat(40) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            Thread.sleep(2)
        }
        assertFalse(c.vectors.isRendering)
    }

    @Test
    fun theRecolorLandsAsOneStepOnceRendered() {
        val c = drawing()
        val before = c.vec.vector!!
        val pixels = kit.pixels(c.vec.bitmap)
        val steps = c.undoManager.undoCount
        val s = start(c)
        val f = cdc.colorMapper(s.values)!!
        applyPending(c, s)
        // Stop and Redo wait for it.
        s.cancelApply()
        c.redo()
        assertTrue(s.isApplying)
        assertSame(before, c.vec.vector)
        assertTrue(kit.idleUntil { s.isClosed })
        val expected = ObjectColorSlots.map(before, null, f)
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertEquals(cdc.name, c.undoManager.undoLabel)
        assertEquals(expected, c.vec.vector)
        assertTrue(c.vec.isVectorLayer)
        assertArrayEquals("I1", kit.render(expected), kit.pixels(c.vec.bitmap))
        assertNull(c.filterSession)
        assertNull(c.renderOverride)
        c.undo()
        assertSame(before, c.vec.vector)
        assertArrayEquals(pixels, kit.pixels(c.vec.bitmap))
    }

    @Test
    fun undoWhileTheRecolorRendersTakesItBackAndNothingLandsLater() {
        val c = drawing()
        val before = c.vec.vector!!
        val pixels = kit.pixels(c.vec.bitmap)
        val steps = c.undoManager.undoCount
        val s = start(c)
        val f = cdc.colorMapper(s.values)!!
        applyPending(c, s)
        assertFalse("the panel's steps wait", s.canUndo)
        c.undo()
        assertTrue("the panel closed", s.isClosed)
        assertNull(c.filterSession)
        assertNull(c.renderOverride)
        assertSame("Undo took back the recolor", before, c.vec.vector)
        assertArrayEquals(pixels, kit.pixels(c.vec.bitmap))
        assertEquals(steps, c.undoManager.undoCount)
        assertEquals(cdc.name, c.undoManager.redoLabel)
        settle(c)
        assertSame("nothing landed after the Undo", before, c.vec.vector)
        assertArrayEquals(pixels, kit.pixels(c.vec.bitmap))
        assertEquals(steps, c.undoManager.undoCount)
        c.redo()
        val expected = ObjectColorSlots.map(before, null, f)
        assertEquals(expected, c.vec.vector)
        assertArrayEquals(kit.render(expected), kit.pixels(c.vec.bitmap))
    }

    @Test
    fun anUndoWhoseWaitHitsTheLimitIsThatUndoAndThePanelStaysOpen() {
        val gate = GatedWorker()
        try {
            val c = drawing()
            val before = c.vec.vector!!
            val pixels = kit.pixels(c.vec.bitmap)
            val steps = c.undoManager.undoCount
            val s = start(c)
            c.vectors.workerDispatcher = gate
            c.deferredWaitLimitMs = 150L
            applyPending(c, s)
            c.undo()
            assertSame("the panel stays open", s, c.filterSession)
            assertFalse(s.isClosed || s.isApplying)
            assertSame("the render was dropped (I1)", before, c.vec.vector)
            assertArrayEquals(pixels, kit.pixels(c.vec.bitmap))
            assertEquals("no step, and none undone", steps, c.undoManager.undoCount)
            assertEquals(StabilityLabels19.TOO_SLOW.format(cdc.name), c.message)
            gate.open()
            settle(c)
            assertSame("never lands later", before, c.vec.vector)
            assertEquals(steps, c.undoManager.undoCount)
            assertSame(s, c.filterSession)
            s.cancel()
            assertNull(c.filterSession)
            assertSame(before, c.vec.vector)
        } finally {
            gate.close()
        }
    }

    @Test
    fun cancelWhileTheRecolorRendersLandsItFirst() {
        val c = drawing()
        val before = c.vec.vector!!
        val pixels = kit.pixels(c.vec.bitmap)
        val steps = c.undoManager.undoCount
        val s = start(c)
        val f = cdc.colorMapper(s.values)!!
        applyPending(c, s)
        s.cancel()
        assertTrue(s.isClosed)
        assertNull(c.filterSession)
        assertNull(c.renderOverride)
        val expected = ObjectColorSlots.map(before, null, f)
        assertEquals("the recolor landed as its one step", steps + 1, c.undoManager.undoCount)
        assertEquals(cdc.name, c.undoManager.undoLabel)
        assertEquals(expected, c.vec.vector)
        assertArrayEquals(kit.render(expected), kit.pixels(c.vec.bitmap))
        settle(c)
        assertEquals("nothing more lands", steps + 1, c.undoManager.undoCount)
        assertEquals(expected, c.vec.vector)
        c.undo()
        assertSame(before, c.vec.vector)
        assertArrayEquals(pixels, kit.pixels(c.vec.bitmap))
    }
}
