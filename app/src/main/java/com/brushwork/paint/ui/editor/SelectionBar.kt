package com.brushwork.paint.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.InvertColors
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.remove.ContentAwareFillJob
import com.brushwork.paint.ui.common.SheetBackground
import com.brushwork.paint.ui.remove.ContentAwareFillSheet
import com.brushwork.paint.ui.theme.BrushworkColors

/**
 * Floating actions for the current selection (whatever made it: marquee, lasso, magic wand,
 * smart select...): copy, cut, paste, deselect, delete the selected pixels, content-aware fill
 * (opens its options), invert and the full selection menu ([onMore]). With no selection but
 * something copied, it offers Paste only, and
 * [onHide] (non-null then) hides it until something new is copied.
 */
@Composable
internal fun SelectionActionBar(
    controller: EditorController,
    onMore: () -> Unit,
    onHide: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val hasSelection = controller.selection != null
    val canPaste = controller.clipboard != null
    // Content-aware fill options; Fill closes them (the busy overlay shows the progress).
    var fillOptionsOpen by remember { mutableStateOf(false) }
    if (fillOptionsOpen) ContentAwareFillSheet(controller, onDismiss = { fillOptionsOpen = false })
    Surface(
        color = SheetBackground,
        contentColor = BrushworkColors.OnChrome,
        shape = RoundedCornerShape(14.dp),
        shadowElevation = 4.dp,
        modifier = modifier,
    ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (hasSelection) {
                BarItem(Icons.Outlined.ContentCopy, "Copy", "Copy selection") {
                    controller.endCanvasGesture(); controller.copySelection()
                }
                BarItem(Icons.Outlined.ContentCut, "Cut", "Cut selection") {
                    controller.endCanvasGesture(); controller.cutSelection()
                }
            }
            BarItem(Icons.Outlined.ContentPaste, "Paste", "Paste as a new layer", enabled = canPaste) {
                controller.endCanvasGesture(); controller.paste()
            }
            if (hasSelection) {
                BarItem(Icons.Outlined.Deselect, "Deselect", "Clear the selection") { controller.endCanvasGesture(); controller.deselect() }
                BarItem(Icons.Outlined.DeleteSweep, "Delete", "Delete the selected pixels") {
                    controller.endCanvasGesture(); controller.clearLayer()
                }
                // Two short lines keep it as narrow as the others: eight items just fit a 392 dp phone.
                BarItem(Icons.Outlined.AutoFixHigh, "Content\naware", ContentAwareFillJob.FILL_LABEL, lines = 2) {
                    controller.endCanvasGesture(); fillOptionsOpen = true
                }
                BarItem(Icons.Outlined.InvertColors, "Invert", "Invert the selection") { controller.endCanvasGesture(); controller.invertSelection() }
            }
            BarItem(Icons.Outlined.MoreHoriz, "More", "Selection menu", onClick = onMore)
            if (onHide != null) BarItem(Icons.Outlined.Close, "Hide", "Hide the paste bar", onClick = onHide)
        }
    }
}

/** Icon over a short label; [clickLabel] tells a screen reader what it does. */
@Composable
private fun BarItem(icon: ImageVector, label: String, clickLabel: String, enabled: Boolean = true, lines: Int = 1, onClick: () -> Unit) {
    val tint = if (enabled) BrushworkColors.OnChrome else BrushworkColors.OnChromeDim.copy(alpha = 0.45f)
    Column(
        Modifier
            .width(46.dp)
            // Grows (rather than clipping) the two-line label under a large system font.
            .heightIn(min = 46.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClickLabel = clickLabel, role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, lineHeight = if (lines > 1) 11.sp else 12.sp, color = tint, maxLines = lines, textAlign = TextAlign.Center)
    }
}
