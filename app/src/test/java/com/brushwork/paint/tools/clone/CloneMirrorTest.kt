package com.brushwork.paint.tools.clone

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.CoverageSource
import com.brushwork.paint.brush.StrokeHook
import com.brushwork.paint.brush.StrokeRecorder
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.CompositeTarget
import com.brushwork.paint.model.Selection
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.clone.CloneMirrorKit.assertAllChecked
import com.brushwork.paint.tools.clone.CloneMirrorKit.checkCopies
import com.brushwork.paint.tools.clone.CloneMirrorKit.pattern
import com.brushwork.paint.tools.clone.CloneMirrorKit.pixels
import com.brushwork.paint.tools.clone.CloneMirrorKit.setup
import com.brushwork.paint.tools.clone.CloneMirrorKit.stroke
import com.brushwork.paint.tools.clone.CloneMirrorKit.tool
import com.brushwork.paint.ui.common.MirrorLabels18
import com.brushwork.paint.vector.ObjectCopies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.zip.CRC32

/**
 * v1.8 D (design §3.1 D1, §5.1 D "Clone stamp with copies"): under a symmetry ruler the clone
 * stamp draws its one coverage buffer once per map, and copy k clones from the source spot its
 * map mirrors: at a destination q it shows S(q − A_k·o) (S the source, o the offset, A_k the
 * linear part of M_k; M_k·T(−o)·M_k⁻¹ for a perspective map). The original stays exact, copies
 * sample bilinearly; more than 32 maps refuse the stroke; without symmetry the stroke is v1.7's.
 */
@RunWith(RobolectricTestRunner::class)
class CloneMirrorTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val w = 200
    private val h = 140

    // The canvas centre is (100, 70). The stroke runs 22..32 px right of it and 10 px above, the
    // source 35 px above the centre (beyond the ⊕'s grab radius from the stroke's start): every
    // copy of both lands inside the canvas, apart from the others (rotation 8: 18 px apart at the
    // stroke's start, more than two brush radii).
    private val ax = 122f
    private val ay = 60f
    private val bx = 132f
    private val by = 60f
    private val source = Vec2(100f, 35f)
    private val dx = 22
    private val dy = 25

    private fun gradientController(ruler: SymmetrySettings, size: Float = 10f): EditorController {
        val c = setup(scope, w, h, size)
        pattern(c.activeLayer.bitmap)
        c.updateSymmetry(ruler)
        tool(c).setSource(source)
        return c
    }

    /** The document as shown (with the live stroke). */
    private fun composite(c: EditorController): IntArray {
        val out = BitmapUtils.createLayerBitmap(c.doc.width, c.doc.height)
        c.compositor.drawDocument(Canvas(out), null, target = CompositeTarget.identity(out))
        return pixels(out)
    }

    /** Replaces the commit gate with one recording the document as shown right before the commit. */
    private fun captureLiveAtCommit(c: EditorController): () -> IntArray? {
        var live: IntArray? = null
        tool(c).brush.strokeHook = {
            StrokeHook.Record(object : StrokeRecorder {
                override val replacesStroke: Boolean get() = false
                override val ignoresSelection: Boolean get() = false
                override fun point(x: Float, y: Float, rawPressure: Float) {}
                override fun commit(label: String, bounds: Rect, commitPixels: () -> Boolean): Boolean {
                    live = composite(c)
                    return commitPixels()
                }
                override fun cancel() {}
            })
        }
        return { live }
    }

    private fun checkRuler(ruler: SymmetrySettings, tol: Int, expectMaps: Int? = null) {
        val c = gradientController(ruler)
        val maps = ObjectCopies.mapsAt(c, ax, ay)
        expectMaps?.let { assertEquals("${ruler.type}: maps", it, maps.size) }
        assertTrue("${ruler.type}: at most 32 maps (${maps.size})", maps.size in 2..CloneTool.MAX_MAPS)
        val before = pixels(c.activeLayer.bitmap)
        stroke(c, ax, ay, bx, by)
        assertEquals("${ruler.type}: one step", 1, c.undoManager.undoCount)
        val checked = checkCopies(pixels(c.activeLayer.bitmap), before, w, h, maps, ax, ay, bx, by, dx, dy, rIn = 2.0, rOut = 9.0, tol = tol)
        assertAllChecked("${ruler.type}", checked, 20)
    }

    @Test
    fun everyMirrorCopyClonesTheMirroredSourceSpotExactly() {
        checkRuler(SymmetrySettings(type = SymmetryType.MIRROR), tol = 0, expectMaps = 2)
    }

    @Test
    fun kaleidoscopeAndRotationCopiesCloneTheirOwnSourceSpots() {
        // Kaleidoscope 2 (two mirrors and a half turn) and Rotation 4 map pixels onto pixels:
        // exact. Rotation 8 turns by 45°: bilinear, within a level per channel and rounding.
        checkRuler(SymmetrySettings(type = SymmetryType.KALEIDOSCOPE, divisions = 2, angleDeg = 90f), tol = 0, expectMaps = 4)
        checkRuler(SymmetrySettings(type = SymmetryType.ROTATION, divisions = 4), tol = 0, expectMaps = 4)
        checkRuler(SymmetrySettings(type = SymmetryType.ROTATION, divisions = 8), tol = 2, expectMaps = 8)
    }

    @Test
    fun arrayCopiesCloneFromTheirOwnCellsSameOffset() {
        // An array copy is a translation (A_k = I): it reads S(q − o), the source moved with it.
        val c = gradientController(SymmetrySettings(type = SymmetryType.ARRAY, spacingX = 80f, spacingY = 70f))
        val maps = ObjectCopies.mapsAt(c, ax, ay)
        assertTrue("array maps: ${maps.size}", maps.size in 3..CloneTool.MAX_MAPS)
        val before = pixels(c.activeLayer.bitmap)
        stroke(c, ax, ay, bx, by)
        val checked = checkCopies(pixels(c.activeLayer.bitmap), before, w, h, maps, ax, ay, bx, by, dx, dy, rIn = 2.0, rOut = 9.0, tol = 0)
        assertTrue("the original", checked[0] > 20)
        assertTrue("copies inside the canvas are checked: ${checked.toList()}", checked.count { it > 20 } >= 3)
    }

    @Test
    fun perspectiveCopiesCloneThroughTheirMapsBilinearly() {
        // A mild perspective with large cells: 30 maps.
        val quad = listOf(60f, 40f, 140f, 42f, 138f, 100f, 62f, 98f)
        val c = gradientController(SymmetrySettings(type = SymmetryType.PERSPECTIVE_ARRAY, quad = quad))
        val maps = ObjectCopies.mapsAt(c, ax, ay)
        assertTrue("perspective maps: ${maps.size}", maps.size in 3..CloneTool.MAX_MAPS)
        assertTrue(ObjectCopies.isPerspective(maps))
        val before = pixels(c.activeLayer.bitmap)
        stroke(c, ax, ay, bx, by)
        assertEquals(1, c.undoManager.undoCount)
        val checked = checkCopies(pixels(c.activeLayer.bitmap), before, w, h, maps, ax, ay, bx, by, dx, dy, rIn = 2.0, rOut = 9.0, tol = 3)
        assertTrue("the original", checked[0] > 20)
        assertTrue("some copies are checked: ${checked.toList()}", checked.drop(1).count { it > 5 } >= 2)
    }

    @Test
    fun aMirrorCopyReadsTheMirroredSpotNotTheSourceItself() {
        val c = setup(scope, w, h, size = 20f)
        val layer = c.activeLayer
        val red = 0xFFFF0000.toInt()
        val blue = 0xFF0000FF.toInt()
        val paint = Paint()
        // Red around the source, blue around its mirror image (x ↦ 200 − x).
        Canvas(layer.bitmap).drawRect(20f, 40f, 50f, 70f, paint.apply { color = red })
        Canvas(layer.bitmap).drawRect(150f, 40f, 180f, 70f, paint.apply { color = blue })
        c.updateSymmetry(SymmetrySettings(type = SymmetryType.MIRROR))
        tool(c).setSource(Vec2(35f, 55f))
        stroke(c, 75f, 55f, 85f, 55f)
        // The original (75..85) clones the red; its copy (115..125) clones the mirrored spot, blue.
        for (x in 75..85) assertEquals("original x=$x", red, layer.bitmap.getPixel(x, 55))
        for (x in 115..125) assertEquals("copy x=$x", blue, layer.bitmap.getPixel(x, 55))
        assertEquals("between them nothing", 0, layer.bitmap.getPixel(100, 55))
        assertEquals("the sources are untouched", red, layer.bitmap.getPixel(35, 55))
        assertEquals(blue, layer.bitmap.getPixel(165, 55))
        // The ⊕ moves with the original only (Aligned): to what the original's end sampled.
        assertEquals(Vec2(45f, 55f), tool(c).anchor.source)
    }

    @Test
    fun theLiveStrokeShowsTheCopiesExactlyAsTheyCommit() {
        val c = gradientController(SymmetrySettings(type = SymmetryType.ROTATION, divisions = 8))
        val live = captureLiveAtCommit(c)
        val before = composite(c)
        stroke(c, ax, ay, bx, by)
        val shown = live()
        assertNotNull(shown)
        val done = composite(c)
        assertFalse("the copies changed the document", before.contentEquals(done))
        assertArrayEquals("live == committed", shown, done)
        assertNull(c.renderOverride)
    }

    @Test
    fun theOriginalAndAllItsCopiesAreOneUndoStep() {
        val c = gradientController(SymmetrySettings(type = SymmetryType.KALEIDOSCOPE, divisions = 3))
        val layer = c.activeLayer
        val before = pixels(layer.bitmap)
        stroke(c, ax, ay, bx, by)
        val after = pixels(layer.bitmap)
        assertEquals(1, c.undoManager.undoCount)
        assertEquals(CloneTool.UNDO_LABEL, c.undoManager.undoLabel)
        // Six places changed: each is far from the others.
        val m = ObjectCopies.mapsAt(c, ax, ay)
        assertEquals(6, m.size)
        for (k in m.indices) {
            val p = CloneMirrorKit.apply(DoubleArray(9) { m[k][it].toDouble() }, 127.5, 60.5)
            val i = p[1].toInt() * w + p[0].toInt()
            assertTrue("copy $k at (${p[0]}, ${p[1]}) changed", before[i] != after[i])
        }
        c.undo()
        assertArrayEquals("undo removes every copy", before, pixels(layer.bitmap))
        c.redo()
        assertArrayEquals("redo puts every copy back", after, pixels(layer.bitmap))
    }

    @Test
    fun moreThan32MapsRefuseTheStroke() {
        // Kaleidoscope 17: 34 maps.
        val c = gradientController(SymmetrySettings(type = SymmetryType.KALEIDOSCOPE, divisions = 17))
        assertEquals(34, ObjectCopies.mapsAt(c, ax, ay).size)
        val before = pixels(c.activeLayer.bitmap)
        stroke(c, ax, ay, bx, by)
        assertEquals(MirrorLabels18.CLONE_TOO_MANY, c.message)
        assertArrayEquals("nothing painted", before, pixels(c.activeLayer.bitmap))
        assertEquals("no step", 0, c.undoManager.undoCount)
        assertFalse(tool(c).isPainting)
        assertNull(c.renderOverride)
        assertFalse(tool(c).source.isActive)
        assertNull("a refused stroke fixes no offset", tool(c).anchor.fixed)
        // A fine array is refused the same way.
        c.updateSymmetry(SymmetrySettings(type = SymmetryType.ARRAY, spacingX = 20f, spacingY = 20f))
        assertTrue(ObjectCopies.mapsAt(c, ax, ay).size > CloneTool.MAX_MAPS)
        stroke(c, ax, ay, bx, by)
        assertEquals(0, c.undoManager.undoCount)
        // Exactly 32 is fine: Rotation 32 and Kaleidoscope 16.
        for (ruler in listOf(SymmetrySettings(type = SymmetryType.ROTATION, divisions = 32), SymmetrySettings(type = SymmetryType.KALEIDOSCOPE, divisions = 16))) {
            val d = gradientController(ruler)
            assertEquals(32, ObjectCopies.mapsAt(d, ax, ay).size)
            val b = pixels(d.activeLayer.bitmap)
            stroke(d, ax, ay, bx, by)
            assertEquals("${ruler.type} 32 maps paint", 1, d.undoManager.undoCount)
            assertFalse(b.contentEquals(pixels(d.activeLayer.bitmap)))
            assertTrue(d.message != MirrorLabels18.CLONE_TOO_MANY)
        }
    }

    @Test
    fun anArrayCountsTheCopiesOnTheCanvasNotItsMarginRing() {
        // §6.3 Q3: a 4 × 8 lattice is 32 copies. On 256 px, 64 × 32 px cells about the centre fill
        // the canvas 4 × 8; the margin ring around it adds maps that only paint where a stroke
        // crosses into them, and they don't count. 6 × 6 cells are 36 copies: refused.
        val c = setup(scope, 256, 256, 10f)
        pattern(c.activeLayer.bitmap)
        c.updateSymmetry(SymmetrySettings(type = SymmetryType.ARRAY, spacingX = 64f, spacingY = 32f))
        tool(c).setSource(Vec2(66f, 50f))
        val maps = ObjectCopies.mapsAt(c, 100f, 50f)
        assertTrue("the margin ring adds maps: ${maps.size}", maps.size > CloneTool.MAX_MAPS)
        assertEquals(32, CloneTool.copiesOnCanvas(maps, 100f, 50f, 256, 256))
        val before = pixels(c.activeLayer.bitmap)
        stroke(c, 100f, 50f, 110f, 50f)
        assertTrue(c.message != MirrorLabels18.CLONE_TOO_MANY)
        assertEquals("32 copies paint", 1, c.undoManager.undoCount)
        val after = pixels(c.activeLayer.bitmap)
        // Every cell's copy (same offset: the source moved with it) painted; the far corner's too.
        for (i in 0 until 4) for (j in 0 until 8) {
            val x = 105 + (i - 1) * 64
            val y = 50 + (j - 1) * 32
            assertTrue("cell ($i, $j) at ($x, $y)", before[y * 256 + x] != after[y * 256 + x])
        }
        c.updateSymmetry(SymmetrySettings(type = SymmetryType.ARRAY, spacingX = 256f / 6, spacingY = 256f / 6))
        val six = ObjectCopies.mapsAt(c, 100f, 50f)
        assertEquals(36, CloneTool.copiesOnCanvas(six, 100f, 50f, 256, 256))
        stroke(c, 100f, 50f, 110f, 50f)
        assertEquals(MirrorLabels18.CLONE_TOO_MANY, c.message)
        assertEquals("refused: no second step", 1, c.undoManager.undoCount)
    }

    /** The document's pixels after one stroke (symmetry off), through [useV17Interface] the plain CoverageSource. */
    private fun offStroke(useV17Interface: Boolean): Pair<IntArray, IntArray?> {
        val c = gradientController(SymmetrySettings(), size = 14f)
        val t = tool(c)
        if (useV17Interface) t.brush.coverageSource = object : CoverageSource by t.source {}
        val live = captureLiveAtCommit(c)
        stroke(c, 40f, 30f, 150f, 110f, steps = 12)
        assertEquals(0, t.source.commitCopiesTaken)
        assertEquals(1, c.undoManager.undoCount)
        return pixels(c.activeLayer.bitmap) to live()
    }

    @Test
    fun withoutSymmetryTheStrokeIsBitIdenticalToV17() {
        val (pixels, live) = offStroke(useV17Interface = false)
        val (v17Pixels, v17Live) = offStroke(useV17Interface = true)
        assertArrayEquals("committed", v17Pixels, pixels)
        assertArrayEquals("live", v17Live, live)
        // The v1.7 build's result for this stroke (Windows Skia).
        assumeTrue(System.getProperty("os.name").orEmpty().startsWith("Windows"))
        val crc = CRC32()
        for (p in pixels) {
            crc.update(p ushr 24); crc.update(p ushr 16); crc.update(p ushr 8); crc.update(p)
        }
        assertEquals(V17_CRC, crc.value)
    }

    @Test
    fun allLayersFillsTheSnapshotWhereverACopySamples() {
        // 512 px: the snapshot has 2 × 2 tiles. The original samples the left one, its mirror
        // copy the right one (which the original's path never comes near).
        val c = setup(scope, 512, 512, size = 10f)
        val bg = c.doc.layers[0].bitmap
        val paint = Paint()
        val blue = 0xFF0000FF.toInt()
        val red = 0xFFFF0000.toInt()
        Canvas(bg).drawRect(0f, 0f, 256f, 512f, paint.apply { color = blue })
        Canvas(bg).drawRect(256f, 0f, 512f, 512f, paint.apply { color = red })
        c.updateSymmetry(SymmetrySettings(type = SymmetryType.MIRROR))
        val t = tool(c)
        t.setSampleAllLayers(true)
        t.setSource(Vec2(160f, 100f))
        stroke(c, 300f, 100f, 320f, 100f)
        assertEquals(CloneSource.Sample.ALL_LAYERS, t.source.sample)
        val layer = c.activeLayer.bitmap
        for (x in 302..318) assertEquals("original x=$x clones the left half", blue, layer.getPixel(x, 100))
        // The copy lands at 512 − x (192..212) and samples 140 px right of it: the red half.
        for (x in 194..210) assertEquals("copy x=$x clones the right half", red, layer.getPixel(x, 100))
        assertEquals(0, layer.getPixel(256, 100))
        assertEquals(1, c.undoManager.undoCount)
    }

    @Test
    fun theSelectionClipsTheCopies() {
        val c = gradientController(SymmetrySettings(type = SymmetryType.MIRROR))
        val layer = c.activeLayer
        // Only the right half is selected: the original (x 122..132) paints, its copy (68..78) not.
        val a8 = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        Canvas(a8).drawRect(100f, 0f, w.toFloat(), h.toFloat(), Paint().apply { color = 0xFF000000.toInt() })
        c.setSelection(Selection.wrap(a8, Rect(100, 0, w, h)), recordUndo = false)
        val before = pixels(layer.bitmap)
        stroke(c, ax, ay, bx, by)
        val after = pixels(layer.bitmap)
        assertTrue("the original paints", before[60 * w + 127] != after[60 * w + 127])
        for (i in 0 until h * w) if (i % w < 100) assertEquals("outside the selection (${i % w}, ${i / w})", before[i], after[i])
        assertEquals(1, c.undoManager.undoCount)
    }

    @Test
    fun overlappingCopiesBothComposite() {
        // A half turn about the centre maps a stroke through it onto itself: at half opacity the
        // two layers of paint add up (documented: copies are composited one after the other).
        fun alphaAtCentre(ruler: SymmetrySettings): Int {
            val c = setup(scope, w, h, size = 16f, opacity = 0.5f)
            Canvas(c.doc.layers[0].bitmap).drawColor(0xFF00FF00.toInt())
            c.updateSymmetry(ruler)
            val t = tool(c)
            t.setSampleAllLayers(true)
            t.setSource(Vec2(60f, 30f))
            stroke(c, 80f, 70f, 120f, 70f)
            return c.activeLayer.bitmap.getPixel(100, 70) ushr 24
        }
        val single = alphaAtCentre(SymmetrySettings())
        val doubled = alphaAtCentre(SymmetrySettings(type = SymmetryType.ROTATION, divisions = 2))
        assertTrue("one layer: $single", single in 124..132)
        assertTrue("two layers: $doubled", doubled in 187..195)
    }

    @Test
    fun aSecondFingerLeavesNoCopiesBehind() {
        val c = gradientController(SymmetrySettings(type = SymmetryType.ROTATION, divisions = 6))
        val layer = c.activeLayer
        val before = pixels(layer.bitmap)
        val shownBefore = composite(c)
        c.pointerDown(ToolPoint(ax, ay))
        c.pointerMove(ToolPoint(bx, by))
        assertTrue(tool(c).isPainting)
        assertEquals(5, tool(c).source.copyCount)
        assertFalse("the live stroke shows", shownBefore.contentEquals(composite(c)))
        c.pointerCancel()
        assertArrayEquals(before, pixels(layer.bitmap))
        assertArrayEquals("nothing left on screen", shownBefore, composite(c))
        assertEquals(0, c.undoManager.undoCount)
        assertNull(c.renderOverride)
        assertEquals(0, tool(c).source.copyCount)
    }

    companion object {
        /** The CRC of [withoutSymmetryTheStrokeIsBitIdenticalToV17]'s pixels, computed with the v1.7 code (4f923db2). */
        const val V17_CRC = 262178705L
    }
}
