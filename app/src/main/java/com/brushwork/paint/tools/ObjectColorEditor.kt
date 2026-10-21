package com.brushwork.paint.tools

import com.brushwork.paint.ui.common.PaintMode

/** v1.10 (R2): the color an Object colors sheet tab edits. */
enum class ObjectColorTarget { FILL, STROKE, TEXT, OUTLINE }

/** v1.10 (R2): what a tab's swatch shows. */
sealed interface ObjectColorFace {
    data class Solid(val argb: Int) : ObjectColorFace
    /** A Linear or Radial fill, kept until a color control is touched; [stops] are its colors. */
    data class Gradient(val stops: List<Int>) : ObjectColorFace
    /** No fill (mode Stroke) or no outline (width 0). */
    data object None : ObjectColorFace
}

/**
 * v1.10 (R2, R7, J21): what a tool with an open object gives the Object colors sheet. The edits
 * are object-only: the drawing color never changes (the tool keeps a restore point). Every color
 * change goes through `vector/ObjectColorSlots` (I35), which applies the fill-follows pin (C4).
 *
 * Sheet contract (R7): OK commits; Cancel and Back cancel; a swipe or an outside touch only
 * minimizes the sheet and keeps the preview. When the object's session ends while the sheet is open
 * or minimized, the session's ✓ calls [commit] first (the color is inside the ✓'s one undo step)
 * and ✕ calls [cancel]; the sheet closes.
 */
interface ObjectColorEditor {
    /** Snapshot state. Shape and Curve: FILL, STROKE; Text: TEXT, OUTLINE. */
    val targets: List<ObjectColorTarget>

    /** The object's paint mode; null for text. */
    val paintMode: PaintMode?

    fun face(t: ObjectColorTarget): ObjectColorFace

    /** The reason toast when [t] cannot be edited now (e.g. a curve's FILL_NEEDS_3), or null. */
    fun unavailable(t: ObjectColorTarget): String?

    fun setPaintMode(m: PaintMode)

    /** The restore point of the main color (V19) and the start values. */
    fun begin()

    /** R7: a pending edit, no undo step. */
    fun preview(t: ObjectColorTarget, argb: Int)

    /** FILL: mode Stroke; OUTLINE: width 0. */
    fun setNone(t: ObjectColorTarget)

    /** Keeps the previewed colors; no undo step of its own (J21): the object's ✓ step carries them. */
    fun commit()

    /** Back to the start values. */
    fun cancel()
}

/** v1.10 (R2): a tool whose open object has colors the pill's COLOR cell can edit; null hides the cell. */
interface ColoredObjectTool { val objectColors: ObjectColorEditor? }
