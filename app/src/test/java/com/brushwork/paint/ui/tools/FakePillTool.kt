package com.brushwork.paint.ui.tools

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.DeletingTool
import com.brushwork.paint.tools.ObjectDeletion
import com.brushwork.paint.tools.ObjectPosition
import com.brushwork.paint.tools.ObjectScale
import com.brushwork.paint.tools.PillPositionTool
import com.brushwork.paint.tools.ScaledTool
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.common.PillLabels

/**
 * v1.7 F5 (design §4.6): a test fixture implementing the three pill interfaces the way a v1.7
 * point editor does, so area I's pill tests (and the foundation's routing test) run without a
 * real implementer: an open "object" of [points] (document px, from [initialPoints]), a settable point selection
 * ([select]), an in-tool step counter for [historyMark] / [rollbackHistory], and counters for
 * [setPosition][ObjectPosition.setPosition], [setScale][ObjectScale.setScale] and
 * [delete][ObjectDeletion.delete].
 *
 * [pillPosition] follows the §4.6 fallback: the single selected point ("Point n", 1-based), the
 * selection's box centre ("Selected points", moving the group), or the object's box centre
 * ("Center"); null only when the object is closed ([open] false). Every edit is one in-tool step
 * ([steps]). The scale reference box is captured when the selection is taken (100 % then).
 *
 * [kind] names the object for the trash cell ("Delete curve"); [id] is the tool it stands in for;
 * [uniformOnly] makes its scale uniform-only, as a text object's (Scale Y hidden, proportions kept).
 */
class FakePillTool(
    controller: EditorController,
    initialPoints: List<Vec2> = listOf(Vec2(10f, 10f), Vec2(50f, 10f), Vec2(50f, 30f), Vec2(10f, 30f)),
    override val id: ToolId = ToolId.CURVE,
    val kind: String = "curve",
    override val pillUnit: LengthUnit = LengthUnit.PX,
    val uniformOnly: Boolean = false,
) : Tool(controller), PillPositionTool, ScaledTool, DeletingTool {

    /** The open object's points (document px). Compose state, as a real tool's. */
    var points: List<Vec2> by mutableStateOf(initialPoints)
        private set

    /** Indices of the selected points. */
    var selected: Set<Int> by mutableStateOf(emptySet())
        private set

    /** False: no object is open (the pill hides: position, scale and deletion are null). */
    var open: Boolean by mutableStateOf(true)

    /** In-tool steps taken (each edit pushes one; [rollbackHistory] drops back to a mark). */
    var steps: Int by mutableIntStateOf(0)
        private set

    var setPositionCalls = 0
        private set
    var setScaleCalls = 0
        private set
    var deleteCalls = 0
        private set
    var beginPositionCalls = 0
        private set
    var endPositionCalls = 0
        private set
    /** v1.7 area I: the Scale row's edits (one typed value or one drag is one begin … end). */
    var beginScaleCalls = 0
        private set
    var endScaleCalls = 0
        private set

    /** The scale reference box (min, max) captured when the selection was taken, and the points then. */
    private var reference: Pair<Vec2, Vec2>? = null
    private var referencePoints: List<Vec2> = emptyList()

    /** Selects [indices] (out-of-range ones are ignored) and captures the scale reference. */
    fun select(vararg indices: Int) {
        selected = indices.filter { it in points.indices }.toSet()
        capture()
    }

    private fun targets(): List<Int> = if (selected.isEmpty()) points.indices.toList() else selected.sorted()

    private fun box(of: List<Vec2>): Pair<Vec2, Vec2>? {
        if (of.isEmpty()) return null
        return Vec2(of.minOf { it.x }, of.minOf { it.y }) to Vec2(of.maxOf { it.x }, of.maxOf { it.y })
    }

    private fun capture() {
        val t = targets()
        referencePoints = points
        reference = box(t.map { points[it] })
    }

    init { capture() }

    override val pillPosition: ObjectPosition = object : ObjectPosition {
        override val position: Vec2?
            get() {
                if (!open || points.isEmpty()) return null
                val sel = selected
                if (sel.size == 1) return points[sel.first()]
                val (lo, hi) = box(targets().map { points[it] }) ?: return null
                return Vec2((lo.x + hi.x) / 2f, (lo.y + hi.y) / 2f)
            }
        override val label: String
            get() = when {
                selected.size == 1 -> "Point ${selected.first() + 1}"
                selected.size > 1 -> "Selected points"
                else -> "Center"
            }

        override fun setPosition(x: Float?, y: Float?) {
            setPositionCalls++
            val at = position ?: return
            val dx = (x ?: at.x) - at.x
            val dy = (y ?: at.y) - at.y
            if (dx == 0f && dy == 0f) return
            val move = targets().toSet()
            points = points.mapIndexed { i, p -> if (i in move) Vec2(p.x + dx, p.y + dy) else p }
            steps++
        }

        override fun beginPositionEdit() { beginPositionCalls++ }
        override fun endPositionEdit() { endPositionCalls++ }
    }

    private val scale = object : ObjectScale {
        override val scalePercent: Vec2?
            get() {
                val (lo, hi) = reference ?: return null
                val (nlo, nhi) = box(targets().map { points[it] }) ?: return null
                fun pct(now: Float, was: Float) = if (was == 0f) 100f else now / was * 100f
                return Vec2(pct(nhi.x - nlo.x, hi.x - lo.x), pct(nhi.y - nlo.y, hi.y - lo.y))
            }

        override val uniformOnly: Boolean get() = this@FakePillTool.uniformOnly

        override fun beginScaleEdit() { beginScaleCalls++ }
        override fun endScaleEdit() { endScaleCalls++ }

        override fun setScale(xPercent: Float?, yPercent: Float?) {
            setScaleCalls++
            val (lo, hi) = reference ?: return
            val cx = (lo.x + hi.x) / 2f
            val cy = (lo.y + hi.y) / 2f
            val now = scalePercent ?: return
            val sx = (xPercent ?: now.x) / 100f
            val sy = (yPercent ?: now.y) / 100f
            val move = targets().toSet()
            points = points.mapIndexed { i, p ->
                if (i !in move) p else referencePoints[i].let { r -> Vec2(cx + (r.x - cx) * sx, cy + (r.y - cy) * sy) }
            }
            steps++
        }
    }

    override val objectScale: ObjectScale? get() = if (open && points.isNotEmpty()) scale else null

    private val deletion = object : ObjectDeletion {
        override val deleteLabel: String?
            get() = when {
                !open -> null
                selected.isNotEmpty() && selected.size < points.size -> PillLabels.DELETE_POINTS
                else -> PillLabels.deleteObject(kind)
            }

        override fun delete() {
            deleteCalls++
            if (!open) return
            if (selected.isNotEmpty() && selected.size < points.size) {
                points = points.filterIndexed { i, _ -> i !in selected }
                selected = emptySet()
            } else {
                points = emptyList()
                selected = emptySet()
                open = false
            }
            capture()
            steps++
        }
    }

    override val objectDeletion: ObjectDeletion? get() = if (open) deletion else null

    /** The in-tool history: snapshots per step, so [rollbackHistory] restores the state at a mark. */
    private val history = ArrayList<Triple<List<Vec2>, Set<Int>, Boolean>>()

    override fun historyMark(): Any? {
        history.add(Triple(points, selected, open))
        return Mark(steps, history.size - 1)
    }

    override fun rollbackHistory(mark: Any?) {
        val m = mark as? Mark ?: return
        val (p, s, o) = history.getOrNull(m.snapshot) ?: return
        points = p
        selected = s
        open = o
        steps = m.steps
        while (history.size > m.snapshot) history.removeAt(history.lastIndex)
        capture()
    }

    /** A [historyMark] token: the step count and the snapshot taken then. */
    data class Mark(val steps: Int, val snapshot: Int)
}
