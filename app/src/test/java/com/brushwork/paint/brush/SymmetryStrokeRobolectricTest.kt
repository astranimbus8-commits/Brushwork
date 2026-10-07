package com.brushwork.paint.brush

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.assist.SymmetryMaps
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.ui.common.SymmetryLabels
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.draw.VectorEraseMode
import com.brushwork.paint.vector.draw.VectorEraserModes
import com.brushwork.paint.vector.render.VectorLayerRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * v1.7 (item 18, §3.18): strokes with the symmetry rulers. A mirrored stroke paints both sides
 * as ONE undo step; overlapping copies share one buffer (a 50 % brush doesn't darken twice);
 * fill isn't replicated; the setting is saved and never undone; without symmetry (or with every
 * copy off the canvas) a stroke is byte-identical; smudge, blur and the eraser are replicated; a
 * vector-mode stroke is ONE `VStroke` whose copies are the ruler's maps and whose redraw equals
 * the live stroke (±1) for the five rulers; the partial vector eraser over one copy removes the
 * whole stroke in one step.
 */
@RunWith(RobolectricTestRunner::class)
class SymmetryStrokeRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()
    private val w = 320
    private val h = 240

    /** "Background" and the active "Layer 1" (a vector layer when [vector]); the brush selected. */
    private fun setup(vector: Boolean = false): EditorController {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("sym", "Sym", w, h)
        doc.layers += Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(w, h))
        doc.layers += Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(w, h)).also { if (vector) it.vector = VectorContent.EMPTY }
        doc.activeLayerIndex = 1
        return EditorController(app, doc, scope, settings).also {
            it.viewTransform.set(Matrix())
            it.color = 0xFF2060C0.toInt()
            it.tools
            it.selectTool(ToolId.BRUSH)
        }
    }

    private val EditorController.layer: Layer get() = doc.layers[1]

    private fun pixels(b: Bitmap): IntArray = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }

    private fun alpha(p: Int): Int = (p ushr 24) and 0xFF

    private fun render(content: VectorContent): IntArray {
        val b = BitmapUtils.createLayerBitmap(w, h)
        VectorLayerRenderer.render(Canvas(b), content, Rect(0, 0, w, h), tips = TipCache(), document = Rect(0, 0, w, h))
        return pixels(b)
    }

    /** A wavy finger stroke from ([x0], [y0]) [len] px to the right. */
    private fun wave(x0: Float, y0: Float, len: Float = 100f, amp: Float = 25f, n: Int = 24): List<ToolPoint> = List(n) { k ->
        val t = k / (n - 1f)
        ToolPoint(x0 + len * t, y0 + amp * sin(t * 6f), 1f, k.toLong())
    }

    private fun line(x0: Float, y0: Float, x1: Float, y1: Float, n: Int = 25): List<ToolPoint> = List(n) { k ->
        val t = k / (n - 1f)
        ToolPoint(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, 1f, k.toLong())
    }

    private fun EditorController.draw(pts: List<ToolPoint>) {
        pointerDown(pts.first())
        for (p in pts.subList(1, pts.size - 1)) pointerMove(p)
        pointerUp(pts.last())
    }

    private fun maxChannelDiff(a: Int, b: Int): Int {
        var m = 0
        for (s in intArrayOf(24, 16, 8, 0)) m = max(m, abs(((a ushr s) and 0xFF) - ((b ushr s) and 0xFF)))
        return m
    }

    /** Painted pixels of the left half, and how many of them differ (alpha > 2) from their mirror image. */
    private fun mirrorMismatch(px: IntArray): Pair<Int, Int> {
        var painted = 0
        var off = 0
        for (y in 0 until h) for (x in 0 until w / 2) {
            val a = alpha(px[y * w + x])
            val b = alpha(px[y * w + (w - 1 - x)])
            if (a == 0 && b == 0) continue
            painted++
            if (abs(a - b) > 2) off++
        }
        return painted to off
    }

    @Test
    fun aMirroredStrokePaintsBothSidesAndOneUndoRemovesEveryCopy() {
        val c = setup()
        c.brush = BrushLibrary.defaultBrush.copy(size = 10f, taperStart = 20f, taperEnd = 20f)
        c.updateSymmetry(SymmetrySettings(SymmetryType.MIRROR))
        assertEquals("the setting is no step", 0, c.undoManager.undoCount)
        c.draw(wave(30f, 110f))
        val px = pixels(c.layer.bitmap)
        val (painted, off) = mirrorMismatch(px)
        assertTrue("both sides painted ($painted)", painted > 800)
        assertTrue("$off of $painted pixels differ from the mirror image", off <= painted / 100)
        assertTrue("the right side", (0 until h).any { y -> alpha(px[y * w + (w - 1 - 60)]) > 0 })
        assertEquals("one step for the stroke and its copy", 1, c.undoManager.undoCount)
        c.undo()
        assertTrue("one undo removes every copy", pixels(c.layer.bitmap).all { it == 0 })
        c.redo()
        assertArrayEquals(px, pixels(c.layer.bitmap))
        // The kaleidoscope (6 axes, 12 copies) and the rotation paint every copy too, in one step.
        c.updateSymmetry(SymmetrySettings(SymmetryType.ROTATION, divisions = 4))
        c.draw(line(170f, 60f, 200f, 60f))
        assertEquals(2, c.undoManager.undoCount)
        // Turned a quarter about the centre (160, 120): (185, 60) goes to (220, 145).
        val after = pixels(c.layer.bitmap)
        assertTrue("a quarter turn", alpha(after[145 * w + 220]) > 200)
        assertTrue("a half turn", alpha(after[180 * w + 135]) > 200)
    }

    @Test
    fun overlappingCopiesOfAHalfOpacityBrushDoNotDarkenTwice() {
        val c = setup()
        c.brush = BrushLibrary.defaultBrush.copy(size = 16f, opacity = 0.5f)
        c.color = 0xFF000000.toInt()
        // Across the axis: the stroke and its mirror image overlap all along.
        val pts = line(100f, 120f, 220f, 120f)
        c.draw(pts)
        val single = pixels(c.layer.bitmap)
        c.undo()
        c.updateSymmetry(SymmetrySettings(SymmetryType.MIRROR))
        c.draw(pts)
        val mirrored = pixels(c.layer.bitmap)
        val top = single.maxOf { alpha(it) }
        assertTrue("about half ($top)", top in 120..135)
        assertTrue("the copies don't darken each other (${mirrored.maxOf { alpha(it) }} vs $top)", mirrored.maxOf { alpha(it) } <= top + 1)
        assertTrue(abs(alpha(mirrored[120 * w + 160]) - alpha(single[120 * w + 160])) <= 1)
    }

    @Test
    fun fillIsNotReplicated() {
        val c = setup()
        // A wall at x = 60: left of it a small region, right of it the rest of the canvas.
        Canvas(c.layer.bitmap).drawRect(58f, 0f, 62f, h.toFloat(), Paint().apply { color = 0xFF000000.toInt() })
        c.updateSymmetry(SymmetrySettings(SymmetryType.MIRROR))
        c.selectTool(ToolId.FILL)
        c.color = 0xFFE02020.toInt()
        c.pointerDown(ToolPoint(20f, 120f))
        c.pointerUp(ToolPoint(20f, 120f))
        assertTrue("filled", Smoke.pumpUntil { c.undoManager.undoCount == 1 })
        assertEquals(0xFFE02020.toInt(), c.layer.bitmap.getPixel(20, 120))
        // The mirror image of the tap (300, 120) lies in the big region: it stays empty.
        assertEquals(0, c.layer.bitmap.getPixel(300, 120))
        assertEquals(0, c.layer.bitmap.getPixel(160, 120))
    }

    @Test
    fun theSettingSurvivesSaveAndLoadAndIsNotUndoable() = runBlocking {
        val c = setup()
        c.draw(wave(30f, 60f))
        assertEquals(1, c.undoManager.undoCount)
        val label = c.undoManager.undoLabel
        val s = SymmetrySettings(SymmetryType.KALEIDOSCOPE, centerX = 100f, centerY = 90f, angleDeg = 75f, divisions = 5)
        c.updateSymmetry(s)
        assertEquals(1, c.undoManager.undoCount)
        assertEquals(label, c.undoManager.undoLabel)
        // The Symmetry tool's drags aren't steps either.
        c.selectTool(ToolId.SYMMETRY)
        c.draw(line(200f, 200f, 240f, 210f, n = 6))
        val moved = c.symmetry
        assertNotEquals(s, moved)
        assertEquals(1, c.undoManager.undoCount)
        c.undo()
        assertEquals("undo takes back the stroke, not the symmetry", moved, c.symmetry)
        assertEquals(moved, c.doc.symmetry)
        File(app.filesDir, "projects").deleteRecursively()
        val repo = ProjectRepository(app)
        repo.save(c.doc, null)
        val loaded = repo.load(c.doc.id)
        assertEquals(emptyList<String>(), loaded.loadWarnings)
        assertEquals(moved, loaded.symmetry)
    }

    @Test
    fun withoutSymmetryOrWithEveryCopyOffTheCanvasAStrokeIsByteIdentical() {
        val offCanvas = SymmetrySettings(SymmetryType.MIRROR, centerX = -2000f)
        val noCopies = SymmetrySettings(SymmetryType.ARRAY, spacingX = 5000f, spacingY = 5000f)
        // An array with cells larger than the canvas has no copies at all.
        assertEquals(0, SymmetryMaps.count(noCopies, w, h, 40f, 100f))
        // The live stroke of a brush without randomness (a live stroke's seed is random).
        fun paint(s: SymmetrySettings): IntArray {
            val c = setup()
            c.brush = BrushLibrary.defaultBrush.copy(size = 14f, taperStart = 30f, taperEnd = 30f)
            c.updateSymmetry(s)
            c.draw(wave(40f, 100f, len = 200f))
            assertEquals(1, c.undoManager.undoCount)
            return pixels(c.layer.bitmap)
        }
        val plain = paint(SymmetrySettings())
        assertTrue(plain.any { it != 0 })
        // A mirror far to the left: its copy lands off the canvas and is culled.
        assertArrayEquals("pen: the copy off the canvas", plain, paint(offCanvas))
        assertArrayEquals("pen: no copies", plain, paint(noCopies))
        // Textured, random brushes, replayed with one seed: the mirror's copy off the canvas
        // changes no byte (the copies take nothing from the stroke's random sequence).
        for (id in listOf("chalk", "pencil", "spray")) {
            val c = setup(vector = true)
            c.brush = BrushLibrary.byId(id) ?: continue
            // The ruler is on but every copy is culled: the stroke has no copies (none are written, I13).
            c.updateSymmetry(noCopies)
            c.draw(wave(40f, 100f, len = 200f))
            val s = c.layer.vector!!.objects.single() as VStroke
            assertTrue("$id: no copies", s.copies.isEmpty())
            fun replay(copies: List<FloatArray>): IntArray {
                val b = BitmapUtils.createLayerBitmap(w, h)
                StrokeRaster().render(Canvas(b), Rect(0, 0, w, h), s.preset, s.color, s.seed, s.stylus, s.points, copies = copies)
                return pixels(b)
            }
            val alone = replay(emptyList())
            assertTrue("$id painted", alone.count { it != 0 } > 200)
            assertArrayEquals("$id: the copy off the canvas", alone, replay(SymmetryMaps.transforms(offCanvas, w, h, 40f, 100f)))
        }
    }

    @Test
    fun smudgeBlurAndTheEraserAreReplicated() {
        for (tool in listOf(ToolId.SMUDGE, ToolId.BLUR, ToolId.ERASER)) {
            val c = setup()
            // Stripes symmetric about the axis (x ↔ 319 − x), so the copy works on the same picture.
            val bmp = c.layer.bitmap
            for (x in 0 until w) {
                val dark = (minOf(x, w - 1 - x) / 4) % 2 == 0
                Canvas(bmp).drawRect(x.toFloat(), 0f, x + 1f, h.toFloat(), Paint().apply { color = if (dark) 0xFF202020.toInt() else 0xFFE0E0E0.toInt() })
            }
            val before = pixels(bmp)
            c.updateSymmetry(SymmetrySettings(SymmetryType.MIRROR))
            c.selectTool(tool)
            c.draw(line(40f, 110f, 120f, 130f))
            val after = pixels(bmp)
            var left = 0
            var right = 0
            var mirrored = 0
            for (y in 0 until h) for (x in 0 until w) {
                if (before[y * w + x] == after[y * w + x]) continue
                if (x < w / 2) {
                    left++
                    if (before[y * w + w - 1 - x] != after[y * w + w - 1 - x]) mirrored++
                } else right++
            }
            assertTrue("$tool changed the left side ($left)", left > 200)
            assertTrue("$tool changed the right side like the left ($right vs $left)", right > left / 2 && right < left * 2)
            assertTrue("$tool: the mirror image changed ($mirrored of $left)", mirrored > left * 8 / 10)
            assertEquals("$tool: one step", 1, c.undoManager.undoCount)
            c.undo()
            assertArrayEquals("$tool: one undo restores both sides", before, pixels(bmp))
        }
    }

    @Test
    fun aVectorStrokeIsOneObjectWithTheRulersMapsAndRedrawsAsDrawn() {
        val cases = listOf(
            Triple(SymmetrySettings(SymmetryType.MIRROR), "pen", 2),
            Triple(SymmetrySettings(SymmetryType.KALEIDOSCOPE, divisions = 3), "chalk", 6),
            Triple(SymmetrySettings(SymmetryType.ROTATION, divisions = 5, centerX = 150f, centerY = 125f), "pencil", 5),
            Triple(SymmetrySettings(SymmetryType.ARRAY, spacingX = 110f, spacingY = 90f, angleDeg = 100f), "softround", -1),
            Triple(SymmetrySettings(SymmetryType.PERSPECTIVE_ARRAY), "pen", -1),
            // Many copies: drawn from DabMapping's shifted tips, live and redrawn alike.
            Triple(SymmetrySettings(SymmetryType.ROTATION, divisions = 24), "pen", 24),
            Triple(SymmetrySettings(SymmetryType.KALEIDOSCOPE, divisions = 10), "softround", 20),
        )
        for ((s, id, expected) in cases) {
            val c = setup(vector = true)
            c.brush = BrushLibrary.byId(id) ?: BrushLibrary.defaultBrush
            c.updateSymmetry(s)
            val pts = if (s.type == SymmetryType.PERSPECTIVE_ARRAY) wave(150f, 150f, len = 20f, amp = 6f) else wave(120f, 40f, len = 60f, amp = 15f)
            c.draw(pts)
            val where = "${s.type} with $id"
            val objects = c.layer.vector!!.objects
            assertEquals("$where: ONE object", 1, objects.size)
            assertEquals("$where: one step", 1, c.undoManager.undoCount)
            val stroke = objects.single() as VStroke
            val count = SymmetryMaps.count(s, w, h, pts[0].x, pts[0].y)
            if (expected > 0) assertEquals(where, expected, count)
            assertTrue("$where: copies ($count)", count >= 2)
            assertEquals("$where: the ruler's maps", count, stroke.copies.size)
            for ((a, b) in SymmetryMaps.transforms(s, w, h, pts[0].x, pts[0].y).zip(stroke.copies)) assertArrayEquals(where, a, b, 0f)
            val live = pixels(c.layer.bitmap)
            val redraw = render(c.layer.vector!!)
            var worst = 0
            var bad = 0
            for (i in live.indices) {
                val d = maxChannelDiff(live[i], redraw[i])
                worst = max(worst, d)
                if (d > 1) bad++
            }
            assertTrue("$where: copies painted", live.count { it != 0 } > 400)
            assertEquals("$where: the redraw equals the live stroke (±1; worst $worst)", 0, bad)
        }
    }

    @Test
    fun aPartialEraserPassAcrossOneCopyRemovesTheWholeStrokeInOneStep() {
        val c = setup(vector = true)
        c.brush = BrushLibrary.defaultBrush.copy(size = 8f)
        c.updateSymmetry(SymmetrySettings(SymmetryType.MIRROR))
        c.draw(line(30f, 120f, 130f, 120f))
        val drawn = c.layer.vector!!
        assertEquals(2, (drawn.objects.single() as VStroke).copies.size)
        assertTrue("the copy is painted", c.layer.bitmap.getPixel(240, 120) != 0)
        val steps = c.undoManager.undoCount
        c.selectTool(ToolId.ERASER)
        c.eraser = BrushLibrary.defaultEraser.copy(size = 12f)
        VectorEraserModes.setMode(c, VectorEraseMode.PARTIAL)
        // Across the copy only (the mirror image of x = 80).
        c.draw(line(240f, 90f, 240f, 150f, n = 8))
        assertEquals("the whole stroke goes", 0, c.layer.vector!!.objects.size)
        assertEquals("in one step", steps + 1, c.undoManager.undoCount)
        assertTrue(pixels(c.layer.bitmap).all { it == 0 })
        c.undo()
        assertEquals(drawn, c.layer.vector)
        assertArrayEquals(render(drawn), pixels(c.layer.bitmap))
        // The eraser isn't replicated, and says so once (after its own first hint).
        c.message = null
        c.draw(line(20f, 200f, 60f, 200f, n = 6))
        assertEquals(SymmetryLabels.ERASER_NOTE, c.message)
        c.message = null
        c.draw(line(20f, 210f, 60f, 210f, n = 6))
        assertEquals(null, c.message)
        // Nor does it show the guides, which the brush shows on the same layer.
        fun overlay(): Int {
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            c.drawOverlays(Canvas(b), 0f)
            return pixels(b).count { it != 0 }
        }
        assertEquals(0, overlay())
        c.selectTool(ToolId.BRUSH)
        assertTrue(overlay() > 100)
    }
}
