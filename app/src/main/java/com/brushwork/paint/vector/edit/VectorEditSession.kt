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
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorLayers
import com.brushwork.paint.vector.VectorOps
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Preview of objects being edited (reopened path or shape, transform lift; v1.5 §5.4; API
 * frozen, owned by A1): draws the layer's cache with a hole where the edited objects were plus
 * [drawPreview]; adopts a painting tool's live override as [inner] (a brush-stroked path being
 * re-edited). The layer itself is untouched until [commit] (crash-safe; autosave stays
 * consistent). Created by `VectorLayers.beginEdit`, which installs it as the controller's render
 * override.
 *
 * The hole: inside [holeRect] (the edited objects' paint bounds within the document, grown to the
 * renderer's tile grid) the other objects are shown re-rendered ([hole], drawn scaled by
 * 1 / [holeScale]; null = nothing there), outside it the cache as it is.
 *
 * [commit] keeps the preview up while the new content renders in the background (a large edit):
 * the session stays installed, showing [drawBase] + [drawPreview], until the result is applied.
 * Set [drawPreview] to what the edited objects become before committing, and leave the session
 * installed (a later [cancel] is a no-op once committed).
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
     * null when that stroke ends while this session stays installed. While it draws its content,
     * the layer's bitmap is a 1 x 1 transparent stand-in (so the edited objects don't ghost
     * through it): it may draw `layer.bitmap`, but must not size or place anything by it
     * (BrushTool's, ShapeTool's and the vector previews' overrides only draw it at the origin).
     */
    var inner: LayerRenderOverride? = null

    private val filtered = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Committed or cancelled (no more edits through this session). */
    private var ended = false

    /** Uninstalled and its hole freed. */
    private var released = false

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

    /** The mask as the adopted [inner] override shows it (a live stroke into the mask), else as it is. */
    override fun drawMask(canvas: Canvas, maskPaint: Paint): Boolean {
        val i = inner
        return i != null && i !== this && i.layer === layer && i.drawMask(canvas, maskPaint)
    }

    /**
     * Re-installs this session as the controller's override with the override installed now as
     * [inner] (a painting tool's live stroke on the same layer, e.g. from
     * `BrushStrokePreview.onLiveChanged`). When this session is still the installed override
     * nothing changes: once the live stroke ends, set [inner] to null (the session can't tell a
     * finished stroke from a running one), e.g. `onLiveChanged = { if (preview.isLive)
     * session.adoptInner() else session.inner = null }`. Does nothing once the session ended.
     */
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
     * position, same id); edited objects without one are removed; replacements with any other id
     * are new objects placed right above the topmost edited one, with new ids. The session ends
     * at once; it is uninstalled (see [cancel]) right before the layer's pixels change — so a
     * large edit rendering in the background keeps this preview on screen until it lands. A
     * pure whole-pixel move (each replacement is `VectorOps.transformed` of its object by an
     * integer translation) goes through `VectorLayers.update`'s shift fast path.
     */
    fun commit(replacements: List<VObject>, label: String, onDone: (Boolean) -> Unit = {}) {
        if (ended) { onDone(false); return }
        ended = true
        // An edit of this layer still rendering lands first (the replacements apply to its result).
        c.vectors.flushPending()
        val content = layer.vector
        if (content == null || c.doc.indexOf(layer) < 0) { release(); onDone(false); return }
        val present = ids.filterTo(HashSet()) { content.byId(it) != null }
        val after = if (present.isEmpty()) {
            // The edited objects are gone meanwhile: the replacements become new top objects.
            content.plus(replacements).first
        } else {
            val byId = HashMap<Long, VObject>()
            val extras = ArrayList<VObject>()
            for (r in replacements) {
                if (r.id in present && r.id !in byId) byId[r.id] = r else extras += r
            }
            val top = present.maxBy { content.indexOf(it) }
            var next = content.nextId
            val out = ArrayList<VObject>(content.objects.size + extras.size)
            for (o in content.objects) {
                if (o.id !in present) { out += o; continue }
                byId[o.id]?.let { out += it.withId(o.id) }
                if (o.id == top) for (e in extras) out += e.withId(next++)
            }
            content.copy(objects = out, nextId = next)
        }
        if (after == content) { release(); onDone(true); return }
        c.vectors.updateInternal(layer, after, label, null, pureMove(content, present, replacements), { release() }, onDone, 0)
    }

    /**
     * The edit as a [VectorLayers.ShiftHint] when it only moves the edited objects by whole
     * pixels (every replacement is exactly its object under that translation, as
     * `VectorOps.transformed` maps it, and nothing is added or removed): the update can then move
     * the cache pixels instead of rendering (a lifted drawing dragged with Transform). Null
     * otherwise.
     */
    private fun pureMove(content: VectorContent, present: Set<Long>, replacements: List<VObject>): VectorLayers.ShiftHint? {
        if (replacements.size != present.size || present.isEmpty()) return null
        val byId = HashMap<Long, VObject>(replacements.size * 2)
        for (r in replacements) if (r.id !in present || byId.put(r.id, r) != null) return null
        val first = content.byId(present.first()) ?: return null
        val moved = byId[first.id] ?: return null
        val ob = VectorOps.bounds(first)
        val nb = VectorOps.bounds(moved)
        if (ob.isEmpty || nb.isEmpty) return null
        val fx = nb.left - ob.left
        val fy = nb.top - ob.top
        if (!fx.isFinite() || !fy.isFinite() || abs(fx) > 1e7f || abs(fy) > 1e7f) return null
        val dx = fx.roundToInt()
        val dy = fy.roundToInt()
        if (dx == 0 && dy == 0) return null
        val m = floatArrayOf(1f, 0f, dx.toFloat(), 0f, 1f, dy.toFloat(), 0f, 0f, 1f)
        for (id in present) {
            val o = content.byId(id) ?: return null
            if (byId[id] != VectorOps.transformed(o, m)) return null
        }
        return VectorLayers.ShiftHint(present, dx, dy)
    }

    /**
     * Ends the preview without changes: the session is uninstalled (when it is the controller's
     * override, [inner] is installed again, so a live stroke it showed keeps showing) and its
     * hole bitmap is freed; [floating] is left to its holder. Safe to call more than once, and a
     * no-op after [commit] (the commit uninstalls the session when its result lands).
     */
    fun cancel() {
        if (ended) return
        ended = true
        release()
    }

    /** Uninstalls the session and frees its hole (once). */
    private fun release() {
        if (released) return
        released = true
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
