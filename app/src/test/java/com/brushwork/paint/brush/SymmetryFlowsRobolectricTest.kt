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
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.RulerSettings
import com.brushwork.paint.model.RulerType
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.render.VectorLayerRenderer
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
 * v1.7 (item 18, §3.18, §6.2, §6.4 row 12), the review's end-to-end flows of the symmetry
 * rulers through the controller: a Rotation × 6 vector stroke saved and reopened is still ONE
 * stroke and redraws as drawn; a symmetric stroke inside an isolated folder is one undo step; the
 * ruler constrains the stroke before its copies are made; the brush on a layer mask is
 * replicated; a cancelled (two-finger) symmetric stroke leaves nothing; a smudge whose copies
 * would not fit in memory is refused with a message instead of failing mid-stroke.
 */
@RunWith(RobolectricTestRunner::class)
class SymmetryFlowsRobolectricTest {
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
        val doc = Document("symflow", "Sym flow", w, h)
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

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun alpha(p: Int): Int = (p ushr 24) and 0xFF

    private fun wave(x0: Float, y0: Float, len: Float = 100f, amp: Float = 25f, n: Int = 24): List<ToolPoint> = List(n) { k ->
        val t = k / (n - 1f)
        ToolPoint(x0 + len * t, y0 + amp * sin(t * 6f), 1f, k.toLong())
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

    /** Pixels of [after] that differ from [before], on the left half and on the right half. */
    private fun changedHalves(before: IntArray, after: IntArray): Pair<Int, Int> {
        var left = 0
        var right = 0
        for (y in 0 until h) for (x in 0 until w) {
            if (before[y * w + x] == after[y * w + x]) continue
            if (x < w / 2) left++ else right++
        }
        return left to right
    }

    /** Stripes symmetric about the vertical centre line (something for smudge to move). */
    private fun stripes(b: Bitmap) {
        val c = Canvas(b)
        val p = Paint()
        for (x in 0 until w) {
            p.color = if ((minOf(x, w - 1 - x) / 4) % 2 == 0) 0xFF202020.toInt() else 0xFFE0E0E0.toInt()
            c.drawRect(x.toFloat(), 0f, x + 1f, h.toFloat(), p)
        }
    }

    @Test
    fun aRotationSixVectorStrokeSavedAndReopenedIsOneStrokeThatRedrawsAsDrawn() = runBlocking {
        val c = setup(vector = true)
        c.brush = BrushLibrary.byId("pencil") ?: BrushLibrary.defaultBrush
        val s = SymmetrySettings(SymmetryType.ROTATION, divisions = 6)
        c.updateSymmetry(s)
        val pts = wave(170f, 60f, len = 50f, amp = 12f)
        c.draw(pts)
        val live = pixels(c.doc.layers[1].bitmap)
        val maps = SymmetryMaps.transforms(s, w, h, pts[0].x, pts[0].y)
        assertEquals(6, maps.size)

        File(app.filesDir, "projects").deleteRecursively()
        val repo = ProjectRepository(app)
        repo.save(c.doc, null)
        val loaded = repo.load(c.doc.id)
        assertEquals(emptyList<String>(), loaded.loadWarnings)
        assertEquals(s, loaded.symmetry)
        val layer = loaded.layers[1]
        val stroke = layer.vector!!.objects.single() as VStroke
        assertEquals("the six maps are saved with the stroke", 6, stroke.copies.size)
        for ((a, b) in maps.zip(stroke.copies)) assertArrayEquals(a, b, 1e-4f)
        assertArrayEquals("the saved pixels come back", live, pixels(layer.bitmap))

        // Reopened, the layer re-renders from its data exactly as it was drawn (±1).
        val redraw = BitmapUtils.createLayerBitmap(w, h)
        VectorLayerRenderer.render(Canvas(redraw), layer.vector!!, Rect(0, 0, w, h), tips = TipCache(), document = Rect(0, 0, w, h))
        val again = pixels(redraw)
        var bad = 0
        var worst = 0
        for (i in live.indices) {
            val d = maxChannelDiff(live[i], again[i])
            worst = max(worst, d)
            if (d > 1) bad++
        }
        assertTrue("all six copies painted", live.count { alpha(it) > 0 } > 600)
        assertEquals("the reopened redraw equals the live stroke (±1; worst $worst)", 0, bad)
    }

    @Test
    fun aSymmetricStrokeInsideAnIsolatedFolderIsOneUndoStep() {
        val c = setup()
        val layer = c.doc.layers[1]
        // An isolated folder inside a pass-through one (two levels of the tree).
        val outer = c.putInNewFolder(layer)
        assertNotNull(outer)
        val folder = c.putInNewFolder(layer)
        assertNotNull(folder)
        assertEquals(outer!!.id, folder!!.parentId)
        c.setFolderPassThrough(folder, false)
        assertFalse(folder.folder!!.passThrough)
        c.doc.activeLayerIndex = c.doc.indexOf(layer)
        assertSame(layer, c.activeLayer)
        val steps = c.undoManager.undoCount
        val before = pixels(layer.bitmap)

        c.updateSymmetry(SymmetrySettings(SymmetryType.MIRROR))
        c.draw(wave(30f, 60f))
        val after = pixels(layer.bitmap)
        val (left, right) = changedHalves(before, after)
        assertTrue("the stroke ($left)", left > 300)
        assertTrue("its mirror image ($right vs $left)", right > left * 8 / 10 && right < left * 12 / 10)
        assertEquals("one step", steps + 1, c.undoManager.undoCount)

        c.undo()
        assertArrayEquals("one undo takes back both sides", before, pixels(layer.bitmap))
        assertEquals("the folder stays", folder, c.doc.layers.first { it.id == layer.parentId })
        c.redo()
        assertArrayEquals("redo brings both back", after, pixels(layer.bitmap))
    }

    @Test
    fun theRulerConstrainsTheStrokeBeforeItsCopiesAreMade() {
        val c = setup()
        c.brush = BrushLibrary.defaultBrush.copy(size = 8f)
        // A horizontal straight ruler: the wavy finger stroke becomes a horizontal line.
        c.updateRuler(RulerSettings(enabled = true, type = RulerType.STRAIGHT, centerX = 160f, centerY = 200f, angleDeg = 0f))
        c.updateSymmetry(SymmetrySettings(SymmetryType.MIRROR))
        c.draw(wave(30f, 60f, len = 90f, amp = 30f))
        val px = pixels(c.doc.layers[1].bitmap)
        var left = 0
        var right = 0
        var outside = 0
        for (y in 0 until h) for (x in 0 until w) {
            if (alpha(px[y * w + x]) == 0) continue
            if (abs(y - 60) > 8) outside++
            if (x < w / 2) left++ else right++
        }
        assertEquals("every copy follows the constrained line", 0, outside)
        assertTrue("the stroke ($left)", left > 300)
        assertTrue("its mirror image on the same line ($right vs $left)", right > left * 8 / 10 && right < left * 12 / 10)
        assertEquals(1, c.undoManager.undoCount)
    }

    @Test
    fun theBrushOnALayerMaskIsReplicated() {
        val c = setup()
        val layer = c.doc.layers[1]
        stripes(layer.bitmap)
        c.addMask(layer, fromSelection = false)
        assertEquals(EditTarget.MASK, c.editTargetOf(layer))
        val steps = c.undoManager.undoCount
        val content = pixels(layer.bitmap)
        val mask = layer.mask!!
        val before = pixels(mask)
        c.color = 0xFF000000.toInt()
        c.updateSymmetry(SymmetrySettings(SymmetryType.MIRROR))
        c.draw(wave(30f, 60f))
        val after = pixels(mask)
        val (left, right) = changedHalves(before, after)
        assertTrue("the mask stroke ($left)", left > 300)
        assertTrue("its mirror image on the mask ($right vs $left)", right > left * 8 / 10 && right < left * 12 / 10)
        assertArrayEquals("the content is untouched", content, pixels(layer.bitmap))
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        c.undo()
        assertArrayEquals("one undo restores the whole mask", before, pixels(mask))
    }

    @Test
    fun aCancelledSymmetricStrokeLeavesNothing() {
        for (tool in listOf(ToolId.BRUSH, ToolId.SMUDGE)) {
            val c = setup()
            val layer = c.doc.layers[1]
            stripes(layer.bitmap)
            val before = pixels(layer.bitmap)
            c.updateSymmetry(SymmetrySettings(SymmetryType.KALEIDOSCOPE, divisions = 4))
            c.selectTool(tool)
            val pts = wave(40f, 70f)
            c.pointerDown(pts.first())
            for (p in pts.subList(1, pts.size - 1)) c.pointerMove(p)
            // A second finger lands: the stroke is cancelled.
            c.pointerCancel()
            assertArrayEquals("$tool: the layer is as before", before, pixels(layer.bitmap))
            assertEquals("$tool: no step", 0, c.undoManager.undoCount)
            assertNull("$tool: nothing drawn over the layer", c.renderOverride)
        }
    }

    @Test
    fun aSmudgeWhoseCopiesWouldNotFitInMemoryIsRefusedUpFront() {
        fun smudge(s: SymmetrySettings, freeBytes: Long?): Triple<Boolean, String?, Int> {
            val c = setup()
            val layer = c.doc.layers[1]
            stripes(layer.bitmap)
            val before = pixels(layer.bitmap)
            c.smudgeBrush = BrushLibrary.defaultSmudge.copy(size = 1000f)
            c.selectTool(ToolId.SMUDGE)
            if (freeBytes != null) (c.tools.getValue(ToolId.SMUDGE) as BrushTool).freeMemoryForCopies = { freeBytes }
            c.updateSymmetry(s)
            c.message = null
            c.draw(wave(40f, 70f, len = 60f, amp = 10f, n = 8))
            return Triple(!before.contentEquals(pixels(layer.bitmap)), c.message, c.undoManager.undoCount)
        }
        // Each copy of a 1000 px smudge keeps a canvas-sized scratch (320 × 240 × 20 bytes ≈ 1.5 MB).
        val kaleidoscope = SymmetrySettings(SymmetryType.KALEIDOSCOPE, divisions = 6)
        val (painted, message, steps) = smudge(kaleidoscope, freeBytes = 8L shl 20)
        assertFalse("11 copies don't fit in 4 MB: nothing is smudged", painted)
        assertEquals(BrushTool.COPIES_OUT_OF_MEMORY, message)
        assertEquals("no step", 0, steps)

        val mirror = smudge(SymmetrySettings(SymmetryType.MIRROR), freeBytes = 8L shl 20)
        assertTrue("one copy fits", mirror.first)
        assertNull(mirror.second)
        assertEquals(1, mirror.third)

        val roomy = smudge(kaleidoscope, freeBytes = null)
        assertTrue("with the memory there, the kaleidoscope smudges", roomy.first)
        assertNull(roomy.second)
        assertEquals(1, roomy.third)
    }
}
