package com.brushwork.paint.filters.draw

import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterCategory
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterMath
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterValues
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws a scannable QR code of the entered text. Modules are square and, at full resolution, a
 * whole number of pixels wide and pixel-aligned so the code stays crisp and readable.
 */
class QrCodeFilter : Filter("draw.qr_code", "QR Code", FilterCategory.DRAW) {
    override val generatesContent = true

    override val params: List<FilterParam> = listOf(
        FilterParam.Text("text", "Text or URL", "https://example.com", multiline = true),
        FilterParam.Slider("size", "Size", 5f, 100f, 40f, 1f, "%"),
        FilterParam.Point("position", "Position", 0.5f, 0.5f),
        FilterParam.Choice("ecc", "Error correction", ECC_NAMES, 1),
        FilterParam.Slider("margin", "Quiet zone (modules)", 0f, 8f, 4f, 1f),
        FilterParam.Color("color", "Code color", 0xFF000000.toInt()),
        FilterParam.Color("background", "Background color", -1),
        FilterParam.Toggle("transparent", "Transparent background", false),
    )

    /** Module matrix (row-major, true = dark) of a QR code. */
    internal class Matrix(val size: Int, val dark: BooleanArray) {
        operator fun get(x: Int, y: Int) = dark[y * size + x]
    }

    /**
     * Encodes [text] at the requested error correction, falling back to lower levels when the
     * text does not fit; null if it cannot be encoded at all.
     */
    internal fun encode(text: String, ecc: Int): Matrix? {
        if (text.isEmpty()) return null
        // ASCII text needs no ECI header (maximally compatible); anything else is UTF-8 + ECI.
        val hints = if (text.all { it.code < 128 }) emptyMap<EncodeHintType, Any>()
        else mapOf(EncodeHintType.CHARACTER_SET to "UTF-8")
        for (level in ecc.coerceIn(0, 3) downTo 0) {
            val qr = try {
                Encoder.encode(text, LEVELS[level], hints)
            } catch (_: WriterException) {
                continue
            }
            val m = qr.matrix
            val n = m.width
            return Matrix(n, BooleanArray(n * n) { m.get(it % n, it / n).toInt() == 1 })
        }
        return null
    }

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val matrix = encode(values.text("text"), values.choice("ecc")) ?: return src.copy()
        val w = src.width; val h = src.height
        val n = matrix.size
        val quiet = values.int("margin").coerceIn(0, 32)
        val total = n + 2 * quiet
        val scale = if (ctx.scale > 0f) ctx.scale else 1f
        // Module size is decided at full resolution (whole pixels) and then scaled to this buffer.
        val fullSide = min(w, h) / scale
        val sizeFull = values.float("size").coerceIn(1f, 100f) / 100f * fullSide
        val moduleFull = max(1f, floor(sizeFull / total))
        val module = moduleFull * scale
        val side = module * total
        val pos = values.point("position")
        var ox = pos[0] * w - side / 2f
        var oy = pos[1] * h - side / 2f
        if (scale >= 1f) { ox = ox.roundToInt().toFloat(); oy = oy.roundToInt().toFloat() }
        val dark = values.color("color")
        val light = values.color("background")
        val transparentBg = values.bool("transparent")
        val inv = 1f / module
        return FilterMath.mapXY(src, ctx) { x, y, c ->
            val fx = (x + 0.5f - ox) * inv
            val fy = (y + 0.5f - oy) * inv
            if (fx < 0f || fy < 0f || fx >= total || fy >= total) return@mapXY c
            val i = fx.toInt() - quiet; val j = fy.toInt() - quiet
            if (i in 0 until n && j in 0 until n && matrix[i, j]) {
                DrawBlend.composite(c, dark, 1f, DrawBlend.NORMAL)
            } else if (transparentBg) c else DrawBlend.composite(c, light, 1f, DrawBlend.NORMAL)
        }
    }

    private companion object {
        val ECC_NAMES = listOf("Low (7%)", "Medium (15%)", "Quartile (25%)", "High (30%)")
        val LEVELS = arrayOf(ErrorCorrectionLevel.L, ErrorCorrectionLevel.M, ErrorCorrectionLevel.Q, ErrorCorrectionLevel.H)
    }
}
