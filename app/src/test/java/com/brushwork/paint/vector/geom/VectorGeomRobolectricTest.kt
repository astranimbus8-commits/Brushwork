package com.brushwork.paint.vector.geom

import android.graphics.Rect
import android.graphics.RectF
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorOps
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

/**
 * v1.5 A1: the 128 px spatial index ([ObjectIndex]), tile sets ([TileSet]), what changed between
 * two contents ([ContentDiff.changedObjects], longest-run z-order) and the three-way merge.
 */
@RunWith(RobolectricTestRunner::class)
class VectorGeomRobolectricTest {

    private fun box(l: Float, t: Float, r: Float, b: Float, id: Long = 0) = VPath(
        id, subpaths = listOf(VSubpath(listOf(VAnchor(l, t, true), VAnchor(r, t, true), VAnchor(r, b, true), VAnchor(l, b, true)), closed = true)),
        fill = VPaint.Solid(-1),
    )

    private fun stroke(pts: List<Vec2>, size: Float = 8f, id: Long = 0) = VStroke(
        id, preset = BrushLibrary.defaultBrush.copy(size = size), color = -1, seed = 1, stylus = false,
        points = PackedPoints(pts.map { it.x }.toFloatArray(), pts.map { it.y }.toFloatArray(), FloatArray(pts.size) { 1f }),
    )

    private fun randomContent(n: Int, seed: Int): VectorContent {
        val r = Random(seed)
        val objs = ArrayList<VObject>()
        repeat(n) {
            val x = r.nextFloat() * 3000f - 200f
            val y = r.nextFloat() * 2500f - 200f
            objs += if (r.nextBoolean()) {
                val w = 2f + r.nextFloat() * (if (r.nextInt(20) == 0) 2500f else 120f)
                box(x, y, x + w, y + 2f + r.nextFloat() * 90f)
            } else {
                stroke(List(2 + r.nextInt(6)) { Vec2(x + r.nextFloat() * 200f, y + r.nextFloat() * 200f) }, 2f + r.nextFloat() * 30f)
            }
        }
        return VectorContent.EMPTY.plus(objs).first
    }

    @Test
    fun queriesFindExactlyWhatABruteForceScanFinds() {
        val content = randomContent(600, 3)
        val index = ObjectIndex.of(content)
        assertSame("one index per content instance", index, ObjectIndex.of(content))
        val bounds = content.objects.map { VectorOps.bounds(it) }
        val r = Random(9)
        repeat(300) {
            val x = r.nextFloat() * 3200f - 300f
            val y = r.nextFloat() * 2700f - 300f
            val q = if (it % 3 == 0) RectF(x, y, x + 1f, y + 1f) else RectF(x, y, x + r.nextFloat() * 900f, y + r.nextFloat() * 700f)
            val expected = bounds.indices.filter { i -> !bounds[i].isEmpty && RectF.intersects(bounds[i], q) }.toIntArray()
            assertArrayEquals("query $q", expected, index.query(q))
        }
        // Points with a tolerance.
        repeat(200) {
            val x = r.nextFloat() * 3000f
            val y = r.nextFloat() * 2500f
            val tol = r.nextFloat() * 20f
            val q = RectF(x - tol, y - tol, x + tol, y + tol)
            val brute = bounds.indices.filter { i -> !bounds[i].isEmpty && bounds[i].left <= q.right && q.left <= bounds[i].right && bounds[i].top <= q.bottom && q.top <= bounds[i].bottom }.toSet()
            val got = index.queryPoint(x, y, tol).toSet()
            assertTrue("point ($x, $y) ± $tol misses ${brute - got}", got.containsAll(brute))
        }
        assertEquals(content.objects.size, index.size)
        assertEquals(3L, content.objects[2].id)
        assertEquals(2, index.indexOfId(3L))
        assertEquals(-1, index.indexOfId(99999L))
    }

    @Test
    fun aNewContentReusesTheBoundsOfSharedObjects() {
        val content = randomContent(50, 4)
        val first = ObjectIndex.of(content)
        val edited = content.without(setOf(1L, 2L)).plus(listOf(box(10f, 10f, 20f, 20f))).first
        val second = ObjectIndex.of(edited)
        for (i in 0 until second.size) assertEquals(VectorOps.bounds(edited.objects[i]), second.bounds(i))
        // Same instance: same bounds as the first index measured.
        val shared = content.objects[5]
        assertEquals(first.boundsOf(shared), second.boundsOf(shared))
        assertTrue(second.unionBounds().contains(RectF(10f, 10f, 20f, 20f)))
    }

    @Test
    fun tileSetsCoverWhatObjectsPaintAndMergeIntoRects() {
        val s = TileSet(1000, 700, 256)
        assertEquals(4, s.cols)
        assertEquals(3, s.rows)
        assertTrue(s.isEmpty)
        // A long diagonal stroke marks the tiles along it, not its whole box.
        val diag = stroke(listOf(Vec2(10f, 10f), Vec2(500f, 340f), Vec2(990f, 690f)), size = 6f)
        s.addObject(diag, VectorOps.bounds(diag))
        assertTrue(s.has(0, 0) && s.has(1, 1) && s.has(3, 2))
        assertFalse("the far corner of the box", s.has(3, 0))
        assertTrue("fewer tiles than its box (${s.count})", s.count < 12)
        // Rects are disjoint, cover exactly the tiles, and stay in the document.
        val rects = s.rects()
        var area = 0L
        for (r in rects) { area += r.width().toLong() * r.height(); assertTrue(Rect(0, 0, 1000, 700).contains(r)) }
        assertEquals(s.area(), area)
        for (i in rects.indices) for (j in i + 1 until rects.size) assertFalse(Rect.intersects(rects[i], rects[j]))
        for (row in 0 until s.rows) for (col in 0 until s.cols) {
            val t = s.tileRect(col, row)
            assertEquals("tile $col,$row", s.has(col, row), rects.any { it.contains(t) })
        }
        // Off-canvas rects add nothing; edge tiles are clipped.
        val e = TileSet(1000, 700, 256)
        e.addRect(RectF(-50f, -50f, -1f, -1f))
        e.addRect(RectF(1001f, 0f, 1200f, 100f))
        assertTrue(e.isEmpty)
        e.addRect(RectF(990f, 690f, 995f, 695f))
        assertEquals(listOf(Rect(768, 512, 1000, 700)), e.rects())
        // A full set is one rect.
        val all = TileSet(1000, 700, 256).also { it.addAll() }
        assertEquals(listOf(Rect(0, 0, 1000, 700)), all.rects())
    }

    @Test
    fun aStrokesTilesHoldEveryDabItPaints() {
        // Fast strokes (far-apart points, sharp corners) bulge off their polyline; the tiles of
        // each point's neighbourhood still hold every dab.
        val r = Random(2)
        repeat(30) {
            val pts = List(4 + r.nextInt(5)) { Vec2(r.nextFloat() * 1800f, r.nextFloat() * 1300f) }
            val st = stroke(pts, size = 2f + r.nextFloat() * 10f)
            val s = TileSet(2000, 1500, 256)
            s.addObject(st, VectorOps.bounds(st))
            val d = StrokeHits.dabs(st)
            for (k in 0 until d.size / 3) {
                val x = d[3 * k]; val y = d[3 * k + 1]
                if (x < 0f || y < 0f || x >= 2000f || y >= 1500f) continue
                assertTrue("dab ($x, $y)", s.has((x / 256).toInt(), (y / 256).toInt()))
            }
        }
    }

    @Test
    fun theLongestIncreasingRunKeepsMostObjectsInPlace() {
        assertArrayEquals(booleanArrayOf(), ContentDiff.longestIncreasing(intArrayOf()))
        // Moving the top object to the bottom: only it moved.
        assertArrayEquals(booleanArrayOf(false, true, true, true), ContentDiff.longestIncreasing(intArrayOf(3, 0, 1, 2)))
        val keep = ContentDiff.longestIncreasing(intArrayOf(0, 4, 1, 2, 3))
        assertEquals(4, keep.count { it })
        assertFalse(keep[1])
    }

    @Test
    fun changedObjectsAreTheEditedOnesOnly() {
        val base = VectorContent.EMPTY.plus(List(6) { box(it * 50f, 0f, it * 50f + 40f, 40f) }).first
        assertTrue(ContentDiff.changedObjects(base, base).isEmpty())
        // Move the top object (id 6) to the back: only it is repainted.
        val toBack = base.copy(objects = listOf(base.objects[5]) + base.objects.subList(0, 5))
        assertEquals(listOf(6L), ContentDiff.changedObjects(base, toBack).map { it.id }.distinct())
        // Delete one, replace one, add one.
        val replaced = (base.byId(2) as VPath).copy(fill = VPaint.Solid(0xFF00FF00.toInt()))
        val edited = base.without(setOf(4L)).replaced(mapOf(2L to listOf(replaced))).plus(listOf(box(400f, 0f, 420f, 20f))).first
        val changed = ContentDiff.changedObjects(base, edited)
        assertEquals(setOf(2L, 4L, 7L), changed.map { it.id }.toSet())
        assertEquals("old and new version of the replaced object", 2, changed.count { it.id == 2L })
        // An equal (not identical) object is unchanged.
        val copy = base.copy(objects = base.objects.map { (it as VPath).copy() })
        assertTrue(ContentDiff.changedObjects(base, copy).isEmpty())
    }

    @Test
    fun theThreeWayMergeKeepsBothEdits() {
        val base = VectorContent.EMPTY.plus(List(5) { box(it * 50f, 0f, it * 50f + 40f, 40f) }).first // ids 1..5
        // Theirs (landed first): deleted 2, recolored 3, added 6 on top.
        val recolored = (base.byId(3) as VPath).copy(fill = VPaint.Solid(0xFFFF0000.toInt()))
        val theirs = base.without(setOf(2L)).replaced(mapOf(3L to listOf(recolored))).plus(listOf(box(0f, 100f, 10f, 110f))).first
        // Ours (computed from base): moved 1 to the top, recolored 4, added an object (id 6 too!).
        val ours0 = base.copy(objects = base.objects.drop(1) + base.objects[0])
        val ours = ours0.replaced(mapOf(4L to listOf((base.byId(4) as VPath).copy(opacity = 0.5f)))).plus(listOf(box(200f, 200f, 220f, 220f))).first
        val merged = ContentDiff.merge3(base, ours, theirs)
        val ids = merged.objects.map { it.id }
        assertFalse("deleted by theirs", 2L in ids)
        assertEquals("theirs' recolor kept", recolored, merged.byId(3))
        assertEquals("ours' edit kept", 0.5f, merged.byId(4)!!.opacity)
        assertEquals("theirs' addition kept", 1, merged.objects.count { it.id == 6L })
        assertEquals("all ids unique", ids.size, ids.toSet().size)
        // 1, 3, 4, 5, theirs' 6 and ours' addition, which got a new id (6 was taken).
        assertEquals(6, merged.objects.size)
        assertEquals(setOf(1L, 3L, 4L, 5L, 6L, 7L), ids.toSet())
        assertEquals(200f, (merged.byId(7) as VPath).subpaths[0].anchors[0].x)
        assertEquals(0f, (merged.byId(6) as VPath).subpaths[0].anchors[0].x)
        assertTrue(merged.nextId > ids.max())
        // Ours' order: object 1 above 3, 4 and 5.
        assertTrue(ids.indexOf(1L) > ids.indexOf(5L))
        // Trivial cases.
        assertSame(ours, ContentDiff.merge3(base, ours, base))
        assertSame(theirs, ContentDiff.merge3(base, base, theirs))
    }
}
