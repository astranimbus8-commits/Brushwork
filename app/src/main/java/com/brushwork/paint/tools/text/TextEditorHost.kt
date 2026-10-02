package com.brushwork.paint.tools.text

import com.brushwork.paint.EditorController
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.fonts.FontStore
import com.brushwork.paint.fonts.ImportedFont

/**
 * What the text editor dialog and the numbers sheet (`ui/placement/TextEditorDialog.kt`) edit
 * (v1.6 §4.4; frozen): exactly the [TextTool] members they use, plus capability flags. The Text
 * tool implements it (every flag true); the Text frames tool's story editor (area D,
 * `StoryEditorHost`) implements it for a story: vertical text, text on a path and the box size
 * are hidden there.
 *
 * Types and semantics are [TextTool]'s: see its members for the details of each.
 */
interface TextEditorHost {
    val controller: EditorController

    /** The text being edited (Compose state); null when there is none (the dialog shows nothing). */
    val item: TextItem?

    /** True while the editor shows a text that was just created (Cancel removes it). */
    val editingNew: Boolean

    /** The app's imported fonts. */
    val fontStore: FontStore

    /** Largest box width / height and font size (px). */
    val maxBoxPx: Float
    val maxSizePx: Float

    /** The numbers sheet is showing. */
    var numbersOpen: Boolean

    /** Units of the position / box fields and of the size field. */
    var positionUnit: LengthUnit
    var sizeUnit: LengthUnit

    /** Placeholder text options (kept while the editor is closed and reopened). */
    var placeholderAmount: PlaceholderAmount
    var placeholderKind: PlaceholderKind
    var placeholderReplace: Boolean

    /** Inputs of [PlaceholderFit.edit] for the current text (computed off the main thread), or null. */
    fun placeholderRequest(): PlaceholderFit.Request?

    /** Where [t] is on the canvas (document px): its centre, or the centre of its text along a path. */
    fun anchorOf(t: TextItem): Vec2

    fun applyBoxPreset(preset: TextBoxPreset)

    /** Applies a placeholder [edit] computed for [basedOn]; false when the text changed meanwhile or there is no room. */
    fun applyPlaceholder(basedOn: TextItem, edit: PlaceholderFit.Edit?): Boolean

    /** Closes the editor reverting its changes (a new text is removed). */
    fun cancelEditor()

    /** Closes the editor keeping the changes. */
    fun confirmEditor()

    fun nudge(dx: Float, dy: Float)

    /** Imported fonts were added or deleted: the text is laid out again. */
    fun onFontsChanged()

    fun setBoxDepth(px: Float)
    fun setBoxLength(px: Float)
    fun setBuiltInFont(font: TextFont)
    fun setImportedFont(font: ImportedFont)
    fun setCenterX(x: Float)
    fun setCenterY(y: Float)
    fun setFixedBox(on: Boolean)
    fun setFixedDepth(on: Boolean)
    fun setPath(spec: TextPathSpec)
    fun setRotation(deg: Float)
    fun setSizePx(px: Float)
    fun setText(text: String)
    fun updateBox(transform: (TextBoxSpec) -> TextBoxSpec)
    fun updateSpec(transform: (TextSpec) -> TextSpec)

    // ------------------------------------------------------------------ v1.6 capability flags (TextTool: all true)

    /** The "Shape / path" section is shown (text on a path). */
    val supportsPath: Boolean

    /** Vertical text (the button and its section) is offered. */
    val supportsVertical: Boolean

    /** Wrapping around a picture is offered (the tool's own wrap sheet / chip; the dialog has no wrap section). */
    val supportsWrap: Boolean

    /** The box's fixed width / height (and its fixed other side) can be changed in the Box section. */
    val boxSizeEditable: Boolean
}
