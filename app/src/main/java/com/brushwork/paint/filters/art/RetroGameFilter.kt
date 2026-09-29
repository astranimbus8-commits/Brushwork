package com.brushwork.paint.filters.art

import com.brushwork.paint.core.Parallel
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterCategory
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterValues
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * A fixed retro palette. [ramp] palettes (dark to light) are indexed by luminance, like the shades
 * of a Game Boy; the others use the nearest color. [spread] is the ordered-dither amplitude in
 * 0..255 units (about the distance between neighboring palette colors).
 */
class RetroPalette(val title: String, val colors: IntArray, val ramp: Boolean, val spread: Float) {
    companion object {
        private fun rgb(vararg c: Int) = IntArray(c.size) { c[it] or 0xFF000000.toInt() }

        /** The palettes offered by [RetroGameFilter], in UI order. */
        val all: List<RetroPalette> = listOf(
            RetroPalette(
                "8 colors",
                rgb(0x000000, 0x0000FF, 0xFF0000, 0xFF00FF, 0x00FF00, 0x00FFFF, 0xFFFF00, 0xFFFFFF),
                ramp = false, spread = 255f,
            ),
            RetroPalette("Game Boy", rgb(0x0F380F, 0x306230, 0x8BAC0F, 0x9BBC0F), ramp = true, spread = 85f),
            RetroPalette(
                "NES",
                rgb(
                    0x7C7C7C, 0x0000FC, 0x0000BC, 0x4428BC, 0x940084, 0xA80020, 0xA81000, 0x881400, 0x503000,
                    0x007800, 0x006800, 0x005800, 0x004058, 0x000000, 0xBCBCBC, 0x0078F8, 0x0058F8, 0x6844FC,
                    0xD800CC, 0xE40058, 0xF83800, 0xE45C10, 0xAC7C00, 0x00B800, 0x00A800, 0x00A844, 0x008888,
                    0xF8F8F8, 0x3CBCFC, 0x6888FC, 0x9878F8, 0xF878F8, 0xF85898, 0xF87858, 0xFCA044, 0xF8B800,
                    0xB8F818, 0x58D854, 0x58F898, 0x00E8D8, 0x787878, 0xFCFCFC, 0xA4E4FC, 0xB8B8F8, 0xD8B8F8,
                    0xF8B8F8, 0xF8A4C0, 0xF0D0B0, 0xFCE0A8, 0xF8D878, 0xD8F878, 0xB8F8B8, 0xB8F8D8, 0x00FCFC,
                    0xF8D8F8,
                ),
                ramp = false, spread = 56f,
            ),
            RetroPalette(
                "16 colors",
                rgb(
                    0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xAA5500, 0xAAAAAA,
                    0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF,
                ),
                ramp = false, spread = 85f,
            ),
            RetroPalette("CGA", rgb(0x000000, 0x55FFFF, 0xFF55FF, 0xFFFFFF), ramp = false, spread = 170f),
            RetroPalette(
                "PICO-8",
                rgb(
                    0x000000, 0x1D2B53, 0x7E2553, 0x008751, 0xAB5236, 0x5F574F, 0xC2C3C7, 0xFFF1E8,
                    0xFF004D, 0xFFA300, 0xFFEC27, 0x00E436, 0x29ADFF, 0x83769C, 0xFF77A8, 0xFFCCAA,
                ),
                ramp = false, spread = 72f,
            ),
            RetroPalette("1-bit", rgb(0x000000, 0xFFFFFF), ramp = true, spread = 255f),
        )
    }
}

/**
 * Retro Game: enlarges pixels to [dot size] blocks and reduces them to a retro palette (the
 * "8 digital colors" of old PCs by default) with optional ordered (Bayer 4×4) dithering at block
 * resolution. Block alpha is thresholded, as old hardware had no partial transparency.
 */
class RetroGameFilter : Filter("art.retro_game", "Retro Game", FilterCategory.ART) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Slider("dotSize", "Dot size", 1f, 64f, 4f, step = 1f, suffix = "px", pixels = true),
        FilterParam.Choice("palette", "Palette", RetroPalette.all.map { it.title }, 0),
        FilterParam.Slider("dither", "Dithering", 0f, 100f, 50f, step = 1f, suffix = "%"),
        FilterParam.Slider("saturation", "Saturation", -100f, 100f, 0f, step = 1f),
        FilterParam.Slider("brightness", "Brightness", -100f, 100f, 0f, step = 1f),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val w = src.width; val h = src.height
        val palette = RetroPalette.all[values.choice("palette").coerceIn(0, RetroPalette.all.lastIndex)]
        val dot = max(1f, ctx.px(values.float("dotSize").coerceIn(1f, 64f)))
        val dither = (values.float("dither") / 100f).coerceIn(0f, 1f)
        val sat = 1f + (values.float("saturation") / 100f).coerceIn(-1f, 1f)
        val bright = (values.float("brightness") / 100f).coerceIn(-1f, 1f) * 128f
        val lut = if (palette.ramp) null else nearestLut(palette.colors)
        val colors = palette.colors
        val nbx = max(1, ceil(w / dot).toInt())
        val nby = max(1, ceil(h / dot).toInt())
        val out = PixelBuffer(w, h)
        val sp = src.pixels; val dp = out.pixels

        Parallel.forRange(nby, 1) { b0, b1 ->
            ctx.checkCancelled()
            val sa = LongArray(nbx); val sr = LongArray(nbx); val sg = LongArray(nbx); val sb = LongArray(nbx)
            val cnt = IntArray(nbx)
            val xStart = IntArray(nbx + 1) { min(w, floor(it * dot).toInt()) }
            xStart[nbx] = w
            val blockColor = IntArray(nbx)
            for (by in b0 until b1) {
                ctx.checkCancelled()
                val y0 = min(h, floor(by * dot).toInt())
                val y1 = if (by == nby - 1) h else min(h, floor((by + 1) * dot).toInt())
                if (y1 <= y0) continue
                sa.fill(0); sr.fill(0); sg.fill(0); sb.fill(0); cnt.fill(0)
                // (1) Average premultiplied RGBA per block.
                for (y in y0 until y1) {
                    val row = y * w
                    for (bx in 0 until nbx) {
                        var a = 0L; var r = 0L; var g = 0L; var b = 0L
                        for (x in xStart[bx] until xStart[bx + 1]) {
                            val c = sp[row + x]
                            val al = (c ushr 24).toLong()
                            a += al
                            r += ((c shr 16) and 0xFF) * al
                            g += ((c shr 8) and 0xFF) * al
                            b += (c and 0xFF) * al
                        }
                        sa[bx] += a; sr[bx] += r; sg[bx] += g; sb[bx] += b
                        cnt[bx] += xStart[bx + 1] - xStart[bx]
                    }
                }
                // (2) Quantize each block.
                for (bx in 0 until nbx) {
                    val n = cnt[bx]
                    if (n == 0 || sa[bx] * 2 < 255L * n) { blockColor[bx] = 0; continue }
                    val inv = 1f / sa[bx]
                    var r = sr[bx] * inv + bright; var g = sg[bx] * inv + bright; var b = sb[bx] * inv + bright
                    if (sat != 1f) {
                        val l = r * 0.299f + g * 0.587f + b * 0.114f
                        r = l + (r - l) * sat; g = l + (g - l) * sat; b = l + (b - l) * sat
                    }
                    val t = (BAYER4[(by and 3) * 4 + (bx and 3)] + 0.5f) / 16f - 0.5f
                    val offset = t * dither * palette.spread
                    blockColor[bx] = if (lut != null) {
                        colors[lut[(q5(r + offset) shl 10) or (q5(g + offset) shl 5) or q5(b + offset)].toInt()]
                    } else {
                        val l = (r * 0.299f + g * 0.587f + b * 0.114f + offset) / 255f
                        colors[(l * (colors.size - 1) + 0.5f).toInt().coerceIn(0, colors.size - 1)]
                    }
                }
                // (3) Fill the blocks.
                for (y in y0 until y1) {
                    val row = y * w
                    for (bx in 0 until nbx) dp.fill(blockColor[bx], row + xStart[bx], row + xStart[bx + 1])
                }
            }
        }
        return out
    }

    companion object {
        private val BAYER4 = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

        private fun q5(v: Float): Int = if (v <= 0f) 0 else if (v >= 255f) 31 else v.toInt() shr 3

        /** Nearest palette index for every 5-bit-per-channel RGB cell (perceptually weighted distance). */
        internal fun nearestLut(colors: IntArray): ByteArray {
            val lut = ByteArray(32 * 32 * 32)
            Parallel.forRange(32, 1) { r0, r1 ->
                for (ri in r0 until r1) for (gi in 0 until 32) for (bi in 0 until 32) {
                    val r = ri * 8 + 4; val g = gi * 8 + 4; val b = bi * 8 + 4
                    var best = 0; var bestD = Int.MAX_VALUE
                    for (i in colors.indices) {
                        val c = colors[i]
                        val dr = r - ((c shr 16) and 0xFF); val dg = g - ((c shr 8) and 0xFF); val db = b - (c and 0xFF)
                        val d = 3 * dr * dr + 4 * dg * dg + 2 * db * db
                        if (d < bestD) { bestD = d; best = i }
                    }
                    lut[(ri shl 10) or (gi shl 5) or bi] = best.toByte()
                }
            }
            return lut
        }
    }
}
