package com.brushwork.paint.brush

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs
import kotlin.math.hypot

/** Drives [BrushTool] on real Skia (Robolectric NATIVE graphics). */
@RunWith(RobolectricTestRunner::class)
class BrushToolRobolectricTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun newController(w: Int = 200, h: Int = 200, fill: Int? = null): EditorController {
        val doc = Document("t", "t", w, h)
        val layer = Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(w, h))
        if (fill != null) layer.bitmap.eraseColor(fill)
        doc.layers += layer
        val c = EditorController(context, doc, CoroutineScope(Dispatchers.Unconfined), AppSettings(context))
        c.tools // create the tools first so their preset restore doesn't overwrite test presets later
        return c
    }

    private fun EditorController.tool(id: ToolId): BrushTool {
        selectTool(id)
        return tools.getValue(id) as BrushTool
    }

    private fun Bitmap.pixels(): IntArray = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }

    private fun BrushTool.line(x0: Float, y0: Float, x1: Float, y1: Float, steps: Int = 20, up: Boolean = true) {
        onDown(ToolPoint(x0, y0))
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            onMove(ToolPoint(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t))
        }
        if (up) onUp(ToolPoint(x1, y1))
    }

    private fun alphaAt(b: Bitmap, x: Int, y: Int) = b.getPixel(x, y) ushr 24

    // ------------------------------------------------------------------ Skia assumptions

    @Test
    fun skiaAlpha8MatrixDrawAndShaderMask() {
        // Filtered matrix draw of an A8 tip into an A8 canvas accumulates with the paint alpha.
        val tip = BitmapUtils.bytesToAlpha8(ByteArray(16) { -1 }, 4, 4)
        val buf = Bitmap.createBitmap(20, 20, Bitmap.Config.ALPHA_8)
        val m = Matrix().apply { setTranslate(-2f, -2f); postScale(2f, 2f); postTranslate(10f, 10f) }
        val p = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 128 }
        Canvas(buf).drawBitmap(tip, m, p)
        val once = alphaAt(buf, 10, 10)
        assertTrue("single dab ~128, was $once", once in 120..136)
        Canvas(buf).drawBitmap(tip, m, p)
        val twice = alphaAt(buf, 10, 10)
        assertTrue("two dabs ~192, was $twice", twice in 180..200)
        assertEquals(0, alphaAt(buf, 1, 1))

        // An A8 BitmapShader drawn with DST_IN keeps the destination by the texture alpha.
        val dst = BitmapUtils.createLayerBitmap(4, 4).apply { eraseColor(0xFFFF0000.toInt()) }
        val tex = BitmapUtils.bytesToAlpha8(ByteArray(16) { if (it % 2 == 0) -1 else 64 }, 4, 4)
        val sp = Paint().apply {
            shader = BitmapShader(tex, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        Canvas(dst).drawRect(0f, 0f, 4f, 4f, sp)
        assertEquals(255, alphaAt(dst, 0, 0))
        assertTrue(alphaAt(dst, 1, 0) in 60..68)
    }

    // ------------------------------------------------------------------ painting

    @Test
    fun strokeChangesPixelsOnlyNearThePath() {
        val c = newController()
        c.color = 0xFF2040C0.toInt()
        val tool = c.tool(ToolId.BRUSH)
        c.brush = BrushLibrary.byId("hardround")!!.copy(size = 10f, pressureSize = false)
        tool.line(40f, 100f, 160f, 100f)
        val bmp = c.activeLayer.bitmap
        assertEquals(0xFF2040C0.toInt(), bmp.getPixel(100, 100))
        assertEquals(255, alphaAt(bmp, 50, 101))
        assertEquals(0, alphaAt(bmp, 100, 110))
        assertEquals(0, alphaAt(bmp, 20, 100))
        assertEquals(0, alphaAt(bmp, 175, 100))
        // Everything painted lies within radius + AA of the segment.
        val px = bmp.pixels()
        for (y in 0 until 200) for (x in 0 until 200) {
            if (px[y * 200 + x] ushr 24 == 0) continue
            val dx = when { x + 0.5f < 40f -> 40f - (x + 0.5f); x + 0.5f > 160f -> x + 0.5f - 160f; else -> 0f }
            val dy = y + 0.5f - 100f
            assertTrue("pixel $x,$y too far from the path", dx * dx + dy * dy <= 7f * 7f)
        }
        assertTrue(c.canUndo)
        assertNull(c.renderOverride)
    }

    @Test
    fun livePreviewGoesThroughRenderOverride() {
        val c = newController()
        c.color = 0xFF000000.toInt()
        val tool = c.tool(ToolId.BRUSH)
        c.brush = BrushLibrary.byId("hardround")!!.copy(size = 12f)
        tool.line(30f, 50f, 170f, 50f, up = false)
        // Pixels untouched until release, but the composite shows the stroke.
        assertEquals(0, alphaAt(c.activeLayer.bitmap, 100, 50))
        assertNotNull(c.renderOverride)
        val out = BitmapUtils.createLayerBitmap(200, 200)
        c.compositor.drawDocument(Canvas(out), null, target = null)
        assertEquals(255, alphaAt(out, 100, 50))
        tool.onUp(ToolPoint(170f, 50f))
        assertEquals(255, alphaAt(c.activeLayer.bitmap, 100, 50))
    }

    @Test
    fun strokeOpacityCapsOverlapsWithinAStrokeButNotAcrossStrokes() {
        val c = newController()
        val tool = c.tool(ToolId.BRUSH)
        c.brush = BrushLibrary.byId("hardround")!!.copy(size = 16f, opacity = 0.5f, flow = 1f, pressureSize = false)
        // Back and forth over the same line within one stroke.
        tool.onDown(ToolPoint(40f, 100f))
        for (i in 1..20) tool.onMove(ToolPoint(40f + 6f * i, 100f))
        for (i in 1..20) tool.onMove(ToolPoint(160f - 6f * i, 100f))
        tool.onUp(ToolPoint(40f, 100f))
        val a1 = alphaAt(c.activeLayer.bitmap, 100, 100)
        assertTrue("within one stroke alpha stays at the cap, was $a1", a1 in 120..132)
        tool.line(40f, 100f, 160f, 100f)
        val a2 = alphaAt(c.activeLayer.bitmap, 100, 100)
        assertTrue("a second stroke builds up, was $a2", a2 in 185..197)
    }

    @Test
    fun eraserClearsAlongThePath() {
        val c = newController(fill = 0xFF00FF00.toInt())
        val tool = c.tool(ToolId.ERASER)
        c.eraser = BrushLibrary.defaultEraser.copy(size = 14f)
        tool.line(20f, 60f, 180f, 60f)
        val bmp = c.activeLayer.bitmap
        assertEquals(0, alphaAt(bmp, 100, 60))
        assertEquals(0xFF00FF00.toInt(), bmp.getPixel(100, 90))
        assertEquals("Eraser", c.undoManager.undoLabel)
    }

    @Test
    fun cancelLeavesNoTraceAndNoUndo() {
        val c = newController(fill = 0x80FFFFFF.toInt())
        val before = c.activeLayer.bitmap.pixels()
        for (id in listOf(ToolId.BRUSH, ToolId.ERASER, ToolId.SMUDGE, ToolId.BLUR)) {
            val tool = c.tool(id)
            tool.line(20f, 20f, 180f, 180f, up = false)
            tool.onCancel()
            assertNull(c.renderOverride)
            assertFalse(tool.isStroking)
        }
        assertTrue(before.contentEquals(c.activeLayer.bitmap.pixels()))
        assertFalse(c.canUndo)
        val out = BitmapUtils.createLayerBitmap(200, 200)
        c.compositor.drawDocument(Canvas(out), null, target = null)
        assertTrue(before.contentEquals(out.pixels()))
        // The shared coverage buffer was cleared: a dot whose bounds overlap the cancelled
        // diagonal stroke paints only its own circle (84,84 is on the diagonal, outside the dot).
        val tool = c.tool(ToolId.BRUSH)
        c.brush = BrushLibrary.byId("hardround")!!.copy(size = 30f, pressureSize = false)
        tool.onDown(ToolPoint(100.5f, 80.5f)); tool.onUp(ToolPoint(100.5f, 80.5f))
        assertEquals(before[84 * 200 + 84], c.activeLayer.bitmap.getPixel(84, 84))
        assertEquals(255, alphaAt(c.activeLayer.bitmap, 100, 80))
    }

    @Test
    fun undoRestoresAndRedoReapplies() {
        val c = newController()
        val tool = c.tool(ToolId.BRUSH)
        tool.line(20f, 20f, 180f, 180f)
        val painted = c.activeLayer.bitmap.pixels()
        assertTrue(painted.any { it != 0 })
        c.undo()
        assertTrue(c.activeLayer.bitmap.pixels().all { it == 0 })
        c.redo()
        assertTrue(painted.contentEquals(c.activeLayer.bitmap.pixels()))
    }

    @Test
    fun selectionLimitsTheStroke() {
        val c = newController()
        val tool = c.tool(ToolId.BRUSH)
        c.brush = BrushLibrary.byId("hardround")!!.copy(size = 20f, pressureSize = false)
        val bytes = ByteArray(200 * 200) { if (it % 200 < 100) -1 else 0 }
        c.setSelection(Selection.fromBytes(bytes, 200, 200))
        tool.line(20f, 100f, 180f, 100f)
        assertEquals(255, alphaAt(c.activeLayer.bitmap, 60, 100))
        assertEquals(0, alphaAt(c.activeLayer.bitmap, 140, 100))
    }

    @Test
    fun alphaLockKeepsTransparencyAndBlocksEraser() {
        val c = newController()
        val layer = c.activeLayer
        Canvas(layer.bitmap).drawRect(0f, 0f, 100f, 200f, Paint().apply { color = 0xFFFFFFFF.toInt() })
        layer.alphaLocked = true
        c.color = 0xFFFF0000.toInt()
        val tool = c.tool(ToolId.BRUSH)
        c.brush = BrushLibrary.byId("hardround")!!.copy(size = 20f, pressureSize = false)
        tool.line(20f, 100f, 180f, 100f)
        assertEquals(0xFFFF0000.toInt(), layer.bitmap.getPixel(60, 100))
        assertEquals(0, alphaAt(layer.bitmap, 140, 100))
        c.tool(ToolId.ERASER).line(20f, 50f, 180f, 50f)
        assertEquals(255, alphaAt(layer.bitmap, 60, 50))
        // Only the brush stroke was recorded.
        c.undo()
        assertFalse(c.canUndo)
    }

    @Test
    fun maskEditingPaintsLuminanceIntoTheMask() {
        val c = newController(fill = 0xFF0000FF.toInt())
        c.addMask(fromSelection = false)
        val layer = c.activeLayer
        assertTrue(layer.editingMask)
        c.color = 0xFF000000.toInt() // black hides
        val tool = c.tool(ToolId.BRUSH)
        c.brush = BrushLibrary.byId("hardround")!!.copy(size = 20f, pressureSize = false)
        tool.line(20f, 100f, 180f, 100f, up = false)
        // Preview goes through drawMask: the composite already hides the stroke area.
        val out = BitmapUtils.createLayerBitmap(200, 200)
        c.compositor.drawDocument(Canvas(out), null, target = null)
        assertEquals(0, alphaAt(out, 100, 100))
        assertEquals(255, alphaAt(out, 100, 150))
        tool.onUp(ToolPoint(180f, 100f))
        assertEquals(0xFF000000.toInt(), layer.mask!!.getPixel(100, 100))
        assertEquals(0xFF0000FF.toInt(), layer.bitmap.getPixel(100, 100)) // content untouched
        // Eraser on a mask paints black too.
        layer.mask!!.eraseColor(-1)
        c.tool(ToolId.ERASER).line(20f, 40f, 180f, 40f)
        assertEquals(0xFF000000.toInt(), layer.mask!!.getPixel(100, 40))
    }

    @Test
    fun lockedOrHiddenLayerIsNotPainted() {
        val c = newController()
        c.activeLayer.locked = true
        c.tool(ToolId.BRUSH).line(20f, 20f, 180f, 180f)
        assertTrue(c.activeLayer.bitmap.pixels().all { it == 0 })
        assertFalse(c.canUndo)
        assertNotNull(c.message)
    }

    @Test
    fun pixelPenPaintsExactAliasedPixels() {
        val c = newController()
        c.color = 0xFF123456.toInt()
        val tool = c.tool(ToolId.BRUSH)
        c.brush = BrushLibrary.byId("pixelpen")!!
        tool.line(10.5f, 10.5f, 60.5f, 10.5f)
        val px = c.activeLayer.bitmap.pixels()
        for (x in 10..60) assertEquals("x=$x", 0xFF123456.toInt(), px[10 * 200 + x])
        assertEquals(0, px[9 * 200 + 30])
        assertEquals(0, px[11 * 200 + 30])
        assertTrue(px.all { it == 0 || it == 0xFF123456.toInt() })
    }

    @Test
    fun smudgeDragsColorAndUndoRestores() {
        val c = newController()
        val layer = c.activeLayer
        Canvas(layer.bitmap).drawRect(0f, 0f, 100f, 200f, Paint().apply { color = 0xFFFF0000.toInt() })
        val before = layer.bitmap.pixels()
        val tool = c.tool(ToolId.SMUDGE)
        c.smudgeBrush = BrushLibrary.defaultSmudge.copy(size = 30f, mixing = 0.9f)
        tool.line(60f, 100f, 160f, 100f, steps = 40)
        // Red was dragged into the transparent half.
        val p = layer.bitmap.getPixel(120, 100)
        assertTrue("alpha ${p ushr 24}", (p ushr 24) > 60)
        assertTrue(((p shr 16) and 0xFF) > 200)
        // Far away nothing changed.
        assertEquals(before[20 * 200 + 20], layer.bitmap.getPixel(20, 20))
        assertEquals(0, alphaAt(layer.bitmap, 180, 20))
        assertEquals("Smudge", c.undoManager.undoLabel)
        c.undo()
        assertTrue(before.contentEquals(layer.bitmap.pixels()))
    }

    @Test
    fun blurSoftensAnEdge() {
        val c = newController(fill = 0xFFFFFFFF.toInt())
        val layer = c.activeLayer
        Canvas(layer.bitmap).drawRect(0f, 0f, 100f, 200f, Paint().apply { color = 0xFF000000.toInt() })
        val tool = c.tool(ToolId.BLUR)
        c.blurBrush = BrushLibrary.byId("blurstrong")!!.copy(size = 40f)
        tool.line(100f, 40f, 100f, 160f, steps = 30)
        val left = layer.bitmap.getPixel(98, 100) and 0xFF
        val right = layer.bitmap.getPixel(101, 100) and 0xFF
        assertTrue("edge blurred: $left / $right", left in 20..235 && right in 20..235)
        assertEquals(0xFF000000.toInt(), layer.bitmap.getPixel(20, 100))
        assertEquals(255, alphaAt(layer.bitmap, 100, 100))
    }

    @Test
    fun watercolorMixesWithCanvasColor() {
        val c = newController()
        val layer = c.activeLayer
        Canvas(layer.bitmap).drawRect(0f, 0f, 100f, 200f, Paint().apply { color = 0xFFFFFF00.toInt() })
        c.color = 0xFF0000FF.toInt()
        val tool = c.tool(ToolId.BRUSH)
        c.brush = BrushLibrary.byId("watercolor")!!.copy(size = 30f, flow = 0.5f, pressureOpacity = false)
        tool.line(20f, 100f, 180f, 100f, steps = 40)
        val onYellow = layer.bitmap.getPixel(60, 100)
        val onEmpty = layer.bitmap.getPixel(150, 100)
        assertTrue((onEmpty ushr 24) > 0)
        // Some yellow was carried over into the transparent half: red/green there.
        assertTrue("carried color ${Integer.toHexString(onEmpty)}", ((onEmpty shr 16) and 0xFF) > 20)
        assertTrue((onYellow and 0xFF) > 0)
        assertEquals("Watercolor", c.undoManager.undoLabel)
    }

    @Test
    fun grayscaleDocumentConstrainsColor() {
        val c = newController()
        c.doc.colorMode = ColorMode.GRAYSCALE
        c.color = 0xFFFF0000.toInt()
        c.tool(ToolId.BRUSH).line(20f, 100f, 180f, 100f)
        val p = c.activeLayer.bitmap.getPixel(100, 100)
        assertEquals((p shr 16) and 0xFF, p and 0xFF)
    }

    @Test
    fun endTaperThinsTheStrokeEnd() {
        val c = newController(400, 200)
        val tool = c.tool(ToolId.BRUSH)
        c.brush = BrushLibrary.byId("hardround")!!.copy(size = 20f, pressureSize = false, taperStart = 0f, taperEnd = 120f)
        tool.line(20f, 100f, 380f, 100f, steps = 60)
        val bmp = c.activeLayer.bitmap
        fun thickness(x: Int) = (0 until 200).count { alphaAt(bmp, x, it) > 128 }
        assertTrue(thickness(100) >= 18)
        assertTrue("taper at the end: ${thickness(370)}", thickness(370) < 8)
        // A tap with tapers still leaves a full dot.
        c.brush = c.brush.copy(taperStart = 50f, taperEnd = 50f)
        tool.onDown(ToolPoint(200.5f, 30.5f)); tool.onUp(ToolPoint(200.5f, 30.5f))
        assertEquals(255, alphaAt(bmp, 200, 30))
        assertEquals(255, alphaAt(bmp, 207, 30))
    }

    @Test
    fun hugeBrushOnTinyCanvasAndTinyBrush() {
        val c = newController(8, 6)
        val tool = c.tool(ToolId.BRUSH)
        c.brush = BrushLibrary.byId("hardround")!!.copy(size = 1000f)
        tool.line(0f, 0f, 8f, 6f)
        assertTrue(c.activeLayer.bitmap.pixels().all { it ushr 24 == 255 })
        c.brush = BrushLibrary.defaultBrush.copy(size = 0.5f)
        tool.line(1f, 1f, 7f, 5f)
        assertTrue(c.canUndo)
    }

    @Test
    fun smudgeTapAndZeroStrengthLeaveNoUndoEntry() {
        val c = newController(fill = 0xFF3366CC.toInt())
        val before = c.activeLayer.bitmap.pixels()
        val tool = c.tool(ToolId.SMUDGE)
        c.smudgeBrush = BrushLibrary.defaultSmudge.copy(size = 30f)
        // A tap moves nothing.
        tool.onDown(ToolPoint(100f, 100f)); tool.onUp(ToolPoint(100f, 100f))
        assertFalse(c.canUndo)
        // Strength 0 moves nothing either.
        c.smudgeBrush = c.smudgeBrush.copy(mixing = 0f)
        tool.line(40f, 100f, 160f, 100f)
        assertFalse(c.canUndo)
        // Zero-opacity paint strokes are not recorded.
        c.brush = BrushLibrary.byId("hardround")!!.copy(opacity = 0f)
        c.tool(ToolId.BRUSH).line(40f, 60f, 160f, 60f)
        assertFalse(c.canUndo)
        assertTrue(before.contentEquals(c.activeLayer.bitmap.pixels()))
        assertNull(c.renderOverride)
    }

    @Test
    fun tiledCommitMatchesTheLivePreview() {
        // Grain + selection use an offscreen layer, which the commit now builds per 256 px tile:
        // the committed pixels must equal what the live preview showed, across tile edges.
        val c = newController(700, 600)
        c.color = 0xFF804020.toInt()
        val tool = c.tool(ToolId.BRUSH)
        c.brush = BrushLibrary.byId("chalk")!!.copy(size = 40f)
        val bytes = ByteArray(700 * 600) { i -> if ((i % 700) in 100..600 && (i / 700) in 50..550) -1 else 64 }
        c.setSelection(Selection.fromBytes(bytes, 700, 600))
        tool.line(30f, 40f, 670f, 560f, steps = 80, up = false)
        val preview = BitmapUtils.createLayerBitmap(700, 600)
        c.compositor.drawDocument(Canvas(preview), null, target = null)
        tool.onUp(ToolPoint(670f, 560f))
        val a = preview.pixels()
        val b = c.activeLayer.bitmap.pixels()
        var maxDiff = 0
        var where = ""
        for (i in a.indices) {
            // The live curve trails the last input point until release: skip the stroke's tail.
            if (hypot(i % 700 - 670f, i / 700 - 560f) < 70f) continue
            for (sh in intArrayOf(0, 8, 16, 24)) {
                // Compare premultiplied values (getPixels unpremultiplies, magnifying tiny-alpha noise).
                fun pm(c: Int) = if (sh == 24) c ushr 24 else ((c shr sh) and 0xFF) * (c ushr 24) / 255
                val d = abs(pm(a[i]) - pm(b[i]))
                if (d > maxDiff) { maxDiff = d; where = "${i % 700},${i / 700}: ${Integer.toHexString(a[i])} vs ${Integer.toHexString(b[i])}" }
            }
        }
        assertTrue("preview vs commit max channel difference $maxDiff at $where", maxDiff <= 1)
        assertTrue(b.count { it ushr 24 != 0 } > 5000)
        // Nothing outside the stroke's tiles changed: far corner stays empty.
        assertEquals(0, alphaAt(c.activeLayer.bitmap, 650, 40))
    }

    @Test
    fun monochromePreviewIsThresholdedLikeTheCommit() {
        val c = newController(300, 200)
        c.doc.colorMode = ColorMode.MONOCHROME
        c.color = 0xFF202020.toInt() // becomes black
        val tool = c.tool(ToolId.BRUSH)
        c.brush = BrushLibrary.byId("softround")!!.copy(size = 50f, flow = 0.6f, pressureOpacity = false)
        tool.line(20f, 100f, 280f, 100f, steps = 30, up = false)
        val preview = BitmapUtils.createLayerBitmap(300, 200)
        c.compositor.drawDocument(Canvas(preview), null, target = null)
        tool.onUp(ToolPoint(280f, 100f))
        val a = preview.pixels()
        val b = c.activeLayer.bitmap.pixels()
        // The preview is already 1-bit: transparent or opaque black.
        assertTrue(a.all { it == 0 || it == 0xFF000000.toInt() })
        var painted = 0
        var mismatched = 0
        for (i in a.indices) {
            if (i % 300 > 230) continue // the curve's tail is only drawn on release
            if (b[i] != 0) painted++
            if (a[i] != b[i]) mismatched++
        }
        assertTrue(painted > 3000)
        assertTrue("mismatched $mismatched of $painted", mismatched <= painted / 200)
    }

    @Test
    fun monochromeWatercolorAccumulatesAndPreviewsThresholded() {
        val c = newController(300, 200)
        c.doc.colorMode = ColorMode.MONOCHROME
        c.color = 0xFF000000.toInt()
        val tool = c.tool(ToolId.BRUSH)
        // One dab alone stays below the 1-bit alpha threshold; only the overlap crosses it.
        c.brush = BrushLibrary.byId("watercolor")!!.copy(size = 40f, flow = 0.3f, pressureOpacity = false)
        tool.line(20f, 100f, 280f, 100f, steps = 40, up = false)
        assertNotNull(c.renderOverride)
        val preview = BitmapUtils.createLayerBitmap(300, 200)
        c.compositor.drawDocument(Canvas(preview), null, target = null)
        tool.onUp(ToolPoint(280f, 100f))
        assertNull(c.renderOverride)
        val a = preview.pixels()
        val b = c.activeLayer.bitmap.pixels()
        assertTrue(a.all { it == 0 || it == 0xFF000000.toInt() || it == -1 })
        var painted = 0
        for (i in a.indices) {
            if (i % 300 > 230) continue // dabs held for the tail are only drawn on release
            if (b[i] != 0) painted++
            assertEquals("pixel ${i % 300},${i / 300}", a[i], b[i])
        }
        // Low-flow dabs still build up past the 1-bit threshold.
        assertTrue("painted $painted", painted > 1500)
        assertTrue(c.canUndo)
    }

    @Test
    fun nanStylusPressureStillPaints() {
        val c = newController()
        val tool = c.tool(ToolId.BRUSH)
        c.brush = BrushLibrary.byId("hardround")!!.copy(size = 10f)
        tool.onDown(ToolPoint(40f, 100f, pressure = Float.NaN, isStylus = true))
        for (i in 1..10) tool.onMove(ToolPoint(40f + 12f * i, 100f, pressure = Float.NaN, isStylus = true))
        tool.onUp(ToolPoint(160f, 100f, pressure = Float.NaN, isStylus = true))
        assertEquals(255, alphaAt(c.activeLayer.bitmap, 100, 100))
    }

    @Test
    fun resourcesAreFreedWhenTheEditorIsDisposed() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val doc = Document("t", "t", 100, 100).apply { layers += Layer(newLayerId(), "L", BitmapUtils.createLayerBitmap(100, 100)) }
        val c = EditorController(context, doc, scope, AppSettings(context))
        c.tool(ToolId.BRUSH).line(10f, 10f, 90f, 90f)
        val res = StrokeResources.of(c)
        assertTrue(res.hasCoverage)
        c.dispose()
        assertFalse(res.hasCoverage)
        assertTrue(StrokeResources.of(c) !== res)
    }

    @Test
    fun storedEditsKeepTheLibraryNameAndTip() {
        val store = BrushPresetStore.get(context)
        store.save(BrushLibrary.byId("pencil")!!.copy(name = "Old pencil", tip = BrushTip.ROUND_HARD, size = 21f))
        val loaded = store.load("pencil")!!
        assertEquals("Pencil", loaded.name)
        assertEquals(BrushTip.PENCIL, loaded.tip)
        assertEquals(21f, loaded.size)
    }

    @Test
    fun presetRestoredOnToolCreation() {
        val store = BrushPresetStore.get(context)
        store.remember(ToolId.BRUSH, BrushLibrary.byId("pencil")!!.copy(size = 33f))
        val doc = Document("t", "t", 50, 50).apply { layers += Layer(newLayerId(), "L", BitmapUtils.createLayerBitmap(50, 50)) }
        val c = EditorController(context, doc, CoroutineScope(Dispatchers.Unconfined), AppSettings(context))
        c.tools
        assertEquals("pencil", c.brush.id)
        assertEquals(33f, c.brush.size)
        // Painting persists edits made elsewhere (e.g. the side size slider).
        c.brush = c.brush.copy(size = 12f)
        (c.tools.getValue(ToolId.BRUSH) as BrushTool).line(5f, 5f, 40f, 40f)
        assertEquals(12f, store.load("pencil")!!.size)
    }
}
