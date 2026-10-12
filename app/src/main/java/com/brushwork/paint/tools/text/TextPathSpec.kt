package com.brushwork.paint.tools.text

import com.brushwork.paint.tools.vector.spline.NurbsGeometry
import com.brushwork.paint.vector.VSpline
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.hypot

/** Shape the text follows. */
@Serializable
enum class TextPathType(val label: String) {
    /** Normal text (no path). */
    NONE("Straight"),
    /** Along a straight line from (x1, y1) to (x2, y2). */
    LINE("Line"),
    /** Around a circle at (cx, cy) with [TextPathSpec.radius]. */
    CIRCLE("Circle"),
    /** Around a rectangle (a square when width == height) centered at (cx, cy). */
    RECT("Square / rectangle"),
    /** Along a cubic Bezier curve p0 = (x1, y1), c1 = (cx1, cy1), c2 = (cx2, cy2), p3 = (x2, y2). */
    CURVE("Curve"),
}

/** How letters follow the path. */
@Serializable
enum class TextPathMode(val label: String) {
    /** Letter outlines are deformed to follow the path (they bend with curves). */
    BEND("Bend letters"),
    /** Letters keep their shape and are rotated to the path direction. */
    ROTATE("Rotate letters"),
}

/** For closed paths (circle, rectangle): which side of the path the letters stand on. */
@Serializable
enum class TextPathSide(val label: String) {
    /** Letters stand on the outside (like text around the top of a badge). */
    OUTSIDE("Outside"),
    /** Letters hang on the inside of the shape. */
    INSIDE("Inside"),
}

@Serializable
enum class TextPathAlign(val label: String) { START("Start"), CENTER("Center"), END("End") }

/**
 * Text-on-a-path settings of a text object. All positions/lengths are DOCUMENT pixels.
 * Only the fields of the current [type] matter; the others keep their values so switching
 * types back and forth doesn't lose the user's shape.
 *
 * v1.9 (R12): [nodes], the guide as editable points, wins over the flat fields while it is set;
 * the flat fields then hold its v1.8 fallback ([withNodeFallback]).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class TextPathSpec(
    val type: TextPathType = TextPathType.NONE,
    val mode: TextPathMode = TextPathMode.BEND,
    // LINE: (x1, y1) -> (x2, y2). CURVE: p0 = (x1, y1), c1 = (cx1, cy1), c2 = (cx2, cy2), p3 = (x2, y2).
    val x1: Float = 0f,
    val y1: Float = 0f,
    val x2: Float = 0f,
    val y2: Float = 0f,
    val cx1: Float = 0f,
    val cy1: Float = 0f,
    val cx2: Float = 0f,
    val cy2: Float = 0f,
    // CIRCLE / RECT center.
    val cx: Float = 0f,
    val cy: Float = 0f,
    /** CIRCLE radius. */
    val radius: Float = 200f,
    /** CIRCLE: where the text is anchored (degrees, 0 = right, -90 = top, clockwise positive on screen). */
    val startAngleDeg: Float = -90f,
    /** RECT size and corner rounding; RECT rotation (degrees). */
    val width: Float = 400f,
    val height: Float = 400f,
    val cornerRadius: Float = 0f,
    val rotationDeg: Float = 0f,
    /** RECT: keep width == height while editing. */
    val keepSquare: Boolean = false,
    /** Closed paths: direction the text runs. */
    val clockwise: Boolean = true,
    val side: TextPathSide = TextPathSide.OUTSIDE,
    /** Where along the path the text sits, and an extra shift along the path (px). */
    val align: TextPathAlign = TextPathAlign.CENTER,
    val offset: Float = 0f,
    /** Moves the text off the path along its normal (px, positive = away from the baseline side). */
    val baselineShift: Float = 0f,
    /**
     * R12: the guide as editable nodes; order 2 = Edit as polyline, order 4 = Edit as path
     * (TextLabels19; VSplinePoint.sharp marks corners). When non-null it wins over the flat
     * fields. Never written when null (I13).
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val nodes: VSpline? = null,
) {
    val isActive: Boolean get() = type != TextPathType.NONE

    /**
     * The v1.8 fallback kept in the flat fields while [nodes] is set: fewer than 2 or more than
     * MAX_NODES points, or a non-finite coordinate, drops [nodes] (the flat fields stay as they are);
     * an order other than 2 or 4 becomes 4. Two nodes give type LINE from the first to the last point;
     * more give type CURVE with the cubic Bezier through the guide's points at 0, 1/3, 2/3 and 1 of
     * its arc length. A closed guide falls back to an open curve. Pure; idempotent.
     *
     * F1a: a straight text ([type] NONE, the guide switched off) keeps NONE and its [nodes]
     * (switching back must not lose them); its flat LINE / CURVE fields still get the fallback.
     * The nodes are stored sanitized ([VSpline.sanitized]: weights and widths in range, the open
     * ends never sharp). This instance when nothing changes.
     */
    fun withNodeFallback(): TextPathSpec {
        val n = nodes ?: return this
        if (n.points.size < 2 || n.points.size > MAX_NODES) return copy(nodes = null)
        if (n.points.any { !it.x.isFinite() || !it.y.isFinite() || abs(it.x) > VSpline.MAX_COORD || abs(it.y) > VSpline.MAX_COORD }) {
            return copy(nodes = null)
        }
        val ordered = if (n.order == 2 || n.order == 4) n else n.copy(order = 4)
        val s = ordered.sanitized()
        val first = s.points.first()
        val last = s.points.last()
        val out = if (s.points.size == 2) {
            copy(
                type = if (type == TextPathType.NONE) TextPathType.NONE else TextPathType.LINE,
                x1 = first.x, y1 = first.y, x2 = last.x, y2 = last.y,
                nodes = s,
            )
        } else {
            val c = fallbackCubic(s)
            copy(
                type = if (type == TextPathType.NONE) TextPathType.NONE else TextPathType.CURVE,
                x1 = c[0], y1 = c[1], cx1 = c[2], cy1 = c[3], cx2 = c[4], cy2 = c[5], x2 = c[6], y2 = c[7],
                nodes = s,
            )
        }
        return if (out == this) this else out
    }

    companion object {
        /** R12: the most nodes a text path holds. */
        const val MAX_NODES = 200

        /** Samples per span of the guide when its arc length is measured. */
        private const val SAMPLES_PER_SPAN = 16

        /**
         * The cubic through the points of the sanitized guide [s] at 0, 1/3, 2/3 and 1 of its arc
         * length (at its parameters 0, 1/3, 2/3 and 1): p0, c1, c2, p3 as x, y pairs. A closed guide
         * runs once around, so its first and last points meet.
         */
        private fun fallbackCubic(s: VSpline): FloatArray {
            val line = guidePolyline(s)
            val total = line.lengths[line.lengths.size - 1]
            val p0x = line.xs[0]; val p0y = line.ys[0]
            val p3x = line.xs[line.xs.size - 1]; val p3y = line.ys[line.ys.size - 1]
            val (p1x, p1y) = line.at(total / 3.0)
            val (p2x, p2y) = line.at(total * 2.0 / 3.0)
            // B(1/3) = p1 and B(2/3) = p2 solved for the two control points.
            val c1x = (-5.0 * p0x + 18.0 * p1x - 9.0 * p2x + 2.0 * p3x) / 6.0
            val c1y = (-5.0 * p0y + 18.0 * p1y - 9.0 * p2y + 2.0 * p3y) / 6.0
            val c2x = (2.0 * p0x - 9.0 * p1x + 18.0 * p2x - 5.0 * p3x) / 6.0
            val c2y = (2.0 * p0y - 9.0 * p1y + 18.0 * p2y - 5.0 * p3y) / 6.0
            return floatArrayOf(
                p0x.toFloat(), p0y.toFloat(), c1x.toFloat(), c1y.toFloat(), c2x.toFloat(), c2y.toFloat(), p3x.toFloat(), p3y.toFloat(),
            )
        }

        private class Polyline(val xs: DoubleArray, val ys: DoubleArray, val lengths: DoubleArray) {
            /** The point at arc length [d] from the start (clamped). */
            fun at(d: Double): Pair<Double, Double> {
                val last = lengths.size - 1
                if (d <= 0.0 || last == 0) return xs[0] to ys[0]
                if (d >= lengths[last]) return xs[last] to ys[last]
                var lo = 0
                var hi = last
                while (hi - lo > 1) {
                    val mid = (lo + hi) ushr 1
                    if (lengths[mid] < d) lo = mid else hi = mid
                }
                val seg = lengths[hi] - lengths[lo]
                val t = if (seg > 0.0) (d - lengths[lo]) / seg else 0.0
                return (xs[lo] + (xs[hi] - xs[lo]) * t) to (ys[lo] + (ys[hi] - ys[lo]) * t)
            }
        }

        /** The guide's curve as a fine polyline with its cumulative arc lengths (corners included, [NurbsGeometry.pieces]). */
        private fun guidePolyline(s: VSpline): Polyline {
            val xs = ArrayList<Double>()
            val ys = ArrayList<Double>()
            val dim = NurbsGeometry.DIM
            val value = DoubleArray(dim)
            val deriv = DoubleArray(dim)
            for (piece in NurbsGeometry.pieces(s)) {
                val b = NurbsGeometry.basis(piece)
                val p = b.degree
                val h = DoubleArray((p + 1) * dim)
                val scratch = DoubleArray((p + 1) * dim)
                val work = DoubleArray((p + 1) * dim)
                for (span in b.spans) {
                    NurbsGeometry.spanBezier(piece, b, span, h, scratch)
                    val from = if (xs.isEmpty()) 0 else 1
                    for (k in from..SAMPLES_PER_SPAN) {
                        NurbsGeometry.bezierAt(h, p, k.toDouble() / SAMPLES_PER_SPAN, value, deriv, work)
                        val w = value[2]
                        xs += if (w != 0.0) value[0] / w else value[0]
                        ys += if (w != 0.0) value[1] / w else value[1]
                    }
                }
            }
            if (xs.isEmpty()) {
                xs += s.points[0].x.toDouble(); ys += s.points[0].y.toDouble()
            }
            val lengths = DoubleArray(xs.size)
            for (i in 1 until xs.size) lengths[i] = lengths[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
            return Polyline(xs.toDoubleArray(), ys.toDoubleArray(), lengths)
        }
    }
}
