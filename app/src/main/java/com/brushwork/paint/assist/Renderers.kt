package com.brushwork.paint.assist

import android.graphics.Canvas
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.GridSettings
import com.brushwork.paint.model.RulerSettings

// STUB — replaced by the assist module.
object GridRenderer {
    /** Draws the grid overlay in screen space (t maps doc -> screen). */
    fun draw(canvas: Canvas, t: ViewTransform, doc: Document, grid: GridSettings) {}
}

object RulerRenderer {
    /** Draws the ruler guide in screen space; [editing] = ruler tool active (show handles). */
    fun draw(canvas: Canvas, t: ViewTransform, doc: Document, ruler: RulerSettings, editing: Boolean) {}
}
