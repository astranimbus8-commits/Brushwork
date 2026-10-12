package com.brushwork.paint.tools

/**
 * v1.9 (D14, design 2.4.2): an open session with its own history of steps (I22), such as a filter
 * session (A2) or the node editing of a text path (A3). While one is set as
 * `EditorController.inToolHistory`, Undo and Redo (the hotbar's buttons, the two- and
 * three-finger taps) step through its history first, and their feedback names its steps; at its
 * bottom (or top) they do what v1.8 did. Usually backed by a [ToolHistory].
 */
interface InToolHistoryOwner {
    /** True when [undoStep] takes a step back. */
    val canUndo: Boolean

    /** True when [redoStep] brings a step back. */
    val canRedo: Boolean

    /** The label of the step the next Undo takes back, shown as "Undo: <label>"; null at the bottom. */
    val undoLabel: String?

    /** The label of the step the next Redo brings back, shown as "Redo: <label>"; null at the top. */
    val redoLabel: String?

    /** One step back. False at the bottom: the controller then runs v1.8's undo (a filter session cancels). */
    fun undoStep(): Boolean

    /** One step forward again. False at the top: the controller then runs v1.8's redo. */
    fun redoStep(): Boolean
}
