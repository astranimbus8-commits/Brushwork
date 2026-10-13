package com.brushwork.paint.tools.text

import android.graphics.RectF
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.UndoAction
import com.brushwork.paint.model.Layer

/**
 * v1.9 (R14, design 2.4.15; owner A3, called by A4): operations on the texts of a text layer as
 * items. F1b: the seam; nothing is available yet.
 */
@Suppress("UNUSED_PARAMETER")
object TextItemOps {
    /** False until A3 implements [duplicateItems]; A4 hides its Duplicate on text layers while false. F1b: false. */
    val available: Boolean = false

    /**
     * R14: duplicates the texts of [layer] whose bounds meet [area] (every text when null) and whose
     * ids are in [ids] (every id when null), offset like the object bar's Duplicate
     * ([com.brushwork.paint.vector.select.ObjectActions.duplicateOffset]); re-renders the layer and
     * returns the undo action for the [com.brushwork.paint.ui.common.LayerLabels19.DUPLICATE_STEP] step
     * (A4's selection bar; A3's Text More row with the open text's id). Null when nothing was duplicated. F1b: null.
     */
    fun duplicateItems(c: EditorController, layer: Layer, area: RectF?, ids: Set<Int>? = null): UndoAction? = null
}
