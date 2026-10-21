package com.brushwork.paint.v110.lead

import android.graphics.Bitmap
import android.graphics.Canvas
import com.brushwork.paint.engine.CanvasSnapshot
import com.brushwork.paint.engine.live.BelowKey
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 F0 (design 3.2 C16 part 1, "Identity checks migrated in F0"; lead, frozen): [Layer]'s
 * `bitmap` and `mask` setters bump [Layer.pixelGeneration] whenever they replace the bitmap by
 * another one (adding or removing a mask too) and never for the same bitmap or a pixel edit in
 * place ([Layer.contentVersion] counts those); [Layer.owns] is true for exactly the layer's own
 * bitmap and mask. The staleness keys that compared bitmap identity in v1.9 now compare the
 * generation: the live adjustments' [BelowKey], [CanvasSnapshot.matches] and the snapshot's own
 * `owns` go stale on every replacement (never fresh where v1.9 was stale), stay fresh when the same
 * bitmap is assigned again, and go stale after a replacement that is undone by assigning the old
 * bitmap back (v1.9 was fresh there: one redraw more, the same pixels).
 */
@RunWith(RobolectricTestRunner::class)
class GenerationReplacesIdentityTest {
    private val made = mutableListOf<Bitmap>()

    @After
    fun recycle() = made.forEach { if (!it.isRecycled) it.recycle() }

    private fun bitmap(): Bitmap = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888).also { made += it }

    private fun doc(layers: Int = 3): Document = Document("gen", "gen", 8, 6).also { d ->
        repeat(layers) { d.layers += Layer(d.newLayerId(), "Layer $it", bitmap()) }
    }

    /** A pixel edit in place, as the tools make it: the same bitmap, a new content version. */
    private fun paint(l: Layer) {
        Canvas(l.bitmap).drawColor(0xFF336699.toInt())
        l.markChanged()
    }

    @Test
    fun theSettersBumpOnlyWhenTheyReplace() {
        val a = bitmap()
        val l = Layer(1L, "L", a)
        assertEquals(0L, l.pixelGeneration)
        l.bitmap = a
        assertEquals("the same bitmap: no bump", 0L, l.pixelGeneration)
        val b = bitmap()
        l.bitmap = b
        assertEquals("another bitmap: bump", 1L, l.pixelGeneration)
        val m = bitmap()
        l.mask = m
        assertEquals("a mask added: bump", 2L, l.pixelGeneration)
        l.mask = m
        assertEquals("the same mask: no bump", 2L, l.pixelGeneration)
        l.mask = bitmap()
        assertEquals("another mask: bump", 3L, l.pixelGeneration)
        l.mask = null
        assertEquals("the mask removed: bump", 4L, l.pixelGeneration)
        l.mask = null
        assertEquals("no mask again: no bump", 4L, l.pixelGeneration)
        val version = l.contentVersion
        paint(l)
        assertEquals("a pixel edit keeps the generation", 4L, l.pixelGeneration)
        assertEquals("and counts as a content version", version + 1, l.contentVersion)
        l.maskEnabled = false
        l.visible = false
        assertEquals("switches are no replacement", 4L, l.pixelGeneration)
        l.bitmap = a
        assertEquals("the old bitmap back is a replacement too", 5L, l.pixelGeneration)
    }

    @Test
    fun ownsIsTrueForTheLayersOwnBitmapAndMaskOnly() {
        val a = bitmap()
        val l = Layer(1L, "L", a)
        val twin = a.copy(Bitmap.Config.ARGB_8888, true).also { made += it }
        assertTrue(l.owns(a))
        assertFalse("null", l.owns(null))
        assertFalse("a copy with the same pixels", l.owns(twin))
        val m = bitmap()
        assertFalse("not yet its mask", l.owns(m))
        l.mask = m
        assertTrue(l.owns(m))
        val b = bitmap()
        l.bitmap = b
        assertTrue(l.owns(b))
        assertFalse("the replaced bitmap", l.owns(a))
        l.mask = null
        assertFalse("the removed mask", l.owns(m))
        assertFalse("another layer's bitmap", l.owns(Layer(2L, "M", bitmap()).bitmap))
    }

    @Test
    fun theLiveBelowKeyGoesStaleOnEveryReplacement() {
        val d = doc(3)
        val key = BelowKey()
        val split = 2
        fun fresh() = key.matches(d, split, false, null)
        fun again() = key.capture(d, split, false)
        again()
        assertTrue(fresh())
        val below = d.layers[1]

        below.bitmap = below.bitmap
        assertTrue("the same bitmap assigned: fresh", fresh())

        below.bitmap = bitmap()
        assertFalse("a replaced bitmap below: stale", fresh())
        again()
        assertTrue(fresh())

        below.mask = bitmap()
        assertFalse("a mask added below: stale", fresh())
        again()
        below.mask = bitmap()
        assertFalse("a mask replaced below: stale", fresh())
        again()
        below.mask = null
        assertFalse("a mask removed below: stale", fresh())
        again()

        paint(d.layers[0])
        assertFalse("a pixel edit below: stale (content version)", fresh())
        again()

        d.layers[2].bitmap = bitmap()
        paint(d.layers[2])
        assertTrue("above the split: fresh", fresh())

        // A replacement taken back by assigning the old bitmap: v1.9's identity matched again;
        // the generation does not (a redraw of the same pixels).
        val old = below.bitmap
        below.bitmap = bitmap()
        below.bitmap = old
        assertFalse("replaced and put back: stale", fresh())
    }

    @Test
    fun aCanvasSnapshotNoLongerMatchesAfterAReplacement() {
        val d = doc(2)
        val l = d.layers[1]
        var snap = CanvasSnapshot.of(d)
        assertTrue(snap.matches(d))
        l.bitmap = l.bitmap
        l.mask = null
        assertTrue("same bitmap, no mask before or after: matches", snap.matches(d))

        l.bitmap = bitmap()
        assertFalse("replaced bitmap", snap.matches(d))
        snap = CanvasSnapshot.of(d)
        l.mask = bitmap()
        assertFalse("mask added", snap.matches(d))
        snap = CanvasSnapshot.of(d)
        paint(l)
        assertFalse("pixel edit", snap.matches(d))
        snap = CanvasSnapshot.of(d)
        assertTrue(snap.matches(d))
    }

    @Test
    fun aLayerSnapshotKeepsItsOwnBitmapsAndGeneration() {
        val d = doc(1)
        val l = d.layers[0]
        l.mask = bitmap()
        val s = CanvasSnapshot.of(d).layers.single()
        val own = l.bitmap
        val mask = l.mask
        assertSame(l, s.layer)
        assertEquals(l.pixelGeneration, s.pixelGeneration)
        assertTrue(s.owns(own))
        assertTrue(s.owns(mask))
        assertFalse(s.owns(null))
        val generation = l.pixelGeneration
        l.bitmap = bitmap()
        assertNotEquals(generation, l.pixelGeneration)
        assertEquals("the snapshot keeps the generation it was taken at", generation, s.pixelGeneration)
        assertTrue("the snapshot still owns the bitmap it holds", s.owns(own))
        assertFalse("not the layer's new bitmap", s.owns(l.bitmap))
        assertTrue(l.owns(l.bitmap))
        assertFalse(l.owns(own))
    }
}
