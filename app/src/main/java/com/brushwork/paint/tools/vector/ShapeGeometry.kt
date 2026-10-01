package com.brushwork.paint.tools.vector

import com.brushwork.paint.core.Geometry
import com.brushwork.paint.core.Vec2
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

@Serializable
enum class ShapeType(val label: String) {
    LINE("Line"),
    RECTANGLE("Rectangle"),
    ELLIPSE("Ellipse"),
    POLYGON("Polygon"),
    STAR("Star"),
    ARROW("Arrow");

    /** Defined by two end points instead of a box. */
    val isLineLike: Boolean get() = this == LINE || this == ARROW

    /** Supports live corners (round / bevel / inverted). */
    val hasCorners: Boolean get() = this == RECTANGLE || this == POLYGON || this == STAR
}

@Serializable
enum class ShapeStyle(val label: String, val stroke: Boolean, val fill: Boolean) {
    STROKE("Stroke", true, false),
    FILL("Fill", false, true),
    STROKE_FILL("Stroke + fill", true, true),
}

/** Live-corner treatment of polygon vertices (like Illustrator's corner widget). */
@Serializable
enum class CornerStyle(val label: String) {
    SHARP("Sharp"),
    ROUND("Round"),
    BEVEL("Bevel"),
    INVERTED("Inverted"),
}

@Serializable
enum class LineCapStyle(val label: String) { BUTT("Flat"), ROUND("Round"), SQUARE("Square") }

@Serializable
enum class ArrowHeads(val label: String, val start: Boolean, val end: Boolean) {
    END("End", false, true),
    START("Start", true, false),
    BOTH("Both", true, true),
}

@Serializable
enum class ArrowHeadStyle(val label: String) { FILLED("Filled"), OPEN("Open") }

/** Stroke join used when drawing a path. */
enum class JoinStyle { MITER, ROUND, BEVEL }

/**
 * Placement of a shape in document pixels: center, unrotated size and rotation (degrees,
 * clockwise on screen). Line-like shapes use `w` = length, `h` = 0 and `rotationDeg` = direction.
 */
data class ShapeBox(val cx: Float, val cy: Float, val w: Float, val h: Float, val rotationDeg: Float = 0f) {
    val center: Vec2 get() = Vec2(cx, cy)
    private val rad: Float get() = rotationDeg * Geometry.DEG

    /** Box-local point (origin at the center, unrotated axes) -> document. */
    fun toDoc(local: Vec2): Vec2 = local.rotated(rad) + center

    /** Document point -> box-local. */
    fun toLocal(doc: Vec2): Vec2 = (doc - center).rotated(-rad)

    val left: Float get() = cx - w / 2f
    val top: Float get() = cy - h / 2f

    /** Line start / end (for line-like shapes). */
    val start: Vec2 get() = toDoc(Vec2(-w / 2f, 0f))
    val end: Vec2 get() = toDoc(Vec2(w / 2f, 0f))

    /** Corners in document space: top-left, top-right, bottom-right, bottom-left. */
    fun corners(): List<Vec2> = listOf(
        toDoc(Vec2(-w / 2f, -h / 2f)), toDoc(Vec2(w / 2f, -h / 2f)),
        toDoc(Vec2(w / 2f, h / 2f)), toDoc(Vec2(-w / 2f, h / 2f)),
    )

    fun translated(dx: Float, dy: Float) = copy(cx = cx + dx, cy = cy + dy)

    companion object {
        /** Line from [a] to [b]. */
        fun line(a: Vec2, b: Vec2): ShapeBox {
            val d = b - a
            val c = a.lerp(b, 0.5f)
            return ShapeBox(c.x, c.y, d.length, 0f, if (d.lengthSq < 1e-12f) 0f else Math.toDegrees(atan2(d.y, d.x).toDouble()).toFloat())
        }

        /** Axis-aligned box spanning the two corners. */
        fun fromCorners(a: Vec2, b: Vec2): ShapeBox =
            ShapeBox((a.x + b.x) / 2f, (a.y + b.y) / 2f, abs(b.x - a.x), abs(b.y - a.y), 0f)
    }
}

/** Parameters that shape the outline of box shapes. */
data class OutlineParams(
    val sides: Int = 5,
    val starPoints: Int = 5,
    /** Star inner radius as a fraction of the outer radius (0..1). */
    val innerRatio: Float = 0.45f,
    val corner: CornerStyle = CornerStyle.SHARP,
    /** Corner size in document pixels (cut-back distance along each edge). */
    val cornerRadius: Float = 0f,
)

/** Arrow parts: a stroked shaft (+ open heads) and filled heads. */
data class ArrowGeometry(val stroke: VectorPath, val fill: VectorPath)

object ShapeGeometry {
    const val MIN_SIDES = 3
    const val MAX_SIDES = 64

    /** Regular polygon on the unit circle, first vertex at the top, clockwise on screen (y down). */
    fun unitPolygon(sides: Int): List<Vec2> {
        val n = sides.coerceIn(MIN_SIDES, MAX_SIDES)
        return List(n) { i -> Vec2.polar(1f, (-PI / 2 + 2 * PI * i / n).toFloat()) }
    }

    /** Star with [points] tips on the unit circle and inner vertices at [innerRatio]. */
    fun unitStar(points: Int, innerRatio: Float): List<Vec2> {
        val n = points.coerceIn(MIN_SIDES, MAX_SIDES)
        val inner = innerRatio.coerceIn(0.01f, 1f)
        return List(2 * n) { i ->
            val r = if (i % 2 == 0) 1f else inner
            Vec2.polar(r, (-PI / 2 + PI * i / n).toFloat())
        }
    }

    /** Width / height of the bounding box of [unit] (what "keep proportions" preserves). */
    fun naturalAspect(unit: List<Vec2>): Float {
        val b = Bounds.of(unit) ?: return 1f
        return if (b.height < 1e-6f) 1f else b.width / b.height
    }

    /** Natural aspect of a box shape type (1 for rectangles and ellipses). */
    fun naturalAspect(type: ShapeType, params: OutlineParams): Float = when (type) {
        ShapeType.POLYGON -> naturalAspect(unitPolygon(params.sides))
        ShapeType.STAR -> naturalAspect(unitStar(params.starPoints, params.innerRatio))
        else -> 1f
    }

    /** Maps the bounding box of [unit] onto the box `[-w/2, w/2] x [-h/2, h/2]`. */
    fun fitToBox(unit: List<Vec2>, w: Float, h: Float): List<Vec2> {
        val b = Bounds.of(unit) ?: return unit
        val cx = (b.left + b.right) / 2f; val cy = (b.top + b.bottom) / 2f
        val sx = if (b.width < 1e-6f) 0f else w / b.width
        val sy = if (b.height < 1e-6f) 0f else h / b.height
        return unit.map { Vec2((it.x - cx) * sx, (it.y - cy) * sy) }
    }

    /** Rectangle corners centered on the origin (clockwise from top-left). */
    fun rectVertices(w: Float, h: Float): List<Vec2> =
        listOf(Vec2(-w / 2f, -h / 2f), Vec2(w / 2f, -h / 2f), Vec2(w / 2f, h / 2f), Vec2(-w / 2f, h / 2f))

    /** Box-local vertices of a polygonal box shape. */
    fun vertices(type: ShapeType, w: Float, h: Float, params: OutlineParams): List<Vec2> = when (type) {
        ShapeType.POLYGON -> fitToBox(unitPolygon(params.sides), w, h)
        ShapeType.STAR -> fitToBox(unitStar(params.starPoints, params.innerRatio), w, h)
        else -> rectVertices(w, h)
    }

    /** The effective cut-back distance at each vertex (radius clamped to half the shorter adjacent edge). */
    fun cornerCuts(v: List<Vec2>, radius: Float): FloatArray {
        val n = v.size
        return FloatArray(n) { i ->
            val lp = v[i].distanceTo(v[(i - 1 + n) % n])
            val ln = v[i].distanceTo(v[(i + 1) % n])
            min(radius.coerceAtLeast(0f), min(lp, ln) / 2f)
        }
    }

    /**
     * Closed outline through [v] with every vertex treated according to [style]: each vertex is
     * cut back by the (clamped) radius along both adjacent edges; ROUND joins the cut points with
     * an arc tangent to both edges, BEVEL with a straight segment, INVERTED with a concave arc
     * centered on the original vertex.
     */
    fun cornerPath(v: List<Vec2>, style: CornerStyle, radius: Float): VectorPath {
        val n = v.size
        if (n < 3 || style == CornerStyle.SHARP || radius <= 0f) return VectorPath.polygon(v)
        val cuts = cornerCuts(v, radius)
        val ops = ArrayList<PathOp>(n * 4 + 2)
        for (i in 0 until n) {
            val p = v[i]
            val c = corner(p, v[(i - 1 + n) % n], v[(i + 1) % n], cuts[i])
            if (c == null) {
                ops += if (i == 0) PathOp.MoveTo(p) else PathOp.LineTo(p)
                continue
            }
            ops += if (i == 0) PathOp.MoveTo(c.a) else PathOp.LineTo(c.a)
            appendCorner(c, style, ops)
        }
        ops += PathOp.Close
        return VectorPath(ops)
    }

    /**
     * One treated corner at vertex [p]: the cut points [a] (towards the previous vertex) and [b]
     * (towards the next one), [d] from [p], the unit directions [uA] / [uB] of the two edges and
     * the angle [theta] between them.
     */
    internal class Corner(val p: Vec2, val a: Vec2, val b: Vec2, val uA: Vec2, val uB: Vec2, val theta: Float, val d: Float)

    /**
     * The corner at [p] between straight edges to [prev] and [next], cut back by [d]; null when
     * it stays sharp (no cut, a degenerate edge or a straight angle).
     */
    internal fun corner(p: Vec2, prev: Vec2, next: Vec2, d: Float): Corner? {
        val toPrev = prev - p; val toNext = next - p
        val lp = toPrev.length; val ln = toNext.length
        val uA = if (lp > 1e-6f) toPrev / lp else Vec2.ZERO
        val uB = if (ln > 1e-6f) toNext / ln else Vec2.ZERO
        val theta = acos(uA.dot(uB).coerceIn(-1f, 1f))
        val sharp = d < 1e-3f || lp < 1e-6f || ln < 1e-6f || theta > PI.toFloat() - 1e-3f
        if (sharp) return null
        return Corner(p, p + uA * d, p + uB * d, uA, uB, theta, d)
    }

    /** Appends the treatment of corner [c] from its cut point a (already reached) to b. */
    internal fun appendCorner(c: Corner, style: CornerStyle, ops: MutableList<PathOp>) {
        val p = c.p; val a = c.a; val b = c.b; val d = c.d
        when (style) {
            CornerStyle.BEVEL -> ops += PathOp.LineTo(b)
            CornerStyle.ROUND -> {
                val bis = (c.uA + c.uB).normalized()
                val center = p + bis * (d / cos(c.theta / 2f))
                val r = d * tan(c.theta / 2f)
                val a0 = (a - center).angle
                val sweep = signedAngle(a - center, b - center)
                arcToCubics(center, r, r, a0, sweep, ops)
                ops[ops.lastIndex] = (ops.last() as PathOp.CubicTo).copy(p = b)
            }
            CornerStyle.INVERTED -> {
                val a0 = c.uA.angle
                val sweep = signedAngle(c.uA, c.uB)
                arcToCubics(p, d, d, a0, sweep, ops)
                ops[ops.lastIndex] = (ops.last() as PathOp.CubicTo).copy(p = b)
            }
            CornerStyle.SHARP -> ops += PathOp.LineTo(b)
        }
    }

    /** Signed angle (radians, -PI..PI) rotating [a] onto [b]. */
    fun signedAngle(a: Vec2, b: Vec2): Float = atan2(a.cross(b), a.dot(b))

    /**
     * Appends cubic Beziers approximating the elliptical arc around [center] (radii [rx], [ry])
     * from angle [start] sweeping [sweep] radians. Split into pieces of at most 90 degrees.
     */
    fun arcToCubics(center: Vec2, rx: Float, ry: Float, start: Float, sweep: Float, out: MutableList<PathOp>) {
        val pieces = max(1, ceil(abs(sweep) / (PI / 2).toFloat() - 1e-4f).toInt())
        val s = sweep / pieces
        val k = 4f / 3f * tan(s / 4f)
        var a0 = start
        for (i in 0 until pieces) {
            val a1 = a0 + s
            val c0 = cos(a0); val s0 = sin(a0); val c1 = cos(a1); val s1 = sin(a1)
            val p0 = Vec2(center.x + rx * c0, center.y + ry * s0)
            val p1 = Vec2(center.x + rx * c1, center.y + ry * s1)
            val h0 = Vec2(-rx * s0 * k, ry * c0 * k)
            val h1 = Vec2(-rx * s1 * k, ry * c1 * k)
            out += PathOp.CubicTo(p0 + h0, p1 - h1, p1)
            a0 = a1
        }
    }

    /** Full ellipse centered on the origin, starting at the top, clockwise. */
    fun ellipsePath(w: Float, h: Float): VectorPath {
        val rx = w / 2f; val ry = h / 2f
        val ops = ArrayList<PathOp>(6)
        ops += PathOp.MoveTo(Vec2(0f, -ry))
        arcToCubics(Vec2.ZERO, rx, ry, (-PI / 2).toFloat(), (2 * PI).toFloat(), ops)
        ops += PathOp.Close
        return VectorPath(ops)
    }

    /** Document-space outline of a box shape (rectangle, ellipse, polygon, star). */
    fun outline(type: ShapeType, box: ShapeBox, params: OutlineParams): VectorPath {
        val local = when (type) {
            ShapeType.ELLIPSE -> ellipsePath(box.w, box.h)
            ShapeType.RECTANGLE, ShapeType.POLYGON, ShapeType.STAR ->
                cornerPath(vertices(type, box.w, box.h, params), params.corner, params.cornerRadius)
            ShapeType.LINE, ShapeType.ARROW -> VectorPath.polyline(listOf(Vec2(-box.w / 2f, 0f), Vec2(box.w / 2f, 0f)))
        }
        return local.transformed { box.toDoc(it) }
    }

    /** Stroke join that matches a corner style. */
    fun joinFor(style: CornerStyle): JoinStyle = when (style) {
        CornerStyle.ROUND -> JoinStyle.ROUND
        CornerStyle.BEVEL -> JoinStyle.BEVEL
        CornerStyle.SHARP, CornerStyle.INVERTED -> JoinStyle.MITER
    }

    /**
     * Arrow from [a] to [b]. Head length is [headScale] x [strokeWidth] (at least 4 px) and is
     * limited so heads never overlap. Filled heads are returned in [ArrowGeometry.fill]; open
     * heads are chevrons added to the stroke.
     */
    fun arrow(a: Vec2, b: Vec2, strokeWidth: Float, heads: ArrowHeads, style: ArrowHeadStyle, headScale: Float): ArrowGeometry {
        val len = a.distanceTo(b)
        if (len < 1e-3f) return ArrowGeometry(VectorPath.EMPTY, VectorPath.EMPTY)
        val u = (b - a) / len
        val perp = u.perpendicular()
        val (headLen, halfW) = arrowHeadSize(len, strokeWidth, heads, style, headScale)
        var s = a
        var e = b
        val strokeOps = ArrayList<PathOp>()
        val fillOps = ArrayList<PathOp>()
        fun head(tip: Vec2, dir: Vec2) {
            val base = tip - dir * headLen
            val l = base + perp * halfW
            val r = base - perp * halfW
            if (style == ArrowHeadStyle.FILLED) {
                fillOps += PathOp.MoveTo(tip); fillOps += PathOp.LineTo(l); fillOps += PathOp.LineTo(r); fillOps += PathOp.Close
            } else {
                strokeOps += PathOp.MoveTo(l); strokeOps += PathOp.LineTo(tip); strokeOps += PathOp.LineTo(r)
            }
        }
        // The shaft stops under a filled head (so round/square caps never poke past the tip) or
        // half a stroke short of an open head's tip.
        val inset = if (style == ArrowHeadStyle.FILLED) headLen * 0.6f else strokeWidth / 2f
        if (heads.end) { head(b, u); e = b - u * min(inset, len / 2f) }
        if (heads.start) { head(a, -u); s = a + u * min(inset, len / 2f) }
        val shaft = ArrayList<PathOp>(2 + strokeOps.size)
        shaft += PathOp.MoveTo(s); shaft += PathOp.LineTo(e)
        shaft += strokeOps
        return ArrowGeometry(VectorPath(shaft), VectorPath(fillOps))
    }

    /** Arrowhead length and half width for an arrow of length [len] (see [arrow]). */
    internal fun arrowHeadSize(len: Float, strokeWidth: Float, heads: ArrowHeads, style: ArrowHeadStyle, headScale: Float): Pair<Float, Float> {
        val count = (if (heads.start) 1 else 0) + (if (heads.end) 1 else 0)
        val headLen = min(max(strokeWidth * headScale, 4f), len * (if (count == 2) 0.45f else 0.9f))
        val halfW = headLen * (if (style == ArrowHeadStyle.FILLED) 0.5f else 0.6f)
        return headLen to halfW
    }

    /**
     * An arrow's outline as ONE continuous open path, to paint it with a brush in a single
     * stroke: through each open head's chevron (retracing one arm) or around each filled head's
     * triangle, joined by the shaft. Heads match [arrow] (filled heads are also filled there).
     */
    fun arrowBrushOutline(a: Vec2, b: Vec2, strokeWidth: Float, heads: ArrowHeads, style: ArrowHeadStyle, headScale: Float): VectorPath {
        val len = a.distanceTo(b)
        if (len < 1e-3f) return VectorPath.EMPTY
        val u = (b - a) / len
        val perp = u.perpendicular()
        val (headLen, halfW) = arrowHeadSize(len, strokeWidth, heads, style, headScale)
        val pts = ArrayList<Vec2>(12)
        // One head at [tip] pointing along [dir]; [first] = the head is drawn before the shaft.
        fun head(tip: Vec2, dir: Vec2, first: Boolean) {
            val base = tip - dir * headLen
            val l = base + perp * halfW
            val r = base - perp * halfW
            val part = if (style == ArrowHeadStyle.FILLED) listOf(base, l, tip, r, base) else listOf(tip, l, tip, r)
            // The shaft joins the head at its base (filled) or tip (open).
            pts += if (first) part.asReversed() else part
        }
        if (heads.start) head(a, -u, first = true) else pts += a
        if (heads.end) head(b, u, first = false) else pts += b
        return VectorPath.polyline(pts)
    }

    // ------------------------------------------------------------------ arrows along a path

    /** Unit direction in which the open polyline [pts] leaves its last point (null when it has no length). */
    private fun endDirection(pts: List<Vec2>): Vec2? {
        val tip = pts.last()
        for (i in pts.lastIndex - 1 downTo 0) {
            val d = tip - pts[i]
            if (d.length > 1e-3f) return d.normalized()
        }
        return null
    }

    /** [pts] shortened by [d] at its end (along the polyline). */
    internal fun trimEnd(pts: List<Vec2>, d: Float): List<Vec2> {
        if (d <= 0f || pts.size < 2) return pts
        var left = d
        var i = pts.lastIndex
        var end = pts[i]
        while (i > 0) {
            val prev = pts[i - 1]
            val seg = end.distanceTo(prev)
            if (seg >= left) {
                val cut = end.lerp(prev, if (seg > 0f) left / seg else 0f)
                return pts.subList(0, i) + cut
            }
            left -= seg
            i--
            end = prev
        }
        return listOf(pts[0], pts[0])
    }

    /** [pts] shortened by [d] at its start. */
    internal fun trimStart(pts: List<Vec2>, d: Float): List<Vec2> = trimEnd(pts.asReversed(), d).asReversed()

    /**
     * An arrow along the open polyline [pts] (a custom line with several points): like [arrow],
     * but each head points along the first / last segment and the shaft follows the polyline.
     */
    fun arrowAlong(pts: List<Vec2>, strokeWidth: Float, heads: ArrowHeads, style: ArrowHeadStyle, headScale: Float): ArrowGeometry {
        if (pts.size < 2) return ArrowGeometry(VectorPath.EMPTY, VectorPath.EMPTY)
        val len = VectorPath.length(pts)
        val uEnd = endDirection(pts)
        val uStart = endDirection(pts.asReversed())
        if (len < 1e-3f || uEnd == null || uStart == null) return ArrowGeometry(VectorPath.EMPTY, VectorPath.EMPTY)
        val (headLen, halfW) = arrowHeadSize(len, strokeWidth, heads, style, headScale)
        val strokeOps = ArrayList<PathOp>()
        val fillOps = ArrayList<PathOp>()
        fun head(tip: Vec2, dir: Vec2) {
            val perp = dir.perpendicular()
            val base = tip - dir * headLen
            val l = base + perp * halfW
            val r = base - perp * halfW
            if (style == ArrowHeadStyle.FILLED) {
                fillOps += PathOp.MoveTo(tip); fillOps += PathOp.LineTo(l); fillOps += PathOp.LineTo(r); fillOps += PathOp.Close
            } else {
                strokeOps += PathOp.MoveTo(l); strokeOps += PathOp.LineTo(tip); strokeOps += PathOp.LineTo(r)
            }
        }
        val inset = min(if (style == ArrowHeadStyle.FILLED) headLen * 0.6f else strokeWidth / 2f, len / 2f)
        var shaft = pts
        if (heads.end) { head(pts.last(), uEnd); shaft = trimEnd(shaft, inset) }
        if (heads.start) { head(pts.first(), uStart); shaft = trimStart(shaft, inset) }
        val ops = ArrayList<PathOp>(shaft.size + strokeOps.size)
        ops += PathOp.MoveTo(shaft[0])
        for (i in 1 until shaft.size) ops += PathOp.LineTo(shaft[i])
        ops += strokeOps
        return ArrowGeometry(VectorPath(ops), VectorPath(fillOps))
    }

    /** [arrowAlong] as ONE continuous open path for a brush (see [arrowBrushOutline]). */
    fun arrowBrushOutlineAlong(pts: List<Vec2>, strokeWidth: Float, heads: ArrowHeads, style: ArrowHeadStyle, headScale: Float): VectorPath {
        if (pts.size < 2) return VectorPath.EMPTY
        val len = VectorPath.length(pts)
        val uEnd = endDirection(pts)
        val uStart = endDirection(pts.asReversed())
        if (len < 1e-3f || uEnd == null || uStart == null) return VectorPath.EMPTY
        val (headLen, halfW) = arrowHeadSize(len, strokeWidth, heads, style, headScale)
        fun part(tip: Vec2, dir: Vec2): List<Vec2> {
            val perp = dir.perpendicular()
            val base = tip - dir * headLen
            val l = base + perp * halfW
            val r = base - perp * halfW
            return if (style == ArrowHeadStyle.FILLED) listOf(base, l, tip, r, base) else listOf(tip, l, tip, r)
        }
        // Filled heads join the shaft at their base, open heads at their tip.
        val trim = if (style == ArrowHeadStyle.FILLED) min(headLen, len / 2f) else 0f
        var shaft = pts
        if (heads.end) shaft = trimEnd(shaft, trim)
        if (heads.start) shaft = trimStart(shaft, trim)
        val out = ArrayList<Vec2>(shaft.size + 10)
        if (heads.start) out += part(pts.first(), uStart).asReversed()
        out += shaft
        if (heads.end) out += part(pts.last(), uEnd)
        return VectorPath.polyline(out)
    }

    /**
     * The outline a brush paints for a shape: one continuous sub-path (a brush tool can only have
     * one stroke in progress). Box shapes use their [outline] (corner styles included), lines a
     * straight path, arrows [arrowBrushOutline].
     */
    fun brushOutline(
        type: ShapeType,
        box: ShapeBox,
        params: OutlineParams,
        strokeWidth: Float,
        heads: ArrowHeads,
        headStyle: ArrowHeadStyle,
        headScale: Float,
    ): VectorPath = when (type) {
        ShapeType.LINE -> VectorPath.polyline(listOf(box.start, box.end))
        ShapeType.ARROW -> arrowBrushOutline(box.start, box.end, strokeWidth, heads, headStyle, headScale)
        else -> outline(type, box, params)
    }

    // ------------------------------------------------------------------ interaction math

    /** Snaps the direction [from] -> [to] to multiples of [stepDeg], keeping the projected length. */
    fun snapAngle(from: Vec2, to: Vec2, stepDeg: Float = 15f): Vec2 {
        val d = to - from
        if (d.lengthSq < 1e-12f) return to
        val step = stepDeg * Geometry.DEG
        val ang = (d.angle / step).roundToInt() * step
        val dir = Vec2(cos(ang), sin(ang))
        return from + dir * d.dot(dir).coerceAtLeast(0f)
    }

    /** Rounds [deg] to a multiple of [stepDeg] and normalizes to (-180, 180]. */
    fun snapDegrees(deg: Float, stepDeg: Float = 15f): Float = normalizeDegrees((deg / stepDeg).roundToInt() * stepDeg)

    fun normalizeDegrees(deg: Float): Float {
        var d = deg % 360f
        if (d <= -180f) d += 360f
        if (d > 180f) d -= 360f
        return d
    }

    /** Nearest grid intersection of a square grid. */
    fun snapToGrid(p: Vec2, spacing: Float, offsetX: Float, offsetY: Float): Vec2 {
        if (spacing <= 0f) return p
        return Vec2(
            ((p.x - offsetX) / spacing).roundToInt() * spacing + offsetX,
            ((p.y - offsetY) / spacing).roundToInt() * spacing + offsetY,
        )
    }

    /**
     * Box dragged from [anchor] to [current]. With [fromCenter] the anchor is the center; with
     * [aspect] (width / height) the box keeps that proportion (the larger dimension wins).
     */
    fun dragBox(anchor: Vec2, current: Vec2, fromCenter: Boolean, aspect: Float?): ShapeBox {
        val dx = current.x - anchor.x
        val dy = current.y - anchor.y
        var w = abs(dx) * (if (fromCenter) 2f else 1f)
        var h = abs(dy) * (if (fromCenter) 2f else 1f)
        if (aspect != null && aspect > 0f) {
            if (h * aspect < w) h = w / aspect else w = h * aspect
        }
        if (fromCenter) return ShapeBox(anchor.x, anchor.y, w, h, 0f)
        val sx = if (dx < 0f) -1f else 1f
        val sy = if (dy < 0f) -1f else 1f
        return ShapeBox(anchor.x + sx * w / 2f, anchor.y + sy * h / 2f, w, h, 0f)
    }

    /** Resize handle of the pending shape's box. [fx]/[fy] are -1, 0 or 1 (which edges it moves). */
    enum class Handle(val fx: Int, val fy: Int) {
        TOP_LEFT(-1, -1), TOP(0, -1), TOP_RIGHT(1, -1), RIGHT(1, 0),
        BOTTOM_RIGHT(1, 1), BOTTOM(0, 1), BOTTOM_LEFT(-1, 1), LEFT(-1, 0),
    }

    /**
     * Resizes [start] by dragging [handle] to document point [p]. The opposite edge stays fixed
     * (or the center with [fromCenter]); [aspect] keeps proportions. Sizes never go below [minSize].
     */
    fun resize(start: ShapeBox, handle: Handle, p: Vec2, fromCenter: Boolean, aspect: Float?, minSize: Float = 1f): ShapeBox {
        val q = start.toLocal(p)
        val hw = start.w / 2f; val hh = start.h / 2f
        var l = -hw; var r = hw; var t = -hh; var b = hh
        if (fromCenter) {
            if (handle.fx != 0) { val x = max(abs(q.x), minSize / 2f); l = -x; r = x }
            if (handle.fy != 0) { val y = max(abs(q.y), minSize / 2f); t = -y; b = y }
        } else {
            if (handle.fx < 0) l = min(q.x, r - minSize)
            if (handle.fx > 0) r = max(q.x, l + minSize)
            if (handle.fy < 0) t = min(q.y, b - minSize)
            if (handle.fy > 0) b = max(q.y, t + minSize)
        }
        if (aspect != null && aspect > 0f) {
            var w = r - l; var h = b - t
            when {
                handle.fx != 0 && handle.fy != 0 -> if (h * aspect < w) h = w / aspect else w = h * aspect
                handle.fx != 0 -> h = w / aspect
                else -> w = h * aspect
            }
            // Re-anchor on the fixed side (or center) of each axis.
            if (fromCenter || handle.fx == 0) { val c = (l + r) / 2f; l = c - w / 2f; r = c + w / 2f }
            else if (handle.fx < 0) l = r - w else r = l + w
            if (fromCenter || handle.fy == 0) { val c = (t + b) / 2f; t = c - h / 2f; b = c + h / 2f }
            else if (handle.fy < 0) t = b - h else b = t + h
        }
        val c = start.toDoc(Vec2((l + r) / 2f, (t + b) / 2f))
        return ShapeBox(c.x, c.y, r - l, b - t, start.rotationDeg)
    }
}
