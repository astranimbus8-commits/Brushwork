package com.brushwork.paint.engine

import android.graphics.Rect

/**
 * v1.9 (design 2.4.5; owner A6): what the canvas redraws after a pixel edit is committed
 * (`EditorController.commitEdit`). v1.8 redraws the changed rect; a commit whose display already
 * shows the result (A6's drawing preview, once `PreviewEqualsCommitParityTest` holds, I25)
 * redraws nothing.
 */
object EditInvalidation {
    /** The document rect to redraw after a commit: v1.8 = [changed]; displayCurrent = true returns null (nothing to redraw). */
    fun afterCommit(changed: Rect?, displayCurrent: Boolean): Rect? = if (displayCurrent) null else changed

    /**
     * v1.9 A6 (P1): the parts of [changed] to redraw after a commit that snapshotted [tiles]
     * (`PixelEditRecorder.touchedTileRects`, all the same size on one grid): each tile's part of
     * [changed], tiles side by side in a row merged into one rect. A commit changes pixels only in
     * the tiles it snapshotted, so this redraws everything [afterCommit] would that can have
     * changed. Empty when [displayCurrent].
     */
    fun tilesAfterCommit(tiles: List<Rect>, changed: Rect, displayCurrent: Boolean): List<Rect> {
        if (displayCurrent || tiles.isEmpty() || changed.isEmpty) return emptyList()
        val sorted = tiles.sortedWith(compareBy<Rect>({ it.top }, { it.left }))
        val out = ArrayList<Rect>()
        var run: Rect? = null
        for (t in sorted) {
            val r = run
            if (r != null && r.top == t.top && r.bottom == t.bottom && r.right == t.left) {
                r.right = t.right
                continue
            }
            if (r != null && r.intersect(changed)) out += r
            run = Rect(t)
        }
        run?.let { if (it.intersect(changed)) out += it }
        return out
    }
}
