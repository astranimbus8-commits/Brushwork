package com.brushwork.paint.exchange.svg

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** An affine map (x, y) -> (a x + c y + e, b x + d y + f), as SVG's `matrix(a b c d e f)`. */
data class Affine(val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float) {
    fun mapX(x: Float, y: Float): Float = a * x + c * y + e
    fun mapY(x: Float, y: Float): Float = b * x + d * y + f

    /** This map applied after [o] (this ∘ o): `then`-style composition reads right to left. */
    operator fun times(o: Affine): Affine = Affine(
        a * o.a + c * o.b, b * o.a + d * o.b,
        a * o.c + c * o.d, b * o.c + d * o.d,
        a * o.e + c * o.f + e, b * o.e + d * o.f + f,
    )

    val det: Float get() = a * d - b * c

    /** How much lengths scale (sqrt |det|). */
    val scale: Float get() = sqrt(abs(det))

    fun inverse(): Affine? {
        val dt = det
        if (dt == 0f || !dt.isFinite()) return null
        val ia = d / dt; val ib = -b / dt; val ic = -c / dt; val id = a / dt
        return Affine(ia, ib, ic, id, -(ia * e + ic * f), -(ib * e + id * f))
    }

    /** As a 3x3 row-major matrix (VectorOps.transformed). */
    fun toMatrix3(): FloatArray = floatArrayOf(a, c, e, b, d, f, 0f, 0f, 1f)

    fun toList(): List<Float> = listOf(a, b, c, d, e, f)

    val isFinite: Boolean get() = a.isFinite() && b.isFinite() && c.isFinite() && d.isFinite() && e.isFinite() && f.isFinite()

    companion object {
        val IDENTITY = Affine(1f, 0f, 0f, 1f, 0f, 0f)
        fun translate(x: Float, y: Float) = Affine(1f, 0f, 0f, 1f, x, y)
        fun scale(x: Float, y: Float = x) = Affine(x, 0f, 0f, y, 0f, 0f)
        fun rotate(deg: Float): Affine {
            val r = deg * PI.toFloat() / 180f
            val c = cos(r); val s = sin(r)
            return Affine(c, s, -s, c, 0f, 0f)
        }
        fun skewX(deg: Float) = Affine(1f, 0f, tan(deg * PI.toFloat() / 180f), 1f, 0f, 0f)
        fun skewY(deg: Float) = Affine(1f, tan(deg * PI.toFloat() / 180f), 0f, 1f, 0f, 0f)
    }
}

/**
 * Numbers, lengths and transforms of SVG attributes (v1.5 §4.11b). Every number must be finite
 * and is clamped to ±[MAX_NUMBER] (a hostile `1e308` can't overflow later arithmetic).
 */
object SvgUnits {
    const val MAX_NUMBER = 1e6f

    /** CSS px per inch (the user unit of SVG content is a CSS px). */
    const val CSS_DPI = 96f

    /** A number token at [i] of [s]: (value, end index) or null. Accepts `-.5`, `1e-5`, `+3.`. */
    fun numberAt(s: CharSequence, i0: Int): Pair<Float, Int>? {
        var i = i0
        val n = s.length
        val start = i
        if (i < n && (s[i] == '+' || s[i] == '-')) i++
        var digits = 0
        while (i < n && s[i].isAsciiDigit()) { i++; digits++ }
        if (i < n && s[i] == '.') {
            i++
            while (i < n && s[i].isAsciiDigit()) { i++; digits++ }
        }
        if (digits == 0) return null
        if (i < n && (s[i] == 'e' || s[i] == 'E')) {
            var k = i + 1
            if (k < n && (s[k] == '+' || s[k] == '-')) k++
            if (k < n && s[k].isAsciiDigit()) {
                while (k < n && s[k].isAsciiDigit()) k++
                i = k
            }
        }
        val v = s.subSequence(start, i).toString().toDoubleOrNull() ?: return null
        return clamp(v) to i
    }

    /** [v] made finite and clamped to ±[MAX_NUMBER]. */
    fun clamp(v: Double): Float = if (v.isNaN()) 0f else v.coerceIn(-MAX_NUMBER.toDouble(), MAX_NUMBER.toDouble()).toFloat()

    private fun Char.isAsciiDigit() = this in '0'..'9'

    /** All numbers of a list attribute (`points`, `viewBox`...), separated by spaces and commas. */
    fun numbers(s: String?): FloatArray {
        if (s.isNullOrBlank()) return FloatArray(0)
        val out = ArrayList<Float>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == ' ' || c == ',' || c == '\n' || c == '\t' || c == '\r') { i++; continue }
            val (v, end) = numberAt(s, i) ?: break
            out += v
            i = end
        }
        return out.toFloatArray()
    }

    /** A length's number and unit (lowercase; "" without one), or null. */
    fun parseLength(s: String?): Pair<Float, String>? {
        if (s == null) return null
        val t = s.trim()
        val (v, end) = numberAt(t, 0) ?: return null
        val unit = t.substring(end).trim().lowercase()
        return v to unit
    }

    /**
     * A length in user units (CSS px): [percentOf] is the reference length for `%`, [fontSize]
     * for em / ex. Null when missing or unreadable.
     */
    fun length(s: String?, percentOf: Float = 0f, fontSize: Float = 16f): Float? {
        val (v, unit) = parseLength(s) ?: return null
        return when (unit) {
            "", "px" -> v
            "pt" -> v * CSS_DPI / 72f
            "pc" -> v * CSS_DPI / 6f
            "mm" -> v * CSS_DPI / 25.4f
            "cm" -> v * CSS_DPI / 2.54f
            "in" -> v * CSS_DPI
            "q" -> v * CSS_DPI / 101.6f
            "em" -> v * fontSize
            "ex" -> v * fontSize / 2f
            "%" -> v * percentOf / 100f
            else -> null
        }
    }

    /**
     * The root element's [s] (width / height) in document px at [dpi]: px and plain numbers stay
     * px (1:1), physical units are converted at [dpi], em at 16 px. Null for `%`, `auto` or none.
     */
    fun rootLength(s: String?, dpi: Float): Float? {
        val (v, unit) = parseLength(s) ?: return null
        return when (unit) {
            "", "px" -> v
            "pt" -> v * dpi / 72f
            "pc" -> v * dpi / 6f
            "mm" -> v * dpi / 25.4f
            "cm" -> v * dpi / 2.54f
            "in" -> v * dpi
            "em" -> v * 16f
            "ex" -> v * 8f
            else -> null
        }?.takeIf { it > 0f }
    }

    /** The root element's [s] as a physical length in inches (px at 96 per inch), null for `%` or none. */
    fun inches(s: String?): Float? {
        val (v, unit) = parseLength(s) ?: return null
        return when (unit) {
            "", "px" -> v / CSS_DPI
            "pt" -> v / 72f
            "pc" -> v / 6f
            "mm" -> v / 25.4f
            "cm" -> v / 2.54f
            "in" -> v
            "em" -> v * 16f / CSS_DPI
            "ex" -> v * 8f / CSS_DPI
            else -> null
        }?.takeIf { it > 0f }
    }

    /** A `transform` attribute (null = unreadable: SVG then ignores the whole attribute). */
    fun transform(s: String?): Affine? {
        if (s.isNullOrBlank()) return Affine.IDENTITY
        var m = Affine.IDENTITY
        var i = 0
        val n = s.length
        while (i < n) {
            while (i < n && (s[i].isWhitespace() || s[i] == ',')) i++
            if (i >= n) break
            val nameStart = i
            while (i < n && s[i].isLetter()) i++
            val name = s.substring(nameStart, i)
            while (i < n && s[i].isWhitespace()) i++
            if (i >= n || s[i] != '(') return null
            val close = s.indexOf(')', i)
            if (close < 0) return null
            val args = numbers(s.substring(i + 1, close))
            i = close + 1
            val t = when (name) {
                "matrix" -> if (args.size == 6) Affine(args[0], args[1], args[2], args[3], args[4], args[5]) else return null
                "translate" -> when (args.size) { 1 -> Affine.translate(args[0], 0f); 2 -> Affine.translate(args[0], args[1]); else -> return null }
                "scale" -> when (args.size) { 1 -> Affine.scale(args[0]); 2 -> Affine.scale(args[0], args[1]); else -> return null }
                "rotate" -> when (args.size) {
                    1 -> Affine.rotate(args[0])
                    3 -> Affine.translate(args[1], args[2]) * Affine.rotate(args[0]) * Affine.translate(-args[1], -args[2])
                    else -> return null
                }
                "skewX" -> if (args.size == 1) Affine.skewX(args[0]) else return null
                "skewY" -> if (args.size == 1) Affine.skewY(args[0]) else return null
                else -> return null
            }
            m = m * t
        }
        return m.takeIf { it.isFinite }
    }

    /**
     * The `viewBox` -> viewport map with `preserveAspectRatio` [par] (default `xMidYMid meet`);
     * null when [viewBox] is missing or degenerate.
     */
    fun viewBoxTransform(viewBox: String?, par: String?, x: Float, y: Float, w: Float, h: Float): Affine? {
        val vb = numbers(viewBox)
        if (vb.size < 4 || !(vb[2] > 0f) || !(vb[3] > 0f)) return null
        val sx = w / vb[2]
        val sy = h / vb[3]
        val parts = (par ?: "").trim().split(Regex("\\s+")).filter { it.isNotEmpty() && it != "defer" }
        val align = parts.getOrNull(0) ?: "xMidYMid"
        val slice = parts.getOrNull(1) == "slice"
        if (align == "none") return Affine(sx, 0f, 0f, sy, x - vb[0] * sx, y - vb[1] * sy)
        val s = if (slice) maxOf(sx, sy) else minOf(sx, sy)
        val cw = vb[2] * s
        val ch = vb[3] * s
        val dx = when {
            align.startsWith("xMin") -> 0f
            align.startsWith("xMax") -> w - cw
            else -> (w - cw) / 2f
        }
        val dy = when {
            align.endsWith("YMin") -> 0f
            align.endsWith("YMax") -> h - ch
            else -> (h - ch) / 2f
        }
        return Affine(s, 0f, 0f, s, x + dx - vb[0] * s, y + dy - vb[1] * s)
    }
}
