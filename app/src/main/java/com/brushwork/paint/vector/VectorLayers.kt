package com.brushwork.paint.vector

import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.StrokeHook
import com.brushwork.paint.brush.StrokeInfo
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.model.SelectionMode
import com.brushwork.paint.tools.transform.ObjectLiftProvider
import com.brushwork.paint.vector.draw.VectorStrokeCapture
import com.brushwork.paint.vector.edit.VectorEditSession
import com.brushwork.paint.vector.lift.VectorLift
import com.brushwork.paint.vector.select.VectorObjectSelection

/**
 * The vector layer service of one editor (`EditorController.vectors`, v1.5 §5.4). Its API is
 * frozen; the implementation is owned by A1 (VEC-CORE): F2 writes a synchronous reference (clear
 * and replay the intersecting objects on the main thread), A1 replaces the internals (cost
 * model, async patches, spatial grid, edit sessions) behind the same API.
 *
 * Every edit keeps a vector layer's pixels (its render cache) equal to the rendering of its
 * [VectorContent], changing both in ONE undo step on the main thread (I1, I2).
 *
 * Foundation state (F1): only [appendData], the object selection and the seams are real; the
 * rendering calls are safe no-ops.
 */
class VectorLayers internal constructor(private val c: EditorController) {

    /** New topmost objects: drawn over the cache (no re-render), tiles + LayerDataAction, one step. Returns the new ids. */
    fun addObjects(layer: Layer, objects: List<VObject>, label: String): List<Long> = emptyList()

    /** Data-only append; the caller already committed the pixels inside keepLayerData (live brush stroke). Call inside groupUndo. */
    fun appendData(layer: Layer, objects: List<VObject>, label: String): List<Long> {
        if (objects.isEmpty() || c.doc.indexOf(layer) < 0) return emptyList()
        val before = layer.dataSnapshot()
        val (content, ids) = (before.vector ?: VectorContent.EMPTY).plus(objects)
        c.setLayerData(layer, before.copy(vector = content), label)
        return ids
    }

    /**
     * New content; re-renders [dirty] (null = union of changed objects' tiles). Sync or async;
     * data and pixels are applied together (I1). [onDone] tells whether it was applied.
     */
    fun update(
        layer: Layer,
        after: VectorContent,
        label: String,
        dirty: List<Rect>? = null,
        /** A pure whole-pixel move of [ShiftHint.ids]: cache tiles shifted, no re-render (F2: ignored). */
        shift: ShiftHint? = null,
        onDone: (applied: Boolean) -> Unit = {},
    ) {
        onDone(false)
    }

    /** A pure whole-pixel translation of the objects [ids] by ([dx], [dy]) document px. */
    data class ShiftHint(val ids: Set<Long>, val dx: Int, val dy: Int)

    private var rendering by mutableStateOf(false)

    /** True while a render runs in the background (Compose state). */
    val isRendering: Boolean get() = rendering

    /** Everything [obj] can paint (document px, including the brush radius). */
    fun paintBounds(obj: VObject): RectF = VectorOps.bounds(obj)

    /** The topmost object of [layer] under [p] within [tolDoc] px; [below] = only objects under that id (cycles overlaps). */
    fun hitTest(layer: Layer, p: Vec2, tolDoc: Float, below: Long? = null): VObject? = null

    /** Ids of the objects of [layer] that [sel] touches. */
    fun touching(layer: Layer, sel: Selection): Set<Long> {
        val content = layer.vector ?: return emptySet()
        return VectorOps.touching(content, sel)
    }

    /** Prepares an edit preview of the objects [ids] (hole + floating); [onReady] gets null when refused. */
    fun beginEdit(layer: Layer, ids: Set<Long>, onReady: (VectorEditSession?) -> Unit) {
        onReady(null)
    }

    // ------------------------------------------------------------------ object selection
    // Runtime only, not history; cleared when the layer goes or its ids vanish.

    private var selLayer by mutableStateOf<Layer?>(null)
    private var selIds by mutableStateOf<Set<Long>>(emptySet())

    /** The vector layer whose objects are selected (null when none is). */
    val selectedLayer: Layer?
        get() {
            c.layersVersion // re-read when layers change (deleted, rasterized)
            val l = selLayer ?: return null
            return if (l.isVectorLayer && c.doc.indexOf(l) >= 0) l else null
        }

    /** The selected objects of [selectedLayer] that still exist (Compose state). */
    val selectedIds: Set<Long>
        get() {
            val ids = selIds
            if (ids.isEmpty()) return ids
            val content = selectedLayer?.vector ?: return emptySet()
            return if (ids.all { content.byId(it) != null }) ids else ids.filterTo(HashSet()) { content.byId(it) != null }
        }

    /** Selects the objects [ids] of [layer] (null or no ids = nothing selected). */
    fun setSelection(layer: Layer?, ids: Set<Long>) {
        if (layer == null || ids.isEmpty()) {
            selLayer = null
            selIds = emptySet()
        } else {
            selLayer = layer
            selIds = ids.toSet()
        }
        c.invalidateOverlay()
    }

    // ------------------------------------------------------------------ seams
    // Lines written by the lead, delegating to area-owned objects; A1 must keep them.

    /** Decides what a starting brush stroke does on a vector layer (A3). */
    fun strokeHook(info: StrokeInfo): StrokeHook = VectorStrokeCapture.hookFor(c, info)

    /** Lifts vector objects for the Transform tool (A2). */
    val liftProvider: ObjectLiftProvider get() = VectorLift.provider(c)

    /** Lasso / Select shape results on a vector layer select objects (A2). True when handled. */
    fun selectObjects(sel: Selection, mode: SelectionMode): Boolean = VectorObjectSelection.select(c, sel, mode)

    /** Object selection feedback (A2). */
    fun drawOverlay(canvas: Canvas, t: ViewTransform) = VectorObjectSelection.drawOverlay(c, canvas, t)

    /** The editor closes: drop every cache and background job. */
    fun dispose() {
        selLayer = null
        selIds = emptySet()
        rendering = false
    }
}
