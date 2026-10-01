package com.brushwork.paint.vector.draw

import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2

/**
 * The bucket on a vector layer (v1.5 §4.9, owned by A3): recolors the object under the tap.
 * Called first by FillTool on vector layers; false lets the bucket fill pixels as today.
 * Foundation (F1): not handled.
 */
object VectorFill {
    /** Handles a bucket tap at [p] (document px) on the active vector layer; false = not handled. */
    fun tap(c: EditorController, p: Vec2): Boolean = false
}
