package com.brushwork.paint.tools.vector

import com.brushwork.paint.vector.geom.ShapeAffine

/**
 * v1.7 (item 11, §3.11; area C): the Transform tool's data lift of a shape layer. Move, rotate,
 * corners, sides and flips map the shape exactly through `ShapeAffine`; a skew gives a
 * custom-points shape, still a shape.
 *
 * The widths stay in document px, as when the shape is resized in the Shape tool: the stroke
 * width, the corner radius, each point's own radius and the brush the outline is painted with are
 * the shape's own, whatever the map's scale (`ShapeAffine` alone would scale them by sqrt|det|).
 */
object ShapeTransforms {
    /**
     * The shape layer data [shapeData] (ShapeCodec JSON) mapped by [m] (a row-major 3 × 3 affine
     * matrix in document px), or null when it can't be: unreadable data, a projective or
     * degenerate map.
     */
    fun mapped(shapeData: String, m: FloatArray): String? {
        val s = ShapeCodec.decode(shapeData) ?: return null
        val t = ShapeAffine.mapped(s, m) ?: return null
        return ShapeCodec.encode(keepingWidths(s, t))
    }

    /** [t] (mapped from [s]) with [s]'s stroke width, corner radius, brush and point radii. */
    internal fun keepingWidths(s: ShapeObject, t: ShapeObject): ShapeObject {
        val from = s.points
        val to = t.points
        val points = if (to != null && from != null && from.size == to.size && from.any { it.radius != null }) {
            to.mapIndexed { i, p -> p.copy(radius = from[i].radius) }
        } else {
            to
        }
        return t.copy(strokeWidth = s.strokeWidth, cornerRadius = s.cornerRadius, brushPreset = s.brushPreset, points = points)
    }
}
