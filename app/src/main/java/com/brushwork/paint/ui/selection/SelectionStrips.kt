package com.brushwork.paint.ui.selection

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChangeHistory
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Rectangle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.select.EyedropperTool
import com.brushwork.paint.tools.select.FillSettings
import com.brushwork.paint.tools.select.FillTool
import com.brushwork.paint.tools.select.LassoCommit
import com.brushwork.paint.tools.select.LassoKind
import com.brushwork.paint.tools.select.LassoTool
import com.brushwork.paint.tools.select.MagicWandTool
import com.brushwork.paint.tools.select.MarqueeShape
import com.brushwork.paint.tools.select.MarqueeTool
import com.brushwork.paint.tools.select.ObjectSelectTool
import com.brushwork.paint.tools.select.SampleSource
import com.brushwork.paint.tools.select.colorrange.ColorSelectTool
import com.brushwork.paint.ui.common.SnapToObjectsChip
import com.brushwork.paint.ui.common.ToolIconButton
import com.brushwork.paint.ui.theme.BrushworkColors
import com.brushwork.paint.ui.tools.strip.StripItem
import com.brushwork.paint.ui.tools.strip.StripRow
import com.brushwork.paint.ui.tools.strip.StripSpec

// The selection tools' strips (v1.8, design §3.9 and §4.6, area C): row 1 holds the most-used
// controls at their declared widths (dp), the More row the rest. "Deselect" and "Selection menu"
// join the More row only while the selection bar, which has both, is hidden.

/** Bucket: tolerance, reference and gap closing; expand and anti-alias on the More row. */
@Composable
internal fun fillStrip(tool: FillTool, c: EditorController): StripSpec {
    val s = tool.settings
    return StripSpec(
        items = listOf(
            StripItem("fill.tolerance", "Tolerance ${s.tolerance}", 72.dp) {
                StripSliderChip("Tolerance", s.tolerance, 0..255, 72.dp) { tool.settings = tool.settings.copy(tolerance = it) }
            },
            StripItem("fill.refer", "Refer: ${s.source.label}", 80.dp) {
                StripChoiceChip("Refer", SampleSource.entries, s.source, { it.label }, 80.dp) { tool.settings = tool.settings.copy(source = it) }
            },
            StripItem("fill.gaps", "Close gaps ${s.gapClose} px", 72.dp) {
                StripSliderChip("Close gaps", s.gapClose, 0..FillSettings.MAX_GAP_CLOSE, 72.dp, suffix = " px") { tool.settings = tool.settings.copy(gapClose = it) }
            },
            StripItem("fill.expand", "Expand ${s.expand} px", 72.dp, row = StripRow.MORE) {
                StripSliderChip("Expand", s.expand, 0..FillSettings.MAX_EXPAND, 72.dp, suffix = " px") { tool.settings = tool.settings.copy(expand = it) }
            },
            StripItem("fill.aa", "Anti-alias", ANTI_ALIAS_DP.dp, row = StripRow.MORE) {
                StripToggle("Anti-alias", s.antiAlias, ANTI_ALIAS_DP.dp) { tool.settings = tool.settings.copy(antiAlias = it) }
            },
        ),
    )
}

/** Eyedropper: what it samples, the sample size, back to the brush. One row, no More row. */
@Composable
internal fun eyedropperStrip(tool: EyedropperTool, c: EditorController): StripSpec {
    val s = tool.settings
    return StripSpec(
        items = listOf(
            StripItem("eyedropper.sample", "Sample: ${s.source.label}", 80.dp) {
                StripChoiceChip("Sample", listOf(SampleSource.CANVAS, SampleSource.LAYER), s.source, { it.label }, 80.dp) {
                    tool.settings = tool.settings.copy(source = it)
                }
            },
            StripItem("eyedropper.size", "Size: ${s.sampleSize}×${s.sampleSize}", 64.dp) {
                StripChoiceChip("Size", SAMPLE_SIZES, s.sampleSize, { "$it×$it" }, 64.dp) { tool.settings = tool.settings.copy(sampleSize = it) }
            },
            StripItem("eyedropper.back", "Back to brush", 104.dp) {
                StripToggle("Back to brush", s.returnToBrush, 104.dp) { tool.settings = tool.settings.copy(returnToBrush = it) }
            },
        ),
    )
}

private val SAMPLE_SIZES = listOf(1, 3, EyedropperTool.MAX_SAMPLE)

/** Magic wand: mode, tolerance, contiguous; reference, anti-alias and the shortcuts on the More row. */
@Composable
internal fun magicWandStrip(tool: MagicWandTool, c: EditorController): StripSpec {
    val s = tool.settings
    var menu by remember(tool) { mutableStateOf(false) }
    return StripSpec(
        items = listOf(
            selectionModeItem(tool.mode) { tool.mode = it },
            StripItem("wand.tolerance", "Tolerance ${s.tolerance}", 72.dp) {
                StripSliderChip("Tolerance", s.tolerance, 0..255, 72.dp) { tool.settings = tool.settings.copy(tolerance = it) }
            },
            StripItem("wand.contiguous", "Contiguous", 104.dp) {
                StripToggle("Contiguous", s.contiguous, 104.dp) { tool.settings = tool.settings.copy(contiguous = it) }
            },
            StripItem("wand.refer", "Refer: ${s.source.label}", 80.dp, row = StripRow.MORE) {
                StripChoiceChip("Refer", SampleSource.entries, s.source, { it.label }, 80.dp) { tool.settings = tool.settings.copy(source = it) }
            },
            StripItem("wand.aa", "Anti-alias", ANTI_ALIAS_DP.dp, row = StripRow.MORE) {
                StripToggle("Anti-alias", s.antiAlias, ANTI_ALIAS_DP.dp) { tool.settings = tool.settings.copy(antiAlias = it) }
            },
        ) + selectionShortcutItems(c) { menu = true },
        overlays = { SelectionMenuOverlay(c, menu) { menu = false } },
        movedInto = modesMovedInto,
    )
}

/**
 * Lasso: mode and kind (one dropdown, v1.7's "<kind> lasso" buttons inside); for a polygon or
 * curve, ✓ (with v1.7's point count under it) and ✕, their room kept while nothing is placed; a
 * long-pressed curve point's sharp / smooth and delete (AUTO: at 360 dp they front the More row).
 * Snapping (polygon and curve), anti-alias and the shortcuts on the More row, v1.7's "how to"
 * text as its hint.
 */
@Composable
internal fun lassoStrip(tool: LassoTool, c: EditorController): StripSpec {
    val s = tool.settings
    val kind = tool.kind
    val curve = tool.curve
    // Derived: dragging a curve point makes a new list on every move, the strip needs only these.
    val pending by remember(tool) { derivedStateOf { tool.hasPendingWork } }
    val count by remember(tool) { derivedStateOf { if (tool.kind == LassoKind.CURVE) tool.curve.count else tool.vertexCount } }
    val point by remember(curve) {
        derivedStateOf { curve.anchors.getOrNull(curve.selected)?.let { LassoPoint(curve.selected, it.sharp) } }
    }
    var menu by remember(tool) { mutableStateOf(false) }
    val noun = if (kind == LassoKind.CURVE) "curve" else "polygon"
    val items = buildList {
        add(selectionModeItem(tool.mode) { tool.mode = it })
        add(
            StripItem(LASSO_KIND_KEY, "${kind.label} lasso", LassoKindWidth) {
                IconDropdown(lassoKindIcon(kind), "${kind.label} lasso", LassoKindWidth) { close ->
                    LassoKind.entries.forEach { k ->
                        StripMenuItem(k.label, checked = k == kind, icon = lassoKindIcon(k), description = "${k.label} lasso") {
                            close()
                            if (k != tool.kind) {
                                tool.setKind(k)
                                lassoKindHint(k)?.let { c.toast(it) }
                            }
                        }
                    }
                }
            },
        )
        if (kind != LassoKind.FREEHAND) {
            add(
                StripItem("lasso.close", "Close $noun", IconItemWidth, shown = pending) {
                    CountedCloseButton("Close $noun", count) { tool.commit() }
                },
            )
            add(
                StripItem("lasso.discard", "Discard $noun", IconItemWidth, shown = pending) {
                    ToolIconButton(Icons.Filled.Close, "Discard $noun", onClick = { tool.discard() }, size = IconItemWidth)
                },
            )
        }
        val a = point
        if (kind == LassoKind.CURVE && a != null) {
            val label = if (a.sharp) "Smooth" else "Sharp"
            add(
                StripItem("lasso.sharp", label, IconItemWidth, overflowRank = 2) {
                    StripIconAction(if (a.sharp) Icons.Filled.Gesture else Icons.Filled.ChangeHistory, label) { curve.setSharp(a.index, !a.sharp) }
                },
            )
            add(
                StripItem("lasso.delete", "Delete", IconItemWidth, overflowRank = 1) {
                    StripIconAction(Icons.Outlined.Delete, "Delete", tint = BrushworkColors.Danger) { curve.deleteAnchor(a.index) }
                },
            )
        }
        if (kind != LassoKind.FREEHAND) add(snapItem("lasso.snap", c))
        add(
            StripItem("lasso.aa", "Anti-alias", ANTI_ALIAS_DP.dp, row = StripRow.MORE) {
                StripToggle("Anti-alias", s.antiAlias, ANTI_ALIAS_DP.dp) { tool.settings = tool.settings.copy(antiAlias = it) }
            },
        )
        addAll(selectionShortcutItems(c) { menu = true })
    }
    val hint = when {
        tool.busy -> LassoCommit.BUSY_LABEL
        pending -> null
        kind == LassoKind.POLYGON -> "Tap corners"
        kind == LassoKind.CURVE -> "Tap points · long-press one to edit"
        else -> null
    }
    return StripSpec(
        items = items,
        hint = hint,
        overlays = { SelectionMenuOverlay(c, menu) { menu = false } },
        movedInto = modesMovedInto + LassoKind.entries.associate { it.label to LASSO_KIND_KEY },
    )
}

/** The lasso's kind dropdown: its key and declared width. */
internal const val LASSO_KIND_KEY = "lasso.kind"
internal val LassoKindWidth = 56.dp

/** Select shape: mode, rectangle / ellipse, 1:1; from center, snapping and the shortcuts on the More row. */
@Composable
internal fun marqueeStrip(tool: MarqueeTool, c: EditorController): StripSpec {
    val s = tool.settings
    var menu by remember(tool) { mutableStateOf(false) }
    return StripSpec(
        items = listOf(
            selectionModeItem(tool.mode) { tool.mode = it },
            StripItem("marquee.shape", if (s.shape == MarqueeShape.RECTANGLE) "Rectangle selection" else "Ellipse selection", 80.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ToolIconButton(
                        Icons.Outlined.Rectangle, "Rectangle selection",
                        onClick = { tool.settings = tool.settings.copy(shape = MarqueeShape.RECTANGLE) },
                        selected = s.shape == MarqueeShape.RECTANGLE, size = IconItemWidth,
                    )
                    ToolIconButton(
                        Icons.Outlined.Circle, "Ellipse selection",
                        onClick = { tool.settings = tool.settings.copy(shape = MarqueeShape.ELLIPSE) },
                        selected = s.shape == MarqueeShape.ELLIPSE, size = IconItemWidth,
                    )
                }
            },
            StripItem("marquee.square", "1:1", 56.dp) {
                StripToggle("1:1", s.square, 56.dp) { tool.settings = tool.settings.copy(square = it) }
            },
            StripItem("marquee.center", "From center", FROM_CENTER_DP.dp, row = StripRow.MORE) {
                StripToggle("From center", s.fromCenter, FROM_CENTER_DP.dp) { tool.settings = tool.settings.copy(fromCenter = it) }
            },
            snapItem("marquee.snap", c),
        ) + selectionShortcutItems(c) { menu = true },
        overlays = { SelectionMenuOverlay(c, menu) { menu = false } },
        movedInto = modesMovedInto,
    )
}

/**
 * Object select: mode, edge refinement and, while a selection is computed, its cancel button
 * (a spinner around the ✕; the room is kept so nothing jumps). The hint, the reference (v1.7
 * rule 5) and the shortcuts on the More row.
 */
@Composable
internal fun objectSelectStrip(tool: ObjectSelectTool, c: EditorController): StripSpec {
    val s = tool.settings
    val busy = tool.busy
    var menu by remember(tool) { mutableStateOf(false) }
    return StripSpec(
        items = listOf(
            selectionModeItem(tool.mode) { tool.mode = it },
            StripItem("object.refine", "Refine edges", 104.dp) {
                StripToggle("Refine edges", s.refineEdges, 104.dp) { tool.settings = tool.settings.copy(refineEdges = it) }
            },
            StripItem("object.cancel", "Cancel object select", IconItemWidth, shown = busy) {
                Box(contentAlignment = Alignment.Center) {
                    if (busy) CircularProgressIndicator(Modifier.size(34.dp), strokeWidth = 2.dp, color = BrushworkColors.Accent)
                    ToolIconButton(Icons.Filled.Close, "Cancel object select", onClick = { tool.cancel() }, size = IconItemWidth)
                }
            },
            StripItem("object.refer", "Refer: ${s.source.label}", 80.dp, row = StripRow.MORE) {
                StripChoiceChip("Refer", SampleSource.entries, s.source, { it.label }, 80.dp) { tool.settings = tool.settings.copy(source = it) }
            },
        ) + selectionShortcutItems(c) { menu = true },
        hint = if (busy) "Selecting…" else "Tap or scribble on an object",
        overlays = { SelectionMenuOverlay(c, menu) { menu = false } },
        movedInto = modesMovedInto,
    )
}

@Composable
internal fun colorSelectStrip(tool: ColorSelectTool, c: EditorController): StripSpec = StripSpec.EMPTY

/** The More row's app-wide "Snap to objects" (one name per tool, design §3.9). */
internal fun snapItem(key: String, c: EditorController): StripItem =
    StripItem(key, "Snap to objects", SNAP_DP.dp, row = StripRow.MORE) { SnapToObjectsChip(c, compact = false) }

/** Declared widths (dp) of the shared More-row controls, rounded up to 4 dp from their measured widths. */
internal const val ANTI_ALIAS_DP = 88
internal const val FROM_CENTER_DP = 96
internal const val SNAP_DP = 160
