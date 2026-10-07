package com.brushwork.paint.tools.select

import android.graphics.Path
import android.graphics.Rect
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.CanvasGeometry
import com.brushwork.paint.engine.CanvasOps
import com.brushwork.paint.engine.CanvasRotation
import com.brushwork.paint.engine.CanvasSnapshot
import com.brushwork.paint.engine.Resample
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.SavedSelection
import com.brushwork.paint.model.Selection
import com.brushwork.paint.testing.PerfBudget
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.7 (§3.14, area G): `SavedSelectionOps.mappedForCanvas` keeps the saved selections through a
 * canvas operation: a flip or a quarter turn moves each one's coverage exactly (antialiased
 * edges included), a crop clips them (one cropped to nothing is dropped), Resize image scales
 * them, and an operation that keeps the geometry and the size keeps the very list. A changed
 * entry gets new packed bytes (same id, name and revision); a kept one is the very instance.
 * (Replaces the F2 stub's `SavedSelectionOpsStubTest`.)
 */
@RunWith(RobolectricTestRunner::class)
class SavedSelectionOpsTest {
    private val w = 40
    private val h = 30

    private fun rect(r: Rect, dw: Int = w, dh: Int = h): Selection {
        val p = Path().apply { addRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), Path.Direction.CW) }
        return Selection.fromPath(p, dw, dh, antiAlias = false)
    }

    /** An antialiased disc: coverage values between 0 and 255 on its edge. */
    private fun disc(cx: Float, cy: Float, r: Float): Selection {
        val p = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
        return Selection.fromPath(p, w, h, antiAlias = true)
    }

    private fun snapshot(vararg sels: Selection): CanvasSnapshot {
        val doc = Document("t", "t", w, h)
        doc.layers += Layer(doc.newLayerId(), "L", BitmapUtils.createLayerBitmap(w, h))
        doc.savedSelections = sels.mapIndexed { i, s -> SavedSelection.of(doc.newSelectionId(), "Selection ${i + 1}", s, 1L)!! }
        return CanvasSnapshot.of(doc)
    }

    /** The document-size coverage of [s] (row-major). */
    private fun coverage(s: Selection): ByteArray = BitmapUtils.alpha8ToBytes(s.mask)

    private fun coverage(e: SavedSelection, dw: Int, dh: Int): ByteArray = coverage(e.toSelection(dw, dh))

    /** [src] (dw × dh) mapped by [f]: new (x, y) takes the value of old f(x, y). */
    private fun remap(src: ByteArray, sw: Int, dw: Int, dh: Int, f: (Int, Int) -> Pair<Int, Int>): ByteArray {
        val out = ByteArray(dw * dh)
        for (y in 0 until dh) for (x in 0 until dw) {
            val (sx, sy) = f(x, y)
            out[y * dw + x] = src[sy * sw + sx]
        }
        return out
    }

    @Test
    fun aFlipMovesEachCoverageExactly() {
        val snap = snapshot(rect(Rect(2, 3, 12, 9)), disc(25f, 14f, 7.3f))
        val list = snap.savedSelections
        val mapped = SavedSelectionOps.mappedForCanvas(list, CanvasOps.flip(snap, horizontal = true), w, h)
        assertEquals(2, mapped.size)
        for (i in list.indices) {
            val before = list[i]
            val after = mapped[i]
            assertEquals(before.id, after.id)
            assertEquals(before.name, after.name)
            assertEquals("the controller bumps the revision", before.revision, after.revision)
            assertNotSame("new packed bytes", before.packed, after.packed)
            assertTrue(after.isIntact())
            val expected = remap(coverage(before, w, h), w, w, h) { x, y -> (w - 1 - x) to y }
            assertArrayEquals("entry $i is the flipped mask", expected, coverage(after, w, h))
        }
        assertEquals(Rect(28, 3, 38, 9), mapped[0].bounds)
        // A vertical flip too.
        val v = SavedSelectionOps.mappedForCanvas(list, CanvasOps.flip(snap, horizontal = false), w, h)
        assertArrayEquals(remap(coverage(list[1], w, h), w, w, h) { x, y -> x to (h - 1 - y) }, coverage(v[1], w, h))
    }

    @Test
    fun aQuarterTurnSwapsTheSize() {
        val snap = snapshot(rect(Rect(2, 3, 12, 9)), disc(25f, 14f, 7.3f))
        val list = snap.savedSelections
        val result = CanvasOps.rotate(snap, CanvasRotation.CW_90)
        assertEquals(h, result.width)
        assertEquals(w, result.height)
        val mapped = SavedSelectionOps.mappedForCanvas(list, result, w, h)
        // (x, y) -> (H - y, x): the old pixel under new (X, Y) is (Y, H - 1 - X).
        for (i in list.indices) {
            val expected = remap(coverage(list[i], w, h), w, h, w) { x, y -> y to (h - 1 - x) }
            assertArrayEquals("entry $i turned", expected, coverage(mapped[i], h, w))
        }
        assertEquals(Rect(21, 2, 27, 12), mapped[0].bounds)
    }

    @Test
    fun theSameGeometryAndSizeKeepTheVeryList() {
        val snap = snapshot(rect(Rect(2, 3, 12, 9)))
        val list = snap.savedSelections
        assertSame(list, SavedSelectionOps.mappedForCanvas(list, CanvasOps.cropTo(snap, Rect(0, 0, w, h)), w, h))
        val none = emptyList<SavedSelection>()
        assertSame("nothing saved: the very list", none, SavedSelectionOps.mappedForCanvas(none, CanvasOps.flip(snap, true), w, h))
    }

    @Test
    fun aCropClipsKeepsAndDrops() {
        val snap = snapshot(rect(Rect(2, 3, 12, 9)), rect(Rect(5, 5, 30, 25)), rect(Rect(32, 22, 38, 28)))
        val list = snap.savedSelections
        // At the top-left corner: the geometry stays the identity, the canvas shrinks.
        val small = SavedSelectionOps.mappedForCanvas(list, CanvasOps.cropTo(snap, Rect(0, 0, w - 10, h - 10)), w, h)
        assertEquals("the third one is cropped away", listOf(list[0].id, list[1].id), small.map { it.id })
        assertSame("inside the new canvas: the very entry", list[0], small[0])
        assertEquals("clipped to the new canvas", Rect(5, 5, 30, 20), small[1].bounds)
        assertArrayEquals(coverage(rect(Rect(5, 5, 30, 20), 30, 20)), coverage(small[1], 30, 20))
        // Elsewhere: everything moves by the crop's corner.
        val moved = SavedSelectionOps.mappedForCanvas(list, CanvasOps.cropTo(snap, Rect(4, 4, 36, 26)), w, h)
        assertEquals(3, moved.size)
        assertEquals(Rect(0, 0, 8, 5), moved[0].bounds)
        assertEquals(Rect(1, 1, 26, 21), moved[1].bounds)
        assertEquals(Rect(28, 18, 32, 22), moved[2].bounds)
        assertArrayEquals(coverage(rect(Rect(28, 18, 32, 22), 32, 22)), coverage(moved[2], 32, 22))
    }

    @Test
    fun aLargerCanvasMovesThemWithTheArtwork() {
        val snap = snapshot(rect(Rect(2, 3, 12, 9)))
        val mapped = SavedSelectionOps.mappedForCanvas(snap.savedSelections, CanvasOps.resizeCanvas(snap, 60, 50, 10, 7), w, h)
        assertEquals(Rect(12, 10, 22, 16), mapped[0].bounds)
        assertArrayEquals(coverage(rect(Rect(12, 10, 22, 16), 60, 50)), coverage(mapped[0], 60, 50))
    }

    @Test
    fun resizeImageScalesThem() {
        val snap = snapshot(rect(Rect(2, 3, 12, 9)), rect(Rect(10, 10, 30, 30)))
        val list = snap.savedSelections
        val up = SavedSelectionOps.mappedForCanvas(list, CanvasOps.resizeImage(snap, 80, 60, Resample.BILINEAR), w, h)
        val b = up[0].bounds
        assertTrue("about twice the bounds: $b", Math.abs(b.left - 4) <= 1 && Math.abs(b.top - 6) <= 1 && Math.abs(b.right - 24) <= 1 && Math.abs(b.bottom - 18) <= 1)
        val big = coverage(up[0], 80, 60)
        for (y in 7 until 17) for (x in 5 until 23) assertEquals("inside ($x, $y)", 255, big[y * 80 + x].toInt() and 0xFF)
        assertEquals("outside", 0, big[30 * 80 + 50].toInt())
        val down = SavedSelectionOps.mappedForCanvas(list, CanvasOps.resizeImage(snap, 20, 15, Resample.BILINEAR), w, h)
        val small = coverage(down[1], 20, 15)
        for (y in 6 until 14) for (x in 6 until 14) assertEquals("half size, inside ($x, $y)", 255, small[y * 20 + x].toInt() and 0xFF)
        assertEquals(0, small[0].toInt())
    }

    /** The old document's border pixels reach the new border: a whole-canvas selection stays whole (no darker frame). */
    @Test
    fun resizeImageKeepsAWholeCanvasSelectionWhole() {
        val snap = snapshot(rect(Rect(0, 0, w, h)))
        for ((nw, nh) in listOf(80 to 60, 57 to 41, 20 to 15)) {
            val m = SavedSelectionOps.mappedForCanvas(snap.savedSelections, CanvasOps.resizeImage(snap, nw, nh, Resample.BILINEAR), w, h).single()
            assertEquals("$nw × $nh: the whole canvas", Rect(0, 0, nw, nh), m.bounds)
            val cov = coverage(m, nw, nh)
            val partial = cov.indices.filter { (cov[it].toInt() and 0xFF) != 255 }
            assertTrue("$nw × $nh: every pixel fully selected, not ${partial.take(4).map { "(${it % nw}, ${it / nw})" }}", partial.isEmpty())
        }
    }

    @Test
    fun theMappingIsDeterministic() {
        val snap = snapshot(disc(20f, 15f, 9.6f), rect(Rect(0, 0, w, h)))
        val r = CanvasOps.resizeImage(snap, 57, 41, Resample.BILINEAR)
        val a = SavedSelectionOps.mappedForCanvas(snap.savedSelections, r, w, h)
        val b = SavedSelectionOps.mappedForCanvas(snap.savedSelections, r, w, h)
        assertEquals(a.size, b.size)
        for (i in a.indices) {
            assertEquals(a[i].bounds, b[i].bounds)
            assertArrayEquals(a[i].packed, b[i].packed)
        }
        assertEquals("the whole canvas stays the whole canvas", Rect(0, 0, 57, 41), a[1].bounds)
    }

    @Test
    fun exactMapsAreRecognised() {
        assertTrue(SavedSelectionOps.isExact(CanvasGeometry.flip(true, w, h)))
        assertTrue(SavedSelectionOps.isExact(CanvasGeometry.rotate(CanvasRotation.CCW_90, w, h)))
        assertTrue(SavedSelectionOps.isExact(CanvasGeometry.translate(-3.0, 4.0)))
        assertFalse(SavedSelectionOps.isExact(CanvasGeometry.scale(2.0, 2.0)))
        assertFalse(SavedSelectionOps.isExact(CanvasGeometry.translate(0.5, 0.0)))
        assertNull(SavedSelectionOps.mappedBounds(Rect(2, 2, 5, 5), CanvasGeometry.translate(-10.0, 0.0), w, h))
    }

    @Test
    fun rowsAndThumbnailsReadTheCrop() {
        val snap = snapshot(rect(Rect(2, 3, 12, 9)))
        val e = snap.savedSelections[0]
        val rows = SavedSelectionOps.rows(e)
        assertEquals(10 * 6, rows.size)
        assertTrue(rows.all { (it.toInt() and 0xFF) == 255 })
        val t = SavedSelectionOps.thumbnail(e, w, h, 20, 15)
        assertEquals(255, t[2 * 20 + 2].toInt() and 0xFF)
        assertEquals(0, t[10 * 20 + 15].toInt())
    }

    /** A 512 × 512 document's full-canvas saved selection flips well within the operation's time. */
    @Test
    fun aLargeEntryMapsQuickly() {
        val n = 512
        val doc = Document("t", "t", n, n)
        doc.layers += Layer(doc.newLayerId(), "L", BitmapUtils.createLayerBitmap(n, n))
        val p = Path().apply { addCircle(256f, 256f, 250f, Path.Direction.CW) }
        doc.savedSelections = listOf(SavedSelection.of(doc.newSelectionId(), "Selection 1", Selection.fromPath(p, n, n, antiAlias = true), 1L)!!)
        val snap = CanvasSnapshot.of(doc)
        val result = CanvasOps.flip(snap, horizontal = true)
        SavedSelectionOps.mappedForCanvas(snap.savedSelections, result, n, n)
        val t0 = System.nanoTime()
        SavedSelectionOps.mappedForCanvas(snap.savedSelections, result, n, n)
        val ms = (System.nanoTime() - t0) / 1e6
        assertTrue("flip of a 512 × 512 entry took $ms ms", ms <= PerfBudget.ms(150.0))
    }
}
