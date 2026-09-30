package com.brushwork.paint.ui.editor

import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
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
        if (tool.hasPendingWork && tool.canRedoStep) return "Redo: ${stepName(tool.id)}"
        if (tool.hasUserChanges) return "Apply or discard the ${c.activeToolId.label.lowercase()} edit first"
        return c.undoManager.redoLabel?.let { "Redo: $it" } ?: "Nothing to redo"
    }

    /**
     * What undo takes back when [tool] steps back ONE part of its pending work (the last point
     * of a curve, the last corner of a polygon...), or null when undo discards the work whole.
     */
    fun undoStepName(tool: Tool): String? {
        val steps = when (tool) {
            is CurveTool -> tool.canUndoStep
            else -> overridesUndoStep(tool)
        }
        return if (steps) stepName(tool.id) else null
    }

    fun stepName(id: ToolId): String = when (id) {
        ToolId.CURVE, ToolId.POLYLINE -> "last point"
        ToolId.LASSO -> "last corner"
        else -> "last ${id.label.lowercase()} step"
    }

    private val overrides = HashMap<Class<*>, Boolean>()

    /** Whether the tool's class implements [Tool.undoStep] (the base class never steps). */
    private fun overridesUndoStep(tool: Tool): Boolean = overrides.getOrPut(tool.javaClass) {
        runCatching { tool.javaClass.getMethod("undoStep").declaringClass != Tool::class.java }.getOrDefault(false)
    }
}
