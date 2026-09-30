package com.brushwork.paint.brush

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * One brush dab. The input part (path position, pressure, distance and the random values drawn
 * for it) is fixed when the dab is created (a path stroke reuses the dabs it removed: see
 * [StrokeDynamics.newDab]); [StrokeDynamics.resolve] computes the rendered center, diameter and
 * alpha, which can change once the stroke length is known (end taper).
 */
class Dab(
    x: Float,
    y: Float,
    pressure: Float,
    distance: Float,
    scatterX: Float,
    scatterY: Float,
    rotation: Float,
    variant: Int,
    jitter: Float,
) {
    /** Position on the smoothed path (document px). */
    var x = x
        private set
    var y = y
        private set
    /** Effective pressure 0..1 (stylus pressure, or 1 for fingers). */
    var pressure = pressure
        private set
    /** Distance along the path from the stroke start. */
    var distance = distance
        private set
    /** Random offset inside the unit disk, scaled by scatter * diameter. */
    var scatterX = scatterX
        private set
    var scatterY = scatterY
        private set
    /** Tip rotation in degrees. */
    var rotation = rotation
        private set
    /** Texture variant of the tip. */
    var variant = variant
        private set
    /** Uniform random 0..1 used for the grain opacity jitter. */
    var jitter = jitter
        private set

    /** Rendered center. */
    var cx = x
    var cy = y
    /** Rendered diameter (document px, >= 1 once resolved). */
    var diameter = 0f
    /** Rendered dab opacity 0..1. */
    var alpha = 0f

    /** Bounds of the last rendering (document px, [left, right) x [top, bottom)). */
    var left = 0
    var top = 0
    var right = 0
    var bottom = 0

    val hasBounds: Boolean get() = right > left && bottom > top

    /** Makes this dab (no longer used anywhere) a new one, as if just constructed. */
    internal fun reuse(x: Float, y: Float, pressure: Float, distance: Float, scatterX: Float, scatterY: Float, rotation: Float, variant: Int, jitter: Float): Dab {
        this.x = x; this.y = y; this.pressure = pressure; this.distance = distance
        this.scatterX = scatterX; this.scatterY = scatterY; this.rotation = rotation
        this.variant = variant; this.jitter = jitter
        cx = x; cy = y
        diameter = 0f; alpha = 0f
        left = 0; top = 0; right = 0; bottom = 0
        return this
    }
}

/**
 * Brush dynamics: pressure -> size/opacity, finger tapers, scatter, grain jitter and dab
 * spacing for one stroke. Pure Kotlin.
 *
 * @param isStylus tapers only apply to finger strokes (a stylus has real pressure).
 * @param sizeOverride replaces [BrushPreset.size] (thumbnails).
 */
class StrokeDynamics(
    val preset: BrushPreset,
    val isStylus: Boolean,
    seed: Long,
    sizeOverride: Float? = null,
) {
    private val rng = CountingRandom(seed)

    /** Random values drawn so far (see [restoreRandom]). */
    val randomDraws: Long get() = rng.draws

    /**
     * Puts the random sequence back to where it was after [draws] values, so dabs created from
     * there on get exactly the random values they got the first time.
     */
    fun restoreRandom(draws: Long) = rng.restore(draws)
    private val variants = TipShapes.variants(preset.tip)
    private val randomRotation = TipShapes.isTextured(preset.tip)

    /** Maximum diameter of this stroke. */
    val size: Float = sizeOverride ?: preset.size

    val taperStart: Float = if (isStylus) 0f else preset.taperStart
    val taperEnd: Float = if (isStylus) 0f else preset.taperEnd
    val hasTaper: Boolean get() = taperStart > 0f || taperEnd > 0f

    /** Size multiplier from pressure. */
    fun pressureSizeFactor(pressure: Float): Float =
        if (preset.pressureSize) preset.minSizeRatio + (1f - preset.minSizeRatio) * pressure.coerceIn(0f, 1f) else 1f

    /** Opacity multiplier from pressure. */
    fun pressureOpacityFactor(pressure: Float): Float =
        if (preset.pressureOpacity) max(0.03f, pressure.coerceIn(0f, 1f)) else 1f

    /**
     * Taper multiplier 0..1 at [distance]. [total] is the final stroke length, or null while the
     * stroke is in progress (only the start taper is known then). When [scaleShort] is true and
     * the stroke is shorter than both tapers, the tapers are shortened proportionally. Taps
     * (strokes shorter than 1 px) are never tapered.
     */
    fun taperFactor(distance: Float, total: Float?, scaleShort: Boolean = true): Float {
        if (!hasTaper) return 1f
        if (total != null && total < 1f) return 1f
        var ts = taperStart
        var te = taperEnd
        if (total != null && scaleShort && ts + te > total) {
            val k = total / (ts + te)
            ts *= k; te *= k
        }
        var f = 1f
        if (ts > 0f) f = min(f, ease(distance / ts))
        if (te > 0f && total != null) f = min(f, ease((total - distance) / te))
        return f
    }

    /** Diameter while the stroke is in progress (used for spacing). */
    fun liveDiameter(pressure: Float, distance: Float): Float =
        size * pressureSizeFactor(pressure) * taperFactor(distance, null)

    /** Distance to the next dab. */
    fun spacing(pressure: Float, distance: Float): Float =
        max(MIN_SPACING_PX, preset.spacing * max(1f, liveDiameter(pressure, distance)))

    /**
     * Creates the dab for a path sample, drawing its random values; with [recycled] (a dab no
     * longer used anywhere) that object is reused instead of allocating one.
     */
    fun newDab(x: Float, y: Float, pressure: Float, distance: Float, recycled: Dab? = null): Dab {
        var sx = 0f
        var sy = 0f
        if (preset.scatter > 0f) {
            val r = sqrt(rng.nextFloat())
            val a = rng.nextFloat() * 2f * PI.toFloat()
            sx = r * cos(a); sy = r * sin(a)
        }
        val rotation = if (randomRotation) preset.angle + rng.nextFloat() * 360f else preset.angle
        val variant = if (variants > 1) rng.nextInt(variants) else 0
        val jitter = rng.nextFloat()
        val p = pressure.coerceIn(0f, 1f)
        return recycled?.reuse(x, y, p, distance, sx, sy, rotation, variant, jitter)
            ?: Dab(x, y, p, distance, sx, sy, rotation, variant, jitter)
    }

    /**
     * Computes the rendered center, diameter and alpha of [dab] (see [taperFactor] for [total]
     * and [scaleShort]). Diameters below 1 px are drawn at 1 px with proportionally lower alpha.
     */
    fun resolve(dab: Dab, total: Float?, scaleShort: Boolean = true) {
        val taper = taperFactor(dab.distance, total, scaleShort)
        var d = size * pressureSizeFactor(dab.pressure) * taper
        var a = preset.flow * pressureOpacityFactor(dab.pressure)
        if (preset.pressureOpacity) a *= taper
        if (preset.grain > 0f) a *= 1f - 0.35f * preset.grain * dab.jitter
        if (preset.scatter > 0f) {
            dab.cx = dab.x + dab.scatterX * preset.scatter * d
            dab.cy = dab.y + dab.scatterY * preset.scatter * d
        } else {
            dab.cx = dab.x
            dab.cy = dab.y
        }
        if (d < 1f) {
            if (preset.antiAlias) a *= max(d, 0f)
            d = 1f
        }
        dab.diameter = d
        dab.alpha = a.coerceIn(0f, 1f)
    }

    /**
     * `Random(seed)` that counts its draws and can go back to an earlier position. Every value
     * of kotlin.random.Random comes from [nextBits], and the seeded generator advances one step
     * per call whatever the bit count, so the sequence is exactly that of `Random(seed)`.
     */
    private class CountingRandom(private val seed: Long) : Random() {
        private var inner = Random(seed)
        var draws = 0L
            private set

        override fun nextBits(bitCount: Int): Int {
            draws++
            return inner.nextBits(bitCount)
        }

        fun restore(target: Long) {
            val n = target.coerceAtLeast(0L)
            if (n < draws) {
                inner = Random(seed)
                draws = 0L
            }
            while (draws < n) {
                inner.nextInt()
                draws++
            }
        }
    }

    companion object {
        /** Smallest taper multiplier (the pointed end of a tapered stroke). */
        const val TAPER_MIN = 0.06f
        const val MIN_SPACING_PX = 0.5f

        private fun ease(t: Float): Float {
            val c = t.coerceIn(0f, 1f)
            val u = 1f - c
            return TAPER_MIN + (1f - TAPER_MIN) * (1f - u * u)
        }
    }
}
