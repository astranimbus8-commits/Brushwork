package com.brushwork.paint.tools.select

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.vector.CurveAnchor
import com.brushwork.paint.tools.vector.CurveGeometry
import com.brushwork.paint.tools.vector.VectorPath
import com.brushwork.paint.tools.vector.docLength
import com.brushwork.paint.tools.vector.toAndroidPath

/**
 * Curve mode of the lasso: tap to add anchor points and the outline runs smoothly through them
 * (closed Catmull-Rom spline, the curve tool's geometry). Tapping near the outline inserts a
 * point there; the finger can drag the new point before lifting. Any point can be dragged at any
 * time, a long press selects it (the options strip then offers sharp / smooth / delete), and
 * tapping the first point (or ✓) closes the outline into a selection. Undo / redo take back one
 * point edit at a time. While points are placed the outline is previewed with marching ants.
 *
 * Owned by [LassoTool], which forwards input in curve mode and turns the closed outline into
 * the selection ([close]). Main thread only.
 */
class LassoCurve internal constructor(
    private val controller: EditorController,
    /** The user closed the curve (tapped the first point): the tool turns it into the selection. */
    private val close: () -> Unit,
) {
    /** The placed points (Compose state: the strip shows ✓ / ✕ and the point count). */
    var anchors by mutableStateOf<List<CurveAnchor>>(emptyList())
        private set

    /** Index of the point whose actions the strip shows, or -1. */
    var selected by mutableIntStateOf(-1)
        private set

    /** Point edits [redoStep] can bring back (Compose state; the in-tool redo button). */
    var redoCount by mutableIntStateOf(0)
        private set

    val count: Int get() = anchors.size

    /** Edits [undoStep] can take back. */
    val undoCount: Int get() = history.size

    private val history = ArrayDeque<List<CurveAnchor>>()
    private val redo = ArrayDeque<List<CurveAnchor>>()

    private enum class Drag { NONE, ANCHOR, NEW }

    private var drag = Drag.NONE
    /** Anchor being dragged, or where the new point is inserted. */
    private var dragIndex = -1
    private var downPoint = Vec2.ZERO
    private var moved = false
    private var longPressed = false
    /** The new point under the finger (not in [anchors] until the finger lifts). */
    private var floating: Vec2? = null
    private var gestureStart: List<CurveAnchor> = emptyList()
    private var gestureSelected = -1
    private var gestureHistorySize = 0
    private var gestureRedo: List<List<CurveAnchor>> = emptyList()

    /** Outline being previewed (document px), rebuilt only when the points change. */
    private val previewPath = Path()
    private var previewSize = 0
    private val pts = FloatArray(2)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    // Marching ants of the preview: the canvas only animates a published selection, so the
    // outline schedules its own redraws while it is drawn (one pending tick at a time; created
    // on first use, when the canvas draws on the main thread).
    private var handler: Handler? = null
    private val tick = Runnable { if (previewSize >= 2) controller.invalidateOverlay() }

    private fun scheduleTick() {
        val h = handler ?: Handler(Looper.getMainLooper()).also { handler = it }
        h.removeCallbacks(tick)
        h.postDelayed(tick, ANTS_FRAME_MS)
    }

    private fun stopTicking() { handler?.removeCallbacks(tick) }

    // ------------------------------------------------------------------ edits

    /** Saves the points before an edit (and drops the redo steps: this is a new edit). */
    private fun pushHistory() {
        history.addLast(anchors)
        while (history.size > MAX_HISTORY) history.removeFirst()
        if (redo.isNotEmpty()) { redo.clear(); redoCount = 0 }
    }

    /** Takes back the last point edit (add, move, delete, sharp / smooth). */
    fun undoStep(): Boolean {
        val prev = history.removeLastOrNull() ?: return false
        redo.addLast(anchors)
        redoCount = redo.size
        set(prev)
        return true
    }

    /** Brings back the edit last taken back by [undoStep]. */
    fun redoStep(): Boolean {
        val next = redo.removeLastOrNull() ?: return false
        redoCount = redo.size
        history.addLast(anchors)
        while (history.size > MAX_HISTORY) history.removeFirst()
        set(next)
        return true
    }

    private fun set(list: List<CurveAnchor>) {
        anchors = list
        if (selected !in list.indices) selected = -1
        changed()
    }

    /** Shows the actions of point [index] in the strip (-1: none). */
    fun select(index: Int) {
        selected = if (index in anchors.indices) index else -1
        controller.invalidateOverlay()
    }

    fun deselect() = select(-1)

    /** Makes point [index] a corner (sharp) or part of the smooth curve. One undo step. */
    fun setSharp(index: Int, sharp: Boolean) {
        val a = anchors.getOrNull(index) ?: return
        if (a.sharp == sharp) return
        pushHistory()
        anchors = anchors.toMutableList().also { it[index] = a.copy(sharp = sharp) }
        changed()
    }

    /**
     * Removes point [index]. One undo step, except for the only point left: that throws the curve
     * away like ✕ (with no points there is nothing pending, so the app's undo would otherwise skip
     * the kept steps and act on the document history).
     */
    fun deleteAnchor(index: Int) {
        if (index !in anchors.indices) return
        if (anchors.size == 1) { clear(); return }
        pushHistory()
        anchors = anchors.toMutableList().also { it.removeAt(index) }
        selected = -1
        changed()
    }

    /** Appends a point at [p] (document px). One undo step. */
    fun addAnchor(p: Vec2, sharp: Boolean = false) {
        if (!p.x.isFinite() || !p.y.isFinite()) return
        pushHistory()
        anchors = anchors + CurveAnchor(p.x, p.y, sharp = sharp)
        selected = -1
        changed()
    }

    /** Forgets the points and their history (the curve was closed or thrown away). */
    fun clear() {
        anchors = emptyList()
        selected = -1
        history.clear()
        redo.clear()
        redoCount = 0
        drag = Drag.NONE
        floating = null
        changed()
        stopTicking()
    }

    /** The closed outline through the points (document px), or null with fewer than 3 points. */
    fun closedPath(): Path? = if (anchors.size >= 3) LassoCurveGeometry.path(anchors) else null

    // ------------------------------------------------------------------ input

    fun onDown(p: ToolPoint) {
        val pt = Vec2(p.x, p.y)
        // A degenerate view transform could map the finger to NaN: such a touch does nothing.
        if (!pt.x.isFinite() || !pt.y.isFinite()) { drag = Drag.NONE; return }
        downPoint = pt
        moved = false
        longPressed = false
        gestureStart = anchors
        gestureSelected = selected
        gestureHistorySize = history.size
        gestureRedo = redo.toList()
        val tol = controller.docLength(GRAB_DP)
        val idx = nearestAnchor(pt, tol)
        if (idx >= 0) {
            // Every point can be grabbed and moved at any time.
            drag = Drag.ANCHOR
            dragIndex = idx
        } else {
            // A new point: on the outline it is inserted there, elsewhere it is appended (which
            // on a closed outline is the same as inserting on the closing stretch).
            drag = Drag.NEW
            dragIndex = LassoCurveGeometry.insertIndex(anchors, pt, tol * INSERT_FRACTION)
            floating = pt
        }
        changed()
    }

    fun onMove(p: ToolPoint) {
        val pt = Vec2(p.x, p.y)
        if (!pt.x.isFinite() || !pt.y.isFinite()) return
        when (drag) {
            Drag.NONE -> return
            Drag.ANCHOR -> {
                if (!moved) {
                    if (pt.distanceTo(downPoint) < controller.docLength(SLOP_DP)) return
                    pushHistory()
                    moved = true
                }
                moveTo(dragIndex, pt)
            }
            Drag.NEW -> {
                floating = pt
                changed()
            }
        }
    }

    fun onUp(p: ToolPoint) {
        // (A non-finite lift-off sample keeps the last good position.)
        val pt = Vec2(p.x, p.y).takeIf { it.x.isFinite() && it.y.isFinite() }
            ?: floating ?: anchors.getOrNull(dragIndex)?.pos ?: downPoint
        val d = drag
        drag = Drag.NONE
        when (d) {
            Drag.NONE -> return
            Drag.ANCHOR -> when {
                moved -> moveTo(dragIndex, pt)
                longPressed -> {}
                // A tap on the first point closes the outline (like the polygon lasso).
                dragIndex == 0 && anchors.size >= 3 -> { floating = null; close(); return }
                else -> select(if (selected == dragIndex) -1 else dragIndex)
            }
            Drag.NEW -> {
                floating = null
                pushHistory()
                val at = dragIndex.coerceIn(0, anchors.size)
                anchors = anchors.toMutableList().also { it.add(at, CurveAnchor(pt.x, pt.y)) }
                selected = -1
            }
        }
        changed()
    }

    /** Drops the current gesture only: the points go back to how they were when it began. */
    fun onCancel() {
        if (drag == Drag.NONE) return
        drag = Drag.NONE
        floating = null
        anchors = gestureStart
        selected = if (gestureSelected in gestureStart.indices) gestureSelected else -1
        while (history.size > gestureHistorySize) history.removeLast()
        redo.clear(); redo.addAll(gestureRedo)
        redoCount = redo.size
        changed()
    }

    /**
     * A long press on a point selects it (the strip shows sharp / smooth / delete) and the finger
     * can still drag it. Elsewhere it is not handled.
     */
    fun onLongPress(p: ToolPoint): Boolean {
        if (drag != Drag.ANCHOR) return false
        if (!moved) {
            longPressed = true
            select(dragIndex)
        }
        return true
    }

    /** The lasso stops being the current tool (or the editor closes). */
    fun onDeactivate() {
        if (drag != Drag.NONE) onCancel()
        stopTicking()
    }

    private fun moveTo(index: Int, p: Vec2) {
        val a = anchors.getOrNull(index) ?: return
        if (a.pos == p) return
        anchors = anchors.toMutableList().also { it[index] = a.moved(p) }
        changed()
    }

    private fun nearestAnchor(p: Vec2, tol: Float): Int {
        var best = -1
        var bestD = tol
        // Later points win ties (the newest one is grabbed when points overlap).
        for (i in anchors.indices.reversed()) {
            val d = anchors[i].pos.distanceTo(p)
            if (d < bestD) { best = i; bestD = d }
        }
        return best
    }

    // ------------------------------------------------------------------ preview

    /** The points as currently shown, including the new one under the finger. */
    internal fun previewAnchors(): List<CurveAnchor> {
        val f = floating
        if (drag != Drag.NEW || f == null) return anchors
        return anchors.toMutableList().also { it.add(dragIndex.coerceIn(0, anchors.size), CurveAnchor(f.x, f.y)) }
    }

    private fun changed() {
        val list = previewAnchors()
        previewSize = list.size
        if (list.size >= 2) LassoCurveGeometry.outline(list).toAndroidPath(previewPath) else previewPath.rewind()
        controller.invalidateOverlay()
    }

    fun drawOverlay(canvas: Canvas, t: ViewTransform) {
        if (previewSize == 0) return
        if (previewSize >= 2) {
            SelectionOverlay.drawDocPath(canvas, t, previewPath, antsPhase())
            scheduleTick()
        }
        val list = anchors
        val closable = list.size >= 3
        val draggedIndex = if (drag == Drag.ANCHOR && moved) dragIndex else -1
        for (i in list.indices) {
            map(t, list[i].pos)
            drawAnchor(canvas, t, pts[0], pts[1], list[i].sharp, active = i == selected || i == draggedIndex, closeHint = i == 0 && closable)
        }
        floating?.let { f ->
            if (drag == Drag.NEW) {
                map(t, f)
                drawAnchor(canvas, t, pts[0], pts[1], sharp = false, active = true, closeHint = false)
            }
        }
    }

    private fun map(t: ViewTransform, p: Vec2) {
        pts[0] = p.x; pts[1] = p.y
        t.matrix.mapPoints(pts)
    }

    /** A point handle: square when sharp, round when smooth; the first point is ringed once it closes the outline. */
    private fun drawAnchor(canvas: Canvas, t: ViewTransform, x: Float, y: Float, sharp: Boolean, active: Boolean, closeHint: Boolean) {
        val r = t.dp(if (active) 7f else 5.5f)
        if (closeHint) {
            edge.color = ACCENT
            edge.strokeWidth = t.dp(2f)
            canvas.drawCircle(x, y, r + t.dp(5f), edge)
        }
        fill.color = if (active) ACCENT else WHITE
        edge.color = if (active) WHITE else DARK
        edge.strokeWidth = t.dp(1.5f)
        if (sharp) {
            canvas.drawRect(x - r, y - r, x + r, y + r, fill)
            canvas.drawRect(x - r, y - r, x + r, y + r, edge)
        } else {
            canvas.drawCircle(x, y, r, fill)
            canvas.drawCircle(x, y, r, edge)
        }
    }

    private fun antsPhase(): Float = (SystemClock.uptimeMillis() % 3_600_000L) / ANTS_FRAME_MS.toFloat()

    companion object {
        /** Grab radius of points (screen dp): generous for fingers. */
        private const val GRAB_DP = 24f
        /** A touch on a point must move this far (dp) before it drags the point. */
        private const val SLOP_DP = 6f
        /** A new point lands ON the outline when the tap is this share of [GRAB_DP] from it. */
        private const val INSERT_FRACTION = 0.6f
        private const val MAX_HISTORY = 200
        /** Marching-ants frame time, as for the selection outline (~15 fps). */
        private const val ANTS_FRAME_MS = 66L
        private const val ACCENT = 0xFF4DA3FF.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val DARK = 0xFF1E1F22.toInt()
    }
}

/** Geometry of the curve lasso (the curve tool's closed spline at zero tension). */
internal object LassoCurveGeometry {

    /**
     * The outline through [anchors]: a closed smooth curve from 3 points on, a straight stretch
     * for 2. Sharp points make corners; with every point sharp it is the polygon.
     */
    fun outline(anchors: List<CurveAnchor>): VectorPath =
        CurveGeometry.toPath(anchors, closed = true, tension = 0f, polyline = false)

    /** [outline] as an android Path (document px). */
    fun path(anchors: List<CurveAnchor>): Path = outline(anchors).toAndroidPath(Path())

    /**
     * Where a new point at [p] goes: on the stretch of the outline within [tolerance] of it
     * (inserted between that stretch's points), otherwise after the last point.
     */
    fun insertIndex(anchors: List<CurveAnchor>, p: Vec2, tolerance: Float): Int {
        if (anchors.size >= 2 && tolerance > 0f) {
            val hit = CurveGeometry.nearest(anchors, p, closed = true, tension = 0f, polyline = false)
            if (hit != null && hit.distance <= tolerance) return hit.segment + 1
        }
        return anchors.size
    }
}
