package com.brushwork.paint.tools.select

import android.graphics.Path
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Selection
import com.brushwork.paint.model.SelectionMode
import kotlinx.coroutines.Job

/**
 * v1.8 (C, design §4.7): the commit of a closed lasso outline, shared by the Lasso (freehand,
 * polygon and curve) and the Magnetic lasso. The outline is rasterized at the document's full
 * resolution on a background thread, combined with the current selection by [SelectionMode] and
 * applied as ONE selection step named by the caller; on a vector layer the area selects objects
 * instead (the funnel of every area tool). Moved out of `LassoTool` byte for byte
 * (`LassoCommitParityTest`).
 */
internal object LassoCommit {

    /** The busy overlay's text while a slow commit runs. */
    const val BUSY_LABEL = "Selecting…"

    /**
     * Commits [path] (document coordinates, closed) as a selection step labelled [label]. The job
     * gets its own copy of [path], so the caller may reuse it. [onFinished] runs on the main
     * thread when the job ends, applied, empty, failed or cancelled.
     */
    fun apply(
        controller: EditorController,
        path: Path,
        mode: SelectionMode,
        antiAlias: Boolean,
        label: String,
        onFinished: () -> Unit = {},
    ): Job {
        val docW = controller.doc.width
        val docH = controller.doc.height
        val work = Path(path) // the background job gets its own copy
        return SelectionJobs.applyAsync(controller, label, mode, BUSY_LABEL, onFinished = onFinished, toObjects = true) { cancelled ->
            if (cancelled()) null else rasterize(work, docW, docH, antiAlias)
        }
    }

    /** Rasterizes a closed lasso path into a document-sized selection (blocking; any thread). */
    fun rasterize(path: Path, docW: Int, docH: Int, antiAlias: Boolean): Selection =
        Selection.fromPath(path, docW, docH, antiAlias)
}
