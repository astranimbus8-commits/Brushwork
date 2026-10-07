package com.brushwork.paint.tools.text

/**
 * v1.7 (item 11, §3.11; area D): the Transform tool's data lift of a text layer. Move, the
 * rotate knob and the corner handles (a proportional scale) map the text's centre, rotation and
 * every length, then the wrap reflow and `StoryWriter` run again, so the layer stays a sharp text
 * layer.
 *
 * Foundation stub: [canMap] is false and [mapped] null, so text keeps the pixel lift until area D
 * implements it.
 */
object TextTransforms {
    /** True when [m] (a row-major 3 × 3 affine matrix in document px) keeps a text a text. */
    @Suppress("UNUSED_PARAMETER")
    fun canMap(m: FloatArray): Boolean = false

    /** The text layer data [textData] (TextCodec JSON) mapped by [m], or null when it can't be. */
    @Suppress("UNUSED_PARAMETER")
    fun mapped(textData: String, m: FloatArray): String? = null
}
