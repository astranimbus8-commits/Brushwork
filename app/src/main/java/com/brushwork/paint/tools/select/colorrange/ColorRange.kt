package com.brushwork.paint.tools.select.colorrange

import com.brushwork.paint.ui.common.ColorSelectLabels18
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * What Color select counts as a selected color (v1.8, design §3.5): the tapped samples, or one of
 * one of the range popup's presets. The six hue presets carry their centre (degrees).
 */
@Serializable
enum class ColorPreset(val label: String, internal val hue: Float = -1f) {
    SAMPLED(ColorSelectLabels18.SAMPLED),
    REDS(ColorSelectLabels18.REDS, 0f),
    YELLOWS(ColorSelectLabels18.YELLOWS, 60f),
    GREENS(ColorSelectLabels18.GREENS, 120f),
    CYANS(ColorSelectLabels18.CYANS, 180f),
    BLUES(ColorSelectLabels18.BLUES, 240f),
    MAGENTAS(ColorSelectLabels18.MAGENTAS, 300f),
    HIGHLIGHTS(ColorSelectLabels18.HIGHLIGHTS),
    MIDTONES(ColorSelectLabels18.MIDTONES),
    SHADOWS(ColorSelectLabels18.SHADOWS),
    VIVID(ColorSelectLabels18.VIVID),
    MUTED(ColorSelectLabels18.MUTED),
    SKIN(ColorSelectLabels18.SKIN),
}

/**
 * Color select's math (v1.8, design §3.5 c), pure and thread-safe.
 *
 * - **Lab** (CIE 1976, D65) from sRGB through a 256-entry linearization table, the 3 × 3 matrix
 *   and a [F_BINS]-bin table of f(t) with linear interpolation (under 0.01 ΔE from the closed
 *   form). [labQ] rounds it to hundredths, the numbers both the preview's cache and the commit use.
 * - **Membership** 0..1 per color ([Params]): for sampled colors from the nearest sample's ΔE76
 *   (1 up to F / 2, 0 from F, smoothstep between; F = 0 only exact matches, d < 0.5); for the
 *   presets from HSV and Lab L in bands widened by the fuzziness. [member] inverts it ("Invert
 *   range"), then multiplies by the pixel's alpha (a transparent pixel is never selected) and,
 *   with anti-alias off, cuts it at half.
 */
object ColorRange {

    /** Bins of the f(t) table over t in 0..1. */
    const val F_BINS = 4096

    private const val EPS = 216.0 / 24389.0
    private const val KAPPA = 24389.0 / 27.0

    /** sRGB (D65) to XYZ, rows: X, Y, Z. */
    internal val RGB_TO_XYZ = doubleArrayOf(
        0.4124564, 0.3575761, 0.1804375,
        0.2126729, 0.7151522, 0.0721750,
        0.0193339, 0.1191920, 0.9503041,
    )

    /** The D65 white (X, Y, Z). */
    internal val WHITE = doubleArrayOf(0.95047, 1.0, 1.08883)

    /** sRGB 0..255 to linear light (closed form). */
    internal fun linear(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    /** CIE f(t) (closed form). */
    internal fun f(t: Double): Double = if (t > EPS) cbrt(t) else (KAPPA * t + 16.0) / 116.0

    private val LIN = FloatArray(256) { linear(it).toFloat() }
    private val M = FloatArray(9) { (RGB_TO_XYZ[it] / WHITE[it / 3]).toFloat() }
    private val F = FloatArray(F_BINS + 2) { f(min(1.0, it.toDouble() / F_BINS)).toFloat() }

    private fun ft(t: Float): Float {
        val x = (if (t < 0f) 0f else if (t > 1f) 1f else t) * F_BINS
        val i = x.toInt()
        return F[i] + (F[i + 1] - F[i]) * (x - i)
    }

    /** CIE Lab of [argb]'s color (its alpha ignored) into [out]: L 0..100, a, b. */
    fun lab(argb: Int, out: FloatArray) {
        val r = LIN[(argb shr 16) and 0xFF]
        val g = LIN[(argb shr 8) and 0xFF]
        val b = LIN[argb and 0xFF]
        val fx = ft(M[0] * r + M[1] * g + M[2] * b)
        val fy = ft(M[3] * r + M[4] * g + M[5] * b)
        val fz = ft(M[6] * r + M[7] * g + M[8] * b)
        out[0] = 116f * fy - 16f
        out[1] = 500f * (fx - fy)
        out[2] = 200f * (fy - fz)
    }

    /** [lab] in hundredths, rounded, packed as L (bits 32..47), a (16..31), b (0..15); see [qL], [qA], [qB]. */
    fun labQ(argb: Int): Long {
        val r = LIN[(argb shr 16) and 0xFF]
        val g = LIN[(argb shr 8) and 0xFF]
        val b = LIN[argb and 0xFF]
        val fx = ft(M[0] * r + M[1] * g + M[2] * b)
        val fy = ft(M[3] * r + M[4] * g + M[5] * b)
        val fz = ft(M[6] * r + M[7] * g + M[8] * b)
        val l = ((116f * fy - 16f) * 100f).roundToInt()
        val a = (500f * (fx - fy) * 100f).roundToInt()
        val bb = (200f * (fy - fz) * 100f).roundToInt()
        return ((l.toLong() and 0xFFFF) shl 32) or ((a.toLong() and 0xFFFF) shl 16) or (bb.toLong() and 0xFFFF)
    }

    fun qL(q: Long): Int = (q shr 32).toInt().toShort().toInt()
    fun qA(q: Long): Int = (q shr 16).toInt().toShort().toInt()
    fun qB(q: Long): Int = q.toInt().toShort().toInt()

    /** Smoothstep: 0 at or below [e0], 1 at or above [e1], cubic between. */
    internal fun smooth(e0: Float, e1: Float, x: Float): Float {
        if (x <= e0) return 0f
        if (x >= e1) return 1f
        val t = (x - e0) / (e1 - e0)
        return t * t * (3f - 2f * t)
    }

    /** 1 inside [lo]..[hi], 0 outside, each edge softened by ± [soft]. */
    private fun band(x: Float, lo: Float, hi: Float, soft: Float): Float =
        smooth(lo - soft, lo + soft, x) * (1f - smooth(hi - soft, hi + soft, x))

    /** The skin preset's hue edges are softened by w × this many degrees (w is a fraction of S, V and L). */
    const val SKIN_HUE_SOFT_DEGREES = 100f

    /**
     * What counts (design §3.5): [preset], [fuzziness] (0..200), the samples' [labQ] values (for
     * [ColorPreset.SAMPLED]), the invert option and anti-alias. Immutable.
     */
    class Params(
        val preset: ColorPreset,
        fuzziness: Int,
        /** Each sample's Lab in hundredths: L, a, b, L, a, b, ... */
        val samples: IntArray,
        val invert: Boolean = false,
        val antiAlias: Boolean = true,
    ) {
        val fuzziness: Int = fuzziness.coerceIn(0, MAX_FUZZINESS)
        internal val fz = this.fuzziness.toFloat()
        /** The presets' band softening. */
        internal val w = 0.05f + fz / 1000f
        internal val inner = 15f + 0.15f * fz
        internal val outer = 45f + 0.15f * fz
        /** Samples: full membership up to F / 2, none from F (hundredths of ΔE). */
        internal val near = fz / 2f * 100f
        internal val far = fz * 100f
        internal val near2 = near * near
        internal val far2 = far * far

        /** True when no color counts (sampled, no samples, not inverted): every pixel is out. */
        val selectsNothing: Boolean get() = preset == ColorPreset.SAMPLED && samples.isEmpty() && !invert

        companion object {
            const val MAX_FUZZINESS = 200

            /** Params for samples given as colors (ARGB, alpha ignored). */
            fun of(preset: ColorPreset, fuzziness: Int, colors: IntArray, invert: Boolean = false, antiAlias: Boolean = true): Params {
                val s = IntArray(colors.size * 3)
                for (i in colors.indices) {
                    val q = labQ(colors[i])
                    s[3 * i] = qL(q); s[3 * i + 1] = qA(q); s[3 * i + 2] = qB(q)
                }
                return Params(preset, fuzziness, s, invert, antiAlias)
            }
        }
    }

    /**
     * The selection value 0..255 of a pixel [argb] whose [labQ] is ([l], [a], [b]): its color's
     * membership (inverted when [Params.invert]) times its alpha; with anti-alias off, 0 or 255.
     */
    fun member(argb: Int, l: Int, a: Int, b: Int, p: Params): Int {
        val alpha = argb ushr 24
        if (alpha == 0) return 0
        var m = colorMember(argb, l, a, b, p)
        if (p.invert) m = 1f - m
        val v = m * alpha
        return if (p.antiAlias) (v + 0.5f).toInt() else if (v >= 127.5f) 255 else 0
    }

    /** The membership 0..1 of [argb]'s color (alpha ignored), Lab in hundredths ([l], [a], [b]). */
    fun colorMember(argb: Int, l: Int, a: Int, b: Int, p: Params): Float = when (p.preset) {
        ColorPreset.SAMPLED -> sampled(l, a, b, p)
        ColorPreset.HIGHLIGHTS -> highlights(l, p.w)
        ColorPreset.SHADOWS -> shadows(l, p.w)
        ColorPreset.MIDTONES -> (1f - highlights(l, p.w) - shadows(l, p.w)).coerceIn(0f, 1f)
        ColorPreset.VIVID -> vivid(argb, p.w)
        ColorPreset.MUTED -> 1f - vivid(argb, p.w)
        ColorPreset.SKIN -> skin(argb, p.w)
        else -> hueBand(argb, p)
    }

    private fun sampled(l: Int, a: Int, b: Int, p: Params): Float {
        val s = p.samples
        if (s.isEmpty()) return 0f
        var best = Int.MAX_VALUE
        var i = 0
        while (i < s.size) {
            val dl = l - s[i]; val da = a - s[i + 1]; val db = b - s[i + 2]
            val d2 = dl * dl + da * da + db * db
            if (d2 < best) best = d2
            i += 3
        }
        if (p.fuzziness == 0) return if (best < EXACT2) 1f else 0f
        val d2 = best.toFloat()
        if (d2 <= p.near2) return 1f
        if (d2 >= p.far2) return 0f
        return 1f - smooth(p.near, p.far, sqrt(d2))
    }

    /** (0.5 ΔE in hundredths)²: F = 0 counts colors nearer than this as the sample's. */
    private const val EXACT2 = 50 * 50

    private fun highlights(l: Int, w: Float): Float = smooth(0.65f - w, 0.65f + w, l / 10_000f)
    private fun shadows(l: Int, w: Float): Float = 1f - smooth(0.35f - w, 0.35f + w, l / 10_000f)

    private fun vivid(argb: Int, w: Float): Float {
        val r = (argb shr 16) and 0xFF; val g = (argb shr 8) and 0xFF; val b = argb and 0xFF
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        // S · V = (max − min) / max · max / 255.
        return smooth(0.45f - w, 0.45f + w, (mx - mn) / 255f)
    }

    /** HSV hue (degrees, 0..360) of r, g, b with [mx] / [mn] their max / min; 0 for grays. */
    private fun hue(r: Int, g: Int, b: Int, mx: Int, mn: Int): Float {
        val d = (mx - mn).toFloat()
        if (d == 0f) return 0f
        var h = when (mx) {
            r -> 60f * ((g - b) / d)
            g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        if (h < 0f) h += 360f
        return h
    }

    private fun hueBand(argb: Int, p: Params): Float {
        val r = (argb shr 16) and 0xFF; val g = (argb shr 8) and 0xFF; val b = argb and 0xFF
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        if (mx == 0) return 0f
        val s = (mx - mn).toFloat() / mx
        val grayness = smooth(0.05f, 0.2f, s)
        if (grayness == 0f) return 0f
        var d = abs(hue(r, g, b, mx, mn) - p.preset.hue)
        if (d > 180f) d = 360f - d
        return (1f - smooth(p.inner, p.outer, d)) * grayness
    }

    private fun skin(argb: Int, w: Float): Float {
        val r = (argb shr 16) and 0xFF; val g = (argb shr 8) and 0xFF; val b = argb and 0xFF
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        if (mx == 0) return 0f
        val s = (mx - mn).toFloat() / mx
        val v = mx / 255f
        var h = hue(r, g, b, mx, mn)
        if (h > 180f) h -= 360f
        return band(h, 5f, 50f, w * SKIN_HUE_SOFT_DEGREES) * band(s, 0.15f, 0.7f, w) * smooth(0.25f - w, 0.25f + w, v)
    }
}
