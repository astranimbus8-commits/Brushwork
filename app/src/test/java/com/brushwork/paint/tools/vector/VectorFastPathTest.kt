package com.brushwork.paint.tools.vector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.BrushPreset
import com.brushwork.paint.brush.BrushTool
import com.brushwork.paint.brush.PathStrokeInput
import com.brushwork.paint.brush.StrokeResources
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import kotlin.math.sqrt

/**
 * The v1.3 fast paths of the vector tools keep their v1.2 results: the brush input of a path is
 * bit-identical to the old sampling, a curve / shape edited with drafts and incremental replays
 * commits exactly the pixels the v1.2 code painted (the whole path through onDown / onMove /
 * onUp), plain previews move to the overlay only while dragged (and only where that looks the
 * same), and the replay budget follows the measured speed of the device.
 */
@RunWith(RobolectricTestRunner::class)
class VectorFastPathTest {
    private val ink = 0xFF2A4C8E.toInt()
    private var savedSpeed = 1.0

    @Before
    fun saveSpeed() { savedSpeed = BrushStrokePreview.nsPerUnit }

    @After
    fun restoreSpeed() { BrushStrokePreview.nsPerUnit = savedSpeed }

    private fun controller(w: Int, h: Int, brush: BrushPreset? = null, layers: Int = 1): EditorController {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("brushwork_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val doc = Document("t", "t", w, h)
        repeat(layers) { doc.layers += Layer(doc.newLayerId(), "Layer ${it + 1}", BitmapUtils.createLayerBitmap(w, h)) }
        doc.activeLayerIndex = layers - 1
        return EditorController(ctx, doc, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), AppSettings(ctx)).also {
            it.color = ink
            it.tools
            if (brush != null) it.brush = brush
        }
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun Bitmap.pixels(): IntArray = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }

    private fun EditorController.composite(): Bitmap {
        val out = BitmapUtils.createLayerBitmap(doc.width, doc.height)
        compositor.drawDocument(Canvas(out), null, target = null)
        return out
    }

    /** The tool overlays at zoom 1 (document = screen pixels). */
    private fun EditorController.overlay(): Bitmap {
        val out = BitmapUtils.createLayerBitmap(doc.width, doc.height)
        drawOverlays(Canvas(out), 0f)
        return out
    }

    private fun alpha(c: Int) = c ushr 24

    /** A finger drag through [pts], one frame (16 ms) per point, then a rest. */
    private fun EditorController.slowDrag(vararg pts: Vec2) {
        pointerDown(ToolPoint(pts[0].x, pts[0].y))
        idle(16)
        for (i in 1 until pts.size) {
            pointerMove(ToolPoint(pts[i].x, pts[i].y))
            idle(16)
        }
        pointerUp(ToolPoint(pts.last().x, pts.last().y))
        idle(16)
    }

    /** v1.2's brush input: `CurveGeometry.sample` plus the taper ramp, as stylus points. */
    private fun v12Points(path: VectorPath, taperFraction: Float): List<ToolPoint> {
        val samples = CurveGeometry.sample(path, BRUSH_SAMPLE_SPACING)
        if (samples.size < 2) return emptyList()
        val total = VectorPath.length(samples)
        val taperLen = total * taperFraction.coerceIn(0f, 0.5f)
        var dist = 0f
        return samples.mapIndexed { i, p ->
            if (i > 0) dist += samples[i].distanceTo(samples[i - 1])
            val pressure = if (taperLen > 0f) CurveGeometry.taperPressure(dist, total, taperLen) else 1f
            ToolPoint(p.x, p.y, pressure, i.toLong(), isStylus = true)
        }
    }

    /** Layer pixels after v1.2 painted [path] (the whole stroke through onDown / onMove / onUp). */
    private fun v12Paint(w: Int, h: Int, brush: BrushPreset, path: VectorPath, taperFraction: Float): IntArray {
        val c = controller(w, h, brush)
        c.selectTool(ToolId.BRUSH)
        val t = c.tools.getValue(ToolId.BRUSH) as BrushTool
        val pts = v12Points(path, taperFraction)
        t.onDown(pts[0])
        for (i in 1 until pts.lastIndex) t.onMove(pts[i])
        t.onUp(pts.last())
        return c.doc.activeLayer.bitmap.pixels()
    }

    // ------------------------------------------------------------------ brush input

    @Test
    fun brushInputIsBitIdenticalToTheV12Sampling() {
        val anchors = listOf(CurveAnchor(40f, 60f), CurveAnchor(300f, 20f), CurveAnchor(420f, 260f, sharp = true), CurveAnchor(90f, 330f))
        val box = ShapeBox(250f, 200f, 300f, 170f, 17f)
        val paths = listOf(
            CurveGeometry.toPath(anchors, closed = false, tension = 0f, polyline = false),
            CurveGeometry.toPath(anchors, closed = true, tension = 0.4f, polyline = false),
            CurveGeometry.toPath(anchors, closed = false, tension = 1f, polyline = true),
            CurveGeometry.toPath(anchors, closed = true, tension = 1f, polyline = true),
            ShapeGeometry.brushOutline(ShapeType.RECTANGLE, box, OutlineParams(corner = CornerStyle.ROUND, cornerRadius = 25f), 8f, ArrowHeads.END, ArrowHeadStyle.FILLED, 4f),
            ShapeGeometry.brushOutline(ShapeType.ELLIPSE, box, OutlineParams(), 8f, ArrowHeads.END, ArrowHeadStyle.FILLED, 4f),
            ShapeGeometry.brushOutline(ShapeType.STAR, box, OutlineParams(starPoints = 7, corner = CornerStyle.BEVEL, cornerRadius = 9f), 8f, ArrowHeads.END, ArrowHeadStyle.FILLED, 4f),
            ShapeGeometry.brushOutline(ShapeType.ARROW, ShapeBox.line(Vec2(30f, 40f), Vec2(400f, 310f)), OutlineParams(), 8f, ArrowHeads.BOTH, ArrowHeadStyle.OPEN, 4f),
            ShapeGeometry.brushOutline(ShapeType.LINE, ShapeBox.line(Vec2(30f, 40f), Vec2(30.5f, 40.25f)), OutlineParams(), 8f, ArrowHeads.END, ArrowHeadStyle.FILLED, 4f),
        )
        val out = PathStrokeInput()
        for ((k, path) in paths.withIndex()) {
            for (taper in listOf(0f, 0.2f, 0.5f)) {
                val ref = v12Points(path, taper)
                brushStrokeInput(path, taper, out)
                assertEquals("path $k: point count", ref.size, out.size)
                for (i in ref.indices) {
                    assertEquals("path $k point $i x", ref[i].x.toRawBits(), out.x[i].toRawBits())
                    assertEquals("path $k point $i y", ref[i].y.toRawBits(), out.y[i].toRawBits())
                    assertEquals("path $k point $i pressure", ref[i].pressure.toRawBits(), out.pressure[i].toRawBits())
                }
            }
        }
    }

    // ------------------------------------------------------------------ committed pixels

    private val deterministicBrush = BrushLibrary.defaultBrush.copy(size = 9f)

    @Test
    fun curveEditedWithDraftsCommitsTheV12Pixels() {
        for (taper in listOf(false, true)) {
            val w = 1000
            val h = 900
            val c = controller(w, h, deterministicBrush)
            c.selectTool(ToolId.CURVE)
            val tool = c.tools.getValue(ToolId.CURVE) as CurveTool
            tool.update { it.copy(stroke = CurveStroke.BRUSH, fill = false, closed = false, tension = 0f, taper = taper, taperPercent = 15f) }
            for (p in listOf(Vec2(60f, 80f), Vec2(900f, 150f), Vec2(120f, 450f), Vec2(880f, 600f), Vec2(200f, 840f))) {
                c.pointerDown(ToolPoint(p.x, p.y)); idle(40); c.pointerUp(ToolPoint(p.x, p.y)); idle(250)
            }
            val brushTool = c.tools.getValue(ToolId.BRUSH) as BrushTool
            // Drag the middle anchor, then the last one: the long stroke follows as a draft.
            var sawDraft = false
            c.pointerDown(ToolPoint(120f, 450f)); idle(16)
            for (i in 1..12) {
                c.pointerMove(ToolPoint(120f + 14f * i, 450f + 6f * i)); idle(16)
                sawDraft = sawDraft || brushTool.isDraft
            }
            c.pointerUp(ToolPoint(288f, 522f)); idle(16)
            c.slowDrag(Vec2(200f, 840f), Vec2(260f, 800f), Vec2(330f, 780f))
            assertTrue("taper=$taper: the long stroke was dragged as a draft", sawDraft)
            // The path rests: the draft becomes exact part by part.
            idle(2000)
            assertFalse("taper=$taper: refined once the path rests", brushTool.isDraft)
            val path = tool.path()
            tool.commit()
            val got = c.doc.activeLayer.bitmap.pixels()
            val ref = v12Paint(w, h, deterministicBrush, path, if (taper) 0.15f else 0f)
            assertTrue(got.any { it != 0 })
            assertEquals("taper=$taper: pixels differing from v1.2", 0, got.indices.count { got[it] != ref[it] })
            assertEquals(1, c.undoManager.undoCount)
        }
    }

    @Test
    fun curveCommittedMidDraftStillPaintsTheExactStroke() {
        val w = 900
        val h = 700
        val c = controller(w, h, deterministicBrush)
        c.selectTool(ToolId.POLYLINE)
        val tool = c.tools.getValue(ToolId.POLYLINE) as CurveTool
        tool.update { it.copy(stroke = CurveStroke.BRUSH, fill = false, taper = false) }
        for (p in listOf(Vec2(40f, 40f), Vec2(860f, 60f), Vec2(60f, 640f), Vec2(850f, 660f))) {
            c.pointerDown(ToolPoint(p.x, p.y)); idle(40); c.pointerUp(ToolPoint(p.x, p.y)); idle(250)
        }
        // A drag, then ✓ right away (the stroke on screen is still a draft).
        c.pointerDown(ToolPoint(860f, 60f)); idle(16)
        for (i in 1..6) { c.pointerMove(ToolPoint(860f - 20f * i, 60f + 30f * i)); idle(16) }
        c.pointerUp(ToolPoint(740f, 240f))
        val path = tool.path()
        tool.commit()
        val got = c.doc.activeLayer.bitmap.pixels()
        val ref = v12Paint(w, h, deterministicBrush, path, 0f)
        assertEquals(0, got.indices.count { got[it] != ref[it] })
    }

    @Test
    fun brushShapeResizedWithDraftsCommitsTheV12Pixels() {
        val w = 1000
        val h = 1000
        val c = controller(w, h, deterministicBrush)
        c.selectTool(ToolId.SHAPE)
        val tool = (c.tools.getValue(ToolId.SHAPE) as ShapeTool).also { it.update { s -> s.copy(editable = false) } }
        tool.update { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE, strokeWith = ShapeStroke.BRUSH, useBrushSize = true, corner = CornerStyle.ROUND, cornerRadius = 40f, fromCenter = false, keepProportions = false, snapAngle = false) }
        // Drag-create, then drag the bottom-right handle.
        c.slowDrag(Vec2(100f, 100f), Vec2(300f, 260f), Vec2(600f, 520f), Vec2(700f, 640f))
        val created = tool.box
        assertNotNull(created)
        c.slowDrag(Vec2(700f, 640f), Vec2(760f, 700f), Vec2(820f, 780f), Vec2(860f, 900f))
        idle(2000)
        val box = tool.box!!
        assertTrue("the handle resized the shape", box.w > created!!.w + 100f)
        val s = tool.settings
        val path = ShapeGeometry.brushOutline(s.type, box, s.outlineParams, tool.strokeWidth, s.arrowHeads, s.arrowHeadStyle, s.arrowHeadScale)
        tool.commit()
        val got = c.doc.activeLayer.bitmap.pixels()
        val ref = v12Paint(w, h, deterministicBrush, path, 0f)
        assertTrue(got.any { it != 0 })
        assertEquals("pixels differing from v1.2", 0, got.indices.count { got[it] != ref[it] })
        assertEquals(1, c.undoManager.undoCount)
    }

    // ------------------------------------------------------------------ plain previews

    /** A point on the ellipse inscribed in [b] (axis aligned), at 45 degrees. */
    private fun onEllipse(b: ShapeBox): Pair<Int, Int> {
        val k = 1f / sqrt(2f)
        return (b.cx + b.w / 2f * k).toInt() to (b.cy + b.h / 2f * k).toInt()
    }

    @Test
    fun plainShapeIsDrawnInTheOverlayOnlyWhileDragged() {
        val c = controller(200, 200)
        c.selectTool(ToolId.SHAPE)
        val tool = (c.tools.getValue(ToolId.SHAPE) as ShapeTool).also { it.update { s -> s.copy(editable = false) } }
        tool.update { it.copy(type = ShapeType.ELLIPSE, style = ShapeStyle.STROKE, strokeWith = ShapeStroke.PLAIN, useBrushSize = false, strokeWidth = 6f, fromCenter = false, keepProportions = false) }
        c.pointerDown(ToolPoint(20f, 20f))
        c.pointerMove(ToolPoint(100f, 100f))
        c.pointerMove(ToolPoint(180f, 180f))
        // Mid-drag: the ellipse is drawn over the canvas, the layer is left alone...
        val dragged = ShapeBox.fromCorners(Vec2(20f, 20f), Vec2(180f, 180f))
        val (x0, y0) = onEllipse(dragged)
        assertNull("no render override while dragging", c.renderOverride)
        assertTrue("the overlay shows the shape", alpha(c.overlay().getPixel(x0, y0)) > 150)
        assertEquals(0, alpha(c.composite().getPixel(x0, y0)))
        // ...so further moves never re-render a tile.
        c.tiles.update(c.compositor)
        c.pointerMove(ToolPoint(170f, 176f))
        c.pointerMove(ToolPoint(160f, 172f))
        assertFalse("moving the shape re-renders no tile", c.tiles.hasDirty)
        c.pointerUp(ToolPoint(160f, 172f))
        // Lifted: back in the layer (blend mode, masks... exactly as it will be painted).
        val box = tool.box!!
        val (x1, y1) = onEllipse(box)
        assertNotNull(c.renderOverride)
        val shot = c.composite()
        assertTrue("the layer preview shows the shape", alpha(shot.getPixel(x1, y1)) > 150)
        assertEquals("not in the overlay any more", 0, alpha(c.overlay().getPixel(x1, y1)))
        // Resizing moves it to the overlay and back again; the commit paints the preview.
        c.pointerDown(ToolPoint(box.left + box.w, box.top + box.h))
        c.pointerMove(ToolPoint(150f, 150f))
        assertNull(c.renderOverride)
        c.pointerUp(ToolPoint(150f, 150f))
        assertNotNull(c.renderOverride)
        val preview = c.composite().pixels()
        tool.commit()
        assertNull(c.renderOverride)
        val layer = c.doc.activeLayer.bitmap.pixels()
        assertTrue(layer.any { it != 0 })
        assertEquals("committed == previewed", 0, layer.indices.count { layer[it] != preview[it] })
    }

    @Test
    fun plainPreviewStaysInTheLayerWhereTheOverlayWouldLookDifferent() {
        // Under a visible layer, at half opacity, with a blend mode or alpha lock, or zoomed in so
        // far that the canvas shows crisp pixels: the compositor keeps drawing the dragged shape.
        val cases: List<(EditorController) -> Unit> = listOf(
            { c -> c.doc.activeLayerIndex = 0 },
            { c -> c.doc.activeLayer.opacity = 0.5f },
            { c -> c.doc.activeLayer.blendMode = com.brushwork.paint.model.LayerBlendMode.MULTIPLY },
            { c -> c.doc.activeLayer.alphaLocked = true },
            { c -> c.viewTransform.set(android.graphics.Matrix().apply { setScale(4f, 4f) }) },
        )
        for ((k, setUp) in cases.withIndex()) {
            val c = controller(200, 200, layers = 2)
            setUp(c)
            c.selectTool(ToolId.SHAPE)
            val tool = (c.tools.getValue(ToolId.SHAPE) as ShapeTool).also { it.update { s -> s.copy(editable = false) } }
            tool.update { it.copy(type = ShapeType.ELLIPSE, style = ShapeStyle.STROKE_FILL, strokeWith = ShapeStroke.PLAIN) }
            c.pointerDown(ToolPoint(20f, 20f))
            c.pointerMove(ToolPoint(100f, 100f))
            c.pointerMove(ToolPoint(180f, 180f))
            assertTrue("case $k: previewed by the compositor while dragging", c.renderOverride is VectorPreview)
            c.pointerUp(ToolPoint(180f, 180f))
            tool.discard()
        }
        // Hidden layers above don't count.
        val c = controller(200, 200, layers = 2)
        c.doc.activeLayerIndex = 0
        c.doc.layers[1].visible = false
        c.selectTool(ToolId.SHAPE)
        val tool = (c.tools.getValue(ToolId.SHAPE) as ShapeTool).also { it.update { s -> s.copy(editable = false) } }
        tool.update { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE, strokeWith = ShapeStroke.PLAIN) }
        c.pointerDown(ToolPoint(20f, 20f))
        c.pointerMove(ToolPoint(120f, 150f))
        assertNull(c.renderOverride)
        c.pointerUp(ToolPoint(120f, 150f))
        assertNotNull(c.renderOverride)
        tool.discard()
    }

    @Test
    fun plainPolylineFollowsADraggedPointInTheOverlay() {
        val c = controller(240, 200)
        c.selectTool(ToolId.POLYLINE)
        val tool = c.tools.getValue(ToolId.POLYLINE) as CurveTool
        tool.update { it.copy(stroke = CurveStroke.PLAIN, plainWidth = 16f, fill = false) }
        for (p in listOf(Vec2(20f, 100f), Vec2(120f, 40f), Vec2(220f, 100f))) {
            c.pointerDown(ToolPoint(p.x, p.y)); c.pointerUp(ToolPoint(p.x, p.y))
        }
        assertTrue(c.renderOverride is VectorPreview)
        c.tiles.update(c.compositor)
        // 6 px beside the middle of the first segment once the middle point is at (120, 170):
        // inside the 16 px line, outside the thin guide drawn along the path.
        val a = Vec2(20f, 100f)
        val b = Vec2(120f, 170f)
        val n = (b - a).normalized().let { Vec2(-it.y, it.x) }
        val probe = a.lerp(b, 0.5f) + n * 6f
        val px = probe.x.toInt()
        val py = probe.y.toInt()
        c.pointerDown(ToolPoint(120f, 40f))
        c.pointerMove(ToolPoint(120f, 90f))
        c.pointerMove(ToolPoint(120f, 160f))
        c.pointerMove(ToolPoint(120f, 170f))
        assertNull("no render override while dragging", c.renderOverride)
        assertEquals("the moved line is in the overlay", ink, c.overlay().getPixel(px, py))
        assertEquals(0, alpha(c.composite().getPixel(px, py)))
        c.tiles.update(c.compositor)
        c.pointerMove(ToolPoint(121f, 172f))
        c.pointerMove(ToolPoint(120f, 170f))
        assertFalse("moving the point re-renders no tile", c.tiles.hasDirty)
        c.pointerUp(ToolPoint(120f, 170f))
        assertTrue(c.renderOverride is VectorPreview)
        assertEquals(ink, c.composite().getPixel(px, py))
        assertEquals("not in the overlay any more", 0, alpha(c.overlay().getPixel(px, py)))
        tool.commit()
        assertEquals(ink, c.doc.activeLayer.bitmap.getPixel(px, py))
        assertEquals(1, c.undoManager.undoCount)
    }

    // ------------------------------------------------------------------ smudge / blur

    @Test
    fun slowFromScratchStrokeWaitsForTheDragToEnd() {
        val c = controller(200, 200)
        val layer = c.doc.activeLayer
        c.editWholeLayer(layer, "Seed") { b ->
            Canvas(b).drawRect(0f, 0f, 100f, 200f, android.graphics.Paint().apply { color = 0xFFFF0000.toInt() })
        }
        c.undoManager.clear()
        val preview = BrushStrokePreview(c) { ToolId.SMUDGE }
        // Every replay from scratch counts as "longer than a frame".
        preview.dragReplayLimitMs = -1L
        fun line(y: Float) = VectorPath.polyline(listOf(Vec2(60f, y), Vec2(160f, y)))
        val a = line(60f)
        preview.request(a.ops) { brushStrokeInput(a, out = it) }
        preview.flush()
        assertTrue(preview.isLive)
        val shownA = layer.bitmap.pixels()
        // A drag: the smudge stays where it is until the finger lifts...
        preview.interacting = true
        for (y in listOf(80f, 100f, 120f)) {
            val p = line(y)
            preview.request(p.ops) { brushStrokeInput(p, out = it) }
            preview.flush()
            idle(16)
        }
        assertTrue("held back while dragging", preview.hasPending)
        assertTrue(shownA.contentEquals(layer.bitmap.pixels()))
        // ...then follows the last position.
        preview.interacting = false
        idle(16)
        assertFalse(preview.hasPending)
        val shownC = layer.bitmap.pixels()
        assertFalse(shownA.contentEquals(shownC))
        val last = line(120f)
        assertTrue(preview.commit(last.ops) { brushStrokeInput(last, out = it) })
        assertFalse(preview.isLive)
        assertEquals(1, c.undoManager.undoCount)
    }

    @Test
    fun draftIsRefinedOnlyOnceTheChangesStop() {
        // Changes without a finger on the canvas (a nudge arrow held down, a numeric field
        // scrubbed): each one moves the whole long stroke, drawn as a draft. Refining it between
        // two changes would be thrown away by the next one, so it waits until they stop.
        val c = controller(1600, 700, deterministicBrush)
        c.selectTool(ToolId.CURVE)
        val preview = BrushStrokePreview(c) { ToolId.BRUSH }
        val stamper = StrokeResources.of(c).stamper
        fun path(dy: Float) = VectorPath.polyline(listOf(Vec2(20f, 100f + dy), Vec2(1580f, 140f + dy), Vec2(20f, 600f + dy)))
        for (k in 0 until 6) {
            val p = path(k * 3f)
            preview.request(p.ops) { brushStrokeInput(p, out = it) }
            idle(16)
            assertTrue("change $k: a draft", preview.isDraft)
            val s = stamper.stampCount
            idle(64)
            assertEquals("change $k: not refined while the changes go on", s, stamper.stampCount)
        }
        // They stopped: refined part by part after a moment.
        idle(3000)
        assertFalse("refined once the path rests", preview.isDraft)
        assertTrue(preview.isLive)
        preview.end()
        assertNull(c.renderOverride)
    }

    // ------------------------------------------------------------------ replay budget

    @Test
    fun replayBudgetFollowsTheMeasuredSpeed() {
        BrushStrokePreview.nsPerUnit = 0.5
        assertEquals("fast device: the largest budget", BrushStrokePreview.MAX_BUDGET, BrushStrokePreview.budget(), 0f)
        BrushStrokePreview.nsPerUnit = 12.0
        assertEquals("6 ms of work at 12 ns per unit", 500_000f, BrushStrokePreview.budget(), 1f)
        BrushStrokePreview.nsPerUnit = 1000.0
        assertEquals("never below the smallest budget", BrushStrokePreview.MIN_BUDGET, BrushStrokePreview.budget(), 0f)

        // A replay measures the real speed: from a far too slow guess, the estimate comes down.
        val c = controller(1600, 400, deterministicBrush.copy(size = 6f))
        c.selectTool(ToolId.CURVE)
        val preview = BrushStrokePreview(c) { ToolId.BRUSH }
        BrushStrokePreview.nsPerUnit = 45.0
        val path = VectorPath.polyline(listOf(Vec2(20f, 100f), Vec2(1580f, 120f), Vec2(20f, 300f)))
        preview.request(path.ops) { brushStrokeInput(path, out = it) }
        preview.flush()
        assertTrue(preview.isLive)
        assertTrue("learned from the replay (${BrushStrokePreview.nsPerUnit})", BrushStrokePreview.nsPerUnit < 45.0)
        preview.end()
        assertNull(c.renderOverride)
    }
}
