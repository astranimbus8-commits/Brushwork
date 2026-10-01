package com.brushwork.paint.vector.select

import android.graphics.Canvas
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.model.Selection
import com.brushwork.paint.model.SelectionMode

/**
 * Object selection on vector layers (v1.5 §4.9, owned by A2): Lasso / Select shape areas select
 * the objects they touch (New / Add / Subtract / Intersect), shown with dashed boxes. Reached
 * through `VectorLayers.selectObjects` / `drawOverlay`. Foundation (F1): not handled (the area
 * becomes a pixel selection as on raster layers).
 */
object VectorObjectSelection {
    /** Selects the objects of the active vector layer that [sel] touches, combined by [mode]; false = not handled. */
    fun select(c: EditorController, sel: Selection, mode: SelectionMode): Boolean = false

    /** Dashed boxes of the selected objects (screen space; [t] maps document -> screen). */
    fun drawOverlay(c: EditorController, canvas: Canvas, t: ViewTransform) {}
}
