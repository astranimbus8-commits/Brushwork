package com.brushwork.paint.vector

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.BrushPreset
import com.brushwork.paint.brush.StrokeRaster
import com.brushwork.paint.core.Geometry
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.Selection
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveAnchor
import com.brushwork.paint.tools.vector.CurveGeometry
import com.brushwork.paint.tools.vector.JoinStyle
import com.brushwork.paint.tools.vector.LineCapStyle
import com.brushwork.paint.tools.vector.MITER_LIMIT
import com.brushwork.paint.tools.vector.PathOp
import com.brushwork.paint.tools.vector.Polyline
import com.brushwork.paint.tools.vector.ShapeOutlines
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.tools.vector.VectorPath
import com.brushwork.paint.tools.vector.toAndroidPath
import com.brushwork.paint.vector.geom.ObjectIndex
import com.brushwork.paint.vector.geom.ObjectMapping
import com.brushwork.paint.vector.geom.StrokeHits
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Geometry of vector objects (v1.5 §5.4; API frozen, owned by A1). A2, A3, A4 and A8 depend on
 * these bodies.
 *
 * - [bounds] are conservative (a stroke's largest dab everywhere: what it can paint).
 * - [hit] is exact for strokes: the replayed dabs ([StrokeHits]: same sampler, pressure, tapers
 *   and scatter as the pixels) as a chain of capsules, so a tap beside a tapered tip misses and
 *   one on a pressure swell hits; paths and shapes use their flattened outline and fill.
 * - [touching] prefilters by the content's spatial index ([ObjectIndex]) and rasterizes each
 *   candidate's footprint (strokes: their dab chain) against the selection.
 * - [transformed] is exact for affine maps (gradients included); a homography first splits every
 *   curved segment into [ObjectMapping.HOMOGRAPHY_PIECES] cubics, then maps anchors and handles.
 *   A shape stays a shape under similarities, and under reflections when it is symmetric (or has
 *   custom points, which are mirrored); otherwise it becomes a path.
 */
object VectorOps {
    /** Flattening tolerance of hit tests and footprints (document px). */
    private const val FLATTEN = 0.25f

    /** Everything [o] can paint (document px), including the stroke width / brush radius × sizeScale. */
    fun bounds(o: VObject): RectF = when (o) {
        is VStroke -> StrokeRaster.strokeBounds(o.preset, o.sizeScale, o.points)
        is VPath -> pathBounds(o)
        is VShape -> shapeBounds(o)
    }

    /** True when [p] is on [o]: strokes within radius + [tol] of their polyline, filled paths inside. */
    fun hit(o: VObject, p: Vec2, tol: Float): Boolean {
        val t = if (tol.isFinite()) max(0f, tol) else 0f
        val b = bounds(o)
        if (!b.isEmpty && !(p.x >= b.left - t && p.x <= b.right + t && p.y >= b.top - t && p.y <= b.bottom + t)) {
            // (An invisible path has control bounds only: still tested below.)
            if (!(o is VPath && o.fill == null && o.stroke == null)) return false
        }
        return when (o) {
            is VStroke -> strokeHit(o, p, t)
            is VPath -> pathHit(o, p, t)
            is VShape -> ShapeOutlines.hits(o.shape, p, t)
        }
    }

    /**
     * Ids of the objects of [content] that [sel] touches: the object's footprint (what it paints;
     * a stroke: its replayed dab chain) has a pixel where the selection is not empty. Only the
     * objects the spatial index finds near the selection are tested.
     *
     * The footprint is rasterized against the selection in [TOUCH_TILE] squares of one reused
     * buffer (memory stays bounded however large the objects), stopping at the first touched
     * pixel.
     */
    fun touching(content: VectorContent, sel: Selection): Set<Long> {
        if (sel.isEmpty || content.objects.isEmpty()) return emptySet()
        val out = LinkedHashSet<Long>()
        var probe: FootprintProbe? = null
        val index = ObjectIndex.of(content)
        val sb = sel.bounds
        try {
            for (i in index.query(sb.left - 1f, sb.top - 1f, sb.right + 1f, sb.bottom + 1f)) {
                val o = content.objects[i]
                val b = index.bounds(i)
                if (b.isEmpty) continue
                val r = Rect(floor(b.left).toInt() - 1, floor(b.top).toInt() - 1, ceil(b.right).toInt() + 1, ceil(b.bottom).toInt() + 1)
                if (!r.intersect(sel.bounds)) continue
                val p = probe ?: try {
                    FootprintProbe(sel).also { probe = it }
                } catch (e: OutOfMemoryError) {
                    // No memory for an exact test: the boxes overlap, count it as touched.
                    out += o.id
                    continue
                }
                if (p.touches(footprint(o), r)) out += o.id
            }
        } finally {
            probe?.release()
        }
        return out
    }

    /** Side of the squares footprints are tested in by [touching] (one reused ALPHA_8 buffer). */
    private const val TOUCH_TILE = 512

    /** Rasterizes footprints against [sel] tile by tile (one reused tile buffer). Not thread-safe. */
    private class FootprintProbe(sel: Selection) {
        private val bmp = Bitmap.createBitmap(TOUCH_TILE, TOUCH_TILE, Bitmap.Config.ALPHA_8)
        private val canvas = Canvas(bmp)
        private val bytes = ByteBuffer.allocate(bmp.rowBytes * bmp.height)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() }
        // (drawBitmap(A8, DST_IN) would be a no-op: Skia treats A8 bitmaps as coverage.)
        private val maskPaint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            shader = BitmapShader(sel.mask, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        private val maskW = sel.mask.width.toFloat()
        private val maskH = sel.mask.height.toFloat()

        /** True when [draw] (document px) paints a pixel inside [area] where the selection is set. */
        fun touches(draw: (Canvas, Paint) -> Unit, area: Rect): Boolean {
            val rb = bmp.rowBytes
            var top = area.top
            while (top < area.bottom) {
                val th = min(TOUCH_TILE, area.bottom - top)
                var left = area.left
                while (left < area.right) {
                    val tw = min(TOUCH_TILE, area.right - left)
                    bmp.eraseColor(0)
                    canvas.save()
                    canvas.clipRect(0, 0, tw, th)
                    canvas.translate(-left.toFloat(), -top.toFloat())
                    draw(canvas, paint)
                    canvas.drawRect(0f, 0f, maskW, maskH, maskPaint)
                    canvas.restore()
                    bytes.rewind()
                    bmp.copyPixelsToBuffer(bytes)
                    val a = bytes.array()
                    for (y in 0 until th) {
                        val base = y * rb
                        for (x in 0 until tw) if (a[base + x].toInt() != 0) return true
                    }
                    left += TOUCH_TILE
                }
                top += TOUCH_TILE
            }
            return false
        }

        fun release() = bmp.recycle()
    }

    /**
     * [o] mapped by [m] (3x3 row-major; may be a homography). Strokes map their points and scale
     * `sizeScale` by sqrt|det| (at the bounds centre); a VShape stays a VShape under similarities
     * (and under reflections when it mirrors into itself, see [ObjectMapping.mirroredShape]),
     * otherwise it becomes a VPath. Exact for affine maps (gradients included); a homography
     * first splits every curved segment into [ObjectMapping.HOMOGRAPHY_PIECES] cubics, then maps
     * anchors and handles. A non-finite matrix returns [o].
     */
    fun transformed(o: VObject, m: FloatArray): VObject {
        if (m.size < 9 || m.any { !it.isFinite() }) return o
        val projective = m[6] != 0f || m[7] != 0f
        return when (o) {
            is VStroke -> {
                // Projective maps keep straight lines straight: mapping the points is exact.
                val b = o.points.bounds()
                val s = scaleAt(m, b.centerX(), b.centerY())
                o.copy(points = o.points.mapped(m), sizeScale = o.sizeScale * s)
            }
            is VPath -> mapPath(if (projective) ObjectMapping.subdivided(o) else o, m)
            is VShape -> similarityShape(o, m) ?: ObjectMapping.mirroredShape(o, m)
                ?: mergePaths(o, toPaths(o)).let { p -> mapPath(if (projective) ObjectMapping.subdivided(p) else p, m) }
        }
    }

    /** The outline of [p] (via CurveGeometry.toPath per sub-path, broken tangents honoured). */
    fun toVectorPath(p: VPath): VectorPath {
        val ops = ArrayList<PathOp>()
        for (s in p.subpaths) {
            if (s.anchors.isEmpty()) continue
            ops += CurveGeometry.toPath(curveAnchors(s), s.closed, p.tension, p.polyline).ops
        }
        return if (ops.isEmpty()) VectorPath.EMPTY else VectorPath(ops)
    }

    // ------------------------------------------------------------------ conversions (additive, F2)

    /** The Curve tool's anchors of [s] (handles kept when both coordinates are set). */
    fun curveAnchors(s: VSubpath): List<CurveAnchor> = s.anchors.map { a ->
        CurveAnchor(
            a.x, a.y, a.sharp,
            handleIn = if (a.inX != null && a.inY != null) Vec2(a.inX, a.inY) else null,
            handleOut = if (a.outX != null && a.outY != null) Vec2(a.outX, a.outY) else null,
            width = a.width,
        )
    }

    /**
     * [path] as sub-paths of sharp anchors with explicit handles (broken tangents, §V14): every
     * cubic keeps its control points exactly, lines get no handles (straight). A closed sub-path
     * whose last point is its first merges them. `toVectorPath` gives the same geometry back
     * (tension 0, not a polyline). Used for shapes turned into paths and for SVG import.
     */
    fun subpathsOf(path: VectorPath): List<VSubpath> {
        val out = ArrayList<VSubpath>()
        var cur: ArrayList<AnchorBuilder>? = null
        var start = Vec2.ZERO
        var last = Vec2.ZERO
        fun finish(closed: Boolean) {
            val c = cur ?: return
            cur = null
            if (c.isEmpty()) return
            if (closed && c.size > 1) {
                val f = c.first(); val l = c.last()
                if (f.x == l.x && f.y == l.y) {
                    f.inX = l.inX; f.inY = l.inY
                    c.removeAt(c.lastIndex)
                }
            }
            out += VSubpath(c.map { it.build() }, closed && c.size > 1)
        }
        fun ensure(): ArrayList<AnchorBuilder> = cur ?: arrayListOf(AnchorBuilder(last.x, last.y)).also { cur = it; start = last }
        for (op in path.ops) {
            when (op) {
                is PathOp.MoveTo -> {
                    finish(false)
                    cur = arrayListOf(AnchorBuilder(op.p.x, op.p.y))
                    start = op.p; last = op.p
                }
                is PathOp.LineTo -> {
                    ensure() += AnchorBuilder(op.p.x, op.p.y)
                    last = op.p
                }
                is PathOp.CubicTo -> {
                    val c = ensure()
                    val a = c.last()
                    a.outX = op.c1.x - a.x; a.outY = op.c1.y - a.y
                    c += AnchorBuilder(op.p.x, op.p.y).also { it.inX = op.c2.x - op.p.x; it.inY = op.c2.y - op.p.y }
                    last = op.p
                }
                PathOp.Close -> {
                    finish(true)
                    last = start
                }
            }
        }
        finish(false)
        return out
    }

    private class AnchorBuilder(val x: Float, val y: Float) {
        var inX: Float? = null
        var inY: Float? = null
        var outX: Float? = null
        var outY: Float? = null
        fun build() = VAnchor(x, y, sharp = true, inX = inX, inY = inY, outX = outX, outY = outY)
    }

    /**
     * [s] as plain paths that draw it like the Shape tool does: one path (fill and/or outline;
     * a brush outline as a BRUSH stroke), and for an arrow a second path with its filled heads
     * (the shaft is stroked, the heads filled). Ids and opacity are [s]'s.
     */
    fun toPaths(s: VShape): List<VPath> {
        val o = s.shape
        val brush = o.paintsWithBrush
        val strokeStyle = when {
            !o.strokes -> null
            brush -> VStrokeStyle(
                kind = VStrokeKind.BRUSH, color = o.strokeColor, width = o.strokeWidth,
                brushTool = o.brushToolId ?: ToolId.BRUSH, brush = brushPresetOf(o), seed = s.seed,
            )
            else -> VStrokeStyle(
                kind = VStrokeKind.PLAIN, color = o.strokeColor, width = o.strokeWidth,
                cap = if (o.type.isLineLike) o.lineCap else LineCapStyle.ROUND, join = ShapeOutlines.join(o), miter = MITER_LIMIT,
            )
        }
        if (o.type == ShapeType.ARROW) {
            val g = ShapeOutlines.arrow(o)
            val shaftGeometry = if (brush) ShapeOutlines.brushOutline(o) else g.stroke
            val shaft = VPath(s.id, s.opacity, subpathsOf(shaftGeometry), stroke = strokeStyle?.copy(join = JoinStyle.ROUND))
            val heads = subpathsOf(g.fill)
            return if (heads.isEmpty()) listOf(shaft) else listOf(shaft, VPath(s.id, s.opacity, heads, fill = VPaint.Solid(o.strokeColor)))
        }
        val outline = if (brush) ShapeOutlines.brushOutline(o) else ShapeOutlines.outline(o)
        val fill = if (!o.type.isLineLike && o.style.fill) VPaint.Solid(o.fillColor) else null
        return listOf(VPath(s.id, s.opacity, subpathsOf(outline), fill = fill, stroke = strokeStyle))
    }

    /**
     * [paths] of [s] (see [toPaths]) as ONE path: an arrow's heads join the shaft (filled and
     * stroked: they come out rounder by half the stroke width; a curved custom shaft gets a thin
     * implicit fill). Exact for every other shape.
     */
    private fun mergePaths(s: VShape, paths: List<VPath>): VPath {
        if (paths.size == 1) return paths[0]
        val shaft = paths[0]
        val heads = paths[1]
        return shaft.copy(subpaths = shaft.subpaths + heads.subpaths, fill = heads.fill)
    }

    /** The brush a shape's outline is painted with (the one it was drawn with, else a default of its width). */
    internal fun brushPresetOf(o: com.brushwork.paint.tools.vector.ShapeObject): BrushPreset =
        o.brushPreset ?: BrushLibrary.defaultFor(o.brushToolId ?: ToolId.BRUSH).copy(size = o.strokeWidth)

    /** The brush a BRUSH-stroked path paints with: its own (sized up to the thickest anchor), else a default of its width. */
    internal fun brushOf(style: VStrokeStyle, maxWidth: Float = 1f): BrushPreset {
        val b = style.brush ?: BrushLibrary.defaultFor(style.brushTool ?: ToolId.BRUSH).copy(size = style.width)
        return if (maxWidth == 1f) b else b.copy(size = b.size * maxWidth)
    }

    /** Largest anchor thickness factor of [p] (≥ 0; 1 for no anchors). */
    internal fun maxWidth(p: VPath): Float {
        var m = 0f
        var any = false
        for (s in p.subpaths) for (a in s.anchors) {
            any = true
            if (a.width.isFinite() && a.width > m) m = a.width
        }
        return if (any) m else 1f
    }

    // ------------------------------------------------------------------ bounds

    private fun pathBounds(p: VPath): RectF {
        val cb = toVectorPath(p).controlBounds() ?: return RectF()
        var reach = 0f
        if (p.fill != null) reach = 2f
        val st = p.stroke
        if (st != null) {
            val wMax = maxWidth(p)
            reach = max(reach, when (st.kind) {
                VStrokeKind.PLAIN -> {
                    val joinF = if (st.join == JoinStyle.MITER) max(1f, st.miter) else 1f
                    val capF = if (st.cap == LineCapStyle.SQUARE) sqrt(2f) else 1f
                    max(0f, st.width) * wMax / 2f * max(joinF, capF) + 2f
                }
                VStrokeKind.BRUSH -> StrokeRaster.reach(brushOf(st, max(wMax, 1e-3f)), 1f)
            })
        }
        return RectF(cb.left - reach, cb.top - reach, cb.right + reach, cb.bottom + reach)
    }

    private fun shapeBounds(s: VShape): RectF {
        val o = s.shape
        val out = RectF()
        ShapeOutlines.paintSpec(o, o.paintsWithBrush)?.let { out.union(it.bounds) }
        if (o.paintsWithBrush) {
            ShapeOutlines.brushOutline(o).controlBounds()?.let { cb ->
                val e = StrokeRaster.reach(brushPresetOf(o), 1f)
                out.union(RectF(cb.left - e, cb.top - e, cb.right + e, cb.bottom + e))
            }
        }
        return out
    }

    // ------------------------------------------------------------------ hit tests

    /** The visible radius of a stroke's largest dab (no rendering margins). */
    private fun strokeRadius(o: VStroke): Float {
        val s = if (o.sizeScale.isFinite() && o.sizeScale > 0f) o.sizeScale else 1f
        val d = max(1f, o.preset.size * s)
        return d / 2f + o.preset.scatter.coerceAtLeast(0f) * d
    }

    /** Exact: within [tol] of the replayed dab chain (see [StrokeHits]). */
    private fun strokeHit(o: VStroke, p: Vec2, tol: Float): Boolean {
        if (o.points.size == 0) return false
        return StrokeHits.hits(o, p.x, p.y, tol)
    }

    private fun pathHit(o: VPath, p: Vec2, tol: Float): Boolean {
        val polys = toVectorPath(o).flatten(FLATTEN)
        if (polys.isEmpty()) return false
        if (o.fill != null && insideFill(polys, p, o.fillRule)) return true
        // Near the outline: half the line (none for a fill alone) plus the tolerance, so a tap
        // just beside a filled shape's edge still hits it (as ShapeOutlines.hits does).
        val st = o.stroke
        val half = when {
            st == null -> 0f
            st.kind == VStrokeKind.PLAIN -> max(0f, st.width) * maxWidth(o) / 2f
            else -> brushOf(st, max(maxWidth(o), 1e-3f)).size / 2f
        }
        return distanceToOutline(polys, p) <= half + tol
    }

    /** Distance from [p] to the flattened outline [polys] (closed ones include their closing edge). */
    internal fun distanceToOutline(polys: List<Polyline>, p: Vec2): Float {
        var best = Float.POSITIVE_INFINITY
        for (poly in polys) {
            val pts = poly.points
            if (pts.size == 1) best = min(best, p.distanceTo(pts[0]))
            for (i in 1 until pts.size) best = min(best, Geometry.distanceToSegment(p, pts[i - 1], pts[i]))
            if (poly.closed && pts.size > 2) best = min(best, Geometry.distanceToSegment(p, pts.last(), pts[0]))
        }
        return best
    }

    /** True when [p] is inside the fill of [polys] (every sub-path implicitly closed) under [rule]. */
    internal fun insideFill(polys: List<Polyline>, p: Vec2, rule: VFillRule): Boolean {
        var winding = 0
        var crossings = 0
        for (poly in polys) {
            val pts = poly.points
            val n = pts.size
            if (n < 3) continue
            for (i in 0 until n) {
                val a = pts[i]
                val b = pts[(i + 1) % n]
                if (a.y <= p.y) {
                    if (b.y > p.y && cross(a, b, p) > 0f) { winding++; crossings++ }
                } else if (b.y <= p.y && cross(a, b, p) < 0f) { winding--; crossings++ }
            }
        }
        return if (rule == VFillRule.EVENODD) crossings % 2 != 0 else winding != 0
    }

    private fun cross(a: Vec2, b: Vec2, p: Vec2): Float = (b.x - a.x) * (p.y - a.y) - (p.x - a.x) * (b.y - a.y)

    // ------------------------------------------------------------------ footprints (touching)

    /**
     * What [o] paints, opaque (strokes at their largest width), in document px: its paths are
     * built once, the returned function draws them (with the given paint) as often as needed.
     */
    private fun footprint(o: VObject): (Canvas, Paint) -> Unit = when (o) {
        is VStroke -> {
            // The replayed dabs as a chain of capsules (thin tapered ends, wide pressure swells).
            val d = StrokeHits.dabs(o)
            val n = d.size / 3
            val chain: (Canvas, Paint) -> Unit = { c, paint ->
                paint.style = Paint.Style.FILL
                for (i in 0 until n) c.drawCircle(d[3 * i], d[3 * i + 1], max(0.5f, d[3 * i + 2]), paint)
                if (n > 1) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeCap = Paint.Cap.ROUND
                    for (i in 1 until n) {
                        paint.strokeWidth = max(1f, 2f * min(d[3 * i - 1], d[3 * i + 2]))
                        c.drawLine(d[3 * i - 3], d[3 * i - 2], d[3 * i], d[3 * i + 1], paint)
                    }
                }
            }
            chain
        }
        is VPath -> {
            val path = toVectorPath(o).toAndroidPath()
            val filled = o.fill != null
            if (filled) path.fillType = if (o.fillRule == VFillRule.EVENODD) Path.FillType.EVEN_ODD else Path.FillType.WINDING
            val st = o.stroke
            val width = when {
                st == null -> if (o.fill == null) 1f else 0f
                st.kind == VStrokeKind.PLAIN -> max(0f, st.width) * maxWidth(o)
                else -> brushOf(st, max(maxWidth(o), 1e-3f)).size
            }
            val draw: (Canvas, Paint) -> Unit = { c, paint ->
                if (filled) {
                    paint.style = Paint.Style.FILL
                    c.drawPath(path, paint)
                }
                if (width > 0f) {
                    strokePaint(paint, width, Paint.Cap.ROUND, Paint.Join.ROUND)
                    c.drawPath(path, paint)
                }
            }
            draw
        }
        is VShape -> {
            val sh = o.shape
            val spec = ShapeOutlines.paintSpec(sh, sh.paintsWithBrush)
            val brushPath = if (sh.paintsWithBrush) ShapeOutlines.brushOutline(sh).toAndroidPath() else null
            val brushSize = if (brushPath != null) brushPresetOf(sh).size else 0f
            val draw: (Canvas, Paint) -> Unit = { c, paint ->
                if (spec != null) {
                    spec.fill?.let { paint.style = Paint.Style.FILL; c.drawPath(it, paint) }
                    spec.stroke?.let { strokePaint(paint, spec.strokeWidth, spec.cap, Paint.Join.ROUND); c.drawPath(it, paint) }
                    spec.strokeFill?.let { paint.style = Paint.Style.FILL; c.drawPath(it, paint) }
                }
                if (brushPath != null) {
                    strokePaint(paint, brushSize, Paint.Cap.ROUND, Paint.Join.ROUND)
                    c.drawPath(brushPath, paint)
                }
            }
            draw
        }
    }

    private fun strokePaint(paint: Paint, width: Float, cap: Paint.Cap, join: Paint.Join) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1f, width)
        paint.strokeCap = cap
        paint.strokeJoin = join
    }

    // ------------------------------------------------------------------ transforms

    /** [m] applied to (x, y) (homographies divide by w). */
    private fun map(m: FloatArray, x: Float, y: Float): Vec2 {
        val qx = m[0] * x + m[1] * y + m[2]
        val qy = m[3] * x + m[4] * y + m[5]
        val w = m[6] * x + m[7] * y + m[8]
        return if (w == 1f) Vec2(qx, qy) else if (w != 0f) Vec2(qx / w, qy / w) else Vec2(qx, qy)
    }

    /** The 2x2 Jacobian of [m] at (x, y) as (a, b, c, d): dX/dx, dX/dy, dY/dx, dY/dy. */
    private fun jacobian(m: FloatArray, x: Float, y: Float): FloatArray {
        val w = m[6] * x + m[7] * y + m[8]
        if (w == 0f) return floatArrayOf(m[0], m[1], m[3], m[4])
        val q = map(m, x, y)
        return floatArrayOf((m[0] - q.x * m[6]) / w, (m[1] - q.x * m[7]) / w, (m[3] - q.y * m[6]) / w, (m[4] - q.y * m[7]) / w)
    }

    /** sqrt|det| of [m]'s Jacobian at (x, y): how much lengths scale there. */
    private fun scaleAt(m: FloatArray, x: Float, y: Float): Float {
        val j = jacobian(m, if (x.isFinite()) x else 0f, if (y.isFinite()) y else 0f)
        val s = sqrt(abs(j[0] * j[3] - j[1] * j[2]))
        return if (s.isFinite()) s else 1f
    }

    private fun mapPath(p: VPath, m: FloatArray): VPath {
        val cb = toVectorPath(p).controlBounds()
        val cx = cb?.let { (it.left + it.right) / 2f } ?: 0f
        val cy = cb?.let { (it.top + it.bottom) / 2f } ?: 0f
        val s = scaleAt(m, cx, cy)
        val subs = p.subpaths.map { sp ->
            sp.copy(anchors = sp.anchors.map { a ->
                val q = map(m, a.x, a.y)
                val hin = if (a.inX != null && a.inY != null) map(m, a.x + a.inX, a.y + a.inY) - q else null
                val hout = if (a.outX != null && a.outY != null) map(m, a.x + a.outX, a.y + a.outY) - q else null
                a.copy(x = q.x, y = q.y, inX = hin?.x, inY = hin?.y, outX = hout?.x, outY = hout?.y)
            })
        }
        val stroke = p.stroke?.let { st ->
            st.copy(width = st.width * s, brush = st.brush?.let { scaledBrush(it, s) })
        }
        return p.copy(subpaths = subs, fill = p.fill?.let { mapPaint(it, m, cx, cy) }, stroke = stroke)
    }

    private fun scaledBrush(b: BrushPreset, s: Float): BrushPreset =
        if (s == 1f) b else b.copy(size = b.size * s, taperStart = b.taperStart * s, taperEnd = b.taperEnd * s)

    /** [paint] under [m] (gradients: exact for the affine part at (cx, cy)). */
    private fun mapPaint(paint: VPaint, m: FloatArray, cx: Float, cy: Float): VPaint = when (paint) {
        is VPaint.Solid -> paint
        is VPaint.Linear -> {
            val j = jacobian(m, paint.x0, paint.y0)
            val q0 = map(m, paint.x0, paint.y0)
            val dx = paint.x1 - paint.x0
            val dy = paint.y1 - paint.y0
            val len2 = dx * dx + dy * dy
            val det = j[0] * j[3] - j[1] * j[2]
            if (len2 <= 0f || det == 0f || !det.isFinite()) {
                val q1 = map(m, paint.x1, paint.y1)
                paint.copy(x0 = q0.x, y0 = q0.y, x1 = q1.x, y1 = q1.y)
            } else {
                // The gradient's slope maps by the inverse transpose: iso-lines stay where they were.
                val gx = (j[3] * dx - j[2] * dy) / det / len2
                val gy = (-j[1] * dx + j[0] * dy) / det / len2
                val g2 = gx * gx + gy * gy
                paint.copy(x0 = q0.x, y0 = q0.y, x1 = q0.x + gx / g2, y1 = q0.y + gy / g2)
            }
        }
        is VPaint.Radial -> {
            // Local affine of m at the gradient's centre, composed with the gradient's own matrix.
            val g = paint.matrix?.takeIf { it.size >= 6 } ?: listOf(1f, 0f, 0f, 1f, 0f, 0f)
            val center = Vec2(g[0] * paint.cx + g[2] * paint.cy + g[4], g[1] * paint.cx + g[3] * paint.cy + g[5])
            val j = jacobian(m, center.x, center.y)
            val q = map(m, center.x, center.y)
            // Affine A(p) = J (p - center) + q, as SVG values (a, b, c, d, e, f).
            val ae = q.x - (j[0] * center.x + j[1] * center.y)
            val af = q.y - (j[2] * center.x + j[3] * center.y)
            val a = j[0] * g[0] + j[1] * g[1]
            val b = j[2] * g[0] + j[3] * g[1]
            val c = j[0] * g[2] + j[1] * g[3]
            val d = j[2] * g[2] + j[3] * g[3]
            val e = j[0] * g[4] + j[1] * g[5] + ae
            val f = j[2] * g[4] + j[3] * g[5] + af
            paint.copy(matrix = listOf(a, b, c, d, e, f))
        }
    }

    /** [s] mapped by [m] when [m] is a similarity without reflection (else null). */
    private fun similarityShape(s: VShape, m: FloatArray): VShape? {
        if (m[6] != 0f || m[7] != 0f || m[8] == 0f) return null
        val k = 1f / m[8]
        val a = m[0] * k; val b = m[1] * k; val c = m[3] * k; val d = m[4] * k
        val scale = sqrt(a * a + c * c)
        if (scale <= 0f || !scale.isFinite()) return null
        val eps = 1e-4f * scale
        if (abs(a - d) > eps || abs(b + c) > eps) return null
        val o = s.shape
        val center = map(m, o.cx, o.cy)
        val rot = o.rotation + Math.toDegrees(atan2(c.toDouble(), a.toDouble())).toFloat()
        val shape = o.copy(
            cx = center.x, cy = center.y, w = o.w * scale, h = o.h * scale, rotation = rot,
            strokeWidth = o.strokeWidth * scale, cornerRadius = o.cornerRadius * scale,
            brushPreset = o.brushPreset?.let { scaledBrush(it, scale) },
        )
        return s.copy(shape = shape)
    }
}
