package com.brushwork.paint.tools.array

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brushwork.paint.EditorController
import com.brushwork.paint.array.ArrayOps
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId

/**
 * The Array tool (v1.7 item 3, §3.3; area E): the canvas handles of the active layer's live array
 * (the Line arrow, the Circle centre, the Curve guide's points, the Transform pivot and step
 * arrow) and its options sheet. Also opened by the layer ⋮ "Edit array" and by every way of
 * making an array.
 *
 * On a layer without an array it draws nothing and takes no touches. Opening it on a layer whose
 * raster source is being edited runs "Finish source edit" (§3.3 a).
 */
class ArrayTool(controller: EditorController) : Tool(controller) {
    override val id = ToolId.ARRAY

    /** The text array waiting for the "Apply turns the text into pixels" confirmation (Compose state). */
    var pendingTextApply by mutableStateOf<Layer?>(null)
        private set

    /** The layer the tool last worked on (a change of the active layer re-targets it). */
    private var lastLayer: Layer? = null

    /** Asks "Apply turns the text into pixels" before the text array of [layer] is applied. */
    fun askApplyText(layer: Layer) {
        pendingTextApply = layer
    }

    /** The confirmation's answer: [apply] applies the text array (one step "Apply array"). */
    fun answerTextApply(apply: Boolean) {
        val layer = pendingTextApply ?: return
        pendingTextApply = null
        if (apply) ArrayOps.applyNow(controller, layer)
    }

    override fun onActivate() {
        val layer = controller.activeLayer
        val prev = lastLayer
        lastLayer = layer
        // Opened on another layer (not a pause around an operation on the same one).
        if (prev !== layer) finishSourceOf(layer)
    }

    override fun onSelected() {
        finishSourceOf(controller.activeLayer)
    }

    override fun onDeactivate() {
        super.onDeactivate()
        pendingTextApply = null
    }

    /** "Finish source edit" when [layer]'s raster source is being edited (§3.3 a). */
    private fun finishSourceOf(layer: Layer) {
        if (layer.array?.spec?.editingSource == true) ArrayOps.finishSource(controller, layer)
    }
}
