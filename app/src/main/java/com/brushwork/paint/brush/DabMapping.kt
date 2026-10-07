package com.brushwork.paint.brush

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.brushwork.paint.vector.StrokeCopies
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * v1.7 (item 18, §3.18): the copies a symmetry ruler makes of a stroke's dabs. [maps] are the
 * stroke's maps (row-major 3×3, the identity first: `SymmetryMaps.transforms`, `VStroke.copies`);
 * copy k (0 until [copies]) is `maps[k + 1]`. A copy of a resolved dab has
 *
 * - its centre mapped (by the homography for the perspective array);
 * - its diameter × √|det J| at the dab's centre (J the map's Jacobian there:
 *   `StrokeCopies.scaleAt`, so the vector readers' bounds and hit tests agree; within 1e-5 of 1
 *   it is 1), drawn at 1 px with proportionally less alpha below 1 px like `StrokeDynamics.resolve`;
 * - its tip turned: the angle of J·(cos θ, sin θ) (θ the dab's rotation; for a pixel tip the
 *   brush angle baked into the tip, so the copy gets a brush turned the same way); a radial tip
 *   ([isRadial]: a disc at any angle) keeps θ, which draws the same disc faster;
 * - its tip mirrored when det J < 0: textured anti-aliased tips (pencil, chalk, spray) are drawn
 *   flipped; every other tip is symmetric about its own axis, so turning it is the mirror image;
 * - the same alpha, variant and distance.
 *
 * The live stroke (`BrushTool`) and its replay (`StrokeRaster`) stamp every dab, then each of its
 * copies in this order, through one instance each, so a vector stroke re-renders like it was
 * drawn. [stamper] measures and draws the dabs (its tips are shared). Not thread-safe (one per
 * stroke / replay).
 *
 * With many copies ([PHASE_MIN_MAPS] maps or more; §6.3 budgets 64 copies of a 64 px brush), an
 * unturned anti-aliased copy under an affine map is drawn from [PhaseTips]: its tip shifted to the
 * nearest quarter pixel, copied at a whole-pixel position. That is about ten times cheaper than
 * DabStamper's sub-pixel draw and lands within 1/8 px of it. The dab itself, and every copy of a
 * stroke with fewer maps (a mirror, a few turns), is drawn exactly as before.
 */
class DabMapping(maps: List<FloatArray>, private val stamper: DabStamper, phaseTips: PhaseTips? = null) {
    private val copyMaps: Array<CopyMap> = Array(max(0, maps.size - 1)) { CopyMap(maps[it + 1]) }

    /** Copies besides the stroke itself (the maps without the identity). */
    val copies: Int get() = copyMaps.size

    /** The dab [place] returns (reused). */
    private val placed = Dab(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0, 0f)
    private var placedPreset: BrushPreset? = null
    private var placedMirrored = false
    private var placedCopy = -1

    /** Whether the last preset placed ([radialPreset]) has a radial tip ([isRadial]). */
    private var radialPreset: BrushPreset? = null
    private var radial = false

    /** Copies drawn from shifted tips (many copies only), and whether the last placed one is. */
    private val phases: PhaseTips? = if (maps.size >= PHASE_MIN_MAPS) phaseTips ?: PhaseTips() else null
    private var placedTip: Tip? = null
    private var placedPhase = false

    /** Draws mirrored textured tips (DabStamper's anti-aliased path with the tip flipped). */
    private val matrix = Matrix()
    private val paint = Paint().apply { isFilterBitmap = true }
    private var paintAlpha = -1

    /**
     * Copy [k] of resolved [dab] (drawn with [preset]), measured (its bounds set): a dab owned
     * by this instance and reused by the next call, so stamp it ([stamp]) before placing another.
     * Null when the copy can't be drawn (it lands on or behind the perspective horizon).
     */
    fun place(k: Int, preset: BrushPreset, dab: Dab): Dab? {
        placedCopy = -1
        val c = copyMaps[k]
        if (preset !== radialPreset) {
            radialPreset = preset
            radial = isRadial(preset)
        }
        if (!c.mapInto(dab, placed, preset.antiAlias, keepRotation = radial)) return null
        val p = if (preset.antiAlias) preset else c.turnedPreset(preset)
        placedTip = stamper.measure(p, placed)
        placedPreset = p
        placedMirrored = c.lastMirrored && preset.antiAlias && TipShapes.isTextured(preset.tip)
        placedPhase = phases != null && c.affine && preset.antiAlias && !placedMirrored && (placed.rotation == 0f || radial)
        placedCopy = k
        return placed
    }

    /** Draws the dab the last [place] returned (copy [k]) into [canvas]. */
    fun stamp(canvas: Canvas, k: Int, mapped: Dab) {
        val p = placedPreset ?: return
        if (placedCopy != k || mapped !== placed) return
        if (!placedMirrored) {
            val tip = placedTip
            if (placedPhase && tip != null && phases!!.stamp(canvas, tip, mapped)) return
            stamper.stamp(canvas, p, mapped)
            return
        }
        // DabStamper.stamp's anti-aliased path with the tip flipped about its own x axis.
        val tip = stamper.measure(p, mapped)
        val alpha = (mapped.alpha * 255f + 0.5f).toInt()
        if (alpha <= 0) return
        val a = alpha.coerceAtMost(255)
        if (a != paintAlpha) {
            paint.alpha = a
            paintAlpha = a
        }
        val s = mapped.diameter / tip.diameter
        val half = tip.size / 2f
        matrix.setTranslate(-half, -half)
        matrix.postScale(s, -s)
        if (mapped.rotation != 0f) matrix.postRotate(mapped.rotation)
        matrix.postTranslate(mapped.cx, mapped.cy)
        canvas.drawBitmap(tip.bitmap, matrix, paint)
    }

    /**
     * Copy [k] of resolved [dab] written into [out] (centre, diameter, alpha, rotation; not
     * measured), for strokes that paint dabs themselves (smudge, blur, watercolor: their tips are
     * symmetric, drawn at any rotation). False when the copy can't be drawn.
     */
    fun mapInto(k: Int, dab: Dab, out: Dab): Boolean = copyMaps[k].mapInto(dab, out, antiAlias = true)

    /** One map, with what is fixed for an affine map worked out once. */
    private class CopyMap(map: FloatArray) {
        private val m = map.copyOf()
        /** No perspective: all the copies of a dab get one scale (a rigid map keeps its diameter). */
        val affine = m[6] == 0f && m[7] == 0f
        /**
         * √|det J| of an affine map (the same everywhere); exactly 1 for a rigid map (a mirror,
         * a turn, a shift), whose float rounding would otherwise make every copy a hair smaller
         * than its dab and miss the stamper's last-tip cache.
         */
        private val affineScale = if (affine) StrokeCopies.scaleAt(m, 0f, 0f).let { if (abs(it - 1f) <= 1e-5f) 1f else it } else 1f
        /** The map reverses orientation (det < 0 where it is in front of the horizon). */
        private val reverses: Boolean = det(m) < 0.0

        /** True when the last [mapInto] mirrored the dab. */
        var lastMirrored = false
            private set

        // The last rotation turned (most tips keep one rotation: the brush angle).
        private var lastIn = Float.NaN
        private var lastOut = 0f
        private var lastX = Float.NaN
        private var lastY = Float.NaN

        // The brush turned for pixel tips (their angle is baked into the tip).
        private var turnedBase: BrushPreset? = null
        private var turned: BrushPreset? = null
        private var turnedByAngle: HashMap<Int, BrushPreset>? = null
        private var turnAtX = 0f
        private var turnAtY = 0f

        fun mapInto(dab: Dab, out: Dab, antiAlias: Boolean, keepRotation: Boolean = false): Boolean {
            val x = dab.cx
            val y = dab.cy
            val w = m[6] * x + m[7] * y + m[8]
            if (!(w > 0f) || !w.isFinite()) return false
            val qx = (m[0] * x + m[1] * y + m[2]) / w
            val qy = (m[3] * x + m[4] * y + m[5]) / w
            if (!qx.isFinite() || !qy.isFinite()) return false
            val scale = if (affine) affineScale else StrokeCopies.scaleAt(m, x, y)
            var d = dab.diameter * scale
            var a = dab.alpha
            if (d < 1f) {
                if (antiAlias) a *= max(d, 0f)
                d = 1f
            }
            val rot = if (keepRotation) dab.rotation else turn(dab.rotation, x, y)
            out.reuse(qx, qy, dab.pressure, dab.distance, dab.scatterX, dab.scatterY, rot, dab.variant, dab.jitter)
            out.cx = qx
            out.cy = qy
            out.diameter = d
            out.alpha = a.coerceIn(0f, 1f)
            lastMirrored = reverses
            turnAtX = x
            turnAtY = y
            return true
        }

        /** [deg] turned by the map's Jacobian at (x, y), in [0, 360). */
        private fun turn(deg: Float, x: Float, y: Float): Float {
            if (deg == lastIn && (affine || (x == lastX && y == lastY))) return lastOut
            val out = turnedAngle(deg, x, y)
            lastIn = deg
            lastX = x
            lastY = y
            lastOut = out
            return out
        }

        private fun turnedAngle(deg: Float, x: Float, y: Float): Float {
            val a = Math.toRadians(deg.toDouble())
            val ux = cos(a)
            val uy = sin(a)
            val j0: Double; val j1: Double; val j2: Double; val j3: Double
            if (affine) {
                j0 = m[0].toDouble(); j1 = m[1].toDouble(); j2 = m[3].toDouble(); j3 = m[4].toDouble()
            } else {
                val w = m[6].toDouble() * x + m[7].toDouble() * y + m[8]
                val qx = (m[0].toDouble() * x + m[1].toDouble() * y + m[2]) / w
                val qy = (m[3].toDouble() * x + m[4].toDouble() * y + m[5]) / w
                j0 = m[0] - qx * m[6]; j1 = m[1] - qx * m[7]; j2 = m[3] - qy * m[6]; j3 = m[4] - qy * m[7]
            }
            val vx = j0 * ux + j1 * uy
            val vy = j2 * ux + j3 * uy
            if (!vx.isFinite() || !vy.isFinite() || (vx == 0.0 && vy == 0.0)) return deg
            var out = Math.toDegrees(atan2(vy, vx))
            if (out < 0.0) out += 360.0
            // Rounding noise of exact turns (a mirror of 0° is 180°, not 179.99998°).
            val r = Math.round(out).toDouble()
            if (abs(out - r) < 1e-4) out = r
            if (out >= 360.0) out -= 360.0
            return out.toFloat()
        }

        /** [preset] with its angle turned like the last mapped dab's (pixel tips bake it in). */
        fun turnedPreset(preset: BrushPreset): BrushPreset {
            if (affine) {
                if (preset === turnedBase && turned != null) return turned!!
                val p = withAngle(preset, turnedAngle(preset.angle, 0f, 0f))
                turnedBase = preset
                turned = p
                return p
            }
            // A homography turns differently across the canvas: one brush per whole degree (as the tip cache keys them).
            val deg = Math.floorMod(turnedAngle(preset.angle, turnAtX, turnAtY).roundToInt(), 360)
            if (preset !== turnedBase) {
                turnedBase = preset
                turnedByAngle = HashMap()
            }
            return turnedByAngle!!.getOrPut(deg) { withAngle(preset, deg.toFloat()) }
        }

        private fun withAngle(p: BrushPreset, deg: Float): BrushPreset = if (deg == p.angle) p else p.copy(angle = deg)

        private fun det(a: FloatArray): Double {
            val d = DoubleArray(9) { a[it].toDouble() }
            return d[0] * (d[4] * d[8] - d[5] * d[7]) - d[1] * (d[3] * d[8] - d[5] * d[6]) + d[2] * (d[3] * d[7] - d[4] * d[6])
        }
    }

    /**
     * The anti-aliased tip of the copies' dabs at their scale, pre-shifted by quarter pixels in x
     * and y (each of the [STEPS]² shifts drawn when first needed, by DabStamper's own bilinear
     * draw at that shift): a copy is then this bitmap copied at a whole-pixel position with the
     * dab's alpha (Skia's plain pixel copy), within 1/8 px of its exact place. Copies wider than
     * [MAX_PX] keep the exact draw (their shifts would take too much memory). One tip and scale
     * at a time: a stroke's copies share both (an affine map scales every copy of a dab alike),
     * and so do most of its dabs. Mappings used one after another on one thread may share one
     * (`StrokeRaster` replays stroke after stroke with the same tips); a mapping makes its own
     * otherwise. Not thread-safe.
     */
    class PhaseTips {
        private var tip: Tip? = null
        private var scale = Float.NaN
        private val shifted = arrayOfNulls<Bitmap>(STEPS * STEPS)
        private val drawn = BooleanArray(STEPS * STEPS)
        private val renderCanvas = Canvas()
        private val renderMatrix = Matrix()
        // DabStamper's anti-aliased, filtered paint (opaque: the copy brings the dab's alpha).
        private val renderPaint = Paint().apply { isFilterBitmap = true }
        private val copyPaint = Paint().apply { isAntiAlias = false; isFilterBitmap = false }
        private var copyAlpha = -1

        /** Draws [dab] (measured with [tip]) into [canvas]; false when it needs the exact draw. */
        fun stamp(canvas: Canvas, tip: Tip, dab: Dab): Boolean {
            val s = dab.diameter / tip.diameter
            if (!(tip.size * s <= MAX_PX)) return false
            val alpha = (dab.alpha * 255f + 0.5f).toInt()
            if (alpha <= 0) return true
            if (tip !== this.tip || s != scale || tip.bitmap.isRecycled) {
                this.tip = tip
                scale = s
                drawn.fill(false)
            }
            // The tip's top left corner (DabStamper: translate(-half) · scale(s) · translate(centre))
            // to the nearest 1/STEPS px.
            val half = tip.size / 2f
            val qx = floor((dab.cx - half * s) * STEPS + 0.5f).toInt()
            val qy = floor((dab.cy - half * s) * STEPS + 0.5f).toInt()
            val px = Math.floorMod(qx, STEPS)
            val py = Math.floorMod(qy, STEPS)
            val i = py * STEPS + px
            val bmp = if (drawn[i]) shifted[i]!! else render(i, tip, s, half, px, py)
            val a = alpha.coerceAtMost(255)
            if (a != copyAlpha) {
                copyPaint.alpha = a
                copyAlpha = a
            }
            canvas.drawBitmap(bmp, Math.floorDiv(qx, STEPS).toFloat(), Math.floorDiv(qy, STEPS).toFloat(), copyPaint)
            return true
        }

        private fun render(i: Int, tip: Tip, s: Float, half: Float, px: Int, py: Int): Bitmap {
            val side = ceil(tip.size * max(s, 1f)).toInt() + 1
            var b = shifted[i]
            if (b == null || b.isRecycled || b.width != side) {
                b?.recycle()
                b = Bitmap.createBitmap(side, side, Bitmap.Config.ALPHA_8)
                shifted[i] = b
            } else {
                b.eraseColor(0)
            }
            renderMatrix.setTranslate(-half, -half)
            renderMatrix.postScale(s, s)
            renderMatrix.postTranslate(half * s + px / STEPS.toFloat(), half * s + py / STEPS.toFloat())
            renderCanvas.setBitmap(b)
            renderCanvas.drawBitmap(tip.bitmap, renderMatrix, renderPaint)
            renderCanvas.setBitmap(null)
            drawn[i] = true
            return b
        }

        companion object {
            /** Shifts per pixel (quarter pixels: a copy lands at most 1/8 px from its place). */
            const val STEPS = 4
            /** Widest copy drawn this way (px): its 16 shifts take at most about 1 MB. */
            const val MAX_PX = 256f
        }
    }

    companion object {
        /**
         * Maps from which copies are drawn from [PhaseTips]. With fewer (a mirror, a few turns)
         * every copy is drawn exactly: they don't cost enough to need it.
         */
        internal const val PHASE_MIN_MAPS = 16

        /** The mapping of [maps] (a stroke's copies), or null when there are none (fewer than 2 maps). */
        fun of(maps: List<FloatArray>, stamper: DabStamper, phaseTips: PhaseTips? = null): DabMapping? =
            if (maps.size < 2) null else DabMapping(maps, stamper, phaseTips)

        private val RADIAL_TIPS = setOf(
            BrushTip.ROUND_HARD, BrushTip.ROUND_SOFT, BrushTip.AIRBRUSH, BrushTip.CALLIGRAPHY,
            BrushTip.WATERCOLOR, BrushTip.SMUDGE, BrushTip.BLUR,
        )

        /**
         * True when [preset]'s tip is a disc at any rotation: an anti-aliased round tip at full
         * roundness (as `TipCache` quantizes it). Its copies keep the dab's rotation instead of
         * turning it (a turned disc is the same disc), so an unturned dab keeps `DabStamper`'s
         * unrotated path, about 1.4× cheaper than a turned one: what many copies cost (§6.3).
         */
        internal fun isRadial(preset: BrushPreset): Boolean =
            preset.antiAlias && preset.tip in RADIAL_TIPS &&
                (preset.roundness.coerceIn(BrushLimits.MIN_ROUNDNESS, 1f) * 40f).roundToInt() == 40
    }
}
