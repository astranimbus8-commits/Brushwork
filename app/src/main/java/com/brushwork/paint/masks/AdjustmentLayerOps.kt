package com.brushwork.paint.masks

import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Layer

/**
 * Adjustment layer commands of the layers window (v1.5 §4.3, owned by A5). Foundation (F1):
 * they only say the feature is coming.
 */
object AdjustmentLayerOps {
    /** "New adjustment layer (Tone)": adds a Tone adjustment above the active layer. */
    fun createDefault(c: EditorController) {
        c.toast("Coming soon")
    }

    /** "Edit adjustment": selects [layer] and the Masks tool and opens the Adjust sheet. */
    fun edit(c: EditorController, layer: Layer) {
        c.toast("Coming soon")
    }

    /** "Edit mask": selects [layer] and the Masks tool. */
    fun editMask(c: EditorController, layer: Layer) {
        c.toast("Coming soon")
    }
}
