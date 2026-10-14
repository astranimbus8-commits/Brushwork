package com.brushwork.paint.engine

import android.graphics.Canvas
import android.graphics.RectF
import com.brushwork.paint.model.Document

/**
 * v1.9 (design 2.4.9, C16; owner A8): the paper ([Document.paper], R15) under the artwork. It is
 * never pixels (I21): it is drawn only here, at a fixed list of sites (the canvas, thumbnails,
 * flattened exports and shares, canvas sampling, the SVG / PDF scene), and never into a merge, a
 * layer export, the clipboard or an undo snapshot. Every site is a no-op while `paper == null`
 * (v1.8; `PaperHookNoOpTest`). The sites are pinned by `v19/a8/PaperBackdropSitesTest`.
 */
object PaperBackdrop {

    /**
     * Draws doc.paper under the artwork into [dst] (the document rect when null); a no-op when
     * doc.paper == null. One clipped `drawColor` and no allocation, so the canvas can call it every
     * frame (F1b G3); any thread, the [canvas]'s state is restored.
     */
    fun draw(canvas: Canvas, doc: Document, dst: RectF? = null) {
        val paper = doc.paper ?: return
        val save = canvas.save()
        if (dst != null) canvas.clipRect(dst) else canvas.clipRect(0f, 0f, doc.width.toFloat(), doc.height.toFloat())
        canvas.drawColor(paper.color)
        canvas.restoreToCount(save)
    }

    /** The colour canvas sampling sees under transparent pixels: the paper's, or null. */
    fun sampleColor(doc: Document): Int? = doc.paper?.color
}
