package com.brushwork.paint.ui.tools

import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.ObjectPosition
import com.brushwork.paint.tools.PositionedTool
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeTool

/*
 * What the X / Y strip (§4.6) shows and moves for each tool: adapters over the public APIs of the
 * tools that existed before v1.5 (so they need no edits), and the tools' own [PositionedTool]
 * position for the new ones (Masks, Clone stamp). Each adapter follows its tool's undo model.
 * Positions are document px and read Compose state, so the strip follows every change.
 */

/** A position the strip edits, with the unit its tool shows lengths in. */
class CoordinateSource(
    val target: ObjectPosition,
    /** The unit the tool's own fields use (the strip types and shows values in it). */
    val unit: () -> LengthUnit,
)

/** The strip's source for [tool], or null when the tool places nothing the strip can move. */
fun coordinateSourceOf(tool: Tool): CoordinateSource? = when (tool) {
    is TransformTool -> CoordinateSource(TransformPosition(tool)) { tool.unit }
    is ShapeTool -> CoordinateSource(ShapePosition(tool)) { tool.settings.unit }
    is TextTool -> CoordinateSource(TextPosition(tool)) { tool.positionUnit }
    // v1.6: in PATH mode the selected control point (area B), else the selected anchor.
    is CurveTool -> CoordinateSource(tool.splinePointPosition ?: CurvePointPosition(tool)) { tool.settings.unit }
    is PositionedTool -> CoordinateSource(OwnPosition(tool)) { LengthUnit.PX }
    else -> null
}

/** A v1.5 tool's own position (Masks: the selected component's pin; Clone stamp: the source), looked up each time. */
internal class OwnPosition(private val tool: PositionedTool) : ObjectPosition {
    override val position: Vec2? get() = tool.objectPosition?.position
    override val label: String get() = tool.objectPosition?.label.orEmpty()
    override fun setPosition(x: Float?, y: Float?) { tool.objectPosition?.setPosition(x, y) }
    override fun beginPositionEdit() { tool.objectPosition?.beginPositionEdit() }
    override fun endPositionEdit() { tool.objectPosition?.endPositionEdit() }
}

/**
 * The Transform tool (pixels, placed pictures and lifted vector objects): the reference point
 * of *Numbers* ("Center" by default). Moves stay part of the pending transform (✓ is one step);
 * the end of a drag ends the numeric edit, so the next one starts from where it is.
 */
internal class TransformPosition(private val tool: TransformTool) : ObjectPosition {
    override val position: Vec2? get() = tool.anchorPosition
    override val label: String get() = tool.anchor.label
    override fun setPosition(x: Float?, y: Float?) = tool.setAnchorPosition(x?.toDouble(), y?.toDouble())
    override fun endPositionEdit() = tool.endNumericEdit()
}

/**
 * The Shape tool's pending shape: its box centre (moves are part of the pending shape; a shape
 * with its own points keeps in-tool steps: one per drag, arrow press or typed value).
 */
internal class ShapePosition(private val tool: ShapeTool) : ObjectPosition {
    override val position: Vec2? get() = tool.box?.center
    override val label: String get() = "Center"
    override fun setPosition(x: Float?, y: Float?) {
        val b = tool.box ?: return
        val nx = x?.takeIf { it.isFinite() } ?: b.cx
        val ny = y?.takeIf { it.isFinite() } ?: b.cy
        if (nx == b.cx && ny == b.cy) return
        tool.place(b.copy(cx = nx, cy = ny))
    }
    override fun beginPositionEdit() = tool.beginNumericEdit()
    override fun endPositionEdit() = tool.endNumericEdit()
}

/** The Text tool's pending text: its centre (moves are part of the pending text). */
internal class TextPosition(private val tool: TextTool) : ObjectPosition {
    override val position: Vec2? get() = tool.item?.let { tool.anchorOf(it) }
    override val label: String get() = "Center"
    override fun setPosition(x: Float?, y: Float?) {
        if (tool.item == null) return
        x?.takeIf { it.isFinite() }?.let { tool.setCenterX(it) }
        y?.takeIf { it.isFinite() }?.let { tool.setCenterY(it) }
    }
}

/**
 * The Curve / Polyline tool's selected point (hidden while no point is selected). A drag of the
 * strip is one in-tool undo step of that point.
 */
internal class CurvePointPosition(private val tool: CurveTool) : ObjectPosition {
    override val position: Vec2? get() = tool.anchors.getOrNull(tool.selected)?.pos
    override val label: String get() = "Point ${tool.selected + 1}"
    override fun setPosition(x: Float?, y: Float?) {
        val i = tool.selected
        val a = tool.anchors.getOrNull(i) ?: return
        tool.moveAnchor(i, Vec2(x?.takeIf { it.isFinite() } ?: a.x, y?.takeIf { it.isFinite() } ?: a.y))
    }
    override fun beginPositionEdit() = tool.beginNumericEdit()
    override fun endPositionEdit() = tool.endNumericEdit()
}
