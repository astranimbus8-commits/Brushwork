package com.brushwork.paint.tools.vector

import android.graphics.RectF
import com.brushwork.paint.core.Affine2
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.ObjectDeletion
import com.brushwork.paint.tools.ObjectPosition
import com.brushwork.paint.tools.ObjectScale
import com.brushwork.paint.tools.points.PointGroupMath
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.ui.common.PillLabels
import com.brushwork.paint.ui.editor.HistoryLabels
import kotlin.math.abs

/*
 * v1.7 (design §3.1, §3.9, §3.13 and §4.6; area C): the Shape tool's sources for the X / Y pill,
 * its Scale row and its trash cell. The tool owns one of each for its lifetime
 * ([ShapeTool.pillPosition], [ShapeTool.objectScale], [ShapeTool.objectDeletion]); they act on
 * the selected points in Points mode ([ShapeTool.pillPoints]) and on the whole shape otherwise.
 * Each pill edit (a slider drag, an arrow run, a typed value: `begin…end`) is ONE in-tool step
 * of a shape with its own points; a regular shape's placement keeps no in-tool steps (v1.6).
 */

/** The bounds of [anchors]' selected points (document px), or null. */
private fun boundsOf(anchors: List<ShapeAnchor>, sel: PointSelection): RectF? = PointGroupMath.bounds(anchors.map { it.pos }, sel)

/**
 * The pill's X / Y (§3.1): the single selected point ("Point 3", moved as v1.6's numbers move
 * it), the selected points' box centre ("Selected points": the group moves), or the shape's
 * centre ("Center", the v1.6 placement); null while no shape is pending.
 */
internal class ShapePillPosition(private val tool: ShapeTool) : ObjectPosition {
    /** A pill edit is in progress ([beginPositionEdit]); without one, each call is an edit of its own. */
    private var held = false
    /** The group edit this pill edit opened ("Selected points"), and its centre and offset so far. */
    private var groupOpen = false
    private var groupCentre = Vec2.ZERO
    private var groupOffset = Vec2.ZERO

    override val position: Vec2?
        get() {
            val b = tool.box ?: return null
            val (anchors, sel) = tool.pillPoints() ?: return b.center
            if (sel.isSingle) return anchors[sel.primary].pos
            return boundsOf(anchors, sel)?.let { Vec2(it.centerX(), it.centerY()) } ?: b.center
        }

    override val label: String
        get() {
            val sel = tool.pillPoints()?.second ?: return CENTER
            return if (sel.isSingle) "Point ${sel.primary + 1}" else SELECTED_POINTS
        }

    override fun setPosition(x: Float?, y: Float?) {
        val b = tool.box ?: return
        val nx = x?.takeIf { it.isFinite() }
        val ny = y?.takeIf { it.isFinite() }
        val group = tool.pillPoints()
        if (group == null) {
            // v1.6: the shape's centre.
            val cx = nx ?: b.cx
            val cy = ny ?: b.cy
            if (cx != b.cx || cy != b.cy) tool.place(b.copy(cx = cx, cy = cy))
            return
        }
        val (anchors, sel) = group
        if (sel.isSingle) {
            val a = anchors[sel.primary].pos
            tool.movePoint(sel.primary, Vec2(nx ?: a.x, ny ?: a.y))
            return
        }
        // Several points: the points captured when this edit began move by one offset.
        if (!groupOpen || !tool.inGroupEdit) {
            val r = boundsOf(anchors, sel) ?: return
            val c = Vec2(r.centerX(), r.centerY())
            if ((nx ?: c.x) == c.x && (ny ?: c.y) == c.y) return
            groupCentre = c
            groupOffset = Vec2.ZERO
            tool.beginGroupEdit(SELECTED_POINTS)
            groupOpen = true
        }
        groupOffset = Vec2(nx?.let { it - groupCentre.x } ?: groupOffset.x, ny?.let { it - groupCentre.y } ?: groupOffset.y)
        tool.setGroupTransform(Affine2.translate(groupOffset.x, groupOffset.y))
        if (!held) closeGroup()
    }

    override fun beginPositionEdit() {
        held = true
        tool.beginNumericEdit()
    }

    override fun endPositionEdit() {
        held = false
        closeGroup()
        tool.endNumericEdit()
    }

    private fun closeGroup() {
        if (!groupOpen) return
        groupOpen = false
        tool.endGroupEdit()
    }

    companion object {
        const val CENTER = "Center"
        const val SELECTED_POINTS = "Selected points"
    }
}

/**
 * The pill's Scale row (§3.9) in %: the selected points' box in Points mode (about its centre;
 * one point has no size, so it stays 100 %), else the shape's own width and height (about its
 * centre, in its own axes; a line's length). 100 % is the size when the shape opened or the
 * selection changed, taken the first time the row asks for it after that (the pill shows it at
 * once). An axis without size stays 100 % and can't be scaled.
 */
internal class ShapeObjectScale(private val tool: ShapeTool) : ObjectScale {
    private class Reference(val key: Any, val size: Vec2)

    private var reference: Reference? = null
    private var held = false
    /** The group edit this scale edit opened, and the selected points' box when it began. */
    private var groupOpen = false
    private var groupBase: RectF? = null

    /** What the reference belongs to: the opened shape and the selection the row acts on. */
    private fun key(): Any? {
        if (tool.box == null) return null
        return tool.openCount to (tool.pillPoints()?.second ?: WHOLE)
    }

    /** The size the row scales now (document px; the shape's own axes for the whole shape). */
    private fun currentSize(): Vec2? {
        val b = tool.box ?: return null
        val (anchors, sel) = tool.pillPoints() ?: return Vec2(abs(b.w), abs(b.h))
        val r = boundsOf(anchors, sel) ?: return null
        return Vec2(r.width(), r.height())
    }

    /** 100 %: taken when first asked for after the shape opened or the selection changed. */
    private fun referenceSize(): Vec2? {
        val k = key() ?: return null
        reference?.let { if (it.key == k) return it.size }
        val s = currentSize() ?: return null
        reference = Reference(k, s)
        return s
    }

    override val scalePercent: Vec2?
        get() {
            val ref = referenceSize() ?: return null
            val now = currentSize() ?: return null
            return Vec2(percent(now.x, ref.x), percent(now.y, ref.y))
        }

    override fun beginScaleEdit() {
        held = true
        tool.beginNumericEdit()
    }

    override fun setScale(xPercent: Float?, yPercent: Float?) {
        val b = tool.box ?: return
        val ref = referenceSize() ?: return
        val now = currentSize() ?: return
        // The size each axis is asked for (an axis left out, or without size, keeps its own).
        fun target(p: Float?, was: Float, cur: Float): Float =
            if (p == null || !p.isFinite() || was <= EPS) cur else was * p.coerceIn(MIN_PERCENT, MAX_PERCENT) / 100f
        val w = target(xPercent, ref.x, now.x)
        val h = target(yPercent, ref.y, now.y)
        val group = tool.pillPoints()
        if (group == null) {
            // The whole shape about its centre (cx, cy are the box centre); a line keeps h = 0.
            if (w != abs(b.w) || h != abs(b.h)) tool.place(b.copy(w = if (now.x <= EPS) b.w else w, h = if (now.y <= EPS) b.h else h))
            return
        }
        val (anchors, sel) = group
        if (!groupOpen || !tool.inGroupEdit) {
            if (sel.isSingle || (w == now.x && h == now.y)) return
            groupBase = boundsOf(anchors, sel) ?: return
            tool.beginGroupEdit(HistoryLabels.SCALE)
            groupOpen = true
        }
        val base = groupBase ?: return
        val sx = if (base.width() <= EPS) 1f else w / base.width()
        val sy = if (base.height() <= EPS) 1f else h / base.height()
        tool.setGroupTransform(Affine2.scaleAbout(Vec2(base.centerX(), base.centerY()), sx, sy))
        if (!held) closeGroup()
    }

    override fun endScaleEdit() {
        held = false
        closeGroup()
        tool.endNumericEdit()
    }

    private fun closeGroup() {
        if (!groupOpen) return
        groupOpen = false
        groupBase = null
        tool.endGroupEdit()
    }

    private fun percent(now: Float, was: Float): Float = if (was <= EPS) 100f else now / was * 100f

    private companion object {
        /** The reference key of the whole shape (no point selected, or not in Points mode). */
        val WHOLE = Any()
        const val EPS = 1e-4f
        const val MIN_PERCENT = 1f
        const val MAX_PERCENT = 10_000f
    }
}

/**
 * The pill's trash cell (§3.13): "Delete selected points" while some but not all points are
 * selected in Points mode (one in-tool step; the existing "A shape needs at least N points"
 * below the minimum), otherwise, also with every point selected, "Delete shape"
 * ([ShapeTool.deleteShape]). Null label: no shape pending.
 */
internal class ShapeObjectDeletion(private val tool: ShapeTool) : ObjectDeletion {
    private fun deletesPoints(): Boolean {
        val (anchors, sel) = tool.pillPoints() ?: return false
        return sel.count < anchors.size
    }

    override val deleteLabel: String?
        get() {
            if (tool.box == null) return null
            return if (deletesPoints()) PillLabels.DELETE_POINTS else PillLabels.deleteObject(KIND)
        }

    override fun delete() {
        if (tool.box == null) return
        if (deletesPoints()) tool.deleteSelectedPoints() else tool.deleteShape()
    }

    private companion object {
        const val KIND = "shape"
    }
}
