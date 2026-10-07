package com.brushwork.paint.tools.text

/**
 * v1.7 (item 17, area D): manual kerning in the text editor dialog. Implemented by the hosts of
 * `ui/placement/TextEditorDialog` next to the frozen [TextEditorHost] (the Text tool and the story
 * editor of the Text frames tool); the dialog asks for it with `host as? KerningEditor`.
 *
 * Kerns ([TextItem.kerns]) belong to the character before their gap ([TextKerns]): the editor's
 * text changes go through [setText] with the cursor, so a kern stays between the same letters
 * while text is typed or deleted around it.
 */
interface KerningEditor {

    /**
     * The edited text becomes [text], the cursor (or the end of the selection) at [cursor] after
     * the change (−1 when unknown); the kerns follow their characters ([TextKerns.edited]).
     */
    fun setText(text: String, cursor: Int)

    /**
     * Gives every gap of [gaps] (see [TextKerns.gaps]) a kern of [value] (1/1000 em, clamped to
     * [TextKern.MIN_VALUE]..[TextKern.MAX_VALUE]; 0 removes them). Ignored for vertical text.
     */
    fun setKerns(gaps: IntRange, value: Int)
}
