package com.brushwork.paint.ui.pathfinder

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.pathfinder.PathfinderTool
import com.brushwork.paint.ui.common.PathfinderLabels
import com.brushwork.paint.ui.common.ToolIconButton
import com.brushwork.paint.ui.placement.StripStatusText
import com.brushwork.paint.ui.theme.BrushworkColors
import com.brushwork.paint.ui.tools.strip.StripItem
import com.brushwork.paint.ui.tools.strip.StripSpec
import com.brushwork.paint.vector.pathfinder.PathfinderOp

/**
 * The Pathfinder tool's strip (v1.8, design §3.9 and §4.6; area G).
 *
 * Row 1 only, no More row (no toggle): the operation dropdown (v1.7's ten operation buttons in one
 * dropdown, each still announced by its description; the button shows the last one used),
 * "Select all objects" and the status text: what to do next ("Tap shapes or paths to combine
 * them", also after a tap on nothing), how many objects are picked, or "Working…" with a spinner.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun pathfinderStrip(tool: PathfinderTool, c: EditorController): StripSpec {
    var last by rememberSaveable { mutableStateOf(PathfinderOp.UNITE) }
    var open by remember { mutableStateOf(false) }
    val items = listOf(
        StripItem(PathfinderStripItems.OPS, last.description, PathfinderStripItems.OPS_DP.dp) {
            Box(Modifier.testTag(PathfinderStripItems.OPS_TAG)) {
                PathfinderOpsMenu(tool, last, PathfinderStripItems.OPS_DP.dp, open, onOpen = { open = it }) { op ->
                    last = op
                    tool.apply(op)
                }
            }
        },
        StripItem(PathfinderStripItems.SELECT_ALL, PathfinderLabels.SELECT_ALL, PathfinderStripItems.SELECT_ALL_DP.dp) {
            ToolIconButton(
                Icons.Outlined.SelectAll,
                PathfinderLabels.SELECT_ALL,
                onClick = { tool.selectAll() },
                enabled = !tool.busy,
                size = PathfinderStripItems.SELECT_ALL_DP.dp,
            )
        },
        StripItem(PathfinderStripItems.STATUS, PathfinderStripItems.status(tool), PathfinderStripItems.STATUS_DP.dp) {
            StripStatusText(
                PathfinderStripItems.status(tool),
                PathfinderStripItems.STATUS_DP.dp,
                leading = if (tool.busy) ({ CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = BrushworkColors.Accent) }) else null,
            )
        },
    )
    return StripSpec(items, movedInto = PathfinderOp.entries.associate { it.description to PathfinderStripItems.OPS })
}

/** The Pathfinder strip's item keys, widths (measured, rounded up to 4 dp) and texts. */
internal object PathfinderStripItems {
    const val OPS = "pathfinder.ops"
    const val SELECT_ALL = "pathfinder.selectAll"
    const val STATUS = "pathfinder.status"

    /** The operation button: an icon over the longest short name ("Minus front"), and ▾. */
    const val OPS_DP = 92f
    const val SELECT_ALL_DP = 40f

    /** The status: the hint on two lines at 11 sp. */
    const val STATUS_DP = 140f

    /** The operation button's tag: tests reach it whatever operation it shows. */
    const val OPS_TAG = "v18g.pathfinder.ops"

    /** What row 1 says: "Working…", the hint, or how many objects are picked. */
    fun status(tool: PathfinderTool): String = when {
        tool.busy -> PathfinderTool.WORKING
        tool.count == 0 || tool.missed -> PathfinderLabels.HINT
        else -> PathfinderTool.picked(tool.count)
    }
}
