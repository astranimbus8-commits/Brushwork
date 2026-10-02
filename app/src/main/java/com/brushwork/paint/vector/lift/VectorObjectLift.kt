package com.brushwork.paint.vector.lift

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.LayerRenderOverride
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.transform.ObjectLift
import com.brushwork.paint.tools.transform.TransformState
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorLayers
import com.brushwork.paint.vector.VectorOps
import com.brushwork.paint.vector.edit.VectorEditSession
import com.brushwork.paint.vector.select.PendingRenders

/**
 * Vector objects lifted by the Transform tool (v1.5 §4.9, A2): a [VectorEditSession] (the layer's
 * cache with the objects' hole, and the objects rendered alone as [floating]) whose ✓ maps the
 * objects' GEOMETRY exactly instead of resampling pixels: stroke points (their brush size scaled by
 * √|det|), path anchors and handles, shapes as shapes under similarities and as paths otherwise
 * (`VectorOps.transformed`; Distort is a homography). A move by whole pixels is passed on as a
 * [VectorLayers.ShiftHint], so the cache can be shifted instead of re-rendered.
 *
 * When `vectors.update` renders in the background, the result keeps showing (the hole plus the
 * objects where the transform put them, as a render override) until the new pixels land; the
 * preview's bitmaps are freed then, even if the Transform tool released the lift before. Lifts and
 * Object bar actions asked for meanwhile wait for it ([PendingRenders]), so they start from the
 * landed content.
 */
internal class VectorObjectLift(
    private val c: EditorController,
    private val session: VectorEditSession,
    /** The lifted objects (their ids stay the same through ✓). */
    val ids: Set<Long>,
    /** Applies a new content as one step: `vectors.update` (see [VectorLiftProvider.update]). */
    private val update: (Layer, VectorContent, String, VectorLayers.ShiftHint?, (Boolean) -> Unit) -> Unit,
    private val onEnded: (VectorObjectLift) -> Unit,
) : ObjectLift {
    override val layer: Layer get() = session.layer

    override val floating: Bitmap = requireNotNull(session.floating) { "A lift needs a floating preview" }

    override val sourceRect: Rect = Rect(session.floatingRect)

    override val floatingScale: Float = session.floatingScale

    /** The session ended: its hole is freed and nothing can be applied any more. */
    private var ended = false

    /** A commit or delete waits for its background render (the preview stays until then). */
    private var applying = false

    /** The Transform tool released the lift while [applying]: free everything once applied. */
    private var releaseWanted = false

    /** True while the Transform tool can still commit, delete or show it. */
    val isOpen: Boolean get() = !ended && !applying

    /** True while its result renders in the background (the preview shows it where it goes). */
    val isLanding: Boolean get() = !ended && applying

    /**
     * Where the lifted objects go while [isLanding] (document -> document, 3x3 row-major), null
     * when they are being deleted (or not landing).
     */
    var landingMatrix: FloatArray? = null
        private set

    override fun drawBase(canvas: Canvas) {
        if (!ended) session.drawBase(canvas)
    }

    /**
     * Maps the lifted objects by [state] (one undo step [label] through `vectors.update`, the ids
     * and stacking order kept). False when nothing changed (the objects are gone, the layer
     * refused, an unusable state).
     */
    override fun commit(state: TransformState, label: String): Boolean {
        if (!isOpen) return false
        val m0 = LiftGeometry.matrix(state, sourceRect.left, sourceRect.top) ?: return finish(false)
        val shift = LiftGeometry.wholePixelShift(m0)
        if (shift != null && shift[0] == 0 && shift[1] == 0) return finish(false)
        // A whole-pixel move maps the data by exactly that many pixels (the cache can be shifted).
        val m = if (shift != null) LiftGeometry.translation(shift[0].toFloat(), shift[1].toFloat()) else m0
        val content = currentContent() ?: return finish(false)
        val map = HashMap<Long, List<VObject>>()
        for (o in content.objects) if (o.id in ids) map[o.id] = listOf(VectorOps.transformed(o, m))
        if (map.isEmpty()) return finish(false)
        val hint = shift?.let { VectorLayers.ShiftHint(map.keys.toSet(), it[0], it[1]) }
        return apply(content.replaced(map), label, m, hint) {}
    }

    /** Deletes the lifted objects (one undo step [label]); they leave the object selection. */
    override fun delete(label: String): Boolean {
        if (!isOpen) return false
        val content = currentContent() ?: return finish(false)
        val after = content.without(ids)
        if (after === content) return finish(false)
        return apply(after, label, null, null) {
            val v = c.vectors
            if (v.selectedLayer === layer) v.setSelection(layer, v.selectedIds - ids)
        }
    }

    override fun release() {
        if (applying) {
            releaseWanted = true
            return
        }
        end()
        if (!floating.isRecycled) floating.recycle()
    }

    /** The layer's content when the lift can still be applied to it, else null. */
    private fun currentContent(): VectorContent? = layer.vector?.takeIf { c.doc.indexOf(layer) >= 0 }

    /**
     * Applies [after] as one step [label]. Done at once: the lift ends. Rendered in the background:
     * the hole plus the objects drawn by [m] (null: nothing, a delete) stay on screen until the
     * new pixels land. [then] runs once applied.
     */
    private inline fun apply(after: VectorContent, label: String, m: FloatArray?, hint: VectorLayers.ShiftHint?, crossinline then: () -> Unit): Boolean {
        applying = true
        var result: Boolean? = null
        var waiting: LayerRenderOverride? = null
        // Work on these objects queued meanwhile (a new lift, an Object bar action) waits for it.
        val landed = PendingRenders.begin(c)
        try {
            update(layer, after, label, hint) { applied ->
                if (result != null) return@update // (reported twice: once is enough)
                result = applied
                if (applied) then()
                val w = waiting
                if (w != null) {
                    // The background render landed: the real pixels replace the preview.
                    if (c.renderOverride === w) c.renderOverride = null
                    c.invalidateDoc(null)
                    applying = false
                    landingMatrix = null
                    end()
                    if (releaseWanted && !floating.isRecycled) floating.recycle()
                    landed()
                }
            }
        } catch (e: Throwable) {
            // The update failed outright: nothing waits for it, the lift ends unchanged.
            applying = false
            end()
            landed()
            throw e
        }
        val r = result
        if (r != null) {
            applying = false
            val done = finish(r)
            landed()
            return done
        }
        // Rendering in the background: keep showing the result meanwhile.
        landingMatrix = m
        val preview = Pending(m)
        waiting = preview
        c.renderOverride = preview
        c.invalidateDoc(null)
        return true
    }

    /** The lift ends (no change pending); returns [result]. */
    private fun finish(result: Boolean): Boolean {
        end()
        return result
    }

    /** The preview ends: the session lets go of the layer (and of its hole bitmap). */
    private fun end() {
        if (ended) return
        ended = true
        session.cancel()
        onEnded(this)
    }

    /** What the layer shows while a commit renders in the background: the hole plus the objects where they go. */
    private inner class Pending(m: FloatArray?) : LayerRenderOverride {
        override val layer: Layer get() = this@VectorObjectLift.layer

        /** Floating px -> document: onto the source rect (1 / floatingScale), then [m]. */
        private val matrix: Matrix? = m?.let { values ->
            Matrix().apply {
                setValues(values)
                preTranslate(sourceRect.left.toFloat(), sourceRect.top.toFloat())
                if (floatingScale != 1f) preScale(1f / floatingScale, 1f / floatingScale)
            }
        }
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        override fun drawContent(canvas: Canvas): Boolean {
            if (ended) return false
            session.drawBase(canvas)
            val mm = matrix
            if (mm != null && !floating.isRecycled) canvas.drawBitmap(floating, mm, paint)
            return true
        }
    }
}
