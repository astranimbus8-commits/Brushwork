package com.brushwork.paint.vector.select

import com.brushwork.paint.EditorController
import com.brushwork.paint.assist.SymmetryMaps
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.ui.editor.HistoryLabels
import com.brushwork.paint.vector.CopyMaps
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorOps
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs

/**
 * v1.8 F2 (I15, §3.1): Detach on the Object bar (`ObjectActions.detachCopies`). The selected
 * objects' live copies become objects of their own in place: ONE undo step
 * [HistoryLabels.DETACH_MIRROR_COPIES] whose undo / redo restore data and pixels together, the
 * source keeping its id and each copy taking a new one, every piece selected, the pixels as
 * before. Nothing with copies selected: nothing to do and no step.
 */
@RunWith(RobolectricTestRunner::class)
class DetachCopiesTest {
    private val kit = ObjectTestKit()

    @After
    fun tearDown() = kit.close()

    private val kaleido = CopyMaps.of(SymmetryMaps.transforms(SymmetrySettings(type = SymmetryType.ROTATION, divisions = 4), kit.w, kit.h))
    private val mirror = CopyMaps.of(SymmetryMaps.transforms(SymmetrySettings(type = SymmetryType.MIRROR), kit.w, kit.h))

    private fun setup(): EditorController {
        val c = kit.controller()
        val box = ObjectCopies.withCopies(kit.box(40f, 40f, 120f, 110f), mirror)
        val ring = ObjectCopies.withCopies(kit.ellipse(200f, 120f, 40f, 30f), kaleido)
        c.vectors.addObjects(c.vec, listOf(box, kit.stroke(30f, 300f, 200f, 330f), ring), "Add")
        settle(c)
        return c
    }

    private fun settle(c: EditorController) {
        c.vectors.flushPending()
        kit.idleUntil { !c.vectors.isRendering }
    }

    @Test
    fun detachIsOneStepAndKeepsThePixels() {
        val c = setup()
        val layer = c.vec
        val before = layer.vector!!
        assertEquals(listOf(1L, 2L, 3L), before.objects.map { it.id })
        val pix = kit.pixels(layer.bitmap)
        c.vectors.setSelection(layer, setOf(1L, 3L))
        assertTrue(ObjectActions.hasCopiesSelected(c))
        val steps = c.undoManager.undoCount
        assertTrue(ObjectActions.detachCopies(c))
        settle(c)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(HistoryLabels.DETACH_MIRROR_COPIES, c.undoManager.undoLabel)
        val after = layer.vector!!
        // The mirrored box's copy follows it; the four turns of the ring follow it.
        assertEquals(listOf(1L, 4L, 2L, 3L, 5L, 6L, 7L), after.objects.map { it.id })
        assertTrue(after.objects.none { ObjectCopies.hasCopies(it) })
        assertEquals(ObjectCopies.source(before.objects[0]), after.objects[0])
        assertEquals(VectorOps.transformed(ObjectCopies.source(before.objects[0]), mirror.maps[1]).withId(4L), after.objects[1])
        assertTrue(after.objects[6] is VShape)
        assertEquals(setOf(1L, 4L, 3L, 5L, 6L, 7L), c.vectors.selectedIds)
        assertFalse("nothing left to detach", ObjectActions.hasCopiesSelected(c))
        // The same picture, now from plain objects.
        assertArrayEquals(kit.render(after), kit.pixels(layer.bitmap))
        assertNearlySame(pix, kit.pixels(layer.bitmap))
        c.undo()
        settle(c)
        assertSame(before, layer.vector)
        assertArrayEquals(pix, kit.pixels(layer.bitmap))
        c.redo()
        settle(c)
        assertSame(after, layer.vector)
        assertArrayEquals(kit.render(after), kit.pixels(layer.bitmap))
    }

    @Test
    fun nothingWithCopiesSelectedRecordsNothing() {
        val c = setup()
        c.vectors.setSelection(c.vec, setOf(2L))
        assertFalse(ObjectActions.hasCopiesSelected(c))
        val steps = c.undoManager.undoCount
        val before = c.vec.vector
        assertFalse(ObjectActions.detachCopies(c))
        settle(c)
        assertEquals(steps, c.undoManager.undoCount)
        assertSame(before, c.vec.vector)
        c.vectors.setSelection(null, emptySet())
        assertFalse(ObjectActions.detachCopies(c))
        assertEquals(steps, c.undoManager.undoCount)
    }

    @Test
    fun detachedContentWithoutCopiesIsTheSameContent() {
        val plain = VectorContent(objects = listOf(kit.box(1f, 1f, 9f, 9f).withId(1)), nextId = 2)
        assertEquals(null, ObjectCopies.detached(plain, setOf(1L)))
    }

    private fun assertNearlySame(expected: IntArray, actual: IntArray) {
        var off = 0
        for (i in expected.indices) {
            val a = expected[i]; val b = actual[i]
            if (a == b) continue
            var d = 0
            for (s in intArrayOf(0, 8, 16, 24)) d = maxOf(d, abs((a ushr s and 0xFF) - (b ushr s and 0xFF)))
            if (d > 8) off++
        }
        assertTrue("$off pixels differ", off <= expected.size / 2000)
    }
}
