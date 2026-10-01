package com.brushwork.paint.vector.lift

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.transform.ObjectLift
import com.brushwork.paint.tools.transform.TransformState
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VectorLayers
import com.brushwork.paint.vector.VectorOps
import com.brushwork.paint.vector.edit.VectorEditSession

/**
 * Vector objects lifted by the Transform tool (v1.5 §4.9, A2): a [VectorEditSession] (the layer's
 * cache with the objects' hole, and the objects rendered alone as [floating]) whose ✓ maps the
 * objects' GEOMETRY exactly instead of resampling pixels: stroke points (their brush size scaled by
 * √|det|), path anchors and handles, shapes as shapes under similarities and as paths otherwise
 * (`VectorOps.transformed`; Distort is a homography). A move by whole pixels is passed on as a
 * [VectorLayers.ShiftHint], so the cache can be shifted instead of re-rendered.
 */
internal class VectorObjectLift(
    private val c: EditorController,
    private val session: VectorEditSession,
    /** The lifted objects (their ids stay the same through ✓). */
    val ids: Set<Long>,
    private val onEnded: (VectorObjectLift) -> Unit,
) : ObjectLift {
    override val layer: Layer get() = session.layer

    override val floating: Bitmap = requireNotNull(session.floating) { "A lift needs a floating preview" }

    override val sourceRect: Rect = Rect(session.floatingRect)

    override val floatingScale: Float = session.floatingScale

    private var ended = false

    /** True until it was committed, deleted or released. */
    val isOpen: Boolean get() = !ended

    override fun drawBase(canvas: Canvas) {
        if (!ended) session.drawBase(canvas)
    }

    /**
     * Maps the lifted objects by [state] (one undo step [label] through `vectors.update`, the ids
     * and stacking order kept). False when nothing changed (the objects are gone, the layer
     * refused, an unusable state).
     */
    override fun commit(state: TransformState, label: String): Boolean {
        if (ended) return false
        val raw = LiftGeometry.matrix(state, sourceRect.left, sourceRect.top)
        end()
        val m0 = raw ?: return false
        val shift = LiftGeometry.wholePixelShift(m0)
        // A whole-pixel move maps the data by exactly that many pixels (the cache can be shifted).
        val m = if (shift != null) LiftGeometry.translation(shift[0].toFloat(), shift[1].toFloat()) else m0
        if (shift != null && shift[0] == 0 && shift[1] == 0) return false
        val l = layer
        val content = l.vector ?: return false
        if (c.doc.indexOf(l) < 0) return false
        val map = HashMap<Long, List<VObject>>()
        for (o in content.objects) if (o.id in ids) map[o.id] = listOf(VectorOps.transformed(o, m))
        if (map.isEmpty()) return false
        val after = content.replaced(map)
        val hint = shift?.let { VectorLayers.ShiftHint(map.keys.toSet(), it[0], it[1]) }
        var result: Boolean? = null
        c.vectors.update(l, after, label, shift = hint) { applied -> result = applied }
        // Applied later (a background render) unless it already said otherwise.
        return result ?: true
    }

    /** Deletes the lifted objects (one undo step [label]). */
    override fun delete(label: String): Boolean {
        if (ended) return false
        end()
        val l = layer
        val content = l.vector ?: return false
        if (c.doc.indexOf(l) < 0) return false
        val after = content.without(ids)
        if (after === content) return false
        var result: Boolean? = null
        c.vectors.update(l, after, label) { applied -> result = applied }
        val done = result ?: true
        if (done) {
            val v = c.vectors
            if (v.selectedLayer === l) v.setSelection(l, v.selectedIds - ids)
        }
        return done
    }

    override fun release() {
        end()
        if (!floating.isRecycled) floating.recycle()
    }

    /** The preview ends: the session lets go of the layer (and of its hole bitmap). */
    private fun end() {
        if (ended) return
        ended = true
        session.cancel()
        onEnded(this)
    }
}
