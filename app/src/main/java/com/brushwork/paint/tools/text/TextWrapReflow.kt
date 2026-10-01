package com.brushwork.paint.tools.text

import com.brushwork.paint.EditEvent
import com.brushwork.paint.EditListener
import com.brushwork.paint.EditorController

/**
 * Re-flows text wrapped around a picture layer when that layer is edited (v1.5 §4.1; owned by
 * A7). The controller creates it at init (`controller.textWrap`, so it works before the Text tool
 * exists) and registers it as an [EditListener]: after any committed edit of a source layer,
 * the wrapped text layers are re-laid out INSIDE that edit's undo step (`amendLastStep`, I2).
 * Undo and redo never re-flow. Foundation (F1): does nothing.
 */
class TextWrapReflow(private val c: EditorController) : EditListener {
    override fun onEdited(e: EditEvent) {}
}
