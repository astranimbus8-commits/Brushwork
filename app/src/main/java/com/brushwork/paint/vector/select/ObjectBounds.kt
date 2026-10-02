package com.brushwork.paint.vector.select

import android.graphics.RectF
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorOps
import java.lang.ref.WeakReference

/**
 * Paint bounds of the objects of ONE vector content, computed once per object (`VectorOps.bounds`
 * builds paths and scans stroke points) and kept while that content instance is the one asked
 * about: the overlay asks every frame, arranging asks for many pairs. Main thread only. (The
 * content is held weakly: a closed editor's drawing is not kept alive.)
 */
internal object ObjectBounds {
    private var content: WeakReference<VectorContent>? = null
    private val cache = HashMap<Long, RectF>()

    /** The paint bounds of [o], an object of [owner] (do not modify the result). */
    fun of(owner: VectorContent, o: VObject): RectF {
        if (owner !== content?.get()) {
            content = WeakReference(owner)
            cache.clear()
        }
        return cache.getOrPut(o.id) { VectorOps.bounds(o) }
    }

    /** True when [a] and [b] (objects of [owner]) can cover each other: their paint bounds meet. */
    fun overlap(owner: VectorContent, a: VObject, b: VObject): Boolean {
        val ra = of(owner, a)
        val rb = of(owner, b)
        return !ra.isEmpty && !rb.isEmpty && ra.left < rb.right && rb.left < ra.right && ra.top < rb.bottom && rb.top < ra.bottom
    }

    /** Forgets everything (the editor closed). */
    fun clear() {
        content = null
        cache.clear()
    }
}
