package com.brushwork.paint.vector.edit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.LayerRenderOverride
import com.brushwork.paint.model.Layer
import com.brushwork.paint.vector.VObject

/**
 * Preview of objects being edited (reopened path or shape, transform lift; v1.5 §5.4; API
 * frozen, owned by A1): draws the layer's cache with a hole where the edited objects were plus
 * [drawPreview]; adopts a painting tool's live override as [inner] (a brush-stroked path being
 * re-edited). The layer itself is untouched until [commit]. Created by `VectorLayers.beginEdit`.
 *
 * Foundation (F1): never created by the stub service; the drawing below is a plain fallback.
 */
class VectorEditSession internal constructor(
    private val c: EditorController,
    override val layer: Layer,
    val ids: Set<Long>,
    /** The edited objects rendered alone (bbox-sized, possibly scaled down by [floatingScale]). */
    val floating: Bitmap?,
    /** Where [floating] belongs (document px). */
    val floatingRect: Rect,
    /** Pixels of [floating] per document px (1 unless memory forced a smaller preview). */
    val floatingScale: Float,
) : LayerRenderOverride {
    /** Draws what replaces the edited objects while they are edited (document px). */
    var drawPreview: ((Canvas) -> Unit)? = null

    /** A painting tool's live override (its stroke) shown inside this session's preview. */
    var inner: LayerRenderOverride? = null

    private val plain = Paint(Paint.FILTER_BITMAP_FLAG)

    override fun drawContent(canvas: Canvas): Boolean {
        val i = inner
        if (i == null || i.layer !== layer || !i.drawContent(canvas)) canvas.drawBitmap(layer.bitmap, 0f, 0f, plain)
        drawPreview?.invoke(canvas)
        return true
    }

    /** Re-installs this session as the controller's override with its current one as [inner]. */
    fun adoptInner() {
        val cur = c.renderOverride
        if (cur !== this) {
            inner = cur
            c.renderOverride = this
        }
        c.invalidateDoc(null)
    }

    /** Replaces the edited objects by [replacements] (one step [label]); [onDone] tells whether it was applied. */
    fun commit(replacements: List<VObject>, label: String, onDone: (Boolean) -> Unit = {}) {
        cancel()
        onDone(false)
    }

    /** Ends the preview without changes. */
    fun cancel() {
        if (c.renderOverride === this) c.renderOverride = inner
        inner = null
        drawPreview = null
        c.invalidateDoc(null)
    }
}
