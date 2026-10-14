package com.brushwork.paint.ui.vector

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.AlignHorizontalCenter
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.ChangeHistory
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material.icons.filled.LineWeight
import androidx.compose.material.icons.filled.Pin
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.RoundedCorner
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Units
import com.brushwork.paint.tools.points.Mixed
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.tools.vector.LineCapStyle
import com.brushwork.paint.tools.vector.ShapeGeometry
import com.brushwork.paint.tools.vector.ShapePoints
import com.brushwork.paint.tools.vector.ShapeStroke
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.common.MirrorLabels18
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.theme.BrushworkColors
import com.brushwork.paint.ui.tools.strip.LocalStripContext
import com.brushwork.paint.ui.tools.strip.StripItem
import com.brushwork.paint.ui.tools.strip.StripRow
import com.brushwork.paint.ui.tools.strip.StripSpec

/**
 * The Shape tool's strip (v1.8, design §3.9 and §4.6; area F).
 *
 * Row 1: "Shape type" 56 (the type's icon and ▾; its menu lists every type, "Cog" last),
 * "Shape style" 56 (the style glyph and ▾), or for Line and Arrow "Line ends" 56 (plain lines
 * only; a brush paints its own ends), the stroke width 64 (AUTO 1; its text, "60 px", opens
 * "Shape settings") and, while a shape is open, "Points" 72. 260 dp in full, 192 without the
 * AUTO item; a cell that doesn't apply keeps its room (neighbours never jump).
 *
 * The More row, in order: "Editable" (a new shape in a layer of its own: raster layers, not
 * while a shape layer is open), "Stroke with", "Center", "Proportional" ("Circle" for an
 * ellipse), "15°", "Snap to objects", "Numbers" and "Shape settings"; in Points mode "Select all
 * points" / "Deselect all points" and the selected points' actions ("Sharp corner" / "Smooth",
 * "Auto tangent", "Delete point", "Deselect point", "Point roundness" and its reset, the
 * "Handles" popup), "Reset shape" (a shape with its own points) and "Turn into path" (also for
 * an open cog with a hole, whose Points mode is refused: D21 keeps its Turn into path, a compound
 * path); and [MirrorLabels18.REMOVE] while the open shape has copies (§3.1, one in-tool step).
 * v1.9 (R18) adds [PointLabels19.BEVEL] in Points mode, last ([shapeBevelItem]; its popup is an overlay,
 * [ShapeBevelPopupHost]), and shows the box's Center and Proportional / Circle outside Points
 * mode only ([ShapeStripInfo.boxModifiersShown]). v1.9 (R14) adds the object items
 * ([shapeObjectItems]: duplicate, bring forward, send backward) after v1.8's More items while an
 * open shape is or becomes a shape object ([ShapeTool.objectActionsApply], never in Points
 * mode), hidden while the selection bar or the object bar shows (`StripContext.selectionBarShown`).
 *
 * Every handle is the v1.7 one (StripHandlesV17 "SHAPE"; "Snap" became "Snap to objects"). The
 * Shape settings and Numbers sheets and the typed handle scale are overlays: they outlive the
 * More row, which an item closes before it opens one.
 */
@Composable
internal fun shapeStrip(tool: ShapeTool, c: EditorController): StripSpec {
    val strip = LocalStripContext.current
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showNumbers by rememberSaveable { mutableStateOf(false) }
    var typingHandles by remember { mutableStateOf(false) }
    var typingRoundness by remember { mutableStateOf(false) }
    // docVersion bumps when the document size / dpi change, so the width text follows the dpi.
    val dpi = remember(c.docVersion) { c.doc.dpi }
    val info by remember(tool, dpi) { derivedStateOf { ShapeStripInfo.of(tool, dpi) } }
    val openSettings = {
        strip.closeMore()
        showSettings = true
    }
    val items = ShapeStripItems.items(
        tool, c, info,
        openSettings = openSettings,
        openNumbers = {
            strip.closeMore()
            if (tool.ensurePending()) showNumbers = true
        },
        typeHandles = {
            strip.closeMore()
            typingHandles = true
        },
        typeRoundness = {
            strip.closeMore()
            typingRoundness = true
        },
        barShown = strip.selectionBarShown,
    )
    val keys = items.map { it.key }.toSet()
    return StripSpec(
        items,
        overlays = {
            if (showSettings) ShapeSettingsSheet(tool) { showSettings = false }
            if (showNumbers) ShapeNumbersSheet(tool) { showNumbers = false }
            if (typingHandles) ShapeHandleScaleDialog(tool) { typingHandles = false }
            if (typingRoundness) ShapeRoundnessDialog(tool) { typingRoundness = false }
            // v1.9 (R18): the bevel popup, while a bevel is open.
            ShapeBevelPopupHost(tool)
        },
        // The v1.7 type, style and line-end chips' entries are inside their dropdowns; the
        // Handles group is inside "Handles".
        movedInto = ShapeStripItems.MOVED.filterValues { it in keys },
    )
}

/** What decides the Shape strip's items and their handles: only a change of these rebuilds it. */
internal data class ShapeStripInfo(
    val type: ShapeType,
    val strokes: Boolean,
    val strokeWith: ShapeStroke,
    /** The width cell's text ("60 px"), or null while the outline has no width of its own to show. */
    val widthText: String?,
    val editableShown: Boolean,
    val pending: Boolean,
    val pointsMode: Boolean,
    val custom: Boolean,
    val count: Int,
    val selectedCount: Int,
    val smooth: Mixed<Boolean>?,
    val explicitHandles: Boolean,
    val anyHandles: Boolean,
    /** "Turn into path" shows: in Points mode, or for an open shape whose Points mode is refused (a cog with a hole, D21). */
    val toPathShown: Boolean,
    val toPathRefusal: String?,
    val hasCopies: Boolean,
    /** v1.9 (R14): the object items show ([ShapeTool.objectActionsApply]; [shapeObjectItems]). */
    val objectActions: Boolean,
) {
    val lineLike: Boolean get() = type.isLineLike

    /** The style cell shows: a closed type's style, or a plain line's ends. */
    val styleShown: Boolean get() = !lineLike || strokeWith == ShapeStroke.PLAIN

    /**
     * v1.9 (R18): [ShapeStripItems.CENTER_LABEL] and [ShapeStripItems.PROPORTIONAL_LABEL] /
     * [ShapeStripItems.CIRCLE_LABEL] show outside Points mode only. They act on the box's resize
     * handles and on a drag that draws a new shape; Points mode has no box handles, so there they
     * only pre-set the next new shape. Without them the fullest Points mode (copies, a smooth
     * point with handles and a corner selected, [PointLabels19.BEVEL]) keeps its More row within
     * three lines at 360 dp, also with the VECTOR cell (a vector layer whose mask is edited).
     */
    val boxModifiersShown: Boolean get() = !pointsMode

    companion object {
        fun of(tool: ShapeTool, dpi: Float): ShapeStripInfo {
            val s = tool.settings
            val pts = tool.points
            val n = pts?.size ?: 0
            val pending = tool.box != null
            val pointsMode = pending && tool.pointsMode
            val sel = tool.pointSelection.resized(n)
            val widthShown = s.strokes && (s.strokeWith == ShapeStroke.PLAIN || s.type == ShapeType.ARROW)
            // v1.8 (D21): a holed cog never reaches Points mode, yet it turns into a (compound) path.
            val toPathShown = pointsMode || (pending && ShapePoints.refusal(s.type, s.outlineParams) != null)
            return ShapeStripInfo(
                type = s.type,
                strokes = s.strokes,
                strokeWith = s.strokeWith,
                widthText = if (widthShown) Units.format(tool.strokeWidth.toDouble(), s.unit, dpi.toDouble()) else null,
                editableShown = tool.editingLayer == null && !tool.drawsOnVectorLayer,
                pending = pending,
                pointsMode = pointsMode,
                custom = pending && pts != null,
                count = n,
                selectedCount = if (pointsMode) sel.count else 0,
                smooth = if (pointsMode) tool.selectionSmooth else null,
                explicitHandles = pointsMode && tool.selectionHasExplicitHandles,
                anyHandles = pointsMode && pts?.any { it.smooth || it.handleIn != null || it.handleOut != null } == true,
                toPathShown = toPathShown,
                toPathRefusal = if (toPathShown) tool.turnIntoPathRefusal else null,
                hasCopies = tool.hasCopies,
                objectActions = tool.objectActionsApply,
            )
        }
    }
}

/** The Shape strip's items: keys, widths and handles (the v1.7 labels). */
internal object ShapeStripItems {
    const val TYPE = "shape.type"
    const val STYLE = "shape.style"
    const val WIDTH = "shape.width"
    const val POINTS = "shape.points"
    const val EDITABLE = "shape.editable"
    const val STROKE_WITH = "shape.strokeWith"
    const val CENTER = "shape.center"
    const val PROPORTIONAL = "shape.proportional"
    const val ANGLE = "shape.angle"
    const val SNAP = "shape.snap"
    const val NUMBERS = "shape.numbers"
    const val SETTINGS = "shape.settings"
    const val SELECT_ALL = "shape.selectAll"
    const val SMOOTH = "shape.smooth"
    const val AUTO_TANGENT = "shape.autoTangent"
    const val DELETE_POINT = "shape.deletePoint"
    const val DESELECT_POINT = "shape.deselectPoint"
    const val ROUNDNESS = "shape.roundness"
    const val RESET_ROUNDNESS = "shape.resetRoundness"
    const val HANDLES = "shape.handles"
    const val RESET_SHAPE = "shape.resetShape"
    const val TO_PATH = "shape.toPath"
    const val REMOVE_COPIES = "shape.removeCopies"

    const val TYPE_DP = 56f
    const val STYLE_DP = 56f
    const val WIDTH_DP = 64f
    const val POINTS_DP = 72f
    const val CELL_DP = 40f
    const val STROKE_WITH_DP = 40f
    const val ROUNDNESS_DP = 96f
    const val HANDLES_DP = 40f

    /** The Handles popup's width: ‹ value › and the 120 dp slider on its first line. */
    const val HANDLES_POPUP_DP = 296f

    const val TYPE_LABEL = "Shape type"
    const val STYLE_LABEL = "Shape style"
    const val LINE_ENDS_LABEL = "Line ends"
    const val POINTS_LABEL = "Points"
    const val EDITABLE_LABEL = "Editable"
    const val STROKE_WITH_LABEL = "Stroke with"
    const val CENTER_LABEL = "Center"
    const val PROPORTIONAL_LABEL = "Proportional"
    const val CIRCLE_LABEL = "Circle"
    const val ANGLE_LABEL = "15°"
    const val SNAP_LABEL = "Snap to objects"
    const val NUMBERS_LABEL = "Numbers"
    const val SETTINGS_LABEL = "Shape settings"
    const val HANDLES_LABEL = "Handles"
    const val SHARP_LABEL = "Sharp corner"
    const val SMOOTH_LABEL = "Smooth"
    const val AUTO_TANGENT_LABEL = "Auto tangent"
    const val DELETE_POINT_LABEL = "Delete point"
    const val DESELECT_POINT_LABEL = "Deselect point"
    const val RESET_SHAPE_LABEL = "Reset shape"

    /** The v1.7 controls inside an item's dropdown or popup: handle to item key (inventory rule 5). */
    val MOVED: Map<String, String> = buildMap {
        for (h in listOf("Shorter handles", "Longer handles", "Handle scale", "In and out", "In", "Out", "All points")) put(h, HANDLES)
    }

    fun items(
        tool: ShapeTool,
        c: EditorController,
        info: ShapeStripInfo,
        openSettings: () -> Unit,
        openNumbers: () -> Unit,
        typeHandles: () -> Unit,
        typeRoundness: () -> Unit,
        barShown: Boolean,
    ): List<StripItem> = buildList {
        fun set(f: (com.brushwork.paint.tools.vector.ShapeSettings) -> com.brushwork.paint.tools.vector.ShapeSettings) = tool.update(f)
        val lineLike = info.lineLike

        // ---- Row 1.
        add(
            StripItem(TYPE, TYPE_LABEL, TYPE_DP.dp) {
                val type = tool.settings.type
                ShapeMenuCell(
                    label = TYPE_LABEL,
                    entries = ShapeGeometry.pickerTypes,
                    selected = type,
                    entryText = { it.label },
                    onPick = { t -> set { it.copy(type = t) } },
                    width = TYPE_DP.dp,
                    entryGlyph = { Icon(shapeIcon(it), contentDescription = null) },
                ) { Icon(shapeIcon(type), contentDescription = null, tint = BrushworkColors.Accent) }
            },
        )
        add(
            StripItem(STYLE, if (lineLike) LINE_ENDS_LABEL else STYLE_LABEL, STYLE_DP.dp, shown = info.styleShown) {
                val s = tool.settings
                if (!s.type.isLineLike) {
                    val fill = s.fillColor ?: c.color
                    ShapeMenuCell(
                        label = STYLE_LABEL,
                        entries = ShapeStyle.entries,
                        selected = s.style,
                        entryText = { it.label },
                        onPick = { st -> set { it.copy(style = st) } },
                        width = STYLE_DP.dp,
                        entryGlyph = { StyleGlyph(it, fill) },
                    ) { StyleGlyph(s.style, fill) }
                } else if (s.strokeWith == ShapeStroke.PLAIN) {
                    // A brush paints its own ends: the line caps only apply to plain lines.
                    ShapeMenuCell(
                        label = LINE_ENDS_LABEL,
                        entries = LineCapStyle.entries,
                        selected = s.lineCap,
                        entryText = { "${it.label} ends" },
                        onPick = { cap -> set { it.copy(lineCap = cap) } },
                        width = STYLE_DP.dp,
                        entryGlyph = { LineEndsGlyph(it) },
                    ) { LineEndsGlyph(s.lineCap) }
                }
            },
        )
        val widthText = info.widthText
        add(
            StripItem(WIDTH, widthText ?: "", WIDTH_DP.dp, overflowRank = 1, shown = widthText != null) {
                // The width (the brush size while "Use brush size" is on); its editor is in Shape settings.
                if (widthText != null) ShapeValueCell(widthText, WIDTH_DP.dp, openSettings)
            },
        )
        add(
            StripItem(POINTS, POINTS_LABEL, POINTS_DP.dp, shown = info.pending) {
                if (tool.box != null) ShapeTextToggle(POINTS_LABEL, tool.pointsMode, { tool.setPointEditing(!tool.pointsMode) }, POINTS_DP.dp)
            },
        )

        // ---- The More row.
        if (info.editableShown) {
            add(more(EDITABLE, EDITABLE_LABEL) {
                val on = tool.settings.editable
                ShapeIconToggle(Icons.Filled.Layers, EDITABLE_LABEL, on, { set { it.copy(editable = !on) } })
            })
        }
        if (info.strokes) {
            add(more(STROKE_WITH, STROKE_WITH_LABEL, STROKE_WITH_DP) {
                val m = tool.settings.strokeWith
                ShapeMenuCell(
                    label = STROKE_WITH_LABEL,
                    entries = ShapeStroke.entries,
                    selected = m,
                    entryText = { it.label },
                    onPick = { w -> set { it.copy(strokeWith = w) } },
                    width = STROKE_WITH_DP.dp,
                    entryGlyph = { Icon(strokeIcon(it), contentDescription = null) },
                ) { Icon(strokeIcon(m), contentDescription = null, tint = BrushworkColors.OnChrome) }
            })
        }
        if (info.boxModifiersShown) add(more(CENTER, CENTER_LABEL) {
            val on = tool.settings.fromCenter
            ShapeIconToggle(Icons.Filled.CenterFocusStrong, CENTER_LABEL, on, { set { it.copy(fromCenter = !on) } })
        })
        if (!lineLike && info.boxModifiersShown) {
            val label = if (info.type == ShapeType.ELLIPSE) CIRCLE_LABEL else PROPORTIONAL_LABEL
            add(more(PROPORTIONAL, label) {
                val on = tool.settings.keepProportions
                ShapeIconToggle(Icons.Filled.AspectRatio, label, on, { set { it.copy(keepProportions = !on) } })
            })
        }
        add(more(ANGLE, ANGLE_LABEL) {
            val on = tool.settings.snapAngle
            ShapeTextToggle(ANGLE_LABEL, on, { set { it.copy(snapAngle = !on) } })
        })
        add(more(SNAP, SNAP_LABEL) {
            val on = c.snapping.enabled
            ShapeIconToggle(Icons.Filled.AlignHorizontalCenter, SNAP_LABEL, on, { c.snapping.enabled = !on }, stateText = { if (it) "$SNAP_LABEL: on" else "$SNAP_LABEL: off" })
        })
        add(more(NUMBERS, NUMBERS_LABEL) { ShapeIconAction(Icons.Filled.Pin, NUMBERS_LABEL, onClick = openNumbers) })
        // I10: "Shape settings" (the tool menu has a "Settings" cell).
        add(more(SETTINGS, SETTINGS_LABEL) { ShapeIconAction(Icons.Filled.Tune, SETTINGS_LABEL, onClick = openSettings) })

        if (info.pointsMode) {
            val all = info.count > 0 && info.selectedCount == info.count
            val selectLabel = if (all) PointLabels.DESELECT_ALL else PointLabels.SELECT_ALL
            add(more(SELECT_ALL, selectLabel) {
                ShapeIconAction(if (all) Icons.Filled.Deselect else Icons.Filled.SelectAll, selectLabel) {
                    val n = tool.pointCount
                    tool.selectPoints(if (all) PointSelection.none(n) else PointSelection.all(n))
                }
            })
            val smooth = info.smooth
            if (smooth != null) {
                val sharpens = smooth == Mixed.Same(true)
                val smoothLabel = if (sharpens) SHARP_LABEL else SMOOTH_LABEL
                add(more(SMOOTH, smoothLabel) {
                    ShapeIconAction(
                        if (sharpens) Icons.Filled.ChangeHistory else Icons.Filled.Gesture,
                        smoothLabel,
                        state = if (smooth is Mixed.Spread) PointLabels.MIXED else null,
                    ) { tool.setSelectedSmooth(!sharpens) }
                })
                if (info.explicitHandles) {
                    add(more(AUTO_TANGENT, AUTO_TANGENT_LABEL) { ShapeIconAction(Icons.Filled.Restore, AUTO_TANGENT_LABEL) { tool.resetSelectedTangents() } })
                }
                add(more(DELETE_POINT, DELETE_POINT_LABEL) { DeletePointCell(tool) })
                add(more(DESELECT_POINT, DESELECT_POINT_LABEL) {
                    ShapeIconAction(Icons.Filled.Deselect, DESELECT_POINT_LABEL) { tool.selectPoints(PointSelection.none(tool.pointCount)) }
                })
                if (!lineLike) {
                    add(more(ROUNDNESS, PointLabels.ROUNDNESS, ROUNDNESS_DP) {
                        ShapeRoundnessCell(tool, rememberPointsInfo(tool).roundness, ROUNDNESS_DP.dp, typeRoundness)
                    })
                    add(more(RESET_ROUNDNESS, PointLabels.RESET_ROUNDNESS) {
                        ShapeIconAction(Icons.Filled.RoundedCorner, PointLabels.RESET_ROUNDNESS, enabled = tool.canResetPointRoundness) { tool.resetPointRoundness() }
                    })
                }
            }
            if (info.anyHandles) {
                add(more(HANDLES, HANDLES_LABEL, HANDLES_DP) { HandlesCell(tool, info.selectedCount > 0, typeHandles) })
            }
        }
        if (info.custom) {
            add(more(RESET_SHAPE, RESET_SHAPE_LABEL) { ShapeIconAction(Icons.Filled.RestartAlt, RESET_SHAPE_LABEL) { tool.resetShape() } })
        }
        if (info.toPathShown) {
            // A refused conversion (an arrow, an outline of a tool that moves pixels) greys the
            // cell and says why; a tap says it again (ShapeTool.turnIntoPath's message).
            val refusal = info.toPathRefusal
            add(more(TO_PATH, PointLabels.TO_PATH) {
                ShapeIconAction(Icons.AutoMirrored.Filled.ShowChart, PointLabels.TO_PATH, dim = refusal != null, state = refusal) { tool.turnIntoPath() }
            })
        }
        if (info.hasCopies) {
            add(more(REMOVE_COPIES, MirrorLabels18.REMOVE) { ShapeIconAction(Icons.Filled.LayersClear, MirrorLabels18.REMOVE) { tool.removeCopies() } })
        }
        // v1.9 (R14): the object items (outside Points mode), hidden while the selection bar or the
        // object bar shows: they offer the same labels (I10).
        if (info.objectActions && !barShown) addAll(shapeObjectItems(tool))
        // v1.9 (R18): [PointLabels19.BEVEL], after v1.8's More items (StripKeysV19 rank 60); in
        // Points mode the box's Center and Proportional are hidden ([ShapeStripInfo.boxModifiersShown]).
        if (info.pointsMode) add(shapeBevelItem(tool))
    }

    private fun more(key: String, label: String, widthDp: Float = CELL_DP, content: @Composable () -> Unit) =
        StripItem(key, label, widthDp.dp, row = StripRow.MORE, content = content)

    private fun strokeIcon(m: ShapeStroke) = if (m == ShapeStroke.BRUSH) Icons.Filled.Brush else Icons.Filled.LineWeight
}

/**
 * "Delete point": the one selected point, or the selected points (as the Path strip; "Delete
 * selected points" is the pill's trash cell, §3.13). Disabled while the shape would keep too few.
 */
@Composable
private fun DeletePointCell(tool: ShapeTool) {
    val info = rememberPointsInfo(tool)
    val sel = info.selected
    val enabled = if (sel >= 0) info.count > tool.minPoints else info.count - info.selectedCount >= tool.minPoints
    ShapeIconAction(Icons.Outlined.Delete, ShapeStripItems.DELETE_POINT_LABEL, enabled = enabled, tint = BrushworkColors.Danger) {
        if (sel >= 0) tool.deletePoint(sel) else tool.deleteSelectedPoints()
    }
}

/** The custom increment step of "Point roundness" (px; the v1.7 strip's key). */
internal const val SHAPE_ROUNDNESS_INCREMENT_KEY = "shape.pointRoundness"

/**
 * "Handles" (v1.6 §3.3 in a popup): ⟷ on the cell (the scale is its state); the popup holds the v1.7 group
 * (‹ value › and the "Handle scale" slider, then the side and "All points"). Typing the scale
 * closes the popup and the More row, and opens the dialog as an overlay ([onType]).
 */
@Composable
private fun HandlesCell(tool: ShapeTool, hasSelection: Boolean, onType: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    ShapePopupCell(
        label = ShapeStripItems.HANDLES_LABEL,
        value = "${Units.formatNumber((tool.handleScale * 100f).toDouble(), 0)} %",
        width = ShapeStripItems.HANDLES_DP.dp,
        open = open,
        onOpen = { open = it },
        popupWidth = ShapeStripItems.HANDLES_POPUP_DP.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShapeHandleScaleControls(tool) {
                open = false
                onType()
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) { ShapeHandleSideControls(tool, hasSelection) }
        Spacer(Modifier.height(4.dp))
    }
}
