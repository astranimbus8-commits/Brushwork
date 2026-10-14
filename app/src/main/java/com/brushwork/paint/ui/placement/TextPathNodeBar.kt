package com.brushwork.paint.ui.placement

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.ChangeHistory
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.brushwork.paint.tools.text.TextPathNodeEditor
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.ui.common.SheetBackground
import com.brushwork.paint.ui.common.TextLabels19
import com.brushwork.paint.ui.common.ToolIconButton
import com.brushwork.paint.ui.common.V19Tags
import com.brushwork.paint.ui.theme.BrushworkColors
import com.brushwork.paint.ui.theme.IbisDims
import com.brushwork.paint.ui.tools.strip.LocalStripContext
import java.util.Locale

/**
 * v1.9 (R12): the node bar of the Text tool's node editing ([TextTool.nodeEditor]), where the
 * selection bar sits: under the X / Y pill (which shows the open text), [TextPathNodeBar.HEIGHT_DP]
 * high, tag [V19Tags.TEXT_PATH_NODES]. "Add point" (after the selected node, at the end with
 * none), "Delete point" (the selected node; at least 2 stay), "Sharp corner" (the selected path
 * node's corner on or off), the count ([TextLabels19.POINT_COUNT]) and ✓
 * ([TextLabels19.DONE_POINTS], as the floating ✓ does while nodes are edited: back to the path
 * sheet). The hint ([TextLabels19.NODES_HINT]) is a caption under it.
 *
 * Composed by the strip's overlays, which sit at the top of the options strip's row 1: the bar
 * pads itself down to its place. Its buttons are 40 dp (TouchTargetAuditTest.MIN); its gaps don't
 * fall through to the canvas.
 */
@Composable
internal fun TextPathNodeBar(tool: TextTool) {
    val ed = tool.nodeEditor
    if (!ed.active || tool.item == null) return
    val strip = LocalStripContext.current
    // The pill shows the open text: folded, or without a scale, it has one row; else two.
    val pillRows = if (tool.controller.settings.coordinateStripFolded || tool.objectScale?.scalePercent == null) 1 else 2
    val top = TextPathNodeBar.topDp(pillRows, strip.selectionBarShown)
    Column(Modifier.fillMaxWidth().padding(top = top.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            color = SheetBackground,
            contentColor = BrushworkColors.OnChrome,
            shape = RoundedCornerShape(14.dp),
            shadowElevation = 4.dp,
            modifier = Modifier.testTag(V19Tags.TEXT_PATH_NODES).height(TextPathNodeBar.HEIGHT_DP.dp).pointerInput(Unit) {},
        ) {
            Row(
                Modifier.padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(TextPathNodeBar.GAP_DP.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val b = TextPathNodeBar.BUTTON_DP.dp
                ToolIconButton(Icons.Filled.AddCircleOutline, TextPathNodeEditor.ADD_POINT_STEP, onClick = { ed.addPoint() }, size = b)
                ToolIconButton(
                    Icons.Outlined.RemoveCircleOutline, TextPathNodeEditor.DELETE_POINT_STEP,
                    onClick = { ed.deletePoint() }, enabled = ed.canDelete, size = b,
                )
                ToolIconButton(
                    Icons.Filled.ChangeHistory, TextPathNodeEditor.SHARP_CORNER_STEP,
                    onClick = { ed.toggleSharp() }, enabled = ed.canSharp, selected = ed.selectedSharp, size = b,
                )
                Text(
                    String.format(Locale.ROOT, TextLabels19.POINT_COUNT, ed.count),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                ToolIconButton(Icons.Filled.Check, TextLabels19.DONE_POINTS, onClick = { tool.doneNodes() }, size = b)
            }
        }
        Text(
            TextLabels19.NODES_HINT,
            style = MaterialTheme.typography.bodySmall,
            color = BrushworkColors.OnChrome,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = 4.dp, start = 16.dp, end = 16.dp)
                .widthIn(max = TextPathNodeBar.HINT_MAX_DP.dp)
                .background(SheetBackground, RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** The node bar's measures and place. */
internal object TextPathNodeBar {
    /** Height of the bar (design R12). */
    const val HEIGHT_DP = 44f

    /** Its buttons (TouchTargetAuditTest.MIN) and the gaps between its cells. */
    const val BUTTON_DP = 40f
    const val GAP_DP = 2f

    /** The hint caption's widest line. */
    const val HINT_MAX_DP = 360f

    /** Gap between the pill and a bar under it, as the selection bar keeps (173 = 135 + 32 + 6). */
    private val PILL_GAP: Float get() = IbisDims.SelectionBarTopBelowPill.value - IbisDims.PillTop.value - IbisDims.PillHeight.value

    /**
     * The bar's top, in dp below the top of the options strip's row 1: where the selection bar
     * sits, under the X / Y pill of [pillRows] rows (0: at the pill's place), and under the
     * selection bar itself while that shows ([selectionBarShown]). The strip shows no More row
     * while nodes are edited, so nothing moves the pill down.
     */
    fun topDp(pillRows: Int, selectionBarShown: Boolean): Float {
        val stripTop = IbisDims.OptionsStripTop.value
        val bar = when {
            pillRows >= 2 -> IbisDims.SelectionBarTopBelowScaleRow.value
            pillRows == 1 -> IbisDims.SelectionBarTopBelowPill.value
            else -> IbisDims.PillTop.value
        }
        return bar - stripTop + if (selectionBarShown) IbisDims.SelectionBarHeight.value + PILL_GAP else 0f
    }
}
