package com.brushwork.paint.vector.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.BrushPreset
import com.brushwork.paint.brush.BrushTool
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeOutlines
import com.brushwork.paint.tools.vector.ShapeStroke
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.tools.vector.VectorRenderer
import com.brushwork.paint.tools.vector.brushStrokeInput
import com.brushwork.paint.tools.vector.toAndroidPath
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VStop
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VStrokeKind
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorOps
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
import org.robolectric.RuntimeEnvironment
import kotlin.math.abs
import kotlin.math.max

/**
 * v1.5 F2: the reference [VectorLayerRenderer]. A brush-stroked path or shape re-renders like the
 * live path stroke that painted it (what A3 / A4 commit through keepLayerData + appendData, so a
 * later re-render must not change it); plain lines, varying widths (§4.5), fills, gradients,
 * opacity, the region and `exclude`.
 */
@RunWith(RobolectricTestRunner::class)
class VectorLayerRendererRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()
    private val w = 360
    private val h = 260
    private val all = Rect(0, 0, w, h)

    private fun setup(): EditorController {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", w, h)
        repeat(2) { i -> doc.layers += Layer(doc.newLayerId(), "Layer ${i + 1}", BitmapUtils.createLayerBitmap(w, h)) }
        doc.activeLayerIndex = 1
        return EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
    }

    private fun pixels(b: Bitmap) = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }

    private fun render(objects: List<VObject>, region: Rect = all, exclude: Set<Long> = emptySet()): IntArray {
        val b = BitmapUtils.createLayerBitmap(w, h)
        VectorLayerRenderer.render(Canvas(b), VectorContent(objects = objects, nextId = 100), region, exclude, TipCache())
        return pixels(b)
    }

    private fun channelDiff(a: Int, b: Int): Int {
        var m = 0
        for (s in intArrayOf(24, 16, 8, 0)) m = max(m, abs(((a ushr s) and 0xFF) - ((b ushr s) and 0xFF)))
        return m
    }

    /**
     * Fraction of the painted pixels (painted in [a] or [b]; the empty rest of the canvas would
     * hide differences) within ±2, and the largest difference.
     */
    private fun compare(a: IntArray, b: IntArray): Pair<Double, Int> {
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
        return (if (painted == 0) 0.0 else ok.toDouble() / painted) to worst
    }

    // ------------------------------------------------------------------ brush outlines

    private val curve = VSubpath(listOf(VAnchor(50f, 190f), VAnchor(130f, 60f), VAnchor(230f, 170f), VAnchor(310f, 70f)))

    /** The live path stroke the Curve / Shape tools paint (BrushStrokePreview.commit), on a raster layer. */
    private fun livePathStroke(preset: BrushPreset, color: Int, seed: Long, input: com.brushwork.paint.brush.PathStrokeInput): IntArray {
        val c = setup()
        c.selectTool(ToolId.BRUSH)
        val b = c.tools.getValue(ToolId.BRUSH) as BrushTool
        c.brush = preset
        c.color = color
        assertTrue(b.beginPath(input, seed))
        val n = input.size
        b.onUp(ToolPoint(input.x[n - 1], input.y[n - 1], input.pressure[n - 1], n.toLong(), isStylus = true))
        assertEquals(1, c.undoManager.undoCount)
        return pixels(c.activeLayer.bitmap)
    }

    @Test
    fun aBrushOutlineReplaysTheLivePathStroke() {
        val presets = listOf(
            "Pen" to BrushLibrary.defaultBrush,
            "Soft" to BrushLibrary.byId("softround")!!,
            "Pencil" to BrushLibrary.byId("pencil")!!,
            "Chalk" to BrushLibrary.byId("chalk")!!,
            "Airbrush" to BrushLibrary.byId("airbrush")!!,
            "G-pen" to BrushLibrary.byId("gpen")!!,
        )
        val color = 0xFF8030A0.toInt()
        for ((name, base) in presets) {
            for (taperPercent in listOf(0f, 20f)) {
                val preset = base.copy(size = 14f)
                val seed = 4242L
                val path = VPath(
                    7, subpaths = listOf(curve),
                    stroke = VStrokeStyle(VStrokeKind.BRUSH, color, 14f, brushTool = ToolId.BRUSH, brush = preset, seed = seed, taperPercent = taperPercent),
                )
                val input = brushStrokeInput(VectorOps.toVectorPath(path), taperPercent / 100f)
                val live = livePathStroke(preset, color, seed, input)
                val replay = render(listOf(path))
                val (within, worst) = compare(live, replay)
                val what = "$name taper $taperPercent %"
                assertTrue("$what painted", live.count { it != 0 } > 500)
                assertTrue("$what: ${"%.4f".format(within * 100)} % within ±2 (max $worst)", within >= 0.995)
                assertTrue("$what: max diff $worst", worst <= 12)
            }
        }
    }

    @Test
    fun aBrushShapeOutlineReplaysTheLivePathStroke() {
        val preset = BrushLibrary.byId("softround")!!.copy(size = 12f)
        val o = ShapeObject(
            ShapeType.STAR, cx = 180f, cy = 130f, w = 200f, h = 160f, rotation = 12f, style = ShapeStyle.STROKE,
            strokeWidth = 12f, strokeColor = 0xFF206040.toInt(), strokeWith = ShapeStroke.BRUSH, brushTool = ToolId.BRUSH.name, brushPreset = preset,
        )
        val s = VShape(3, shape = o, seed = 77L)
        val live = livePathStroke(preset, o.strokeColor, 77L, brushStrokeInput(ShapeOutlines.brushOutline(o), 0f))
        val (within, worst) = compare(live, render(listOf(s)))
        assertTrue("${within * 100} % within ±2 (max $worst)", within >= 0.995 && worst <= 12)
    }

    // ------------------------------------------------------------------ plain lines and widths

    /**
     * [draw] (document px) rasterized the way the renderer draws paths and shapes: clipped to
     * each square of its tile grid (Skia's anti-aliasing depends on where a clip cuts a path).
     */
    private fun tiled(draw: (Canvas) -> Unit): IntArray {
        val b = BitmapUtils.createLayerBitmap(w, h)
        val c = Canvas(b)
        val t = VectorLayerRenderer.TILE
        for (y in 0 until h step t) for (x in 0 until w step t) {
            c.save()
            c.clipRect(x, y, x + t, y + t)
            draw(c)
            c.restore()
        }
        return pixels(b)
    }

    @Test
    fun aPlainLineIsAPaintStroke() {
        val p = VPath(
            1, subpaths = listOf(curve), tension = 0.3f,
            stroke = VStrokeStyle(color = 0xFF000000.toInt(), width = 9f, cap = com.brushwork.paint.tools.vector.LineCapStyle.BUTT, join = com.brushwork.paint.tools.vector.JoinStyle.BEVEL),
        )
        val path = VectorOps.toVectorPath(p).toAndroidPath()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = 0xFF000000.toInt(); strokeWidth = 9f
            strokeCap = Paint.Cap.BUTT; strokeJoin = Paint.Join.BEVEL; strokeMiter = 4f
        }
        assertTrue(w > VectorLayerRenderer.TILE && h > VectorLayerRenderer.TILE)
        assertArrayEquals(tiled { it.drawPath(path, paint) }, render(listOf(p)))
        // A re-render of any region made of whole tiles gives exactly those pixels.
        val whole = render(listOf(p))
        val part = render(listOf(p), Rect(VectorLayerRenderer.TILE, 0, w, h))
        for (y in 0 until h) for (x in VectorLayerRenderer.TILE until w) assertEquals(whole[y * w + x], part[y * w + x])
    }

    /** Height of the painted run (alpha ≥ 128) in column [x]. */
    private fun thickness(px: IntArray, x: Int): Int = (0 until h).count { y -> px[y * w + x] ushr 24 >= 128 }

    @Test
    fun varyingWidthsWidenOnlyWhereSetWithASmoothstepBlend() {
        val line = VSubpath(listOf(VAnchor(40f, 130f, width = 1f), VAnchor(180f, 130f, width = 3f), VAnchor(320f, 130f, width = 1f)))
        val p = VPath(1, subpaths = listOf(line), polyline = true, stroke = VStrokeStyle(color = 0xFF000000.toInt(), width = 6f))
        val px = render(listOf(p))
        assertEquals(18f, thickness(px, 180).toFloat(), 1f)
        // Smoothstep along the arc length: 6 · (1 + 2 · e(t)), e = t²(3 − 2t).
        for (x in listOf(60, 100, 140, 220, 300)) {
            val t = if (x <= 180) (x - 40) / 140f else (320 - x) / 140f
            val e = t * t * (3f - 2f * t)
            assertEquals("x = $x", 6f * (1f + 2f * e), thickness(px, x).toFloat(), 1.01f)
        }
        // 0 % at the ends gives tips under a pixel.
        val tips = VPath(1, subpaths = listOf(line.copy(anchors = line.anchors.mapIndexed { i, a -> if (i == 1) a else a.copy(width = 0f) })), polyline = true, stroke = VStrokeStyle(color = 0xFF000000.toInt(), width = 6f))
        val tp = render(listOf(tips))
        assertTrue(thickness(tp, 41) <= 1)
        assertTrue(thickness(tp, 180) >= 17)
        // All widths 100 %: exactly the plain stroke.
        val plain = p.copy(subpaths = listOf(line.copy(anchors = line.anchors.map { it.copy(width = 1f) })))
        val expected = BitmapUtils.createLayerBitmap(w, h)
        Canvas(expected).drawPath(
            VectorOps.toVectorPath(plain).toAndroidPath(),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF000000.toInt(); strokeWidth = 6f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND },
        )
        assertArrayEquals(pixels(expected), render(listOf(plain)))
    }

    @Test
    fun aBrushFollowsVaryingWidthsAsPressure() {
        val line = VSubpath(listOf(VAnchor(40f, 130f, width = 1f), VAnchor(180f, 130f, width = 3f), VAnchor(320f, 130f, width = 1f)))
        // A brush whose size ignores pressure still widens (§4.5 / V15).
        val brush = BrushLibrary.defaultBrush.copy(size = 8f, pressureSize = false, taperStart = 0f, taperEnd = 0f)
        val p = VPath(1, subpaths = listOf(line), polyline = true, stroke = VStrokeStyle(VStrokeKind.BRUSH, 0xFF000000.toInt(), 8f, brushTool = ToolId.BRUSH, brush = brush, seed = 1L))
        val px = render(listOf(p))
        assertEquals(24f, thickness(px, 180).toFloat(), 2f)
        assertTrue(thickness(px, 45) in 5..12)
        assertTrue(thickness(px, 110) in 10..20)
    }

    // ------------------------------------------------------------------ fills, opacity, shapes

    private fun box(l: Float, t: Float, r: Float, b: Float) =
        VSubpath(listOf(VAnchor(l, t, true), VAnchor(r, t, true), VAnchor(r, b, true), VAnchor(l, b, true)), closed = true)

    @Test
    fun fillsGradientsAndOpacity() {
        val solid = VPath(1, subpaths = listOf(box(40f, 40f, 140f, 120f)), fill = VPaint.Solid(0xFF20A040.toInt()))
        assertEquals(0xFF20A040.toInt(), render(listOf(solid))[80 * w + 90])
        // Opacity below 1 fades the whole object (fill and outline together).
        val faded = solid.copy(opacity = 0.5f, stroke = VStrokeStyle(color = 0xFF000000.toInt(), width = 10f))
        val fp = render(listOf(faded))
        assertEquals(128f, (fp[80 * w + 90] ushr 24).toFloat(), 1f)
        assertEquals(128f, (fp[80 * w + 40] ushr 24).toFloat(), 1f) // on the outline over the fill: not darker
        assertEquals(32f, ((fp[80 * w + 90] shr 16) and 0xFF).toFloat(), 2f)

        val stops = listOf(VStop(0f, 0xFF000000.toInt()), VStop(1f, 0xFFFFFFFF.toInt()))
        val linear = VPath(1, subpaths = listOf(box(40f, 140f, 340f, 240f)), fill = VPaint.Linear(90f, 0f, 290f, 0f, stops))
        val lp = render(listOf(linear))
        fun red(px: IntArray, x: Int, y: Int) = (px[y * w + x] shr 16) and 0xFF
        assertEquals(0, red(lp, 60, 190))
        assertEquals(255, red(lp, 320, 190))
        assertEquals(128f, red(lp, 190, 190).toFloat(), 2f)
        var prev = -1
        for (x in 60..320 step 10) { val r = red(lp, x, 190); assertTrue(r >= prev); prev = r }

        val radial = VPath(1, subpaths = listOf(box(40f, 140f, 340f, 240f)), fill = VPaint.Radial(190f, 190f, 40f, stops))
        val rp = render(listOf(radial))
        assertTrue(red(rp, 190, 190) <= 6)
        assertEquals(255, red(rp, 240, 190))
        // An ellipse through the gradient's own matrix: twice as wide as high.
        val stretched = radial.copy(fill = VPaint.Radial(0f, 0f, 40f, stops, listOf(2f, 0f, 0f, 1f, 190f, 190f)))
        val sp = render(listOf(stretched))
        assertEquals(red(rp, 210, 190).toFloat(), red(sp, 230, 190).toFloat(), 3f)
        assertEquals(255, red(sp, 190, 235))
        assertTrue(red(sp, 260, 190) < 255)
    }

    @Test
    fun aFadedObjectIsDrawnTileByTileLikeOneOffscreenLayer() {
        // Objects over many renderer tiles (VectorLayerRenderer.TILE) at opacity < 1 are faded
        // tile by tile: the same pixels as the opaque rendering faded through ONE offscreen
        // layer of the whole canvas.
        val bw = 1100
        val bh = 700
        val region = Rect(0, 0, bw, bh)
        fun px(b: Bitmap) = IntArray(bw * bh).also { b.getPixels(it, 0, bw, 0, 0, bw, bh) }
        fun draw(o: VObject): Bitmap = BitmapUtils.createLayerBitmap(bw, bh).also {
            VectorLayerRenderer.render(Canvas(it), VectorContent(objects = listOf(o), nextId = 100), region, tips = TipCache())
        }
        val stops = listOf(VStop(0f, 0xFF102080.toInt()), VStop(1f, 0xFFF0C020.toInt()))
        val big = VPath(
            1, subpaths = listOf(VSubpath(listOf(VAnchor(40f, 60f, true), VAnchor(1050f, 90f, true), VAnchor(990f, 660f, true), VAnchor(70f, 630f, true)), closed = true)),
            fill = VPaint.Linear(40f, 0f, 1050f, 0f, stops), stroke = VStrokeStyle(color = 0xFF000000.toInt(), width = 30f),
        )
        val brushed = VPath(
            2, subpaths = listOf(VSubpath(listOf(VAnchor(60f, 400f), VAnchor(380f, 120f), VAnchor(700f, 620f), VAnchor(1040f, 300f)))),
            stroke = VStrokeStyle(VStrokeKind.BRUSH, 0xFF802040.toInt(), 40f, brushTool = ToolId.BRUSH, brush = BrushLibrary.byId("chalk")!!.copy(size = 40f), seed = 9L),
        )
        assertTrue(bw > 3 * VectorLayerRenderer.TILE && bh > 2 * VectorLayerRenderer.TILE)
        for (o in listOf(big, brushed)) {
            for (alpha in listOf(0.6f, 0.35f)) {
                val expected = BitmapUtils.createLayerBitmap(bw, bh)
                val cv = Canvas(expected)
                cv.saveLayerAlpha(RectF(region), (alpha * 255f + 0.5f).toInt())
                VectorLayerRenderer.render(cv, VectorContent(objects = listOf(o), nextId = 100), region, tips = TipCache())
                cv.restore()
                val faded = px(draw(o.copy(opacity = alpha)))
                val ref = px(expected)
                // The path is drawn into the same tiles either way, and a brush outline replayed
                // per tile stamps its dabs whole (StrokeRaster): exact.
                assertTrue(faded.count { it != 0 } > 20_000)
                assertArrayEquals("object ${o.id} at $alpha", ref, faded)
            }
        }
    }

    @Test
    fun shapesDrawLikeTheShapeTool() {
        for (type in ShapeType.entries) {
            val o = ShapeObject(
                type, cx = 180f, cy = 130f, w = if (type.isLineLike) 220f else 200f, h = if (type.isLineLike) 0f else 140f, rotation = 15f,
                style = ShapeStyle.STROKE_FILL, strokeWidth = 7f, strokeColor = 0xFF102040.toInt(), fillColor = 0xFFE0B020.toInt(),
            )
            val spec = ShapeOutlines.paintSpec(o, false)!!
            assertArrayEquals(type.name, tiled { VectorRenderer().draw(it, spec, false, ColorMode.RGB) }, render(listOf(VShape(1, shape = o))))
        }
    }

    // ------------------------------------------------------------------ region, exclude, z-order, cost

    @Test
    fun theRegionClipsAndExcludedObjectsAreLeftOut() {
        val a = VPath(1, subpaths = listOf(box(40f, 40f, 200f, 200f)), fill = VPaint.Solid(0xFFFF0000.toInt()))
        val b = VPath(2, subpaths = listOf(box(120f, 100f, 320f, 230f)), fill = VPaint.Solid(0xFF0000FF.toInt()))
        val s = VStroke(
            3, preset = BrushLibrary.defaultBrush.copy(size = 12f), color = 0xFF00FF00.toInt(), seed = 2L, stylus = false,
            points = PackedPoints(floatArrayOf(20f, 340f), floatArrayOf(20f, 240f), floatArrayOf(1f, 1f)),
        )
        val whole = render(listOf(a, b, s))
        // Z-order: the later object is on top.
        assertEquals(0xFF0000FF.toInt(), whole[150 * w + 150])
        // Excluding an object equals drawing the others alone.
        assertArrayEquals(render(listOf(a, s)), render(listOf(a, b, s), exclude = setOf(2L)))
        // A region: inside it the same pixels, outside nothing.
        val region = Rect(100, 60, 260, 180)
        val part = render(listOf(a, b, s), region)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (region.contains(x, y)) assertEquals("($x, $y)", whole[i], part[i]) else assertEquals("($x, $y)", 0, part[i])
        }
        assertTrue(render(listOf(a), Rect()).all { it == 0 })
    }

    @Test
    fun theCostEstimateGrowsWithWhatIsDrawn() {
        val a = VPath(1, subpaths = listOf(box(40f, 40f, 200f, 200f)), fill = VPaint.Solid(-1))
        val s = VStroke(
            2, preset = BrushLibrary.defaultBrush.copy(size = 30f), color = -1, seed = 2L, stylus = false,
            points = PackedPoints(floatArrayOf(20f, 340f), floatArrayOf(20f, 240f), floatArrayOf(1f, 1f)),
        )
        val one = VectorLayerRenderer.estimateUnits(VectorContent(objects = listOf(a)), all)
        val two = VectorLayerRenderer.estimateUnits(VectorContent(objects = listOf(a, s)), all)
        assertTrue(one > 0.0)
        assertTrue(two > one)
        assertTrue(VectorLayerRenderer.estimateUnits(VectorContent(objects = listOf(a, s)), Rect(300, 0, 340, 10)) < two)
        assertEquals(0.0, VectorLayerRenderer.estimateUnits(VectorContent(objects = listOf(a, s)), Rect()), 0.0)
    }
}
