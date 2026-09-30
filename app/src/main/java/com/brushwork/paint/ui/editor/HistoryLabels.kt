package com.brushwork.paint.ui.editor

import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.vector.CurveTool

/**
 * Feedback text for undo/redo, computed BEFORE calling [EditorController.undo]/[EditorController.redo]
 * (which first cancel a running filter, step back one point of a curve / polygon, or discard
 * pending tool work).
 */
internal object HistoryLabels {
    /**
     * Why undo / redo must wait right now, or null. While the text editor is open (shown, or
     * minimized to its pill while the canvas is used: the hotbar and the canvas stay usable) the
     * text being typed is not history yet: undo would throw the whole text away, and redo would
     * drop an untouched edit and close the editor under the user. The editor's OK / Cancel
     * finish it first.
     */
    fun historyBlocked(c: EditorController): String? {
        val text = c.currentTool as? TextTool ?: return null
        return if (text.editorOpen && c.filterSession == null) BLOCKED_BY_TEXT_EDITOR else null
    }

    const val BLOCKED_BY_TEXT_EDITOR = "Finish the text first: OK or Cancel"

    /**
     * Undoes (see [EditorController.undo]) unless [historyBlocked]; returns the feedback text
     * either way. Used by the hotbar's Undo and the canvas' two-finger tap.
     */
    fun performUndo(c: EditorController): String {
        historyBlocked(c)?.let { return it }
        val label = undo(c)
        c.undo()
        return label
    }

    /** Redo counterpart of [performUndo] (the hotbar's Redo, the three-finger tap). */
    fun performRedo(c: EditorController): String {
        historyBlocked(c)?.let { return it }
        val label = redo(c)
        c.redo()
        return label
    }

    fun undo(c: EditorController): String {
        if (c.filterSession != null) return "Filter cancelled"
        val tool = c.currentTool
        if (tool.hasUserChanges) {
            undoStepName(tool)?.let { return "Undo: $it" }
            return "Undo: ${c.activeToolId.label} (discarded)"
        }
        return c.undoManager.undoLabel?.let { "Undo: $it" } ?: "Nothing to undo"
    }

    fun redo(c: EditorController): String {
        if (c.filterSession != null) return "Finish the filter first"
        val tool = c.currentTool
        if (tool.hasPendingWork && tool.canRedoStep) return "Redo: ${stepName(tool.id)}"
        if (tool.hasUserChanges) return "Apply or discard the ${c.activeToolId.label.lowercase()} edit first"
        return c.undoManager.redoLabel?.let { "Redo: $it" } ?: "Nothing to redo"
    }

    /**
     * What undo takes back when [tool] steps back ONE part of its pending work (the last point
     * of a curve, the last corner of a polygon), or null when undo discards the work whole.
     *
     * Known per tool, never found by reflection: release builds are minified (R8 renames
     * [Tool.undoStep]), so a by-name lookup would label steps differently on the phone than in
     * tests. The curve / polyline tools step back one point ([CurveTool.canUndoStep]); the lasso
     * only has pending work while polygon corners are being tapped, and its undo takes back the
     * last corner.
     */
    fun undoStepName(tool: Tool): String? {
        // Tool.canUndoStep is a plain property (safe under R8), overridden by the curve,
        // polyline and lasso tools.
        return if (tool.canUndoStep) stepName(tool.id) else null
    }

    fun stepName(id: ToolId): String = when (id) {
        ToolId.CURVE, ToolId.POLYLINE -> "last point"
        ToolId.LASSO -> "last corner"
        else -> "last ${id.label.lowercase()} step"
    }
}
