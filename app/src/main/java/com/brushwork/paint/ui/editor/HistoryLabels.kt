package com.brushwork.paint.ui.editor

import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.select.LassoKind
import com.brushwork.paint.tools.select.LassoTool
import com.brushwork.paint.tools.vector.CurveTool

/**
 * Feedback text for undo/redo, computed BEFORE calling [EditorController.undo]/[EditorController.redo]
 * (which first cancel a running filter, step back one point of a curve / polygon, or discard
 * pending tool work).
 */
internal object HistoryLabels {
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
        if (tool.hasPendingWork && tool.canRedoStep) return "Redo: ${stepName(tool)}"
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
     * only has pending work while polygon corners or curve points are being tapped, and its undo
     * takes back the last corner / point.
     */
    fun undoStepName(tool: Tool): String? {
        // Tool.canUndoStep is a plain property (safe under R8), overridden by the curve,
        // polyline and lasso tools.
        return if (tool.canUndoStep) stepName(tool) else null
    }

    /** [stepName] of the tool's current mode (the curve lasso steps back points, not corners). */
    private fun stepName(tool: Tool): String =
        if (tool is LassoTool && tool.kind == LassoKind.CURVE) "last point" else stepName(tool.id)

    fun stepName(id: ToolId): String = when (id) {
        ToolId.CURVE, ToolId.POLYLINE -> "last point"
        ToolId.LASSO -> "last corner"
        else -> "last ${id.label.lowercase()} step"
    }
}
