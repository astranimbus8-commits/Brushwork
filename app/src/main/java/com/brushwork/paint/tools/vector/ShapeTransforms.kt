package com.brushwork.paint.tools.vector

/**
 * v1.7 (item 11, §3.11; area C): the Transform tool's data lift of a shape layer. Move, rotate,
 * corners, sides and flips map the shape exactly through `ShapeAffine`; a skew gives a
 * custom-points shape, still a shape.
 *
 * Foundation stub: always null, so shape layers keep the pixel lift until area C implements it.
 */
object ShapeTransforms {
    /**
     * The shape layer data [shapeData] (ShapeCodec JSON) mapped by [m] (a row-major 3 × 3 affine
     * matrix in document px), or null when it can't be.
     */
    @Suppress("UNUSED_PARAMETER")
    fun mapped(shapeData: String, m: FloatArray): String? = null
}
