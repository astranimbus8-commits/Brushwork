package com.brushwork.paint.ui.pathfinder

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brushwork.paint.tools.pathfinder.PathfinderTool
import com.brushwork.paint.ui.placement.StripMenu
import com.brushwork.paint.ui.placement.StripMenuButton
import com.brushwork.paint.ui.theme.BrushworkColors
import com.brushwork.paint.vector.pathfinder.PathfinderOp

/*
 * The Pathfinder tool's operations (v1.7 item 20, §3.20; v1.8 §3.9: one dropdown; area G): the
 * ten operations, the shape modes first, each shown with its icon and announced by its unique
 * description ("Unite shapes", I10). They need [MIN_OPERANDS] or more picked objects.
 */

/** At least this many objects for an operation. */
internal const val MIN_OPERANDS = 2

/**
 * The operation dropdown, [width] wide: the last operation used ([last]: its icon over its short
 * name, "Unite", and ▾), announced by that operation's description only. A tap opens the ten
 * operations, each its icon and description, enabled with [MIN_OPERANDS] picked objects while the
 * tool is idle; picking one closes the dropdown and runs it ([onPick]). While open the button
 * leaves the semantics, so each description belongs to its entry alone.
 */
@Composable
internal fun PathfinderOpsMenu(
    tool: PathfinderTool,
    last: PathfinderOp,
    width: Dp,
    open: Boolean,
    onOpen: (Boolean) -> Unit,
    onPick: (PathfinderOp) -> Unit,
) {
    val enabled = !tool.busy && tool.count >= MIN_OPERANDS
    Box {
        StripMenuButton(last.description, width, open, onClick = { onOpen(true) }, announceContent = false) {
            val tint = if (enabled) BrushworkColors.OnChrome else BrushworkColors.OnChromeDim
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(iconOf(last), contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                Text(last.label, color = tint, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible)
            }
        }
        StripMenu(open, onDismiss = { onOpen(false) }) {
            for (op in PathfinderOp.entries) {
                if (op == PathfinderOp.DIVIDE) Divider()
                OpEntry(op, enabled) {
                    onOpen(false)
                    onPick(op)
                }
            }
        }
    }
}

/** One operation in the dropdown: its icon and description (its only announced label). */
@Composable
private fun OpEntry(op: PathfinderOp, enabled: Boolean, onClick: () -> Unit) {
    val tint = if (enabled) BrushworkColors.OnChrome else BrushworkColors.OnChromeDim.copy(alpha = 0.5f)
    Row(
        Modifier
            .width(ENTRY_WIDTH)
            .heightIn(min = 44.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = op.description }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(iconOf(op), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(op.description, color = tint, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
    }
}

/** The dropdown's entries: wide enough for the longest description ("Minus front shape"). */
private val ENTRY_WIDTH = 220.dp

/** Between the shape modes and the pathfinders. */
@Composable
private fun Divider() {
    Box(Modifier.width(ENTRY_WIDTH).padding(vertical = 4.dp).height(1.dp).background(BrushworkColors.ChromeBorder))
}

/** An operation's icon, on the button and in the dropdown. */
internal fun iconOf(op: PathfinderOp): ImageVector = when (op) {
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
