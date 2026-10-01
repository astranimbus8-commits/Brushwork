package com.brushwork.paint.vector

import android.graphics.RectF
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.Selection
import com.brushwork.paint.tools.vector.VectorPath

/**
 * Geometry of vector objects (v1.5 §5.4; API frozen, owned by A1). F2 writes real reference
 * bodies (A2, A3, A4 and A8 depend on them); A1 makes them precise. Foundation (F1): safe
 * defaults (empty bounds, no hits).
 */
object VectorOps {
    /** Everything [o] can paint (document px), including the stroke width / brush radius × sizeScale. */
    fun bounds(o: VObject): RectF = RectF()

    /** True when [p] is on [o]: strokes within radius + [tol] of their polyline, filled paths inside. */
    fun hit(o: VObject, p: Vec2, tol: Float): Boolean = false

    /** Ids of the objects of [content] that [sel] touches. */
    fun touching(content: VectorContent, sel: Selection): Set<Long> = emptySet()

    /**
     * [o] mapped by [m] (3x3 row-major; may be a homography). Strokes map their points and scale
     * `sizeScale` by sqrt|det|; a VShape becomes a VPath unless [m] is a similarity.
     */
    fun transformed(o: VObject, m: FloatArray): VObject = o

    /** The outline of [p] (via CurveGeometry.toPath per sub-path, broken tangents honoured). */
    fun toVectorPath(p: VPath): VectorPath = VectorPath.EMPTY
}
