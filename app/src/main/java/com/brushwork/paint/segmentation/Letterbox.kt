package com.brushwork.paint.segmentation

import com.brushwork.paint.core.PixelBuffer
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Geometry of fitting a [srcWidth]x[srcHeight] image into a square [size]x[size] model input
 * without distortion: the image is scaled to fit and centered, the rest is padding.
 * Continuous coordinates (pixel centers at +0.5) map linearly in both directions.
 */
class Letterbox(val srcWidth: Int, val srcHeight: Int, val size: Int = MODEL_SIZE) {
    init {
        require(srcWidth > 0 && srcHeight > 0 && size > 0)
    }

    /** Size of the scaled image inside the square. */
    val contentWidth: Int = (srcWidth.toDouble() * size / max(srcWidth, srcHeight)).roundToInt().coerceIn(1, size)
    val contentHeight: Int = (srcHeight.toDouble() * size / max(srcWidth, srcHeight)).roundToInt().coerceIn(1, size)

    /** Top-left corner of the content inside the square. */
    val offsetX: Int = (size - contentWidth) / 2
    val offsetY: Int = (size - contentHeight) / 2

    /** Exact per-axis scale source -> model (they differ only by rounding). */
    val scaleX: Float = contentWidth.toFloat() / srcWidth
    val scaleY: Float = contentHeight.toFloat() / srcHeight

    fun toModelX(x: Float): Float = offsetX + x * scaleX
    fun toModelY(y: Float): Float = offsetY + y * scaleY
    fun toSourceX(mx: Float): Float = (mx - offsetX) / scaleX
    fun toSourceY(my: Float): Float = (my - offsetY) / scaleY

    /** True if the model pixel (mx, my) lies in the content (not the padding). */
    fun isContent(mx: Int, my: Int): Boolean =
        mx >= offsetX && my >= offsetY && mx < offsetX + contentWidth && my < offsetY + contentHeight

    /**
     * Writes the NHWC model input for [content] (already resampled to contentWidth x contentHeight)
     * into [out] (size*size*3 bytes). Each channel value v is written as [lut][v]; padding gets
     * lut[128] (mid gray), which is 0 for the Autoseg int8 input (v - 128).
     */
    fun fillInput(content: PixelBuffer, lut: ByteArray, out: ByteArray) {
        require(content.width == contentWidth && content.height == contentHeight) {
            "content ${content.width}x${content.height} != ${contentWidth}x$contentHeight"
        }
        require(lut.size == 256 && out.size == size * size * 3)
        out.fill(lut[128])
        val px = content.pixels
        for (y in 0 until contentHeight) {
            var o = ((offsetY + y) * size + offsetX) * 3
            val row = y * contentWidth
            for (x in 0 until contentWidth) {
                val c = MaskOps.flattenOverWhite(px[row + x])
                out[o] = lut[(c shr 16) and 0xFF]
                out[o + 1] = lut[(c shr 8) and 0xFF]
                out[o + 2] = lut[c and 0xFF]
                o += 3
            }
        }
    }

    /** The content part of a size*size per-pixel model output (e.g. class ids), row-major. */
    fun crop(modelOutput: ByteArray): ByteArray {
        require(modelOutput.size == size * size)
        val out = ByteArray(contentWidth * contentHeight)
        for (y in 0 until contentHeight) {
            System.arraycopy(modelOutput, (offsetY + y) * size + offsetX, out, y * contentWidth, contentWidth)
        }
        return out
    }

    override fun toString(): String =
        "Letterbox(${srcWidth}x$srcHeight -> ${contentWidth}x$contentHeight @($offsetX,$offsetY) in $size)"

    companion object {
        /** Input side of the bundled Autoseg-EdgeTPU model. */
        const val MODEL_SIZE = 512

        /**
         * Quantization table for an 8-bit model input that expects RGB normalized to [-1, 1]
         * (real = (v - 127.5) / 127.5). For the Autoseg int8 input (scale 1/127.5, zero point -1)
         * this yields exactly v - 128. [signed] selects int8 (else uint8) clamping. When the
         * tensor has no quantization (scale 0) the conventional v - 128 / v mapping is used.
         *
         * That normalization puts every value exactly on a rounding tie (v - 128.5), so ties
         * round up with a small tolerance: the stored float scale (0.007843138) is a hair above
         * 1/127.5, and plain rounding would give v - 129 for half of the values.
         */
        fun quantLut(scale: Float, zeroPoint: Int, signed: Boolean): ByteArray = ByteArray(256) { v ->
            val q = if (scale > 0f) {
                floor(((v - 127.5) / 127.5) / scale + zeroPoint + 0.5 + 1e-3).toInt()
            } else {
                if (signed) v - 128 else v
            }
            if (signed) min(127, max(-128, q)).toByte() else min(255, max(0, q)).toByte()
        }
    }
}
