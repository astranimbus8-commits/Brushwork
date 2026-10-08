package com.brushwork.paint.tools.transform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.ceil
import kotlin.math.floor

/**
 * v1.7 (item 16, design §3.16 c): draws a lifted bitmap through a Free deform mesh with
 * `Canvas.drawBitmapMesh`. Its triangles have no antialiased edges, so the bitmap is drawn from a
 * copy with a 1 px transparent margin ([padded]), filtered: the margin lands just outside the
 * mesh's rectangle and the edges fade out smoothly. The preview takes [PREVIEW_SUB] parts per
 * cell (at most 97 x 97 points at 12 x 12 cells); the commit [COMMIT_SUB].
 */
internal object MeshRenderer {
    /** Parts per cell (each axis) of the preview. */
    const val PREVIEW_SUB = 8

    /** Parts per cell (each axis) of the commit. */
    const val COMMIT_SUB = 16

    /** [src] with a 1 px transparent margin, in [config] (default: [src]'s). Null when out of memory. */
    fun padded(src: Bitmap, config: Bitmap.Config = src.config ?: Bitmap.Config.ARGB_8888): Bitmap? = try {
        Bitmap.createBitmap(src.width + 2, src.height + 2, config).also { Canvas(it).drawBitmap(src, 1f, 1f, null) }
    } catch (e: OutOfMemoryError) {
        null
    }

    /**
     * The `drawBitmapMesh` points of [mesh] (whose rectangle is the [srcW] x [srcH] source) for its
     * [padded] copy, [sub] parts per cell: the copy's edges sit 1 source px outside the rectangle.
     */
    fun vertices(mesh: MeshDeform, srcW: Int, srcH: Int, sub: Int, smooth: Boolean): FloatArray =
        mesh.dense(sub, smooth, padS = 1f / srcW.coerceAtLeast(1), padT = 1f / srcH.coerceAtLeast(1))

    /** Draws [padded] through [mesh] by [verts] (from [vertices] with the same [sub]). */
    fun draw(canvas: Canvas, padded: Bitmap, mesh: MeshDeform, verts: FloatArray, sub: Int, paint: Paint) {
        if (padded.isRecycled) return
        canvas.drawBitmapMesh(padded, mesh.cols * sub, mesh.rows * sub, verts, 0, null, 0, paint)
    }

    /** Document pixels [verts] cover, rounded out with a margin for filtered edges. */
    fun bounds(verts: FloatArray): Rect {
        val b = MeshDeform.boundsOf(verts)
        fun lo(v: Float) = floor(v.coerceIn(-LIMIT, LIMIT)).toInt() - 2
        fun hi(v: Float) = ceil(v.coerceIn(-LIMIT, LIMIT)).toInt() + 2
        return Rect(lo(b[0]), lo(b[1]), hi(b[2]), hi(b[3]))
    }

    /** Coordinates beyond this (document px) are clamped before rounding. */
    private const val LIMIT = 1e7f
}
