package com.brushwork.paint.vector.select

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.model.SelectionMode
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.lift.LiftGeometry
import com.brushwork.paint.vector.lift.VectorLift
import kotlinx.coroutines.Job
import java.util.WeakHashMap

/**
 * Object selection on vector layers (v1.5 §4.9, owned by A2): Lasso / Select shape areas select
 * the objects they touch (New / Add / Subtract / Intersect), shown with dashed boxes. Reached
 * through `VectorLayers.selectObjects` / `drawOverlay`.
 *
 * The selection itself is `controller.vectors.setSelection` (runtime only, not history): selecting
 * objects records no undo step and leaves any pixel selection as it is. While objects are
 * selected the editor shows the Object bar (`ui/vector/VectorObjectBar`) instead of the
 * selection bar, and the Transform tool lifts exactly them.
 */
object VectorObjectSelection {
    /** Per editor: the background search of a large selection still running. */
    private class State {
        var job: Job? = null
    }

    private val states = WeakHashMap<EditorController, State>()

    private fun state(c: EditorController): State = states.getOrPut(c) { State() }

    /**
     * Selects the objects of the active vector layer that [sel] touches, combined by [mode] with
     * the objects selected there now; false = not handled (not a vector layer). Small searches
     * finish before this returns; large ones in the background (the selection changes when they
     * are done; a newer selection cancels them).
     */
    fun select(c: EditorController, sel: Selection, mode: SelectionMode): Boolean {
        val layer = c.activeLayer
        val content = layer.vector ?: return false
        val st = state(c)
        st.job?.cancel()
        st.job = null
        val job = ObjectTouch.run(c, content, sel, "Selecting objects…") { touched ->
            st.job = null
            apply(c, layer, touched, mode)
        }
        if (job != null && job.isActive) {
            st.job = job
            // (A finished search is not kept: nothing here refers to the editor afterwards.)
            job.invokeOnCompletion { if (st.job === job) st.job = null }
        }
        return true
    }

    /** Publishes the objects [touched] on [layer] combined by [mode] (layer still active, ids still there). */
    private fun apply(c: EditorController, layer: Layer, touched: Set<Long>, mode: SelectionMode) {
        val content = layer.vector ?: return
        if (c.doc.indexOf(layer) < 0 || c.activeLayer !== layer) return
        val exists = HashSet<Long>(content.objects.size * 2)
        for (o in content.objects) exists += o.id
        val found = touched.filterTo(LinkedHashSet()) { it in exists }
        val v = c.vectors
        val base = if (v.selectedLayer === layer) v.selectedIds else emptySet()
        val result: Set<Long> = when (mode) {
            SelectionMode.REPLACE -> found
            SelectionMode.ADD -> LinkedHashSet(base).apply { addAll(found) }
            SelectionMode.SUBTRACT -> base.filterTo(LinkedHashSet()) { it !in found }
            SelectionMode.INTERSECT -> base.filterTo(LinkedHashSet()) { it in found }
        }
        v.setSelection(layer, result)
        if (found.isEmpty() && (mode == SelectionMode.REPLACE || mode == SelectionMode.ADD)) c.toast(NOTHING_THERE)
    }

    /** Shown when an area touches no object. */
    const val NOTHING_THERE = "No objects there"

    /**
     * The selected objects of the active layer, in stacking order (bottom first), with their
     * layer; null when none are selected there.
     */
    internal fun selected(c: EditorController): Pair<Layer, List<VObject>>? {
        val v = c.vectors
        val ids = v.selectedIds
        if (ids.isEmpty()) return null
        val layer = v.selectedLayer ?: return null
        val content = layer.vector ?: return null
        val objs = content.objects.filter { it.id in ids }
        return if (objs.isEmpty()) null else layer to objs
    }

    // ------------------------------------------------------------------ overlay

    /** More selected objects than this show only their common box (drawing stays cheap). */
    internal const val MAX_BOXES = 300

    private val boxPath = Path()
    private val unionPath = Path()
    private val screenPath = Path()
    private val unionRect = RectF()
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x99000000.toInt() }
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = ACCENT }
    private val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = ACCENT }
    private var dashDensity = 0f

    /**
     * Dashed boxes around the selected objects of the active layer (screen space; [t] maps
     * document -> screen), plus their common box while the Transform tool is not active (it
     * draws its own). While the Transform tool moves the lifted objects, their boxes follow.
     */
    fun drawOverlay(c: EditorController, canvas: Canvas, t: ViewTransform) {
        val v = c.vectors
        val ids = v.selectedIds
        if (ids.isEmpty()) return
        val layer = v.selectedLayer ?: return
        if (layer !== c.activeLayer) return
        val content = layer.vector ?: return
        val transformActive = c.activeToolId == ToolId.TRANSFORM
        // Lifted objects being transformed: their boxes go where the transform puts them.
        var m: FloatArray? = null
        var lifted: Set<Long> = emptySet()
        if (transformActive) {
            val st = (c.tools[ToolId.TRANSFORM] as? TransformTool)?.transformState
            val lift = VectorLift.activeLift(c)
            if (st != null && lift != null && lift.layer === layer) {
                m = LiftGeometry.matrix(st, lift.sourceRect.left, lift.sourceRect.top)
                lifted = lift.ids
            }
        }
        boxPath.rewind()
        unionPath.rewind()
        unionRect.setEmpty()
        var count = 0
        for (o in content.objects) if (o.id in ids) count++
        val each = count <= MAX_BOXES
        for (o in content.objects) {
            if (o.id !in ids) continue
            val b = ObjectBounds.of(content, o)
            if (b.isEmpty) continue
            val mm = if (o.id in lifted) m else null
            if (mm == null) {
                unionRect.union(b)
                if (each) boxPath.addRect(b, Path.Direction.CW)
            } else {
                val q = arrayOf(
                    LiftGeometry.map(mm, b.left, b.top), LiftGeometry.map(mm, b.right, b.top),
                    LiftGeometry.map(mm, b.right, b.bottom), LiftGeometry.map(mm, b.left, b.bottom),
                )
                // (RectF.union(x, y) would also take in the origin of an empty rect.)
                unionRect.union(
                    minOf(q[0].x, q[1].x, q[2].x, q[3].x), minOf(q[0].y, q[1].y, q[2].y, q[3].y),
                    maxOf(q[0].x, q[1].x, q[2].x, q[3].x), maxOf(q[0].y, q[1].y, q[2].y, q[3].y),
                )
                if (each) addQuad(boxPath, q)
            }
        }
        if (unionRect.isEmpty && boxPath.isEmpty) return
        prepare(t)
        if (each && !boxPath.isEmpty) {
            boxPath.transform(t.matrix, screenPath)
            canvas.drawPath(screenPath, shadow)
            canvas.drawPath(screenPath, dashed)
        }
        // The common box: when the Transform tool isn't there to show it, or instead of too many boxes.
        if ((!transformActive && count > 1) || !each) {
            unionPath.addRect(unionRect, Path.Direction.CW)
            unionPath.transform(t.matrix, screenPath)
            canvas.drawPath(screenPath, shadow)
            canvas.drawPath(screenPath, solid)
        }
    }

    private fun addQuad(path: Path, q: Array<Vec2>) {
        path.moveTo(q[0].x, q[0].y)
        for (i in 1 until 4) path.lineTo(q[i].x, q[i].y)
        path.close()
    }

    private fun prepare(t: ViewTransform) {
        if (dashDensity != t.density) {
            dashDensity = t.density
            dashed.pathEffect = DashPathEffect(floatArrayOf(t.dp(5f), t.dp(4f)), 0f)
        }
        shadow.strokeWidth = t.dp(3f)
        dashed.strokeWidth = t.dp(1.25f)
        solid.strokeWidth = t.dp(1.5f)
    }

    private const val ACCENT = 0xFF4DA3FF.toInt()
}
