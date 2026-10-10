package com.brushwork.paint.ui.editor

import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.select.LassoKind
import com.brushwork.paint.tools.select.LassoTool
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.common.FolderLabels
import com.brushwork.paint.ui.common.PillLabels
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.common.SavedSelectionLabels
import com.brushwork.paint.ui.common.TransformLabels17

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
        // A saved selection still compressing lands first (v1.7), as undo() would: the feedback
        // then names the step undo takes back ("Undo: Save selection").
        c.landSavedSelections()
        val label = undo(c)
        c.undo()
        return label
    }

    /** Redo counterpart of [performUndo] (the hotbar's Redo, the three-finger tap). */
    fun performRedo(c: EditorController): String {
        historyBlocked(c)?.let { return it }
        // (As performUndo: the save lands first; as a new edit it empties the redo stack.)
        c.landSavedSelections()
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
        ToolId.CURVE, ToolId.POLYLINE, ToolId.PATH -> "last point"
        ToolId.LASSO -> "last corner"
        // A shape with its own points steps back one point / shape edit.
        ToolId.SHAPE -> "last shape edit"
        else -> "last ${id.label.lowercase()} step"
    }

    // ------------------------------------------------------------------ v1.7 step names (I10)
    // The names of v1.7's undo steps ("Undo: <name>"), pre-declared by the foundation (design
    // §4.8) so every area labels its steps with these constants and the names stay an API.

    const val NEW_FOLDER = FolderLabels.NEW
    const val PUT_IN_NEW_FOLDER = FolderLabels.PUT_IN_NEW
    const val MOVE_INTO_FOLDER = EditorController.MOVE_INTO_FOLDER_LABEL
    const val MOVE_OUT_OF_FOLDER = FolderLabels.MOVE_OUT
    const val MOVE_LAYER = "Move layer"
    const val MERGE_FOLDER = FolderLabels.MERGE
    const val LAYER_FROM_FOLDER = FolderLabels.FROM_FOLDER
    const val UNGROUP_FOLDER = FolderLabels.UNGROUP
    const val DELETE_FOLDER = EditorController.DELETE_FOLDER_LABEL
    const val DUPLICATE_FOLDER = FolderLabels.DUPLICATE
    const val PASS_THROUGH = FolderLabels.PASS_THROUGH
    const val ARRAY = ArrayLabels.BUTTON
    const val EDIT_ARRAY = ArrayLabels.EDIT
    const val APPLY_ARRAY = ArrayLabels.APPLY
    const val REMOVE_ARRAY = ArrayLabels.REMOVE
    const val EDIT_SOURCE = ArrayLabels.EDIT_SOURCE
    const val FINISH_SOURCE = ArrayLabels.FINISH_SOURCE
    const val TURN_INTO_PATH = PointLabels.TO_PATH
    const val SAVE_SELECTION = SavedSelectionLabels.SAVE
    const val UPDATE_SAVED_SELECTION = EditorController.UPDATE_SAVED_SELECTION_LABEL
    const val RENAME_SAVED_SELECTION = EditorController.RENAME_SAVED_SELECTION_LABEL
    const val DELETE_SAVED_SELECTION = SavedSelectionLabels.DELETE
    const val FREE_DEFORM = TransformLabels17.FREE_DEFORM
    /** The pill's Scale row (one step per committed scale). */
    const val SCALE = "Scale"

    /** The pill's trash cell with every point (or no point) selected: "Delete <kind>" ([PillLabels.deleteObject]). */
    const val DELETE_SHAPE = "Delete shape"
    const val DELETE_TEXT = "Delete text"
    const val DELETE_CURVE = "Delete curve"
    const val DELETE_POLYLINE = "Delete polyline"
    const val DELETE_PATH = "Delete path"

    /** Pathfinder's ten operations (design §3.20), in its row's order; each step is "Pathfinder: <op>" ([pathfinder]). */
    val PATHFINDER_OPS: List<String> = listOf(
        "Unite", "Minus front", "Minus back", "Intersect", "Exclude",
        "Divide", "Trim", "Merge", "Crop", "Outline",
    )

    /** The undo step of Pathfinder's [op] (one of [PATHFINDER_OPS]): "Pathfinder: Unite". */
    fun pathfinder(op: String): String = "Pathfinder: $op"

    /** Every v1.7 step name above, the ten Pathfinder steps included. */
    val V17: List<String> = listOf(
        NEW_FOLDER, PUT_IN_NEW_FOLDER, MOVE_INTO_FOLDER, MOVE_OUT_OF_FOLDER, MOVE_LAYER, MERGE_FOLDER,
        LAYER_FROM_FOLDER, UNGROUP_FOLDER, DELETE_FOLDER, DUPLICATE_FOLDER, PASS_THROUGH,
        ARRAY, EDIT_ARRAY, APPLY_ARRAY, REMOVE_ARRAY, EDIT_SOURCE, FINISH_SOURCE, TURN_INTO_PATH,
        SAVE_SELECTION, UPDATE_SAVED_SELECTION, RENAME_SAVED_SELECTION, DELETE_SAVED_SELECTION, FREE_DEFORM,
        DELETE_SHAPE, DELETE_TEXT, DELETE_CURVE, DELETE_POLYLINE, DELETE_PATH, SCALE,
    ) + PATHFINDER_OPS.map(::pathfinder)
}
