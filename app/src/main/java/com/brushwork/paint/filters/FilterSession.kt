package com.brushwork.paint.filters

import android.graphics.Canvas
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.tools.ToolPoint

// STUB — replaced by the filter-UI module.
/**
 * A filter being previewed on the active layer. Created by EditorController.startFilter().
 * Must set controller.filterSession = null when finished (applied or cancelled).
 */
class FilterSession(val controller: EditorController, val filter: Filter) {
    fun start() {}
    fun cancel() { controller.filterSession = null }
    fun onPointerDown(p: ToolPoint): Boolean = false
    fun onPointerMove(p: ToolPoint) {}
    fun onPointerUp(p: ToolPoint) {}
    fun drawOverlay(canvas: Canvas, t: ViewTransform) {}
}
