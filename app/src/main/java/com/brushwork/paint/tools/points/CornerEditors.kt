package com.brushwork.paint.tools.points

import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeTool

/**
 * v1.10 (A5, R12, J28): the point editors behind the X / Y pill's Sharp / Smooth cell
 * (`PillSharpCell`): [CurveCornerEditor] for the Curve tools (Curve, Path, Polyline) and
 * [ShapeCornerEditor] for the Shape tool in Points mode. Each tool keeps one editor for its whole
 * life (the editor screen remembers the cell by it).
 *
 * The rules (design R12 (a)):
 * - [PointCornerEditor.corner] is null (no cell) without a selected point; it is derived state
 *   over the selection and the points' flags, so a point drag never recomposes the pill.
 * - A tap applies one value to every selected point that can take it: the cell asks for sharp
 *   while the selection is smooth or mixed ("Mixed means Sharp", UQ9) and for smooth while every
 *   point is sharp. It is ONE in-tool step, with the tool's existing step.
 * - During a group edit (a drag of the group or of its gizmo, C10) a tap does nothing.
 * - Path: sharp is the control point's corner flag (`VSplinePoint.sharp`); the ends of an open
 *   path are always sharp (only ends selected: [CornerState.ENDS_ONLY], greyed).
 * - Polyline: every point is a corner ([CornerState.ALWAYS_SHARP], greyed, UQ18).
 */
object CornerEditors {
    /**
     * v1.10 (R12): the strips' More-row Sharp / Smooth items, which the pill's cell replaces
     * (lead's choice). The items stay in code behind this flag; only the lead may turn it on.
     */
    const val SHOW_IN_MORE = false

    /**
     * The cell's state from the selected points that can change, [sharp] each ([anySelected]: some
     * point is selected at all; none that can change is "ends only").
     */
    internal fun stateOf(sharp: BooleanArray, anySelected: Boolean): CornerState? = when {
        !anySelected -> null
        sharp.isEmpty() -> CornerState.ENDS_ONLY
        sharp.all { it } -> CornerState.SHARP
        sharp.none { it } -> CornerState.SMOOTH
        else -> CornerState.MIXED
    }
}

/**
 * v1.10 (A5, R12): [tool]'s points for the pill's Sharp / Smooth cell (the Curve, Path and
 * Polyline tools). See [CornerEditors].
 */
class CurveCornerEditor(private val tool: CurveTool) : PointCornerEditor {
    private val state: State<CornerState?> = derivedStateOf { compute() }

    private fun compute(): CornerState? {
        val n = tool.pointCount
        val sel = tool.pointSelection.indices.filter { it in 0 until n }
        if (sel.isEmpty()) return null
        if (tool.polyline) return CornerState.ALWAYS_SHARP
        // The points that can be corners (the ends of an open path cannot: always reached).
        val can = sel.filter { tool.canBeSharp(it) }
        val s = tool.spline
        val a = tool.anchors
        val sharp = BooleanArray(can.size) { k ->
            if (tool.isPath) s?.points?.getOrNull(can[k])?.sharp == true else a.getOrNull(can[k])?.sharp == true
        }
        return CornerEditors.stateOf(sharp, anySelected = true)
    }

    override val corner: CornerState? get() = state.value

    override val cornerLocked: Boolean get() = tool.groupEditing

    override fun setSelectedSharp(sharp: Boolean): Boolean {
        if (cornerLocked) return false
        when (corner) {
            null, CornerState.ENDS_ONLY, CornerState.ALWAYS_SHARP -> return false
            else -> {}
        }
        val n = tool.pointCount
        val idx = tool.pointSelection.indices.filter { it in 0 until n && tool.canBeSharp(it) }
        if (idx.isEmpty()) return false
        val anchors = tool.anchors
        val spline = tool.spline
        // One in-tool step; nothing when no point changes (the tool's own rule).
        tool.setSharpPoints(idx, sharp)
        return tool.anchors !== anchors || tool.spline !== spline
    }
}

/**
 * v1.10 (A5, R12): [tool]'s points (Points mode) for the pill's Sharp / Smooth cell; "sharp" is a
 * point that is not smooth. [groupEditing] tells whether a group edit is in progress (the tool
 * keeps it private). See [CornerEditors].
 */
class ShapeCornerEditor(private val tool: ShapeTool, private val groupEditing: () -> Boolean) : PointCornerEditor {
    private val state: State<CornerState?> = derivedStateOf { compute() }

    private fun compute(): CornerState? {
        if (!tool.pointsMode) return null
        return when (val m = tool.selectionSmooth) {
            null -> null
            is Mixed.Same -> if (m.value) CornerState.SMOOTH else CornerState.SHARP
            is Mixed.Spread -> CornerState.MIXED
        }
    }

    override val corner: CornerState? get() = state.value

    override val cornerLocked: Boolean get() = groupEditing()

    override fun setSelectedSharp(sharp: Boolean): Boolean {
        if (cornerLocked || corner == null) return false
        val anchors = tool.docAnchors() ?: return false
        val smooth = !sharp
        // The tool's own rule: a point changes when its kind differs or it has handles of its own.
        val changes = tool.pointSelection.resized(anchors.size).indices.any { anchors[it].smooth != smooth || anchors[it].hasExplicitHandles }
        if (!changes) return false
        tool.setSelectedSmooth(smooth)
        return true
    }
}
