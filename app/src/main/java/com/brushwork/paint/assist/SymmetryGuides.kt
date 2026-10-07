package com.brushwork.paint.assist

import android.graphics.Canvas
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.model.Document

/**
 * The symmetry rulers' guides and handles (v1.7 item 18, §3.18; area H): thin dashed lines in the
 * theme accent, drawn while a replicating tool is active, with the 44 dp handles while the
 * Symmetry tool is ([editing]). The controller's overlay pass calls [draw] right after the ruler
 * (`EditorController.drawOverlays`); there is no global hook (V30).
 *
 * Foundation stub: draws nothing until area H implements it.
 */
object SymmetryGuides {
    /** Draws `doc.symmetry`'s guides in screen space; [t] maps document -> screen. */
    @Suppress("UNUSED_PARAMETER")
    fun draw(canvas: Canvas, t: ViewTransform, doc: Document, editing: Boolean) {}
}
