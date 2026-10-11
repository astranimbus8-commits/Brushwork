package com.brushwork.paint.tools.vector

import com.brushwork.paint.core.Vec2
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.max

/**
 * v1.8 (item 7, design §3.8 (c), §4.7): the cog's own geometry, its teeth and its hole as closed
 * contours.
 *
 * The cog is built on the unit circle, then scaled into the box: x by w/2, y by h/2, so the tips
 * reach the box's edges (the outer radius R fills the box). With [CogParams] clamped to their
 * ranges ([clamped]):
 * - pitch p = 2π/N; a tooth's base b = toothWidth·p, its tip b·(1 - taper); the root radius
 *   r = R·(1 - depth);
 * - tooth k is centred at -90° + k·p (the first one at the top, then clockwise on the screen) and
 *   has 4 vertices: root start, tip start, tip end, root end;
 * - the root arc between two teeth gets a vertex every ≤ [MAX_ROOT_STEP_DEG] degrees, so the
 *   outer contour has N·(3 + m) vertices, m = ceil(gap / 20°): at most [MAX_VERTICES];
 * - the hole ([CogParams.hole] > 0) is a circle of radius hole·r with [HOLE_VERTICES] vertices,
 *   a second contour going the other way round (counter-clockwise on the screen), so it is a hole
 *   under both the non-zero and the even-odd rule.
 *
 * [outer] is what `ShapeGeometry.vertices(COG)` returns (Points mode and the v1.7 fallback points
 * start from it). The corners (the shape's Corners and corner radius) treat every outer vertex
 * alike, as `ShapePoints.outline` treats the points made from it, so turning a cog into points
 * never moves its outline. The hole has no corners. Pure and thread-safe.
 */
object CogGeometry {
    /** The largest angle between two vertices of a root arc. */
    const val MAX_ROOT_STEP_DEG = 20.0

    /** The hole's vertices. */
    const val HOLE_VERTICES = 48

    /** The most vertices the outer contour can have (design §3.8: "at most 320"). */
    const val MAX_VERTICES = 320

    /** [c] with every value in its range ([CogParams]' constants); a non-finite one takes its default. */
    fun clamped(c: CogParams): CogParams {
        val d = CogParams.DEFAULT
        fun Float.or(v: Float) = if (isFinite()) this else v
        val n = CogParams(
            teeth = c.teeth.coerceIn(CogParams.MIN_TEETH, CogParams.MAX_TEETH),
            depth = c.depth.or(d.depth).coerceIn(CogParams.MIN_DEPTH, CogParams.MAX_DEPTH),
            toothWidth = c.toothWidth.or(d.toothWidth).coerceIn(CogParams.MIN_TOOTH_WIDTH, CogParams.MAX_TOOTH_WIDTH),
            taper = c.taper.or(d.taper).coerceIn(0f, CogParams.MAX_TAPER),
            hole = c.hole.or(d.hole).coerceIn(0f, CogParams.MAX_HOLE),
        )
        return if (n == c) c else n
    }

    /** Vertices of each root arc's run (the gap between two teeth split into steps of ≤ 20°): m. */
    fun rootSteps(c: CogParams): Int {
        val k = clamped(c)
        val gap = 2 * PI / k.teeth * (1.0 - k.toothWidth)
        return max(1, ceil(gap / Math.toRadians(MAX_ROOT_STEP_DEG) - 1e-9).toInt())
    }

    /** The outer contour's vertex count for [c]: N·(3 + [rootSteps]). */
    fun vertexCount(c: CogParams): Int = clamped(c).teeth * (3 + rootSteps(c))

    /** The outer contour on the unit circle (tips at radius 1), clockwise on the screen (y down). */
    fun unitOuter(c: CogParams): List<Vec2> {
        val k = clamped(c)
        val n = k.teeth
        val pitch = 2 * PI / n
        val base = k.toothWidth * pitch
        val tip = base * (1.0 - k.taper)
        val root = 1f - k.depth
        val gap = pitch - base
        val m = rootSteps(k)
        val out = ArrayList<Vec2>(n * (3 + m))
        for (t in 0 until n) {
            val a = -PI / 2 + t * pitch
            out += polar(root, a - base / 2)
            out += polar(1f, a - tip / 2)
            out += polar(1f, a + tip / 2)
            out += polar(root, a + base / 2)
            for (j in 1 until m) out += polar(root, a + base / 2 + j * gap / m)
        }
        return out
    }

    /** The hole on the unit circle (radius hole·r), counter-clockwise on the screen; empty without a hole. */
    fun unitHole(c: CogParams): List<Vec2> {
        val k = clamped(c)
        if (k.hole <= 0f) return emptyList()
        val radius = k.hole * (1f - k.depth)
        return List(HOLE_VERTICES) { j -> polar(radius, -PI / 2 - j * 2 * PI / HOLE_VERTICES) }
    }

    /** Box-local outer contour of a [w] x [h] cog (what `ShapeGeometry.vertices(COG)` returns). */
    fun outer(w: Float, h: Float, c: CogParams): List<Vec2> = scaled(unitOuter(c), w, h)

    /** Box-local hole of a [w] x [h] cog; empty without a hole. */
    fun hole(w: Float, h: Float, c: CogParams): List<Vec2> = scaled(unitHole(c), w, h)

    /** True when [c] has a hole (the outline is then two contours). */
    fun hasHole(c: CogParams): Boolean = clamped(c).hole > 0f

    /**
     * The box-local outline of a [w] x [h] cog for [p]: the outer contour with the shape's corners
     * ([OutlineParams.corner], [OutlineParams.cornerRadius]), then the hole when there is one.
     */
    fun local(w: Float, h: Float, p: OutlineParams): VectorPath {
        val outerPath = ShapeGeometry.cornerPath(outer(w, h, p.cog), p.corner, p.cornerRadius)
        val holePts = hole(w, h, p.cog)
        if (holePts.isEmpty()) return outerPath
        return VectorPath(outerPath.ops + VectorPath.polygon(holePts).ops)
    }

    /**
     * The cog's contours in [box] (document px) for [p] ([OutlineParams.cog] sets the teeth): the
     * toothed outline, then the hole (when [CogParams.hole] > 0) as a second contour.
     */
    fun contours(box: ShapeBox, p: OutlineParams): VectorPath = local(box.w, box.h, p).transformed { box.toDoc(it) }

    private fun polar(r: Float, a: Double) = Vec2((r * kotlin.math.cos(a)).toFloat(), (r * kotlin.math.sin(a)).toFloat())

    private fun scaled(unit: List<Vec2>, w: Float, h: Float): List<Vec2> {
        val sx = w / 2f
        val sy = h / 2f
        return unit.map { Vec2(it.x * sx, it.y * sy) }
    }
}
