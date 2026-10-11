package com.brushwork.paint.brush

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.assist.SymmetryMaps
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.sin

/**
 * v1.8 F3 (design §4.4): the `maps` seam of [BrushTool.beginPath]. Empty maps (the default) are
 * the v1.7 path stroke byte for byte; mirror maps paint the copy as well, in one undo step, as an
 * exact stroke that is never updated in place (the caller starts it again).
 */
@RunWith(RobolectricTestRunner::class)
class PathStrokeMapsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val w = 320
    private val h = 240

    private fun controller(fill: Int? = null): EditorController {
        val doc = Document("t", "t", w, h)
        val layer = Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(w, h))
        if (fill != null) layer.bitmap.eraseColor(fill)
        doc.layers += layer
        val c = EditorController(context, doc, CoroutineScope(Dispatchers.Unconfined), AppSettings(context))
        c.tools
        c.color = 0xFF3050A0.toInt()
        return c
    }

    private fun EditorController.tool(id: ToolId, preset: BrushPreset): BrushTool {
        selectTool(id)
        updatePreset(id, preset)
        return tools.getValue(id) as BrushTool
    }

    private fun Bitmap.pixels(): IntArray = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }

    /** A wavy path on the left half of the canvas. */
    private fun leftWave(): PathStrokeInput {
        val out = PathStrokeInput()
        for (i in 0 until 200) {
            val t = i / 199f
            out.add(20f + 110f * t, 120f + 50f * sin(t * 7f), 1f)
        }
        return out
    }

    private fun BrushTool.lift(input: PathStrokeInput) =
        onUp(ToolPoint(input.x[input.size - 1], input.y[input.size - 1], input.pressure[input.size - 1], isStylus = true))

    /** Layer pixels of [input] painted by [id] with [preset], [maps] passed explicitly or not at all. */
    private fun paint(id: ToolId, preset: BrushPreset, input: PathStrokeInput, maps: List<FloatArray>?, budget: Float = 0f): IntArray {
        val fill = if (id == ToolId.ERASER) 0xFF20A040.toInt() else null
        val c = controller(fill)
        val t = c.tool(id, preset)
        val started = if (maps == null) t.beginPath(input, 0x5eedL, budget) else t.beginPath(input, 0x5eedL, budget, maps)
        assertTrue(started)
        t.lift(input)
        return c.doc.activeLayer.bitmap.pixels()
    }

    @Test
    fun emptyMapsAreTheV17PathStroke() {
        val input = leftWave()
        val cases = listOf(
            ToolId.BRUSH to BrushLibrary.defaultBrush,
            ToolId.BRUSH to BrushLibrary.all.first { it.id == "pencil" },
            ToolId.ERASER to BrushLibrary.defaultEraser.copy(size = 14f),
            ToolId.SMUDGE to BrushLibrary.defaultSmudge,
        )
        for ((id, preset) in cases) {
            val v17 = paint(id, preset, input, maps = null)
            assertArrayEquals("$id ${preset.name}: empty maps", v17, paint(id, preset, input, maps = emptyList()))
            assertArrayEquals("$id ${preset.name}: empty maps with a draft budget", paint(id, preset, input, maps = null, budget = 50_000f), paint(id, preset, input, maps = emptyList(), budget = 50_000f))
        }
    }

    @Test
    fun mirrorMapsPaintTheCopyInOneStep() {
        val input = leftWave()
        val maps = SymmetryMaps.transforms(SymmetrySettings(type = SymmetryType.MIRROR), w, h, input.x[0], input.y[0])
        assertEquals("the identity and the mirror", 2, maps.size)
        val c = controller()
        val t = c.tool(ToolId.BRUSH, BrushLibrary.defaultBrush.copy(size = 10f))
        val steps = c.undoManager.undoCount
        assertTrue(t.beginPath(input, 0x5eedL, draftBudget = 50_000f, maps = maps))
        assertFalse("a stroke with copies is exact", t.isDraft)
        assertFalse("and is started again rather than updated", t.updatePath(leftWave(), 0f))
        assertTrue(t.beginPath(input, 0x5eedL, maps = maps))
        t.lift(input)
        assertEquals("one undo step", steps + 1, c.undoManager.undoCount)
        val px = c.doc.activeLayer.bitmap.pixels()
        var left = 0
        var mirrored = 0
        for (y in 0 until h) for (x in 0 until w / 2 - 4) {
            if (px[y * w + x] ushr 24 < 128) continue
            left++
            if (px[y * w + (w - 1 - x)] ushr 24 > 0) mirrored++
        }
        assertTrue("the stroke itself is painted ($left px)", left > 500)
        assertTrue("its mirror image is painted too ($mirrored of $left)", mirrored >= left * 95 / 100)
        // Without maps the right half stays empty.
        val plain = paint(ToolId.BRUSH, BrushLibrary.defaultBrush.copy(size = 10f), input, maps = null)
        var right = 0
        for (y in 0 until h) for (x in w / 2 + 4 until w) if (plain[y * w + x] ushr 24 > 0) right++
        assertEquals("no copy without maps", 0, right)
    }

    // ------------------------------------------------------------------ v1.8 D: the body polish

    private fun kaleidoscope2(input: PathStrokeInput): List<FloatArray> =
        SymmetryMaps.transforms(SymmetrySettings(type = SymmetryType.KALEIDOSCOPE, divisions = 2, angleDeg = 90f), w, h, input.x[0], input.y[0])

    /** Every pixel of the shared coverage buffer of [c] that holds coverage. */
    private fun coverageLeft(c: EditorController): Int {
        val b = StrokeResources.of(c).coverage(w, h)
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        return px.count { it ushr 24 != 0 }
    }

    @Test
    fun copiesAreDrawnIntoOneBufferWithNTimesTheOriginalsDabs() {
        val input = leftWave()
        val preset = BrushLibrary.defaultBrush.copy(size = 10f)
        val counts = mutableMapOf<Int, Long>()
        for (maps in listOf(emptyList(), SymmetryMaps.transforms(SymmetrySettings(type = SymmetryType.MIRROR), w, h, input.x[0], input.y[0]), kaleidoscope2(input))) {
            val c = controller()
            val t = c.tool(ToolId.BRUSH, preset)
            val res = StrokeResources.of(c)
            val before = res.stamper.stampCount
            assertTrue(t.beginPath(input, 0x5eedL, maps = maps))
            counts[maps.size] = res.stamper.stampCount - before
            // One coverage buffer for the stroke and all its copies: the controller's own.
            assertTrue(res.hasCoverage)
            assertEquals(w, res.coverage(w, h).width)
            t.onCancel()
        }
        val own = counts.getValue(0)
        assertTrue("the stroke has dabs ($own)", own > 50)
        assertEquals("Mirror: the identity and one copy, 2 x the dabs", 2 * own, counts.getValue(2))
        assertEquals("Kaleidoscope 2: four maps, 4 x the dabs", 4 * own, counts.getValue(4))
    }

    @Test
    fun aDraftBudgetLeavesAStrokeWithCopiesExact() {
        val input = leftWave()
        val preset = BrushLibrary.defaultBrush.copy(size = 10f)
        val maps = kaleidoscope2(input)
        fun paintWith(budget: Float): IntArray {
            val c = controller()
            val t = c.tool(ToolId.BRUSH, preset)
            assertTrue(t.beginPath(input, 0x5eedL, draftBudget = budget, maps = maps))
            assertFalse("$budget: exact", t.isDraft)
            assertFalse("$budget: nothing to refine", t.refinePath(1_000_000f))
            t.lift(input)
            return c.doc.activeLayer.bitmap.pixels()
        }
        val exact = paintWith(0f)
        assertArrayEquals("a 50 000 budget", exact, paintWith(50_000f))
        assertArrayEquals("a tiny budget", exact, paintWith(1f))
        // Without copies the same budget does draw a draft (the budget is real).
        val c = controller()
        val t = c.tool(ToolId.BRUSH, preset)
        assertTrue(t.beginPath(input, 0x5eedL, draftBudget = 50_000f))
        assertTrue("no copies: a draft", t.isDraft)
        t.onCancel()
    }

    @Test
    fun aStrokeWithCopiesLeavesNoCoverageBehind() {
        val input = leftWave()
        val preset = BrushLibrary.defaultBrush.copy(size = 10f)
        val maps = kaleidoscope2(input)
        val c = controller()
        val t = c.tool(ToolId.BRUSH, preset)
        // Cancelled (the preview is started again on every change of the path).
        assertTrue(t.beginPath(input, 0x5eedL, maps = maps))
        assertTrue(coverageLeft(c) > 1000)
        t.onCancel()
        assertEquals("a cancelled stroke clears its copies' coverage too", 0, coverageLeft(c))
        // Started again over a running one, then committed.
        assertTrue(t.beginPath(input, 0x5eedL, maps = maps))
        assertTrue(t.beginPath(input, 0x5eedL, maps = maps))
        t.lift(input)
        assertEquals("a committed stroke clears its copies' coverage too", 0, coverageLeft(c))
        // So a plain stroke across a copy commits only itself: the same as on a fresh editor
        // holding the same pixels.
        val line = PathStrokeInput().also { for (i in 0 until 90) it.add(200f + i, 118f + (i % 7), 1f) }
        val fresh = controller()
        Canvas(fresh.doc.activeLayer.bitmap).drawBitmap(c.doc.activeLayer.bitmap, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) })
        for (e in listOf(c, fresh)) {
            val b = e.tool(ToolId.BRUSH, preset)
            assertTrue(b.beginPath(line, 1L))
            b.lift(line)
        }
        assertArrayEquals("nothing of the copies is committed again", fresh.doc.activeLayer.bitmap.pixels(), c.doc.activeLayer.bitmap.pixels())
    }
}
