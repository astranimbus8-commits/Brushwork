package com.brushwork.paint.tools.clone

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.StrokeHook
import com.brushwork.paint.brush.StrokeRecorder
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.CompositeTarget
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.storage.ProjectRepository
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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.math.hypot

/**
 * v1.8 D review (design §3.1 D1, D1b, D4): the clone stamp's copies where the first round of
 * tests did not go: a stroke reaching the canvas edge under turned and tilted maps (no clamped
 * coverage streaks, live or committed), the cap for rulers whose copies all come round as the
 * stroke goes, an alpha-locked layer, the layer mask, and a save and reopen.
 */
@RunWith(RobolectricTestRunner::class)
class CloneMirrorReviewTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

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

    /**
     * The pixels of [after] that changed from [before] although no map's preimage of them lies
     * on the canvas (a pixel of slack) within [reach] of the stroke ([x], [y0]..[y1]): streaks.
     */
    private fun stray(c: EditorController, maps: List<FloatArray>, before: IntArray, after: IntArray, x: Double, y0: Double, y1: Double, reach: Double): List<String> {
        val w = c.doc.width
        val h = c.doc.height
        val inv = maps.map { CloneMirrorKit.inverse(it) }
        val out = ArrayList<String>()
        for (j in 0 until h) for (i in 0 until w) {
            val idx = j * w + i
            if (after[idx] == before[idx]) continue
            val explained = inv.any { m ->
                val p = CloneMirrorKit.apply(m, i + 0.5, j + 0.5)
                val y = p[1].coerceIn(y0, y1)
                p[0] > -1.5 && p[1] > -1.5 && p[0] < w + 1.5 && p[1] < h + 1.5 && hypot(p[0] - x, p[1] - y) < reach
            }
            if (!explained) out += "($i, $j)"
        }
        return out
    }

    @Test
    fun aStrokeAlongTheEdgeLeavesNoStreakInItsTurnedCopies() {
        // Rotation 8 on a square: a copy turned by 45° takes the right edge across the canvas.
        // The coverage beyond the edge would repeat the edge column (CLAMP) over the copy's
        // bounding box: 2356 stray pixels before the review fix.
        val c = setup(scope, 200, 200, 10f)
        pattern(c.activeLayer.bitmap)
        c.updateSymmetry(SymmetrySettings(type = SymmetryType.ROTATION, divisions = 8))
        tool(c).setSource(Vec2(150f, 60f))
        val maps = ObjectCopies.mapsAt(c, 196f, 80f)
        assertEquals(8, maps.size)
        val live = captureLiveAtCommit(c)
        val before = pixels(c.activeLayer.bitmap)
        stroke(c, 196f, 80f, 196f, 120f)
        assertEquals(1, c.undoManager.undoCount)
        val after = pixels(c.activeLayer.bitmap)
        assertTrue("the copies painted", (0 until after.size).count { after[it] != before[it] } > 500)
        val s = stray(c, maps, before, after, 196.0, 80.0, 120.0, 7.0)
        assertEquals("pixels no copy explains: ${s.take(12)}", 0, s.size)
        assertArrayEquals("live == committed", live(), composite(c))
    }

    @Test
    fun aPerspectiveCopyNearTheEdgeShowsOnlyTheCanvasImage() {
        val w = 200
        val h = 140
        val c = setup(scope, w, h, 10f)
        pattern(c.activeLayer.bitmap)
        val quad = listOf(150f, 40f, 196f, 46f, 194f, 100f, 152f, 94f)
        c.updateSymmetry(SymmetrySettings(type = SymmetryType.PERSPECTIVE_ARRAY, quad = quad))
        tool(c).setSource(Vec2(150f, 20f))
        val maps = ObjectCopies.mapsAt(c, 195f, 60f)
        assertTrue("perspective maps: ${maps.size}", maps.size >= 3 && ObjectCopies.isPerspective(maps))
        val live = captureLiveAtCommit(c)
        val before = pixels(c.activeLayer.bitmap)
        stroke(c, 195f, 60f, 195f, 80f)
        assertEquals(1, c.undoManager.undoCount)
        val after = pixels(c.activeLayer.bitmap)
        val s = stray(c, maps, before, after, 195.0, 60.0, 80.0, 7.0)
        assertEquals("pixels no copy explains: ${s.take(12)}", 0, s.size)
        assertArrayEquals("live == committed", live(), composite(c))
    }

    @Test
    fun aKaleidoscopeOverThirtyTwoCopiesIsRefusedWhereverTheStrokeStarts() {
        // Kaleidoscope 17 gives 34 places. Near a corner most of the copies' starts lie off the
        // canvas, but the stroke may go on to the middle, where all of them show: refused
        // (design §3.1 D4). (Rotation stops at 32 divisions, so it never passes the cap.)
        val c = setup(scope, 200, 200, 10f)
        pattern(c.activeLayer.bitmap)
        c.updateSymmetry(SymmetrySettings(type = SymmetryType.KALEIDOSCOPE, divisions = 17))
        tool(c).setSource(Vec2(60f, 140f))
        val maps = ObjectCopies.mapsAt(c, 8f, 8f)
        assertEquals(34, maps.size)
        assertTrue("few copies start on the canvas", CloneTool.copiesOnCanvas(maps, 8f, 8f, 200, 200) <= CloneTool.MAX_MAPS)
        assertEquals(34, CloneTool.copiesCounted(SymmetryType.KALEIDOSCOPE, maps, 8f, 8f, 200, 200))
        val before = pixels(c.activeLayer.bitmap)
        stroke(c, 8f, 8f, 100f, 100f)
        assertEquals(MirrorLabels18.CLONE_TOO_MANY, c.message)
        assertEquals(0, c.undoManager.undoCount)
        assertArrayEquals(before, pixels(c.activeLayer.bitmap))
        // Kaleidoscope 16 (32 places) and Rotation 32 paint from the same corner.
        for ((n, s) in listOf(1 to SymmetrySettings(type = SymmetryType.KALEIDOSCOPE, divisions = 16), 2 to SymmetrySettings(type = SymmetryType.ROTATION, divisions = 32))) {
            c.updateSymmetry(s)
            assertEquals(32, ObjectCopies.mapsAt(c, 8f, 8f).size)
            c.message = null
            stroke(c, 8f, 8f, 100f, 100f)
            assertEquals("${s.type}", n, c.undoManager.undoCount)
            assertTrue(c.message != MirrorLabels18.CLONE_TOO_MANY)
        }
        // The arrays still count the cells the canvas shows (the margin ring is free).
        val lattice = SymmetrySettings(type = SymmetryType.ARRAY, spacingX = 50f, spacingY = 25f)
        c.updateSymmetry(lattice)
        val cells = ObjectCopies.mapsAt(c, 30f, 30f)
        assertTrue(cells.size > CloneTool.MAX_MAPS)
        assertEquals(32, CloneTool.copiesCounted(SymmetryType.ARRAY, cells, 30f, 30f, 200, 200))
    }

    @Test
    fun aPerspectiveArrayRefusalSaysToDragTheCellsCornersOutward() {
        // v1.8 polish: the default cell (a fifth of the shorter side) gives more than 32 copies on
        // any canvas, so a fresh Perspective array refuses every clone stroke. Its refusal says how
        // to fix that: drag the cell's corners outward. Checked here: the corners moved away from
        // the cell's centre fit fewer copies on the canvas (enough of that paints), towards it more.
        val w = 200
        val h = 200
        val c = setup(scope, w, h, 10f)
        pattern(c.activeLayer.bitmap)
        c.updateSymmetry(SymmetrySettings(type = SymmetryType.PERSPECTIVE_ARRAY))
        tool(c).setSource(Vec2(60f, 150f))
        val x = 100f
        val y = 110f
        fun counted() = CloneTool.copiesCounted(SymmetryType.PERSPECTIVE_ARRAY, ObjectCopies.mapsAt(c, x, y), x, y, w, h)
        val default = counted()
        assertTrue("the default cell: $default copies", default > CloneTool.MAX_MAPS)
        val before = pixels(c.activeLayer.bitmap)
        stroke(c, x, y, x + 20f, y)
        assertEquals(MirrorLabels18.CLONE_TOO_MANY_PERSPECTIVE, c.message)
        assertEquals("refused: no step", 0, c.undoManager.undoCount)
        assertArrayEquals("refused: no pixel", before, pixels(c.activeLayer.bitmap))

        // The default cell with each corner [k] times as far from the cell's centre.
        val q = com.brushwork.paint.assist.SymmetryMaps.defaultQuad(w, h)
        val cx = (0 until 4).map { q[2 * it] }.average().toFloat()
        val cy = (0 until 4).map { q[2 * it + 1] }.average().toFloat()
        fun cell(k: Float) = q.mapIndexed { i, v -> if (i % 2 == 0) cx + (v - cx) * k else cy + (v - cy) * k }
        val counts = listOf(0.7f, 1f, 1.5f, 2f, 3f).associateWith { k ->
            c.updateSymmetry(SymmetrySettings(type = SymmetryType.PERSPECTIVE_ARRAY, quad = cell(k)))
            counted()
        }
        println("CloneMirrorReview perspective copies counted by corner distance: $counts")
        assertEquals(default, counts.getValue(1f))
        assertTrue("corners inward: more copies: $counts", counts.getValue(0.7f) > default)
        assertTrue("corners outward: fewer copies: $counts", counts.getValue(1.5f) < default && counts.getValue(2f) < counts.getValue(1.5f))
        val k = counts.entries.first { it.key > 1f && it.value <= CloneTool.MAX_MAPS }.key
        c.updateSymmetry(SymmetrySettings(type = SymmetryType.PERSPECTIVE_ARRAY, quad = cell(k)))
        c.message = null
        stroke(c, x, y, x + 20f, y)
        assertEquals("corners $k times as far: the stroke paints", 1, c.undoManager.undoCount)
        assertTrue(c.message != MirrorLabels18.CLONE_TOO_MANY_PERSPECTIVE)

        // Every other ruler keeps the cap's message (Kaleidoscope 17 and a fine Array: the tests above).
        for (t in SymmetryType.entries - SymmetryType.PERSPECTIVE_ARRAY) assertEquals("$t", MirrorLabels18.CLONE_TOO_MANY, CloneTool.tooManyMessage(t))
        assertEquals(MirrorLabels18.CLONE_TOO_MANY_PERSPECTIVE, CloneTool.tooManyMessage(SymmetryType.PERSPECTIVE_ARRAY))
    }

    @Test
    fun anAlphaLockedLayerKeepsItsAlphaUnderEveryCopy() {
        val w = 200
        val h = 140
        val c = setup(scope, w, h, 12f)
        val layer = c.activeLayer
        pattern(layer.bitmap)
        // Only a band across the middle (y 56..64) is opaque, the rest transparent.
        Canvas(layer.bitmap).apply {
            save(); clipRect(0, 0, w, 56); drawColor(0, android.graphics.PorterDuff.Mode.CLEAR); restore()
            save(); clipRect(0, 64, w, h); drawColor(0, android.graphics.PorterDuff.Mode.CLEAR); restore()
        }
        c.toggleAlphaLock(layer)
        assertTrue(layer.alphaLocked)
        c.updateSymmetry(SymmetrySettings(type = SymmetryType.MIRROR))
        // The source far from the stroke's start (no grab of the ⊕); the mirror copy at x 40..50
        // shows the mirrored source spot (x 130..140), on the canvas.
        tool(c).setSource(Vec2(60f, 60f))
        val before = pixels(layer.bitmap)
        val steps = c.undoManager.undoCount
        stroke(c, 150f, 60f, 160f, 60f)
        assertEquals(steps + 1, c.undoManager.undoCount)
        val after = pixels(layer.bitmap)
        for (i in after.indices) assertEquals("alpha at (${i % w}, ${i / w})", before[i] ushr 24, after[i] ushr 24)
        // The original (x 150..160) and its mirror (x 40..50) both painted inside the band.
        assertTrue("original", (150..160).any { before[60 * w + it] != after[60 * w + it] })
        assertTrue("mirror", (40..50).any { before[60 * w + it] != after[60 * w + it] })
        // Nothing appeared outside the band.
        for (j in 0 until h) if (j < 56 || j >= 64) for (i in 0 until w) assertEquals("($i, $j)", 0, after[j * w + i])
    }

    @Test
    fun onTheLayerMaskEveryCopyClonesTheMasksOwnValues() {
        val w = 200
        val h = 140
        val c = setup(scope, w, h, 12f)
        val layer = c.activeLayer
        pattern(layer.bitmap)
        c.addMask(layer, fromSelection = false)
        assertEquals(EditTarget.MASK, c.editTargetOf(layer))
        val mask = layer.mask
        assertNotNull(mask)
        // Black around the source, dark gray around its mirror image, white elsewhere.
        Canvas(mask!!).drawRect(20f, 40f, 50f, 80f, Paint().apply { color = 0xFF000000.toInt() })
        Canvas(mask).drawRect(150f, 40f, 180f, 80f, Paint().apply { color = 0xFF404040.toInt() })
        c.updateSymmetry(SymmetrySettings(type = SymmetryType.MIRROR))
        tool(c).setSource(Vec2(35f, 60f))
        val content = pixels(layer.bitmap)
        val maskBefore = pixels(mask)
        val steps = c.undoManager.undoCount
        stroke(c, 75f, 60f, 85f, 60f)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertArrayEquals("the content is untouched", content, pixels(layer.bitmap))
        // The original (75..85) copies the black, its mirror (115..125) the mirrored spot's gray.
        for (x in 77..83) assertEquals("original x=$x", 0xFF000000.toInt(), mask.getPixel(x, 60))
        for (x in 117..123) assertEquals("copy x=$x", 0xFF404040.toInt(), mask.getPixel(x, 60))
        c.undo()
        assertArrayEquals("one step", maskBefore, pixels(layer.mask!!))
    }

    @Test
    fun theCopiesSurviveASaveAndReopenAndTheRulerStillApplies() {
        val c = setup(scope, 200, 140, 10f)
        pattern(c.activeLayer.bitmap)
        c.updateSymmetry(SymmetrySettings(type = SymmetryType.KALEIDOSCOPE, divisions = 3))
        tool(c).setSource(Vec2(100f, 35f))
        stroke(c, 122f, 60f, 132f, 60f)
        assertEquals(1, c.undoManager.undoCount)
        val painted = pixels(c.activeLayer.bitmap)
        val app = RuntimeEnvironment.getApplication()
        val repo = ProjectRepository(app)
        runBlocking { repo.save(c.doc, null) }
        val doc = runBlocking { repo.load(c.doc.id) }
        assertTrue("no load warnings: ${doc.loadWarnings}", doc.loadWarnings.isEmpty())
        assertArrayEquals("the copies are pixels: reopened as painted", painted, pixels(doc.layers[1].bitmap))
        assertEquals(c.doc.symmetry, doc.symmetry)
        val d = EditorController(app, doc, scope, c.settings)
        d.viewTransform.set(android.graphics.Matrix())
        d.selectTool(com.brushwork.paint.tools.ToolId.CLONE)
        d.updatePreset(com.brushwork.paint.tools.ToolId.CLONE, c.cloneBrush)
        tool(d).setSource(Vec2(100f, 110f))
        stroke(d, 122f, 80f, 132f, 80f)
        assertEquals(1, d.undoManager.undoCount)
        assertEquals("the reopened ruler gives the stroke its six places", 5, ObjectCopies.mapsAt(d, 122f, 80f).size - 1)
        assertFalse(painted.contentEquals(pixels(doc.layers[1].bitmap)))
        d.dispose()
    }
}
