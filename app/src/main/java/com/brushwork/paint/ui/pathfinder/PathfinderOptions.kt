package com.brushwork.paint.ui.pathfinder

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Difference
import androidx.compose.material.icons.outlined.JoinFull
import androidx.compose.material.icons.outlined.JoinInner
import androidx.compose.material.icons.outlined.JoinLeft
import androidx.compose.material.icons.outlined.JoinRight
import androidx.compose.material.icons.outlined.Merge
import androidx.compose.material.icons.outlined.Polyline
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brushwork.paint.tools.pathfinder.PathfinderTool
import com.brushwork.paint.ui.common.PathfinderLabels
import com.brushwork.paint.ui.theme.BrushworkColors
import com.brushwork.paint.ui.theme.IbisDims
import com.brushwork.paint.ui.vector.ActionChip
import com.brushwork.paint.vector.pathfinder.PathfinderOp

/**
 * The Pathfinder tool's options strip (v1.7 item 20, §3.20; area G). Emits one Row, like every
 * tool's options; the options bar scrolls it sideways. First what to do next, so a narrow phone
 * shows it without scrolling: the hint "Tap shapes or paths to combine them" (also after a tap
 * on nothing), else how many objects are picked, or "Working…" with a spinner. Then "Select all
 * objects" and the ten operations as 56 dp wide icon-and-label buttons (as tall as the 44 dp
 * strip), the shape modes first: each
 * shows its short name ("Unite") and is announced by its unique description ("Unite shapes",
 * I10); they need 2 or more picked objects.
 */
@Composable
fun PathfinderOptions(tool: PathfinderTool) {
    val count = tool.count
    val busy = tool.busy
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        when {
            busy -> {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = BrushworkColors.Accent)
                Text(PathfinderTool.WORKING, style = MaterialTheme.typography.labelMedium, color = BrushworkColors.OnChrome)
            }
            count == 0 || tool.missed ->
                Text(PathfinderLabels.HINT, style = MaterialTheme.typography.labelMedium, color = BrushworkColors.OnChromeDim, maxLines = 2, modifier = Modifier.widthIn(max = 150.dp))
            else ->
                Text(PathfinderTool.picked(count), style = MaterialTheme.typography.labelMedium, color = BrushworkColors.OnChrome, maxLines = 1)
        }
        ActionChip(PathfinderLabels.SELECT_ALL, Icons.Outlined.SelectAll, enabled = !busy) { tool.selectAll() }
        Divider()
        for (op in PathfinderOp.entries) {
            if (op == PathfinderOp.DIVIDE) Divider()
            OpButton(op, enabled = !busy && count >= MIN_OPERANDS) { tool.apply(op) }
        }
    }
}

/** At least this many objects for an operation. */
private const val MIN_OPERANDS = 2

/**
 * One operation: its icon over its short name, announced by its description. At least
 * [OP_BUTTON_WIDTH] wide and as tall as the options strip ([IbisDims.OptionsStripHeight]: the
 * strip is the only host and clips to it), with the icon and a fixed line height that fit in it.
 */
@Composable
private fun OpButton(op: PathfinderOp, enabled: Boolean, onClick: () -> Unit) {
    val tint = if (enabled) BrushworkColors.OnChrome else BrushworkColors.OnChromeDim.copy(alpha = 0.5f)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .widthIn(min = OP_BUTTON_WIDTH)
            .height(IbisDims.OptionsStripHeight)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            // The short visible name is not announced: the unique description is (I10).
            .clearAndSetSemantics { contentDescription = op.description }
            .padding(horizontal = 4.dp),
    ) {
        Icon(iconOf(op), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Text(op.label, color = tint, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible)
    }
}

/** An operation button's least width: the design's 56 dp buttons (their height is the strip's 44 dp). */
private val OP_BUTTON_WIDTH = 56.dp

private fun iconOf(op: PathfinderOp): ImageVector = when (op) {
    PathfinderOp.UNITE -> Icons.Outlined.JoinFull
    PathfinderOp.MINUS_FRONT -> Icons.Outlined.JoinLeft
    PathfinderOp.MINUS_BACK -> Icons.Outlined.JoinRight
    PathfinderOp.INTERSECT -> Icons.Outlined.JoinInner
    PathfinderOp.EXCLUDE -> Icons.Outlined.Difference
    PathfinderOp.DIVIDE -> Icons.Outlined.Dashboard
    PathfinderOp.TRIM -> Icons.Outlined.ContentCut
    PathfinderOp.MERGE -> Icons.Outlined.Merge
    PathfinderOp.CROP -> Icons.Outlined.Crop
    PathfinderOp.OUTLINE -> Icons.Outlined.Polyline
}

@Composable
private fun Divider() {
    Box(Modifier.padding(horizontal = 2.dp).width(1.dp).height(24.dp).background(BrushworkColors.ChromeBorder))
}
