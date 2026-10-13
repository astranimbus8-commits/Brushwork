package com.brushwork.paint.vector.draw

import android.graphics.Paint
import android.graphics.Path
import com.brushwork.paint.vector.VObject

/**
 * v1.9 (R11 b, design 2.4.12; owner A5): the boolean cut of vector objects by an eraser region.
 * F1b: the seam; [cut] changes nothing.
 */
object EraseCut {
    const val MAX_PIECES = 256
    const val MAX_TARGETS = 500

    sealed interface Outcome {
        /** The pieces that stay; empty = the object is removed. */
        data class Cut(val pieces: List<VObject>) : Outcome

        /** A cap was hit; the object is unchanged. */
        data object Partly : Outcome

        /** The op failed or nothing was touched. */
        data object Unchanged : Outcome
    }

    /** The boolean cut of one target (R11 b). Pure; runs on Dispatchers.Default over immutable copies. F1b: Unchanged. */
    @Suppress("UNUSED_PARAMETER")
    fun cut(target: VObject, eraser: Path): Outcome = Outcome.Unchanged

    /**
     * A round-capped, round-joined stroke region along [centre] (the Eraser brush, or an open
     * outline), [radius] on each side. An empty path when [radius] is not a positive number.
     */
    fun strokeRegion(centre: Path, radius: Float): Path {
        val out = Path()
        if (!radius.isFinite() || radius <= 0f) return out
        val paint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = radius * 2f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        paint.getFillPath(centre, out)
        return out
    }
}
