package com.brushwork.paint.ui.selection

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.select.LassoCommit
import com.brushwork.paint.tools.select.MagneticSettings
import com.brushwork.paint.tools.select.SampleSource
import com.brushwork.paint.tools.select.magnetic.MagneticLassoTool
import com.brushwork.paint.ui.common.MagneticLabels18
import com.brushwork.paint.ui.common.ToolIconButton
import com.brushwork.paint.ui.theme.BrushworkColors
import com.brushwork.paint.ui.tools.strip.StripItem
import com.brushwork.paint.ui.tools.strip.StripRow
import com.brushwork.paint.ui.tools.strip.StripSpec

// The Magnetic lasso's strip (v1.8, design §3.4 and §3.9, area C).

/** Item keys and declared widths (dp, rounded up to 4 dp from their measured widths). */
internal object MagneticStrip {
    const val DELETE = "magnetic.delete"
    const val DISCARD = "magnetic.discard"
    const val CLOSE = "magnetic.close"
    const val REACH = "magnetic.reach"
    const val CONTRAST = "magnetic.contrast"
    const val SNAP = "magnetic.snap"
    const val REFER = "magnetic.refer"
    const val ANTI_ALIAS = "magnetic.aa"

    const val REACH_DP = 72
    const val CONTRAST_DP = 88
    const val SNAP_DP = 96
    const val REFER_DP = 80
}

/**
 * Magnetic lasso: row 1 the mode dropdown, "Delete point" (the active anchor), the outline's
 * discard and close (with its point count); their room is kept while nothing is placed. The More
 * row: the snap's reach (8..64 dp) and the edges' contrast floor (0..50 %), snapping on / off,
 * Refer, anti-alias and the shortcuts. While a closed outline is turned into the selection, the
 * hint says so.
 */
@Composable
internal fun magneticLassoStrip(tool: MagneticLassoTool, c: EditorController): StripSpec {
    val s = tool.settings
    val count = tool.anchorCount
    val active = tool.activeAnchor
    var menu by remember(tool) { mutableStateOf(false) }
    fun set(next: MagneticSettings) = tool.updateSettings(next)
    val items = listOf(
        selectionModeItem(tool.mode) { tool.mode = it },
        StripItem(MagneticStrip.DELETE, "Delete point", IconItemWidth, shown = active in 0 until count) {
            StripIconAction(Icons.Outlined.Delete, "Delete point", tint = BrushworkColors.Danger) { tool.deleteActiveAnchor() }
        },
        StripItem(MagneticStrip.DISCARD, MagneticLabels18.DISCARD, IconItemWidth, shown = count > 0) {
            ToolIconButton(Icons.Filled.Close, MagneticLabels18.DISCARD, onClick = { tool.discardOutline() }, size = IconItemWidth)
        },
        StripItem(MagneticStrip.CLOSE, MagneticLabels18.CLOSE, IconItemWidth, shown = count > 0) {
            CountedCloseButton(MagneticLabels18.CLOSE, count) { tool.commit() }
        },
        StripItem(MagneticStrip.REACH, MagneticLabels18.EDGE_REACH, MagneticStrip.REACH_DP.dp, row = StripRow.MORE) {
            StripSliderChip(
                MagneticLabels18.EDGE_REACH, s.edgeReachDp, MagneticSettings.MIN_REACH_DP..MagneticSettings.MAX_REACH_DP,
                MagneticStrip.REACH_DP.dp, suffix = " dp", stateful = true,
            ) { set(tool.settings.copy(edgeReachDp = it)) }
        },
        StripItem(MagneticStrip.CONTRAST, MagneticLabels18.EDGE_CONTRAST, MagneticStrip.CONTRAST_DP.dp, row = StripRow.MORE) {
            StripSliderChip(
                MagneticLabels18.EDGE_CONTRAST, s.edgeContrast, 0..MagneticSettings.MAX_CONTRAST,
                MagneticStrip.CONTRAST_DP.dp, suffix = " %", stateful = true,
            ) { set(tool.settings.copy(edgeContrast = it)) }
        },
        StripItem(MagneticStrip.SNAP, MagneticLabels18.SNAP_TO_EDGE, MagneticStrip.SNAP_DP.dp, row = StripRow.MORE) {
            StripToggle(MagneticLabels18.SNAP_TO_EDGE, s.snapToEdge, MagneticStrip.SNAP_DP.dp) { set(tool.settings.copy(snapToEdge = it)) }
        },
        StripItem(MagneticStrip.REFER, "Refer: ${s.source.label}", MagneticStrip.REFER_DP.dp, row = StripRow.MORE) {
            StripChoiceChip("Refer", SampleSource.entries, s.source, { it.label }, MagneticStrip.REFER_DP.dp) { set(tool.settings.copy(source = it)) }
        },
        StripItem(MagneticStrip.ANTI_ALIAS, "Anti-alias", ANTI_ALIAS_DP.dp, row = StripRow.MORE) {
            StripToggle("Anti-alias", s.antiAlias, ANTI_ALIAS_DP.dp) { set(tool.settings.copy(antiAlias = it)) }
        },
    ) + selectionShortcutItems(c) { menu = true }
    return StripSpec(
        items = items,
        hint = if (tool.busy) LassoCommit.BUSY_LABEL else null,
        overlays = { SelectionMenuOverlay(c, menu) { menu = false } },
        movedInto = modesMovedInto,
    )
}
