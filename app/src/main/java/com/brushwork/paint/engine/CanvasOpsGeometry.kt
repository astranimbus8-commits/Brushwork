package com.brushwork.paint.engine

import com.brushwork.paint.model.GridSettings
import com.brushwork.paint.model.RulerSettings
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Whole-canvas rotations (the document's width and height swap for quarter turns). */
enum class CanvasRotation(val label: String, val quarterTurnsCw: Int) {
    CW_90("Rotate 90° clockwise", 1),
    CCW_90("Rotate 90° counter-clockwise", 3),
    R_180("Rotate 180°", 2),
}

/**
 * Affine map from OLD document coordinates to NEW document coordinates produced by a canvas
 * operation: `x' = a·x + b·y + tx`, `y' = c·x + d·y + ty`. Used to carry the ruler and grid
 * along with the artwork. Pure Kotlin (JVM-testable).
 */
data class CanvasGeometry(
    val a: Double = 1.0,
    val b: Double = 0.0,
    val c: Double = 0.0,
    val d: Double = 1.0,
    val tx: Double = 0.0,
    val ty: Double = 0.0,
) {
    val isIdentity: Boolean get() = this == IDENTITY

    val determinant: Double get() = a * d - b * c

    /** Uniform length scale (square root of the area scale). */
    val lengthScale: Double get() = sqrt(abs(determinant))

    fun mapX(x: Double, y: Double): Double = a * x + b * y + tx
    fun mapY(x: Double, y: Double): Double = c * x + d * y + ty

    fun inverse(): CanvasGeometry {
        val det = determinant
        require(det != 0.0) { "Singular canvas geometry" }
        val ia = d / det
        val ib = -b / det
        val ic = -c / det
        val id = a / det
        return CanvasGeometry(ia, ib, ic, id, -(ia * tx + ib * ty), -(ic * tx + id * ty))
    }

    /** Direction [deg] (degrees, y down) mapped through the linear part, in [-180, 180). */
    fun mapAngleDeg(deg: Float): Float {
        val r = Math.toRadians(deg.toDouble())
        val dx = cos(r); val dy = sin(r)
        return normalizeAngle(Math.toDegrees(atan2(c * dx + d * dy, a * dx + b * dy)).toFloat())
    }

    /** How much a length along direction [deg] is stretched. */
    fun stretchAlong(deg: Float): Double {
        val r = Math.toRadians(deg.toDouble())
        val dx = cos(r); val dy = sin(r)
        return hypot(a * dx + b * dy, c * dx + d * dy)
    }

    /**
     * Ruler positions follow the artwork; the center is kept inside the new canvas
     * ([newWidth] x [newHeight]) like the controller does after geometry changes.
     */
    fun mapRuler(r: RulerSettings, newWidth: Int, newHeight: Int): RulerSettings {
        if (r.centerX < 0f || r.centerY < 0f) return r
        if (isIdentity) {
            // Same coordinates (e.g. a canvas cropped at the top-left): only keep it on the canvas.
            return r.copy(centerX = r.centerX.coerceIn(0f, newWidth.toFloat()), centerY = r.centerY.coerceIn(0f, newHeight.toFloat()))
        }
        val cx = mapX(r.centerX.toDouble(), r.centerY.toDouble())
        val cy = mapY(r.centerX.toDouble(), r.centerY.toDouble())
        return r.copy(
            centerX = cx.toFloat().coerceIn(0f, newWidth.toFloat()),
            centerY = cy.toFloat().coerceIn(0f, newHeight.toFloat()),
            angleDeg = mapAngleDeg(r.angleDeg),
            radius = (r.radius * lengthScale).toFloat().coerceAtLeast(1f),
            radiusX = (r.radiusX * stretchAlong(r.angleDeg)).toFloat().coerceAtLeast(1f),
            radiusY = (r.radiusY * stretchAlong(r.angleDeg + 90f)).toFloat().coerceAtLeast(1f),
        )
    }

    /** Grid spacing scales with the artwork; the offset follows one lattice point. */
    fun mapGrid(g: GridSettings): GridSettings {
        if (isIdentity) return g
        val spacing = (g.spacingPx * lengthScale).toFloat().coerceAtLeast(1f)
        val ox = mapX(g.offsetXPx.toDouble(), g.offsetYPx.toDouble()).toFloat()
        val oy = mapY(g.offsetXPx.toDouble(), g.offsetYPx.toDouble()).toFloat()
        return g.copy(spacingPx = spacing, offsetXPx = positiveMod(ox, spacing), offsetYPx = positiveMod(oy, spacing))
    }

    companion object {
        val IDENTITY = CanvasGeometry()

        fun scale(sx: Double, sy: Double) = CanvasGeometry(a = sx, d = sy)

        fun translate(dx: Double, dy: Double) = CanvasGeometry(tx = dx, ty = dy)

        /** Rotation of a [width] x [height] canvas; the result's origin is its top-left corner. */
        fun rotate(rotation: CanvasRotation, width: Int, height: Int): CanvasGeometry = when (rotation) {
            // (x, y) -> (H - y, x)
            CanvasRotation.CW_90 -> CanvasGeometry(a = 0.0, b = -1.0, c = 1.0, d = 0.0, tx = height.toDouble(), ty = 0.0)
            // (x, y) -> (y, W - x)
            CanvasRotation.CCW_90 -> CanvasGeometry(a = 0.0, b = 1.0, c = -1.0, d = 0.0, tx = 0.0, ty = width.toDouble())
            // (x, y) -> (W - x, H - y)
            CanvasRotation.R_180 -> CanvasGeometry(a = -1.0, d = -1.0, tx = width.toDouble(), ty = height.toDouble())
        }

        fun flip(horizontal: Boolean, width: Int, height: Int): CanvasGeometry =
            if (horizontal) CanvasGeometry(a = -1.0, tx = width.toDouble()) else CanvasGeometry(d = -1.0, ty = height.toDouble())

        fun normalizeAngle(deg: Float): Float {
            var a = deg % 360f
            if (a < -180f) a += 360f
            if (a >= 180f) a -= 360f
            return a
        }

        private fun positiveMod(v: Float, m: Float): Float {
            if (m <= 0f) return v
            val r = v % m
            return if (r < 0f) r + m else r
        }

        /**
         * Where the old image's left (or top) edge lands when a side of length [oldSize] becomes
         * [newSize] with anchor 0 (start), 1 (center) or 2 (end). Negative = cropped away.
         */
        fun anchorOffset(oldSize: Int, newSize: Int, anchor: Int): Int =
            Math.floorDiv((newSize - oldSize) * anchor.coerceIn(0, 2), 2)
    }
}
