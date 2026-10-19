package com.brushwork.paint.ui.vector

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.AlignHorizontalCenter
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.ChangeHistory
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Commit
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.FormatShapes
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material.icons.filled.LineWeight
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Pin
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.RoundedCorner
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.ChangeHistory
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Units
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.points.Mixed
import com.brushwork.paint.tools.vector.CurveKind
import com.brushwork.paint.tools.vector.CurvePaint
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.common.CurveGenLabels18
import com.brushwork.paint.ui.common.CurveLabels17
import com.brushwork.paint.ui.common.EraseLabels19
import com.brushwork.paint.ui.common.FitText
import com.brushwork.paint.ui.common.MirrorLabels18
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.common.PointLabels19
import com.brushwork.paint.ui.common.V19Tags
import com.brushwork.paint.ui.common.closeOnSecondFinger
import com.brushwork.paint.ui.common.longPressLabel
import com.brushwork.paint.ui.editor.EditorIcons
import com.brushwork.paint.ui.theme.BrushworkColors
import com.brushwork.paint.ui.theme.IbisColors
import com.brushwork.paint.ui.tools.strip.LocalStripContext
import com.brushwork.paint.ui.tools.strip.StripContext
import com.brushwork.paint.ui.tools.strip.StripItem
import com.brushwork.paint.ui.tools.strip.StripKeysV19
import com.brushwork.paint.ui.tools.strip.StripRow
import com.brushwork.paint.ui.tools.strip.StripSpec
import com.brushwork.paint.vector.CurveGenKind
import com.brushwork.paint.vector.draw.VectorEraseMode
import com.brushwork.paint.vector.draw.VectorEraserModes

/*
 * The Curve, Polyline and Path strips (v1.8, design §3.9 and §3.7, area E): [curveStrip] builds
 * one [StripSpec] by `CurveTool.kind` and by what is open. Row 1 holds the most-used controls
 * (the [CurveGenLabels18.CURVES] dropdown or the point actions, the stroke kind); the More row the rest, mostly
 * 40 dp icons known by their v1.7 descriptions (each with its long-press label). The controls
 * themselves (the thickness and weight controls, the Handles group, the sheets and dialogs) live
 * in CurveToolOptions.kt and CurveGeneratorSheet.kt.
 */

/** The sheets the curve strips open (in [StripSpec.overlays]; at most one at a time). */
internal enum class CurveSheet { NONE, SETTINGS, NUMBERS, GENERATOR }

/** The typing dialogs the strips' controls open (in [StripSpec.overlays]). */
internal sealed interface CurveTyping {
    data class Thickness(val index: Int) : CurveTyping
    data class Weight(val index: Int) : CurveTyping
    data object Handles : CurveTyping
    data object GroupThickness : CurveTyping
    data object GroupWeight : CurveTyping
}

/** Where the strips' sheet and typing dialog are open: outside the items, so they outlive the More row (§3.9 "Overlays"). */
@Stable
internal class CurveStripUi(sheetState: MutableState<CurveSheet> = mutableStateOf(CurveSheet.NONE)) {
    var sheet by sheetState
    var typing by mutableStateOf<CurveTyping?>(null)
    /** v1.9 (R11 a): the layer the mode cell's [EraseLabels19.TURN_INTO_PIXELS] asks about ([ErasePixelsDialog]), else null. */
    var pixels by mutableStateOf<Layer?>(null)
}

/** The selected points' corner state, as the sharp item offers it. */
internal enum class SharpState {
    /** No item: nothing selected, or a Polyline (all corners). */
    NONE,
    /** "Ends are always sharp": only open-path ends selected (disabled). */
    ENDS,
    /** All smooth: the item offers "Sharp corner". */
    SMOOTH,
    /** All corners: the item offers "Smooth". */
    SHARP,
    /** They differ: "Sharp corner: Mixed" makes them all corners. */
    MIXED,
}

/**
 * What decides which items the curve strips show (derived from the tool, so dragging a point or
 * changing a value recomposes only the item that shows it, never the spec).
 */
internal data class CurveStripFacts(
    val kind: CurveKind,
    /**
     * Points of the open path (0: nothing open). [curveFacts] holds it at 2: the strip only asks
     * "open" and "two or more", so a generator slider that changes the count rebuilds no spec.
     */
    val points: Int = 0,
    /** Selected points. */
    val selected: Int = 0,
    /** The one selected point, or -1 (none or several). */
    val single: Int = -1,
    /** Every point is selected ("Deselect all points"). */
    val allSelected: Boolean = false,
    val stroke: CurveStroke = CurveStroke.BRUSH,
    /** The open path's generator kind (a generated curve), or null. */
    val generated: CurveGenKind? = null,
    /** The open path has mirror copies ([MirrorLabels18.REMOVE]). */
    val copies: Boolean = false,
    /** Some point's thickness is not 100 % ("All points 100 %"). */
    val anyThickness: Boolean = false,
    val sharp: SharpState = SharpState.NONE,
    /** Curve, one point selected: its tangent was dragged ("Auto tangent"). */
    val customTangent: Boolean = false,
    /** Curve with 2 points or more: the Handles group. */
    val handles: Boolean = false,
    /** A path object of a vector layer is reopened (no "Use brush size"). */
    val reopened: Boolean = false,
    /** Path: the order its item shows ("Order 4"). */
    val order: Int = 4,
    /** Path: one smooth middle point selected ([PATH_WIDTH_CAPTION] applies). */
    val caption: Boolean = false,
    /**
     * v1.9 (R11 a): the Eraser's mode while the tool erases a vector or legacy shape layer's
     * content: row 1 shows the mode cell ([eraseCellMode]); null otherwise.
     */
    val eraseCell: VectorEraseMode? = null,
)

/**
 * v1.9 (R11 a): the mode [tool]'s row-1 mode cell shows ([VectorEraseChooser.modeCell]): the
 * Eraser's, while the tool erases ([CurveTool.eraseOn]) the objects of a vector or legacy shape
 * layer (its content, not its mask); else null (Compose state).
 */
internal fun eraseCellMode(tool: CurveTool): VectorEraseMode? {
    if (!tool.eraseOn) return null
    val c = tool.controller
    val layer = tool.eraseTarget
    if (!VectorEraseChooser.holdsObjects(layer) || c.editTargetOf(layer) != EditTarget.CONTENT) return null
    return VectorEraserModes.mode(c)
}

/** The strip facts of [tool] now (read in a `derivedStateOf`). */
internal fun curveFacts(tool: CurveTool): CurveStripFacts {
    val n = tool.pointCount
    val sel = tool.pointSelection
    val count = sel.count
    val single = if (sel.isSingle) sel.primary else -1
    fun pointSharp(i: Int): Boolean = if (tool.isPath) tool.spline?.points?.getOrNull(i)?.sharp == true else tool.anchors.getOrNull(i)?.sharp == true
    val sharp = when {
        tool.polyline || count == 0 || n == 0 -> SharpState.NONE
        single >= 0 -> when {
            !tool.canBeSharp(single) -> SharpState.ENDS
            pointSharp(single) -> SharpState.SHARP
            else -> SharpState.SMOOTH
        }
        else -> when (val m = Mixed.of(tool.selectedSharp())) {
            null -> SharpState.ENDS
            is Mixed.Same -> if (m.value) SharpState.SHARP else SharpState.SMOOTH
            is Mixed.Spread -> SharpState.MIXED
        }
    }
    return CurveStripFacts(
        kind = tool.kind,
        points = n.coerceAtMost(2),
        selected = count,
        single = single,
        allSelected = n > 0 && count == n,
        stroke = tool.settings.stroke,
        generated = tool.generator?.kind,
        copies = n > 0 && tool.hasCopies,
        anyThickness = n > 0 && !tool.uniformWidth,
        sharp = sharp,
        customTangent = tool.kind == CurveKind.CURVE && single >= 0 && tool.anchors.getOrNull(single)?.hasCustomTangent == true,
        handles = tool.canScaleHandles,
        reopened = tool.isReopened,
        order = tool.pathOrder,
        caption = pathCaptionApplies(tool),
        eraseCell = eraseCellMode(tool),
    )
}

/**
 * The Curve, Polyline and Path strips, by `CurveTool.kind` (v1.8, design §3.9; area E). The
 * facts are derived (a point drag recomposes no spec); the sheet and the typing dialog are open
 * in [CurveStripUi], kept by the tool's key (`ToolStrips` calls this one builder for the three
 * tools). [PATH_WIDTH_CAPTION], v1.7's hint line, is the editor's message once per session.
 */
@Composable
internal fun curveStrip(tool: CurveTool, c: EditorController): StripSpec {
    val facts by remember(tool) { derivedStateOf { curveFacts(tool) } }
    val sheet = rememberSaveable(tool) { mutableStateOf(CurveSheet.NONE) }
    val ui = remember(tool, sheet) { CurveStripUi(sheet) }
    val f = facts
    LaunchedEffect(tool, f.caption) {
        if (f.caption && !tool.widthCaptionShown) {
            tool.widthCaptionShown = true
            c.toast(PATH_WIDTH_CAPTION)
        }
    }
    return remember(tool, f, ui) { curveSpec(tool, f, ui) }
}

// ====================================================================== widths (measured, rounded up to 4 dp)

/** An icon item: the touch minimum (§3.9). */
internal const val CURVE_ICON_DP = 40
internal const val CURVE_CURVES_DP = 92
internal const val CURVE_SHAPES_DP = 80
internal const val CURVE_STROKE_KIND_DP = 56
internal const val CURVE_LINE_WIDTH_DP = 64
internal const val CURVE_ORDER_DP = 64
internal const val CURVE_GENERATOR_DP = 92
internal const val CURVE_THICKNESS_DP = 260
internal const val CURVE_WEIGHT_DP = 64

/** The keys of the curve strips' items. */
internal object CurveKeys {
    const val CURVES = "curve.curves"; const val SHAPES = "curve.shapes"; const val STROKE_KIND = "curve.strokeKind"
    const val LINE_WIDTH = "curve.lineWidth"; const val ADD = "curve.addPoint"; const val DELETE = "curve.deletePoint"
    const val CLOSED = "curve.closed"; const val ORDER = "curve.order"; const val GENERATOR = "curve.generator"
    const val ENDPOINT = "curve.endpoint"; const val SHARP = "curve.sharp"; const val AUTO_TANGENT = "curve.autoTangent"
    const val ALL_100 = "curve.allPoints100"; const val HANDLES = "curve.handles"; const val SELECT_ALL = "curve.selectAll"
    const val THICKNESS = "curve.thickness"; const val WEIGHT = "curve.weight"; const val DESELECT = "curve.deselect"
    const val PREVIOUS = "curve.previous"; const val NEXT = "curve.next"; const val BRUSH_SIZE = "curve.brushSize"
    const val TAPER = "curve.taper"; const val TO_BEZIER = "curve.toBezier"; const val SETTINGS = "curve.settings"
    const val NUMBERS = "curve.numbers"; const val SNAP = "curve.snap"; const val REMOVE_COPIES = "curve.removeCopies"
}

/** v1.7 labels the strips keep (descriptions of icon items now). */
internal const val ADD_POINT_LABEL = "Add point"
internal const val DELETE_POINT_LABEL = "Delete point"
internal const val DESELECT_POINT_LABEL = "Deselect point"
internal const val SNAP_LABEL = "Snap to objects"

/** The generator kinds in the [CurveGenLabels18.CURVES] dropdown's order (§3.7). */
internal val CURVES_MENU = listOf(
    CurveGenKind.ARCHIMEDEAN, CurveGenKind.GOLDEN, CurveGenKind.LOGARITHMIC, CurveGenKind.FERMAT, CurveGenKind.SINE, CurveGenKind.ROSE,
)

/**
 * The strip of [tool] for [f] (pure: no composition; `CurveStripTest` builds it for every state).
 * Row 1, then the More row, in the order of §3.9's table; the paint modes (v1.7's segments) live
 * in the "Stroke kind" dropdown, the order arrows in the "Order N" popup ([StripSpec.movedInto]).
 */
internal fun curveSpec(tool: CurveTool, f: CurveStripFacts, ui: CurveStripUi): StripSpec {
    val items = ArrayList<StripItem>()
    val open = f.points > 0
    val kind = f.kind
    // v1.9 (R11 a): erasing a vector or shape layer, the mode cell follows the stroke kind in row 1
    // (it never leaves it), and room is made for it (rules 1, 2 and 5 at 360 dp with the VECTOR
    // cell): the stroke kind may go to the More row first (Curve and Polyline with a path open,
    // Path); a generated curve's item and an open Path's "Order N" are More items; the "Use brush
    // size" / "Taper ends" shortcut leaves the strip (both stay in the settings sheet; a taper
    // does not change what an erase takes).
    val cell = eraseCellItem(tool, f, ui)
    val kindRank = if (cell != null) 1 else 0
    val shortcut = if (cell == null) brushOrTaper(tool, f) else emptyList()
    when (kind) {
        CurveKind.CURVE -> if (!open) {
            items += curvesItem(tool)
            items += strokeKindItem(tool, f, StripRow.ROW1, 0)
            if (cell != null) items += cell
            if (f.stroke == CurveStroke.PLAIN) items += lineWidthItem(tool, ui, StripRow.ROW1, 1)
            items += closedItem(tool, StripRow.MORE)
            items += shortcut
            items += sheetItems(tool, ui)
        } else {
            items += addItem(tool)
            items += deleteItem(tool, f)
            items += closedItem(tool, StripRow.ROW1)
            items += strokeKindItem(tool, f, StripRow.ROW1, kindRank)
            if (cell != null) items += cell
            if (f.generated != null) items += generatorItem(tool, f.generated, ui, if (cell != null) StripRow.MORE else StripRow.ROW1, if (cell != null) 0 else 1)
            if (f.sharp != SharpState.NONE) items += sharpItem(tool, f)
            // The host composes items by position: an item that a control's use brings in comes
            // after that control, so the control (an open Handles popup, a slider being dragged)
            // keeps its place: Auto tangent follows Handles; All points 100 % joins the More line
            // above the thickness slider (a line of its own at both widths).
            if (f.handles) items += handlesItem(tool, ui)
            if (f.customTangent) items += autoTangentItem(tool, f.single)
            if (f.anyThickness) items += all100Item(tool)
            items += selectAllItem(tool, f)
            thicknessItem(tool, f, ui)?.let { items += it }
            if (f.selected > 0) items += deselectItem(tool)
            if (f.points >= 2) {
                items += previousItem(tool)
                items += nextItem(tool)
            }
            items += shortcut
            items += sheetItems(tool, ui)
            if (f.copies) items += removeCopiesItem(tool)
        }
        CurveKind.POLYLINE -> if (!open) {
            items += strokeKindItem(tool, f, StripRow.ROW1, 0)
            if (cell != null) items += cell
            if (f.stroke == CurveStroke.PLAIN) items += lineWidthItem(tool, ui, StripRow.ROW1, 0)
            items += closedItem(tool, StripRow.MORE)
            items += shortcut
            items += sheetItems(tool, ui)
        } else {
            items += addItem(tool)
            items += deleteItem(tool, f)
            items += closedItem(tool, StripRow.ROW1)
            items += strokeKindItem(tool, f, StripRow.ROW1, kindRank)
            if (cell != null) items += cell
            thicknessItem(tool, f, ui)?.let { items += it }
            if (f.anyThickness) items += all100Item(tool)
            items += selectAllItem(tool, f)
            if (f.selected > 0) items += deselectItem(tool)
            items += shortcut
            items += sheetItems(tool, ui)
            if (f.copies) items += removeCopiesItem(tool)
            // v1.9 (R18): after v1.8's More items (StripKeysV19.BEVEL's rank).
            if (f.selected > 0) items += bevelItem(tool)
        }
        CurveKind.PATH -> if (!open) {
            items += curvesItem(tool)
            items += shapesItem(tool)
            items += strokeKindItem(tool, f, StripRow.ROW1, kindRank)
            if (cell != null) items += cell
            items += orderItem(tool, f, StripRow.MORE)
            items += endpointItem(tool)
            items += closedItem(tool, StripRow.MORE)
            if (f.stroke == CurveStroke.PLAIN) items += lineWidthItem(tool, ui, StripRow.MORE, 0)
            items += sheetItems(tool, ui)
        } else {
            items += addItem(tool)
            items += deleteItem(tool, f)
            items += closedItem(tool, StripRow.ROW1)
            // (Erasing: the order is a More item, after the weight.)
            if (cell == null) items += orderItem(tool, f, StripRow.ROW1)
            items += strokeKindItem(tool, f, StripRow.ROW1, 1)
            if (cell != null) items += cell
            items += endpointItem(tool)
            if (f.sharp != SharpState.NONE) items += sharpItem(tool, f)
            if (f.anyThickness) items += all100Item(tool)
            items += selectAllItem(tool, f)
            if (f.generated != null) items += generatorItem(tool, f.generated, ui, StripRow.MORE, 0)
            thicknessItem(tool, f, ui)?.let { items += it }
            if (f.selected > 0) items += weightItem(tool, f, ui)
            if (cell != null) items += orderItem(tool, f, StripRow.MORE)
            if (f.selected > 0) items += deselectItem(tool)
            if (f.points >= 2) items += toBezierItem(tool)
            items += sheetItems(tool, ui)
            if (f.copies) items += removeCopiesItem(tool)
        }
    }
    val moved = buildMap {
        if (items.any { it.key == CurveKeys.STROKE_KIND }) {
            put(CurveLabels17.STROKE_ONLY, CurveKeys.STROKE_KIND)
            put(CurveLabels17.FILL_ONLY, CurveKeys.STROKE_KIND)
            put(CurveLabels17.BOTH, CurveKeys.STROKE_KIND)
        }
        if (items.any { it.key == CurveKeys.ORDER }) {
            put(LOWER_ORDER, CurveKeys.ORDER)
            put(HIGHER_ORDER, CurveKeys.ORDER)
        }
    }
    return StripSpec(items = items, overlays = { CurveOverlays(tool, ui) }, movedInto = moved)
}

/** The Order popup's arrows ([OrderStepper]). */
private const val LOWER_ORDER = "Lower order"
private const val HIGHER_ORDER = "Higher order"

/** The sheets and typing dialogs the strips open: composed while the strip shows, More row open or not. */
@Composable
private fun CurveOverlays(tool: CurveTool, ui: CurveStripUi) {
    when (ui.sheet) {
        CurveSheet.NONE -> Unit
        CurveSheet.SETTINGS -> CurveSettingsSheet(tool) {
            ui.sheet = CurveSheet.NONE
            tool.persistBrushSize()
        }
        CurveSheet.NUMBERS -> CurveNumbersSheet(tool) { ui.sheet = CurveSheet.NONE }
        CurveSheet.GENERATOR -> CurveGeneratorSheet(tool) { ui.sheet = CurveSheet.NONE }
    }
    val close = { ui.typing = null }
    when (val t = ui.typing) {
        null -> Unit
        is CurveTyping.Thickness -> if (t.index in 0 until tool.pointCount) ThicknessDialog(tool, t.index, close) else LaunchedEffect(t) { close() }
        is CurveTyping.Weight -> if (t.index in 0 until tool.pointCount) WeightDialog(tool, t.index, close) else LaunchedEffect(t) { close() }
        CurveTyping.Handles -> HandleScaleDialog(tool, close)
        CurveTyping.GroupThickness -> GroupThicknessDialog(tool, close)
        CurveTyping.GroupWeight -> GroupWeightDialog(tool, close)
    }
    // v1.9 (R18): Polyline's bevel popup, More row open or closed.
    if (tool.polyline) CurveBevelPopupHost(tool)
    // v1.9 (R11 a): the mode cell's [EraseLabels19.TURN_INTO_PIXELS] (the erase then takes the layer's pixels).
    ui.pixels?.let { layer -> ErasePixelsDialog(tool.controller, layer) { ui.pixels = null } }
}

// ====================================================================== items

private fun item(key: String, label: String, width: Int, row: StripRow, rank: Int = 0, content: @Composable () -> Unit) =
    StripItem(key = key, label = label, width = width.dp, row = row, overflowRank = rank, content = content)

/** [CurveGenLabels18.CURVES] (known as [CurveGenLabels18.CURVES_DESC]): the generators, while nothing is open (Curve, Path). */
private fun curvesItem(tool: CurveTool) = item(CurveKeys.CURVES, CurveGenLabels18.CURVES_DESC, CURVE_CURVES_DP, StripRow.ROW1) {
    val view = LocalView.current
    DropCell(CurveGenLabels18.CURVES_DESC, CURVE_CURVES_DP, longPress = false, face = { CellText(CurveGenLabels18.CURVES) }) { close ->
        for (g in CURVES_MENU) {
            DropdownMenuItem(
                text = { Text(g.label) },
                onClick = {
                    close()
                    // The part of the canvas that shows: the canvas view less the chrome over it.
                    tool.startGenerator(g, findCanvasView(view.rootView)?.freeArea())
                },
            )
        }
    }
}

/** Path, while nothing is open: "Shapes" (known as "Path shapes"), the Circle and Capsule quick starts. */
private fun shapesItem(tool: CurveTool) = item(CurveKeys.SHAPES, PATH_SHAPES, CURVE_SHAPES_DP, StripRow.ROW1) {
    val view = LocalView.current
    DropCell(PATH_SHAPES, CURVE_SHAPES_DP, longPress = false, face = { CellText("Shapes") }) { close ->
        for (shape in CurveTool.PathShape.entries) {
            DropdownMenuItem(
                text = { Text(shape.label) },
                onClick = {
                    close()
                    tool.startShape(shape, tool.shapeArea(findCanvasView(view.rootView)?.freeArea()))
                },
            )
        }
    }
}

private const val PATH_SHAPES = "Path shapes"

/**
 * "Stroke kind": the paint mode's icon; its dropdown holds v1.7's three segments ("Stroke",
 * "Fill", "Both", known as [CurveLabels17.STROKE_ONLY], [CurveLabels17.FILL_ONLY] and
 * [CurveLabels17.BOTH]; Fill and Both greyed with 1 or 2 points, a tap says
 * [CurveLabels17.FILL_NEEDS_3]) and the stroke kinds ("Current brush", "Plain line").
 */
private fun strokeKindItem(tool: CurveTool, f: CurveStripFacts, row: StripRow, rank: Int) =
    item(CurveKeys.STROKE_KIND, CurveLabels17.STROKE_KIND, CURVE_STROKE_KIND_DP, row, rank) {
        val mode = tool.paintMode
        val erasing = tool.eraseOn
        val icon = when {
            // v1.9 (R11 a): the eraser's glyph while the tool erases.
            erasing -> EditorIcons.Eraser
            mode == CurvePaint.FILL -> Icons.Filled.FormatColorFill
            mode == CurvePaint.BOTH -> Icons.Filled.FormatShapes
            else -> Icons.Filled.LineWeight
        }
        DropCell(CurveLabels17.STROKE_KIND, CURVE_STROKE_KIND_DP, face = { Icon(icon, contentDescription = null, tint = BrushworkColors.OnChrome, modifier = Modifier.size(22.dp)) }) { close ->
            val fillOk = tool.fillPossible
            fun pick(m: CurvePaint) {
                if (m != CurvePaint.STROKE && !fillOk) tool.controller.toast(CurveLabels17.FILL_NEEDS_3) else tool.setPaintMode(m)
                close()
            }
            PaintModeEntry("Stroke", CurveLabels17.STROKE_ONLY, mode == CurvePaint.STROKE, available = true) { pick(CurvePaint.STROKE) }
            PaintModeEntry("Fill", CurveLabels17.FILL_ONLY, mode == CurvePaint.FILL, available = fillOk) { pick(CurvePaint.FILL) }
            PaintModeEntry("Both", CurveLabels17.BOTH, mode == CurvePaint.BOTH, available = fillOk) { pick(CurvePaint.BOTH) }
            HorizontalDivider(color = BrushworkColors.ChromeBorder)
            val stroke = tool.settings.stroke
            for (k in STROKE_KINDS) {
                DropdownMenuItem(
                    text = { Text(k.label) },
                    onClick = {
                        tool.setStrokeKind(k)
                        close()
                    },
                    enabled = stroke != CurveStroke.NONE,
                    leadingIcon = { CheckMark(stroke == k) },
                )
            }
            // v1.9 (R11 a): the last row, the same switch as the bottom bar's slot 1.
            HorizontalDivider(color = BrushworkColors.ChromeBorder)
            DropdownMenuItem(
                text = { Text(EraseLabels19.INSTEAD_OF_PAINT) },
                onClick = {
                    close()
                    // (Read now, as slot 1 does: the menu may be older than the last switch.)
                    tool.setErase(!tool.eraseOn)
                },
                leadingIcon = { CheckMark(erasing) },
                modifier = Modifier.semantics { selected = erasing },
            )
        }
    }

/**
 * v1.9 (R11 a): the row-1 mode cell while the tool erases a vector or shape layer
 * ([CurveStripFacts.eraseCell]; StripKeysV19.ERASE_MODE_CELL, A5's [VectorEraseChooser.modeCell]):
 * a mode changes the Eraser's (the pending erase follows it); [EraseLabels19.TURN_INTO_PIXELS] asks first.
 */
private fun eraseCellItem(tool: CurveTool, f: CurveStripFacts, ui: CurveStripUi): StripItem? {
    val mode = f.eraseCell ?: return null
    val c = tool.controller
    val layer = tool.eraseTarget
    // (The cell uses no strip context.)
    return VectorEraseChooser.modeCell(StripContext(selectionBarShown = false, vectorMode = true), layer, mode, onMode = { VectorEraserModes.setMode(c, it) }) {
        ui.pixels = layer
    }
}

/** One paint mode of the Stroke kind dropdown: [text] shown, known by [description] alone (I10). */
@Composable
private fun PaintModeEntry(text: String, description: String, selected: Boolean, available: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                text,
                color = if (available) BrushworkColors.OnChrome else BrushworkColors.OnChromeDim.copy(alpha = 0.5f),
            )
        },
        onClick = onClick,
        leadingIcon = { CheckMark(selected) },
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = description
            this.selected = selected
            if (!available) {
                stateDescription = CurveLabels17.FILL_NEEDS_3
                disabled()
            }
        },
    )
}

@Composable
private fun CheckMark(on: Boolean) {
    Box(Modifier.size(20.dp)) {
        if (on) Icon(Icons.Filled.Check, contentDescription = null, tint = BrushworkColors.Accent, modifier = Modifier.size(20.dp))
    }
}

/** The plain line's width (its value; while it follows the brush size, the brush's): a tap opens the settings sheet. */
private fun lineWidthItem(tool: CurveTool, ui: CurveStripUi, row: StripRow, rank: Int) =
    item(CurveKeys.LINE_WIDTH, LINE_WIDTH_LABEL, CURVE_LINE_WIDTH_DP, row, rank) {
        val ctx = LocalStripContext.current
        val dpi = tool.controller.doc.dpi.toDouble()
        val text = Units.format(tool.lineWidth.toDouble(), tool.settings.unit, dpi)
        Box(
            Modifier
                .width(CURVE_LINE_WIDTH_DP.dp)
                .height(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClickLabel = LINE_WIDTH_LABEL, role = Role.Button) {
                    ctx.closeMore()
                    ui.sheet = CurveSheet.SETTINGS
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = if (tool.widthLinked) BrushworkColors.Accent else BrushworkColors.OnChrome,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

private const val LINE_WIDTH_LABEL = "Line width"

private fun addItem(tool: CurveTool) = item(CurveKeys.ADD, ADD_POINT_LABEL, CURVE_ICON_DP, StripRow.ROW1) {
    IconCell(Icons.Filled.AddCircleOutline, ADD_POINT_LABEL, onClick = { tool.addPointAfterSelected() })
}

/** "Delete point": the selected one (or ones, ONE in-tool step); greyed while none is selected. */
private fun deleteItem(tool: CurveTool, f: CurveStripFacts) = item(CurveKeys.DELETE, DELETE_POINT_LABEL, CURVE_ICON_DP, StripRow.ROW1) {
    IconCell(
        Icons.Outlined.Delete, DELETE_POINT_LABEL,
        onClick = { if (f.single >= 0) tool.deleteAnchor(f.single) else tool.deleteSelectedPoints() },
        enabled = f.selected > 0,
        tint = BrushworkColors.Danger,
    )
}

/** "Closed" (Curve, Polyline) or "Cyclic" (Path). */
private fun closedItem(tool: CurveTool, row: StripRow): StripItem {
    val label = if (tool.isPath) "Cyclic" else "Closed"
    return item(CurveKeys.CLOSED, label, CURVE_ICON_DP, row) {
        val on = if (tool.isPath) tool.pathCyclic else tool.settings.closed
        IconCell(Icons.Filled.Loop, label, onClick = { if (tool.isPath) tool.setCyclic(!on) else tool.setClosed(!on) }, selected = on)
    }
}

/** Path: the "Order" item's label and face ("Order 4"). */
internal fun orderLabel(order: Int): String = "Order $order"

/** Path: "Order 4" (its value); its popup holds v1.7's stepper ("Lower order", "Higher order"). */
private fun orderItem(tool: CurveTool, f: CurveStripFacts, row: StripRow) = item(CurveKeys.ORDER, orderLabel(f.order), CURVE_ORDER_DP, row) {
    val order = tool.pathOrder
    DropCell(orderLabel(order), CURVE_ORDER_DP, longPress = false, arrow = false, face = { CellText(orderLabel(order)) }) { _ ->
        OrderStepper(tool, tool.pathOrder)
    }
}

/** The generator chip: its name shown, known as [CurveGenLabels18.CHANGE]; it opens the generator sheet. */
private fun generatorItem(tool: CurveTool, g: CurveGenKind, ui: CurveStripUi, row: StripRow, rank: Int) =
    item(CurveKeys.GENERATOR, CurveGenLabels18.CHANGE, CURVE_GENERATOR_DP, row, rank) {
        val ctx = LocalStripContext.current
        Box(
            Modifier
                .width(CURVE_GENERATOR_DP.dp)
                .height(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(BrushworkColors.ChromeHigh)
                .clickable(role = Role.Button) {
                    ctx.closeMore()
                    ui.sheet = CurveSheet.GENERATOR
                }
                .semantics { contentDescription = CurveGenLabels18.CHANGE }
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                (tool.generator?.kind ?: g).label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 12.sp),
                color = BrushworkColors.OnChrome,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

/** Path: "Endpoint" (greyed while Cyclic is on: it only matters for an open curve). */
private fun endpointItem(tool: CurveTool) = item(CurveKeys.ENDPOINT, "Endpoint", CURVE_ICON_DP, StripRow.MORE) {
    val on = tool.pathEndpoint
    IconCell(Icons.Filled.Commit, "Endpoint", onClick = { tool.setEndpoint(!on) }, selected = on, enabled = !tool.pathCyclic)
}

/** The sharp item: "Sharp corner", "Smooth", "Sharp corner: Mixed" or (greyed) "Ends are always sharp". */
private fun sharpItem(tool: CurveTool, f: CurveStripFacts): StripItem {
    val label = when (f.sharp) {
        SharpState.SHARP -> "Smooth"
        SharpState.SMOOTH -> "Sharp corner"
        SharpState.MIXED -> SHARP_MIXED
        SharpState.ENDS, SharpState.NONE -> PointLabels.ENDS_SHARP
    }
    return item(CurveKeys.SHARP, label, CURVE_ICON_DP, StripRow.MORE) {
        fun set(sharp: Boolean) {
            if (f.single >= 0) tool.setSharp(f.single, sharp) else tool.setSharpPoints(tool.pointSelection.indices, sharp)
        }
        when (f.sharp) {
            SharpState.SHARP -> IconCell(Icons.Filled.Gesture, label, onClick = { set(false) })
            SharpState.SMOOTH -> IconCell(Icons.Filled.ChangeHistory, label, onClick = { set(true) })
            SharpState.MIXED -> IconCell(Icons.Outlined.ChangeHistory, label, onClick = { set(true) })
            SharpState.ENDS, SharpState.NONE -> IconCell(Icons.Filled.ChangeHistory, label, onClick = {}, enabled = false)
        }
    }
}

private fun autoTangentItem(tool: CurveTool, index: Int) = item(CurveKeys.AUTO_TANGENT, "Auto tangent", CURVE_ICON_DP, StripRow.MORE) {
    IconCell(Icons.Filled.Timeline, "Auto tangent", onClick = { tool.resetTangent(index) })
}

private fun all100Item(tool: CurveTool) = item(CurveKeys.ALL_100, "All points 100 %", CURVE_ICON_DP, StripRow.MORE) {
    IconCell(Icons.Filled.Restore, "All points 100 %", onClick = { tool.resetAllWidths() })
}

/** Curve: "Handles" (⟷); its popup is v1.6's Handles group ([HandlesPanel]). */
private fun handlesItem(tool: CurveTool, ui: CurveStripUi) = item(CurveKeys.HANDLES, "Handles", CURVE_ICON_DP, StripRow.MORE) {
    val ctx = LocalStripContext.current
    DropCell("Handles", CURVE_ICON_DP, arrow = false, face = {
        // v1.9 Gate 2 (I27): the glyph shrinks at a large font scale rather than outgrow the cell.
        FitText("⟷", fontSize = MaterialTheme.typography.titleMedium.fontSize, color = BrushworkColors.OnChrome, style = MaterialTheme.typography.titleMedium)
    }) { close ->
        HandlesPanel(tool, onType = {
            close()
            ctx.closeMore()
            ui.typing = CurveTyping.Handles
        })
    }
}

/** "Select all points" ("Deselect all points" once every point is selected). */
private fun selectAllItem(tool: CurveTool, f: CurveStripFacts): StripItem {
    val label = if (f.allSelected) PointLabels.DESELECT_ALL else PointLabels.SELECT_ALL
    return item(CurveKeys.SELECT_ALL, label, CURVE_ICON_DP, StripRow.MORE) {
        IconCell(if (f.allSelected) Icons.Filled.Deselect else Icons.Filled.SelectAll, label, onClick = { tool.toggleSelectAll() })
    }
}

/**
 * "Point thickness": one point's [ThicknessControl] (‹ value › and its slider, whole on a 360 dp
 * screen), or v1.9 (R1) several points' [GroupThicknessControl], the same row at the same width;
 * nothing without a selection.
 */
private fun thicknessItem(tool: CurveTool, f: CurveStripFacts, ui: CurveStripUi): StripItem? = when {
    f.single >= 0 -> item(CurveKeys.THICKNESS, POINT_THICKNESS_LABEL, CURVE_THICKNESS_DP, StripRow.MORE) {
        val ctx = LocalStripContext.current
        ThicknessControl(tool, f.single, tool.widthOf(f.single), onType = {
            ctx.closeMore()
            ui.typing = CurveTyping.Thickness(f.single)
        })
    }
    f.selected >= 2 -> item(CurveKeys.THICKNESS, POINT_THICKNESS_LABEL, CURVE_THICKNESS_DP, StripRow.MORE) {
        val ctx = LocalStripContext.current
        GroupThicknessControl(tool, onType = {
            ctx.closeMore()
            ui.typing = CurveTyping.GroupThickness
        })
    }
    else -> null
}

/** Path: "Point weight" (its value under "Weight"); its popup is v1.6's Weight control or (v1.9) the group's [GroupWeightControl]. */
private fun weightItem(tool: CurveTool, f: CurveStripFacts, ui: CurveStripUi) = item(CurveKeys.WEIGHT, POINT_WEIGHT_LABEL, CURVE_WEIGHT_DP, StripRow.MORE) {
    val ctx = LocalStripContext.current
    val shown = if (f.single >= 0) {
        formatWeight(tool.weightOf(f.single))
    } else {
        when (val m = Mixed.of(tool.selectedWeights())) {
            is Mixed.Same -> formatWeight(m.value)
            is Mixed.Spread -> PointLabels.MIXED
            null -> ""
        }
    }
    DropCell(POINT_WEIGHT_LABEL, CURVE_WEIGHT_DP, longPress = false, arrow = false, face = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // v1.9 Gate 2 (I27): both lines shrink at a large font scale, the caption to at most 18 dp
            // of the 40, rather than be cut.
            val caption = MaterialTheme.typography.labelSmall
            val value = MaterialTheme.typography.labelMedium
            FitText("Weight", fontSize = caption.fontSize, style = caption, color = BrushworkColors.OnChromeDim, modifier = Modifier.heightIn(max = 18.dp))
            FitText(shown, fontSize = value.fontSize, style = value, color = BrushworkColors.OnChrome, overflow = TextOverflow.Ellipsis)
        }
    }) { close ->
        if (f.single >= 0) {
            WeightControl(tool, f.single, tool.weightOf(f.single), onType = {
                close()
                ctx.closeMore()
                ui.typing = CurveTyping.Weight(f.single)
            })
        } else {
            GroupWeightControl(tool, onType = {
                close()
                ctx.closeMore()
                ui.typing = CurveTyping.GroupWeight
            })
        }
    }
}

/**
 * v1.9 (R18): [PointLabels19.BEVEL], Polyline with points selected ([StripKeysV19.BEVEL], tag
 * [V19Tags.BEVEL]): bevels the selected corners at once and opens the popup that adjusts that
 * step ([CurveBevelPopupHost]); while the new points stay selected a second tap reopens it.
 */
private fun bevelItem(tool: CurveTool) = item(StripKeysV19.BEVEL.key, PointLabels19.BEVEL, StripKeysV19.BEVEL.widthDp, StripRow.MORE) {
    IconCell(Icons.Filled.RoundedCorner, PointLabels19.BEVEL, onClick = { tool.bevelPoints() }, tag = V19Tags.BEVEL)
}

private fun deselectItem(tool: CurveTool) = item(CurveKeys.DESELECT, DESELECT_POINT_LABEL, CURVE_ICON_DP, StripRow.MORE) {
    IconCell(Icons.Filled.Deselect, DESELECT_POINT_LABEL, onClick = { tool.deselect() })
}

/** Curve: "Previous point" (the last one while none is selected). */
private fun previousItem(tool: CurveTool) = item(CurveKeys.PREVIOUS, "Previous point", CURVE_ICON_DP, StripRow.MORE) {
    IconCell(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous point", onClick = {
        val n = tool.pointCount
        val i = tool.pointSelection.primary
        if (n > 0) tool.select(if (i < 0) n - 1 else (i - 1 + n) % n)
    })
}

/** Curve: "Next point" (the first one while none is selected). */
private fun nextItem(tool: CurveTool) = item(CurveKeys.NEXT, "Next point", CURVE_ICON_DP, StripRow.MORE) {
    IconCell(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next point", onClick = {
        val n = tool.pointCount
        val i = tool.pointSelection.primary
        if (n > 0) tool.select(if (i < 0) 0 else (i + 1) % n)
    })
}

/** The settings sheet's two stroke toggles on the strip: "Use brush size" (a plain line, not reopened) or Curve's "Taper ends" (a brush). */
private fun brushOrTaper(tool: CurveTool, f: CurveStripFacts): List<StripItem> = when {
    f.kind == CurveKind.PATH -> emptyList()
    f.stroke == CurveStroke.PLAIN && !f.reopened -> listOf(
        item(CurveKeys.BRUSH_SIZE, "Use brush size", CURVE_ICON_DP, StripRow.MORE) {
            val on = tool.settings.useBrushSize
            IconCell(Icons.Filled.Brush, "Use brush size", onClick = { tool.update { it.copy(useBrushSize = !on) } }, selected = on)
        },
    )
    f.stroke == CurveStroke.BRUSH && f.kind == CurveKind.CURVE -> listOf(
        item(CurveKeys.TAPER, "Taper ends", CURVE_ICON_DP, StripRow.MORE) {
            val on = tool.settings.taper
            IconCell(Icons.AutoMirrored.Filled.TrendingFlat, "Taper ends", onClick = { tool.update { it.copy(taper = !on) } }, selected = on)
        },
    )
    else -> emptyList()
}

private fun toBezierItem(tool: CurveTool) = item(CurveKeys.TO_BEZIER, "To Bézier", CURVE_ICON_DP, StripRow.MORE) {
    IconCell(Icons.Filled.Gesture, "To Bézier", onClick = { tool.toBezier() })
}

/** "<Tool> settings", "Numbers" and "Snap to objects", the end of every curve strip's More row. */
private fun sheetItems(tool: CurveTool, ui: CurveStripUi): List<StripItem> {
    val settings = "${tool.id.label} settings"
    return listOf(
        item(CurveKeys.SETTINGS, settings, CURVE_ICON_DP, StripRow.MORE) {
            val ctx = LocalStripContext.current
            IconCell(Icons.Filled.Tune, settings, onClick = {
                ctx.closeMore()
                ui.sheet = CurveSheet.SETTINGS
            })
        },
        item(CurveKeys.NUMBERS, "Numbers", CURVE_ICON_DP, StripRow.MORE) {
            val ctx = LocalStripContext.current
            IconCell(Icons.Filled.Pin, "Numbers", onClick = {
                ctx.closeMore()
                ui.sheet = CurveSheet.NUMBERS
            })
        },
        item(CurveKeys.SNAP, SNAP_LABEL, CURVE_ICON_DP, StripRow.MORE) {
            val snapping = tool.controller.snapping
            val on = snapping.enabled
            IconCell(
                Icons.Filled.AlignHorizontalCenter, SNAP_LABEL,
                onClick = { snapping.enabled = !on },
                selected = on,
                state = if (on) "$SNAP_LABEL: on" else "$SNAP_LABEL: off",
            )
        },
    )
}

private fun removeCopiesItem(tool: CurveTool) = item(CurveKeys.REMOVE_COPIES, MirrorLabels18.REMOVE, CURVE_ICON_DP, StripRow.MORE) {
    IconCell(Icons.Filled.LayersClear, MirrorLabels18.REMOVE, onClick = { tool.removeCopies() })
}

// ====================================================================== cells

/**
 * A 40 dp icon item known by [description] (also its long-press label, §3.9). With [selected]
 * it is a toggle (selected semantics, as v1.7's chips); [state] is its state description.
 */
@Composable
private fun IconCell(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    selected: Boolean? = null,
    tint: Color = BrushworkColors.OnChrome,
    state: String? = null,
    tag: String? = null,
) {
    val on = selected == true
    val base = Modifier
        .then(if (tag != null) Modifier.testTag(tag) else Modifier)
        .size(CURVE_ICON_DP.dp)
        .clip(RoundedCornerShape(8.dp))
        .background(if (on) BrushworkColors.AccentDim else Color.Transparent)
        .longPressLabel(description)
    val action = if (selected != null) {
        base.selectable(selected = on, enabled = enabled, role = Role.Checkbox, onClick = onClick)
    } else {
        base.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    }
    Box(
        action.semantics {
            contentDescription = description
            if (state != null) stateDescription = state
        },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = when {
                !enabled -> BrushworkColors.OnChromeDim.copy(alpha = 0.5f)
                on -> Color.White
                else -> tint
            },
            modifier = Modifier.size(22.dp),
        )
    }
}

/** A text face of a [DropCell]. */
@Composable
private fun CellText(text: String) {
    // v1.9 Gate 2 (I27): shrinks at a large font scale rather than be cut.
    val style = MaterialTheme.typography.labelLarge
    FitText(text, fontSize = style.fontSize, style = style, color = BrushworkColors.OnChrome, overflow = TextOverflow.Ellipsis)
}

/**
 * An item [width] dp wide that opens an anchored dropdown or popup (opaque, §3.9): it stays in the
 * item and keeps the More row open. [menu] gets the call that closes it.
 */
@Composable
private fun DropCell(
    description: String,
    width: Int,
    longPress: Boolean = true,
    arrow: Boolean = true,
    face: @Composable RowScope.() -> Unit,
    menu: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val close = { open = false }
    Box {
        Row(
            Modifier
                .width(width.dp)
                .height(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .then(if (longPress) Modifier.longPressLabel(description) else Modifier)
                .clickable(role = Role.DropdownList) { open = true }
                .semantics { contentDescription = description }
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
            face()
            if (arrow) Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = BrushworkColors.OnChromeDim, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = close,
            containerColor = IbisColors.Menu,
            modifier = Modifier.closeOnSecondFinger(close),
        ) { menu(close) }
    }
}

/** The generator kind's name ([CurveGenLabels18]): the [CurveGenLabels18.CURVES] entries, the chip and the sheet's title. */
internal val CurveGenKind.label: String
    get() = when (this) {
        CurveGenKind.ARCHIMEDEAN -> CurveGenLabels18.ARCHIMEDEAN
        CurveGenKind.LOGARITHMIC -> CurveGenLabels18.LOGARITHMIC
        CurveGenKind.GOLDEN -> CurveGenLabels18.GOLDEN
        CurveGenKind.FERMAT -> CurveGenLabels18.FERMAT
        CurveGenKind.SINE -> CurveGenLabels18.SINE
        CurveGenKind.ROSE -> CurveGenLabels18.ROSE
    }
