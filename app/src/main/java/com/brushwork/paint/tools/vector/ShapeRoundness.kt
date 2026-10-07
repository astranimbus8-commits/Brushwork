package com.brushwork.paint.tools.vector

import com.brushwork.paint.tools.points.MixedEdit

/**
 * v1.7 (item 2, design §3.2): "Point roundness" of the selected points of a custom Shape outline
 * (pure Kotlin; the Shape tool applies each result as ONE in-tool step).
 *
 * A point's roundness is its own [ShapeAnchor.radius] (document px), else the shape's "Corner
 * radius" when the shape's corners are treated, else 0 (see [ShapePoints.cornerStyleOf] and
 * [ShapePoints.cornerRadiusOf] for how the outline draws it). Only corners between two straight
 * sides can be rounded ([ShapePoints.roundable]); the others are left as they are.
 */
object ShapeRoundness {
    /** The largest roundness the field sets (document px). */
    const val MAX = 500f

    /** The points among [indices] that can be rounded (ascending, in range, each once). */
    fun targets(a: List<ShapeAnchor>, closed: Boolean, indices: IntArray): IntArray {
        val ok = ShapePoints.roundable(a, closed)
        return indices.filter { it in a.indices && ok[it] }.distinct().sorted().toIntArray()
    }

    /** The roundness of each point of [targets] (see the class docs) on a shape whose corners are [corner] at [radius]. */
    fun values(a: List<ShapeAnchor>, targets: IntArray, corner: CornerStyle, radius: Float): FloatArray =
        FloatArray(targets.size) { k ->
            val p = a[targets[k]]
            p.radius?.takeIf { it.isFinite() } ?: if (corner == CornerStyle.SHARP) 0f else radius
        }

    /** [a] with point `targets[k]` at roundness `values[k]` (its own radius from now on). */
    fun withValues(a: List<ShapeAnchor>, targets: IntArray, values: FloatArray): List<ShapeAnchor> {
        val out = a.toMutableList()
        for (k in targets.indices) {
            val i = targets[k]
            val v = values.getOrNull(k)?.takeIf { it.isFinite() } ?: continue
            out[i] = out[i].copy(radius = v.coerceIn(0f, MAX))
        }
        return out
    }

    /**
     * A typed value for the points [targets]: an absolute expression sets all, a relative one
     * (`*2`, `/2`) applies to each ([MixedEdit.typed]). Null when the text is invalid.
     */
    fun typed(a: List<ShapeAnchor>, targets: IntArray, corner: CornerStyle, radius: Float, text: String): List<ShapeAnchor>? {
        if (targets.isEmpty()) return null
        val next = MixedEdit.typed(values(a, targets, corner, radius), text, 0f, MAX) ?: return null
        return withValues(a, targets, next)
    }

    /** A scrub of the field by [delta] px from the values in [base] (each value + delta, clamped). */
    fun shifted(base: List<ShapeAnchor>, targets: IntArray, corner: CornerStyle, radius: Float, delta: Float): List<ShapeAnchor> =
        withValues(base, targets, MixedEdit.shifted(values(base, targets, corner, radius), delta, 0f, MAX))

    /** "Reset point roundness": the points [targets] follow the shape's own "Corner radius" again (null). */
    fun reset(a: List<ShapeAnchor>, targets: IntArray): List<ShapeAnchor> {
        val out = a.toMutableList()
        for (i in targets) if (i in out.indices) out[i] = out[i].copy(radius = null)
        return out
    }
}
