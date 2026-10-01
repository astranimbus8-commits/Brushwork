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
import com.brushwork.paint.vector.geom.ObjectIndex
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max

/**
 * Draws vector content (v1.5 §5.4; API frozen, owned by A1): objects in z-order, clipped to the
 * region. Thread-safe when every thread passes its own [TipCache] (and [RenderCache]).
 *
 * - [VStroke]: [StrokeRaster] replay (per-object opacity folded into the stroke opacity).
 * - [VPath]: fill with its paint (solid / linear / radial), then the outline: a plain line of
 *   constant width as a stroked path, varying anchor widths as a [VariableWidthOutline] fill, a
 *   brush outline as a [StrokeRaster] replay along `brushStrokeSamples` (each sub-path, seed + its
 *   index, sized up to the thickest anchor with the widths as pressure, §4.5).
 * - [VShape]: `ShapeOutlines.paintSpec` like the Shape tool, then its brush outline.
 * - opacity < 1 (paths, shapes): `saveLayerAlpha`, one [TILE] square at a time.
 *
 * Objects are found through the content's spatial index ([ObjectIndex], 128 px cells). Paths and
 * shapes are rasterized per [TILE] grid square (see there: the same pixels whatever the region,
 * and bounded offscreen layers). A [RenderCache] keeps each object's prepared paths, paints and
 * brush samples between renders. No color-mode constraint is applied (the editor's commit
 * constrains the touched tiles). Brush outlines are drawn as paint whatever their tool.
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

    /** Cost units ([BrushTool.pathDabCost]) of filling one pixel of a path or shape. */
    private const val PIXEL_UNITS = 0.35

    /** Cost units of one dab that is sampled but lands outside the region. */
    private const val SAMPLE_UNITS = 150.0

    /**
     * Draws [content] (document px) clipped to [region], leaving out the objects [exclude].
     *
     * [document] (v1.5 F2 review, additive): the document rect, where brush dabs are cut as the
     * live stroke cuts them (`StrokeRaster.render`'s `cut`): pass it whenever the pixels must
     * equal the layer's cache or a full render (re-renders, edit-session holes). Null cuts dabs at
     * [region]: a region cutting through a stroke can then differ from a full render by one level
     * at a few pixels.
     */
    fun render(canvas: Canvas, content: VectorContent, region: Rect, exclude: Set<Long> = emptySet(), tips: TipCache, document: Rect? = null) {
        renderWith(canvas, content, region, exclude, tips, document, null, null)
    }

    /**
     * [render] with the caller thread's [cache] of prepared objects (null = none) and a
     * [progress] callback after each drawn object (objects done, objects to draw; false stops the
     * render). Returns false when stopped.
     */
    internal fun renderWith(
        canvas: Canvas,
        content: VectorContent,
        region: Rect,
        exclude: Set<Long>,
        tips: TipCache,
        document: Rect?,
        cache: RenderCache?,
        progress: ((done: Int, total: Int) -> Boolean)?,
    ): Boolean {
        if (region.isEmpty || content.objects.isEmpty()) return true
        val index = ObjectIndex.of(content)
        val found = index.query(region.left.toFloat(), region.top.toFloat(), region.right.toFloat(), region.bottom.toFloat())
        if (found.isEmpty()) return true
        val ctx = Context(StrokeRaster(tips), document ?: Rect(region), cache)
        val save = canvas.save()
        try {
            canvas.clipRect(region)
            var done = 0
            for (i in found) {
                val o = content.objects[i]
                if (o.id in exclude) continue
                draw(canvas, o, region, index.bounds(i), ctx)
                done++
                if (progress != null && !progress(done, found.size)) return false
            }
        } finally {
            canvas.restoreToCount(save)
            // The stroke coverage buffer lives for one call: freed now, not whenever the GC runs
            // (the tips stay in the caller's cache).
            ctx.raster.releaseCoverage()
        }
        return true
    }

    /**
     * Estimated cost of rendering [region] ([BrushTool.pathDabCost] units, about a nanosecond of
     * pixel work each on a desktop): for the strokes and brush outlines that reach it, the share
     * of their dabs landing there (plus sampling the rest); for paths and shapes, the pixels of
     * their bounds there.
     */
    fun estimateUnits(content: VectorContent, region: Rect): Double {
        if (region.isEmpty || content.objects.isEmpty()) return 0.0
        val index = ObjectIndex.of(content)
        var units = 0.0
        for (i in index.query(region.left.toFloat(), region.top.toFloat(), region.right.toFloat(), region.bottom.toFloat())) {
            units += objectUnits(content.objects[i], index.bounds(i), region)
        }
        return units
    }

    /** [estimateUnits] of several disjoint regions (a tile set). */
    internal fun estimateUnits(content: VectorContent, regions: List<Rect>): Double = regions.sumOf { estimateUnits(content, it) }

    private fun objectUnits(o: VObject, b: RectF, region: Rect): Double {
        if (b.isEmpty) return 0.0
        val clipped = RectF(b)
        if (!clipped.intersect(region.left.toFloat(), region.top.toFloat(), region.right.toFloat(), region.bottom.toFloat())) return 0.0
        val area = b.width().toDouble() * b.height()
        val share = if (area > 0.0) (clipped.width().toDouble() * clipped.height() / area).coerceIn(0.0, 1.0) else 1.0
        val stats = ObjectCost.of(o)
        return stats.dabUnits * share + stats.dabs * SAMPLE_UNITS + if (stats.fills) clipped.width().toDouble() * clipped.height() * PIXEL_UNITS else 0.0
    }

    /** Per-object cost figures (kept by identity: objects are immutable). */
    internal class ObjectCost(val dabUnits: Double, val dabs: Double, val fills: Boolean) {
        companion object {
            private class Key(val o: VObject) {
                override fun hashCode(): Int = System.identityHashCode(o)
                override fun equals(other: Any?): Boolean = other is Key && other.o === o
            }

            private val cache = object : LinkedHashMap<Key, ObjectCost>(256, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, ObjectCost>?): Boolean = size > 4096
            }

            fun of(o: VObject): ObjectCost {
                val k = Key(o)
                synchronized(cache) { cache[k]?.let { return it } }
                val c = measure(o)
                synchronized(cache) { cache[k] = c }
                return c
            }

            private fun measure(o: VObject): ObjectCost = when (o) {
                is VStroke -> {
                    val (u, n) = dabUnits(o.preset.size * o.sizeScale, o.preset.spacing, polylineLength(o.points))
                    ObjectCost(u, n, fills = false)
                }
                is VPath -> {
                    val st = o.stroke
                    var u = 0.0
                    var n = 0.0
                    if (st != null && st.kind == VStrokeKind.BRUSH) {
                        val brush = VectorOps.brushOf(st, max(VectorOps.maxWidth(o), 1e-3f))
                        val len = VectorOps.toVectorPath(o).flatten(1f).sumOf { VectorPath.length(it.points).toDouble() }
                        val d = dabUnits(brush.size, brush.spacing, len.toFloat())
                        u = d.first; n = d.second
                    }
                    ObjectCost(u, n, fills = o.fill != null || (st != null && st.kind == VStrokeKind.PLAIN))
                }
                is VShape -> {
                    var u = 0.0
                    var n = 0.0
                    if (o.shape.paintsWithBrush) {
                        val brush = VectorOps.brushPresetOf(o.shape)
                        val len = ShapeOutlines.brushOutline(o.shape).flatten(1f).sumOf { VectorPath.length(it.points).toDouble() }
                        val d = dabUnits(brush.size, brush.spacing, len.toFloat())
                        u = d.first; n = d.second
                    }
                    ObjectCost(u, n, fills = true)
                }
            }

            /** (units, dab count) of a stroke of [length] with dabs of [size]. */
            private fun dabUnits(size: Float, spacing: Float, length: Float): Pair<Double, Double> {
                val d = max(1f, if (size.isFinite()) size else 1f)
                val step = max(StrokeDynamics.MIN_SPACING_PX, (if (spacing.isFinite()) spacing else 0.1f) * d)
                val count = ((if (length.isFinite()) length else 0f) / step + 1f).toDouble()
                return count * BrushTool.pathDabCost(d) to count
            }

            private fun polylineLength(p: PackedPoints): Float {
                var s = 0f
                for (i in 1 until p.size) {
                    val l = hypot(p.x[i] - p.x[i - 1], p.y[i] - p.y[i - 1])
                    if (l.isFinite()) s += l
                }
                return s
            }
        }
    }

    /** One render call's (thread-confined) helpers; [cut]: where brush dabs are cut (see [render]). */
    private class Context(val raster: StrokeRaster, val cut: Rect, val cache: RenderCache?) {
        val input = PathStrokeInput(512)
    }

    /** [o]'s prepared parts, from the cache when there is one. */
    private fun prepared(o: VObject, ctx: Context): RenderCache.Prepared {
        val c = ctx.cache ?: return prepare(o, ctx)
        return c.get(o) { prepare(o, ctx) }
    }

    private fun prepare(o: VObject, ctx: Context): RenderCache.Prepared = when (o) {
        is VStroke -> RenderCache.Prepared(null, emptyList())
        is VPath -> RenderCache.Prepared(pathParts(o), o.stroke?.takeIf { it.kind == VStrokeKind.BRUSH }?.let { brushReplays(o, it, ctx) } ?: emptyList())
        is VShape -> RenderCache.Prepared(
            ShapeOutlines.paintSpec(o.shape, o.shape.paintsWithBrush)?.let { spec ->
                val renderer = VectorRenderer()
                val draw: (Canvas) -> Unit = { c -> renderer.draw(c, spec, false, ColorMode.RGB) }
                draw
            },
            if (o.shape.paintsWithBrush) shapeBrushReplays(o, ctx) else emptyList(),
        )
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
            ctx.raster.render(canvas, region, o.preset, o.color, o.seed, o.stylus, o.points, o.sizeScale, opacity, o.taperIn, o.taperOut, cut = ctx.cut)
            return
        }
        val parts = prepared(o, ctx)
        val faded = opacity < 1f
        val plain = parts.plain
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
                        if (faded) drawBrushes(canvas, parts, tile, ctx)
                        canvas.restoreToCount(save)
                    }
                    left += TILE
                }
                top += TILE
            }
        }
        if (!faded) drawBrushes(canvas, parts, region, ctx)
    }

    /** The brush replays of an object within [region]. */
    private fun drawBrushes(canvas: Canvas, parts: RenderCache.Prepared, region: Rect, ctx: Context) {
        for (b in parts.brushes) ctx.raster.render(canvas, region, b.preset, b.color, b.seed, true, b.points, cut = ctx.cut)
    }

    // ------------------------------------------------------------------ paths

    /**
     * What [p] draws besides a brush outline (built once, drawn into each tile): its fill and
     * plain outline; null when there is nothing of the kind.
     */
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
    private fun brushReplays(p: VPath, st: VStrokeStyle, ctx: Context): List<RenderCache.BrushReplay> {
        if (!(VectorOps.maxWidth(p) > 0f)) return emptyList()
        val out = ArrayList<RenderCache.BrushReplay>(p.subpaths.size)
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
            out += RenderCache.BrushReplay(brush, st.color, st.seed + index, withLastPointAgain(input))
        }
        return out
    }

    /** The samples as a replay input that ends with its last point once more (the live stroke's onUp). */
    private fun withLastPointAgain(input: PathStrokeInput): PackedPoints {
        val n = input.size
        val xs = FloatArray(n + 1); val ys = FloatArray(n + 1); val ps = FloatArray(n + 1)
        input.x.copyInto(xs, 0, 0, n); input.y.copyInto(ys, 0, 0, n); input.pressure.copyInto(ps, 0, 0, n)
        xs[n] = xs[n - 1]; ys[n] = ys[n - 1]; ps[n] = ps[n - 1]
        return PackedPoints(xs, ys, ps)
    }

    /**
     * The thickness factor at every sample of [input] (one per sample, for a [WidthProfile]):
     * the factor at the nearest point of the flattened sub-path, whose factors blend with
     * smoothstep along arc length between anchors (§4.5).
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
    private fun shapeBrushReplays(s: VShape, ctx: Context): List<RenderCache.BrushReplay> {
        val o = s.shape
        val input = brushStrokeSamples(ShapeOutlines.brushOutline(o), 0f, null, ctx.input)
        if (input.size < 2) return emptyList()
        return listOf(RenderCache.BrushReplay(VectorOps.brushPresetOf(o), o.strokeColor, s.seed, withLastPointAgain(input)))
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
