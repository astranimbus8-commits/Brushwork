package com.brushwork.paint.tools.vector

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.spline.SplineBezier
import com.brushwork.paint.vector.VFillRule
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VSpline
import com.brushwork.paint.vector.VSplinePoint
import com.brushwork.paint.vector.VStrokeKind
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VectorOps
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * v1.7 (item 6, §3.6; area C): what [ShapeToSpline.convert] gives: the shape as a Path-tool
 * path ([path], with its spline) and the indices of its control points that stand for the
 * shape's selected corners ([selectedSplineIndices]), for `CurveTool.openPath` to select.
 */
class ConvertResult(val path: VPath, val selectedSplineIndices: IntArray)

/**
 * v1.7 (item 6, §3.6; area C): a shape turned into an editable Path-tool path, exactly: its
 * corners become sharp control points, its curved segments clamped pieces, its rounded corners
 * rational arcs, and its selected corners editable NURBS arcs; its look is carried over.
 *
 * The spline (order 4, Endpoint on, cyclic when the shape is closed, every width 1) is built in
 * document px from the shape's points (a regular shape's from [ShapePoints.fromRegular]):
 * - an unselected vertex is a SHARP control point, so a straight side between two of them is a
 *   2-point piece of order 2 (`NurbsGeometry.pieces`): an exact line;
 * - a curved segment P → Q with handles H1, H2 adds H1 and H2 between its two ends: a clamped
 *   4-point piece of order 4, exactly that cubic;
 * - a corner the outline rounds today (the shape's style or the point's own radius, with the
 *   outline's own cut, [ShapeGeometry.corner]) becomes a (sharp), c, b (sharp) with c the
 *   tangent intersection weighted cos(φ / 2), φ the arc's sweep: a rational quadratic, an exact
 *   circular arc (an inverted corner's arc is centred on the vertex). A bevel is a, b. An arc
 *   whose weight would fall below [MIN_ARC_WEIGHT] (a very pointed corner) is split into equal
 *   arcs joined at sharp points, so no weight is ever clamped by [VSpline.sanitized];
 * - a SELECTED corner between two straight sides becomes such an arc at its own cut (a bevel's
 *   cut becomes a round arc), else at [SELECTED_CUT] of its shorter side; its c is what
 *   [ConvertResult.selectedSplineIndices] names, for the user to drag and weight;
 * - a selected vertex next to a curved segment becomes a smooth (non-sharp) control point.
 *
 * Points in the middle of a straight run (left out of a treated outline, [ShapePoints.outline])
 * stay as sharp control points on the run, unless a corner's cut covers them. So the curve of
 * the spline is the shape's outline (within float precision) everywhere except at the selected
 * corners; [VPath.subpaths] holds its Bézier form ([SplineBezier.toSubpath], I9), which is within
 * [SplineBezier.DEFAULT_TOLERANCE] of the exact arcs. O(n) in the shape's points.
 */
object ShapeToSpline {
    /** A selected sharp corner is cut back by this fraction of its shorter side (§3.6). */
    const val SELECTED_CUT = 0.25f

    /** Smallest weight of a converted arc; a wider arc is split ([VSpline.MIN_WEIGHT] is 0.1). */
    const val MIN_ARC_WEIGHT = 0.2

    /** Two sharp control points closer than this (document px) are one. */
    private const val SAME = 1e-4f

    /**
     * [shape] as a path whose corners [selected] (indices into its points) became editable
     * curves, or null when it can't be converted (an arrow, or an outline without length).
     */
    fun convert(shape: ShapeObject, selected: IntArray): ConvertResult? {
        if (shape.type == ShapeType.ARROW) return null
        val o = shape.sanitized() ?: return null
        val built = controlPoints(o, selected) ?: return null
        val spline = VSpline(built.points, order = VSpline.DEFAULT_ORDER, endpoint = true, cyclic = o.closed).sanitized()
        val path = VPath(
            id = 0L,
            subpaths = listOf(SplineBezier.toSubpath(spline)),
            tension = 0f,
            polyline = false,
            fillRule = VFillRule.NONZERO,
            fill = if (!o.type.isLineLike && o.style.fill) VPaint.Solid(o.fillColor) else null,
            stroke = strokeOf(o),
            spline = spline,
        )
        return ConvertResult(path, built.selected)
    }

    /** The outline style of [o] as a path's (§3.6 (b) "Style"). */
    internal fun strokeOf(o: ShapeObject): VStrokeStyle? = when {
        !o.strokes -> null
        o.paintsWithBrush -> VStrokeStyle(
            kind = VStrokeKind.BRUSH, color = o.strokeColor, width = o.strokeWidth, cap = o.lineCap,
            brushTool = o.brushToolId ?: ToolId.BRUSH, brush = o.brushPreset ?: VectorOps.brushPresetOf(o),
        )
        else -> VStrokeStyle(
            kind = VStrokeKind.PLAIN, color = o.strokeColor, width = o.strokeWidth, cap = o.lineCap,
            join = ShapeOutlines.join(o), miter = MITER_LIMIT,
        )
    }

    private class Built(val points: List<VSplinePoint>, val selected: IntArray)

    /** How a corner is drawn in the path: its cut points and the arc between them. */
    private class Treated(val c: ShapeGeometry.Corner, val style: CornerStyle)

    /** The control points of [o]'s spline and the indices standing for the [selected] points. */
    private fun controlPoints(o: ShapeObject, selected: IntArray): Built? {
        val pts = if (ShapeOutlines.isCustom(o)) o.points ?: return null else ShapePoints.fromRegular(o.type, o.outlineParams)
        val a = ShapePoints.docAnchors(o.box, pts)
        val n = a.size
        val closed = o.closed
        if (n < ShapePoints.minPoints(closed)) return null
        val corner = if (o.type.hasCorners) o.corner else CornerStyle.SHARP
        val isSel = BooleanArray(n)
        for (i in selected) if (i in 0 until n) isSel[i] = true
        val segCount = ShapePoints.segmentCount(n, closed)
        val segs = List(segCount) { ShapePoints.segment(a, it, closed) }
        val straight = BooleanArray(segCount) { ShapePoints.isStraight(segs[it]) }
        val verts = a.map { it.pos }
        fun straightIn(i: Int) = if (closed) straight[(i - 1 + n) % n] else i > 0 && straight[i - 1]
        fun straightOut(i: Int) = if (closed) straight[i] else i < n - 1 && straight[i]
        fun prevOf(i: Int) = if (closed) (i - 1 + n) % n else i - 1
        fun nextOf(i: Int) = if (closed) (i + 1) % n else i + 1

        // Points in the middle of a straight run (closed outlines only, as ShapePoints.outline).
        val through = BooleanArray(n) { i ->
            closed && straightIn(i) && straightOut(i) && ShapePoints.passesThrough(verts[prevOf(i)], verts[i], verts[nextOf(i)])
        }
        val keepCount = through.count { !it }
        val styles = List(n) { ShapePoints.cornerStyleOf(a[it], corner) }
        val radii = FloatArray(n) { ShapePoints.cornerRadiusOf(a[it], o.cornerRadius) }
        // The outline treats its corners (else it is drawn through every point as it is).
        val treats = closed && keepCount >= ShapePoints.MIN_CLOSED && (0 until n).any { styles[it] != CornerStyle.SHARP && radii[it] > 0f }
        // The corner neighbours: the next points that are not in the middle of a run.
        val prevKeep = IntArray(n) { i -> var k = prevOf(i); var guard = 0; while (k >= 0 && through[k] && guard++ < n) k = prevOf(k); k }
        val nextKeep = IntArray(n) { i -> var k = nextOf(i); var guard = 0; while (k in 0 until n && through[k] && guard++ < n) k = nextOf(k); k }

        // Each corner's treatment in the path (null: a plain control point).
        val treated = arrayOfNulls<Treated>(n)
        for (i in 0 until n) {
            if (through[i] || !straightIn(i) || !straightOut(i)) continue
            if (closed && keepCount < ShapePoints.MIN_CLOSED) continue
            val pk = prevKeep[i]; val nk = nextKeep[i]
            if (pk !in 0 until n || nk !in 0 until n) continue
            val v = verts[i]; val prev = verts[pk]; val next = verts[nk]
            val half = min(v.distanceTo(prev), v.distanceTo(next)) / 2f
            val drawn = if (treats && styles[i] != CornerStyle.SHARP && radii[i] > 0f) {
                ShapeGeometry.corner(v, prev, next, min(radii[i], half))?.let { Treated(it, styles[i]) }
            } else null
            treated[i] = when {
                !isSel[i] -> drawn
                // A selected corner keeps its arc (a bevel's cut becomes a round one) ...
                drawn != null -> if (drawn.style == CornerStyle.BEVEL) Treated(drawn.c, CornerStyle.ROUND) else drawn
                // ... and a sharp one gets one at a quarter of its shorter side.
                else -> ShapeGeometry.corner(v, prev, next, SELECTED_CUT * 2f * half)?.let { Treated(it, CornerStyle.ROUND) }
            }
        }

        val b = Builder()
        for (i in 0 until n) {
            val t = treated[i]
            when {
                through[i] -> {
                    val p = onRun(i, verts, prevKeep[i], nextKeep[i], treated, project = treats)
                    if (p != null) b.add(p, sharp = true, selected = isSel[i])
                }
                t == null -> {
                    val curvedNeighbour = !straightIn(i) && (closed || i > 0) || !straightOut(i) && (closed || i < n - 1)
                    b.add(verts[i], sharp = !(isSel[i] && curvedNeighbour), selected = isSel[i])
                }
                t.style == CornerStyle.BEVEL -> {
                    b.add(t.c.a, sharp = true)
                    b.add(t.c.b, sharp = true)
                }
                else -> arc(t, b, isSel[i])
            }
            if (i < segCount && !straight[i]) {
                val seg = segs[i]
                b.add(seg[1], sharp = false)
                b.add(seg[2], sharp = false)
            }
        }
        return b.finish(closed)
    }

    /**
     * Middle-of-run point [i] where the path draws it: on the run from its corner [pk] to [nk]
     * (projected onto it when the outline goes corner to corner, [project]), or null when a
     * corner's cut covers it.
     */
    private fun onRun(i: Int, verts: List<Vec2>, pk: Int, nk: Int, treated: Array<Treated?>, project: Boolean): Vec2? {
        val n = verts.size
        if (pk !in 0 until n || nk !in 0 until n) return verts[i]
        val from = verts[pk]
        val d = verts[nk] - from
        val len = d.length
        if (len < 1e-6f) return null
        val dir = d / len
        fun u(p: Vec2) = (p - from).dot(dir)
        val start = treated[pk]?.c?.b?.let { u(it) } ?: 0f
        val end = treated[nk]?.c?.a?.let { u(it) } ?: len
        val ui = u(verts[i])
        if (ui <= start + SAME || ui >= end - SAME) return null
        return if (project) from + dir * ui else verts[i]
    }

    /**
     * The arc of treated corner [t] (ROUND or INVERTED) from its cut point a to b: a rational
     * quadratic per part, its middle control point at the tangent intersection with weight
     * cos(sweep / 2). Parts are equal and joined at sharp points on the arc; [selected] marks
     * every control point between a and b.
     */
    private fun arc(t: Treated, out: Builder, selected: Boolean) {
        val c = t.c
        val px = c.p.x.toDouble(); val py = c.p.y.toDouble()
        val ox: Double; val oy: Double; val r: Double
        if (t.style == CornerStyle.INVERTED) {
            ox = px; oy = py; r = c.d.toDouble()
        } else {
            val bis = (c.uA + c.uB).normalized()
            val half = c.theta / 2.0
            val k = c.d / cos(half)
            ox = px + bis.x * k; oy = py + bis.y * k; r = c.d * tan(half)
        }
        val a0 = atan2(c.a.y - oy, c.a.x - ox)
        val a1 = atan2(c.b.y - oy, c.b.x - ox)
        var sweep = a1 - a0
        while (sweep > Math.PI) sweep -= 2 * Math.PI
        while (sweep < -Math.PI) sweep += 2 * Math.PI
        var parts = 1
        while (cos(abs(sweep) / (2 * parts)) < MIN_ARC_WEIGHT && parts < 8) parts++
        val s = sweep / parts
        val w = cos(s / 2)
        out.add(c.a, sharp = true)
        for (j in 0 until parts) {
            val mid = a0 + (j + 0.5) * s
            val m = if (parts == 1 && t.style == CornerStyle.ROUND) c.p
            else Vec2((ox + r / w * cos(mid)).toFloat(), (oy + r / w * sin(mid)).toFloat())
            out.add(m, sharp = false, weight = w.toFloat(), selected = selected)
            if (j < parts - 1) {
                val e = a0 + (j + 1) * s
                out.add(Vec2((ox + r * cos(e)).toFloat(), (oy + r * sin(e)).toFloat()), sharp = true, selected = selected)
            }
        }
        out.add(c.b, sharp = true)
    }

    /** Collects the control points; two sharp points on top of each other are one. */
    private class Builder {
        val points = ArrayList<VSplinePoint>()
        val selected = ArrayList<Int>()

        fun add(p: Vec2, sharp: Boolean, weight: Float = 1f, selected: Boolean = false) {
            val last = points.lastOrNull()
            if (sharp && last != null && last.sharp && last.weight == 1f && weight == 1f && near(last, p)) {
                if (selected) this.selected += points.lastIndex
                return
            }
            points += VSplinePoint(p.x, p.y, weight = weight, width = 1f, sharp = sharp)
            if (selected) this.selected += points.lastIndex
        }

        fun finish(closed: Boolean): Built? {
            if (closed && points.size >= 2) {
                val first = points[0]
                val last = points[points.lastIndex]
                if (first.sharp && last.sharp && first.weight == 1f && last.weight == 1f && near(first, Vec2(last.x, last.y))) {
                    val li = points.lastIndex
                    points.removeAt(li)
                    for (k in selected.indices) if (selected[k] == li) selected[k] = 0
                }
            }
            if (points.size < (if (closed) 3 else 2) || points.size > VSpline.MAX_POINTS) return null
            if (!closed) {
                // The two ends of an open spline are never corners (VSpline.sanitized).
                points[0] = points[0].copy(sharp = false)
                points[points.lastIndex] = points[points.lastIndex].copy(sharp = false)
            }
            return Built(points, selected.distinct().sorted().toIntArray())
        }

        private fun near(q: VSplinePoint, p: Vec2) = abs(q.x - p.x) <= SAME && abs(q.y - p.y) <= SAME
    }
}
