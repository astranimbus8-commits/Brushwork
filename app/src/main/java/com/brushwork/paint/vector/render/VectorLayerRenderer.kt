package com.brushwork.paint.vector.render

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import com.brushwork.paint.brush.BrushTool
import com.brushwork.paint.brush.PathStrokeInput
import com.brushwork.paint.brush.StrokeDynamics
import com.brushwork.paint.brush.StrokeRaster
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.tools.vector.CurveGeometry
import com.brushwork.paint.tools.vector.JoinStyle
import com.brushwork.paint.tools.vector.LineCapStyle
import com.brushwork.paint.tools.vector.ShapeOutlines
import com.brushwork.paint.tools.vector.VariableWidthOutline
import com.brushwork.paint.tools.vector.VectorPath
import com.brushwork.paint.tools.vector.VectorRenderer
import com.brushwork.paint.tools.vector.WidthProfile
import com.brushwork.paint.tools.vector.brushStrokeSamples
import com.brushwork.paint.tools.vector.toAndroidPath
import com.brushwork.paint.vector.VFillRule
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VStrokeKind
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorOps
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max

/**
 * Draws vector content (v1.5 §5.4; API frozen, owned by A1): objects in z-order, clipped to the
 * region. Thread-safe when every thread passes its own [TipCache].
 *
 * F2 reference (minimal, every object kind):
 * - [VStroke]: [StrokeRaster] replay (per-object opacity folded into the stroke opacity);
 * - [VPath]: fill with its paint (solid / linear / radial), then the outline: a plain line of
 *   constant width as a stroked path, varying anchor widths as a [VariableWidthOutline] fill, a
 *   brush outline as a [StrokeRaster] replay along `brushStrokeSamples` (each sub-path, seed + its
 *   index, sized up to the thickest anchor with the widths as pressure, §4.5);
 * - [VShape]: `ShapeOutlines.paintSpec` like the Shape tool, then its brush outline;
 * - opacity < 1 (paths, shapes): `saveLayerAlpha`, one [TILE] square at a time.
 * Paths and shapes are rasterized per [TILE] grid square (see there: the same pixels whatever the
 * region, and bounded offscreen layers). No spatial grid (every object's bounds are tested) and no
 * color-mode constraint (the editor's commit constrains the touched tiles). Brush outlines are
 * drawn as paint whatever their tool.
 */
object VectorLayerRenderer {
    /** Flattening tolerance of varying-width outlines (document px). */
    private const val WIDTH_TOLERANCE = 0.25f

    /** Largest distance between two points of a varying-width line (document px). */
    private const val WIDTH_STEP = 2f

    /** Most points one flattened piece of a varying-width line is cut into. */
    private const val MAX_PIECES = 1024

    /**
     * Side of the document-anchored grid squares paths and shapes are rasterized in. Skia's
     * anti-aliasing of a path depends on where the canvas clip cuts it (measured: up to ~40
     * levels at edge pixels, for paths of any size), so a path or shape is always drawn clipped to
     * grid tiles (∩ the region): re-rendering a region made of whole tiles gives exactly the
     * pixels a full render gives there, which keeps a vector layer's cache equal to a fresh
     * rendering (I1) however its dirty regions fell. `VectorLayers` keeps its dirty regions on
     * this grid. Tile-sized clips also bound the offscreen layer of a semi-transparent object.
     */
    const val TILE = 256

    /** Draws [content] (document px) clipped to [region], leaving out the objects [exclude]. */
    fun render(canvas: Canvas, content: VectorContent, region: Rect, exclude: Set<Long> = emptySet(), tips: TipCache) {
        if (region.isEmpty || content.objects.isEmpty()) return
        val ctx = Context(StrokeRaster(tips))
        val regionF = RectF(region)
        val save = canvas.save()
        try {
            canvas.clipRect(region)
            for (o in content.objects) {
                if (o.id in exclude) continue
                val b = VectorOps.bounds(o)
                if (b.isEmpty || !RectF.intersects(b, regionF)) continue
                draw(canvas, o, region, b, ctx)
            }
        } finally {
            canvas.restoreToCount(save)
            // The stroke coverage buffer lives for one call: freed now, not whenever the GC runs
            // (the tips stay in the caller's cache).
            ctx.raster.releaseCoverage()
        }
    }

    /**
     * Estimated cost of rendering [region] ([com.brushwork.paint.brush.BrushTool.pathDabCost]
     * units): the dabs of the strokes and brush outlines that reach it, plus the pixels of the
     * paths and shapes there.
     */
    fun estimateUnits(content: VectorContent, region: Rect): Double {
        if (region.isEmpty) return 0.0
        val regionF = RectF(region)
        var units = 0.0
        for (o in content.objects) {
            val b = VectorOps.bounds(o)
            if (b.isEmpty || !RectF.intersects(b, regionF)) continue
            val clipped = RectF(b).apply { intersect(regionF) }
            units += clipped.width().toDouble() * clipped.height()
            when (o) {
                is VStroke -> units += dabUnits(o.preset.size * o.sizeScale, o.preset.spacing, polylineLength(o.points))
                is VPath -> {
                    val st = o.stroke
                    if (st != null && st.kind == VStrokeKind.BRUSH) {
                        val brush = VectorOps.brushOf(st, max(VectorOps.maxWidth(o), 1e-3f))
                        val len = VectorOps.toVectorPath(o).flatten(1f).sumOf { VectorPath.length(it.points).toDouble() }
                        units += dabUnits(brush.size, brush.spacing, len.toFloat())
                    }
                }
                is VShape -> if (o.shape.paintsWithBrush) {
                    val brush = VectorOps.brushPresetOf(o.shape)
                    val len = ShapeOutlines.brushOutline(o.shape).flatten(1f).sumOf { VectorPath.length(it.points).toDouble() }
                    units += dabUnits(brush.size, brush.spacing, len.toFloat())
                }
            }
        }
        return units
    }

    private fun dabUnits(size: Float, spacing: Float, length: Float): Double {
        val d = max(1f, size)
        val step = max(StrokeDynamics.MIN_SPACING_PX, spacing * d)
        return (length / step + 1f).toDouble() * BrushTool.pathDabCost(d)
    }

    private fun polylineLength(p: PackedPoints): Float {
        var s = 0f
        for (i in 1 until p.size) s += hypot(p.x[i] - p.x[i - 1], p.y[i] - p.y[i - 1])
        return s
    }

    /** One render call's (thread-confined) helpers. */
    private class Context(val raster: StrokeRaster) {
        val input = PathStrokeInput(512)
    }

    /**
     * Draws [o] (paint [bounds]) within [region]: a stroke as one replay; a path or shape tile by
     * tile ([TILE] grid squares ∩ the region; the same pixels whatever the region, see [TILE]),
     * through a tile-sized offscreen layer when it is semi-transparent (fill and outline fade
     * together). A brush outline is replayed once over the region (like the live path stroke
     * that painted it), or per tile inside the offscreen layers of a semi-transparent object.
     */
    private fun draw(canvas: Canvas, o: VObject, region: Rect, bounds: RectF, ctx: Context) {
        val opacity = o.opacity.let { if (it.isFinite()) it.coerceIn(0f, 1f) else 1f }
        if (opacity <= 0f) return
        if (o is VStroke) {
            ctx.raster.render(canvas, region, o.preset, o.color, o.seed, o.stylus, o.points, o.sizeScale, opacity, o.taperIn, o.taperOut)
            return
        }
        val faded = opacity < 1f
        val plain = plainParts(o)
        if (plain != null || faded) {
            val area = Rect()
            RectF(bounds).roundOut(area)
            if (!area.intersect(region)) return
            val alpha = (opacity * 255f + 0.5f).toInt()
            val tile = Rect()
            var top = Math.floorDiv(area.top, TILE) * TILE
            while (top < area.bottom) {
                var left = Math.floorDiv(area.left, TILE) * TILE
                while (left < area.right) {
                    tile.set(left, top, left + TILE, top + TILE)
                    if (tile.intersect(area)) {
                        val save = canvas.save()
                        canvas.clipRect(tile)
                        if (faded) canvas.saveLayerAlpha(RectF(tile), alpha)
                        plain?.invoke(canvas)
                        if (faded) drawBrush(canvas, o, tile, ctx)
                        canvas.restoreToCount(save)
                    }
                    left += TILE
                }
                top += TILE
            }
        }
        if (!faded) drawBrush(canvas, o, region, ctx)
    }

    /** The brush outline of [o] (if it has one) within [region]. */
    private fun drawBrush(canvas: Canvas, o: VObject, region: Rect, ctx: Context) {
        when (o) {
            is VPath -> o.stroke?.takeIf { it.kind == VStrokeKind.BRUSH }?.let { drawBrushOutline(canvas, o, it, region, ctx) }
            is VShape -> if (o.shape.paintsWithBrush) drawShapeBrush(canvas, o, region, ctx)
            is VStroke -> {}
        }
    }

    // ------------------------------------------------------------------ paths

    /**
     * What [o] draws besides a brush outline (built once, drawn into each tile): a path's fill
     * and plain outline, a shape's `ShapeOutlines.paintSpec` like the Shape tool; null when
     * there is nothing of the kind.
     */
    private fun plainParts(o: VObject): ((Canvas) -> Unit)? = when (o) {
        is VPath -> pathParts(o)
        is VShape -> ShapeOutlines.paintSpec(o.shape, o.shape.paintsWithBrush)?.let { spec ->
            val renderer = VectorRenderer()
            val draw: (Canvas) -> Unit = { c -> renderer.draw(c, spec, false, ColorMode.RGB) }
            draw
        }
        is VStroke -> null
    }

    private fun pathParts(p: VPath): ((Canvas) -> Unit)? {
        val geometry = VectorOps.toVectorPath(p)
        if (geometry.ops.isEmpty()) return null
        val parts = ArrayList<(Canvas) -> Unit>(2)
        p.fill?.let { v ->
            val path = geometry.toAndroidPath()
            path.fillType = if (p.fillRule == VFillRule.EVENODD) Path.FillType.EVEN_ODD else Path.FillType.WINDING
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            if (applyPaint(paint, v)) parts += { c -> c.drawPath(path, paint) }
        }
        val st = p.stroke
        if (st != null && st.kind == VStrokeKind.PLAIN) plainOutline(p, st, geometry)?.let { parts += it }
        return when (parts.size) {
            0 -> null
            1 -> parts[0]
            else -> { c -> for (d in parts) d(c) }
        }
    }

    /** A plain outline: a stroked path, or with varying anchor widths a filled outline per sub-path (round joins and caps). */
    private fun plainOutline(p: VPath, st: VStrokeStyle, geometry: VectorPath): ((Canvas) -> Unit)? {
        if (!(st.width > 0f) || !st.width.isFinite()) return null
        val uniform = p.subpaths.all { s -> s.anchors.all { it.width == 1f } }
        if (uniform) {
            val path = geometry.toAndroidPath()
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = st.color
                strokeWidth = st.width
                strokeCap = when (st.cap) { LineCapStyle.BUTT -> Paint.Cap.BUTT; LineCapStyle.ROUND -> Paint.Cap.ROUND; LineCapStyle.SQUARE -> Paint.Cap.SQUARE }
                strokeJoin = when (st.join) { JoinStyle.MITER -> Paint.Join.MITER; JoinStyle.ROUND -> Paint.Join.ROUND; JoinStyle.BEVEL -> Paint.Join.BEVEL }
                strokeMiter = max(1f, st.miter)
            }
            return { c -> c.drawPath(path, paint) }
        }
        val outlines = ArrayList<Path>()
        for (s in p.subpaths) {
            val line = widthLine(s, p.tension, p.polyline, st.width) ?: continue
            val outline = VariableWidthOutline.build(line.xs, line.ys, line.ws, line.n, s.closed && s.anchors.size > 2, WIDTH_TOLERANCE)
            if (!outline.isEmpty) outlines += outline.toAndroidPath()
        }
        if (outlines.isEmpty()) return null
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = st.color }
        return { c -> for (o in outlines) c.drawPath(o, paint) }
    }

    /** A sub-path flattened with the full line width at every point (§4.5: smoothstep between anchors along arc length). */
    private class WidthLine(val xs: FloatArray, val ys: FloatArray, val ws: FloatArray, val n: Int)

    private fun widthLine(s: VSubpath, tension: Float, polyline: Boolean, width: Float): WidthLine? {
        val anchors = VectorOps.curveAnchors(s)
        val n = anchors.size
        if (n == 0) return null
        if (n == 1) return WidthLine(floatArrayOf(anchors[0].x), floatArrayOf(anchors[0].y), floatArrayOf(width * anchors[0].width.coerceAtLeast(0f)), 1)
        val closed = s.closed && n > 2
        val segs = CurveGeometry.segmentCount(n, closed)
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>(); val ws = ArrayList<Float>()
        fun w(i: Int) = anchors[i % n].width.let { if (it.isFinite()) it.coerceAtLeast(0f) else 1f } * width
        xs += anchors[0].x; ys += anchors[0].y; ws += w(0)
        val pts = ArrayList<Vec2>()
        for (seg in 0 until segs) {
            val (p0, c1, c2, p1) = CurveGeometry.segment(anchors, seg, closed, tension, polyline)
            pts.clear()
            pts += p0
            VectorPath.flattenCubic(p0, c1, c2, p1, WIDTH_TOLERANCE, pts)
            var total = 0f
            for (i in 1 until pts.size) total += pts[i - 1].distanceTo(pts[i])
            var acc = 0f
            val wa = w(seg); val wb = w(seg + 1)
            for (i in 1 until pts.size) {
                val a = pts[i - 1]; val b = pts[i]
                val len = a.distanceTo(b)
                // A changing width needs points along straight pieces too (they flatten to their
                // ends), or the outline would blend linearly instead of with smoothstep.
                val pieces = if (wa == wb) 1 else ceil(len / WIDTH_STEP).toInt().coerceIn(1, MAX_PIECES)
                for (k in 1..pieces) {
                    val f = k.toFloat() / pieces
                    val t = if (total > 0f) ((acc + len * f) / total).coerceIn(0f, 1f) else 1f
                    val e = t * t * (3f - 2f * t)
                    val q = if (k == pieces) b else a.lerp(b, f)
                    xs += q.x; ys += q.y; ws += wa + (wb - wa) * e
                }
                acc += len
            }
        }
        return WidthLine(xs.toFloatArray(), ys.toFloatArray(), ws.toFloatArray(), xs.size)
    }

    /**
     * A brush along each sub-path, as the live path stroke paints it (BrushTool.beginPath with
     * the `brushStrokeSamples` input, then onUp at the last point): the same samples, seed
     * ([VStrokeStyle.seed] + the sub-path's index) and brush. Varying anchor widths (§4.5 / V15)
     * are fed as a [WidthProfile] (pressure × w / wMax) to the brush sized up to the thickest
     * sample, with size following pressure and opacity not.
     */
    private fun drawBrushOutline(canvas: Canvas, p: VPath, st: VStrokeStyle, region: Rect, ctx: Context) {
        if (!(VectorOps.maxWidth(p) > 0f)) return
        val uniform = p.subpaths.all { s -> s.anchors.all { it.width == 1f } }
        val base = VectorOps.brushOf(st)
        val taper = (st.taperPercent / 100f).let { if (it.isFinite()) it.coerceIn(0f, 0.5f) else 0f }
        p.subpaths.forEachIndexed { index, s ->
            if (s.anchors.size < 2) return@forEachIndexed
            val single = VPath(p.id, subpaths = listOf(s), tension = p.tension, polyline = p.polyline)
            val path = VectorOps.toVectorPath(single)
            var input = brushStrokeSamples(path, taper, null, ctx.input)
            if (input.size < 2) return@forEachIndexed
            var brush = base
            if (!uniform) {
                val widths = widthsAtSamples(input, s, p.tension, p.polyline)
                var wMax = 0f
                for (v in widths) if (v > wMax) wMax = v
                if (!(wMax > 0f) || !wMax.isFinite()) return@forEachIndexed
                // Bit-identical sample points; only the pressures change.
                input = brushStrokeSamples(path, taper, WidthProfile(widths), ctx.input)
                brush = base.copy(size = base.size * wMax, pressureSize = true, minSizeRatio = 0f, pressureOpacity = false)
            }
            // The live path stroke ends with its last point once more (onUp).
            val n = input.size
            val xs = FloatArray(n + 1); val ys = FloatArray(n + 1); val ps = FloatArray(n + 1)
            input.x.copyInto(xs, 0, 0, n); input.y.copyInto(ys, 0, 0, n); input.pressure.copyInto(ps, 0, 0, n)
            xs[n] = xs[n - 1]; ys[n] = ys[n - 1]; ps[n] = ps[n - 1]
            ctx.raster.render(canvas, region, brush, st.color, st.seed + index, true, PackedPoints(xs, ys, ps))
        }
    }

    /**
     * The thickness factor at every sample of [input] (one per sample, for a [WidthProfile]):
     * the factor at the nearest point of the flattened sub-path, whose factors blend with
     * smoothstep along arc length between anchors (§4.5). A reference; A4 owns the real profile.
     */
    private fun widthsAtSamples(input: PathStrokeInput, s: VSubpath, tension: Float, polyline: Boolean): FloatArray {
        val out = FloatArray(input.size) { 1f }
        val line = widthLine(s, tension, polyline, 1f) ?: return out
        var j = 0
        for (i in 0 until input.size) {
            val x = input.x[i]; val y = input.y[i]
            // Samples advance along the path: walk the flattened points forward.
            var best = j
            var bestD = Float.POSITIVE_INFINITY
            var k = j
            while (k < line.n && k < j + 64) {
                val d = hypot(line.xs[k] - x, line.ys[k] - y)
                if (d < bestD) { bestD = d; best = k }
                k++
            }
            j = best
            out[i] = line.ws[best]
        }
        return out
    }

    // ------------------------------------------------------------------ shapes

    /** A brush-stroked shape's outline, replayed along `brushStrokeSamples(brushOutline)` with the shape's seed. */
    private fun drawShapeBrush(canvas: Canvas, s: VShape, region: Rect, ctx: Context) {
        val o = s.shape
        val input = brushStrokeSamples(ShapeOutlines.brushOutline(o), 0f, null, ctx.input)
        if (input.size < 2) return
        val n = input.size
        val xs = FloatArray(n + 1); val ys = FloatArray(n + 1); val ps = FloatArray(n + 1)
        input.x.copyInto(xs, 0, 0, n); input.y.copyInto(ys, 0, 0, n); input.pressure.copyInto(ps, 0, 0, n)
        xs[n] = xs[n - 1]; ys[n] = ys[n - 1]; ps[n] = ps[n - 1]
        ctx.raster.render(canvas, region, VectorOps.brushPresetOf(o), o.strokeColor, s.seed, true, PackedPoints(xs, ys, ps))
    }

    // ------------------------------------------------------------------ paints

    /** Sets [paint] up for [v]; false when there is nothing to draw (no stops). */
    private fun applyPaint(paint: Paint, v: VPaint): Boolean {
        paint.shader = null
        when (v) {
            is VPaint.Solid -> paint.color = v.color
            is VPaint.Linear -> {
                val stops = sortedStops(v.stops) ?: return false
                paint.color = 0xFF000000.toInt()
                if (stops.first.size == 1) { paint.color = stops.first[0]; return true }
                paint.shader = LinearGradient(v.x0, v.y0, v.x1, v.y1, stops.first, stops.second, Shader.TileMode.CLAMP)
            }
            is VPaint.Radial -> {
                val stops = sortedStops(v.stops) ?: return false
                paint.color = 0xFF000000.toInt()
                if (stops.first.size == 1 || !(v.r > 0f)) { paint.color = stops.first.last(); return true }
                val sh = RadialGradient(v.cx, v.cy, v.r, stops.first, stops.second, Shader.TileMode.CLAMP)
                v.matrix?.takeIf { it.size >= 6 }?.let { m ->
                    sh.setLocalMatrix(Matrix().apply { setValues(floatArrayOf(m[0], m[2], m[4], m[1], m[3], m[5], 0f, 0f, 1f)) })
                }
                paint.shader = sh
            }
        }
        return true
    }

    /** Stops sorted by offset (clamped to 0..1) as colors + positions; null without stops. */
    private fun sortedStops(stops: List<com.brushwork.paint.vector.VStop>): Pair<IntArray, FloatArray>? {
        if (stops.isEmpty()) return null
        val sorted = stops.map { it.copy(offset = if (it.offset.isFinite()) it.offset.coerceIn(0f, 1f) else 0f) }.sortedBy { it.offset }
        return IntArray(sorted.size) { sorted[it].color } to FloatArray(sorted.size) { sorted[it].offset }
    }
}
