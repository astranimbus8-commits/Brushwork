package com.brushwork.paint.filters.art

import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.core.Parallel
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterValues
import com.brushwork.paint.filters.draw.DrawBlend
import com.brushwork.paint.model.LayerBlendMode

/**
 * v1.9 (R10, D19): the chromatic-aberration filters' "Blend mode" and "Opacity". The aberrated
 * picture is put onto the original per pixel with one of the layers panel's 17 modes
 * ([LayerBlendMode], its order and names) at the opacity: the modes [DrawBlend] has through
 * [DrawBlend.composite] itself, Exclusion with its W3C formula, and Hue, Saturation, Color and
 * Luminosity with the W3C Compositing Level 1 functions Lum, ClipColor, SetLum, Sat and SetSat
 * (non-premultiplied colors, as [DrawBlend]). No platform blend mode is used: below API 29 the
 * platform has none of the last five (fact V8).
 *
 * At Normal and 100 % (the defaults, and a session or test whose values lack the two keys) the
 * pass is skipped, so the output is v1.8's byte for byte. The values live only in the session.
 */
object CaBlend {
    /** The modes in the layers panel's order; the dropdown's rows are their labels. */
    val MODES: List<LayerBlendMode> = LayerBlendMode.entries

    /** "Blend mode": a choice of [MODES] by label (a dropdown, as the layers panel), default Normal. */
    val modeParam: FilterParam.Choice =
        DrawBlend.param().copy(options = MODES.map { it.label }, default = LayerBlendMode.NORMAL.ordinal)

    /** "Opacity": 0-100 %, default 100 ([DrawBlend.opacityParam]). */
    val opacityParam: FilterParam.Slider = DrawBlend.opacityParam(100f)

    /** Whether [p] is the CA "Blend mode" choice (the panel shows it as a dropdown). */
    fun isModeChoice(p: FilterParam): Boolean = p is FilterParam.Choice && p.key == modeParam.key && p.options == modeParam.options

    /** The mode [values] hold; a missing or unknown value is Normal (v1.8). */
    fun modeOf(values: FilterValues): LayerBlendMode =
        (values.raw(modeParam.key) as? Number)?.toInt()?.let { MODES.getOrNull(it) } ?: LayerBlendMode.NORMAL

    /** The opacity [values] hold, 0..1; a missing value is 100 % (v1.8). */
    fun opacityOf(values: FilterValues): Float =
        ((values.raw(opacityParam.key) as? Number)?.toFloat() ?: opacityParam.default).coerceIn(0f, 100f) / 100f

    /** True when [values] leave the aberrated picture as it is (Normal at 100 %): the pass is skipped. */
    fun isIdentity(values: FilterValues): Boolean = modeOf(values) == LayerBlendMode.NORMAL && opacityOf(values) >= 1f

    /**
     * [aberrated] put onto [original] (same size) with the mode and opacity of [values]: [aberrated]
     * itself when [isIdentity], else written into [aberrated]'s pixels and returned. The mode is
     * resolved once per pass, so each pixel runs only its mode's arithmetic.
     */
    fun over(original: PixelBuffer, aberrated: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        if (isIdentity(values)) return aberrated
        val mode = modeOf(values)
        val cov = opacityOf(values)
        val o = original.pixels; val a = aberrated.pixels; val w = original.width
        val drawMode = drawModeOf(mode)
        val own = ownCode(mode)
        Parallel.forRows(original.height) { y0, y1 ->
            ctx.checkCancelled()
            if (drawMode >= 0) {
                for (i in y0 * w until y1 * w) a[i] = DrawBlend.composite(o[i], a[i], cov, drawMode)
            } else {
                for (i in y0 * w until y1 * w) a[i] = compositeOwn(o[i], a[i], cov, own)
            }
        }
        return aberrated
    }

    /**
     * Composites [src] (ARGB, its alpha scaled by [coverage] 0..1) onto [dst] with [mode], as
     * [DrawBlend.composite] does: the modes it has are its own results.
     */
    fun composite(dst: Int, src: Int, coverage: Float, mode: LayerBlendMode): Int {
        val drawMode = drawModeOf(mode)
        return if (drawMode >= 0) DrawBlend.composite(dst, src, coverage, drawMode) else compositeOwn(dst, src, coverage, ownCode(mode))
    }

    /** Exclusion and the four non-separable modes as [composite] puts them ([ownCode]'s codes). */
    private fun compositeOwn(dst: Int, src: Int, coverage: Float, own: Int): Int {
        if (!(coverage > 0f)) return dst
        val cov = if (coverage > 1f) 1f else coverage
        val sa = (src ushr 24) * (cov * INV255)
        if (sa <= 0f) return dst
        val daI = dst ushr 24
        if (daI == 0) return ColorUtils.withAlpha(src, ColorUtils.clamp255(sa * 255f))
        // Hue, Saturation, Color and Luminosity of a color with itself give the color: an opaque
        // backdrop under its own color stays as it is (the flat parts of a picture).
        if (src == dst && daI == 255 && own != OWN_EXCLUSION) return dst
        val sr = ((src shr 16) and 0xFF) * INV255
        val sg = ((src shr 8) and 0xFF) * INV255
        val sb = (src and 0xFF) * INV255
        val dr = ((dst shr 16) and 0xFF) * INV255
        val dg = ((dst shr 8) and 0xFF) * INV255
        val db = (dst and 0xFF) * INV255
        // B(backdrop, source) for the three channels at once.
        var br: Float; var bg: Float; var bb: Float
        if (own == OWN_EXCLUSION) {
            br = dr + sr - 2f * dr * sr; bg = dg + sg - 2f * dg * sg; bb = db + sb - 2f * db * sb
        } else {
            // The non-separable modes: Hue, Saturation, Color, Luminosity.
            var cr: Float; var cg: Float; var cb: Float
            val lum: Float
            when (own) {
                OWN_HUE -> {
                    // SetLum(SetSat(Cs, Sat(Cb)), Lum(Cb))
                    val mn = min3(sr, sg, sb)
                    val k = satScale(sr, sg, sb, max3(dr, dg, db) - min3(dr, dg, db))
                    cr = (sr - mn) * k; cg = (sg - mn) * k; cb = (sb - mn) * k; lum = lum(dr, dg, db)
                }
                OWN_SATURATION -> {
                    // SetLum(SetSat(Cb, Sat(Cs)), Lum(Cb))
                    val mn = min3(dr, dg, db)
                    val k = satScale(dr, dg, db, max3(sr, sg, sb) - min3(sr, sg, sb))
                    cr = (dr - mn) * k; cg = (dg - mn) * k; cb = (db - mn) * k; lum = lum(dr, dg, db)
                }
                OWN_COLOR -> { cr = sr; cg = sg; cb = sb; lum = lum(dr, dg, db) } // SetLum(Cs, Lum(Cb))
                else -> { cr = dr; cg = dg; cb = db; lum = lum(sr, sg, sb) } // Luminosity: SetLum(Cb, Lum(Cs))
            }
            // SetLum(C, lum) with ClipColor. Lum(C + d) is lum (Lum is linear and its weights sum
            // to 1), so it is not computed again. A grey just outside 0..1 (float rounding: l equals
            // the channel it is clipped by) keeps l, so no 0 / 0 reaches the channels.
            val d = lum - lum(cr, cg, cb)
            cr += d; cg += d; cb += d
            val l = lum
            val n = min3(cr, cg, cb); val x = max3(cr, cg, cb)
            if (n < 0f) {
                val span = l - n
                val k = if (span > CLIP_EPS) l / span else 0f
                cr = l + (cr - l) * k; cg = l + (cg - l) * k; cb = l + (cb - l) * k
            }
            if (x > 1f) {
                val span = x - l
                val k = if (span > CLIP_EPS) (1f - l) / span else 0f
                cr = l + (cr - l) * k; cg = l + (cg - l) * k; cb = l + (cb - l) * k
            }
            br = cr; bg = cg; bb = cb
        }
        if (daI == 255) {
            // An opaque backdrop: the result is opaque, and the blend is B where the source covers.
            val kd = 1f - sa
            return ColorUtils.argbUnchecked(
                255,
                ColorUtils.clamp255((br * sa + dr * kd) * 255f),
                ColorUtils.clamp255((bg * sa + dg * kd) * 255f),
                ColorUtils.clamp255((bb * sa + db * kd) * 255f),
            )
        }
        // Source-over with the blend where the backdrop is (W3C: Cs' = (1 - αb)·Cs + αb·B).
        val da = daI * INV255
        val keep = 1f - da
        br = keep * sr + da * br
        bg = keep * sg + da * bg
        bb = keep * sb + da * bb
        val oa = sa + da * (1f - sa)
        val kd = da * (1f - sa)
        val inv = 255f / oa
        return ColorUtils.argbUnchecked(
            ColorUtils.clamp255(oa * 255f),
            ColorUtils.clamp255((br * sa + dr * kd) * inv),
            ColorUtils.clamp255((bg * sa + dg * kd) * inv),
            ColorUtils.clamp255((bb * sa + db * kd) * inv),
        )
    }

    /** [DrawBlend]'s mode for [mode], or -1 for the five it does not have. */
    fun drawModeOf(mode: LayerBlendMode): Int = when (mode) {
        LayerBlendMode.NORMAL -> DrawBlend.NORMAL
        LayerBlendMode.MULTIPLY -> DrawBlend.MULTIPLY
        LayerBlendMode.SCREEN -> DrawBlend.SCREEN
        LayerBlendMode.OVERLAY -> DrawBlend.OVERLAY
        LayerBlendMode.DARKEN -> DrawBlend.DARKEN
        LayerBlendMode.LIGHTEN -> DrawBlend.LIGHTEN
        LayerBlendMode.COLOR_DODGE -> DrawBlend.COLOR_DODGE
        LayerBlendMode.COLOR_BURN -> DrawBlend.COLOR_BURN
        LayerBlendMode.HARD_LIGHT -> DrawBlend.HARD_LIGHT
        LayerBlendMode.SOFT_LIGHT -> DrawBlend.SOFT_LIGHT
        LayerBlendMode.DIFFERENCE -> DrawBlend.DIFFERENCE
        LayerBlendMode.ADD -> DrawBlend.ADD
        LayerBlendMode.EXCLUSION, LayerBlendMode.HUE, LayerBlendMode.SATURATION,
        LayerBlendMode.COLOR, LayerBlendMode.LUMINOSITY -> -1
    }

    /** The code [compositeOwn] takes for one of the five modes [DrawBlend] does not have, else -1. */
    private fun ownCode(mode: LayerBlendMode): Int = when (mode) {
        LayerBlendMode.EXCLUSION -> OWN_EXCLUSION
        LayerBlendMode.HUE -> OWN_HUE
        LayerBlendMode.SATURATION -> OWN_SATURATION
        LayerBlendMode.COLOR -> OWN_COLOR
        LayerBlendMode.LUMINOSITY -> OWN_LUMINOSITY
        else -> -1
    }

    /** W3C Lum. */
    fun lum(r: Float, g: Float, b: Float): Float = 0.3f * r + 0.59f * g + 0.11f * b

    /** W3C Sat. */
    fun sat(r: Float, g: Float, b: Float): Float = max3(r, g, b) - min3(r, g, b)

    /**
     * W3C SetSat as one scale: SetSat(C, s) is `(C - min(C)) · satScale(C, s)` per channel (Cmax
     * becomes s, Cmin 0, Cmid `(Cmid - Cmin)·s / (Cmax - Cmin)`; a grey gives 0, 0, 0).
     */
    fun satScale(r: Float, g: Float, b: Float, s: Float): Float {
        val mx = max3(r, g, b); val mn = min3(r, g, b)
        return if (mx > mn) s / (mx - mn) else 0f
    }

    // Math.min and Math.max: one instruction each on the phone's ARM64 (and branch-free on the
    // JVM), where comparisons of a picture's unordered channels mispredict.
    @Suppress("NOTHING_TO_INLINE")
    private inline fun min3(a: Float, b: Float, c: Float): Float = Math.min(a, Math.min(b, c))

    @Suppress("NOTHING_TO_INLINE")
    private inline fun max3(a: Float, b: Float, c: Float): Float = Math.max(a, Math.max(b, c))

    private const val INV255 = 1f / 255f
    private const val CLIP_EPS = 1e-6f
    private const val OWN_EXCLUSION = 0
    private const val OWN_HUE = 1
    private const val OWN_SATURATION = 2
    private const val OWN_COLOR = 3
    private const val OWN_LUMINOSITY = 4
}
