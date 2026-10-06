package com.brushwork.paint.tools.select

import com.brushwork.paint.engine.CanvasResult
import com.brushwork.paint.model.SavedSelection

/**
 * v1.7 (item 14): saved-selection helpers. Area G owns this file and writes the bodies; the
 * foundation (F2) declares what the frozen `CanvasOps` pipeline calls.
 */
object SavedSelectionOps {
    /**
     * The saved selections after a canvas operation (resize, crop, trim, rotate, flip...).
     * `CanvasOps` calls it once per operation on its background thread, after mapping the layers;
     * [oldWidth] / [oldHeight] are the size before. The result goes into the operation's undo step.
     *
     * Stub (F2): the list unchanged when [result] keeps the geometry and the size, otherwise an
     * empty list (the operation clears the saved selections, undoably, until G maps them).
     */
    fun mappedForCanvas(list: List<SavedSelection>, result: CanvasResult, oldWidth: Int, oldHeight: Int): List<SavedSelection> =
        if (result.geometry.isIdentity && result.width == oldWidth && result.height == oldHeight) list else emptyList()
}
