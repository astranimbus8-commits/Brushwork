package com.brushwork.paint.exchange.svg

import kotlin.math.abs
import kotlin.math.roundToInt

/** A `fill` / `stroke` value. */
sealed class SvgPaint {
    object None : SvgPaint()
    object CurrentColor : SvgPaint()
    data class Color(val argb: Int) : SvgPaint()

    /** `url(#id)` with an optional fallback paint. */
    data class Url(val id: String, val fallback: SvgPaint?) : SvgPaint()
}

/**
 * SVG / CSS colors (v1.5 §4.11b): `#rgb`, `#rgba`, `#rrggbb`, `#rrggbbaa`, `rgb()` / `rgba()`
 * (numbers or percentages, comma or space syntax), `hsl()` / `hsla()`, the 147 named colors
 * (plus `rebeccapurple` and `transparent`), `currentColor` and `none`. Pure Kotlin.
 */
object SvgColors {
    /** A color value as ARGB, or null when unreadable (`currentColor` and `none` are not colors). */
    fun parse(s: String?): Int? {
        val t = s?.trim()?.lowercase() ?: return null
        if (t.isEmpty()) return null
        if (t.startsWith("#")) return hex(t.substring(1))
        val paren = t.indexOf('(')
        if (paren > 0 && t.endsWith(")")) {
            val fn = t.substring(0, paren).trim()
            val args = t.substring(paren + 1, t.length - 1).split(',', ' ', '/', '\t', '\n').filter { it.isNotBlank() }
            return when (fn) {
                "rgb", "rgba" -> rgb(args)
                "hsl", "hsla" -> hsl(args)
                else -> null
            }
        }
        if (t == "transparent") return 0
        return NAMED[t]
    }

    /** A paint value: `none`, `currentColor`, a color, or `url(#id) [fallback]`; null when unreadable. */
    fun paint(s: String?): SvgPaint? {
        val t = s?.trim() ?: return null
        if (t.isEmpty()) return null
        if (t.equals("none", true)) return SvgPaint.None
        if (t.equals("currentColor", true)) return SvgPaint.CurrentColor
        if (t.startsWith("url(", true)) {
            val close = t.indexOf(')')
            if (close < 0) return null
            var ref = t.substring(4, close).trim().trim('"', '\'').trim()
            if (!ref.startsWith("#")) return SvgPaint.Url("", paint(t.substring(close + 1)))
            ref = ref.substring(1)
            val rest = t.substring(close + 1).trim()
            return SvgPaint.Url(ref, if (rest.isEmpty()) null else paint(rest))
        }
        return parse(t)?.let { SvgPaint.Color(it) }
    }

    private fun hex(h: String): Int? {
        if (h.any { Character.digit(it, 16) < 0 }) return null
        return when (h.length) {
            3, 4 -> {
                val r = Character.digit(h[0], 16) * 17
                val g = Character.digit(h[1], 16) * 17
                val b = Character.digit(h[2], 16) * 17
                val a = if (h.length == 4) Character.digit(h[3], 16) * 17 else 255
                argb(a, r, g, b)
            }
            6, 8 -> {
                val v = h.substring(0, 6).toLong(16).toInt()
                val a = if (h.length == 8) h.substring(6, 8).toInt(16) else 255
                (a shl 24) or (v and 0xFFFFFF)
            }
            else -> null
        }
    }

    private fun channel(s: String): Int? {
        val t = s.trim()
        return if (t.endsWith("%")) t.dropLast(1).toFloatOrNull()?.let { (it.coerceIn(0f, 100f) * 2.55f).roundToInt() }
        else t.toFloatOrNull()?.takeIf { it.isFinite() }?.let { it.coerceIn(0f, 255f).roundToInt() }
    }

    private fun alpha(s: String?): Int? {
        if (s == null) return 255
        val t = s.trim()
        val v = if (t.endsWith("%")) t.dropLast(1).toFloatOrNull()?.div(100f) else t.toFloatOrNull()
        return v?.takeIf { it.isFinite() }?.let { (it.coerceIn(0f, 1f) * 255f).roundToInt() }
    }

    private fun rgb(args: List<String>): Int? {
        if (args.size < 3) return null
        val r = channel(args[0]) ?: return null
        val g = channel(args[1]) ?: return null
        val b = channel(args[2]) ?: return null
        val a = alpha(args.getOrNull(3)) ?: return null
        return argb(a, r, g, b)
    }

    private fun hsl(args: List<String>): Int? {
        if (args.size < 3) return null
        val h = args[0].trim().removeSuffix("deg").toFloatOrNull() ?: return null
        val s = args[1].trim().removeSuffix("%").toFloatOrNull()?.div(100f)?.coerceIn(0f, 1f) ?: return null
        val l = args[2].trim().removeSuffix("%").toFloatOrNull()?.div(100f)?.coerceIn(0f, 1f) ?: return null
        val a = alpha(args.getOrNull(3)) ?: return null
        if (!h.isFinite()) return null
        val hh = ((h % 360f) + 360f) % 360f / 60f
        val c = (1f - abs(2f * l - 1f)) * s
        val x = c * (1f - abs(hh % 2f - 1f))
        val (r1, g1, b1) = when (hh.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = l - c / 2f
        fun to255(v: Float) = ((v + m) * 255f).roundToInt().coerceIn(0, 255)
        return argb(a, to255(r1), to255(g1), to255(b1))
    }

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int = (a shl 24) or (r shl 16) or (g shl 8) or b

    /** The CSS named colors. */
    val NAMED: Map<String, Int> = mapOf(
        "aliceblue" to 0xf0f8ff, "antiquewhite" to 0xfaebd7, "aqua" to 0x00ffff, "aquamarine" to 0x7fffd4,
        "azure" to 0xf0ffff, "beige" to 0xf5f5dc, "bisque" to 0xffe4c4, "black" to 0x000000,
        "blanchedalmond" to 0xffebcd, "blue" to 0x0000ff, "blueviolet" to 0x8a2be2, "brown" to 0xa52a2a,
        "burlywood" to 0xdeb887, "cadetblue" to 0x5f9ea0, "chartreuse" to 0x7fff00, "chocolate" to 0xd2691e,
        "coral" to 0xff7f50, "cornflowerblue" to 0x6495ed, "cornsilk" to 0xfff8dc, "crimson" to 0xdc143c,
        "cyan" to 0x00ffff, "darkblue" to 0x00008b, "darkcyan" to 0x008b8b, "darkgoldenrod" to 0xb8860b,
        "darkgray" to 0xa9a9a9, "darkgreen" to 0x006400, "darkgrey" to 0xa9a9a9, "darkkhaki" to 0xbdb76b,
        "darkmagenta" to 0x8b008b, "darkolivegreen" to 0x556b2f, "darkorange" to 0xff8c00, "darkorchid" to 0x9932cc,
        "darkred" to 0x8b0000, "darksalmon" to 0xe9967a, "darkseagreen" to 0x8fbc8f, "darkslateblue" to 0x483d8b,
        "darkslategray" to 0x2f4f4f, "darkslategrey" to 0x2f4f4f, "darkturquoise" to 0x00ced1, "darkviolet" to 0x9400d3,
        "deeppink" to 0xff1493, "deepskyblue" to 0x00bfff, "dimgray" to 0x696969, "dimgrey" to 0x696969,
        "dodgerblue" to 0x1e90ff, "firebrick" to 0xb22222, "floralwhite" to 0xfffaf0, "forestgreen" to 0x228b22,
        "fuchsia" to 0xff00ff, "gainsboro" to 0xdcdcdc, "ghostwhite" to 0xf8f8ff, "gold" to 0xffd700,
        "goldenrod" to 0xdaa520, "gray" to 0x808080, "grey" to 0x808080, "green" to 0x008000,
        "greenyellow" to 0xadff2f, "honeydew" to 0xf0fff0, "hotpink" to 0xff69b4, "indianred" to 0xcd5c5c,
        "indigo" to 0x4b0082, "ivory" to 0xfffff0, "khaki" to 0xf0e68c, "lavender" to 0xe6e6fa,
        "lavenderblush" to 0xfff0f5, "lawngreen" to 0x7cfc00, "lemonchiffon" to 0xfffacd, "lightblue" to 0xadd8e6,
        "lightcoral" to 0xf08080, "lightcyan" to 0xe0ffff, "lightgoldenrodyellow" to 0xfafad2, "lightgray" to 0xd3d3d3,
        "lightgreen" to 0x90ee90, "lightgrey" to 0xd3d3d3, "lightpink" to 0xffb6c1, "lightsalmon" to 0xffa07a,
        "lightseagreen" to 0x20b2aa, "lightskyblue" to 0x87cefa, "lightslategray" to 0x778899, "lightslategrey" to 0x778899,
        "lightsteelblue" to 0xb0c4de, "lightyellow" to 0xffffe0, "lime" to 0x00ff00, "limegreen" to 0x32cd32,
        "linen" to 0xfaf0e6, "magenta" to 0xff00ff, "maroon" to 0x800000, "mediumaquamarine" to 0x66cdaa,
        "mediumblue" to 0x0000cd, "mediumorchid" to 0xba55d3, "mediumpurple" to 0x9370db, "mediumseagreen" to 0x3cb371,
        "mediumslateblue" to 0x7b68ee, "mediumspringgreen" to 0x00fa9a, "mediumturquoise" to 0x48d1cc, "mediumvioletred" to 0xc71585,
        "midnightblue" to 0x191970, "mintcream" to 0xf5fffa, "mistyrose" to 0xffe4e1, "moccasin" to 0xffe4b5,
        "navajowhite" to 0xffdead, "navy" to 0x000080, "oldlace" to 0xfdf5e6, "olive" to 0x808000,
        "olivedrab" to 0x6b8e23, "orange" to 0xffa500, "orangered" to 0xff4500, "orchid" to 0xda70d6,
        "palegoldenrod" to 0xeee8aa, "palegreen" to 0x98fb98, "paleturquoise" to 0xafeeee, "palevioletred" to 0xdb7093,
        "papayawhip" to 0xffefd5, "peachpuff" to 0xffdab9, "peru" to 0xcd853f, "pink" to 0xffc0cb,
        "plum" to 0xdda0dd, "powderblue" to 0xb0e0e6, "purple" to 0x800080, "rebeccapurple" to 0x663399,
        "red" to 0xff0000, "rosybrown" to 0xbc8f8f, "royalblue" to 0x4169e1, "saddlebrown" to 0x8b4513,
        "salmon" to 0xfa8072, "sandybrown" to 0xf4a460, "seagreen" to 0x2e8b57, "seashell" to 0xfff5ee,
        "sienna" to 0xa0522d, "silver" to 0xc0c0c0, "skyblue" to 0x87ceeb, "slateblue" to 0x6a5acd,
        "slategray" to 0x708090, "slategrey" to 0x708090, "snow" to 0xfffafa, "springgreen" to 0x00ff7f,
        "steelblue" to 0x4682b4, "tan" to 0xd2b48c, "teal" to 0x008080, "thistle" to 0xd8bfd8,
        "tomato" to 0xff6347, "turquoise" to 0x40e0d0, "violet" to 0xee82ee, "wheat" to 0xf5deb3,
        "white" to 0xffffff, "whitesmoke" to 0xf5f5f5, "yellow" to 0xffff00, "yellowgreen" to 0x9acd32,
    ).mapValues { (_, v) -> v or (0xFF shl 24) }
}
