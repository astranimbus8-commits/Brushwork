package com.brushwork.paint.vector

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import com.brushwork.paint.assist.SymmetryMaps
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.vector.select.ObjectTestKit
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
import kotlin.math.abs

/**
 * v1.8 F2 (I15, I17; design §3.1, §4.4): `ObjectCopies`, the one place objects' live copies are
 * read: the symmetry seams (`mapsAt`, `isPerspective`, `fromSymmetry`), the accessors, the
 * expansion into plain pieces, bounds, hits and drawing per copy, transforms (conjugated under an
 * affine map, expanded under a homography), the perspective bake and detaching. An object
 * without copies takes the v1.7 path everywhere (same instances, same values).
 */
@RunWith(RobolectricTestRunner::class)
class ObjectCopiesTest {
    private val kit = ObjectTestKit()

    @After
    fun tearDown() = kit.close()

    /** The Mirror ruler of the kit's 512 × 384 canvas: x ↦ 512 − x. */
    private val mirror = SymmetryMaps.transforms(SymmetrySettings(type = SymmetryType.MIRROR), kit.w, kit.h)
    private val copies = CopyMaps.of(mirror)

    private val box = kit.box(40f, 40f, 140f, 120f).copy(id = 5)
    private val ellipse = kit.ellipse(90f, 250f).copy(id = 6)

    @Test
    fun mapsAtFollowsTheDocumentSymmetry() {
        val c = kit.controller()
        assertTrue("symmetry off: no maps", ObjectCopies.mapsAt(c, 30f, 40f).isEmpty())
        c.updateSymmetry(SymmetrySettings(type = SymmetryType.MIRROR))
        val maps = ObjectCopies.mapsAt(c, 30f, 40f)
        assertEquals(2, maps.size)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), maps[0], 0f)
        val expected = SymmetryMaps.transforms(c.symmetry, c.doc.width, c.doc.height, 30f, 40f)
        for (k in maps.indices) assertArrayEquals(expected[k], maps[k], 0f)
    }

    @Test
    fun affineMapsBecomeCopiesAndAHomographyBakes() {
        assertSame(CopyMaps.NONE, ObjectCopies.fromSymmetry(emptyList()))
        assertFalse(ObjectCopies.isPerspective(mirror))
        assertEquals(copies, ObjectCopies.fromSymmetry(mirror))
        val kaleido = SymmetryMaps.transforms(SymmetrySettings(type = SymmetryType.KALEIDOSCOPE, divisions = 6), kit.w, kit.h)
        assertEquals(12, ObjectCopies.fromSymmetry(kaleido)!!.count)
        val perspective = perspectiveMaps()
        assertTrue(ObjectCopies.isPerspective(perspective))
        assertNull("a homography never becomes a copy", ObjectCopies.fromSymmetry(perspective))
    }

    @Test
    fun accessorsKeepObjectsWithoutCopiesAsTheyAre() {
        val stroke = kit.stroke(30f, 60f, 200f, 80f)
        assertSame(CopyMaps.NONE, ObjectCopies.of(stroke))
        assertSame("strokes keep their v1.7 copies", stroke, ObjectCopies.withCopies(stroke, copies))
        assertFalse(ObjectCopies.hasCopies(box))
        assertEquals(1, ObjectCopies.count(box))
        assertSame(box, ObjectCopies.source(box))
        assertSame(box, ObjectCopies.expanded(box).single())
        val o = ObjectCopies.withCopies(box, copies)
        assertTrue(ObjectCopies.hasCopies(o))
        assertEquals(2, ObjectCopies.count(o))
        assertEquals(box, ObjectCopies.source(o))
        val s = ObjectCopies.withCopies(ellipse, copies) as VShape
        assertEquals(copies, s.shape.copies)
        assertEquals(ellipse, ObjectCopies.source(s))
        assertEquals(ellipse.shape, ObjectCopies.source(s.shape))
    }

    @Test
    fun expandedIsTheSourceThenEachMappedCopy() {
        for (src in listOf<VObject>(box, ellipse)) {
            val pieces = ObjectCopies.expanded(ObjectCopies.withCopies(src, copies))
            assertEquals(2, pieces.size)
            assertEquals(src, pieces[0])
            assertEquals(VectorOps.transformed(src, mirror[1]), pieces[1])
            assertTrue(pieces.all { it.id == src.id && !ObjectCopies.hasCopies(it) })
        }
        val shapePieces = ObjectCopies.expanded(ObjectCopies.withCopies(ellipse, copies).let { (it as VShape).shape }, seed = 9L)
        assertEquals(2, shapePieces.size)
        assertEquals(9L, (shapePieces[0] as VShape).seed)
    }

    @Test
    fun boundsAreTheUnionOfEveryCopy() {
        for (src in listOf<VObject>(box, ellipse)) {
            val o = ObjectCopies.withCopies(src, copies)
            val union = RectF()
            for (p in ObjectCopies.expanded(o)) union.union(VectorOps.bounds(p))
            assertRect(union, VectorOps.bounds(o), 0.01f)
            assertTrue("the copy is on the right", VectorOps.bounds(o).right > kit.w / 2f + 100f)
        }
        val r = RectF(1f, 2f, 3f, 4f)
        assertSame("no copies: the box itself", r, ObjectCopies.mappedBounds(r, CopyMaps.NONE))
        assertEquals(listOf(r), ObjectCopies.eachBounds(r, CopyMaps.NONE))
    }

    @Test
    fun aHitOnAnyCopyHitsTheObject() {
        val o = ObjectCopies.withCopies(box, copies)
        val onCopy = Vec2(kit.w - 90f, 80f)
        assertTrue(VectorOps.hit(o, onCopy, 2f))
        assertFalse(VectorOps.hit(box, onCopy, 2f))
        assertFalse(VectorOps.hit(o, Vec2(kit.w / 2f, 80f), 2f))
        val s = ObjectCopies.withCopies(ellipse, copies) as VShape
        val onShapeCopy = Vec2(kit.w - 90f, 250f)
        assertTrue(VectorOps.hit(s, onShapeCopy, 2f))
        assertTrue(ObjectCopies.hits(s.shape, onShapeCopy, 2f))
        assertFalse(ObjectCopies.hits(ellipse.shape, onShapeCopy, 2f))
    }

    @Test
    fun drawEachDrawsOncePerCopySourceFirst() {
        val b = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(b)
        val depth = canvas.saveCount
        var n = 0
        ObjectCopies.drawEach(canvas, CopyMaps.NONE) { c -> assertSame(canvas, c); assertEquals(depth, c.saveCount); n++ }
        assertEquals(1, n)
        val seen = ArrayList<Int>()
        ObjectCopies.drawEach(canvas, copies) { c -> seen += c.saveCount }
        assertEquals(listOf(depth, depth + 1), seen)
        assertEquals(depth, canvas.saveCount)
        b.recycle()
    }

    @Test
    fun theRenderOfTheCopiesIsTheRenderOfThePieces() {
        val o = ObjectCopies.withCopies(box, copies)
        val s = ObjectCopies.withCopies(ellipse, copies)
        val kept = kit.render(VectorContent(objects = listOf(o, s), nextId = 7))
        val pieces = ObjectCopies.expanded(o) + ObjectCopies.expanded(s)
        val expanded = kit.render(VectorContent(objects = pieces.mapIndexed { i, p -> p.withId(10L + i) }, nextId = 20))
        assertNearlySame(expanded, kept)
        // Something is drawn on both sides of the axis.
        assertTrue(kept[80 * kit.w + 90] != 0 && kept[80 * kit.w + (kit.w - 90)] != 0)
    }

    @Test
    fun anAffineTransformConjugatesTheCopies() {
        val m = floatArrayOf(1.5f, 0f, 30f, 0f, 1.5f, -10f, 0f, 0f, 1f)
        for (src in listOf<VObject>(box, ellipse)) {
            val o = ObjectCopies.withCopies(src, copies)
            val t = VectorOps.transformed(o, m)
            assertTrue(ObjectCopies.hasCopies(t))
            val after = ObjectCopies.expanded(t)
            val expected = ObjectCopies.expanded(o).map { VectorOps.transformed(it, m) }
            assertEquals(expected.size, after.size)
            for (k in after.indices) assertRect(VectorOps.bounds(expected[k]), VectorOps.bounds(after[k]), 0.05f)
            assertEquals(listOf(t), ObjectCopies.transformedAll(o, m))
        }
    }

    @Test
    fun aHomographyExpandsTheCopiesFirst() {
        val h = floatArrayOf(1f, 0.1f, 5f, 0f, 1f, 3f, 0.0004f, 0.0002f, 1f)
        val o = ObjectCopies.withCopies(box, copies)
        val pieces = ObjectCopies.transformedAll(o, h)
        assertEquals(2, pieces.size)
        assertTrue(pieces.none { ObjectCopies.hasCopies(it) })
        val content = VectorContent(objects = listOf(ellipse, o), nextId = 30)
        val mapped = ObjectCopies.mapped(content, h)
        assertEquals(listOf(6L, 5L, 30L), mapped.objects.map { it.id })
        assertEquals(31L, mapped.nextId)
        assertTrue(mapped.objects.none { ObjectCopies.hasCopies(it) })
        // Without copies (or under an affine map) the content maps exactly as in v1.7.
        val plain = VectorContent(objects = listOf(box, ellipse), nextId = 7)
        assertEquals(plain.copy(objects = plain.objects.map { VectorOps.transformed(it, h) }), ObjectCopies.mapped(plain, h))
        val a = floatArrayOf(-1f, 0f, 512f, 0f, 1f, 0f, 0f, 0f, 1f)
        assertEquals(content.copy(objects = content.objects.map { VectorOps.transformed(it, a) }), ObjectCopies.mapped(content, a))
    }

    @Test
    fun theBakeAddsOnePlainObjectPerNonIdentityMap() {
        val maps = perspectiveMaps()
        val baked = ObjectCopies.baked(ObjectCopies.withCopies(box, copies), maps)
        assertEquals(maps.size, baked.size)
        assertEquals(box, baked[0])
        assertTrue(baked.none { ObjectCopies.hasCopies(it) })
        for (k in 1 until maps.size) assertEquals(VectorOps.transformed(box, maps[k]), baked[k])
        assertEquals(listOf<VObject>(box), ObjectCopies.baked(box, emptyList()))
    }

    @Test
    fun detachedReplacesTheObjectByItsPiecesInPlace() {
        val o = ObjectCopies.withCopies(box, copies)
        val content = VectorContent(objects = listOf(ellipse, o, kit.stroke(30f, 300f, 200f, 320f).withId(7)), nextId = 8)
        assertNull("nothing selected has copies", ObjectCopies.detached(content, setOf(6L, 7L)))
        val (after, ids) = ObjectCopies.detached(content, setOf(5L, 7L))!!
        assertEquals(listOf(6L, 5L, 8L, 7L), after.objects.map { it.id })
        assertEquals(9L, after.nextId)
        assertEquals(setOf(5L, 8L, 7L), ids.toSet())
        assertTrue(after.objects.none { ObjectCopies.hasCopies(it) })
        assertEquals(box, after.objects[1])
        assertEquals(VectorOps.transformed(box, mirror[1]).withId(8), after.objects[2])
    }

    @Test
    fun affineOfNormalizesAndRejects() {
        assertNull(ObjectCopies.affineOf(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0.001f, 0f, 1f)))
        val m = ObjectCopies.affineOf(floatArrayOf(2f, 0f, 4f, 0f, 2f, 6f, 0f, 0f, 2f))
        assertNotNull(m)
        assertArrayEquals(floatArrayOf(1f, 0f, 2f, 0f, 1f, 3f, 0f, 0f, 1f), m, 0f)
    }

    // ------------------------------------------------------------------ helpers

    /** The perspective array's maps at the canvas centre (the identity first, then homographies). */
    private fun perspectiveMaps(): List<FloatArray> {
        val maps = SymmetryMaps.transforms(SymmetrySettings(type = SymmetryType.PERSPECTIVE_ARRAY), kit.w, kit.h, kit.w / 2f, kit.h / 2f)
        assertTrue("the perspective array gives copies", maps.size >= 2)
        return maps
    }

    private fun assertRect(expected: RectF, actual: RectF, tol: Float) {
        assertEquals(expected.left, actual.left, tol)
        assertEquals(expected.top, actual.top, tol)
        assertEquals(expected.right, actual.right, tol)
        assertEquals(expected.bottom, actual.bottom, tol)
    }

    private fun assertNearlySame(expected: IntArray, actual: IntArray) {
        assertEquals(expected.size, actual.size)
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
