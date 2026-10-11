package com.brushwork.paint.vector

import com.brushwork.paint.EditorController
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.vector.select.ObjectTestKit
import com.brushwork.paint.vector.select.vec
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.8 F2 scaffold of I15 (design §3.1, §4.9): committing a symmetric object through
 * `ObjectCopies` is ONE undo step, whatever the number of maps. The maps are taken at the first
 * point (`mapsAt`); affine maps become the object's copies (`fromSymmetry`), a homography bakes
 * one plain object per map (`baked`), added together. The Curve and Shape tools (areas E, F) commit
 * this way; their own tests extend this one.
 */
@RunWith(RobolectricTestRunner::class)
class SymmetricCommitUndoTest {
    private val kit = ObjectTestKit()

    @After
    fun tearDown() = kit.close()

    private fun settle(c: EditorController) {
        c.vectors.flushPending()
        kit.idleUntil { !c.vectors.isRendering }
    }

    /** Commits [src] with the document's symmetry at its first point, as the tools will; the objects added. */
    private fun commit(c: EditorController, src: VObject, x: Float, y: Float): List<VObject> {
        val maps = ObjectCopies.mapsAt(c, x, y)
        val copies = ObjectCopies.fromSymmetry(maps)
        val objects = if (copies != null) listOf(ObjectCopies.withCopies(src, copies)) else ObjectCopies.baked(src, maps)
        c.vectors.addObjects(c.vec, objects, "Curve")
        settle(c)
        return objects
    }

    private fun assertOneStep(c: EditorController, n: Int, commit: () -> List<VObject>) {
        val layer = c.vec
        val before = layer.vector!!
        val pix = kit.pixels(layer.bitmap)
        val steps = c.undoManager.undoCount
        val added = commit()
        assertEquals(n, added.size)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals("Curve", c.undoManager.undoLabel)
        val after = layer.vector!!
        assertEquals(before.objects.size + n, after.objects.size)
        assertArrayEquals(kit.render(after), kit.pixels(layer.bitmap))
        c.undo()
        settle(c)
        assertSame(before, layer.vector)
        assertArrayEquals(pix, kit.pixels(layer.bitmap))
        c.redo()
        settle(c)
        assertSame(after, layer.vector)
    }

    @Test
    fun aMirroredCommitIsOneObjectAndOneStep() {
        val c = kit.controller()
        c.updateSymmetry(SymmetrySettings(type = SymmetryType.MIRROR))
        assertOneStep(c, 1) { commit(c, kit.box(40f, 40f, 120f, 100f), 40f, 40f) }
        val o = c.vec.vector!!.objects.single()
        assertEquals(2, ObjectCopies.count(o))
    }

    @Test
    fun aKaleidoscopeOfManyCopiesIsStillOneStep() {
        val c = kit.controller()
        c.updateSymmetry(SymmetrySettings(type = SymmetryType.KALEIDOSCOPE, divisions = 16))
        assertOneStep(c, 1) { commit(c, kit.ellipse(300f, 150f, 30f, 20f), 300f, 150f) }
        assertEquals(32, ObjectCopies.count(c.vec.vector!!.objects.single()))
    }

    @Test
    fun aPerspectiveCommitBakesPlainObjectsInOneStep() {
        val c = kit.controller()
        c.updateSymmetry(SymmetrySettings(type = SymmetryType.PERSPECTIVE_ARRAY))
        val maps = ObjectCopies.mapsAt(c, kit.w / 2f, kit.h / 2f)
        assertTrue(maps.size >= 2)
        assertNull(ObjectCopies.fromSymmetry(maps))
        assertOneStep(c, maps.size) { commit(c, kit.box(240f, 180f, 270f, 200f), kit.w / 2f, kit.h / 2f) }
        assertTrue(c.vec.vector!!.objects.none { ObjectCopies.hasCopies(it) })
    }

    @Test
    fun withoutSymmetryTheCommitIsTheV17One() {
        val c = kit.controller()
        val src = kit.box(40f, 40f, 120f, 100f)
        assertOneStep(c, 1) { commit(c, src, 40f, 40f) }
        val o = c.vec.vector!!.objects.single()
        assertNotNull(o)
        assertEquals(src.withId(o.id), o)
        assertSame(CopyMaps.NONE, ObjectCopies.of(o))
    }
}
