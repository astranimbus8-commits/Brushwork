package com.brushwork.paint.filters.adjust

import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterCategory
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterMath
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterValues
import com.brushwork.paint.filters.GradientStop
import com.brushwork.paint.filters.PixelMapper
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private const val ALPHA = 0xFF000000.toInt()

/**
 * Shifts the color cast separately in shadows, midtones and highlights (GIMP-style range masks
 * that sum to 1, so the same shift in all three ranges moves every tone equally). "Preserve
 * luminosity" restores each pixel's original Rec.601 luma afterwards.
 */
class ColorBalanceFilter : Filter("adjust.color_balance", "Color Balance", FilterCategory.ADJUST) {
    override val params: List<FilterParam> = buildList {
        for ((key, range) in RANGES) {
            add(FilterParam.Slider("${key}CR", "$range: Cyan – Red", -100f, 100f, 0f, 1f))
            add(FilterParam.Slider("${key}MG", "$range: Magenta – Green", -100f, 100f, 0f, 1f))
            add(FilterParam.Slider("${key}YB", "$range: Yellow – Blue", -100f, 100f, 0f, 1f))
        }
        add(FilterParam.Toggle("preserveLuminosity", "Preserve luminosity", true))
    }

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val m = mapperOrNull(values) ?: return src.copy()
        return AdjustMath.applyMapper(src, ctx, m)
    }

    override fun pixelMapper(values: FilterValues): PixelMapper = mapperOrNull(values) ?: AdjustMath.IDENTITY_MAPPER

    /** Null when every shift is 0 (nothing changes). */
    private fun mapperOrNull(values: FilterValues): PixelMapper? {
        fun v(key: String) = (values.float(key) / 100f).coerceIn(-1f, 1f)
        val cr = floatArrayOf(v("shadowsCR"), v("midtonesCR"), v("highlightsCR"))
        val mg = floatArrayOf(v("shadowsMG"), v("midtonesMG"), v("highlightsMG"))
        val yb = floatArrayOf(v("shadowsYB"), v("midtonesYB"), v("highlightsYB"))
        if (cr.all { it == 0f } && mg.all { it == 0f } && yb.all { it == 0f }) return null
        val preserve = values.bool("preserveLuminosity")
        // Shift per channel indexed by (max + min) of the 0..255 components, i.e. HSL lightness * 510.
        val shR = FloatArray(511); val shG = FloatArray(511); val shB = FloatArray(511)
        for (l2 in 0..510) {
            val wts = rangeWeights(l2 / 510f)
            shR[l2] = cr[0] * wts[0] + cr[1] * wts[1] + cr[2] * wts[2]
            shG[l2] = mg[0] * wts[0] + mg[1] * wts[1] + mg[2] * wts[2]
            shB[l2] = yb[0] * wts[0] + yb[1] * wts[1] + yb[2] * wts[2]
        }
        return AdjustMath.pointwise { c ->
            if (c ushr 24 == 0) c else {
                val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                val l2 = max(r, max(g, b)) + min(r, min(g, b))
                var rf = (r / 255f + shR[l2]).coerceIn(0f, 1f)
                var gf = (g / 255f + shG[l2]).coerceIn(0f, 1f)
                var bf = (b / 255f + shB[l2]).coerceIn(0f, 1f)
                if (preserve) {
                    // Restore the original luma; a second pass redistributes what clamping lost.
                    val y0 = (r * 0.299f + g * 0.587f + b * 0.114f) / 255f
                    repeat(2) {
                        val diff = y0 - (rf * 0.299f + gf * 0.587f + bf * 0.114f)
                        rf = (rf + diff).coerceIn(0f, 1f); gf = (gf + diff).coerceIn(0f, 1f); bf = (bf + diff).coerceIn(0f, 1f)
                    }
                }
                AdjustMath.pack(c ushr 24, rf, gf, bf)
            }
        }
    }

    companion object {
        private val RANGES = listOf("shadows" to "Shadows", "midtones" to "Midtones", "highlights" to "Highlights")

        /** Shadow / midtone / highlight weights for HSL lightness [l] (0..1), each scaled by 0.7. */
        fun rangeWeights(l: Float): FloatArray {
            val a = 0.25f; val b = 0.333f; val scale = 0.7f
            val s = ((l - b) / -a + 0.5f).coerceIn(0f, 1f)
            val m = ((l - b) / a + 0.5f).coerceIn(0f, 1f) * ((l + b - 1f) / -a + 0.5f).coerceIn(0f, 1f)
            val h = ((l + b - 1f) / a + 0.5f).coerceIn(0f, 1f)
            return floatArrayOf(s * scale, m * scale, h * scale)
        }
    }
}

/**
 * Hue rotation, saturation and brightness. In Colorize mode every pixel gets the hue from the Hue
 * slider (0° = red) and a fixed saturation (the Saturation slider maps -100..100 to 0..100 %) while
 * keeping its luminance, like a toned photograph.
 */
class HueSaturationFilter : Filter("adjust.hue_saturation", "Hue / Saturation / Brightness", FilterCategory.ADJUST) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Slider("hue", "Hue", -180f, 180f, 0f, 1f, "°"),
        FilterParam.Slider("saturation", "Saturation", -100f, 100f, 0f, 1f),
        FilterParam.Slider("brightness", "Brightness", -100f, 100f, 0f, 1f),
        FilterParam.Toggle("colorize", "Colorize", false),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val m = mapperOrNull(values) ?: return src.copy()
        return AdjustMath.applyMapper(src, ctx, m)
    }

    override fun pixelMapper(values: FilterValues): PixelMapper = mapperOrNull(values) ?: AdjustMath.IDENTITY_MAPPER

    /** Null when nothing changes. Each [PixelMapper.map] call uses its own scratch arrays (thread-safe). */
    private fun mapperOrNull(values: FilterValues): PixelMapper? {
        val hue = values.float("hue")
        val sat = (values.float("saturation") / 100f).coerceIn(-1f, 1f)
        val light = (values.float("brightness") / 100f).coerceIn(-1f, 1f)
        if (values.bool("colorize")) {
            val h = (((hue % 360f) + 360f) % 360f) / 360f
            val s = (sat + 1f) * 0.5f
            return PixelMapper { px, from, until ->
                val rgb = FloatArray(3)
                for (i in from until until) {
                    val c = px[i]
                    if (c ushr 24 == 0) continue
                    var l = AdjustMath.luma(c) / 255f
                    l = if (light > 0f) l * (1f - light) + light else l * (1f + light)
                    AdjustMath.hslToRgb(h, s, l, rgb)
                    px[i] = AdjustMath.pack(c ushr 24, rgb[0], rgb[1], rgb[2])
                }
            }
        }
        if (hue == 0f && sat == 0f && light == 0f) return null
        val shift = hue / 360f
        return PixelMapper { px, from, until ->
            val rgb = FloatArray(3); val hsl = FloatArray(3)
            for (i in from until until) {
                val c = px[i]
                if (c ushr 24 == 0) continue
                rgb[0] = ((c shr 16) and 0xFF) / 255f; rgb[1] = ((c shr 8) and 0xFF) / 255f; rgb[2] = (c and 0xFF) / 255f
                AdjustMath.adjustHsb(rgb, hsl, shift, sat, light)
                px[i] = AdjustMath.pack(c ushr 24, rgb[0], rgb[1], rgb[2])
            }
        }
    }
}

/**
 * Replaces one color (picked under a reference point on the canvas, or chosen directly) and the
 * colors near it. Tolerance sets how different a color may be (chroma counts more than
 * lightness, so shading on the same object is caught), Soft edge feathers the transition.
 * "Shift" moves hue/saturation/brightness of matching pixels; "Color" maps the reference color
 * onto the replacement color while keeping the shading of every pixel; "Solid" paints the flat
 * replacement color. The H/S/B sliders fine-tune the replacement in the last two modes.
 */
class ReplaceColorFilter : Filter("adjust.replace_color", "Replace Color", FilterCategory.ADJUST) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Choice("source", "Target from", listOf("Reference point", "Target color"), 0),
        FilterParam.Point("point", "Reference point"),
        FilterParam.Color("target", "Target color", 0xFFF2C12E.toInt()),
        FilterParam.Slider("tolerance", "Tolerance", 0f, 100f, 40f, 1f, "%"),
        FilterParam.Slider("softness", "Soft edge", 0f, 100f, 50f, 1f, "%"),
        FilterParam.Choice("mode", "Replace with", listOf("Shift", "Color", "Solid"), 1),
        FilterParam.Color("replacement", "Replacement color", 0xFFE53935.toInt()),
        FilterParam.Slider("hue", "Hue", -180f, 180f, 0f, 1f, "°"),
        FilterParam.Slider("saturation", "Saturation", -100f, 100f, 0f, 1f),
        FilterParam.Slider("brightness", "Brightness", -100f, 100f, 0f, 1f),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val ref = if (values.choice("source") == 0) sampleReference(src, values.point("point"), ctx) ?: return src.copy()
        else values.color("target") or ALPHA
        val m = mapperFor(ref, values) ?: return src.copy()
        return AdjustMath.applyMapper(src, ctx, m)
    }

    /**
     * Pointwise with a chosen "Target color"; a reference point reads the image there (content
     * dependent: no mapper).
     */
    override fun pixelMapper(values: FilterValues): PixelMapper? {
        if (values.choice("source") == 0) return null
        return mapperFor(values.color("target") or ALPHA, values) ?: AdjustMath.IDENTITY_MAPPER
    }

    /** The replacement of colors near [ref]; null when it changes nothing. */
    private fun mapperFor(ref: Int, values: FilterValues): PixelMapper? {
        val tol = (values.float("tolerance") / 100f).coerceIn(0f, 1f) * MAX_DISTANCE
        val inner = tol * (1f - (values.float("softness") / 100f).coerceIn(0f, 1f))
        val tol2 = tol * tol; val inner2 = inner * inner
        val hueShift = values.float("hue") / 360f
        val sat = (values.float("saturation") / 100f).coerceIn(-1f, 1f)
        val light = (values.float("brightness") / 100f).coerceIn(-1f, 1f)
        val mode = values.choice("mode")
        if (mode == 0 && hueShift == 0f && sat == 0f && light == 0f) return null

        val rY = AdjustMath.luma(ref) / 255f
        val rCb = ((ref and 0xFF) / 255f - rY) * CB
        val rCr = (((ref shr 16) and 0xFF) / 255f - rY) * CR

        // Replacement color after the fine-tune sliders, and its HSL relative to the reference.
        val tmp = FloatArray(3); val tmpHsl = FloatArray(3)
        val repl = values.color("replacement")
        tmp[0] = ((repl shr 16) and 0xFF) / 255f; tmp[1] = ((repl shr 8) and 0xFF) / 255f; tmp[2] = (repl and 0xFF) / 255f
        AdjustMath.adjustHsb(tmp, tmpHsl, hueShift, sat, light)
        val solidR = tmp[0]; val solidG = tmp[1]; val solidB = tmp[2]
        AdjustMath.rgbToHsl(solidR, solidG, solidB, tmpHsl)
        val tH = tmpHsl[0]; val tS = tmpHsl[1]; val tL = tmpHsl[2]
        AdjustMath.rgbToHsl(((ref shr 16) and 0xFF) / 255f, ((ref shr 8) and 0xFF) / 255f, (ref and 0xFF) / 255f, tmpHsl)
        val refH = tmpHsl[0]; val refS = tmpHsl[1]; val refL = tmpHsl[2]
        val dS = tS - refS; val dL = tL - refL

        return PixelMapper { px, from, until ->
            val rgb = FloatArray(3); val hsl = FloatArray(3)
            for (i in from until until) {
                val c = px[i]
                val a = c ushr 24
                if (a == 0) continue
                val r = ((c shr 16) and 0xFF) / 255f; val g = ((c shr 8) and 0xFF) / 255f; val b = (c and 0xFF) / 255f
                val y = r * 0.299f + g * 0.587f + b * 0.114f
                val dy = y - rY; val dcb = (b - y) * CB - rCb; val dcr = (r - y) * CR - rCr
                val dist2 = LUMA_WEIGHT * dy * dy + dcb * dcb + dcr * dcr
                val weight = when {
                    dist2 <= inner2 -> 1f
                    dist2 >= tol2 -> 0f
                    else -> 1f - AdjustMath.smoothstep(inner, tol, sqrt(dist2))
                }
                if (weight <= 0f) continue
                when (mode) {
                    0 -> {
                        rgb[0] = r; rgb[1] = g; rgb[2] = b
                        AdjustMath.adjustHsb(rgb, hsl, hueShift, sat, light)
                    }
                    1 -> {
                        AdjustMath.rgbToHsl(r, g, b, hsl)
                        val baseH = if (hsl[1] < GRAY_S) refH else hsl[0]
                        val h = if (refS < GRAY_S) tH else baseH + (tH - refH)
                        AdjustMath.hslToRgb(h, hsl[1] + dS, hsl[2] + dL, rgb)
                    }
                    else -> { rgb[0] = solidR; rgb[1] = solidG; rgb[2] = solidB }
                }
                px[i] = AdjustMath.pack(a, r + (rgb[0] - r) * weight, g + (rgb[1] - g) * weight, b + (rgb[2] - b) * weight)
            }
        }
    }

    companion object {
        /** Weighted YCbCr distance at 100 % tolerance. */
        const val MAX_DISTANCE = 0.8f
        private const val LUMA_WEIGHT = 0.3f
        private const val CB = 0.564f
        private const val CR = 0.713f
        private const val GRAY_S = 0.02f

        /**
         * Alpha-weighted average color in a 5×5 (full-resolution) window around the normalized
         * [point], opaque; null if the window is fully transparent.
         */
        fun sampleReference(src: PixelBuffer, point: FloatArray, ctx: FilterContext): Int? {
            val px = point.getOrElse(0) { 0.5f }; val py = point.getOrElse(1) { 0.5f }
            val cx = (px * src.width).toInt().coerceIn(0, src.width - 1)
            val cy = (py * src.height).toInt().coerceIn(0, src.height - 1)
            // Rounded down so a downscaled preview doesn't average a wider area than the final pass.
            val rad = ctx.px(2f).toInt().coerceIn(0, 8)
            var sa = 0L; var sr = 0L; var sg = 0L; var sb = 0L
            for (y in max(0, cy - rad)..min(src.height - 1, cy + rad)) for (x in max(0, cx - rad)..min(src.width - 1, cx + rad)) {
                val c = src[x, y]
                val a = c ushr 24
                sa += a; sr += ((c shr 16) and 0xFF) * a; sg += ((c shr 8) and 0xFF) * a; sb += (c and 0xFF) * a
            }
            if (sa == 0L) return null
            return ColorUtils.argb(255, ((sr + sa / 2) / sa).toInt(), ((sg + sa / 2) / sa).toInt(), ((sb + sa / 2) / sa).toInt())
        }
    }
}

/**
 * Recolors by luminance through a gradient: dark pixels take the colors at the left of the strip,
 * bright pixels those at the right. Stop alpha scales the pixel's alpha.
 */
class GradationMapFilter : Filter("adjust.gradation_map", "Gradation Map", FilterCategory.ADJUST) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Gradient(
            "gradient", "Gradation",
            listOf(
                GradientStop(0f, 0xFF14213D.toInt()),
                GradientStop(0.45f, 0xFFB5476B.toInt()),
                GradientStop(0.8f, 0xFFF4A259.toInt()),
                GradientStop(1f, 0xFFFFF1D0.toInt()),
            ),
        ),
        FilterParam.Slider("opacity", "Opacity", 0f, 100f, 100f, 1f, "%"),
        FilterParam.Toggle("reverse", "Reverse", false),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val m = mapperOrNull(values) ?: return src.copy()
        return AdjustMath.applyMapper(src, ctx, m)
    }

    override fun pixelMapper(values: FilterValues): PixelMapper = mapperOrNull(values) ?: AdjustMath.IDENTITY_MAPPER

    /** Null at 0 % opacity (nothing changes). */
    private fun mapperOrNull(values: FilterValues): PixelMapper? {
        val opacity = (values.float("opacity") / 100f).coerceIn(0f, 1f)
        if (opacity <= 0f) return null
        val lut = AdjustMath.gradientLut(values.gradient("gradient"))
        if (values.bool("reverse")) lut.reverse()
        return AdjustMath.pointwise { c ->
            val a = c ushr 24
            if (a == 0) c else {
                val m = lut[ColorUtils.luminance(c)]
                val mapped = (m and 0xFFFFFF) or (((a * (m ushr 24) + 127) / 255) shl 24)
                if (opacity >= 1f) mapped else ColorUtils.lerp(c, mapped, opacity)
            }
        }
    }
}

/**
 * Turns the layer into shades of one color while keeping every pixel's luminance and alpha:
 * luminance runs black -> color -> white, with the chosen color placed at its own luminance (a
 * pixel as bright as the color becomes exactly that color). Black or white give plain grayscale.
 * The color starts at the current drawing color.
 */
class MonocolorFilter : Filter("adjust.monocolor", "Monocolor", FilterCategory.ADJUST) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Color("color", "Color", 0xFF9C6B3C.toInt(), useDrawingColor = true),
        FilterParam.Slider("amount", "Strength", 0f, 100f, 100f, 1f, "%"),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val m = mapperOrNull(values) ?: return src.copy()
        return AdjustMath.applyMapper(src, ctx, m)
    }

    override fun pixelMapper(values: FilterValues): PixelMapper = mapperOrNull(values) ?: AdjustMath.IDENTITY_MAPPER

    /** Null at 0 % strength (nothing changes). */
    private fun mapperOrNull(values: FilterValues): PixelMapper? {
        val amount = (values.float("amount") / 100f).coerceIn(0f, 1f)
        if (amount <= 0f) return null
        val lut = rampLut(values.color("color"))
        return AdjustMath.pointwise { c ->
            val a = c ushr 24
            if (a == 0) c else {
                val m = (lut[ColorUtils.luminance(c)] and 0xFFFFFF) or (a shl 24)
                if (amount >= 1f) m else ColorUtils.lerp(c, m, amount)
            }
        }
    }

    companion object {
        /**
         * Opaque RGB for every luminance 0..255 along the black -> [color] -> white ramp, with
         * [color] at its own Rec.601 luminance. The ramp is linear in RGB on both sides, so the
         * output luminance equals the input luminance.
         */
        fun rampLut(color: Int): IntArray {
            val cr = ((color shr 16) and 0xFF) / 255f; val cg = ((color shr 8) and 0xFF) / 255f; val cb = (color and 0xFF) / 255f
            val pivot = AdjustMath.luma(color) / 255f
            return IntArray(256) { i ->
                val y = i / 255f
                if (y <= pivot) {
                    // pivot > 0 here unless y == 0, where black is the right answer anyway.
                    val k = if (pivot > 0f) y / pivot else 0f
                    AdjustMath.pack(255, cr * k, cg * k, cb * k)
                } else {
                    val k = (y - pivot) / (1f - pivot)
                    AdjustMath.pack(255, cr + (1f - cr) * k, cg + (1f - cg) * k, cb + (1f - cb) * k)
                }
            }
        }
    }
}

/**
 * Paints everything on the layer in one color, keeping its shape and anti-aliased alpha (like
 * filling an alpha-locked layer). The color starts at the current drawing color.
 */
class ChangeDrawingColorFilter : Filter("adjust.change_drawing_color", "Change Drawing Color", FilterCategory.ADJUST) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Color("color", "Color", 0xFF1E5BD8.toInt(), useDrawingColor = true),
        FilterParam.Slider("amount", "Strength", 0f, 100f, 100f, 1f, "%"),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val amount = (values.float("amount") / 100f).coerceIn(0f, 1f)
        if (amount <= 0f) return src.copy()
        val rgb = values.color("color") and 0xFFFFFF
        return FilterMath.mapPixels(src, ctx) { c ->
            val a = c ushr 24
            if (a == 0) c else {
                val m = rgb or (a shl 24)
                if (amount >= 1f) m else ColorUtils.lerp(c, m, amount)
            }
        }
    }
}
