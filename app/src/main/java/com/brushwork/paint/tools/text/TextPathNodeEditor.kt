package com.brushwork.paint.tools.text

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.InToolHistoryOwner
import com.brushwork.paint.tools.ToolHistory
import com.brushwork.paint.ui.common.TextLabels19
import com.brushwork.paint.vector.VSpline

/** v1.9 (R12): the path sheet's three ways into node editing ([TextPathNodeEditor.start]). */
enum class TextPathNodeEntry {
    /** TextLabels19.ADD_POINTS: path nodes, then a node in the middle of every segment (one step). */
    ADD_POINTS,

    /** TextLabels19.EDIT_AS_PATH: the guide as path nodes ([TextPathNodes.asPath]). */
    EDIT_AS_PATH,

    /** TextLabels19.EDIT_AS_POLYLINE: the guide as a polyline ([TextPathNodes.asPolyline]). */
    EDIT_AS_POLYLINE,
}

/**
 * v1.9 (R12, D31): the node editing of the open text's path, inside the Text tool. An entry of
 * the path sheet turns the guide into nodes ([TextPathNodes]) and starts it with the toast
 * TextLabels19.NODES_STARTED; the node bar's buttons and node drags edit the nodes while the text
 * re-flows live. Each finished edit is one in-tool step of a [ToolHistory] of the path, which
 * Undo and Redo go through first while this is the controller's `inToolHistory`
 * ([InToolHistoryOwner]); at its bottom they do what v1.8 did. The steps never reach the
 * document: the text, nodes and all, is committed as one document step by the Text tool's next ✓
 * after TextLabels19.DONE_POINTS ended the editing ([end]).
 *
 * The Text tool owns the open text; [host] reads and sets its path (without a step of its own).
 * Main thread only. Its Compose state ([active], [selected], the history's) drives the node bar.
 */
class TextPathNodeEditor internal constructor(private val host: Host) : InToolHistoryOwner {

    /** What the editor needs from the Text tool. */
    internal interface Host {
        /** The open text's path; null while no text is open. */
        val path: TextPathSpec?

        /** Sets the open text's path (the text re-flows; no step). */
        fun setPath(spec: TextPathSpec)

        fun toast(message: String)
    }

    /** True while nodes are edited (between [start] and [end]). */
    var active: Boolean by mutableStateOf(false)
        private set

    /** The selected node (the node bar's Delete point and Sharp corner act on it), -1 with none. */
    var selected: Int by mutableIntStateOf(-1)
        private set

    private var history: ToolHistory<TextPathSpec>? = null

    /** The labels of the steps [history] can take back (the newest last), and of those it can bring back. */
    private val undoLabels = ArrayList<String>()
    private val redoLabels = ArrayList<String>()

    /** The node being dragged, -1 with none; the path when the drag began; finger to node. */
    private var dragIndex = -1
    private var dragFrom: TextPathSpec? = null
    private var dragGrab = Vec2.ZERO

    /** The open path's nodes while they are its guide ([TextPathNodes.of]). */
    val nodes: VSpline? get() = host.path?.let { TextPathNodes.of(it) }

    /** How many nodes the guide has (the node bar's [TextLabels19.POINT_COUNT]). */
    val count: Int get() = nodes?.points?.size ?: 0

    /** Delete point applies: a node is selected and more than 2 are left. */
    val canDelete: Boolean get() = nodes?.let { selected in it.points.indices && it.points.size > 2 } == true

    /** Sharp corner applies to the selected node ([TextPathNodes.canBeSharp]). */
    val canSharp: Boolean get() = nodes?.let { TextPathNodes.canBeSharp(it, selected) } == true

    /** The selected node is a corner. */
    val selectedSharp: Boolean get() = nodes?.points?.getOrNull(selected)?.sharp == true

    /** A finger holds a node ([down] hit one, not lifted yet). */
    val dragging: Boolean get() = dragIndex >= 0

    // ------------------------------------------------------------------ start and end

    /**
     * Starts node editing on the open text's path through [entry]: the guide becomes nodes (the
     * start of the in-tool history, no step), then ADD_POINTS adds its points as the first step.
     * False (nothing changes) for straight text or a guide that can't become nodes.
     */
    fun start(entry: TextPathNodeEntry): Boolean {
        val spec = host.path ?: return false
        if (!spec.isActive) return false
        val n = when (entry) {
            TextPathNodeEntry.ADD_POINTS, TextPathNodeEntry.EDIT_AS_PATH -> TextPathNodes.asPath(spec)
            TextPathNodeEntry.EDIT_AS_POLYLINE -> TextPathNodes.asPolyline(spec)
        } ?: return false
        val begin = TextPathGeometry.sanitized(TextPathNodes.withNodes(spec, n))
        if (TextPathNodes.of(begin) == null) return false
        host.setPath(begin)
        history = ToolHistory(begin, HISTORY_CAP)
        undoLabels.clear()
        redoLabels.clear()
        endDrag()
        selected = -1
        active = true
        host.toast(TextLabels19.NODES_STARTED)
        if (entry == TextPathNodeEntry.ADD_POINTS) addPoints()
        return true
    }

    /** Ends node editing: the nodes stay as they are, the in-tool steps end with it. */
    fun end() {
        active = false
        history = null
        undoLabels.clear()
        redoLabels.clear()
        endDrag()
        selected = -1
    }

    // ------------------------------------------------------------------ the node bar

    /** TextLabels19.ADD_POINTS: a node in the middle of every segment, one step; TextLabels19.NODES_MAX when full. */
    fun addPoints() {
        val n = nodes ?: return
        val more = TextPathNodes.withAddedPoints(n)
        if (more.points.size == n.points.size) {
            host.toast(TextLabels19.NODES_MAX)
            return
        }
        applyStep(more, TextLabels19.ADD_POINTS)
    }

    /** "Add point": a node after the selected one (at the end with none), selected; TextLabels19.NODES_MAX at the cap. */
    fun addPoint() {
        val n = nodes ?: return
        val (more, at) = TextPathNodes.withPointAfter(n, selected) ?: run {
            host.toast(TextLabels19.NODES_MAX)
            return
        }
        if (applyStep(more, ADD_POINT_STEP)) selected = at
    }

    /** "Delete point": the selected node goes (at least 2 stay); nothing is selected then. */
    fun deletePoint() {
        val n = nodes ?: return
        val fewer = TextPathNodes.withoutPoint(n, selected) ?: return
        if (applyStep(fewer, DELETE_POINT_STEP)) selected = -1
    }

    /** "Sharp corner": the selected node becomes a corner, or smooth again. */
    fun toggleSharp() {
        val n = nodes ?: return
        val toggled = TextPathNodes.withSharpToggled(n, selected) ?: return
        applyStep(toggled, SHARP_CORNER_STEP)
    }

    /** Sets [n] as the guide and records it as one step named [label]; false when nothing changed. */
    private fun applyStep(n: VSpline, label: String): Boolean {
        val spec = host.path ?: return false
        val next = TextPathGeometry.sanitized(TextPathNodes.withNodes(spec, n))
        if (next == spec) return false
        host.setPath(next)
        record(next, label)
        return true
    }

    private fun record(spec: TextPathSpec, label: String) {
        val h = history ?: return
        if (spec == h.current) return
        h.record(spec)
        undoLabels += label
        // The history keeps at most its cap: the oldest labels go with their steps.
        while (undoLabels.size > h.depth) undoLabels.removeAt(0)
        redoLabels.clear()
    }

    // ------------------------------------------------------------------ dragging a node

    /**
     * A finger down at [at] (document px): the node nearest to it within [tolerance] document px
     * is selected and follows the finger; elsewhere nothing is selected. True when a node was hit.
     */
    fun down(at: Vec2, tolerance: Float): Boolean {
        endDrag()
        val n = nodes ?: return false
        val i = TextPathNodes.nearest(n, at, tolerance)
        selected = i
        if (i < 0) return false
        dragIndex = i
        dragFrom = host.path
        val p = n.points[i]
        dragGrab = Vec2(p.x - at.x, p.y - at.y)
        return true
    }

    /** The finger at [at]: the dragged node follows and the text re-flows (no step yet). */
    fun move(at: Vec2) {
        val from = dragFrom ?: return
        if (dragIndex < 0) return
        host.setPath(TextPathGeometry.moveHandle(from, dragIndex, at + dragGrab))
    }

    /** The finger lifted: a node that moved is one step. */
    fun up() {
        val from = dragFrom
        val now = host.path
        val dragged = dragIndex >= 0
        endDrag()
        if (dragged && from != null && now != null && now != from) record(now, MOVE_POINT_STEP)
    }

    /** The gesture was taken over (a second finger): the dragged node goes back. */
    fun cancel() {
        val from = dragFrom
        if (dragIndex >= 0 && from != null) host.setPath(from)
        endDrag()
    }

    private fun endDrag() {
        dragIndex = -1
        dragFrom = null
        dragGrab = Vec2.ZERO
    }

    // ------------------------------------------------------------------ InToolHistoryOwner

    override val canUndo: Boolean get() = active && history?.canUndo == true
    override val canRedo: Boolean get() = active && history?.canRedo == true
    override val undoLabel: String? get() = if (canUndo) undoLabels.lastOrNull() else null
    override val redoLabel: String? get() = if (canRedo) redoLabels.lastOrNull() else null

    override fun undoStep(): Boolean {
        if (!active) return false
        endDrag()
        val h = history ?: return false
        val back = h.undo() ?: return false
        undoLabels.removeLastOrNull()?.let { redoLabels += it }
        host.setPath(back)
        keepSelection()
        return true
    }

    override fun redoStep(): Boolean {
        if (!active) return false
        endDrag()
        val h = history ?: return false
        val again = h.redo() ?: return false
        redoLabels.removeLastOrNull()?.let { undoLabels += it }
        host.setPath(again)
        keepSelection()
        return true
    }

    /** A selection past the nodes an undo or redo left goes. */
    private fun keepSelection() {
        if (selected >= count) selected = -1
    }

    companion object {
        /** In-tool steps kept (the oldest go first). */
        const val HISTORY_CAP = 100

        /** The steps' names, shown as "Undo: …" / "Redo: …" (the node bar's buttons, a drag). */
        const val ADD_POINT_STEP = "Add point"
        const val DELETE_POINT_STEP = "Delete point"
        const val SHARP_CORNER_STEP = "Sharp corner"
        const val MOVE_POINT_STEP = "Move point"
    }
}
