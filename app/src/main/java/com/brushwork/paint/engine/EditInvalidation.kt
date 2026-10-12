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
}
