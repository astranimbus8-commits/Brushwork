package com.brushwork.paint.engine.tiles

import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer

/**
 * v1.10 (F1, design 2.5; implementations A9): a cached composite of the units below or above the
 * active one. [validFor] says whether it still matches [doc] with [active] as the active layer;
 * [drop] frees it (projections go first under pressure, P5); [heldBytes] is what it holds now.
 */
interface Projection {
    fun validFor(doc: Document, active: Layer?): Boolean
    fun drop()
    val heldBytes: Long
}
