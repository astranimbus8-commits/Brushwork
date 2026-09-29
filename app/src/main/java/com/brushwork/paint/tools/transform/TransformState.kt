package com.brushwork.paint.tools.transform

import com.brushwork.paint.core.Vec2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Axis-aligned box in document pixels. */
data class DocBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * Where a floating bitmap of [srcW] x [srcH] pixels lands in the document. Pure Kotlin (no
 * android.graphics) so all handle math is unit-testable.
 *
 * A normalized source point (u, v) (0..1 across the bitmap) maps to
 * `center + R(rotationDeg) * (sx * (u - 0.5) * srcW, sy * (v - 0.5) * srcH)`.
 * Negative scales mirror. [distortion], when set, replaces the normalized positions of the four
 * source corners (TL, TR, BR, BL) so the final quad can be any convex shape (perspective);
 * the affine part still applies on top of it, so move/rotate/scale/flip keep working after a
 * distort.
 */
data class TransformState(
    val srcW: Int,
    val srcH: Int,
    val cx: Float,
    val cy: Float,
    val sx: Float = 1f,
    val sy: Float = 1f,
    /** Rotation in degrees, normalized to (-180, 180]. Positive = clockwise on screen (y down). */
    val rotationDeg: Float = 0f,
    val distortion: List<Vec2>? = null,
) {
    init {
        require(srcW > 0 && srcH > 0) { "Empty transform source ${srcW}x$srcH" }
    }

    /** Width of the undistorted box in document pixels. */
    val width: Float get() = abs(sx) * srcW

    /** Height of the undistorted box in document pixels. */
    val height: Float get() = abs(sy) * srcH

    /** Overall scale in percent (geometric mean of both axes). */
    val scalePercent: Float get() = sqrt(abs(sx * sy)) * 100f

    val isDistorted: Boolean get() = distortion != null

    /** True when the result is an axis-aligned rectangle (multiple of 90°, no distortion). */
    val isAxisAligned: Boolean
        get() {
            if (distortion != null) return false
            val q = rotationDeg / 90f
            return abs(q - roundHalfUp(q)) < 1e-5f
        }

    private fun cosSin(deg: Float = rotationDeg): FloatArray {
        val r = Math.toRadians(deg.toDouble())
        return floatArrayOf(snapUnit(cos(r).toFloat()), snapUnit(sin(r).toFloat()))
    }

    /** Maps normalized source coordinates to the document (affine part only). */
    fun map(u: Float, v: Float): Vec2 {
        val cs = cosSin()
        val lx = sx * (u - 0.5f) * srcW
        val ly = sy * (v - 0.5f) * srcH
        return Vec2(cx + cs[0] * lx - cs[1] * ly, cy + cs[1] * lx + cs[0] * ly)
    }

    /** Inverse of [map]: document point to normalized source coordinates (affine part only). */
    fun unmap(p: Vec2): Vec2 {
        val cs = cosSin()
        val dx = p.x - cx
        val dy = p.y - cy
        val lx = cs[0] * dx + cs[1] * dy
        val ly = -cs[1] * dx + cs[0] * dy
        return Vec2(lx / (sx * srcW) + 0.5f, ly / (sy * srcH) + 0.5f)
    }

    fun normalizedCorner(i: Int): Vec2 = distortion?.get(i) ?: UNIT[i]

    /** Document position of source corner [i] (0 = TL, 1 = TR, 2 = BR, 3 = BL). */
    fun corner(i: Int): Vec2 {
        val n = normalizedCorner(i)
        return map(n.x, n.y)
    }

    fun corners(): List<Vec2> = List(4) { corner(it) }

    /** Visual center: the average of the four corners. */
    fun center(): Vec2 {
        val c = corners()
        return Vec2((c[0].x + c[1].x + c[2].x + c[3].x) / 4f, (c[0].y + c[1].y + c[2].y + c[3].y) / 4f)
    }

    /** Axis-aligned bounds of the transformed bitmap (a projective map sends the rect to exactly the quad). */
    fun bounds(): DocBox {
        val c = corners()
        return DocBox(
            min(min(c[0].x, c[1].x), min(c[2].x, c[3].x)),
            min(min(c[0].y, c[1].y), min(c[2].y, c[3].y)),
            max(max(c[0].x, c[1].x), max(c[2].x, c[3].x)),
            max(max(c[0].y, c[1].y), max(c[2].y, c[3].y)),
        )
    }

    /** Vector [v] (document axes) expressed in the box's rotated axes. */
    fun toLocalAxes(v: Vec2): Vec2 {
        val cs = cosSin()
        return Vec2(cs[0] * v.x + cs[1] * v.y, -cs[1] * v.x + cs[0] * v.y)
    }

    /** Vector [v] in the box's rotated axes expressed in document axes. */
    fun fromLocalAxes(v: Vec2): Vec2 {
        val cs = cosSin()
        return Vec2(cs[0] * v.x - cs[1] * v.y, cs[1] * v.x + cs[0] * v.y)
    }

    /**
     * Affine matrix values in android.graphics.Matrix order (scaleX, skewX, transX, skewY,
     * scaleY, transY, persp0, persp1, persp2) mapping bitmap pixels to the document. Only valid
     * when not [isDistorted] (use the corners with setPolyToPoly then).
     */
    fun affineValues(): FloatArray {
        val cs = cosSin()
        val a = cs[0] * sx
        val b = -cs[1] * sy
        val c = cs[1] * sx
        val d = cs[0] * sy
        val hw = srcW / 2f
        val hh = srcH / 2f
        return floatArrayOf(a, b, cx - a * hw - b * hh, c, d, cy - c * hw - d * hh, 0f, 0f, 1f)
    }

    // ------------------------------------------------------------------ operations

    fun translated(dx: Float, dy: Float): TransformState =
        if (dx == 0f && dy == 0f) this else copy(cx = cx + dx, cy = cy + dy)

    /** Rotates the whole result by [deltaDeg] around [pivot] (document coordinates). */
    fun rotatedAbout(pivot: Vec2, deltaDeg: Float): TransformState {
        if (deltaDeg == 0f) return this
        val cs = cosSin(deltaDeg)
        val dx = cx - pivot.x
        val dy = cy - pivot.y
        return copy(
            cx = pivot.x + cs[0] * dx - cs[1] * dy,
            cy = pivot.y + cs[1] * dx + cs[0] * dy,
            rotationDeg = normalizeDeg(rotationDeg + deltaDeg),
        )
    }

    /** Scales by [kx], [ky] along the box's own (rotated) axes, keeping [anchor] fixed. */
    fun scaledAbout(anchor: Vec2, kx: Float, ky: Float): TransformState {
        val l = toLocalAxes(Vec2(cx - anchor.x, cy - anchor.y))
        val m = fromLocalAxes(Vec2(l.x * kx, l.y * ky))
        return copy(cx = anchor.x + m.x, cy = anchor.y + m.y, sx = sx * kx, sy = sy * ky)
    }

    /** Mirrors across the box's own vertical ([horizontal] = true) or horizontal axis. */
    fun flipped(horizontal: Boolean): TransformState =
        if (horizontal) scaledAbout(center(), -1f, 1f) else scaledAbout(center(), 1f, -1f)

    /** Quarter turn around the visual center. */
    fun rotated90(clockwise: Boolean): TransformState =
        rotatedAbout(center(), if (clockwise) 90f else -90f).snappedToPixels()

    /**
     * Replaces the four corners (document coordinates, TL/TR/BR/BL of the source) for a
     * perspective distort. Returns null if the quad would not be convex (invalid perspective).
     */
    fun withCorners(pts: List<Vec2>): TransformState? {
        if (pts.size != 4 || !isConvexQuad(pts)) return null
        val n = pts.map { unmap(it) }
        val isUnit = n.indices.all { abs(n[it].x - UNIT[it].x) < 1e-5f && abs(n[it].y - UNIT[it].y) < 1e-5f }
        return copy(distortion = if (isUnit) null else n)
    }

    /** Drops the perspective distortion (keeps the affine part). */
    fun undistorted(): TransformState = if (distortion == null) this else copy(distortion = null)

    /**
     * For axis-aligned results, shifts by less than a pixel so the bounds start on whole
     * pixels (a pure move / quarter turn then copies pixels exactly instead of resampling).
     */
    fun snappedToPixels(): TransformState {
        if (!isAxisAligned) return this
        val b = bounds()
        return translated(roundHalfUp(b.left) - b.left, roundHalfUp(b.top) - b.top)
    }

    /** Moves so the bounds' left/top edge is at the given document position (null = unchanged). */
    fun withPosition(left: Float? = null, top: Float? = null): TransformState {
        val b = bounds()
        return translated(if (left != null) left - b.left else 0f, if (top != null) top - b.top else 0f)
    }

    /**
     * Sets the box size (document pixels; null = unchanged). With [keepAspect] the other side
     * follows proportionally. Axis-aligned results keep their left/top edge (like design
     * apps); rotated ones scale around the center.
     */
    fun withSize(width: Float? = null, height: Float? = null, keepAspect: Boolean = false): TransformState {
        if (width == null && height == null) return this
        var kx = if (width != null) max(MIN_SIZE, width) / this.width else 1f
        var ky = if (height != null) max(MIN_SIZE, height) / this.height else 1f
        if (keepAspect) {
            if (width != null) ky = kx else kx = ky
            val k = clampUniform(kx)
            kx = k; ky = k
        }
        val before = bounds()
        val scaled = scaledAbout(center(), kx, ky)
        if (!scaled.isAxisAligned) return scaled
        val b = scaled.bounds()
        return scaled.translated(before.left - b.left, before.top - b.top)
    }

    /** Sets the absolute rotation (degrees), turning around the visual center. */
    fun withRotation(deg: Float): TransformState =
        rotatedAbout(center(), normalizeDeg(deg) - rotationDeg).snappedToPixels()

    /** Sets a uniform scale relative to the source size (100 = original), around the center. */
    fun withScalePercent(percent: Float): TransformState {
        val minScale = MIN_SIZE / min(srcW, srcH)
        val s = max(minScale, percent / 100f)
        return scaledAbout(center(), s / abs(sx), s / abs(sy)).snappedToPixels()
    }

    /** Uniformly scaled to fit the document exactly, centered, rotation/distortion cleared, flips kept. */
    fun fittedTo(docW: Int, docH: Int): TransformState =
        fitted(srcW, srcH, docW, docH, 1f, onlyShrink = false).let {
            it.copy(sx = if (sx < 0f) -it.sx else it.sx, sy = if (sy < 0f) -it.sy else it.sy)
        }

    /** Clamps a uniform factor so neither side collapses below [MIN_SIZE]. */
    fun clampUniform(k: Float): Float = clampFactor(k, MIN_SIZE / min(width, height))

    fun clampX(k: Float): Float = clampFactor(k, MIN_SIZE / width)
    fun clampY(k: Float): Float = clampFactor(k, MIN_SIZE / height)

    /**
     * How many times the source should be halved before resampling so bilinear filtering never
     * shrinks by more than 2x (0 = draw the source itself). Uses the larger axis scale so the
     * less-shrunk axis isn't blurred.
     */
    fun minificationLevel(maxLevel: Int = 8): Int {
        val c = corners()
        val ex = max(c[0].distanceTo(c[1]), c[3].distanceTo(c[2])) / srcW
        val ey = max(c[0].distanceTo(c[3]), c[1].distanceTo(c[2])) / srcH
        var s = max(ex, ey)
        var k = 0
        while (s < 0.5f && k < maxLevel) { s *= 2f; k++ }
        return k
    }

    /** True when both map every corner to the same place (within [eps] document pixels). */
    fun sameGeometry(other: TransformState, eps: Float = 1e-3f): Boolean {
        if (srcW != other.srcW || srcH != other.srcH) return false
        for (i in 0 until 4) {
            val a = corner(i)
            val b = other.corner(i)
            if (abs(a.x - b.x) > eps || abs(a.y - b.y) > eps) return false
        }
        return true
    }

    companion object {
        /** Normalized corners of the source: TL, TR, BR, BL. */
        val UNIT: List<Vec2> = listOf(Vec2(0f, 0f), Vec2(1f, 0f), Vec2(1f, 1f), Vec2(0f, 1f))

        /** Smallest allowed side, document pixels. */
        const val MIN_SIZE = 1f

        /** Untransformed placement of a bitmap lifted from ([left], [top]). */
        fun identity(left: Int, top: Int, width: Int, height: Int): TransformState =
            TransformState(width, height, left + width / 2f, top + height / 2f)

        /**
         * Centered in a [docW] x [docH] document and scaled uniformly to [fraction] of it.
         * With [onlyShrink], a source that already fits keeps its size.
         */
        fun fitted(srcW: Int, srcH: Int, docW: Int, docH: Int, fraction: Float = 1f, onlyShrink: Boolean = false): TransformState {
            val s = if (onlyShrink && srcW <= docW && srcH <= docH) 1f
            else min(docW * fraction / srcW, docH * fraction / srcH)
            return TransformState(srcW, srcH, docW / 2f, docH / 2f, s, s).snappedToPixels()
        }

        /** Initial placement of an imported picture: centered, shrunk with a small margin if too large. */
        fun placement(srcW: Int, srcH: Int, docW: Int, docH: Int): TransformState =
            fitted(srcW, srcH, docW, docH, 0.9f, onlyShrink = true)

        fun normalizeDeg(d: Float): Float {
            var r = d % 360f
            if (r <= -180f) r += 360f
            if (r > 180f) r -= 360f
            return r
        }

        /** Strictly convex, non-degenerate quad (either winding). */
        fun isConvexQuad(q: List<Vec2>): Boolean {
            if (q.size != 4) return false
            var sign = 0
            for (i in 0 until 4) {
                val a = q[i]
                val b = q[(i + 1) % 4]
                val c = q[(i + 2) % 4]
                val cr = (b - a).cross(c - b)
                if (abs(cr) < 1e-3f || cr.isNaN()) return false
                val s = if (cr > 0f) 1 else -1
                if (sign == 0) sign = s else if (s != sign) return false
            }
            return true
        }

        fun roundHalfUp(v: Float): Float = floor(v + 0.5f)

        private fun clampFactor(k: Float, minAbs: Float): Float = when {
            k.isNaN() -> 1f
            abs(k) >= minAbs -> k
            k < 0f -> -minAbs
            else -> minAbs
        }

        /** Makes cos/sin of multiples of 90° exact so quarter turns resample nothing. */
        private fun snapUnit(v: Float): Float = when {
            abs(v) < 1e-6f -> 0f
            abs(v - 1f) < 1e-6f -> 1f
            abs(v + 1f) < 1e-6f -> -1f
            else -> v
        }
    }
}
