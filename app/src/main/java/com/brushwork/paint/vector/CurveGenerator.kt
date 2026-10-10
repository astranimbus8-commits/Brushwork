package com.brushwork.paint.vector

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/** v1.8 (item 6): the kinds of generated curve. */
@Serializable
enum class CurveGenKind { ARCHIMEDEAN, LOGARITHMIC, GOLDEN, FERMAT, SINE, ROSE }

/**
 * v1.8 (item 6): an editable generated curve. Document px; angles in degrees. Unused fields keep
 * their defaults per kind. The geometry is CurveGenerators.build (tools/vector/gen, area E).
 *
 * Stored on its path ([VPath.generator], never written when null). A path keeps it only while
 * its geometry is the generator's (I16): a hand edit of a point clears it, and a reopened path
 * whose geometry no longer matches loses it.
 */
@Serializable
data class CurveGenerator(
    val kind: CurveGenKind = CurveGenKind.ARCHIMEDEAN,
    val cx: Float = 0f,
    val cy: Float = 0f,
    /** Outer radius (spirals, rose) or the wave's length (sine). */
    val size: Float = 200f,
    val angleDeg: Float = 0f,
    val turns: Float = 3f,          // spirals: 0.25..MAX_TURNS
    val inner: Float = 0f,          // Archimedean / Fermat: inner radius as a fraction of size, 0..0.9
    val growth: Float = 2f,         // Logarithmic: radius factor per turn, 1.05..10 (GOLDEN fixes φ^4 per turn)
    val clockwise: Boolean = false,
    val amplitude: Float = 40f,     // sine: px
    val waves: Float = 3f,          // sine: 0.5..50
    val petalsN: Int = 5,           // rose r = cos(n/d·θ): 1..24
    val petalsD: Int = 1,           // 1..12
    /** Path tool: interpolated samples per turn, wave or petal; 0 = the default 8. */
    val density: Int = 0,
) {
    /**
     * Clamped and finite; null when unusable (non-finite centre, size <= 0). A non-finite value
     * other than the centre and the size takes its default; the centre and the size are kept
     * within [MAX_SIZE] of the origin; the amplitude within ±[MAX_SIZE]; the angle in -360..360
     * (its remainder). This instance when nothing changes.
     */
    fun sanitized(): CurveGenerator? {
        if (!cx.isFinite() || !cy.isFinite() || !size.isFinite() || size <= 0f) return null
        val n = CurveGenerator(
            kind = kind,
            cx = cx.coerceIn(-MAX_SIZE, MAX_SIZE),
            cy = cy.coerceIn(-MAX_SIZE, MAX_SIZE),
            size = size.coerceAtMost(MAX_SIZE),
            angleDeg = if (angleDeg.isFinite()) angleDeg % 360f else DEFAULT.angleDeg,
            turns = finite(turns, DEFAULT.turns).coerceIn(MIN_TURNS, MAX_TURNS),
            inner = finite(inner, DEFAULT.inner).coerceIn(0f, MAX_INNER),
            growth = finite(growth, DEFAULT.growth).coerceIn(MIN_GROWTH, MAX_GROWTH),
            clockwise = clockwise,
            amplitude = finite(amplitude, DEFAULT.amplitude).coerceIn(-MAX_SIZE, MAX_SIZE),
            waves = finite(waves, DEFAULT.waves).coerceIn(MIN_WAVES, MAX_WAVES),
            petalsN = petalsN.coerceIn(1, MAX_PETALS_N),
            petalsD = petalsD.coerceIn(1, MAX_PETALS_D),
            density = density.coerceIn(0, MAX_DENSITY),
        )
        return if (n == this) this else n
    }

    /**
     * Under a similarity [m] (uniform scale, rotation, translation, reflection flips clockwise);
     * null for anything else. [m] is row-major 3×3 (`VectorOps.transformed`). The centre is mapped
     * as a point; the size and the amplitude scale by the map's factor; the angle turns with it.
     * A reflection mirrors the angle (the curve's start direction) and flips [clockwise]. Null too
     * when the result is not usable ([sanitized]).
     */
    fun mapped(m: FloatArray): CurveGenerator? {
        if (m.size < 9 || m.any { !it.isFinite() } || m[6] != 0f || m[7] != 0f || m[8] == 0f) return null
        val w = m[8]
        val a = m[0] / w; val b = m[1] / w; val tx = m[2] / w
        val c = m[3] / w; val d = m[4] / w; val ty = m[5] / w
        val det = a * d - b * c
        if (!det.isFinite() || det == 0f) return null
        val s = sqrt(abs(det))
        val reflects = det < 0f
        // A similarity: [a b; c d] = s·R (rotation) or s·R·F (reflection), so its columns are
        // orthogonal and of length s.
        val tol = SIMILARITY_TOLERANCE * s
        if (reflects) {
            if (abs(a + d) > tol || abs(b - c) > tol) return null
        } else {
            if (abs(a - d) > tol || abs(b + c) > tol) return null
        }
        // The map's own turn: s·R(φ) takes the angle α to α + φ; the reflection s·R(φ)·F (F = the
        // mirror in the x axis) takes it to φ − α. A map without a turn keeps the angle exactly.
        val turn = if (c == 0f && a > 0f) 0f else Math.toDegrees(atan2(c.toDouble(), a.toDouble())).toFloat()
        val angle = if (reflects) turn - angleDeg else angleDeg + turn
        return copy(
            cx = a * cx + b * cy + tx,
            cy = c * cx + d * cy + ty,
            size = size * s,
            amplitude = amplitude * s,
            angleDeg = angle,
            clockwise = if (reflects) !clockwise else clockwise,
        ).sanitized()
    }

    companion object {
        const val MAX_SIZE = 100_000f
        const val MAX_TURNS = 50f
        const val MAX_DENSITY = 64

        private const val MIN_TURNS = 0.25f
        private const val MAX_INNER = 0.9f
        private const val MIN_GROWTH = 1.05f
        private const val MAX_GROWTH = 10f
        private const val MIN_WAVES = 0.5f
        private const val MAX_WAVES = 50f
        private const val MAX_PETALS_N = 24
        private const val MAX_PETALS_D = 12

        /** How far from a similarity a map may be (relative to its scale): float rounding only. */
        private const val SIMILARITY_TOLERANCE = 1e-4f

        private val DEFAULT = CurveGenerator()

        private fun finite(v: Float, default: Float) = if (v.isFinite()) v else default
    }
}
