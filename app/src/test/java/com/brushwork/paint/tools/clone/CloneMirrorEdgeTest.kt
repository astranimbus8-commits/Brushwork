package com.brushwork.paint.tools.clone

import android.graphics.Canvas
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.CompositeTarget
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.clone.CloneMirrorKit.checkCopies
import com.brushwork.paint.tools.clone.CloneMirrorKit.pattern
import com.brushwork.paint.tools.clone.CloneMirrorKit.pixels
import com.brushwork.paint.tools.clone.CloneMirrorKit.setup
import com.brushwork.paint.tools.clone.CloneMirrorKit.stroke
import com.brushwork.paint.tools.clone.CloneMirrorKit.tool
import com.brushwork.paint.vector.ObjectCopies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.8 D (design §3.1 D1): clone copies whose source spot is near or beyond the canvas edge.
 * As for the original since v1.5, nothing is copied from outside the canvas: a copy whose
 * mirrored source leaves the canvas paints nothing there (no repeated edge pixels), live and
 * committed, and a stroke is kept as long as any copy samples the canvas.
 */
@RunWith(RobolectricTestRunner::class)
class CloneMirrorEdgeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val w = 200
    private val h = 140

    private fun composite(c: EditorController): IntArray {
        val out = BitmapUtils.createLayerBitmap(w, h)
        c.compositor.drawDocument(Canvas(out), null, target = CompositeTarget.identity(out))
        return pixels(out)
    }

    /** A gradient layer, [ruler] on, the source at [src]. */
    private fun gradient(ruler: SymmetrySettings, src: Vec2): EditorController {
        val c = setup(scope, w, h, 10f)
        pattern(c.activeLayer.bitmap)
        c.updateSymmetry(ruler)
        tool(c).setSource(src)
        return c
    }

    @Test
    fun aCopyWhoseMirroredSourceLeavesTheCanvasPaintsNothingThere() {
        // Rotation 4 about (100, 70); the source near the top-right corner. The quarter turns take
        // it beyond the bottom (155, 155) and the top (45, −15); the half turn keeps it inside.
        val c = gradient(SymmetrySettings(type = SymmetryType.ROTATION, divisions = 4), Vec2(185f, 15f))
        val maps = ObjectCopies.mapsAt(c, 130f, 40f)
        val before = pixels(c.activeLayer.bitmap)
        val shownBefore = composite(c)
        // The live stroke: the copies leaving the canvas are drawn from a region copy.
        c.pointerDown(ToolPoint(130f, 40f))
        for (i in 1..7) c.pointerMove(ToolPoint(130f + i, 40f))
        val live = composite(c)
        assertTrue("a region copy for the copies leaving the canvas", tool(c).source.copyRegionCopies > 0)
        for (k in 0..3) {
            val p = CloneMirrorKit.apply(DoubleArray(9) { maps[k][it].toDouble() }, 133.5, 40.5)
            val i = p[1].toInt() * w + p[0].toInt()
            if (k == 1 || k == 3) assertEquals("live copy $k shows nothing", shownBefore[i], live[i])
            else assertTrue("live copy $k shows the clone", shownBefore[i] != live[i])
        }
        c.pointerUp(ToolPoint(138f, 40f))
        val after = pixels(c.activeLayer.bitmap)
        assertEquals(1, c.undoManager.undoCount)
        val checked = checkCopies(after, before, w, h, maps, 130f, 40f, 138f, 40f, dx = -55, dy = 25, rIn = 2.0, rOut = 9.0, tol = 0)
        for (k in 0..3) assertTrue("copy $k checked: ${checked.toList()}", checked[k] > 20)
        // The quarter turns' places are untouched (their sources are outside the canvas).
        for (k in listOf(1, 3)) {
            val p = CloneMirrorKit.apply(DoubleArray(9) { maps[k][it].toDouble() }, 134.0, 40.0)
            for (y in p[1].toInt() - 3..p[1].toInt() + 3) for (x in p[0].toInt() - 3..p[0].toInt() + 3) {
                assertEquals("copy $k at ($x, $y)", before[y * w + x], after[y * w + x])
            }
        }
    }

    @Test
    fun aMirroredSourceAcrossTheEdgeCopiesOnlyItsInsidePart() {
        // Mirror: the source hugs the right edge (its dabs reach past it), its mirror image hugs
        // the left edge; both the original and the copy copy only what lies inside.
        val c = gradient(SymmetrySettings(type = SymmetryType.MIRROR), Vec2(196f, 70f))
        val maps = ObjectCopies.mapsAt(c, 60f, 70f)
        val before = pixels(c.activeLayer.bitmap)
        stroke(c, 60f, 70f, 70f, 70f)
        val checked = checkCopies(pixels(c.activeLayer.bitmap), before, w, h, maps, 60f, 70f, 70f, 70f, dx = -136, dy = 0, rIn = 2.0, rOut = 9.0, tol = 0)
        assertTrue("both checked: ${checked.toList()}", checked[0] > 20 && checked[1] > 20)
        assertEquals(1, c.undoManager.undoCount)
    }

    @Test
    fun aStrokeWhoseOwnSourceIsOutsideIsKeptWhenACopySamplesTheCanvas() {
        // The source above the canvas: the original copies nothing, but the quarter turns take
        // its source to (180, 70) and (20, 70), inside: those copies paint, in one step.
        val c = gradient(SymmetrySettings(type = SymmetryType.ROTATION, divisions = 4), Vec2(100f, -10f))
        val maps = ObjectCopies.mapsAt(c, 130f, 40f)
        val before = pixels(c.activeLayer.bitmap)
        stroke(c, 130f, 40f, 138f, 40f)
        assertEquals("kept: copies sample the canvas", 1, c.undoManager.undoCount)
        val checked = checkCopies(pixels(c.activeLayer.bitmap), before, w, h, maps, 130f, 40f, 138f, 40f, dx = 30, dy = 50, rIn = 2.0, rOut = 9.0, tol = 0)
        for (k in 0..3) assertTrue("copy $k checked: ${checked.toList()}", checked[k] > 20)
        // The original's place is untouched.
        for (x in 130..138) assertEquals(before[40 * w + x], c.activeLayer.bitmap.getPixel(x, 40))
    }

    @Test
    fun aStrokeWhoseEverySourceIsOutsideLeavesNoStep() {
        val c = gradient(SymmetrySettings(type = SymmetryType.ROTATION, divisions = 4), Vec2(-300f, -300f))
        val before = pixels(c.activeLayer.bitmap)
        stroke(c, 130f, 40f, 138f, 40f)
        assertArrayEquals(before, pixels(c.activeLayer.bitmap))
        assertEquals("nothing copied: no step", 0, c.undoManager.undoCount)
    }

    @Test
    fun allLayersCopiesNothingFromOutsideTheCanvasEither() {
        // The same geometry as the first test, sampling all layers onto an empty layer: where a
        // copy's source is outside the canvas the layer stays transparent.
        val c = setup(scope, w, h, 10f)
        pattern(c.doc.layers[0].bitmap)
        c.updateSymmetry(SymmetrySettings(type = SymmetryType.ROTATION, divisions = 4))
        val t = tool(c)
        t.setSampleAllLayers(true)
        t.setSource(Vec2(185f, 15f))
        val maps = ObjectCopies.mapsAt(c, 130f, 40f)
        val before = pixels(c.activeLayer.bitmap)
        stroke(c, 130f, 40f, 138f, 40f)
        assertEquals(CloneSource.Sample.ALL_LAYERS, t.source.sample)
        val checked = checkCopies(pixels(c.activeLayer.bitmap), before, w, h, maps, 130f, 40f, 138f, 40f, dx = -55, dy = 25, rIn = 2.0, rOut = 9.0, tol = 0)
        for (k in 0..3) assertTrue("copy $k checked: ${checked.toList()}", checked[k] > 20)
        assertTrue("copies 0 and 2 painted", c.activeLayer.bitmap.getPixel(134, 40) != 0 && c.activeLayer.bitmap.getPixel(66, 100) != 0)
        assertEquals("copy 1 transparent", 0, c.activeLayer.bitmap.getPixel(130, 104))
    }
}
