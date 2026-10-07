package com.brushwork.paint.vector.geom

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.vector.ShapeAnchor
import com.brushwork.paint.tools.vector.ShapeGeometry
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeOutlines
import com.brushwork.paint.tools.vector.ShapePoint
import com.brushwork.paint.tools.vector.ShapePoints
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.vector.VectorOps
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * v1.7 (design §4.5, F4; frozen): a shape mapped by an AFFINE map stays a shape, so a stretch,
 * a skew or a group scale (Transform, the gizmo, an array) never turns it into a path; only
 * homographies still do (`VectorOps.transformed`).
 *
 * - Lines and arrows: their two ends are mapped (exact for any affine).
 * - Box shapes (rectangle, ellipse, polygon, star) whose own axes stay perpendicular under the
 *   map (rotations, mirrors, scales along the shape's axes): a new rotation and axis scale. A
 *   mirror turns the shape half a turn and mirrors it about its vertical axis, about which every
 *   regular box shape is symmetric.
 * - An ellipse under any other affine: its semi-axes by the singular value decomposition.
 * - Point shapes under any affine: the points (and their tangent handles, as vectors) are mapped
 *   in document space and renormalized into the box turned like the mapped x axis
 *   ([ShapePoints.fit]); automatic tangents are affine-invariant, so the outline is exact.
 * - A rectangle, polygon or star under a skew becomes a point shape ([ShapePoints.fromRegular]).
 *
 * Widths that cannot follow a non-uniform map exactly (the stroke, the corner radius, each
 * point's own radius, a brush outline's tip) scale by sqrt|det|, as strokes and paths do.
 */
object ShapeAffine {
    /** Relative tolerance of "the shape's axes stay perpendicular". */
    private const val ORTHO_EPS = 1e-5

    /** [s] mapped by the AFFINE [m] (row-major 3×3; null when m is projective): rotation and axis scale in the shape's own frame
     *  for every type; an ellipse under any affine (SVD); point shapes under any affine (points renormalized into the new
     *  oriented box); a regular type under a skew becomes a point shape (ShapePoints.fromRegular). Stroke width × sqrt|det|. */
    fun mapped(s: ShapeObject, m: FloatArray): ShapeObject? {
        if (m.size < 9 || m.any { !it.isFinite() }) return null
        if (m[6] != 0f || m[7] != 0f || m[8] == 0f) return null
        if (!(s.cx.isFinite() && s.cy.isFinite() && s.w.isFinite() && s.h.isFinite() && s.rotation.isFinite())) return null
        val k = 1.0 / m[8]
        // x' = a·x + c·y + tx, y' = b·x + d·y + ty
        val a = m[0] * k
        val c = m[1] * k
        val tx = m[2] * k
        val b = m[3] * k
        val d = m[4] * k
        val ty = m[5] * k
        val det = a * d - b * c
        if (det == 0.0 || !det.isFinite()) return null
        val scale = sqrt(abs(det)).toFloat()
        fun mapPoint(p: Vec2): Vec2 = Vec2((a * p.x + c * p.y + tx).toFloat(), (b * p.x + d * p.y + ty).toFloat())
        fun mapVector(v: Vec2): Vec2 = Vec2((a * v.x + c * v.y).toFloat(), (b * v.x + d * v.y).toFloat())

        // The map in the shape's own frame: J = M·R(θ); its columns are the images of the shape's axes.
        val th = Math.toRadians(s.rotation.toDouble())
        val ct = cos(th)
        val st = sin(th)
        val j11 = a * ct + c * st
        val j21 = b * ct + d * st
        val j12 = -a * st + c * ct
        val j22 = -b * st + d * ct
        val len1 = hypot(j11, j21)
        val len2 = hypot(j12, j22)
        if (!(len1 > 0.0) || !(len2 > 0.0)) return null
        val axisDeg = Math.toDegrees(atan2(j21, j11))
        val center = mapPoint(Vec2(s.cx, s.cy))
        val brush = s.brushPreset?.let {
            VectorOps.turnedBrush(VectorOps.scaledBrush(it, scale), floatArrayOf(a.toFloat(), c.toFloat(), b.toFloat(), d.toFloat()))
        }
        val common = s.copy(
            strokeWidth = s.strokeWidth * scale,
            cornerRadius = s.cornerRadius * scale,
            brushPreset = brush,
        )

        if (ShapeOutlines.isCustom(s)) return pointShape(common, s, ::mapPoint, ::mapVector, axisDeg, scale)

        if (s.type.isLineLike) {
            // The ends: start/end = centre ∓ (w/2)·axis, mapped.
            val h = if (s.h == 0f) 0f else (s.h * abs(det) / len1).toFloat()
            return common.copy(
                cx = center.x, cy = center.y, w = (s.w * len1).toFloat(), h = h,
                rotation = ShapeGeometry.normalizeDegrees(axisDeg.toFloat()),
            )
        }

        val orthogonal = abs(j11 * j12 + j21 * j22) <= ORTHO_EPS * len1 * len2
        if (orthogonal) {
            // J = R(φ)·diag(len1, sy) with sy = ±len2 (negative for a mirror).
            val mirror = det < 0.0
            val rot = if (mirror) axisDeg + 180.0 else axisDeg
            // A mirror: R(φ)·diag(len1, −len2) = R(φ + 180°)·diag(−len1, len2), i.e. the shape mirrored about its vertical axis.
            return common.copy(
                cx = center.x, cy = center.y, w = (s.w * len1).toFloat(), h = (s.h * len2).toFloat(),
                rotation = ShapeGeometry.normalizeDegrees(rot.toFloat()),
            )
        }

        if (s.type == ShapeType.ELLIPSE) {
            // E = J·diag(rx, ry) = R(α)·diag(σ1, σ2)·R(β): the image of the unit circle is R(α)·diag(σ1, |σ2|)·circle.
            val rx = s.w / 2.0
            val ry = s.h / 2.0
            val e11 = j11 * rx
            val e12 = j12 * ry
            val e21 = j21 * rx
            val e22 = j22 * ry
            val ee = (e11 + e22) / 2.0
            val f = (e11 - e22) / 2.0
            val g = (e21 + e12) / 2.0
            val hh = (e21 - e12) / 2.0
            val q = hypot(ee, hh)
            val r = hypot(f, g)
            val s1 = q + r
            val s2 = abs(q - r)
            val alpha = (atan2(g, f) + atan2(hh, ee)) / 2.0
            return common.copy(
                cx = center.x, cy = center.y, w = (2.0 * s1).toFloat(), h = (2.0 * s2).toFloat(),
                rotation = ShapeGeometry.normalizeDegrees(Math.toDegrees(alpha).toFloat()),
            )
        }

        // A rectangle, polygon or star under a skew: its own points, mapped.
        val regular = s.copy(points = ShapePoints.fromRegular(s.type, s.outlineParams))
        return pointShape(common, regular, ::mapPoint, ::mapVector, axisDeg, scale)
    }

    /**
     * [points] with each point's own corner radius (document px, v1.7 item 2) scaled by [k]; the
     * same instance when no point has one (v1.6 content is untouched).
     */
    internal fun scaledRadii(points: List<ShapePoint>?, k: Float): List<ShapePoint>? {
        if (points == null || k == 1f || points.none { it.radius != null }) return points
        return points.map { p -> p.radius?.let { p.copy(radius = it * k) } ?: p }
    }

    /** [s]'s points (custom) mapped in document space and renormalized into a box turned by [axisDeg]; [common] carries the widths. */
    private fun pointShape(
        common: ShapeObject,
        s: ShapeObject,
        mapPoint: (Vec2) -> Vec2,
        mapVector: (Vec2) -> Vec2,
        axisDeg: Double,
        scale: Float,
    ): ShapeObject? {
        val pts = s.points ?: return null
        val anchors = ShapePoints.docAnchors(s.box, pts).map { an ->
            ShapeAnchor(
                mapPoint(an.pos), an.smooth, an.handleIn?.let(mapVector), an.handleOut?.let(mapVector),
                an.radius?.let { it * scale },
            )
        }
        if (anchors.any { !it.pos.x.isFinite() || !it.pos.y.isFinite() }) return null
        val (box, normalized) = ShapePoints.fit(ShapeGeometry.normalizeDegrees(axisDeg.toFloat()), anchors, s.closed)
        if (!(box.cx.isFinite() && box.cy.isFinite() && box.w.isFinite() && box.h.isFinite())) return null
        return common.copy(cx = box.cx, cy = box.cy, w = box.w, h = box.h, rotation = box.rotationDeg, points = normalized)
    }
}
