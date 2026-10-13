package com.brushwork.paint.vector.draw

import android.graphics.Canvas
import android.graphics.Path
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Layer

/** v1.9 (R11, design 2.4.12; owner A5): what a tool's erase ended in. */
sealed interface ToolEraseResult {
    /** Nothing was erased; no step. */
    data object Nothing : ToolEraseResult

    /** One undo step [label] was recorded. */
    data class Done(val label: String) : ToolEraseResult

    /** The erase was refused; [message] says why. */
    data class Refused(val message: String) : ToolEraseResult
}

/**
 * v1.9 (R11): one erase with a tool's own outline (Curve, Polyline, Path, Shape). A5 gives it its
 * state; F1b's session does nothing ([ToolErase.begin] returns null, so none is made).
 */
class ToolEraseSession internal constructor() {
    /** The tool's current outline as an eraser: a centre line with a width, or a closed region (+ its stroke). */
    @Suppress("UNUSED_PARAMETER")
    fun update(outline: Path, strokeWidth: Float, filledRegion: Boolean) {}

    /** Draws the layer as it looks now (true removal), in document coordinates. */
    @Suppress("UNUSED_PARAMETER")
    fun drawPreview(canvas: Canvas) {}

    /** One undo step with the tool's label (EraseLabels19.STEP_*). */
    @Suppress("UNUSED_PARAMETER")
    fun commit(label: String): ToolEraseResult = ToolEraseResult.Nothing

    fun cancel() {}
}

/** v1.9 (R11, design 2.4.12; owner A5): starts a tool's erase. */
object ToolErase {
    /** F1b stub: null (the tool stays in paint mode). */
    @Suppress("UNUSED_PARAMETER")
    fun begin(c: EditorController, layer: Layer, source: EraseSource): ToolEraseSession? = null
}
