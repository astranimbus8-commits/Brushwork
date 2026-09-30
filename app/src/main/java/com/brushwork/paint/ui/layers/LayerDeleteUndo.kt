package com.brushwork.paint.ui.layers

import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Layer

/**
 * Undo offered right after a layer was deleted from the layers window (the "Layer deleted"
 * message's Undo). It may only take back that deletion: once anything else happened since
 * (another edit, an undo, a filter preview, the user's pending tool work), plain undo is the way.
 */
object LayerDeleteUndo {
    /** Label of the undo step [EditorController.deleteLayer] records. */
    const val LABEL = "Delete layer"

    /**
     * Whether undoing now brings back [layer], deleted when the history had [undoCountAfterDelete]
     * steps (counted right after the deletion).
     */
    fun canUndo(controller: EditorController, layer: Layer, undoCountAfterDelete: Int): Boolean {
        val history = controller.undoManager
        return controller.doc.layerById(layer.id) == null &&
            history.undoCount == undoCountAfterDelete &&
            history.undoLabel == LABEL &&
            controller.filterSession == null &&
            !controller.currentTool.hasUserChanges
    }
}
