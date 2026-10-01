package com.brushwork.paint.tools.vector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.runtime.snapshots.Snapshot
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.BrushTool
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.brush.sanitized
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStrokeKind
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorOps
import com.brushwork.paint.vector.render.VectorLayerRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs
import kotlin.math.max

/**
 * v1.5 (A4, §4.4 / §4.5 / §4.9): the curve and polyline tools. The plain line follows the brush
 * size (linked by default, unlinked keeps its own width, a reopened path keeps its width); per-point
 * thickness widens only where set (plain lines and brushes, also brushes whose size ignores
 * pressure) and 100 % everywhere is exactly the old drawing; one undo step per slider drag. On a
 * vector layer ✓ adds ONE path object in ONE step (a live brush stroke keeps its pixels, which are
 * the object's replay; neither the selection nor alpha lock clip it), a tap reopens a path object
 * (width, handles, colors and brush kept; ✓ = "Edit path"; ✕ restores it exactly), and the layer
 * stays a vector layer through all of it.
 */
@RunWith(RobolectricTestRunner::class)
class CurveV15RobolectricTest {

    private val ink = 0xFF203080.toInt()
    private val w = 360
    private val h = 260

    private fun controller(vector: Boolean = false): EditorController {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("brushwork_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val doc = Document("t", "t", w, h)
        doc.layers += Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(w, h))
        doc.layers += Layer(doc.newLayerId(), if (vector) "Vector 1" else "Layer 2", BitmapUtils.createLayerBitmap(w, h)).also {
            if (vector) it.vector = VectorContent.EMPTY
        }
        doc.activeLayerIndex = 1
        return EditorController(ctx, doc, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), AppSettings(ctx)).also {
            it.color = ink
            // Creates the tools (the brush tools load their stored presets), then pins the presets.
            it.tools
            it.brush = BrushLibrary.defaultBrush.copy(size = 6f, pressureSize = false, taperStart = 0f, taperEnd = 0f)
            // (Positions are exact: no snapping.)
            it.snapping.enabled = false
        }
    }

    private fun curveTool(c: EditorController, polyline: Boolean = false): CurveTool {
        val id = if (polyline) ToolId.POLYLINE else ToolId.CURVE
        c.selectTool(id)
        return c.tools.getValue(id) as CurveTool
    }

    private fun EditorController.drag(vararg pts: Pair<Float, Float>) {
        pointerDown(ToolPoint(pts[0].first, pts[0].second))
        for (i in 1 until pts.size) pointerMove(ToolPoint(pts[i].first, pts[i].second))
        pointerUp(ToolPoint(pts.last().first, pts.last().second))
    }

    private fun EditorController.tap(x: Float, y: Float) = drag(x to y)

    private fun EditorController.composite(): Bitmap {
        val out = BitmapUtils.createLayerBitmap(doc.width, doc.height)
        compositor.drawDocument(Canvas(out), null, target = null)
        return out
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }

    /** A fresh rendering of [content], as the layer's cache must be (I1). */
    private fun render(content: VectorContent): IntArray {
        val b = BitmapUtils.createLayerBitmap(w, h)
        VectorLayerRenderer.render(Canvas(b), content, Rect(0, 0, w, h), tips = TipCache(), document = Rect(0, 0, w, h))
        return pixels(b)
    }

    private fun alpha(c: Int) = c ushr 24

    /** Height of the painted run (alpha ≥ 128) in column [x] between rows [y0] and [y1]. */
    private fun thickness(b: Bitmap, x: Int, y0: Int = 0, y1: Int = h): Int = (y0 until y1).count { y -> alpha(b.getPixel(x, y)) >= 128 }

    private fun channelDiff(a: Int, b: Int): Int {
        var m = 0
        for (s in intArrayOf(24, 16, 8, 0)) m = max(m, abs(((a ushr s) and 0xFF) - ((b ushr s) and 0xFF)))
        return m
    }

    /** The live-vs-replay bar (§4.9e): ≥ 99.5 % of the painted pixels within ±2, max ≤ 12. */
    private fun assertParity(what: String, a: IntArray, b: IntArray) {
        var ok = 0
        var painted = 0
        var worst = 0
        for (i in a.indices) {
            if (a[i] == 0 && b[i] == 0) continue
            painted++
            val d = channelDiff(a[i], b[i])
            if (d <= 2) ok++
            worst = max(worst, d)
        }
        assertTrue("$what: something is painted", painted > 200)
        assertTrue("$what: ${ok * 100.0 / painted} % within ±2 (max $worst)", ok >= painted * 0.995 && worst <= 12)
    }

    // ------------------------------------------------------------------ §4.4 plain line follows the brush size

    @Test
    fun aLinkedPlainLineFollowsTheBrushSizeAndAnUnlinkedOneKeepsItsWidth() {
        val c = controller()
        val layer = c.activeLayer
        val tool = curveTool(c, polyline = true)
        tool.update { it.copy(stroke = CurveStroke.PLAIN) }
        assertTrue("linked by default", tool.settings.useBrushSize)
        c.brush = c.brush.copy(size = 8f)
        c.tap(40f, 130f); c.tap(320f, 130f)
        assertEquals(8f, tool.lineWidth, 0f)
        assertEquals(8f, thickness(c.composite(), 180).toFloat(), 1f)
        // The side size slider resizes the pending line live.
        c.brush = c.brush.copy(size = 20f)
        Snapshot.sendApplyNotifications()
        assertEquals(20f, tool.lineWidth, 0f)
        assertEquals(20f, thickness(c.composite(), 180).toFloat(), 1f)
        // Typing a width while linked resizes the brush.
        tool.setLineWidth(14f)
        assertEquals(14f, c.brush.size, 0f)
        tool.commit()
        assertEquals(1, c.undoManager.undoCount)
        assertEquals("the committed width is the brush size", 14f, thickness(layer.bitmap, 180, 100, 160).toFloat(), 1f)
        // Unlinked: the line's own width, whatever the brush.
        tool.update { it.copy(useBrushSize = false, plainWidth = 5f) }
        c.brush = c.brush.copy(size = 30f)
        Snapshot.sendApplyNotifications()
        assertEquals(5f, tool.lineWidth, 0f)
        c.tap(40f, 50f); c.tap(320f, 50f)
        tool.commit()
        assertEquals(5f, thickness(layer.bitmap, 180, 20, 90).toFloat(), 1f)
        tool.setLineWidth(9f)
        assertEquals(9f, tool.settings.plainWidth, 0f)
        assertEquals(30f, c.brush.size, 0f)
    }

    @Test
    fun storedSettingsFromBeforeAreLinked() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val c = controller()
        // v1.4 wrote no "useBrushSize": the tool reads it as linked (the release notes say so).
        c.settings.prefs.edit().putString("vec.curve", """{"closed":true,"plainWidth":3.0,"stroke":"PLAIN"}""").commit()
        val fresh = EditorController(ctx, c.doc, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), AppSettings(ctx))
        val tool = fresh.tools.getValue(ToolId.CURVE) as CurveTool
        assertTrue(tool.settings.useBrushSize)
        assertEquals(3f, tool.settings.plainWidth, 0f)
        assertEquals(CurveStroke.PLAIN, tool.settings.stroke)
    }

    // ------------------------------------------------------------------ §4.5 per-point thickness

    @Test
    fun thicknessWidensOnlyWhereSetWithASmoothBlend() {
        val c = controller()
        val layer = c.activeLayer
        val tool = curveTool(c, polyline = true)
        tool.update { it.copy(stroke = CurveStroke.PLAIN, useBrushSize = false, plainWidth = 6f) }
        c.tap(40f, 130f); c.tap(180f, 130f); c.tap(320f, 130f)
        tool.setWidth(1, 3f)
        tool.endNumericEdit()
        assertEquals(18f, tool.diameterAt(1), 0f)
        // The preview already shows it.
        assertEquals(18f, thickness(c.composite(), 180).toFloat(), 1f)
        tool.commit()
        assertEquals(1, c.undoManager.undoCount)
        val px = layer.bitmap
        assertEquals("300 % in the middle", 18f, thickness(px, 180).toFloat(), 1f)
        for (x in listOf(60, 110, 150, 250, 300)) {
            val t = if (x <= 180) (x - 40) / 140f else (320 - x) / 140f
            val e = t * t * (3f - 2f * t)
            assertEquals("smoothstep at x = $x", 6f * (1f + 2f * e), thickness(px, x).toFloat(), 1.01f)
        }
        // 0 % at the ends: tips under a pixel.
        c.tap(40f, 40f); c.tap(180f, 40f); c.tap(320f, 40f)
        tool.setWidth(0, 0f); tool.setWidth(2, 0f); tool.setWidth(1, 2f)
        tool.commit()
        assertTrue("start tip ${thickness(px, 41, 0, 90)}", thickness(px, 41, 0, 90) <= 1)
        assertTrue("end tip", thickness(px, 319, 0, 90) <= 1)
        assertEquals(12f, thickness(px, 180, 0, 90).toFloat(), 1f)
    }

    @Test
    fun aBrushFollowsTheThicknessEvenWhenItsSizeIgnoresPressure() {
        val c = controller()
        val layer = c.activeLayer
        c.brush = BrushLibrary.defaultBrush.copy(size = 8f, pressureSize = false, taperStart = 0f, taperEnd = 0f)
        val tool = curveTool(c, polyline = true)
        tool.update { it.copy(stroke = CurveStroke.BRUSH, taper = false) }
        c.tap(40f, 130f); c.tap(180f, 130f); c.tap(320f, 130f)
        tool.setWidth(1, 3f)
        tool.flushPreview()
        assertTrue(tool.brushLive)
        tool.commit()
        assertEquals(1, c.undoManager.undoCount)
        assertEquals(24f, thickness(layer.bitmap, 180).toFloat(), 2f)
        assertTrue(thickness(layer.bitmap, 45) in 5..12)
        assertTrue(thickness(layer.bitmap, 110) in 12..20)
        // The user's brush is left as it was.
        assertEquals(8f, c.brush.size, 0f)
        assertFalse(c.brush.pressureSize)
    }

    @Test
    fun hundredPercentEverywhereIsExactlyTheOldDrawing() {
        // Plain line: the same spec through the same commit as v1.4.
        val c = controller()
        val tool = curveTool(c)
        tool.update { it.copy(stroke = CurveStroke.PLAIN, useBrushSize = false, plainWidth = 7f, tension = 0.2f) }
        val pts = listOf(Vec2(40f, 200f), Vec2(130f, 50f), Vec2(230f, 190f), Vec2(320f, 60f))
        for (p in pts) c.tap(p.x, p.y)
        val path = tool.path()
        tool.commit()
        val ref = controller()
        val spec = VectorPaintSpec.build(null, 0, path, ink, 7f, LineCapStyle.ROUND, JoinStyle.ROUND)!!
        VectorCommit.commit(ref, ref.activeLayer, listOf(spec), "Curve")
        assertArrayEquals(pixels(ref.activeLayer.bitmap), pixels(c.activeLayer.bitmap))

        // Brush: exactly the path stroke a brush tool paints along the same samples.
        val c2 = controller()
        c2.brush = BrushLibrary.byId("softround")!!.copy(size = 12f)
        val tool2 = curveTool(c2)
        tool2.update { it.copy(stroke = CurveStroke.BRUSH, taper = true, taperPercent = 20f) }
        for (p in pts) c2.tap(p.x, p.y)
        tool2.flushPreview()
        val seed = tool2.brushSeed
        val path2 = tool2.path()
        tool2.commit()
        val ref2 = controller()
        ref2.brush = c2.brush
        ref2.selectTool(ToolId.BRUSH)
        val brush = ref2.tools.getValue(ToolId.BRUSH) as BrushTool
        val input = brushStrokeInput(path2, 0.2f)
        assertTrue(brush.beginPath(input, seed))
        val n = input.size
        brush.onUp(ToolPoint(input.x[n - 1], input.y[n - 1], input.pressure[n - 1], 1L, isStylus = true))
        assertArrayEquals(pixels(ref2.activeLayer.bitmap), pixels(c2.activeLayer.bitmap))
    }

    @Test
    fun aThicknessDragIsOneInToolStep() {
        val c = controller()
        val tool = curveTool(c, polyline = true)
        tool.update { it.copy(stroke = CurveStroke.PLAIN) }
        c.tap(40f, 130f); c.tap(180f, 130f); c.tap(320f, 130f)
        tool.select(1)
        // A slider drag: many values, then release.
        for (v in listOf(1.1f, 1.4f, 1.9f, 2.4f)) tool.setWidth(1, v)
        tool.endNumericEdit()
        // A second drag right away is its own step.
        tool.setWidth(1, 0.5f)
        tool.endNumericEdit()
        assertEquals(0.5f, tool.anchors[1].width, 0f)
        assertTrue(tool.undoStep())
        assertEquals(2.4f, tool.anchors[1].width, 0f)
        assertTrue(tool.undoStep())
        assertEquals(1f, tool.anchors[1].width, 0f)
        // Clamped to 0..300 %, non-finite ignored.
        tool.setWidth(1, 7f)
        assertEquals(3f, tool.anchors[1].width, 0f)
        tool.setWidth(1, Float.NaN)
        assertEquals(3f, tool.anchors[1].width, 0f)
        // "All points 100 %" is one step.
        tool.setWidth(0, 2f)
        tool.resetAllWidths()
        assertTrue(CurveGeometry.isUniformWidth(tool.anchors))
        assertTrue(tool.undoStep())
        assertEquals(2f, tool.anchors[0].width, 0f)
        // A point inserted on the line takes the thickness the line has there.
        tool.setWidth(0, 1f); tool.setWidth(1, 3f)
        c.tap(110f, 130f)
        assertEquals(4, tool.anchors.size)
        assertEquals(2f, tool.anchors[1].width, 0.05f)
        assertFalse(c.canUndo)
    }

    @Test
    fun theThicknessRingShowsTheRealDiameter() {
        val c = controller()
        val tool = curveTool(c, polyline = true)
        tool.update { it.copy(stroke = CurveStroke.PLAIN, useBrushSize = false, plainWidth = 20f) }
        c.tap(60f, 130f); c.tap(300f, 130f)
        tool.select(0)
        tool.setWidth(0, 2.5f)
        tool.thicknessRing = true
        assertEquals(50f, tool.diameterAt(0), 0f)
        val over = BitmapUtils.createLayerBitmap(w, h)
        c.drawOverlays(Canvas(over), 0f)
        // On the ring (25 px from the point), not halfway.
        assertTrue((-2..2).any { d -> alpha(over.getPixel(60, 130 - 25 + d)) > 0 })
        assertEquals(0, alpha(over.getPixel(60, 130 - 12)))
    }

    // ------------------------------------------------------------------ §4.9 vector layers

    @Test
    fun onAVectorLayerAPlainPathIsOneObjectAndOneStep() {
        val c = controller(vector = true)
        val layer = c.activeLayer
        val green = 0xFF40A060.toInt()
        c.brush = c.brush.copy(size = 10f)
        val tool = curveTool(c)
        tool.update { it.copy(stroke = CurveStroke.PLAIN, fill = true, fillColor = green) }
        c.tap(60f, 200f); c.tap(180f, 60f); c.tap(300f, 200f)
        tool.setWidth(1, 2f)
        tool.commit()
        assertEquals(1, c.undoManager.undoCount)
        val content = layer.vector!!
        val p = content.objects.single() as VPath
        assertEquals(VStrokeKind.PLAIN, p.stroke!!.kind)
        assertEquals("the absolute width", 10f, p.stroke!!.width, 0f)
        assertEquals(ink, p.stroke!!.color)
        assertEquals(VPaint.Solid(green), p.fill)
        assertEquals(listOf(1f, 2f, 1f), p.subpaths.single().anchors.map { it.width })
        assertFalse(p.polyline)
        assertArrayEquals("the cache is the rendering of the object", render(content), pixels(layer.bitmap))
        c.undo()
        assertEquals(VectorContent.EMPTY, layer.vector)
        assertTrue(pixels(layer.bitmap).all { it == 0 })
        c.redo()
        assertSame(content, layer.vector)
        assertArrayEquals(render(content), pixels(layer.bitmap))
    }

    @Test
    fun onAVectorLayerALiveBrushStrokeKeepsItsPixelsAndIsTheObjectsReplay() {
        for (fill in listOf(false, true)) {
            val c = controller(vector = true)
            val layer = c.activeLayer
            c.brush = BrushLibrary.byId("softround")!!.copy(size = 14f)
            val tool = curveTool(c)
            tool.update { it.copy(stroke = CurveStroke.BRUSH, taper = true, taperPercent = 15f, fill = fill, closed = fill, fillColor = 0xFFE0A020.toInt()) }
            c.tap(60f, 200f); c.tap(160f, 50f); c.tap(300f, 190f)
            tool.flushPreview()
            assertTrue(tool.brushLive)
            val seed = tool.brushSeed
            tool.commit()
            assertEquals("fill $fill: one step", 1, c.undoManager.undoCount)
            assertEquals("Curve", c.undoManager.undoLabel)
            val content = layer.vector!!
            val p = content.objects.single() as VPath
            val st = p.stroke!!
            assertEquals(VStrokeKind.BRUSH, st.kind)
            assertEquals(c.brush.sanitized(), st.brush)
            assertEquals(ToolId.BRUSH, st.brushTool)
            assertEquals(seed, st.seed)
            assertEquals(15f, st.taperPercent, 0f)
            assertEquals(fill, p.fill != null)
            assertParity("fill $fill", render(content), pixels(layer.bitmap))
            if (fill) {
                // Inside the fill, away from the stroke: exactly the layer's own drawing.
                val fresh = render(content)
                for ((x, y) in listOf(180 to 150, 170 to 170, 200 to 160)) assertEquals(fresh[y * w + x], layer.bitmap.getPixel(x, y))
            }
            c.undo()
            assertEquals(VectorContent.EMPTY, layer.vector)
            assertTrue(pixels(layer.bitmap).all { it == 0 })
            c.redo()
            assertSame(content, layer.vector)
        }
    }

    @Test
    fun objectsAreNotClippedByTheSelectionOrAlphaLock() {
        val c = controller(vector = true)
        val layer = c.activeLayer
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        Canvas(mask).drawRect(0f, 0f, 150f, h.toFloat(), Paint().apply { color = 0xFF000000.toInt() })
        c.setSelection(Selection.wrap(mask, Rect(0, 0, 150, h)), recordUndo = false)
        layer.alphaLocked = true
        c.brush = BrushLibrary.defaultBrush.copy(size = 10f)
        // A plain line: the preview isn't clipped either.
        val tool = curveTool(c, polyline = true)
        tool.update { it.copy(stroke = CurveStroke.PLAIN) }
        c.tap(40f, 60f); c.tap(320f, 60f)
        assertTrue("previewed beyond the selection", alpha(c.composite().getPixel(250, 60)) > 200)
        tool.commit()
        assertTrue(alpha(layer.bitmap.getPixel(250, 60)) > 200)
        // A brush stroke: painted beyond the selection, the selection is still there.
        tool.update { it.copy(stroke = CurveStroke.BRUSH) }
        c.tap(40f, 180f); c.tap(320f, 180f)
        tool.flushPreview()
        assertTrue("live beyond the selection", alpha(c.composite().getPixel(250, 180)) > 200)
        tool.commit()
        assertTrue(alpha(layer.bitmap.getPixel(250, 180)) > 200)
        assertNotNull(c.selection)
        assertTrue(layer.alphaLocked)
        assertEquals(2, layer.vector!!.objects.size)
        assertEquals(2, c.undoManager.undoCount)
        assertParity("unclipped", render(layer.vector!!), pixels(layer.bitmap))
    }

    @Test
    fun aBrushThatNeedsPixelsDrawsAPlainLineOnAVectorLayer() {
        val c = controller(vector = true)
        val layer = c.activeLayer
        c.selectTool(ToolId.SMUDGE)
        c.smudgeBrush = c.smudgeBrush.copy(size = 9f)
        val tool = curveTool(c)
        assertEquals(ToolId.SMUDGE, c.lastPaintTool)
        tool.update { it.copy(stroke = CurveStroke.BRUSH) }
        c.message = null
        c.tap(60f, 130f); c.tap(300f, 130f)
        assertTrue("says so: ${c.message}", c.message?.contains("needs a raster layer") == true)
        tool.commit()
        val p = layer.vector!!.objects.single() as VPath
        assertEquals(VStrokeKind.PLAIN, p.stroke!!.kind)
        assertEquals(9f, p.stroke!!.width, 0f)
        assertArrayEquals(render(layer.vector!!), pixels(layer.bitmap))
    }

    /** An imported-style cubic: sharp anchors with broken tangents, thickness, its own color. */
    private fun svgPath() = VPath(
        0,
        subpaths = listOf(VSubpath(listOf(
            VAnchor(50f, 200f, true, outX = 40f, outY = -90f),
            VAnchor(180f, 90f, true, inX = -50f, inY = -20f, outX = 30f, outY = 60f, width = 2.5f),
            VAnchor(310f, 170f, true, inX = 10f, inY = -70f, width = 0.5f),
        ))),
        stroke = VStrokeStyle(color = 0xFFA03050.toInt(), width = 9f),
    )

    /** A point on [p]'s outline (its middle sample). */
    private fun onPath(p: VPath): Vec2 {
        val pts = VectorOps.toVectorPath(p).flatten(0.25f).single().points
        return pts[pts.size / 3]
    }

    @Test
    fun aTappedPathReopensWithItsWidthHandlesAndColorAndCommitsBackUnchanged() {
        val c = controller(vector = true)
        val layer = c.activeLayer
        val id = c.vectors.addObjects(layer, listOf(svgPath()), "Import").single()
        val original = layer.vector!!
        val before = pixels(layer.bitmap)
        c.brush = c.brush.copy(size = 30f)
        val tool = curveTool(c)
        val q = onPath(original.objects[0] as VPath)
        c.tap(q.x, q.y)
        assertTrue(tool.isReopened)
        assertEquals((original.objects[0] as VPath).subpaths[0].anchors.map { it.toCurveAnchor() }, tool.anchors)
        assertEquals("its own width, unlinked", 9f, tool.lineWidth, 0f)
        assertFalse(tool.widthLinked)
        assertEquals("the main color shows its line color", 0xFFA03050.toInt(), c.color)
        assertEquals(CurveStroke.PLAIN, tool.settings.stroke)
        assertFalse("an untouched reopen is not the user's work", tool.hasUserChanges)
        // ✓ untouched: nothing changes, no step.
        tool.commit()
        assertFalse(tool.isReopened)
        assertEquals(1, c.undoManager.undoCount)
        assertSame(original, layer.vector)
        assertArrayEquals(before, pixels(layer.bitmap))
        assertEquals("the user's color is back", ink, c.color)
        assertTrue("the user's settings are back", tool.settings.useBrushSize)
        // Reopened, a point moved away and back: the same path (within 1e-3), committed unchanged.
        c.tap(q.x, q.y)
        assertTrue(tool.isReopened)
        tool.moveAnchor(1, Vec2(190f, 95f))
        tool.endNumericEdit()
        tool.moveAnchor(1, Vec2(180f, 90f))
        assertTrue(tool.hasPendingWork)
        tool.commit()
        val again = layer.vector!!.objects.single() as VPath
        val orig = original.objects[0] as VPath
        assertEquals(id, again.id)
        for ((a, b) in orig.subpaths[0].anchors.zip(again.subpaths[0].anchors)) {
            assertEquals(a.x, b.x, 1e-3f); assertEquals(a.y, b.y, 1e-3f)
            assertEquals(a.inX ?: 0f, b.inX ?: 0f, 1e-3f); assertEquals(a.outY ?: 0f, b.outY ?: 0f, 1e-3f)
            assertEquals(a.width, b.width, 0f)
            assertEquals(a.sharp, b.sharp)
        }
        assertEquals(orig.stroke, again.stroke)
        assertArrayEquals(render(layer.vector!!), pixels(layer.bitmap))
        assertNotNull(layer.vector)
    }

    @Test
    fun aReopenedEditIsOneStepAndCancellingRestoresTheLayerExactly() {
        val c = controller(vector = true)
        val layer = c.activeLayer
        c.vectors.addObjects(layer, listOf(svgPath()), "Import")
        val second = svgPath().copy(stroke = VStrokeStyle(color = 0xFF2080F0.toInt(), width = 4f), subpaths = listOf(VSubpath(listOf(VAnchor(30f, 30f), VAnchor(330f, 40f)))))
        c.vectors.addObjects(layer, listOf(second), "Import")
        val original = layer.vector!!
        val before = pixels(layer.bitmap)
        val tool = curveTool(c)
        val q = onPath(original.objects[0] as VPath)
        // ✕ after a drag: the layer is exactly as before, no step.
        c.tap(q.x, q.y)
        assertTrue(tool.isReopened)
        c.drag(180f to 90f, 200f to 130f, 220f to 150f)
        assertTrue(tool.hasUserChanges)
        tool.discard()
        assertFalse(tool.isReopened)
        assertSame(original, layer.vector)
        assertArrayEquals(before, pixels(layer.bitmap))
        assertEquals(2, c.undoManager.undoCount)
        // ✓ after a drag: ONE step "Edit path", the object keeps its id and place.
        c.tap(q.x, q.y)
        c.drag(180f to 90f, 200f to 130f, 220f to 150f)
        tool.setWidth(0, 3f)
        tool.commit()
        assertEquals(3, c.undoManager.undoCount)
        assertEquals(CurveTool.EDIT_PATH_LABEL, c.undoManager.undoLabel)
        val edited = layer.vector!!
        assertEquals(original.objects.map { it.id }, edited.objects.map { it.id })
        val p = edited.objects[0] as VPath
        assertEquals(Vec2(220f, 150f), Vec2(p.subpaths[0].anchors[1].x, p.subpaths[0].anchors[1].y))
        assertEquals(3f, p.subpaths[0].anchors[0].width, 0f)
        assertSame(original.objects[1], edited.objects[1])
        assertArrayEquals(render(edited), pixels(layer.bitmap))
        c.undo()
        assertSame(original, layer.vector)
        assertArrayEquals(before, pixels(layer.bitmap))
        c.redo()
        assertSame(edited, layer.vector)
        // Every point deleted: the object goes, one step.
        val q2 = onPath(p)
        c.tap(q2.x, q2.y)
        assertTrue(tool.isReopened)
        repeat(3) { tool.deleteAnchor(0) }
        assertFalse(tool.isReopened)
        assertEquals(1, layer.vector!!.objects.size)
        assertEquals(4, c.undoManager.undoCount)
        assertArrayEquals(render(layer.vector!!), pixels(layer.bitmap))
    }

    @Test
    fun aDragFromAPathStartsANewPathAndUndoClosesAnUntouchedReopen() {
        val c = controller(vector = true)
        val layer = c.activeLayer
        c.vectors.addObjects(layer, listOf(svgPath()), "Import")
        val tool = curveTool(c)
        val q = onPath(layer.vector!!.objects[0] as VPath)
        c.drag(q.x to q.y, q.x + 30f to q.y + 20f, q.x + 60f to q.y + 40f)
        assertFalse(tool.isReopened)
        assertEquals(1, tool.anchors.size)
        assertEquals(Vec2(q.x + 60f, q.y + 40f), tool.anchors[0].pos)
        tool.discard()
        // Undo with an untouched reopened path: it closes and the document undo goes on.
        c.tap(q.x, q.y)
        assertTrue(tool.isReopened)
        c.undo()
        assertFalse(tool.isReopened)
        assertEquals(VectorContent.EMPTY, layer.vector)
    }

    @Test
    fun aReopenedBrushPathKeepsItsBrushAndTexture() {
        val c = controller(vector = true)
        val layer = c.activeLayer
        val chalk = BrushLibrary.byId("chalk")!!.copy(size = 16f)
        c.brush = chalk
        val tool = curveTool(c)
        tool.update { it.copy(stroke = CurveStroke.BRUSH, taper = false) }
        c.tap(60f, 200f); c.tap(170f, 60f); c.tap(300f, 190f)
        tool.flushPreview()
        tool.commit()
        val first = layer.vector!!
        val made = first.objects.single() as VPath
        // Another brush picked meanwhile: the path keeps its own.
        c.brush = BrushLibrary.defaultBrush.copy(size = 3f)
        val q = onPath(made)
        c.tap(q.x, q.y)
        assertTrue(tool.isReopened)
        tool.commit()
        assertSame("untouched: unchanged", first, layer.vector)
        c.tap(q.x, q.y)
        c.drag(300f to 190f, 310f to 150f, 320f to 120f)
        tool.flushPreview()
        assertTrue(tool.brushLive)
        tool.commit()
        val edited = layer.vector!!.objects.single() as VPath
        assertEquals(made.stroke!!.brush, edited.stroke!!.brush)
        assertEquals(made.stroke!!.seed, edited.stroke!!.seed)
        assertEquals(Vec2(320f, 120f), Vec2(edited.subpaths[0].anchors[2].x, edited.subpaths[0].anchors[2].y))
        assertArrayEquals(render(layer.vector!!), pixels(layer.bitmap))
        assertEquals(3f, c.brush.size, 0f)
        assertEquals(2, c.undoManager.undoCount)
    }

    @Test
    fun aListenersAmendJoinsEveryCurveStep() {
        // I2: an edit listener that records a follow-up step (text wrap re-flow) folds it into the
        // step of a plain object, a brush object (fill + stroke + data: three edits) and an
        // "Edit path": each stays ONE step, and one undo takes back both.
        val c = controller(vector = true)
        val layer = c.activeLayer
        var followUps = 0
        var undone = 0
        c.addEditListener { e ->
            if (e.layer !== layer) return@addEditListener
            c.amendLastStep {
                c.pushUndo(object : com.brushwork.paint.engine.UndoAction {
                    override val label = "Follow-up"
                    override val byteSize = 0L
                    override fun undo(c: EditorController) { undone++ }
                    override fun redo(c: EditorController) { undone-- }
                })
            }
            followUps++
        }
        val tool = curveTool(c)
        tool.update { it.copy(stroke = CurveStroke.PLAIN) }
        c.tap(40f, 60f); c.tap(320f, 60f)
        tool.commit()
        assertEquals(1, c.undoManager.undoCount)
        assertTrue(followUps >= 1)
        tool.update { it.copy(stroke = CurveStroke.BRUSH, fill = true, closed = true) }
        c.tap(60f, 220f); c.tap(180f, 120f); c.tap(300f, 220f)
        tool.flushPreview()
        tool.commit()
        assertEquals(2, c.undoManager.undoCount)
        assertEquals("Curve", c.undoManager.undoLabel)
        val q = onPath(layer.vector!!.objects[0] as VPath)
        c.tap(q.x, q.y)
        assertTrue(tool.isReopened)
        tool.setWidth(0, 2f)
        tool.commit()
        assertEquals(3, c.undoManager.undoCount)
        assertEquals(CurveTool.EDIT_PATH_LABEL, c.undoManager.undoLabel)
        val before = undone
        c.undo()
        assertTrue("the follow-up went with the step", undone > before)
        assertEquals(2, c.undoManager.undoCount)
        assertEquals(2, layer.vector!!.objects.size)
    }

    @Test
    fun thePolylineToolReopensPolylinesOnly() {
        val c = controller(vector = true)
        val layer = c.activeLayer
        val poly = curveTool(c, polyline = true)
        poly.update { it.copy(stroke = CurveStroke.PLAIN) }
        c.tap(40f, 60f); c.tap(320f, 60f)
        poly.commit()
        val made = layer.vector!!.objects.single() as VPath
        assertTrue(made.polyline)
        assertEquals(1f, made.tension, 0f)
        // The curve tool doesn't reopen it (it adds a point there).
        val curve = curveTool(c)
        c.tap(180f, 60f)
        assertFalse(curve.isReopened)
        assertEquals(1, curve.anchors.size)
        curve.discard()
        // The polyline tool does.
        val p2 = curveTool(c, polyline = true)
        c.tap(180f, 60f)
        assertTrue(p2.isReopened)
        assertEquals(2, p2.anchors.size)
        p2.discard()
        assertNotNull(layer.vector)
    }
}
