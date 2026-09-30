package com.brushwork.paint.ui.editor

import com.brushwork.paint.EditorController

/**
 * Feedback text for undo/redo, computed BEFORE calling [EditorController.undo]/[EditorController.redo]
 * (which first cancel a running filter or discard pending tool work).
 */
internal object HistoryLabels {
    fun undo(c: EditorController): String = when {
        c.filterSession != null -> "Filter cancelled"
        // Tools with steps (curve points, polygon corners) take back only the last one.
        c.currentTool.hasUserChanges && c.currentTool.canUndoStep -> "Undo: ${c.activeToolId.label} point"
        c.currentTool.hasUserChanges -> "Undo: ${c.activeToolId.label} (discarded)"
        else -> c.undoManager.undoLabel?.let { "Undo: $it" } ?: "Nothing to undo"
    }

    fun redo(c: EditorController): String = when {
        c.filterSession != null -> "Finish the filter first"
        c.currentTool.canRedoStep -> "Redo: ${c.activeToolId.label} point"
        c.currentTool.hasUserChanges -> "Apply or discard the ${c.activeToolId.label.lowercase()} edit first"
        else -> c.undoManager.redoLabel?.let { "Redo: $it" } ?: "Nothing to redo"
    }
}
