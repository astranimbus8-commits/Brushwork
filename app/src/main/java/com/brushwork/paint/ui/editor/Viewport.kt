package com.brushwork.paint.ui.editor

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pure-Kotlin model of the canvas view transform (no android imports, so it is unit-tested on
 * the JVM). Document -> screen is
 *
 *     base(p)   = R(rotation) * (scale * p) + (tx, ty)
 *     screen(p) = mirrored ? (viewWidth - base.x, base.y) : base(p)
 *
 * i.e. the optional display mirror flips x around the view's vertical center line on top of
 * the pan/zoom/rotate transform. Every input goes through the same un-mirror first, so pinching
 * and drawing on a mirrored view still track the fingers.
 */
class Viewport {
    /** Screen pixels per document pixel. */
    var scale: Float = 1f
        private set

    /** View rotation in degrees, normalized to [-180, 180). */
    var rotation: Float = 0f
        private set

    var tx: Float = 0f
        private set
    var ty: Float = 0f
        private set

    var viewWidth: Int = 0
        private set
    var viewHeight: Int = 0
        private set

    /** Display-only horizontal mirror around the view center. */
    var mirrored: Boolean = false

    /** Document version the view was last fitted for (managed by the canvas view). */
    var fittedDocVersion: Int = Int.MIN_VALUE

    /** False until the user pans/zooms/rotates after the last fit (then we stop auto-refitting). */
    var userAdjusted: Boolean = false

    val hasSize: Boolean get() = viewWidth > 0 && viewHeight > 0

    /** Immutable copy of the transform (used to revert the view after a multi-finger tap). */
    data class State(val scale: Float, val rotation: Float, val tx: Float, val ty: Float)

    fun snapshot() = State(scale, rotation, tx, ty)

    fun restore(s: State) {
        scale = s.scale; rotation = s.rotation; tx = s.tx; ty = s.ty
    }

    // ------------------------------------------------------------------ mapping

    private var cachedRotation = Float.NaN
    private var cachedCos = 1f
    private var cachedSin = 0f

    private fun updateTrig() {
        if (cachedRotation == rotation) return
        cachedRotation = rotation
        // Exact values at right angles keep axis-aligned views free of sub-pixel skew.
        when (rotation) {
            0f -> { cachedCos = 1f; cachedSin = 0f }
            90f -> { cachedCos = 0f; cachedSin = 1f }
            -180f, 180f -> { cachedCos = -1f; cachedSin = 0f }
            -90f, 270f -> { cachedCos = 0f; cachedSin = -1f }
            else -> {
                val r = Math.toRadians(rotation.toDouble())
                cachedCos = cos(r).toFloat(); cachedSin = sin(r).toFloat()
            }
        }
    }

    /** Converts a display x into the un-mirrored base space (self-inverse). */
    private fun unmirrorX(x: Float): Float = if (mirrored) viewWidth - x else x

    /** Maps a document point to screen (view) coordinates into [out] (size >= 2). */
    fun docToScreen(x: Float, y: Float, out: FloatArray) {
        updateTrig()
        val c = cachedCos * scale; val s = cachedSin * scale
        out[0] = unmirrorX(c * x - s * y + tx)
        out[1] = s * x + c * y + ty
    }

    /** Maps a screen (view) point to document coordinates into [out] (size >= 2). */
    fun screenToDoc(x: Float, y: Float, out: FloatArray) {
        updateTrig()
        val dx = unmirrorX(x) - tx
        val dy = y - ty
        out[0] = (cachedCos * dx + cachedSin * dy) / scale
        out[1] = (-cachedSin * dx + cachedCos * dy) / scale
    }

    /**
     * Converts a screen direction ([radians] clockwise from the top of the screen, the convention
     * of `MotionEvent.AXIS_ORIENTATION`) into the same convention relative to the document's up
     * axis, removing the view rotation and mirror. Result in (-PI, PI].
     */
    fun screenAngleToDoc(radians: Float): Float {
        if (radians.isNaN()) return 0f
        val base = if (mirrored) -radians else radians
        return normalizeRadians(base - Math.toRadians(rotation.toDouble()).toFloat())
    }

    /**
     * Writes the document -> screen transform in `android.graphics.Matrix` value order
     * (MSCALE_X, MSKEW_X, MTRANS_X, MSKEW_Y, MSCALE_Y, MTRANS_Y, MPERSP_0, MPERSP_1, MPERSP_2).
     */
    fun matrixValues(out: FloatArray) {
        updateTrig()
        val a = cachedCos * scale; val b = -cachedSin * scale
        val d = cachedSin * scale; val e = cachedCos * scale
        if (mirrored) {
            out[0] = -a; out[1] = -b; out[2] = viewWidth - tx
        } else {
            out[0] = a; out[1] = b; out[2] = tx
        }
        out[3] = d; out[4] = e; out[5] = ty
        out[6] = 0f; out[7] = 0f; out[8] = 1f
    }

    /** Sets the translation so that document point ([docX], [docY]) lands on screen point ([sx], [sy]). */
    fun anchor(docX: Float, docY: Float, sx: Float, sy: Float) {
        updateTrig()
        val c = cachedCos * scale; val s = cachedSin * scale
        tx = unmirrorX(sx) - (c * docX - s * docY)
        ty = sy - (s * docX + c * docY)
    }

    // ------------------------------------------------------------------ view operations

    /** Updates the view size, keeping the document point at the old view center at the new center. */
    fun resize(width: Int, height: Int) {
        if (width == viewWidth && height == viewHeight) return
        if (hasSize && width > 0 && height > 0) {
            val p = FloatArray(2)
            screenToDoc(viewWidth / 2f, viewHeight / 2f, p)
            viewWidth = width; viewHeight = height
            anchor(p[0], p[1], width / 2f, height / 2f)
        } else {
            viewWidth = width; viewHeight = height
        }
    }

    /**
     * Fits a [docWidth] x [docHeight] document into the screen area [left, top, right, bottom]
     * (view coordinates; the part not covered by chrome) with [margin] (fraction of the area)
     * on each side, centered and unrotated.
     */
    fun fit(docWidth: Int, docHeight: Int, left: Float = 0f, top: Float = 0f, right: Float = viewWidth.toFloat(), bottom: Float = viewHeight.toFloat(), margin: Float = 0.05f) {
        if (docWidth <= 0 || docHeight <= 0) return
        var l = left; var t = top; var r = right; var b = bottom
        if (r - l < viewWidth * 0.25f || b - t < viewHeight * 0.25f) {
            // Chrome covers too much (tiny window): use the whole view.
            l = 0f; t = 0f; r = viewWidth.toFloat(); b = viewHeight.toFloat()
        }
        val aw = r - l; val ah = b - t
        if (aw <= 0f || ah <= 0f) return
        val k = (1f - 2f * margin).coerceIn(0.1f, 1f)
        scale = clampZoom(min(aw * k / docWidth, ah * k / docHeight))
        rotation = 0f
        // Center of the area (display coords) receives the document center.
        anchor(docWidth / 2f, docHeight / 2f, (l + r) / 2f, (t + b) / 2f)
        userAdjusted = false
    }

    /** Zooms to [newScale] keeping the document point under screen point ([fx], [fy]) fixed. */
    fun zoomAround(fx: Float, fy: Float, newScale: Float) {
        val p = FloatArray(2)
        screenToDoc(fx, fy, p)
        scale = clampZoom(newScale)
        anchor(p[0], p[1], fx, fy)
    }

    /** Sets the rotation to [degrees] keeping the document point under ([fx], [fy]) fixed. */
    fun rotateAround(fx: Float, fy: Float, degrees: Float) {
        val p = FloatArray(2)
        screenToDoc(fx, fy, p)
        rotation = normalizeDegrees(degrees)
        anchor(p[0], p[1], fx, fy)
    }

    /** 100 %: one document pixel per screen pixel, around the view center. */
    fun actualPixels() = zoomAround(viewWidth / 2f, viewHeight / 2f, 1f)

    fun resetRotation() = rotateAround(viewWidth / 2f, viewHeight / 2f, 0f)

    // ------------------------------------------------------------------ multi-pointer gesture

    private var gCount = 0
    private var gDocX = 0f
    private var gDocY = 0f
    private var gScale0 = 1f
    private var gRotation0 = 0f
    private var gSpread0 = 0f
    private var gAngle0 = 0f
    private val gTmp = FloatArray(4)

    /**
     * Starts (or re-anchors after a pointer was added/removed) a pan/pinch/rotate gesture with
     * [count] pointers at screen positions [xs]/[ys]. One pointer = pan only.
     */
    fun beginGesture(xs: FloatArray, ys: FloatArray, count: Int) {
        if (count <= 0) { gCount = 0; return }
        measure(xs, ys, count, gTmp)
        val p = FloatArray(2)
        // gTmp[0..1] is the centroid in base space; convert back to display to map to the doc.
        screenToDoc(unmirrorX(gTmp[0]), gTmp[1], p)
        gDocX = p[0]; gDocY = p[1]
        gScale0 = scale; gRotation0 = rotation
        gSpread0 = gTmp[2]; gAngle0 = gTmp[3]
        gCount = count
    }

    /**
     * Updates the gesture: the document point that was under the starting centroid stays under
     * the current centroid, scale follows the finger spread and rotation the angle between the
     * first two pointers (snapped to multiples of 90 degrees within [ROTATION_SNAP_DEG]).
     */
    fun updateGesture(xs: FloatArray, ys: FloatArray, count: Int) {
        if (count != gCount || count <= 0) { beginGesture(xs, ys, count); return }
        measure(xs, ys, count, gTmp)
        if (count >= 2) {
            if (gSpread0 > 1e-3f) scale = clampZoom(gScale0 * gTmp[2] / gSpread0)
            val delta = normalizeDegrees(gTmp[3] - gAngle0)
            rotation = snapRotation(normalizeDegrees(gRotation0 + delta))
        }
        anchor(gDocX, gDocY, unmirrorX(gTmp[0]), gTmp[1])
        userAdjusted = true
    }

    /** Centroid (base space), mean spread and angle (degrees) of the first two pointers. */
    private fun measure(xs: FloatArray, ys: FloatArray, n: Int, out: FloatArray) {
        var cx = 0f; var cy = 0f
        for (i in 0 until n) { cx += unmirrorX(xs[i]); cy += ys[i] }
        cx /= n; cy /= n
        var spread = 0f
        for (i in 0 until n) spread += hypot(unmirrorX(xs[i]) - cx, ys[i] - cy)
        spread /= n
        val angle = if (n >= 2) {
            Math.toDegrees(atan2((ys[1] - ys[0]).toDouble(), (unmirrorX(xs[1]) - unmirrorX(xs[0])).toDouble())).toFloat()
        } else 0f
        out[0] = cx; out[1] = cy; out[2] = spread; out[3] = angle
    }

    companion object {
        /** 1 % */
        const val MIN_ZOOM = 0.01f
        /** 6400 % */
        const val MAX_ZOOM = 64f
        const val ROTATION_SNAP_DEG = 4f

        fun clampZoom(s: Float): Float = if (s.isNaN()) 1f else s.coerceIn(MIN_ZOOM, MAX_ZOOM)

        /** Normalizes to [-180, 180). */
        fun normalizeDegrees(d: Float): Float {
            var r = (d + 180f) % 360f
            if (r < 0f) r += 360f
            return r - 180f
        }

        /** Normalizes to (-PI, PI]. */
        fun normalizeRadians(r: Float): Float {
            val twoPi = (2.0 * Math.PI).toFloat()
            var a = r % twoPi
            if (a <= -Math.PI.toFloat()) a += twoPi
            if (a > Math.PI.toFloat()) a -= twoPi
            return a
        }

        /** Snaps to the nearest multiple of 90 degrees when within [threshold]. */
        fun snapRotation(degrees: Float, threshold: Float = ROTATION_SNAP_DEG): Float {
            val nearest = (degrees / 90f).roundToInt() * 90f
            return if (abs(degrees - nearest) <= threshold) normalizeDegrees(nearest) else degrees
        }
    }
}
