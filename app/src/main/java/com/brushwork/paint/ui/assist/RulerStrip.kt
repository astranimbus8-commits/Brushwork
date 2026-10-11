package com.brushwork.paint.ui.assist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.brushwork.paint.assist.RulerGeometry
import com.brushwork.paint.assist.RulerTool
import com.brushwork.paint.model.RulerType
import com.brushwork.paint.ui.common.SnapToObjectsChip
import com.brushwork.paint.ui.common.ToolIconButton
import com.brushwork.paint.ui.tools.strip.LocalStripContext
import com.brushwork.paint.ui.tools.strip.StripItem
import com.brushwork.paint.ui.tools.strip.StripRow
import com.brushwork.paint.ui.tools.strip.StripSpec

/**
 * The Ruler tool's strip (v1.8, design §3.9 and §4.6; area D).
 *
 * Row 1: the four ruler-type buttons 172 ("Straight ruler", "Circular ruler", "Elliptical ruler",
 * "Radial ruler", 40 dp each, 4 dp apart) and "Done" 64 (240 dp: it fits 360 dp with the VECTOR
 * cell and the toggle). The More row: "Numbers" (the Ruler sheet, an overlay: it outlives the
 * row) and "Snap to objects" (v1.7's "Snap": §3.9 keeps one name per tool, the longer one).
 */
@Composable
internal fun rulerStrip(tool: RulerTool, c: EditorController): StripSpec {
    val strip = LocalStripContext.current
    var showNumbers by rememberSaveable { mutableStateOf(false) }
    val currentType by remember(c) { derivedStateOf { c.ruler.type } }
    val items = listOf(
        StripItem(RulerStripItems.TYPES, RulerStripItems.typeLabel(currentType), RulerStripItems.TYPES_DP.dp) { RulerTypeButtons(c) },
        StripItem(RulerStripItems.DONE, RulerStripItems.DONE_LABEL, RulerStripItems.DONE_DP.dp) {
            Button(
                onClick = { c.selectTool(c.lastPaintTool) },
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.width(RulerStripItems.DONE_DP.dp),
            ) { Text(RulerStripItems.DONE_LABEL, maxLines = 1) }
        },
        StripItem(RulerStripItems.NUMBERS, RulerStripItems.NUMBERS_LABEL, RulerStripItems.NUMBERS_DP.dp, row = StripRow.MORE) {
            TextButton(
                onClick = {
                    strip.closeMore()
                    showNumbers = true
                },
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.width(RulerStripItems.NUMBERS_DP.dp),
            ) {
                Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(RulerStripItems.NUMBERS_LABEL, maxLines = 1)
            }
        },
        StripItem(RulerStripItems.SNAP, RulerStripItems.SNAP_LABEL, RulerStripItems.SNAP_DP.dp, row = StripRow.MORE) {
            Box(Modifier.width(RulerStripItems.SNAP_DP.dp), contentAlignment = Alignment.CenterStart) { SnapToObjectsChip(c, compact = false) }
        },
    )
    return StripSpec(items, overlays = { if (showNumbers) RulerPanel(c, onDismiss = { showNumbers = false }) })
}

/** The ruler strip's item keys, widths and handles (the v1.7 labels). */
internal object RulerStripItems {
    const val TYPES = "ruler.types"
    const val DONE = "ruler.done"
    const val NUMBERS = "ruler.numbers"
    const val SNAP = "ruler.snap"

    /** Four 40 dp buttons, 4 dp apart. */
    const val TYPES_DP = 172f
    const val DONE_DP = 64f
    const val NUMBERS_DP = 112f
    const val SNAP_DP = 168f

    const val DONE_LABEL = "Done"
    const val NUMBERS_LABEL = "Numbers"
    const val SNAP_LABEL = "Snap to objects"

    fun typeLabel(type: RulerType) = "${type.label} ruler"
}

/** v1.7's four ruler-type buttons (the current one selected); a tap turns the ruler on with that type. */
@Composable
private fun RulerTypeButtons(c: EditorController) {
    // Only the type is shown here: don't recompose on every frame of an on-canvas drag.
    val currentType by remember(c) { derivedStateOf { c.ruler.type } }
    Row(
        Modifier.width(RulerStripItems.TYPES_DP.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (type in RulerType.entries) {
            ToolIconButton(
                icon = AssistIcons.of(type),
                contentDescription = RulerStripItems.typeLabel(type),
                onClick = {
                    val d = c.doc
                    c.updateRuler(RulerGeometry.resolved(c.ruler, d.width, d.height).copy(type = type, enabled = true))
                },
                selected = currentType == type,
                size = 40.dp,
            )
        }
    }
}
