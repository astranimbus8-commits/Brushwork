package com.brushwork.paint.vector.edit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.LayerRenderOverride
import com.brushwork.paint.model.Layer
import com.brushwork.paint.vector.VObject

/**
 * Preview of objects being edited (reopened path or shape, transform lift; v1.5 §5.4; API
 * frozen, owned by A1): draws the layer's cache with a hole where the edited objects were plus
 * [drawPreview]; adopts a painting tool's live override as [inner] (a brush-stroked path being
 * re-edited). The layer itself is untouched until [commit]. Created by `VectorLayers.beginEdit`,
 * which installs it as the controller's render override.
 *
 * The hole: inside [holeRect] (the edited objects' paint bounds within the document) the other
 * objects are shown re-rendered ([hole], drawn scaled by 1 / [holeScale]; null = nothing there),
 * outside it the cache as it is. F2 reference: [hole] and [floating] are rendered synchronously
 * by `beginEdit`.
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
    /** Where the edited objects can have painted (document px, within the document; empty = no hole). */
    internal val holeRect: Rect = Rect(),
    /** The other objects re-rendered over [holeRect] (null: transparent there). */
    private var hole: Bitmap? = null,
    /** Pixels of [hole] per document px. */
    private val holeScale: Float = 1f,
) : LayerRenderOverride {
    /** Draws what replaces the edited objects while they are edited (document px). */
    var drawPreview: ((Canvas) -> Unit)? = null

    /**
     * A painting tool's live override (its stroke) shown inside this session's preview. Set to
     * null when that stroke ends while this session stays installed.
     */
    var inner: LayerRenderOverride? = null

    private val filtered = Paint(Paint.FILTER_BITMAP_FLAG)
    private var ended = false

    /** True until [commit] or [cancel]. */
    val isOpen: Boolean get() = !ended

    /**
     * Draws the layer's cache with the edited objects' hole (document px): the cache outside
     * [holeRect], the other objects inside it. (What `ObjectLift.drawBase` draws.)
     */
    fun drawBase(canvas: Canvas) {
        val bmp = layer.bitmap
        if (holeRect.isEmpty) {
            canvas.drawBitmap(bmp, 0f, 0f, null)
            return
        }
        canvas.save()
        canvas.clipOutRect(holeRect)
        canvas.drawBitmap(bmp, 0f, 0f, null)
        canvas.restore()
        val h = hole ?: return
        if (h.isRecycled) return
        canvas.save()
        canvas.clipRect(holeRect)
        if (holeScale == 1f) {
            canvas.drawBitmap(h, holeRect.left.toFloat(), holeRect.top.toFloat(), null)
        } else {
            val dst = RectF(holeRect.left.toFloat(), holeRect.top.toFloat(), holeRect.left + h.width / holeScale, holeRect.top + h.height / holeScale)
            canvas.drawBitmap(h, null, dst, filtered)
        }
        canvas.restore()
    }

    override fun drawContent(canvas: Canvas): Boolean {
        drawBase(canvas)
        drawPreview?.invoke(canvas)
        val i = inner
        if (i != null && i !== this && i.layer === layer) {
            // The painting tool's override draws the layer's bitmap under its stroke: for this one
            // call (main thread) the layer shows an empty bitmap, so the old objects don't ghost.
            val real = layer.bitmap
            layer.bitmap = blank
            try {
                i.drawContent(canvas)
            } finally {
                layer.bitmap = real
            }
        }
        return true
    }

    /** Re-installs this session as the controller's override with its current one as [inner]. */
    fun adoptInner() {
        if (ended) return
        val cur = c.renderOverride
        if (cur !== this) {
            inner = cur?.takeIf { it.layer === layer }
            c.renderOverride = this
        }
        c.invalidateDoc(null)
    }

    /**
     * Replaces the edited objects by [replacements] (one step [label]); [onDone] tells whether it
     * was applied. A replacement whose id is one of [ids] takes that object's place (same z
     * position); edited objects without one are removed; replacements with any other id are new
     * objects placed right above the topmost edited one (they get new ids). The session ends
     * (it is uninstalled) first.
     */
    fun commit(replacements: List<VObject>, label: String, onDone: (Boolean) -> Unit = {}) {
        if (ended) { onDone(false); return }
        cancel()
        val content = layer.vector
        if (content == null || c.doc.indexOf(layer) < 0) { onDone(false); return }
        val present = ids.filter { content.byId(it) != null }
        val after = if (present.isEmpty()) {
            content.plus(replacements).first
        } else {
            val byId = HashMap<Long, VObject>()
            val extras = ArrayList<VObject>()
            for (r in replacements) {
                if (r.id in present && r.id !in byId) byId[r.id] = r else extras += r
            }
            val top = present.maxBy { content.indexOf(it) }
            val map = present.associateWith { id -> listOfNotNull(byId[id]) + (if (id == top) extras else emptyList()) }
            content.replaced(map)
        }
        if (after == content) { onDone(true); return }
        c.vectors.update(layer, after, label, onDone = onDone)
    }

    /** Ends the preview without changes. */
    fun cancel() {
        if (ended) return
        ended = true
        if (c.renderOverride === this) c.renderOverride = inner?.takeIf { it !== this }
        inner = null
        drawPreview = null
        hole?.let { if (!it.isRecycled) it.recycle() }
        hole = null
        c.invalidateDoc(null)
    }

    private companion object {
        /** A transparent stand-in for the layer's pixels (see [drawContent]). */
        val blank: Bitmap by lazy { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) }
    }
}
