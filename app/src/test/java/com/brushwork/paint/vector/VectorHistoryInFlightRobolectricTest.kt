package com.brushwork.paint.vector

import android.os.Looper
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterCategory
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterMath
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterValues
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.vector.select.ObjectActions
import com.brushwork.paint.vector.select.ObjectTestKit
import com.brushwork.paint.vector.select.PendingRenders
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
 * v1.5 integration (lead): vector edits still rendering in the background (Policy.ASYNC) and the
 * object edits waiting for them land BEFORE undo / redo and before a filter session opens, in the
 * order the user asked for them. Undo then takes back the newest of them, and nothing stale lands
 * on top of what it took back later; the data and the pixels always match (I1).
 */
@RunWith(RobolectricTestRunner::class)
class VectorHistoryInFlightRobolectricTest {
    private val kit = ObjectTestKit()

    @After
    fun tearDown() = kit.close()

    /** Three objects added synchronously, then every re-render goes to the background. */
    private fun drawing(): EditorController {
        val c = kit.controller()
        c.vectors.addObjects(c.vec, listOf(kit.box(40f, 40f, 140f, 120f), kit.ellipse(260f, 200f), kit.box(400f, 250f, 480f, 330f)), "Add")
        c.vectors.policy = VectorLayers.Policy.ASYNC
        return c
    }

    private fun ids(c: EditorController) = c.vec.vector!!.objects.map { it.id }

    /** Lets every background render land (and the polls of the work waiting for them). */
    private fun settle(c: EditorController) {
        repeat(40) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            Thread.sleep(2)
        }
        assertFalse(c.vectors.isRendering)
    }

    private fun assertCacheFresh(c: EditorController) = assertArrayEquals(kit.render(c.vec.vector!!), kit.pixels(c.vec.bitmap))

    @Test
    fun undoWhileATransformCommitRendersTakesBackThatCommitAndNothingLandsLater() {
        val c = kit.controller()
        c.vectors.addObjects(c.vec, listOf(kit.box(40f, 40f, 140f, 120f), kit.ellipse(260f, 200f)), "Add")
        val before = c.vec.vector
        val pixels = kit.pixels(c.vec.bitmap)
        c.vectors.setSelection(c.vec, setOf(1L))
        val tool = kit.transform(c)
        c.vectors.policy = VectorLayers.Policy.ASYNC
        val steps = c.undoManager.undoCount
        tool.flip(horizontal = true)
        tool.moveBy(30f, 10f)
        tool.commit()
        assertTrue("the commit renders in the background", c.vectors.isRendering || c.vec.vector !== before)
        c.undo()
        assertSame("undo took back the transform", before, c.vec.vector)
        assertArrayEquals(pixels, kit.pixels(c.vec.bitmap))
        assertEquals(steps, c.undoManager.undoCount)
        settle(c)
        assertSame("nothing landed after the undo", before, c.vec.vector)
        assertArrayEquals(pixels, kit.pixels(c.vec.bitmap))
        assertNull(c.renderOverride)
        c.redo()
        assertEquals(TransformTool.TRANSFORM_OBJECTS_LABEL, c.undoManager.undoLabel)
        assertTrue(c.vec.vector !== before)
        assertCacheFresh(c)
    }

    @Test
    fun undoTakesBackAnObjectBarActionThatWaitedForARender() {
        val c = drawing()
        val layer = c.vec
        c.vectors.setSelection(layer, setOf(1L))
        val erased = layer.vector!!.without(setOf(3L))
        // A render that is not the Object bar's own (the eraser, the bucket...).
        c.vectors.update(layer, erased, "Erase")
        assertTrue(c.vectors.isRendering)
        // Duplicate pressed meanwhile waits for it.
        assertTrue(ObjectActions.duplicate(c))
        assertTrue(PendingRenders.busy(c))
        // Undo: the erase lands, the duplicate is made, and undo takes back the duplicate.
        c.undo()
        assertEquals(listOf(1L, 2L), ids(c))
        assertEquals("Erase", c.undoManager.undoLabel)
        assertEquals(ObjectActions.DUPLICATE_LABEL, c.undoManager.redoLabel)
        assertCacheFresh(c)
        settle(c)
        assertEquals("no late duplicate", listOf(1L, 2L), ids(c))
        assertCacheFresh(c)
        // Redo brings the duplicate back, undo again takes back the erase.
        c.redo()
        assertEquals(3, ids(c).size)
        assertCacheFresh(c)
        c.undo()
        c.undo()
        assertEquals(listOf(1L, 2L, 3L), ids(c))
        assertCacheFresh(c)
    }

    @Test
    fun redoWhileARenderIsInFlightBringsBackNothingOnTopOfIt() {
        val c = drawing()
        val layer = c.vec
        c.vectors.policy = VectorLayers.Policy.SYNC
        c.vectors.update(layer, layer.vector!!.without(setOf(1L)), "Delete objects")
        c.undo()
        assertTrue(c.canRedo)
        c.vectors.policy = VectorLayers.Policy.ASYNC
        c.vectors.update(layer, layer.vector!!.without(setOf(3L)), "Erase")
        // Redo: the erase lands first (a new step clears what could be redone).
        c.redo()
        assertEquals(listOf(1L, 2L), ids(c))
        assertFalse(c.canRedo)
        assertEquals("Erase", c.undoManager.undoLabel)
        settle(c)
        assertEquals(listOf(1L, 2L), ids(c))
        assertCacheFresh(c)
    }

    /** Inverts RGB. */
    private class InvertFilter : Filter("test_inta_invert", "Test invert", FilterCategory.ADJUST) {
        override val params = emptyList<FilterParam>()
        override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer =
            FilterMath.mapPixels(src, ctx) { c -> ColorUtils.lerp(c, (c and 0xFF000000.toInt()) or (c.inv() and 0xFFFFFF), 1f) }
    }

    @Test
    fun aFilterOpensOnTheResultOfAVectorEditStillRendering() {
        val c = drawing()
        val layer = c.vec
        val erased = layer.vector!!.without(setOf(3L))
        c.vectors.update(layer, erased, "Erase")
        assertTrue(c.vectors.isRendering)
        c.startFilter(InvertFilter())
        val s = c.filterSession
        assertNotNull(s)
        assertFalse("the render landed before the session opened", c.vectors.isRendering)
        assertEquals(listOf(1L, 2L), ids(c))
        assertEquals("Erase", c.undoManager.undoLabel)
        assertCacheFresh(c)
        val erasedPixels = kit.pixels(layer.bitmap)
        s!!.debounceMs = 0
        pumpUntil { s.hasPreview && !s.isRendering }
        s.apply()
        pumpUntil { c.filterSession == null && c.busyMessage == null }
        assertNull(c.filterSession)
        assertNull("applying a filter rasterizes the vector layer", layer.vector)
        assertEquals("Test invert", c.undoManager.undoLabel)
        c.undo()
        assertEquals(listOf(1L, 2L), ids(c))
        assertArrayEquals(erasedPixels, kit.pixels(layer.bitmap))
    }

    private fun pumpUntil(done: () -> Boolean) {
        var waited = 0
        while (!done() && waited++ < 500) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            Thread.sleep(2)
        }
        assertTrue(done())
    }
}
