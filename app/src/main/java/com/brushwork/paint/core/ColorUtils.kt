package com.brushwork.paint.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure-Kotlin color helpers (no android.graphics dependency, so they work in JVM unit tests).
 * Colors are packed 0xAARRGGBB ints, NON-premultiplied unless a function says otherwise.
 */
object ColorUtils {
    @JvmStatic fun alpha(c: Int): Int = c ushr 24
    @JvmStatic fun red(c: Int): Int = (c shr 16) and 0xFF
    @JvmStatic fun green(c: Int): Int = (c shr 8) and 0xFF
    @JvmStatic fun blue(c: Int): Int = c and 0xFF

    @JvmStatic fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (clamp255(a) shl 24) or (clamp255(r) shl 16) or (clamp255(g) shl 8) or clamp255(b)

    /** Same as [argb] but assumes components are already in 0..255 (no clamping). */
    @JvmStatic fun argbUnchecked(a: Int, r: Int, g: Int, b: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    @JvmStatic fun rgb(r: Int, g: Int, b: Int): Int = argb(255, r, g, b)

    @JvmStatic fun clamp255(v: Int): Int = if (v < 0) 0 else if (v > 255) 255 else v
    @JvmStatic fun clamp255(v: Float): Int = if (v <= 0f) 0 else if (v >= 255f) 255 else (v + 0.5f).toInt()
    @JvmStatic fun clamp255(v: Double): Int = if (v <= 0.0) 0 else if (v >= 255.0) 255 else (v + 0.5).toInt()
    @JvmStatic fun clamp01(v: Float): Float = if (v < 0f) 0f else if (v > 1f) 1f else v

    @JvmStatic fun withAlpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or (clamp255(a) shl 24)

    /** Rec.601 luma, 0..255. */
    @JvmStatic fun luminance(c: Int): Int = (red(c) * 299 + green(c) * 587 + blue(c) * 114 + 500) / 1000

    @JvmStatic fun luminance(r: Int, g: Int, b: Int): Int = (r * 299 + g * 587 + b * 114 + 500) / 1000

    @JvmStatic fun gray(v: Int, a: Int = 255): Int { val g = clamp255(v); return argbUnchecked(clamp255(a), g, g, g) }

    /** Linear interpolation of all four channels. t in 0..1. */
    @JvmStatic fun lerp(c1: Int, c2: Int, t: Float): Int {
        val u = clamp01(t)
        return argb(
            (alpha(c1) + (alpha(c2) - alpha(c1)) * u).roundToInt(),
            (red(c1) + (red(c2) - red(c1)) * u).roundToInt(),
            (green(c1) + (green(c2) - green(c1)) * u).roundToInt(),
            (blue(c1) + (blue(c2) - blue(c1)) * u).roundToInt(),
        )
    }

    @JvmStatic fun premultiply(c: Int): Int {
        val a = alpha(c)
        if (a == 255) return c
        if (a == 0) return 0
        return argbUnchecked(a, (red(c) * a + 127) / 255, (green(c) * a + 127) / 255, (blue(c) * a + 127) / 255)
    }

    @JvmStatic fun unpremultiply(c: Int): Int {
        val a = alpha(c)
        if (a == 255) return c
        if (a == 0) return 0
        return argbUnchecked(
            a,
            min(255, (red(c) * 255 + a / 2) / a),
            min(255, (green(c) * 255 + a / 2) / a),
            min(255, (blue(c) * 255 + a / 2) / a),
        )
    }

    /**
     * Source-over composite of two NON-premultiplied colors ([src] over [dst]).
     */
    @JvmStatic fun over(src: Int, dst: Int): Int {
        val sa = alpha(src) / 255f
        if (sa >= 1f) return src
        if (sa <= 0f) return dst
        val da = alpha(dst) / 255f
        val oa = sa + da * (1f - sa)
        if (oa <= 0f) return 0
        fun ch(s: Int, d: Int) = ((s * sa + d * da * (1f - sa)) / oa)
        return argb((oa * 255f).roundToInt(), ch(red(src), red(dst)).roundToInt(), ch(green(src), green(dst)).roundToInt(), ch(blue(src), blue(dst)).roundToInt())
    }

    /** RGB (0..255) to HSV: h in [0,360), s and v in [0,1]. Writes into [out] (size >= 3). */
    @JvmStatic fun rgbToHsv(r: Int, g: Int, b: Int, out: FloatArray) {
        val rf = r / 255f; val gf = g / 255f; val bf = b / 255f
        val mx = max(rf, max(gf, bf)); val mn = min(rf, min(gf, bf))
        val d = mx - mn
        var h = when {
            d == 0f -> 0f
            mx == rf -> 60f * (((gf - bf) / d) % 6f)
            mx == gf -> 60f * (((bf - rf) / d) + 2f)
            else -> 60f * (((rf - gf) / d) + 4f)
        }
        if (h < 0f) h += 360f
        out[0] = h
        out[1] = if (mx == 0f) 0f else d / mx
        out[2] = mx
    }

    @JvmStatic fun colorToHsv(c: Int, out: FloatArray) = rgbToHsv(red(c), green(c), blue(c), out)

    /** HSV (h degrees, s/v 0..1) to opaque-or-[alpha] ARGB. */
    @JvmStatic fun hsvToColor(h: Float, s: Float, v: Float, alpha: Int = 255): Int {
        val hh = ((h % 360f) + 360f) % 360f
        val ss = clamp01(s); val vv = clamp01(v)
        val c = vv * ss
        val x = c * (1f - abs((hh / 60f) % 2f - 1f))
        val m = vv - c
        val (r1, g1, b1) = when {
            hh < 60f -> Triple(c, x, 0f)
            hh < 120f -> Triple(x, c, 0f)
            hh < 180f -> Triple(0f, c, x)
            hh < 240f -> Triple(0f, x, c)
            hh < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return argb(alpha, ((r1 + m) * 255f).roundToInt(), ((g1 + m) * 255f).roundToInt(), ((b1 + m) * 255f).roundToInt())
    }

    /** RGB to HSL: h [0,360), s,l [0,1]. */
    @JvmStatic fun rgbToHsl(r: Int, g: Int, b: Int, out: FloatArray) {
        val rf = r / 255f; val gf = g / 255f; val bf = b / 255f
        val mx = max(rf, max(gf, bf)); val mn = min(rf, min(gf, bf))
        val l = (mx + mn) / 2f
        val d = mx - mn
        var h = 0f
        var s = 0f
        if (d != 0f) {
            s = if (l > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
            h = when (mx) {
                rf -> ((gf - bf) / d + (if (gf < bf) 6f else 0f))
                gf -> ((bf - rf) / d + 2f)
                else -> ((rf - gf) / d + 4f)
            } * 60f
        }
        out[0] = h; out[1] = s; out[2] = l
    }

    @JvmStatic fun hslToColor(h: Float, s: Float, l: Float, alpha: Int = 255): Int {
        val hh = (((h % 360f) + 360f) % 360f) / 360f
        val ss = clamp01(s); val ll = clamp01(l)
        if (ss == 0f) { val v = (ll * 255f).roundToInt(); return argb(alpha, v, v, v) }
        val q = if (ll < 0.5f) ll * (1f + ss) else ll + ss - ll * ss
        val p = 2f * ll - q
        fun hue2rgb(t0: Float): Float {
            var t = t0
            if (t < 0f) t += 1f
            if (t > 1f) t -= 1f
            return when {
                t < 1f / 6f -> p + (q - p) * 6f * t
                t < 1f / 2f -> q
                t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
                else -> p
            }
        }
        return argb(alpha, (hue2rgb(hh + 1f / 3f) * 255f).roundToInt(), (hue2rgb(hh) * 255f).roundToInt(), (hue2rgb(hh - 1f / 3f) * 255f).roundToInt())
    }

    /** "#RRGGBB" (alpha omitted when 255) or "#AARRGGBB". */
    @JvmStatic fun toHex(c: Int, includeAlpha: Boolean = alpha(c) != 255): String =
        if (includeAlpha) String.format("#%08X", c) else String.format("#%06X", c and 0xFFFFFF)

    /** Parses "#RGB", "#RRGGBB", "#AARRGGBB" with or without '#'. */
    @JvmStatic fun parseHex(text: String): Int? {
        var s = text.trim().removePrefix("#")
        if (s.length == 3) s = s.map { "$it$it" }.joinToString("")
        return when (s.length) {
            6 -> s.toLongOrNull(16)?.let { (0xFF000000L or it).toInt() }
            8 -> s.toLongOrNull(16)?.toInt()
            else -> null
        }
    }
}
