package com.brushwork.paint.brush

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.cos
import kotlin.math.sin

/**
 * Path strokes ([BrushTool.beginPath] / [BrushTool.updatePath]): re-rendering only from the
 * first changed point, and drafts while dragging, must end in exactly the pixels a stroke drawn
 * in one go produces.
 */
@RunWith(RobolectricTestRunner::class)
class PathStrokeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun controller(w: Int = 320, h: Int = 240, fill: Int? = null): EditorController {
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

    private fun EditorController.composite(): IntArray {
        val out = BitmapUtils.createLayerBitmap(doc.width, doc.height)
        compositor.drawDocument(Canvas(out), null)
        return out.pixels()
    }

    /** A wavy path through the canvas, [bend] changing the part after [from] (0..1 of the length). */
    private fun wave(bend: Float = 0f, from: Float = 0.5f, pressure: (Float) -> Float = { 1f }, extra: Int = 0, startShift: Float = 0f): PathStrokeInput {
        val out = PathStrokeInput()
        val n = 520 + extra
        for (i in 0 until n) {
            val t = i / 519f
            val k = if (t > from) (t - from) else 0f
            val x = 30f + 260f * t + (if (i == 0) startShift else 0f)
            val y = 120f + 60f * sin(t * 9f) + bend * 140f * k * cos(t * 3f)
            out.add(x, y, pressure(t))
        }
        return out
    }

    private fun sameInput(a: PathStrokeInput) = PathStrokeInput().also { it.set(a) }

    private val presets: List<Pair<ToolId, BrushPreset>> get() {
        val lib = BrushLibrary.all.associateBy { it.id }
        return listOf(
            ToolId.BRUSH to BrushLibrary.defaultBrush,
            ToolId.BRUSH to BrushLibrary.defaultBrush.copy(size = 7.3f),
            ToolId.BRUSH to lib.getValue("calligraphy"),
            ToolId.BRUSH to lib.getValue("dippen"),
            ToolId.BRUSH to lib.getValue("pencil"),
            ToolId.BRUSH to lib.getValue("chalk"),
            ToolId.BRUSH to lib.getValue("spray").copy(size = 40f),
            ToolId.BRUSH to lib.getValue("pixelpen").copy(size = 3f),
            ToolId.BRUSH to lib.getValue("airbrush").copy(size = 70f),
            ToolId.BRUSH to lib.getValue("marker"),
            ToolId.ERASER to BrushLibrary.defaultEraser.copy(size = 14f),
        )
    }

    /** Layer pixels after painting [final] in one go with [seed]. */
    private fun reference(id: ToolId, preset: BrushPreset, final: PathStrokeInput, seed: Long, fill: Int?): IntArray {
        val c = controller(fill = fill)
        val t = c.tool(id, preset)
        assertTrue(t.beginPath(final, seed))
        t.onUp(ToolPoint(final.x[final.size - 1], final.y[final.size - 1], final.pressure[final.size - 1], isStylus = true))
        return c.doc.activeLayer.bitmap.pixels()
    }

    @Test
    fun editedPathEndsInExactlyTheOneGoPixels() {
        val seed = 0x5eedL
        val final = wave(bend = 0.3f, from = 0.55f)
        for ((id, preset) in presets) {
            val fill = if (id == ToolId.ERASER) 0xFF20A040.toInt() else null
            val ref = reference(id, preset, final, seed, fill)
            val c = controller(fill = fill)
            val t = c.tool(id, preset)
            assertTrue(t.beginPath(wave(), seed))
            // Drafts while "dragging", then exact edits of the end, the middle and the start.
            assertTrue(t.updatePath(wave(bend = 0.6f, from = 0.55f), draftBudget = 50_000f))
            assertTrue("${preset.name}: a long re-render is a draft", t.isDraft)
            assertTrue(t.updatePath(wave(bend = -0.4f, from = 0.3f), draftBudget = 50_000f))
            assertTrue(t.updatePath(wave(bend = 0.2f, from = 0.8f)))
            assertFalse("${preset.name}: an exact update", t.isDraft)
            assertTrue(t.updatePath(wave(startShift = 3f, extra = 40)))
            assertTrue(t.updatePath(wave(bend = 0.9f, from = 0.1f), draftBudget = 50_000f))
            assertTrue(t.updatePath(sameInput(final)))
            assertFalse(t.isDraft)
            val preview = c.composite()
            t.onUp(ToolPoint(final.x[final.size - 1], final.y[final.size - 1], final.pressure[final.size - 1], isStylus = true))
            val got = c.doc.activeLayer.bitmap.pixels()
            val diff = got.indices.count { got[it] != ref[it] }
            assertEquals("${preset.name} (${id.label}): pixels differing from the one-go stroke", 0, diff)
            assertTrue("${preset.name}: something was painted", got.any { it != (fill ?: 0) })
            // The live preview showed the committed result (except at the very end: an
            // unfinished stroke has no closing dab yet).
            val shot = c.composite()
            val w = c.doc.width
            assertEquals("${preset.name}: preview == committed", 0, preview.indices.count { it % w < 240 && preview[it] != shot[it] })
        }
    }

    @Test
    fun stamperDrawsExactlyWhatTheFullMatrixChainDraws() {
        // The stamper's shortcuts (reused tip, paint state set only on change, a plain
        // translation for unscaled unrotated dabs) must not change a single pixel.
        val tips = TipCache()
        val stamper = DabStamper(tips)
        val m = android.graphics.Matrix()
        val paint = android.graphics.Paint()
        for (preset in listOf(BrushLibrary.defaultBrush, BrushLibrary.defaultBrush.copy(size = 7.3f), BrushLibrary.all.first { it.id == "calligraphy" }, BrushLibrary.all.first { it.id == "pixelpen" }.copy(size = 3f))) {
            val a = Bitmap.createBitmap(200, 120, Bitmap.Config.ALPHA_8)
            val b = Bitmap.createBitmap(200, 120, Bitmap.Config.ALPHA_8)
            val ca = Canvas(a)
            val cb = Canvas(b)
            for (i in 0 until 400) {
                val x = 20f + i * 0.4f + (i % 7) * 0.13f
                val y = 60f + 30f * sin(i / 17f)
                val d = if (i % 3 == 0) preset.size else preset.size * (0.6f + 0.4f * ((i % 5) / 4f))
                val rot = if (i % 4 == 0) 0f else preset.angle
                val alpha = if (i % 6 == 0) 0.37f else 1f
                fun dab() = Dab(x, y, 1f, i.toFloat(), 0f, 0f, rot, 0, 0.5f).also { it.diameter = d; it.alpha = alpha; it.cx = x; it.cy = y }
                stamper.stamp(ca, preset, dab())
                // The original drawing code.
                val ref = dab()
                val tip = tips.get(preset, ref.diameter, ref.variant)
                paint.alpha = (ref.alpha * 255f + 0.5f).toInt().coerceAtMost(255)
                if (preset.antiAlias) {
                    paint.isFilterBitmap = true
                    val s = ref.diameter / tip.diameter
                    val half = tip.size / 2f
                    m.setTranslate(-half, -half)
                    m.postScale(s, s)
                    if (ref.rotation != 0f) m.postRotate(ref.rotation)
                    m.postTranslate(ref.cx, ref.cy)
                    cb.drawBitmap(tip.bitmap, m, paint)
                } else {
                    paint.isFilterBitmap = false
                    val left = kotlin.math.floor(ref.cx - tip.size / 2f + 0.5f)
                    val top = kotlin.math.floor(ref.cy - tip.size / 2f + 0.5f)
                    cb.drawBitmap(tip.bitmap, left, top, paint)
                }
            }
            val pa = a.pixels(); val pb = b.pixels()
            assertEquals("${preset.name}: stamper vs original chain", 0, pa.indices.count { pa[it] != pb[it] })
            assertTrue(pa.any { it != 0 })
        }
    }

    @Test
    fun partByPartRefinementEndsInTheExactStroke() {
        val seed = 77L
        val presets = listOf(
            ToolId.BRUSH to BrushLibrary.defaultBrush,
            ToolId.BRUSH to BrushLibrary.all.first { it.id == "pencil" },
            ToolId.BRUSH to BrushLibrary.all.first { it.id == "chalk" },
            // 6 tip variants (nextInt(6) may draw several values per dab) and scatter: the random
            // sequence must be rewound exactly when parts are redrawn.
            ToolId.BRUSH to BrushLibrary.all.first { it.id == "spray" }.copy(size = 24f),
            ToolId.BRUSH to BrushLibrary.defaultBrush.copy(size = 5.5f),
            ToolId.ERASER to BrushLibrary.defaultEraser.copy(size = 9f),
        )
        for ((id, preset) in presets) {
            val fill = if (id == ToolId.ERASER) 0xFF20A040.toInt() else null
            // Straight refinement of a draft.
            run {
                val final = wave(bend = 0.4f, from = 0.2f)
                val ref = reference(id, preset, final, seed, fill)
                val c = controller(fill = fill)
                val t = c.tool(id, preset)
                assertTrue(t.beginPath(wave(), seed, draftBudget = 30_000f))
                assertTrue(t.isDraft)
                assertTrue(t.updatePath(sameInput(final), draftBudget = 30_000f))
                var steps = 0
                while (t.refinePath(60_000f)) steps++
                assertTrue("${preset.name}: refined in several parts ($steps)", steps >= 2)
                assertFalse(t.isDraft)
                t.onUp(ToolPoint(final.x[final.size - 1], final.y[final.size - 1], 1f, isStylus = true))
                val got = c.doc.activeLayer.bitmap.pixels()
                assertEquals("${preset.name}: refined == one go", 0, got.indices.count { got[it] != ref[it] })
            }
            // Refinement interrupted by new drags (before and after the refined part).
            run {
                val final = wave(bend = -0.5f, from = 0.1f)
                val ref = reference(id, preset, final, seed, fill)
                val c = controller(fill = fill)
                val t = c.tool(id, preset)
                assertTrue(t.beginPath(wave(bend = 0.3f, from = 0.05f), seed, draftBudget = 30_000f))
                assertTrue(t.refinePath(40_000f))
                assertTrue(t.refinePath(40_000f))
                // A drag changing the end only: the refined start stays exact.
                assertTrue(t.updatePath(wave(bend = 0.3f, from = 0.05f).also { p -> p.truncate(p.size - 60) }, draftBudget = 30_000f))
                assertTrue(t.refinePath(40_000f))
                // A drag changing almost everything.
                assertTrue(t.updatePath(wave(bend = 0.8f, from = 0.02f), draftBudget = 30_000f))
                assertTrue(t.refinePath(40_000f))
                assertTrue(t.updatePath(sameInput(final), draftBudget = 30_000f))
                while (t.refinePath(40_000f)) { /* until exact */ }
                assertFalse(t.isDraft)
                t.onUp(ToolPoint(final.x[final.size - 1], final.y[final.size - 1], 1f, isStylus = true))
                val got = c.doc.activeLayer.bitmap.pixels()
                assertEquals("${preset.name}: interrupted refinement == one go", 0, got.indices.count { got[it] != ref[it] })
            }
        }
    }

    @Test
    fun onUpOfADraftCommitsTheExactStroke() {
        val seed = 42L
        val final = wave(bend = 0.5f, from = 0.4f)
        val preset = BrushLibrary.all.first { it.id == "pencil" }
        val ref = reference(ToolId.BRUSH, preset, final, seed, null)
        val c = controller()
        val t = c.tool(ToolId.BRUSH, preset)
        assertTrue(t.beginPath(wave(), seed))
        assertTrue(t.updatePath(final, draftBudget = 100_000f))
        assertTrue(t.isDraft)
        t.onUp(ToolPoint(final.x[final.size - 1], final.y[final.size - 1], 1f, isStylus = true))
        val got = c.doc.activeLayer.bitmap.pixels()
        assertEquals(0, got.indices.count { got[it] != ref[it] })
        assertEquals(1, c.undoManager.undoCount)
    }

    @Test
    fun pathStrokeMatchesTheClassicStrokeAndLeavesNoTraceWhenCancelled() {
        // The same stylus points through onDown / onMove / onUp (how vector strokes were painted
        // before path strokes existed) give the same pixels for a brush without randomness.
        val final = wave(bend = 0.2f, pressure = { t -> 0.3f + 0.7f * t })
        for (preset in listOf(BrushLibrary.defaultBrush, BrushLibrary.all.first { it.id == "calligraphy" }, BrushLibrary.all.first { it.id == "hardround" })) {
            val ref = reference(ToolId.BRUSH, preset, final, 1L, null)
            val c = controller()
            val t = c.tool(ToolId.BRUSH, preset)
            t.onDown(ToolPoint(final.x[0], final.y[0], final.pressure[0], isStylus = true))
            for (i in 1 until final.size - 1) t.onMove(ToolPoint(final.x[i], final.y[i], final.pressure[i], isStylus = true))
            t.onUp(ToolPoint(final.x[final.size - 1], final.y[final.size - 1], final.pressure[final.size - 1], isStylus = true))
            val got = c.doc.activeLayer.bitmap.pixels()
            assertEquals("${preset.name}: classic vs path stroke", 0, got.indices.count { got[it] != ref[it] })
        }
        // Cancelling an edited path stroke leaves nothing behind (coverage buffer included).
        val c = controller()
        val t = c.tool(ToolId.BRUSH, BrushLibrary.defaultBrush)
        val before = c.composite()
        assertTrue(t.beginPath(wave(), 3L))
        assertNotNull(c.renderOverride)
        assertTrue(t.updatePath(wave(bend = 0.7f, from = 0.2f), draftBudget = 100_000f))
        t.onCancel()
        assertNull(c.renderOverride)
        assertFalse(c.canUndo)
        val after = c.composite()
        assertEquals(0, after.indices.count { after[it] != before[it] })
        // The next stroke starts on a clean coverage buffer.
        assertTrue(t.beginPath(final, 9L))
        t.onUp(ToolPoint(final.x[final.size - 1], final.y[final.size - 1], 1f, isStylus = true))
        val ref = reference(ToolId.BRUSH, BrushLibrary.defaultBrush, final, 9L, null)
        val got = c.doc.activeLayer.bitmap.pixels()
        assertEquals(0, got.indices.count { got[it] != ref[it] })
    }

    @Test
    fun updatingTheEndRedrawsOnlyTheEnd() {
        val c = controller(1900, 400)
        val t = c.tool(ToolId.BRUSH, BrushLibrary.defaultBrush)
        val stamper = StrokeResources.of(c).stamper
        fun line(endY: Float) = PathStrokeInput().also { p ->
            for (i in 0..2400) p.add(20f + i * 0.75f, if (i < 2300) 200f else 200f + (i - 2300) * endY, 1f)
        }
        assertTrue(t.beginPath(line(0f), 7L))
        val full = stamper.stampCount
        val before = stamper.stampCount
        assertTrue(t.updatePath(line(0.5f)))
        val edit = stamper.stampCount - before
        assertTrue("an edit of the last 100 points redraws only them ($edit dabs of $full)", edit < full / 6)
        // Smudge / blur / watercolor strokes can't be rewound: the caller starts them again.
        val smudge = c.tool(ToolId.SMUDGE, BrushLibrary.defaultSmudge)
        assertTrue(smudge.beginPath(line(0f), 7L))
        assertFalse(smudge.updatePath(line(0.5f)))
        smudge.onCancel()
    }

    @Test
    fun draftsStayWithinTheBudgetAndLookSolid() {
        val c = controller(1200, 400)
        val t = c.tool(ToolId.BRUSH, BrushLibrary.defaultBrush)
        val stamper = StrokeResources.of(c).stamper
        fun line(y: Float) = PathStrokeInput().also { p -> for (i in 0..1400) p.add(20f + i * 0.75f, y, 1f) }
        val s0 = stamper.stampCount
        assertTrue(t.beginPath(line(200f), 1L))
        val exact = stamper.stampCount - s0
        val s1 = stamper.stampCount
        assertTrue(t.updatePath(line(210f), draftBudget = 1_000_000f))
        val draft = stamper.stampCount - s1
        assertTrue(t.isDraft)
        assertTrue("draft uses fewer (and much cheaper) dabs ($draft vs $exact)", draft * 2 < exact)
        // No gaps: the draft line is solid along its whole length.
        val shot = c.composite()
        val w = c.doc.width
        for (x in 25..1060 step 5) assertTrue("solid at x=$x", (shot[210 * w + x] ushr 24) > 200)
        // Exact again once asked.
        assertTrue(t.updatePath(line(210f)))
        assertFalse(t.isDraft)
        t.onCancel()
    }
}
